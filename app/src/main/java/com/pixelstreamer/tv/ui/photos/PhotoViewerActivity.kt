package com.pixelstreamer.tv.ui.photos

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.pixelstreamer.tv.PixelStreamerApp
import com.pixelstreamer.tv.data.model.MediaItem
import com.pixelstreamer.tv.databinding.ActivityPhotoViewerBinding
import com.pixelstreamer.tv.util.loadUrlWithFallback
import java.text.DateFormat
import java.util.Date

class PhotoViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPhotoViewerBinding
    private val app by lazy { application as PixelStreamerApp }
    private val repo by lazy { app.repository }

    private var photos: List<MediaItem> = emptyList()
    private var currentIndex: Int = 0
    private var slideshowRunning = false
    private var overlayVisible = true

    private val handler = Handler(Looper.getMainLooper())
    private val slideTick = object : Runnable {
        override fun run() {
            if (slideshowRunning && photos.size > 1) {
                showPhoto((currentIndex + 1) % photos.size)
                handler.postDelayed(this, repo.slideshowSeconds * 1000L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPhotoViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val albumId = intent.getStringExtra(EXTRA_ALBUM_ID).orEmpty()
        val startId = intent.getStringExtra(EXTRA_START_ITEM_ID).orEmpty()

        val albumPhotos = repo.getAlbumItems(albumId).filter { !it.isVideo }
        photos = if (albumPhotos.isNotEmpty()) albumPhotos else repo.getVisibleItems().filter { !it.isVideo }
        if (photos.isEmpty()) {
            finish()
            return
        }

        currentIndex = photos.indexOfFirst { it.id == startId }.coerceAtLeast(0)
        showPhoto(currentIndex)

        binding.btnPhotoSlideshow.setOnClickListener {
            toggleSlideshow()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }

    private fun showPhoto(idx: Int) {
        currentIndex = idx
        val item = photos[idx]
        binding.photoFullImage.loadUrlWithFallback(
            primaryUrl = item.fullPhotoUrl(),
            fallbackUrl = item.thumbnailUrl(1920, 1080)
        )

        val dateStr = if (item.timestampMs > 0L) {
            DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(item.timestampMs))
        } else ""

        binding.photoCaption.text = item.caption.ifBlank { item.displayTitle }
        binding.photoCounter.text = buildString {
            append("${idx + 1} / ${photos.size}")
            if (dateStr.isNotBlank()) append("   •   $dateStr")
            append("   •   ◀ ▶ Navigate   •   OK Slideshow")
        }
    }

    private fun toggleSlideshow() {
        slideshowRunning = !slideshowRunning
        handler.removeCallbacks(slideTick)
        if (slideshowRunning) {
            binding.btnPhotoSlideshow.text = "⏸  Pause (${repo.slideshowSeconds}s)"
            handler.postDelayed(slideTick, repo.slideshowSeconds * 1000L)
        } else {
            binding.btnPhotoSlideshow.text = "▶  Slideshow"
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (photos.isNotEmpty()) {
                    showPhoto((currentIndex + 1) % photos.size)
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (photos.isNotEmpty()) {
                    showPhoto((currentIndex - 1 + photos.size) % photos.size)
                    return true
                }
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_DPAD_CENTER -> {
                toggleSlideshow()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                overlayVisible = !overlayVisible
                binding.photoOverlayBar.visibility = if (overlayVisible) View.VISIBLE else View.GONE
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        const val EXTRA_ALBUM_ID = "extra_album_id"
        const val EXTRA_START_ITEM_ID = "extra_start_item_id"
    }
}
