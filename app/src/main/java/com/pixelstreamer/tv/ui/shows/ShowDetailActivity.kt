package com.pixelstreamer.tv.ui.shows

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pixelstreamer.tv.PixelStreamerApp
import com.pixelstreamer.tv.R
import com.pixelstreamer.tv.data.model.MediaItem
import com.pixelstreamer.tv.data.model.ShowGroup
import com.pixelstreamer.tv.databinding.ActivityShowDetailBinding
import com.pixelstreamer.tv.ui.player.PlayerActivity
import com.pixelstreamer.tv.util.bindTitleLogo
import com.pixelstreamer.tv.util.loadUrlWithFallback
import kotlinx.coroutines.launch

class ShowDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityShowDetailBinding
    private val app by lazy { application as PixelStreamerApp }
    private val repo by lazy { app.repository }

    private var seriesKey: String = ""
    private var selectedSeason: Int = 1
    private var showGroup: ShowGroup? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityShowDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        seriesKey = intent.getStringExtra(EXTRA_SERIES_KEY).orEmpty()
        binding.btnShowBack.setOnClickListener { finish() }
        binding.seasonsRecycler.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.episodesRecycler.layoutManager = LinearLayoutManager(this)

        renderShow()
        binding.btnShowPlayNext.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        renderShow()
    }

    private fun renderShow() {
        val show = repo.getShowByKey(seriesKey)
        if (show == null) {
            finish()
            return
        }
        showGroup = show

        binding.showBackdrop.loadUrlWithFallback(
            primaryUrl = show.backdropUrl.orEmpty(),
            fallbackUrl = show.posterUrl
        )
        binding.showPoster.loadUrlWithFallback(
            primaryUrl = show.posterUrl.orEmpty(),
            fallbackUrl = show.nextUpEpisode.thumbnailUrl(600, 900),
            placeholderRes = R.color.nf_surface
        )

        binding.showTitle.text = show.title
        binding.showTitleLogo.bindTitleLogo(show.logoUrl, binding.showTitle)
        val metaParts = mutableListOf<String>()
        show.year?.let { metaParts.add(it.toString()) }
        show.rating?.let { metaParts.add("★ ${"%.1f".format(it)}") }
        metaParts.add("${show.seasonCount} Season${if (show.seasonCount == 1) "" else "s"}")
        metaParts.add("${show.episodeCount} Episodes")
        if (show.genres.isNotEmpty()) metaParts.add(show.genres.take(2).joinToString(" · "))
        binding.showMeta.text = metaParts.joinToString("  •  ")
        binding.showOverview.text = show.overview

        val nextEp = show.nextUpEpisode
        val nextProg = repo.progressMap.value[nextEp.id]
        val nextLabel = nextEp.episodeCode.ifBlank { "E01" }
        binding.btnShowPlayNext.text = if (nextProg != null && nextProg.isInProgress) {
            "▶  Resume $nextLabel"
        } else {
            "▶  Play $nextLabel"
        }
        binding.btnShowPlayNext.setOnClickListener {
            val startMs = if (nextProg != null && nextProg.isInProgress) nextProg.positionMs else 0L
            playEpisode(nextEp, startMs)
        }
        lifecycleScope.launch { repo.preIndexSingleItem(nextEp) }

        binding.btnShowFix.setOnClickListener {
            showFixTitleDialog(show)
        }

        val seasonKeys = show.seasons.keys.toList()
        if (selectedSeason !in seasonKeys) {
            selectedSeason = nextEp.seasonNumber ?: seasonKeys.firstOrNull() ?: 1
        }
        binding.seasonsRecycler.adapter = SeasonTabAdapter(seasonKeys)
        renderEpisodesForSeason(selectedSeason)
    }

    private fun renderEpisodesForSeason(season: Int) {
        selectedSeason = season
        val eps = showGroup?.seasons?.get(season).orEmpty()
        binding.episodesRecycler.adapter = EpisodeAdapter(eps)
    }

    private fun playEpisode(ep: MediaItem, startMs: Long) {
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_ITEM_ID, ep.id)
                .putExtra(PlayerActivity.EXTRA_RESUME_MS, startMs)
        )
    }

    private fun showFixTitleDialog(show: ShowGroup) {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(44, 16, 44, 8)
        }
        val input = EditText(this).apply {
            setText(show.title)
            setSelection(text.length)
            hint = "e.g. Breaking Bad"
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF9E9E9E.toInt())
            setSingleLine(true)
        }
        wrap.addView(input)

        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle("Fix Show Title & Artwork")
            .setMessage("Enter the TV show name to match posters & episode titles:")
            .setView(wrap)
            .setPositiveButton("Match & Save") { _, _ ->
                val entered = input.text?.toString().orEmpty().trim()
                if (entered.isBlank()) return@setPositiveButton
                lifecycleScope.launch {
                    Toast.makeText(this@ShowDetailActivity, "Matching \"$entered\"...", Toast.LENGTH_SHORT).show()
                    val (newKey, matched) = repo.setCustomShowTitleAndRematch(seriesKey, entered)
                    if (newKey.isNotBlank()) seriesKey = newKey
                    renderShow()
                    Toast.makeText(
                        this@ShowDetailActivity,
                        if (matched != null) "Matched: ${matched.matchedTitle}" else "Saved title",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    inner class SeasonTabAdapter(private val seasons: List<Int>) :
        RecyclerView.Adapter<SeasonTabAdapter.SeasonVH>() {

        inner class SeasonVH(val text: TextView) : RecyclerView.ViewHolder(text)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SeasonVH {
            val tv = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_season_tab, parent, false) as TextView
            return SeasonVH(tv)
        }

        override fun onBindViewHolder(holder: SeasonVH, position: Int) {
            val s = seasons[position]
            val active = s == selectedSeason
            holder.text.text = "Season $s"
            holder.text.isSelected = active
            holder.text.setTextColor(if (active) 0xFF111115.toInt() else Color.WHITE)
            holder.text.setOnFocusChangeListener { _, hasFocus ->
                val bright = hasFocus || s == selectedSeason
                holder.text.setTextColor(if (bright) 0xFF111115.toInt() else Color.WHITE)
            }
            holder.text.setOnClickListener {
                renderEpisodesForSeason(s)
                notifyDataSetChanged()
            }
        }

        override fun getItemCount(): Int = seasons.size
    }

    inner class EpisodeAdapter(private val episodes: List<MediaItem>) :
        RecyclerView.Adapter<EpisodeAdapter.EpVH>() {

        inner class EpVH(view: View) : RecyclerView.ViewHolder(view) {
            val thumb: ImageView = view.findViewById(R.id.epThumb)
            val progress: ProgressBar = view.findViewById(R.id.epProgress)
            val title: TextView = view.findViewById(R.id.epTitle)
            val duration: TextView = view.findViewById(R.id.epDuration)
            val overview: TextView = view.findViewById(R.id.epOverview)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EpVH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_episode_row, parent, false)
            return EpVH(v)
        }

        override fun onBindViewHolder(holder: EpVH, position: Int) {
            val ep = episodes[position]
            holder.title.text = ep.episodeRowLabel
            holder.duration.text = ep.displayDuration
            holder.overview.text = ep.episodeOverview.ifBlank {
                if (ep.episodeCode.isNotBlank()) "${ep.seriesDisplayTitle} · ${ep.episodeCode}"
                else "Ready to play"
            }

            holder.thumb.loadUrlWithFallback(
                primaryUrl = ep.landscapeCardUrl,
                fallbackUrl = ep.thumbnailUrl(640, 360),
                placeholderRes = R.color.nf_surface
            )

            val prog = repo.progressMap.value[ep.id]
            if (prog != null && prog.isInProgress) {
                holder.progress.visibility = View.VISIBLE
                holder.progress.progress = (prog.fraction * 100).toInt().coerceIn(2, 100)
            } else {
                holder.progress.visibility = View.GONE
            }

            holder.itemView.setOnClickListener {
                val startMs = if (prog != null && prog.isInProgress) prog.positionMs else 0L
                playEpisode(ep, startMs)
            }
        }

        override fun getItemCount(): Int = episodes.size
    }

    companion object {
        const val EXTRA_SERIES_KEY = "extra_series_key"
    }
}
