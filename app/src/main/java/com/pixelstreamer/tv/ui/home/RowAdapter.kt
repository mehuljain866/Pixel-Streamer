package com.pixelstreamer.tv.ui.home

import android.graphics.drawable.GradientDrawable
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.pixelstreamer.tv.R
import com.pixelstreamer.tv.data.model.HomeRow
import com.pixelstreamer.tv.data.model.MediaItem
import com.pixelstreamer.tv.data.model.PlaybackProgress
import com.pixelstreamer.tv.data.model.RowStyle
import com.pixelstreamer.tv.data.model.ShowGroup
import com.pixelstreamer.tv.util.bindTitleLogo

data class HeroUiState(
    val id: String = "welcome",
    val badgeText: String = "",
    val isContinueWatching: Boolean = false,
    val title: String = "Your Personal Cinema",
    val titleLogoUrl: String? = null,
    val meta: String = "Shared Google Photos · No Sign-In Required",
    val overview: String = "Share any Google Photos album containing movies, shows, or photos to stream in sleek cinema mode.",
    val playLabel: String = "▶  Play",
    val showDetailsButton: Boolean = true,
    val backdropUrl: String? = null,
    val fallbackBackdropUrl: String? = null,
    val item: MediaItem? = null,
    val show: ShowGroup? = null
)

class RowAdapter(
    private val onHeroFocused: () -> Unit,
    private val onHeroSlideSelected: (HeroUiState) -> Unit,
    private val onHeroPlayClicked: (HeroUiState) -> Unit,
    private val onHeroDetailsClicked: (HeroUiState) -> Unit,
    private val onItemFocused: (MediaItem) -> Unit,
    private val onItemClicked: (MediaItem, RowStyle) -> Unit,
    private val onShowFocused: (ShowGroup) -> Unit,
    private val onShowClicked: (ShowGroup) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var rows: List<HomeRow> = emptyList()
    private var progressMap: Map<String, PlaybackProgress> = emptyMap()
    private var heroSlides: List<HeroUiState> = listOf(HeroUiState())
    private var selectedHeroIndex: Int = 0
    private val viewPool = RecyclerView.RecycledViewPool()

    fun submit(
        newRows: List<HomeRow>,
        newProgress: Map<String, PlaybackProgress>,
        newHeroSlides: List<HeroUiState>,
        activeHeroIndex: Int = 0
    ) {
        rows = newRows
        progressMap = newProgress
        heroSlides = newHeroSlides.ifEmpty { listOf(HeroUiState()) }
        selectedHeroIndex = activeHeroIndex.coerceIn(0, heroSlides.lastIndex)
        notifyDataSetChanged()
    }

    class HeroSlideAdapter(
        private val onFocused: () -> Unit,
        private val onPlay: (HeroUiState) -> Unit,
        private val onDetails: (HeroUiState) -> Unit,
        private val onStepSlide: (delta: Int, focusPlay: Boolean) -> Unit
    ) : RecyclerView.Adapter<HeroSlideAdapter.SlideVH>() {

        private var slides: List<HeroUiState> = emptyList()

        fun submitSlides(newSlides: List<HeroUiState>) {
            slides = newSlides
            notifyDataSetChanged()
        }

        inner class SlideVH(view: View) : RecyclerView.ViewHolder(view) {
            private val badge: TextView = view.findViewById(R.id.heroSlideBadge)
            private val titleLogo: ImageView = view.findViewById(R.id.heroTitleLogo)
            private val title: TextView = view.findViewById(R.id.heroTitle)
            private val meta: TextView = view.findViewById(R.id.heroMeta)
            private val overview: TextView = view.findViewById(R.id.heroOverview)
            private val btnPlay: Button = view.findViewById(R.id.btnHeroPlay)
            private val btnDetails: Button = view.findViewById(R.id.btnHeroDetails)
            private var boundSlide: HeroUiState? = null
            private var boundLogoUrl: String? = null

            init {
                btnPlay.setOnClickListener { boundSlide?.let(onPlay) }
                btnDetails.setOnClickListener { boundSlide?.let(onDetails) }
                val focusListener = View.OnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) onFocused()
                }
                btnPlay.onFocusChangeListener = focusListener
                btnDetails.onFocusChangeListener = focusListener

                btnPlay.setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN && slides.size > 1) {
                        when {
                            keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> {
                                onStepSlide(-1, true)
                                return@setOnKeyListener true
                            }
                            keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && btnDetails.visibility != View.VISIBLE -> {
                                onStepSlide(1, true)
                                return@setOnKeyListener true
                            }
                        }
                    }
                    false
                }

                btnDetails.setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN &&
                        keyCode == KeyEvent.KEYCODE_DPAD_RIGHT &&
                        slides.size > 1
                    ) {
                        onStepSlide(1, true)
                        return@setOnKeyListener true
                    }
                    false
                }
            }

            fun bind(state: HeroUiState) {
                boundSlide = state
                if (state.badgeText.isNotBlank()) {
                    badge.text = state.badgeText
                    badge.setTextColor(if (state.isContinueWatching) 0xFF00E5A3.toInt() else 0xFFE0E0EA.toInt())
                    badge.visibility = View.VISIBLE
                } else {
                    badge.visibility = View.GONE
                }
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

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SlideVH {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_hero_slide, parent, false)
            return SlideVH(view)
        }

        override fun onBindViewHolder(holder: SlideVH, position: Int) {
            holder.bind(slides[position])
        }

        override fun getItemCount(): Int = slides.size
    }

    class HeroVH(
        view: View,
        onFocused: () -> Unit,
        private val onSlideSelected: (Int, HeroUiState) -> Unit,
        onPlay: (HeroUiState) -> Unit,
        onDetails: (HeroUiState) -> Unit
    ) : RecyclerView.ViewHolder(view) {
        private val pagerRecycler: RecyclerView = view.findViewById(R.id.heroPagerRecycler)
        private val pagerBar: LinearLayout = view.findViewById(R.id.heroPagerBar)
        private val btnPrev: TextView = view.findViewById(R.id.btnHeroPrev)
        private val dotsContainer: LinearLayout = view.findViewById(R.id.heroDotsContainer)
        private val btnNext: TextView = view.findViewById(R.id.btnHeroNext)
        private val counterText: TextView = view.findViewById(R.id.heroSlideCounter)

        private val layoutManager = LinearLayoutManager(view.context, LinearLayoutManager.HORIZONTAL, false)
        private val snapHelper = PagerSnapHelper()
        private val slideAdapter = HeroSlideAdapter(
            onFocused = onFocused,
            onPlay = onPlay,
            onDetails = onDetails,
            onStepSlide = { delta, focusPlay -> stepSlide(delta, focusPlay) }
        )
        private var slides: List<HeroUiState> = emptyList()
        private var currentIndex: Int = 0

        init {
            pagerRecycler.layoutManager = layoutManager
            pagerRecycler.adapter = slideAdapter
            pagerRecycler.itemAnimator = null
            snapHelper.attachToRecyclerView(pagerRecycler)

            pagerRecycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        syncSnappedSlide()
                    }
                }
            })

            btnPrev.setOnClickListener { stepSlide(-1, focusPlay = false) }
            btnNext.setOnClickListener { stepSlide(1, focusPlay = false) }
            btnPrev.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) onFocused() }
            btnNext.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) onFocused() }
        }

        private fun syncSnappedSlide() {
            val snapView = snapHelper.findSnapView(layoutManager) ?: return
            val pos = layoutManager.getPosition(snapView)
            if (pos in slides.indices && pos != currentIndex) {
                currentIndex = pos
                updatePagerChrome()
                onSlideSelected(pos, slides[pos])
            }
        }

        private fun stepSlide(delta: Int, focusPlay: Boolean) {
            if (slides.size <= 1) return
            val nextIdx = (currentIndex + delta + slides.size) % slides.size
            goToSlide(nextIdx, focusPlay)
        }

        private fun goToSlide(targetIdx: Int, focusPlay: Boolean) {
            if (targetIdx !in slides.indices) return
            currentIndex = targetIdx
            pagerRecycler.smoothScrollToPosition(targetIdx)
            updatePagerChrome()
            onSlideSelected(targetIdx, slides[targetIdx])
            if (focusPlay) {
                pagerRecycler.postDelayed({
                    (pagerRecycler.findViewHolderForAdapterPosition(targetIdx) as? HeroSlideAdapter.SlideVH)
                        ?.focusPlay()
                }, 90L)
            }
        }

        fun bind(newSlides: List<HeroUiState>, targetIndex: Int) {
            slides = newSlides
            currentIndex = targetIndex.coerceIn(0, (slides.size - 1).coerceAtLeast(0))
            slideAdapter.submitSlides(slides)
            if (slides.isNotEmpty()) {
                pagerRecycler.scrollToPosition(currentIndex)
            }
            updatePagerChrome()
        }

        private fun updatePagerChrome() {
            if (slides.size <= 1) {
                pagerBar.visibility = View.GONE
                return
            }
            pagerBar.visibility = View.VISIBLE
            counterText.text = "${currentIndex + 1} / ${slides.size}"

            val density = itemView.resources.displayMetrics.density
            dotsContainer.removeAllViews()
            for (i in slides.indices) {
                val active = i == currentIndex
                val dot = View(itemView.context).apply {
                    val w = ((if (active) 20f else 7f) * density).toInt()
                    val h = (7f * density).toInt()
                    val margin = (4f * density).toInt()
                    layoutParams = LinearLayout.LayoutParams(w, h).apply {
                        setMargins(margin, 0, margin, 0)
                    }
                    background = GradientDrawable().apply {
                        cornerRadius = h / 2f
                        setColor(if (active) 0xFF00E5A3.toInt() else 0x66FFFFFF)
                    }
                    setOnClickListener { goToSlide(i, focusPlay = false) }
                }
                dotsContainer.addView(dot)
            }
        }

        fun focusPlay() {
            pagerRecycler.post {
                (pagerRecycler.findViewHolderForAdapterPosition(currentIndex) as? HeroSlideAdapter.SlideVH)
                    ?.focusPlay()
            }
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
            HeroVH(
                view = view,
                onFocused = onHeroFocused,
                onSlideSelected = { idx, slide ->
                    selectedHeroIndex = idx
                    onHeroSlideSelected(slide)
                },
                onPlay = onHeroPlayClicked,
                onDetails = onHeroDetailsClicked
            )
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
            holder.bind(heroSlides, selectedHeroIndex)
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
