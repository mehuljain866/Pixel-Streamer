package com.pixelstreamer.tv.data.network

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.pixelstreamer.tv.data.model.MediaItem
import com.pixelstreamer.tv.data.model.VideoQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Maintains a warm CDN URL map in memory and a bounded 96 MB Media3 [SimpleCache] on disk
 * keyed stably by media item ID + quality so background pre-indexing survives expired Google URLs.
 */
@OptIn(UnstableApi::class)
class StreamPreIndexer(
    private val context: Context,
    private val httpClient: OkHttpClient
) {
    private data class WarmEntry(
        val info: ResolvedStreamInfo,
        val expiresAtMs: Long
    )

    private val warmUrls = ConcurrentHashMap<String, WarmEntry>()
    private val inFlightKeys = Collections.synchronizedSet(mutableSetOf<String>())
    private val cancelledKeys = Collections.synchronizedSet(mutableSetOf<String>())
    private val activeWriters = ConcurrentHashMap<String, CacheWriter>()
    private val prefetchSemaphore = Semaphore(2)

    fun cancelPrefetch(item: MediaItem, quality: VideoQuality) {
        val diskKey = cacheKeyFor(item, quality)
        if (diskKey in inFlightKeys) {
            cancelledKeys.add(diskKey)
        }
        activeWriters.remove(diskKey)?.cancel()
    }

    fun invalidateWarmUrl(item: MediaItem, quality: VideoQuality) {
        warmUrls.remove("${item.id}_${quality.name}")
    }

    private val upstreamFactory: OkHttpDataSource.Factory by lazy {
        OkHttpDataSource.Factory(httpClient)
            .setUserAgent(GooglePhotosAlbumScraper.DESKTOP_UA)
    }

    private val simpleCache: SimpleCache by lazy {
        val dir = File(context.cacheDir, "video_prefetch_cache")
        SimpleCache(
            dir,
            LeastRecentlyUsedCacheEvictor(MAX_CACHE_BYTES),
            StandaloneDatabaseProvider(context)
        )
    }

    fun buildCacheDataSourceFactory(): CacheDataSource.Factory {
        return CacheDataSource.Factory()
            .setCache(simpleCache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    suspend fun getOrResolveStreamInfo(
        item: MediaItem,
        quality: VideoQuality,
        scraper: GooglePhotosAlbumScraper
    ): ResolvedStreamInfo {
        val memKey = "${item.id}_${quality.name}"
        val now = System.currentTimeMillis()
        warmUrls[memKey]?.takeIf { it.expiresAtMs > now }?.let { return it.info }

        val resolved = scraper.resolveFreshStreamInfo(item, quality)
        warmUrls[memKey] = WarmEntry(
            info = resolved,
            expiresAtMs = now + WARM_URL_TTL_MS
        )
        return resolved
    }

    /**
     * Resolves fresh CDN tokens and caches the MP4 header, tail (for non-faststart moov atoms),
     * and an estimated resume window on disk before playback starts.
     */
    suspend fun preIndexItem(
        item: MediaItem,
        quality: VideoQuality,
        resumePosMs: Long,
        scraper: GooglePhotosAlbumScraper
    ) = withContext(Dispatchers.IO) {
        if (!item.isVideo) return@withContext
        val diskKey = cacheKeyFor(item, quality)
        if (!inFlightKeys.add(diskKey)) return@withContext

        try {
            prefetchSemaphore.withPermit {
                val info = getOrResolveStreamInfo(item, quality, scraper)
                if (!info.rangeSupported) return@withPermit

                val totalBytes = if (info.totalBytes > 0L) info.totalBytes else item.fileSizeBytes
                val windows = computePrefetchRanges(
                    totalBytes = totalBytes,
                    resumePosMs = resumePosMs,
                    durationMs = item.durationMs
                )
                val cacheDs = buildCacheDataSourceFactory().createDataSource()
                val uri = Uri.parse(info.url)
                for ((offset, len) in windows) {
                    if (diskKey in cancelledKeys) break
                    if (len <= 0L) continue
                    val spec = DataSpec.Builder()
                        .setUri(uri)
                        .setKey(diskKey)
                        .setPosition(offset)
                        .setLength(len)
                        .build()
                    val writer = CacheWriter(cacheDs, spec, null, null)
                    activeWriters[diskKey] = writer
                    runCatching {
                        if (diskKey in cancelledKeys) writer.cancel()
                        else writer.cache()
                    }
                    activeWriters.remove(diskKey, writer)
                }
            }
        } catch (_: Exception) {
        } finally {
            activeWriters.remove(diskKey)
            inFlightKeys.remove(diskKey)
            cancelledKeys.remove(diskKey)
        }
    }

    companion object {
        private const val MAX_CACHE_BYTES = 96L * 1024L * 1024L
        private const val WARM_URL_TTL_MS = 5L * 60L * 1000L

        fun cacheKeyFor(item: MediaItem, quality: VideoQuality): String {
            return "ps_${item.id}_${quality.name}_${item.baseUrl.hashCode()}"
        }

        /**
         * Computes non-overlapping byte ranges (offset, length) to pre-index:
         * - Head bytes for container / faststart moov
         * - Tail bytes for non-faststart MP4 moov atoms stored at EOF
         * - Estimated resume byte window when resuming mid-video
         */
        fun computePrefetchRanges(
            totalBytes: Long,
            resumePosMs: Long,
            durationMs: Long
        ): List<Pair<Long, Long>> {
            if (totalBytes <= 0L) {
                return listOf(0L to 512L * 1024L)
            }

            val headLen = if (resumePosMs <= 15_000L || durationMs <= 0L) {
                minOf(768L * 1024L, totalBytes)
            } else {
                minOf(256L * 1024L, totalBytes)
            }
            val ranges = mutableListOf(0L to headLen)

            val tailSize = 512L * 1024L
            val tailStart = totalBytes - tailSize
            if (totalBytes > 2L * 1024L * 1024L && tailStart > headLen) {
                ranges.add(tailStart to tailSize)
            }

            if (resumePosMs > 15_000L && durationMs > 0L) {
                val ratio = (resumePosMs.toDouble() / durationMs.toDouble()).coerceIn(0.0, 0.99)
                val approxOffset = (ratio * totalBytes.toDouble()).toLong()
                val winStart = (approxOffset - 64L * 1024L).coerceAtLeast(headLen)
                val maxEnd = if (totalBytes > 2L * 1024L * 1024L) tailStart else totalBytes
                if (winStart < maxEnd) {
                    val winLen = minOf(768L * 1024L, maxEnd - winStart)
                    if (winLen > 0L) {
                        ranges.add(1, winStart to winLen)
                    }
                }
            }

            return ranges
        }
    }
}
