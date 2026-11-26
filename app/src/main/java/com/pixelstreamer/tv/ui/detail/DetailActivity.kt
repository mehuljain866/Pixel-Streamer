package com.pixelstreamer.tv.ui.detail

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.pixelstreamer.tv.PixelStreamerApp
import com.pixelstreamer.tv.R
import com.pixelstreamer.tv.data.model.MediaItem
import com.pixelstreamer.tv.data.model.VideoQuality
import com.pixelstreamer.tv.databinding.ActivityDetailBinding
import com.pixelstreamer.tv.ui.player.PlayerActivity
import com.pixelstreamer.tv.ui.shows.ShowDetailActivity
import com.pixelstreamer.tv.util.TitleCleaner
import com.pixelstreamer.tv.util.bindTitleLogo
import com.pixelstreamer.tv.util.loadUrlWithFallback
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class DetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDetailBinding
    private val app by lazy { application as PixelStreamerApp }
    private val repo by lazy { app.repository }

    private var itemId: String = ""
    private var selectedQuality: VideoQuality = VideoQuality.AUTO

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        itemId = intent.getStringExtra(EXTRA_ITEM_ID).orEmpty()
        selectedQuality = repo.defaultQuality

        val item = repo.getItemById(itemId)
        if (item == null) {
            finish()
            return
        }

        render(item)
        setupButtons()
        binding.btnDetailPlay.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        repo.getItemById(itemId)?.let { render(it) }
    }

    private fun setupButtons() {
        binding.btnDetailPlay.setOnClickListener {
            val prog = repo.progressMap.value[itemId]
            val startMs = if (prog != null && prog.isInProgress) prog.positionMs else 0L
            launchPlayer(startMs)
        }

        binding.btnDetailRestart.setOnClickListener {
            launchPlayer(0L)
        }

        binding.btnDetailQuality.setOnClickListener {
            val all = VideoQuality.entries
            val nextIdx = (all.indexOf(selectedQuality) + 1) % all.size
            selectedQuality = all[nextIdx]
            binding.btnDetailQuality.text = "Quality: ${selectedQuality.label}"
        }

        binding.btnDetailEpisodes.setOnClickListener {
            val cur = repo.getItemById(itemId) ?: return@setOnClickListener
            val key = TitleCleaner.normalizeSeriesKey(cur.seriesDisplayTitle)
            startActivity(
                Intent(this, ShowDetailActivity::class.java)
                    .putExtra(ShowDetailActivity.EXTRA_SERIES_KEY, key)
            )
        }

        binding.btnDetailRename.setOnClickListener {
            val cur = repo.getItemById(itemId) ?: return@setOnClickListener
            val cleanHint = TitleCleaner.cleanDisplayTitle(cur.displayTitle)
            val wrap = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(44, 16, 44, 8)
            }
            val input = EditText(this).apply {
                setText(cleanHint)
                setSelection(text.length)
                hint = "e.g. Interstellar (2014)"
                setTextColor(Color.WHITE)
                setHintTextColor(0xFF9E9E9E.toInt())
                setSingleLine(true)
            }
            wrap.addView(input)
            AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
                .setTitle("Fix Movie Title & Cover Art")
                .setMessage("Enter the movie or show title to fetch poster art & synopsis:")
                .setView(wrap)
                .setPositiveButton("Match & Save") { _, _ ->
                    val entered = input.text?.toString().orEmpty().trim()
                    if (entered.isBlank()) return@setPositiveButton
                    lifecycleScope.launch {
                        Toast.makeText(this@DetailActivity, "Searching cover art for \"$entered\"...", Toast.LENGTH_SHORT).show()
                        val matched = repo.setCustomMovieTitleAndRematch(cur.albumId, cur.id, entered)
                        repo.getItemById(itemId)?.let { render(it) }
                        val msg = if (matched != null) {
                            "Matched: ${matched.matchedTitle} (${matched.source})"
                        } else {
                            "Saved \"$entered\" (no poster match found — try a shorter title)"
                        }
                        Toast.makeText(this@DetailActivity, msg, Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun launchPlayer(startMs: Long) {
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_ITEM_ID, itemId)
                .putExtra(PlayerActivity.EXTRA_RESUME_MS, startMs)
                .putExtra(PlayerActivity.EXTRA_QUALITY, selectedQuality.name)
        )
    }

    private fun render(item: MediaItem) {
        val meta = item.movieMetadata
        binding.detailBackdrop.loadUrlWithFallback(
            primaryUrl = item.backdropImageUrl,
            fallbackUrl = item.thumbnailUrl(1280, 720)
        )
        binding.detailPoster.loadUrlWithFallback(
            primaryUrl = item.posterImageUrl,
            fallbackUrl = item.thumbnailUrl(600, 900),
            placeholderRes = R.color.nf_surface
        )

        binding.detailTag.text = if (meta != null) "PIXEL STREAMER · ${meta.source.uppercase()}" else "GOOGLE PHOTOS VIDEO"
        binding.detailTitle.text = item.displayTitle
        binding.detailTitleLogo.bindTitleLogo(item.titleLogoUrl, binding.detailTitle)

        val metaParts = mutableListOf<String>()
        meta?.rating?.let { metaParts.add("★ ${"%.1f".format(it)}") }
        meta?.year?.let { metaParts.add(it.toString()) }
        if (meta?.contentRating?.isNotBlank() == true) metaParts.add(meta.contentRating)
        if (item.displayDuration.isNotBlank()) metaParts.add(item.displayDuration)
        else meta?.runtimeMinutes?.let { metaParts.add("${it}m") }
        metaParts.add("${item.width}×${item.height}")
        binding.detailMeta.text = metaParts.joinToString("   •   ")

        val genresText = meta?.genres?.takeIf { it.isNotEmpty() }?.joinToString("  ·  ").orEmpty()
        binding.detailGenres.text = genresText
        binding.detailGenres.visibility = if (genresText.isBlank()) View.GONE else View.VISIBLE

        binding.detailOverview.text = meta?.overview?.takeIf { it.isNotBlank() }
            ?: item.caption.takeIf { it.isNotBlank() && !it.contains("_1080p", true) && !it.endsWith(".mp4", true) }
            ?: "Streaming directly from your Google Photos shared album."

        val creditsParts = mutableListOf<String>()
        if (meta?.director?.isNotBlank() == true) creditsParts.add("Director: ${meta.director}")
        if (meta?.cast?.isNotEmpty() == true) creditsParts.add("Starring: ${meta.cast.joinToString(", ")}")
        binding.detailCredits.text = creditsParts.joinToString("   |   ")
        binding.detailCredits.visibility = if (creditsParts.isEmpty()) View.GONE else View.VISIBLE

        val prog = repo.progressMap.value[item.id]
        if (prog != null && prog.isInProgress) {
            binding.detailProgressBar.visibility = View.VISIBLE
            binding.detailProgressBar.progress = (prog.fraction * 100).toInt().coerceIn(2, 100)
            binding.btnDetailPlay.text = "▶  Resume (${formatTime(prog.positionMs)})"
            binding.btnDetailRestart.visibility = View.VISIBLE
        } else {
            binding.detailProgressBar.visibility = View.GONE
            binding.btnDetailPlay.text = "▶  Play"
            binding.btnDetailRestart.visibility = View.GONE
        }

        binding.btnDetailEpisodes.visibility = if (item.isEpisode) View.VISIBLE else View.GONE
        binding.btnDetailQuality.text = "Quality: ${selectedQuality.label}"
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
    }
}
