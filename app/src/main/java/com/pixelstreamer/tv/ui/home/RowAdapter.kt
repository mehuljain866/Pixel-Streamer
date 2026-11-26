package com.pixelstreamer.tv.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pixelstreamer.tv.R
import com.pixelstreamer.tv.data.model.HomeRow
import com.pixelstreamer.tv.data.model.MediaItem
import com.pixelstreamer.tv.data.model.PlaybackProgress
import com.pixelstreamer.tv.data.model.RowStyle
import com.pixelstreamer.tv.data.model.ShowGroup
import com.pixelstreamer.tv.util.bindTitleLogo

data class HeroUiState(
    val title: String = "Your Personal Cinema",
    val titleLogoUrl: String? = null,
    val meta: String = "Shared Google Photos · No Sign-In Required",
    val overview: String = "Share any Google Photos album containing movies, shows, or photos to stream in sleek cinema mode.",
    val playLabel: String = "▶  Play",
    val showDetailsButton: Boolean = true
)

class RowAdapter(
    private val onHeroFocused: () -> Unit,
    private val onHeroPlayClicked: () -> Unit,
    private val onHeroDetailsClicked: () -> Unit,
    private val onItemFocused: (MediaItem) -> Unit,
    private val onItemClicked: (MediaItem, RowStyle) -> Unit,
    private val onShowFocused: (ShowGroup) -> Unit,
    private val onShowClicked: (ShowGroup) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var rows: List<HomeRow> = emptyList()
    private var progressMap: Map<String, PlaybackProgress> = emptyMap()
    private var heroState: HeroUiState = HeroUiState()
    private val viewPool = RecyclerView.RecycledViewPool()

    fun submit(newRows: List<HomeRow>, newProgress: Map<String, PlaybackProgress>) {
        rows = newRows
        progressMap = newProgress
        notifyDataSetChanged()
    }

    fun updateHero(state: HeroUiState, recycler: RecyclerView) {
        heroState = state
        if (rows.isEmpty()) return
        val vh = recycler.findViewHolderForAdapterPosition(0) as? HeroVH
        vh?.bind(state)
    }

    class HeroVH(
        view: View,
        onFocused: () -> Unit,
        onPlay: () -> Unit,
        onDetails: () -> Unit
    ) : RecyclerView.ViewHolder(view) {
        private val titleLogo: ImageView = view.findViewById(R.id.heroTitleLogo)
        private val title: TextView = view.findViewById(R.id.heroTitle)
        private val meta: TextView = view.findViewById(R.id.heroMeta)
        private val overview: TextView = view.findViewById(R.id.heroOverview)
        private val btnPlay: Button = view.findViewById(R.id.btnHeroPlay)
        private val btnDetails: Button = view.findViewById(R.id.btnHeroDetails)
        private var boundLogoUrl: String? = null

        init {
            btnPlay.setOnClickListener { onPlay() }
            btnDetails.setOnClickListener { onDetails() }
            val focusListener = View.OnFocusChangeListener { _, hasFocus ->
                if (hasFocus) onFocused()
            }
            btnPlay.onFocusChangeListener = focusListener
            btnDetails.onFocusChangeListener = focusListener
        }

        fun bind(state: HeroUiState) {
            title.text = state.title
            if (boundLogoUrl != state.titleLogoUrl) {
                boundLogoUrl = state.titleLogoUrl
                titleLogo.bindTitleLogo(state.titleLogoUrl, title)
            } else if (state.titleLogoUrl.isNullOrBlank()) {
                titleLogo.visibility = View.GONE
                title.visibility = View.VISIBLE
            }
            meta.text = state.meta
            overview.text = state.overview
            btnPlay.text = state.playLabel
            btnDetails.visibility = if (state.showDetailsButton) View.VISIBLE else View.GONE
        }

        fun focusPlay() {
            btnPlay.requestFocus()
        }
    }

    class RowVH(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.rowTitle)
        val subtitle: TextView = view.findViewById(R.id.rowSubtitle)
        val itemsRecycler: RecyclerView = view.findViewById(R.id.rowItemsRecycler)
    }

    override fun getItemViewType(position: Int): Int {
        return if (position == 0) VIEW_TYPE_HERO else VIEW_TYPE_ROW
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_HERO) {
            val view = inflater.inflate(R.layout.item_home_hero, parent, false)
            HeroVH(view, onHeroFocused, onHeroPlayClicked, onHeroDetailsClicked)
        } else {
            val view = inflater.inflate(R.layout.row_home, parent, false)
            val vh = RowVH(view)
            vh.itemsRecycler.layoutManager =
                LinearLayoutManager(parent.context, LinearLayoutManager.HORIZONTAL, false)
            vh.itemsRecycler.setRecycledViewPool(viewPool)
            vh.itemsRecycler.itemAnimator = null
            vh
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is HeroVH) {
            holder.bind(heroState)
            return
        }
        val rowHolder = holder as RowVH
        val row = rows[position - 1]
        rowHolder.title.text = if (row.rowStyle == RowStyle.CONTINUE_WATCHING) "${row.title}  ›" else row.title
        rowHolder.subtitle.text = row.subtitle
        rowHolder.itemsRecycler.adapter = CardAdapter(
            style = row.rowStyle,
            items = row.items,
            shows = row.shows,
            progressMap = progressMap,
            onItemFocused = onItemFocused,
            onItemClicked = { item -> onItemClicked(item, row.rowStyle) },
            onShowFocused = onShowFocused,
            onShowClicked = onShowClicked
        )
    }

    override fun getItemCount(): Int = if (rows.isEmpty()) 0 else rows.size + 1

    companion object {
        private const val VIEW_TYPE_HERO = 0
        private const val VIEW_TYPE_ROW = 1
    }
}
