package com.pixelstreamer.tv.data.network

import com.pixelstreamer.tv.data.model.MediaItem
import com.pixelstreamer.tv.data.model.VideoQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder

data class ScrapedAlbumResult(
    val resolvedUrl: String,
    val shareId: String,
    val albumKey: String,
    val albumTitle: String,
    val items: List<MediaItem>
)

data class ItemDetailResult(
    val caption: String,
    val availableItags: List<Int>,
    val durationMs: Long,
    val streamUrlsByItag: Map<Int, String> = emptyMap()
)

data class ResolvedStreamInfo(
    val url: String,
    val totalBytes: Long = -1L,
    val rangeSupported: Boolean = false
)

class GooglePhotosAlbumScraper(private val httpClient: OkHttpClient) {

    suspend fun fetchAlbum(
        rawInputUrl: String,
        cachedItemsById: Map<String, MediaItem> = emptyMap()
    ): ScrapedAlbumResult = withContext(Dispatchers.IO) {
        val targetUrl = extractUrlFromText(rawInputUrl)
            ?: throw IllegalArgumentException("Please enter a valid Google Photos shared link.")

        val req = Request.Builder()
            .url(targetUrl)
            .header("User-Agent", DESKTOP_UA)
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()

        val (finalUrl, html) = httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("Google Photos returned HTTP ${resp.code}")
            }
            resp.request.url.toString() to (resp.body?.string() ?: "")
        }

        val parsed = parseAlbumHtml(finalUrl, html)

        // Enrich uncached videos with original filename (HEAD =dv); skip cached items and redundant /photo/ HTML fetches.
        val enriched = enrichItems(parsed.items, parsed.shareId, parsed.albumKey, cachedItemsById)
        parsed.copy(items = enriched)
    }

    /**
     * Pure parser exposed for unit testing without network calls.
     */
    fun parseAlbumHtml(finalUrl: String, html: String): ScrapedAlbumResult {
        val (shareId, albumKey) = parseShareIdAndKey(finalUrl)
        val ds1Json = extractCallbackData(html, "ds:1")
            ?: throw IllegalStateException("Could not read shared album data. Make sure link sharing is enabled.")

        val root = JSONArray(ds1Json)
        val albumMeta = root.optJSONArray(3)
        val resolvedShareId = albumMeta?.optString(0)?.takeIf { it.isNotBlank() } ?: shareId
        val albumTitle = albumMeta?.optString(1)?.takeIf { it.isNotBlank() } ?: "Shared Album"

        val rawItems = root.optJSONArray(1) ?: JSONArray()
        val items = mutableListOf<MediaItem>()

        for (i in 0 until rawItems.length()) {
            val itemArr = rawItems.optJSONArray(i) ?: continue
            val itemId = itemArr.optString(0).takeIf { it.isNotBlank() } ?: continue
            val mediaInfo = itemArr.optJSONArray(1) ?: continue
            val baseUrl = mediaInfo.optString(0).takeIf { it.startsWith("http") } ?: continue
            val width = mediaInfo.optInt(1, 1920)
            val height = mediaInfo.optInt(2, 1080)
            val timestampMs = itemArr.optLong(2, 0L)

            val metaObj = findItemMetaObject(itemArr)
            val videoBlock = metaObj?.optJSONArray(VIDEO_EXT_KEY)
            val isVideo = videoBlock != null
            val durationMs = videoBlock?.optLong(0, 0L) ?: 0L
            val caption = metaObj?.optJSONArray(CAPTION_EXT_KEY)?.optString(0)?.trim().orEmpty()
            val itags = extractAvailableItags(videoBlock)

            items.add(
                MediaItem(
                    id = itemId,
                    albumId = "${resolvedShareId}_${albumKey}".trimEnd('_'),
                    albumShareId = resolvedShareId,
                    albumKey = albumKey,
                    baseUrl = baseUrl,
                    width = width,
                    height = height,
                    timestampMs = timestampMs,
                    isVideo = isVideo,
                    durationMs = durationMs,
                    caption = caption,
                    availableItags = itags
                )
            )
        }

        return ScrapedAlbumResult(
            resolvedUrl = finalUrl,
            shareId = resolvedShareId,
            albumKey = albumKey,
            albumTitle = albumTitle,
            items = items
        )
    }

    fun parsePhotoPageHtml(html: String): ItemDetailResult? {
        val ds1Json = extractCallbackData(html, "ds:1") ?: return null
        val root = JSONArray(ds1Json)
        val itemArr = root.optJSONArray(0) ?: return null
        val metaObj = findItemMetaObject(itemArr) ?: return null
        val caption = metaObj.optJSONArray(CAPTION_EXT_KEY)?.optString(0)?.trim().orEmpty()
        val videoBlock = metaObj.optJSONArray(VIDEO_EXT_KEY)
        val durationMs = videoBlock?.optLong(0, 0L) ?: 0L
        val streamUrls = extractSignedStreamUrls(html)
        val itags = (extractAvailableItags(videoBlock) + streamUrls.keys).distinct()
        return ItemDetailResult(
            caption = caption,
            availableItags = itags,
            durationMs = durationMs,
            streamUrlsByItag = streamUrls
        )
    }

    fun extractSignedStreamUrls(html: String): Map<Int, String> {
        val map = mutableMapOf<Int, String>()
        val cleaned = html
            .replace("\\u003d", "=")
            .replace("\\u0026", "&")
            .replace("\\/", "/")
        val urlRegex = Regex("""https://video-downloads\.googleusercontent\.com/[^\s"'<>\\|]+""")
        for (m in urlRegex.findAll(cleaned)) {
            val url = m.value.trimEnd(',', ';')
            val itag = Regex("""[?&]itag=(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull()
                ?: continue
            if (!map.containsKey(itag)) {
                map[itag] = url
            }
        }
        return map
    }

    private suspend fun enrichItems(
        items: List<MediaItem>,
        shareId: String,
        albumKey: String,
        cachedItemsById: Map<String, MediaItem>
    ): List<MediaItem> = coroutineScope {
        val semaphore = Semaphore(10)
        items.map { item ->
            async {
                if (!item.isVideo) {
                    return@async item
                }
                val cached = cachedItemsById[item.id]
                if (cached != null && cached.originalFilename.isNotBlank()) {
                    return@async mergeCachedVideoFields(item, cached)
                }
                semaphore.withPermit {
                    val (filename, sizeBytes) = fetchFilenameAndSize("${item.baseUrl}=dv")
                    // Only fetch full /photo/<id> HTML if both caption & filename are missing or duration wasn't in album data.
                    val needDetail = (filename.isBlank() && item.caption.isBlank()) || item.durationMs <= 0L
                    val detail = if (needDetail) fetchItemDetail(shareId, albumKey, item.id) else null
                    item.copy(
                        originalFilename = filename.ifBlank { item.originalFilename },
                        fileSizeBytes = if (sizeBytes > 0L) sizeBytes else item.fileSizeBytes,
                        caption = detail?.caption?.ifBlank { item.caption } ?: item.caption,
                        availableItags = detail?.availableItags?.takeIf { it.isNotEmpty() } ?: item.availableItags,
                        durationMs = detail?.durationMs?.takeIf { it > 0L } ?: item.durationMs
                    )
                }
            }
        }.awaitAll()
    }

    fun mergeCachedVideoFields(fresh: MediaItem, cached: MediaItem): MediaItem {
        return fresh.copy(
            originalFilename = cached.originalFilename.ifBlank { fresh.originalFilename },
            fileSizeBytes = if (cached.fileSizeBytes > 0L) cached.fileSizeBytes else fresh.fileSizeBytes,
            caption = fresh.caption.ifBlank { cached.caption },
            availableItags = fresh.availableItags.ifEmpty { cached.availableItags },
            durationMs = if (fresh.durationMs > 0L) fresh.durationMs else cached.durationMs
        )
    }

    suspend fun fetchItemDetail(
        shareId: String,
        albumKey: String,
        itemId: String
    ): ItemDetailResult? = withContext(Dispatchers.IO) {
        if (shareId.isBlank() || itemId.isBlank()) return@withContext null
        val keyParam = if (albumKey.isNotBlank()) "?key=$albumKey" else ""
        val url = "https://photos.google.com/share/$shareId/photo/$itemId$keyParam"
        try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", DESKTOP_UA)
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val html = resp.body?.string() ?: return@withContext null
                parsePhotoPageHtml(html)
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Resolves a fresh playable high-definition MP4 URL and its byte-range metadata.
     * For AUTO / ORIGINAL, checks the range-seekable original file (=dv) first so 1080p/720p
     * movies play in full quality without Google Photos proxy compression or =m22 hangs.
     */
    suspend fun resolveFreshStreamInfo(
        item: MediaItem,
        preferredQuality: VideoQuality
    ): ResolvedStreamInfo = withContext(Dispatchers.IO) {
        val dvUrl = "${item.baseUrl}=dv"
        if (preferredQuality == VideoQuality.ORIGINAL) {
            return@withContext probeStreamInfo(dvUrl)
                ?: ResolvedStreamInfo(url = dvUrl, totalBytes = item.fileSizeBytes, rangeSupported = true)
        }

        // Fast path for AUTO: probe =dv immediately without waiting on /photo/ HTML fetch.
        if (preferredQuality == VideoQuality.AUTO) {
            val dvInfo = probeStreamInfo(dvUrl)
            if (dvInfo != null && dvInfo.rangeSupported) {
                return@withContext dvInfo
            }
        }

        val detail = fetchItemDetail(item.albumShareId, item.albumKey, item.id)
        val available = detail?.availableItags?.takeIf { it.isNotEmpty() } ?: item.availableItags
        val signedUrls = detail?.streamUrlsByItag.orEmpty()

        val candidates = buildCandidateUrls(
            item = item,
            available = available,
            signedUrls = signedUrls,
            preferred = preferredQuality
        )
        for (candidate in candidates) {
            val probed = probeStreamInfo(candidate)
            if (probed != null) {
                return@withContext probed
            }
        }
        ResolvedStreamInfo(url = dvUrl, totalBytes = item.fileSizeBytes, rangeSupported = true)
    }

    suspend fun resolveFreshStreamUrl(
        item: MediaItem,
        preferredQuality: VideoQuality
    ): String = resolveFreshStreamInfo(item, preferredQuality).url

    internal fun buildCandidateUrls(
        item: MediaItem,
        available: List<Int>,
        signedUrls: Map<Int, String>,
        preferred: VideoQuality
    ): List<String> {
        val dvUrl = "${item.baseUrl}=dv"
        val out = mutableListOf<String>()

        fun addItags(itags: List<Int>) {
            val filtered = if (available.isNotEmpty()) {
                itags.filter { it in available }.ifEmpty { itags }
            } else {
                itags
            }
            for (tag in filtered.distinct()) {
                signedUrls[tag]?.let { out.add(it) }
                out.add("${item.baseUrl}=m$tag")
            }
        }

        when (preferred) {
            VideoQuality.AUTO -> {
                addItags(listOf(37, 22))
                out.add(dvUrl)
                addItags(listOf(18, 36))
            }
            VideoQuality.Q1080P -> {
                addItags(listOf(37))
                if (item.supports1080p) {
                    // Never silently drop to 360p when 1080p proxy is 404 and Original is 1080p+
                    out.add(dvUrl)
                }
                addItags(listOf(22, 18, 36))
            }
            VideoQuality.Q720P -> {
                addItags(listOf(22))
                if (!item.supports1080p && item.supports720p) {
                    out.add(dvUrl)
                }
                addItags(listOf(18, 36))
                out.add(dvUrl)
            }
            VideoQuality.Q360P -> {
                addItags(listOf(18, 36))
                out.add(dvUrl)
            }
            VideoQuality.ORIGINAL -> out.add(dvUrl)
        }
        return out.distinct()
    }

    private fun probeStreamInfo(url: String): ResolvedStreamInfo? {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", DESKTOP_UA)
                .header("Range", "bytes=0-1023")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.code != 200 && resp.code != 206) return null
                val finalUrl = resp.request.url.toString()
                val rangeSupported = resp.code == 206 ||
                    resp.header("Accept-Ranges").equals("bytes", ignoreCase = true)
                val totalBytes = if (resp.code == 206) {
                    val cr = resp.header("Content-Range").orEmpty()
                    Regex("""bytes\s+\d+-\d+/(\d+)""").find(cr)?.groupValues?.get(1)?.toLongOrNull() ?: -1L
                } else {
                    resp.header("Content-Length")?.toLongOrNull() ?: -1L
                }
                ResolvedStreamInfo(
                    url = selectReusableStreamUrl(url, finalUrl),
                    totalBytes = totalBytes,
                    rangeSupported = rangeSupported
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchFilenameAndSize(url: String): Pair<String, Long> {
        return try {
            val req = Request.Builder()
                .url(url)
                .head()
                .header("User-Agent", DESKTOP_UA)
                .build()
            httpClient.newCall(req).execute().use { resp ->
                val cd = resp.header("Content-Disposition").orEmpty()
                val size = resp.header("Content-Length")?.toLongOrNull() ?: 0L
                parseContentDispositionFilename(cd) to size
            }
        } catch (_: Exception) {
            "" to 0L
        }
    }

    private fun parseContentDispositionFilename(header: String): String {
        if (header.isBlank()) return ""
        val utf8Match = Regex("""filename\*\s*=\s*UTF-8''([^;]+)""", RegexOption.IGNORE_CASE).find(header)
        if (utf8Match != null) {
            return URLDecoder.decode(utf8Match.groupValues[1].trim().trim('"'), "UTF-8")
        }
        val plainMatch = Regex("""filename\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE).find(header)
            ?: Regex("""filename\s*=\s*([^;\s]+)""", RegexOption.IGNORE_CASE).find(header)
        return plainMatch?.groupValues?.getOrNull(1)?.trim()?.trim('"').orEmpty()
    }

    private fun findItemMetaObject(itemArr: JSONArray): JSONObject? {
        // Google places the metadata dict at index 9 or 10 depending on item flags.
        for (idx in 0 until itemArr.length()) {
            val obj = itemArr.optJSONObject(idx)
            if (obj != null) return obj
        }
        return null
    }

    private fun extractAvailableItags(videoBlock: JSONArray?): List<Int> {
        if (videoBlock == null) return emptyList()
        val formats = videoBlock.optJSONArray(7) ?: return emptyList()
        val tags = mutableListOf<Int>()
        for (i in 0 until formats.length()) {
            val entry = formats.optJSONArray(i) ?: continue
            val tag = entry.optInt(0, -1)
            if (tag > 0) tags.add(tag)
        }
        return tags
    }

    /**
     * Extracts the JSON array payload passed to `data:` inside `AF_initDataCallback({key: '<key>', ...})`.
     */
    fun extractCallbackData(html: String, key: String): String? {
        val keyRegex = Regex("""AF_initDataCallback\(\{\s*key:\s*['"]${Regex.escape(key)}['"]""")
        for (match in keyRegex.findAll(html)) {
            val dataIdx = html.indexOf("data:", match.range.last)
            if (dataIdx < 0 || dataIdx - match.range.last > 120) continue
            var start = dataIdx + 5
            while (start < html.length && html[start].isWhitespace()) start++
            if (start >= html.length || html[start] != '[') continue
            val extracted = scanJsonArray(html, start) ?: continue
            return extracted
        }
        return null
    }

    private fun scanJsonArray(src: String, startIdx: Int): String? {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in startIdx until src.length) {
            val c = src[i]
            if (escaped) {
                escaped = false
                continue
            }
            if (inString) {
                if (c == '\\') {
                    escaped = true
                } else if (c == '"') {
                    inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) {
                        return src.substring(startIdx, i + 1)
                    }
                }
            }
        }
        return null
    }

    private fun parseShareIdAndKey(url: String): Pair<String, String> {
        val shareMatch = Regex("""/share/([^/?#]+)""").find(url)
        val shareId = shareMatch?.groupValues?.getOrNull(1).orEmpty()
        val keyMatch = Regex("""[?&]key=([^&#]+)""").find(url)
        val key = keyMatch?.groupValues?.getOrNull(1).orEmpty()
        return shareId to key
    }

    fun extractUrlFromText(raw: String): String? {
        val match = Regex("""https?://[^\s<>"']+""").find(raw.trim())
        return match?.value?.trimEnd('.', ',', ')', ']')
    }

    companion object {
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        private const val VIDEO_EXT_KEY = "76647426"
        private const val CAPTION_EXT_KEY = "396644657"

        /**
         * Avoid pinning short-lived (~10-15 min) `video-downloads.googleusercontent.com` redirect URLs
         * when the permanent `lh3.googleusercontent.com` URL works, so OkHttp obtains a fresh token
         * automatically whenever ExoPlayer reconnects or seeks late into a movie.
         */
        fun selectReusableStreamUrl(requestedUrl: String, finalUrl: String): String {
            return if (
                requestedUrl.startsWith("https://lh3.googleusercontent.com/") &&
                finalUrl.contains("video-downloads.googleusercontent.com")
            ) {
                requestedUrl
            } else {
                finalUrl
            }
        }
    }
}
