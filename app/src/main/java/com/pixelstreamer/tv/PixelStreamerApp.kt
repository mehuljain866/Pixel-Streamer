package com.pixelstreamer.tv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.pixelstreamer.tv.data.network.GooglePhotosAlbumScraper
import com.pixelstreamer.tv.data.network.MovieMetadataResolver
import com.pixelstreamer.tv.data.network.ResilientGoogleDns
import com.pixelstreamer.tv.data.network.StreamPreIndexer
import com.pixelstreamer.tv.data.repo.MediaRepository
import com.pixelstreamer.tv.server.CompanionWebServer
import okhttp3.OkHttpClient
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.TimeUnit

class PixelStreamerApp : Application(), ImageLoaderFactory {

    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(ResilientGoogleDns())
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    val scraper: GooglePhotosAlbumScraper by lazy {
        GooglePhotosAlbumScraper(httpClient)
    }

    val metadataResolver: MovieMetadataResolver by lazy {
        MovieMetadataResolver(httpClient)
    }

    val preIndexer: StreamPreIndexer by lazy {
        StreamPreIndexer(this, httpClient)
    }

    val repository: MediaRepository by lazy {
        MediaRepository(this, scraper, metadataResolver, preIndexer)
    }

    private val companionServer by lazy { CompanionWebServer(repository) }
    private var serverRefCount = 0

    override fun onCreate() {
        super.onCreate()
        val prevHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                File(filesDir, "last_crash.txt").writeText("${thread.name}\n$sw")
            } catch (_: Exception) {
            }
            prevHandler?.uncaughtException(thread, throwable)
        }
    }

    @Synchronized
    fun acquireCompanionServer(): String? {
        serverRefCount++
        return companionServer.start()
    }

    @Synchronized
    fun releaseCompanionServer() {
        serverRefCount = (serverRefCount - 1).coerceAtLeast(0)
        if (serverRefCount == 0) {
            companionServer.stop()
        }
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .okHttpClient(httpClient)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.18)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("coil_cache"))
                    .maxSizeBytes(64L * 1024L * 1024L)
                    .build()
            }
            .crossfade(220)
            .build()
    }
}
