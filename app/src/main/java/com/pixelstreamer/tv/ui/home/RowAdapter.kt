package com.pixelstreamer.tv.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pixelstreamer.tv.R
import com.pixelstreamer.tv.data.model.HomeRow
import com.pixelstreamer.tv.data.model.MediaItem
import com.pixelstreamer.tv.data.model.PlaybackProgress
import com.pixelstreamer.tv.data.model.RowStyle
import com.pixelstreamer.tv.data.model.ShowGroup

class RowAdapter(
    private val onItemFocused: (MediaItem) -> Unit,
    private val onItemClicked: (MediaItem, RowStyle) -> Unit,
    private val onShowFocused: (ShowGroup) -> Unit,
    private val onShowClicked: (ShowGroup) -> Unit
) : RecyclerView.Adapter<RowAdapter.RowVH>() {

    private var rows: List<HomeRow> = emptyList()
    private var progressMap: Map<String, PlaybackProgress> = emptyMap()
    private val viewPool = RecyclerView.RecycledViewPool()

    fun submit(newRows: List<HomeRow>, newProgress: Map<String, PlaybackProgress>) {
        rows = newRows
        progressMap = newProgress
        notifyDataSetChanged()
    }

    class RowVH(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.rowTitle)
        val subtitle: TextView = view.findViewById(R.id.rowSubtitle)
        val itemsRecycler: RecyclerView = view.findViewById(R.id.rowItemsRecycler)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowVH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.row_home, parent, false)
        val vh = RowVH(view)
        vh.itemsRecycler.layoutManager = LinearLayoutManager(parent.context, LinearLayoutManager.HORIZONTAL, false)
        vh.itemsRecycler.setRecycledViewPool(viewPool)
        vh.itemsRecycler.itemAnimator = null
        return vh
    }

    override fun onBindViewHolder(holder: RowVH, position: Int) {
        val row = rows[position]
        holder.title.text = if (row.rowStyle == RowStyle.CONTINUE_WATCHING) "${row.title}  ›" else row.title
        holder.subtitle.text = row.subtitle
        holder.itemsRecycler.adapter = CardAdapter(
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

    override fun getItemCount(): Int = rows.size
}
