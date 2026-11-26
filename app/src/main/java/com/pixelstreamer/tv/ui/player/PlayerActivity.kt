package com.pixelstreamer.tv.ui.player

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import coil.load
import com.pixelstreamer.tv.PixelStreamerApp
import com.pixelstreamer.tv.R
import com.pixelstreamer.tv.data.model.MediaItem
import com.pixelstreamer.tv.data.model.VideoQuality
import com.pixelstreamer.tv.data.network.GooglePhotosAlbumScraper
import com.pixelstreamer.tv.data.network.StreamPreIndexer
import com.pixelstreamer.tv.databinding.ActivityPlayerBinding
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private val app by lazy { application as PixelStreamerApp }
    private val repo by lazy { app.repository }

    private var player: ExoPlayer? = null
    private var currentItem: MediaItem? = null
    private var resumePositionMs: Long = 0L
    private var currentQuality: VideoQuality = VideoQuality.AUTO
    private var playbackSpeed: Float = 1.0f
    private var fillScreen: Boolean = false

    private var isScrubbing = false
    private var triedOriginalFallback = false
    private var hasStartedPlayback = false

    private var pendingSeekTargetMs: Long = -1L
    private var isAwaitingSeekReady = false
    private var expectedSeekPosMs: Long = -1L
    private var seekAwaitDeadlineMs: Long = 0L
    private var playingVideoWidth: Int = 0
    private var playingVideoHeight: Int = 0
    private var baseHudTitle: String = ""

    private val handler = Handler(Looper.getMainLooper())
    private val autoHideControlsRunnable = Runnable {
        if (player?.isPlaying == true && binding.settingsPopupOverlay.visibility != View.VISIBLE) {
            binding.controlsOverlay.visibility = View.GONE
        }
    }
    private val commitSeekRunnable = Runnable { commitPendingSeek() }
    private val progressTickRunnable = object : Runnable {
        override fun run() {
            updateTimeUi()
            persistProgress()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val itemId = intent.getStringExtra(EXTRA_ITEM_ID).orEmpty()
        resumePositionMs = intent.getLongExtra(EXTRA_RESUME_MS, 0L)
        val qName = intent.getStringExtra(EXTRA_QUALITY)
        val requestedQuality = VideoQuality.entries.firstOrNull { it.name == qName } ?: repo.defaultQuality

        val item = repo.getItemById(itemId)
        if (item == null) {
            finish()
            return
        }
        currentItem = item
        currentQuality = item.normalizeQuality(requestedQuality)

        bindPauseMetadata(item)
        setupControls()
        initPlayerAndStart(item, resumePositionMs)
    }

    override fun onPause() {
        super.onPause()
        persistProgress()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        persistProgress()
        binding.playerView.player = null
        player?.release()
        player = null
    }

    private fun bindPauseMetadata(item: MediaItem) {
        val meta = item.movieMetadata
        val titleText = if (item.isEpisode) {
            val code = item.episodeCode
            val epTitle = item.episodeTitle.takeIf { it.isNotBlank() }
            if (epTitle != null) "${item.seriesDisplayTitle} · $code: $epTitle"
            else item.displayTitle
        } else {
            item.displayTitle
        }
        baseHudTitle = titleText
        playingVideoWidth = 0
        playingVideoHeight = 0
        binding.pauseMovieTitle.text = titleText
        binding.hudBarTitle.text = titleText

        // Load official transparent movie/show PNG Title Logo (e.g. Interstellar typography)
        val logoUrl = item.titleLogoUrl
        if (!logoUrl.isNullOrBlank() && !item.isEpisode) {
            binding.pauseTitleLogo.visibility = View.GONE
            binding.pauseMovieTitle.visibility = View.VISIBLE
            binding.pauseTitleLogo.load(logoUrl) {
                crossfade(true)
                listener(
                    onSuccess = { _, _ ->
                        binding.pauseTitleLogo.visibility = View.VISIBLE
                        binding.pauseMovieTitle.visibility = View.GONE
                    },
                    onError = { _, _ ->
                        binding.pauseTitleLogo.visibility = View.GONE
                        binding.pauseMovieTitle.visibility = View.VISIBLE
                    }
                )
            }
        } else {
            binding.pauseTitleLogo.visibility = View.GONE
            binding.pauseMovieTitle.visibility = View.VISIBLE
        }

        val parts = mutableListOf<String>()
        meta?.year?.let { parts.add(it.toString()) }
        if (item.displayDuration.isNotBlank()) parts.add(item.displayDuration)
        meta?.rating?.let { parts.add("★ ${"%.1f".format(it)}") }
        binding.pauseMovieMeta.text = parts.joinToString("  ·  ")

        val ratingBadge = meta?.contentRating?.trim().orEmpty()
        if (ratingBadge.isNotBlank()) {
            binding.pauseContentRating.text = ratingBadge
            binding.pauseContentRating.visibility = View.VISIBLE
        } else {
            binding.pauseContentRating.visibility = View.GONE
        }

        val overview = when {
            item.isEpisode && item.episodeOverview.isNotBlank() -> item.episodeOverview
            meta?.overview?.isNotBlank() == true -> meta.overview
            item.caption.isNotBlank() && !item.caption.contains("_1080p", true) && !item.caption.endsWith(".mp4", true) -> item.caption
            else -> ""
        }
        binding.pauseMovieOverview.text = overview
        binding.pauseMovieOverview.visibility = if (overview.isBlank()) View.GONE else View.VISIBLE

        binding.btnPauseNext.visibility = if (repo.getNextChronologicalVideo(item) != null) View.VISIBLE else View.GONE
    }

    private fun setupControls() {
        binding.btnPauseResume.setOnClickListener {
            val exo = player ?: return@setOnClickListener
            if (exo.isPlaying) {
                exo.pause()
            } else {
                exo.play()
                hideAllOverlays()
            }
        }

        binding.btnPauseRew10.setOnClickListener {
            performImmediateSeekDelta(-10_000L)
            scheduleControlsAutoHide()
        }

        binding.btnPauseFwd10.setOnClickListener {
            performImmediateSeekDelta(10_000L)
            scheduleControlsAutoHide()
        }

        binding.btnPauseSettings.setOnClickListener {
            openSettingsPopup()
        }

        binding.settingsPopupOverlay.setOnClickListener {
            closeSettingsPopup()
        }

        binding.btnPauseNext.setOnClickListener {
            val cur = currentItem ?: return@setOnClickListener
            val next = repo.getNextChronologicalVideo(cur) ?: return@setOnClickListener
            persistProgress()
            currentItem = next
            resumePositionMs = 0L
            triedOriginalFallback = false
            bindPauseMetadata(next)
            loadStream(next, 0L)
        }

        binding.btnPauseBack.setOnClickListener {
            finish()
        }

        binding.pauseSeekBar.setOnKeyListener { _, keyCode, event ->
            if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) &&
                event.action == KeyEvent.ACTION_DOWN
            ) {
                val exo = player ?: return@setOnKeyListener true
                if (pendingSeekTargetMs >= 0L) {
                    commitPendingSeek()
                }
                if (exo.isPlaying) {
                    exo.pause()
                } else {
                    exo.play()
                    hideAllOverlays()
                }
                return@setOnKeyListener true
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                val dir = if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1L else -1L
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> {
                        isScrubbing = true
                        handler.removeCallbacks(autoHideControlsRunnable)
                        val step = stepForRepeat(event.repeatCount)
                        stepScrubTarget(dir * step)
                        return@setOnKeyListener true
                    }
                    KeyEvent.ACTION_UP -> {
                        commitPendingSeek()
                        scheduleControlsAutoHide()
                        return@setOnKeyListener true
                    }
                }
            }
            false
        }

        binding.pauseSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val dur = effectiveDurationMs()
                    if (dur > 0L) {
                        val target = (dur * progress) / 1000L
                        pendingSeekTargetMs = target
                        binding.pauseTimeDisplay.text = "${formatTime(target)} / ${formatTime(dur)}"
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isScrubbing = true
                handler.removeCallbacks(autoHideControlsRunnable)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val dur = effectiveDurationMs()
                val p = seekBar?.progress ?: 0
                if (dur > 0L) {
                    pendingSeekTargetMs = (dur * p) / 1000L
                }
                commitPendingSeek()
                scheduleControlsAutoHide()
            }
        })
    }

    private fun openSettingsPopup() {
        handler.removeCallbacks(autoHideControlsRunnable)
        populateSettingsPopup()
        binding.settingsPopupOverlay.visibility = View.VISIBLE
    }

    private fun closeSettingsPopup() {
        binding.settingsPopupOverlay.visibility = View.GONE
        binding.btnPauseSettings.requestFocus()
        scheduleControlsAutoHide()
    }

    private fun populateSettingsPopup() {
        // 1. Quality options (filtered by actual video resolution so 720p hides 1080p!)
        binding.qualityOptionsContainer.removeAllViews()
        val allowedQualities = currentItem?.supportedQualities() ?: VideoQuality.entries
        var firstFocus: View? = null
        for (q in allowedQualities) {
            val active = q == currentQuality
            val row = buildMenuRow(
                text = if (active) "●  ${q.label}" else "    ${q.label}",
                active = active
            ) {
                closeSettingsPopup()
                if (q != currentQuality) {
                    val pos = player?.currentPosition ?: 0L
                    currentQuality = q
                    triedOriginalFallback = false
                    currentItem?.let { loadStream(it, pos) }
                }
            }
            if (active || firstFocus == null) firstFocus = row
            binding.qualityOptionsContainer.addView(row)
        }

        // 2. Speed options
        binding.speedOptionsContainer.removeAllViews()
        val speeds = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        for (sp in speeds) {
            val active = sp == playbackSpeed
            val label = if (sp == 1.0f) "1x" else "${sp}x"
            val chip = buildChip(label, active) {
                playbackSpeed = sp
                player?.setPlaybackSpeed(sp)
                populateSettingsPopup()
            }
            binding.speedOptionsContainer.addView(chip)
        }

        // 3. Aspect / Zoom options
        binding.aspectOptionsContainer.removeAllViews()
        val aspects = listOf("Fit" to false, "Fill / Crop" to true)
        for ((label, isFill) in aspects) {
            val active = fillScreen == isFill
            val chip = buildChip(label, active) {
                fillScreen = isFill
                binding.playerView.resizeMode = if (isFill) {
                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                } else {
                    AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
                populateSettingsPopup()
            }
            binding.aspectOptionsContainer.addView(chip)
        }

        firstFocus?.requestFocus()
    }

    private fun buildMenuRow(text: String, active: Boolean, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(if (active) 0xFF00E5A3.toInt() else Color.WHITE)
            setPadding(16, 12, 16, 12)
            background = getDrawable(R.drawable.bg_nav_tab_modern)
            isFocusable = true
            isClickable = true
            setOnFocusChangeListener { _, hasFocus ->
                setTextColor(
                    when {
                        hasFocus -> 0xFF111115.toInt()
                        active -> 0xFF00E5A3.toInt()
                        else -> Color.WHITE
                    }
                )
            }
            setOnClickListener { onClick() }
        }
    }

    private fun buildChip(text: String, active: Boolean, onClick: () -> Unit): TextView {
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = 8 }
        return TextView(this).apply {
            layoutParams = lp
            this.text = text
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(if (active) 0xFF111115.toInt() else Color.WHITE)
            isSelected = active
            setPadding(18, 9, 18, 9)
            background = getDrawable(R.drawable.bg_nav_tab_modern)
            isFocusable = true
            isClickable = true
            setOnFocusChangeListener { _, hasFocus ->
                setTextColor(if (hasFocus || active) 0xFF111115.toInt() else Color.WHITE)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun initPlayerAndStart(item: MediaItem, startPositionMs: Long) {
        val dataSourceFactory = app.preIndexer.buildCacheDataSourceFactory()

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 45_000, 300, 1_000)
            .build()

        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setLoadControl(loadControl)
            .build()

        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        if (!hasStartedPlayback) {
                            binding.loadingOverlay.visibility = View.VISIBLE
                        } else if (binding.controlsOverlay.visibility == View.VISIBLE) {
                            binding.hudBufferSpinner.visibility = View.VISIBLE
                        }
                    }
                    Player.STATE_READY -> {
                        hasStartedPlayback = true
                        isAwaitingSeekReady = false
                        expectedSeekPosMs = -1L
                        binding.loadingOverlay.visibility = View.GONE
                        binding.hudBufferSpinner.visibility = View.GONE
                        updateTimeUi()
                    }
                    Player.STATE_ENDED -> {
                        isAwaitingSeekReady = false
                        persistProgress(forceEnded = true)
                        val cur = currentItem
                        val next = cur?.let { repo.getNextChronologicalVideo(it) }
                        if (next != null) {
                            currentItem = next
                            hasStartedPlayback = false
                            triedOriginalFallback = false
                            bindPauseMetadata(next)
                            Toast.makeText(
                                this@PlayerActivity,
                                "Playing next: ${next.displayTitle}",
                                Toast.LENGTH_SHORT
                            ).show()
                            loadStream(next, 0L)
                        } else {
                            showPausedUi()
                        }
                    }
                    Player.STATE_IDLE -> Unit
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    playingVideoWidth = videoSize.width
                    playingVideoHeight = videoSize.height
                    binding.hudBarTitle.text = "$baseHudTitle  ·  ${videoSize.width}×${videoSize.height}"
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                binding.btnPauseResume.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
                if (isPlaying) {
                    binding.pauseInfoOverlay.visibility = View.GONE
                    scheduleControlsAutoHide()
                } else if (exo.playbackState == Player.STATE_READY) {
                    showPausedUi()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                isAwaitingSeekReady = false
                val active = currentItem
                if (active != null && !triedOriginalFallback && currentQuality != VideoQuality.ORIGINAL) {
                    triedOriginalFallback = true
                    Toast.makeText(
                        this@PlayerActivity,
                        "Switching to original stream...",
                        Toast.LENGTH_SHORT
                    ).show()
                    loadStream(active, exo.currentPosition.coerceAtLeast(startPositionMs), forceOriginal = true)
                } else {
                    binding.loadingOverlay.visibility = View.GONE
                    Toast.makeText(
                        this@PlayerActivity,
                        "Playback failed: ${error.localizedMessage ?: "Stream unavailable"}",
                        Toast.LENGTH_LONG
                    ).show()
                    showPausedUi()
                }
            }
        })

        player = exo
        binding.playerView.player = exo
        loadStream(item, startPositionMs)
        handler.postDelayed(progressTickRunnable, 1000L)
    }

    private fun loadStream(item: MediaItem, startPositionMs: Long, forceOriginal: Boolean = false) {
        hasStartedPlayback = false
        isAwaitingSeekReady = false
        pendingSeekTargetMs = -1L
        binding.loadingOverlay.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val qualityToUse = if (forceOriginal) VideoQuality.ORIGINAL else currentQuality
                val streamUrl = repo.resolveFreshPlaybackUrl(item, qualityToUse)
                val exo = player ?: return@launch
                val exoItem = ExoMediaItem.Builder()
                    .setUri(streamUrl)
                    .setCustomCacheKey(StreamPreIndexer.cacheKeyFor(item, qualityToUse))
                    .build()
                exo.setMediaItem(exoItem, startPositionMs)
                exo.setPlaybackSpeed(playbackSpeed)
                exo.prepare()
                exo.playWhenReady = true
            } catch (e: Exception) {
                binding.loadingOverlay.visibility = View.GONE
                Toast.makeText(
                    this@PlayerActivity,
                    "Could not load stream: ${e.message ?: "Network error"}",
                    Toast.LENGTH_LONG
                ).show()
                showPausedUi()
            }
        }
    }

    private fun showPausedUi() {
        handler.removeCallbacks(autoHideControlsRunnable)
        updateTimeUi()
        if (binding.pauseInfoOverlay.visibility != View.VISIBLE) {
            binding.pauseInfoOverlay.alpha = 0f
            binding.pauseInfoOverlay.visibility = View.VISIBLE
            binding.pauseInfoOverlay.animate().alpha(1f).setDuration(180).start()
        }
        if (binding.controlsOverlay.visibility != View.VISIBLE) {
            binding.controlsOverlay.visibility = View.VISIBLE
            binding.btnPauseResume.requestFocus()
        }
    }

    private fun showTimelineHudOnly(focusTimeline: Boolean = true) {
        binding.pauseInfoOverlay.visibility = View.GONE
        updateTimeUi()
        if (binding.controlsOverlay.visibility != View.VISIBLE) {
            binding.controlsOverlay.visibility = View.VISIBLE
        }
        if (focusTimeline) {
            binding.pauseSeekBar.requestFocus()
        }
        scheduleControlsAutoHide()
    }

    private fun hideAllOverlays() {
        handler.removeCallbacks(autoHideControlsRunnable)
        binding.pauseInfoOverlay.visibility = View.GONE
        binding.controlsOverlay.visibility = View.GONE
        binding.settingsPopupOverlay.visibility = View.GONE
    }

    private fun scheduleControlsAutoHide() {
        handler.removeCallbacks(autoHideControlsRunnable)
        if (player?.isPlaying == true) {
            handler.postDelayed(autoHideControlsRunnable, 4500L)
        }
    }

    private fun effectiveDurationMs(): Long {
        val exoDur = player?.duration?.takeIf { it > 0L } ?: 0L
        if (exoDur > 0L) return exoDur
        return currentItem?.durationMs?.takeIf { it > 0L } ?: 0L
    }

    private fun stepForRepeat(repeatCount: Int): Long = when {
        repeatCount >= 18 -> 60_000L
        repeatCount >= 6 -> 30_000L
        else -> 10_000L
    }

    private fun stepScrubTarget(deltaMs: Long) {
        val exo = player ?: return
        val dur = effectiveDurationMs()
        val basePos = when {
            pendingSeekTargetMs >= 0L -> pendingSeekTargetMs
            isAwaitingSeekReady && expectedSeekPosMs >= 0L -> expectedSeekPosMs
            else -> exo.currentPosition.coerceAtLeast(0L)
        }
        val maxTarget = if (dur > 0L) (dur - 1000L).coerceAtLeast(0L) else Long.MAX_VALUE
        val target = (basePos + deltaMs).coerceIn(0L, maxTarget)
        pendingSeekTargetMs = target

        if (binding.controlsOverlay.visibility != View.VISIBLE) {
            binding.controlsOverlay.visibility = View.VISIBLE
        }
        binding.pauseTimeDisplay.text = "${formatTime(target)} / ${formatTime(dur)}"
        if (dur > 0L) {
            binding.pauseSeekBar.progress = ((target * 1000L) / dur).toInt().coerceIn(0, 1000)
        }

        handler.removeCallbacks(commitSeekRunnable)
        handler.postDelayed(commitSeekRunnable, 320L)
    }

    private fun performImmediateSeekDelta(deltaMs: Long) {
        val exo = player ?: return
        val dur = effectiveDurationMs()
        val basePos = if (isAwaitingSeekReady && expectedSeekPosMs >= 0L) expectedSeekPosMs else exo.currentPosition.coerceAtLeast(0L)
        val maxTarget = if (dur > 0L) (dur - 1000L).coerceAtLeast(0L) else Long.MAX_VALUE
        pendingSeekTargetMs = (basePos + deltaMs).coerceIn(0L, maxTarget)
        commitPendingSeek()
    }

    private fun commitPendingSeek() {
        handler.removeCallbacks(commitSeekRunnable)
        val target = pendingSeekTargetMs
        pendingSeekTargetMs = -1L
        isScrubbing = false
        if (target < 0L) return

        val exo = player ?: return
        expectedSeekPosMs = target
        isAwaitingSeekReady = true
        seekAwaitDeadlineMs = System.currentTimeMillis() + 5_000L
        exo.seekTo(target)

        val dur = effectiveDurationMs()
        binding.pauseTimeDisplay.text = "${formatTime(target)} / ${formatTime(dur)}"
        if (dur > 0L) {
            binding.pauseSeekBar.progress = ((target * 1000L) / dur).toInt().coerceIn(0, 1000)
        }
        scheduleControlsAutoHide()
    }

    private fun updateTimeUi() {
        val exo = player ?: return
        if (isScrubbing || pendingSeekTargetMs >= 0L) return
        if (isAwaitingSeekReady) {
            if (System.currentTimeMillis() < seekAwaitDeadlineMs) {
                return
            }
            isAwaitingSeekReady = false
        }

        val pos = exo.currentPosition.coerceAtLeast(0L)
        val dur = effectiveDurationMs()
        binding.pauseTimeDisplay.text = "${formatTime(pos)} / ${formatTime(dur)}"
        if (dur > 0L) {
            binding.pauseSeekBar.progress = ((pos * 1000L) / dur).toInt().coerceIn(0, 1000)
        }
    }

    private fun persistProgress(forceEnded: Boolean = false) {
        val item = currentItem ?: return
        val exo = player ?: return
        if (isScrubbing || pendingSeekTargetMs >= 0L || isAwaitingSeekReady) return
        val pos = if (forceEnded) 0L else exo.currentPosition.coerceAtLeast(0L)
        val dur = exo.duration.coerceAtLeast(item.durationMs)
        lifecycleScope.launch {
            repo.savePlaybackProgress(item.id, pos, dur)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN &&
            event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN &&
            binding.settingsPopupOverlay.visibility != View.VISIBLE &&
            player?.isPlaying == true
        ) {
            if (binding.controlsOverlay.visibility == View.VISIBLE) {
                hideAllOverlays()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val exo = player
        val controlsVisible = binding.controlsOverlay.visibility == View.VISIBLE

        if (binding.settingsPopupOverlay.visibility == View.VISIBLE) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                closeSettingsPopup()
                return true
            }
            return super.onKeyDown(keyCode, event)
        }

        if (controlsVisible) {
            scheduleControlsAutoHide()
        }

        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_SPACE -> {
                if (exo != null) {
                    if (exo.isPlaying) exo.pause() else exo.play()
                    return true
                }
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                exo?.play()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                exo?.pause()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> {
                if (exo != null && (!controlsVisible || binding.pauseSeekBar.isFocused)) {
                    if (pendingSeekTargetMs >= 0L) commitPendingSeek()
                    if (exo.isPlaying) {
                        exo.pause()
                    } else {
                        exo.play()
                        hideAllOverlays()
                    }
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (!controlsVisible) {
                    // Show timeline HUD WITHOUT pausing playback!
                    showTimelineHudOnly(focusTimeline = true)
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (controlsVisible && exo?.isPlaying == true) {
                    hideAllOverlays()
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (!controlsVisible) {
                    val step = stepForRepeat(event?.repeatCount ?: 0)
                    stepScrubTarget(step)
                    scheduleControlsAutoHide()
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                if (!controlsVisible) {
                    val step = stepForRepeat(event?.repeatCount ?: 0)
                    stepScrubTarget(-step)
                    scheduleControlsAutoHide()
                    return true
                }
            }
            KeyEvent.KEYCODE_MENU -> {
                if (!controlsVisible) {
                    showTimelineHudOnly(focusTimeline = false)
                }
                openSettingsPopup()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (controlsVisible && exo?.isPlaying == true) {
                    hideAllOverlays()
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        val controlsVisible = binding.controlsOverlay.visibility == View.VISIBLE
        if (!controlsVisible && (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
                keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ||
                keyCode == KeyEvent.KEYCODE_MEDIA_REWIND)
        ) {
            if (pendingSeekTargetMs >= 0L) {
                commitPendingSeek()
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun formatTime(ms: Long): String {
        val totalSec = TimeUnit.MILLISECONDS.toSeconds(ms.coerceAtLeast(0L))
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
    }

    companion object {
        const val EXTRA_ITEM_ID = "extra_item_id"
        const val EXTRA_RESUME_MS = "extra_resume_ms"
        const val EXTRA_QUALITY = "extra_quality"
    }
}
