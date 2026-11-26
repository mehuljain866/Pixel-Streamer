package com.pixelstreamer.tv.ui.home

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.pixelstreamer.tv.R
import com.pixelstreamer.tv.data.model.MediaItem
import com.pixelstreamer.tv.data.model.PlaybackProgress
import com.pixelstreamer.tv.data.model.RowStyle
import com.pixelstreamer.tv.data.model.ShowGroup
import com.pixelstreamer.tv.util.loadUrlWithFallback

class CardAdapter(
    private val style: RowStyle,
    private val items: List<MediaItem> = emptyList(),
    private val shows: List<ShowGroup> = emptyList(),
    private val progressMap: Map<String, PlaybackProgress> = emptyMap(),
    private val onItemFocused: (MediaItem) -> Unit = {},
    private val onItemClicked: (MediaItem) -> Unit = {},
    private val onShowFocused: (ShowGroup) -> Unit = {},
    private val onShowClicked: (ShowGroup) -> Unit = {}
) : RecyclerView.Adapter<CardAdapter.CardVH>() {

    class CardVH(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.cardImage)
        val badge: TextView? = view.findViewById(R.id.cardBadge)
        val progress: ProgressBar? = view.findViewById(R.id.cardProgress)
        val title: TextView = view.findViewById(R.id.cardTitle)
        val subtitle: TextView? = view.findViewById(R.id.cardSubtitle)
    }

    override fun getItemViewType(position: Int): Int {
        return when (style) {
            RowStyle.CONTINUE_WATCHING -> VIEW_TYPE_CONTINUE
            RowStyle.SHOWS_POSTER, RowStyle.MOVIES_POSTER -> VIEW_TYPE_PORTRAIT
            RowStyle.PHOTOS_LANDSCAPE -> VIEW_TYPE_LANDSCAPE
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardVH {
        val layoutId = when (viewType) {
            VIEW_TYPE_CONTINUE -> R.layout.item_continue_card
            VIEW_TYPE_PORTRAIT -> R.layout.item_poster_card
            else -> R.layout.item_landscape_card
        }
        val view = LayoutInflater.from(parent.context).inflate(layoutId, parent, false)
        return CardVH(view)
    }

    override fun onBindViewHolder(holder: CardVH, position: Int) {
        if (style == RowStyle.SHOWS_POSTER || position < shows.size) {
            bindShow(holder, shows[position])
        } else {
            bindMedia(holder, items[position - shows.size])
        }
    }

    private fun bindShow(holder: CardVH, show: ShowGroup) {
        holder.title.text = show.title
        holder.badge?.text = "${show.episodeCount} eps"
        holder.badge?.visibility = View.VISIBLE

        holder.image.loadUrlWithFallback(
            primaryUrl = show.posterUrl.orEmpty(),
            fallbackUrl = show.nextUpEpisode.thumbnailUrl(600, 900),
            placeholderRes = R.color.nf_surface
        )

        val prog = progressMap[show.nextUpEpisode.id]
        if (prog != null && prog.isInProgress) {
            holder.progress?.visibility = View.VISIBLE
            holder.progress?.progress = (prog.fraction * 100).toInt().coerceIn(2, 100)
        } else {
            holder.progress?.visibility = View.GONE
        }

        holder.itemView.setOnFocusChangeListener { v, hasFocus ->
            animateFocus(v, holder.title, hasFocus)
            if (hasFocus) onShowFocused(show)
        }
        holder.itemView.setOnClickListener { onShowClicked(show) }
    }

    private fun bindMedia(holder: CardVH, item: MediaItem) {
        val prog = progressMap[item.id]
        if (style == RowStyle.CONTINUE_WATCHING) {
            holder.title.text = if (item.isEpisode) item.seriesDisplayTitle else item.displayTitle
            val rem = prog?.remainingText.orEmpty()
            holder.subtitle?.text = buildString {
                if (item.isEpisode && item.episodeCode.isNotBlank()) {
                    append(item.episodeCode)
                    if (rem.isNotBlank()) append(" · ")
                }
                append(rem)
            }.ifBlank { item.displayDuration }

            holder.image.loadUrlWithFallback(
                primaryUrl = item.landscapeCardUrl,
                fallbackUrl = item.thumbnailUrl(640, 360),
                placeholderRes = R.color.nf_surface
            )
            holder.progress?.visibility = View.VISIBLE
            holder.progress?.progress = ((prog?.fraction ?: 0f) * 100).toInt().coerceIn(2, 100)
        } else {
            holder.title.text = item.displayTitle
            val imgUrl = if (style == RowStyle.MOVIES_POSTER) item.posterImageUrl else item.landscapeCardUrl
            holder.image.loadUrlWithFallback(
                primaryUrl = imgUrl,
                fallbackUrl = item.thumbnailUrl(640, 360),
                placeholderRes = R.color.nf_surface
            )

            val badgeText = when {
                item.isVideo && item.displayDuration.isNotBlank() -> item.displayDuration
                item.isVideo -> "HD"
                else -> ""
            }
            holder.badge?.text = badgeText
            holder.badge?.visibility = if (badgeText.isBlank()) View.GONE else View.VISIBLE

            if (item.isVideo && prog != null && prog.isInProgress) {
                holder.progress?.visibility = View.VISIBLE
                holder.progress?.progress = (prog.fraction * 100).toInt().coerceIn(2, 100)
            } else {
                holder.progress?.visibility = View.GONE
            }
        }

        holder.itemView.setOnFocusChangeListener { v, hasFocus ->
            animateFocus(v, holder.title, hasFocus)
            if (hasFocus) onItemFocused(item)
        }
        holder.itemView.setOnClickListener { onItemClicked(item) }
    }

    private fun animateFocus(v: View, titleView: TextView, hasFocus: Boolean) {
        v.animate()
            .scaleX(if (hasFocus) 1.06f else 1.0f)
            .scaleY(if (hasFocus) 1.06f else 1.0f)
            .setDuration(130)
            .start()
        v.elevation = if (hasFocus) 14f else 0f
        titleView.setTextColor(if (hasFocus) Color.WHITE else 0xFFD0D0D8.toInt())
    }

    override fun getItemCount(): Int {
        return if (style == RowStyle.SHOWS_POSTER) shows.size else shows.size + items.size
    }

    companion object {
        private const val VIEW_TYPE_LANDSCAPE = 0
        private const val VIEW_TYPE_PORTRAIT = 1
        private const val VIEW_TYPE_CONTINUE = 2
    }
}
