package com.pixelstreamer.tv.ui.photos

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.pixelstreamer.tv.BuildConfig
import com.pixelstreamer.tv.PixelStreamerApp
import kotlin.math.abs
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
        binding.btnPhotoBack.setOnClickListener {
            finish()
        }
        binding.btnPhotoPrev.setOnClickListener {
            if (photos.isNotEmpty()) showPhoto((currentIndex - 1 + photos.size) % photos.size)
        }
        binding.btnPhotoNext.setOnClickListener {
            if (photos.isNotEmpty()) showPhoto((currentIndex + 1) % photos.size)
        }

        val photoGestureDetector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    togglePhotoOverlay()
                    return true
                }

                override fun onFling(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    velocityX: Float,
                    velocityY: Float
                ): Boolean {
                    if (e1 == null || photos.isEmpty()) return false
                    val dx = e2.x - e1.x
                    val dy = e2.y - e1.y
                    if (abs(dx) > abs(dy) && abs(dx) > 80f) {
                        val nextIdx = if (dx < 0) {
                            (currentIndex + 1) % photos.size
                        } else {
                            (currentIndex - 1 + photos.size) % photos.size
                        }
                        showPhoto(nextIdx)
                        return true
                    }
                    return false
                }
            }
        )
        binding.photoFullImage.setOnTouchListener { _, event ->
            photoGestureDetector.onTouchEvent(event)
            true
        }
    }

    private fun togglePhotoOverlay() {
        overlayVisible = !overlayVisible
        val vis = if (overlayVisible) View.VISIBLE else View.GONE
        binding.photoOverlayBar.visibility = vis
        binding.btnPhotoBack.visibility = vis
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
            append(
                if (BuildConfig.IS_TABLET) "   •   Swipe or tap ‹ ›"
                else "   •   ◀ ▶ Navigate   •   OK Slideshow"
            )
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
                togglePhotoOverlay()
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
