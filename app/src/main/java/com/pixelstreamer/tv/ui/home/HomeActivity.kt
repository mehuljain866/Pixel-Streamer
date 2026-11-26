package com.pixelstreamer.tv.ui.home

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.pixelstreamer.tv.PixelStreamerApp
import com.pixelstreamer.tv.R
import com.pixelstreamer.tv.data.model.MediaItem
import com.pixelstreamer.tv.data.model.RowStyle
import com.pixelstreamer.tv.data.model.ShowGroup
import com.pixelstreamer.tv.databinding.ActivityHomeBinding
import com.pixelstreamer.tv.ui.albums.AlbumsActivity
import com.pixelstreamer.tv.ui.detail.DetailActivity
import com.pixelstreamer.tv.ui.photos.PhotoViewerActivity
import com.pixelstreamer.tv.ui.player.PlayerActivity
import com.pixelstreamer.tv.ui.shows.ShowDetailActivity
import com.pixelstreamer.tv.util.QrGenerator
import com.pixelstreamer.tv.util.TitleCleaner
import com.pixelstreamer.tv.util.bindTitleLogo
import com.pixelstreamer.tv.util.loadUrlWithFallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private val app by lazy { application as PixelStreamerApp }
    private val repo by lazy { app.repository }
    private lateinit var rowAdapter: RowAdapter

    private var currentTab = "HOME"
    private var sortAZ = false
    private var heroItem: MediaItem? = null
    private var heroShow: ShowGroup? = null
    private var quickResumeTarget: MediaItem? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecycler()
        setupNav()
        setupQuickResumeOverlay()
        setupSearchOverlay()
        observeState()
        handleIncomingDeepLink(intent)

        lifecycleScope.launch {
            repo.preIndexContinueWatchingAndHero()
            repo.syncAllEnabledAlbums()
            repo.preIndexContinueWatchingAndHero()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingDeepLink(intent)
    }

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch(Dispatchers.IO) {
            val url = app.acquireCompanionServer()
            if (url != null) {
                val bmp = QrGenerator.generate(url, 320)
                runOnUiThread {
                    binding.emptyCompanionUrl.text =
                        "Scan with your phone or open $url on Wi-Fi to paste a Google Photos shared album link."
                    binding.emptyQrImage.setImageBitmap(bmp)
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        app.releaseCompanionServer()
    }

    private fun handleIncomingDeepLink(intent: Intent?) {
        val uri = intent?.data?.toString() ?: return
        if (uri.contains("photos.app.goo.gl") || uri.contains("photos.google.com/share")) {
            lifecycleScope.launch {
                repo.addAndSyncAlbum(uri)
            }
        }
    }

    private fun setupRecycler() {
        rowAdapter = RowAdapter(
            onHeroPlayClicked = { handleHeroPlayClick() },
            onHeroDetailsClicked = { handleHeroDetailsClick() },
            onItemFocused = { item -> updateHeroForItem(item) },
            onItemClicked = { item, style ->
                if (style == RowStyle.CONTINUE_WATCHING) {
                    showQuickResume(item)
                } else {
                    openMediaItem(item)
                }
            },
            onShowFocused = { show -> updateHeroForShow(show) },
            onShowClicked = { show -> openShow(show) }
        )
        binding.rowsRecycler.layoutManager = LinearLayoutManager(this)
        binding.rowsRecycler.adapter = rowAdapter
        binding.rowsRecycler.itemAnimator = null
        binding.gridRecycler.itemAnimator = null
    }

    private fun setupNav() {
        val selectTab = { tab: String ->
            currentTab = tab
            updateTabSelection()
            refreshCurrentView()
        }
        binding.navHome.setOnClickListener { selectTab("HOME") }
        binding.navMovies.setOnClickListener { selectTab("MOVIES") }
        binding.navShows.setOnClickListener { selectTab("SHOWS") }
        binding.navPhotos.setOnClickListener { selectTab("PHOTOS") }

        val tabViews = listOf(
            binding.navHome to "HOME",
            binding.navMovies to "MOVIES",
            binding.navShows to "SHOWS",
            binding.navPhotos to "PHOTOS"
        )
        for ((tv, tabName) in tabViews) {
            tv.setOnFocusChangeListener { _, hasFocus ->
                updateTabTextColor(tv, hasFocus || currentTab == tabName)
            }
        }

        binding.filterAll.setOnClickListener {
            sortAZ = false
            refreshGridTab()
        }
        binding.filterAZ.setOnClickListener {
            sortAZ = true
            refreshGridTab()
        }
        binding.btnGridSlideshow.setOnClickListener {
            val firstPhoto = repo.getPhotoItems().firstOrNull() ?: return@setOnClickListener
            openMediaItem(firstPhoto)
        }

        binding.navSearch.setOnClickListener {
            openSearchOverlay()
        }
        binding.navRefresh.setOnClickListener {
            lifecycleScope.launch { repo.syncAllEnabledAlbums() }
        }
        binding.navAlbums.setOnClickListener {
            startActivity(Intent(this, AlbumsActivity::class.java))
        }
        binding.btnEmptyAddAlbum.setOnClickListener {
            startActivity(Intent(this, AlbumsActivity::class.java))
        }

        updateTabSelection()
    }

    private fun handleHeroPlayClick() {
        val show = heroShow
        val target = heroItem
        when {
            show != null -> {
                val ep = show.nextUpEpisode
                val prog = repo.progressMap.value[ep.id]
                val startMs = if (prog != null && prog.isInProgress) prog.positionMs else 0L
                launchPlayer(ep.id, startMs)
            }
            target != null -> {
                if (target.isVideo) {
                    val prog = repo.progressMap.value[target.id]
                    val startMs = if (prog != null && prog.isInProgress) prog.positionMs else 0L
                    launchPlayer(target.id, startMs)
                } else {
                    openMediaItem(target)
                }
            }
            else -> startActivity(Intent(this, AlbumsActivity::class.java))
        }
    }

    private fun handleHeroDetailsClick() {
        val show = heroShow
        val target = heroItem
        when {
            show != null -> openShow(show)
            target != null -> openMediaItem(target)
            else -> startActivity(Intent(this, AlbumsActivity::class.java))
        }
    }

    private fun setupQuickResumeOverlay() {
        binding.quickResumeBackdrop.setOnClickListener { hideQuickResume() }
        binding.btnQrResume.setOnClickListener {
            val item = quickResumeTarget ?: return@setOnClickListener
            val prog = repo.progressMap.value[item.id]
            val startMs = if (prog != null && prog.isInProgress) prog.positionMs else 0L
            hideQuickResume()
            launchPlayer(item.id, startMs)
        }
        binding.btnQrMoreInfo.setOnClickListener {
            val item = quickResumeTarget ?: return@setOnClickListener
            hideQuickResume()
            if (item.isEpisode) {
                val key = TitleCleaner.normalizeSeriesKey(item.seriesDisplayTitle)
                val show = repo.getShowByKey(key)
                if (show != null) {
                    openShow(show)
                    return@setOnClickListener
                }
            }
            openMediaItem(item)
        }
        binding.btnQrRestart.setOnClickListener {
            val item = quickResumeTarget ?: return@setOnClickListener
            hideQuickResume()
            launchPlayer(item.id, 0L)
        }
    }

    private fun showQuickResume(item: MediaItem) {
        quickResumeTarget = item
        val prog = repo.progressMap.value[item.id]
        binding.qrCardThumb.loadUrlWithFallback(
            primaryUrl = item.posterImageUrl,
            fallbackUrl = item.thumbnailUrl(600, 900),
            placeholderRes = R.color.nf_surface
        )
        binding.qrCardTitle.text = if (item.isEpisode) item.seriesDisplayTitle else item.displayTitle
        binding.qrCardTitleLogo.bindTitleLogo(item.titleLogoUrl, binding.qrCardTitle)
        binding.qrCardSubtitle.text = if (item.isEpisode) {
            listOf(item.episodeCode, item.episodeTitle).filter { it.isNotBlank() }.joinToString(" · ")
        } else {
            item.movieMetadata?.year?.toString().orEmpty()
        }
        binding.qrCardProgress.progress = ((prog?.fraction ?: 0f) * 100).toInt().coerceIn(2, 100)
        binding.qrCardRemaining.text = prog?.remainingText?.ifBlank { item.displayDuration } ?: item.displayDuration

        binding.quickResumeBackdrop.visibility = View.VISIBLE
        binding.btnQrResume.requestFocus()
    }

    private fun hideQuickResume() {
        quickResumeTarget = null
        binding.quickResumeBackdrop.visibility = View.GONE
    }

    private fun updateTabSelection() {
        binding.navHome.isSelected = currentTab == "HOME"
        binding.navMovies.isSelected = currentTab == "MOVIES"
        binding.navShows.isSelected = currentTab == "SHOWS"
        binding.navPhotos.isSelected = currentTab == "PHOTOS"

        updateTabTextColor(binding.navHome, currentTab == "HOME" || binding.navHome.isFocused)
        updateTabTextColor(binding.navMovies, currentTab == "MOVIES" || binding.navMovies.isFocused)
        updateTabTextColor(binding.navShows, currentTab == "SHOWS" || binding.navShows.isFocused)
        updateTabTextColor(binding.navPhotos, currentTab == "PHOTOS" || binding.navPhotos.isFocused)
    }

    private fun updateTabTextColor(tv: TextView, active: Boolean) {
        tv.setTextColor(if (active) 0xFF111115.toInt() else Color.WHITE)
    }

    private fun observeState() {
        lifecycleScope.launch {
            combine(
                repo.albums,
                repo.itemsByAlbum,
                repo.progressMap
            ) { _, _, _ -> Unit }.collect {
                refreshCurrentView()
            }
        }

        lifecycleScope.launch {
            repo.isSyncing.collect { syncing ->
                binding.syncProgress.visibility = if (syncing) View.VISIBLE else View.GONE
            }
        }

        lifecycleScope.launch {
            repo.syncError.collect { err ->
                if (err.isNullOrBlank()) {
                    binding.statusBanner.visibility = View.GONE
                } else {
                    binding.statusBanner.visibility = View.VISIBLE
                    binding.statusBanner.text = err
                }
            }
        }
    }

    private fun refreshCurrentView() {
        if (currentTab == "HOME") {
            binding.heroBackdropLayer.visibility = View.VISIBLE
            binding.homeModeContainer.visibility = View.VISIBLE
            binding.gridModeContainer.visibility = View.GONE
            refreshHomeTab()
        } else {
            binding.heroBackdropLayer.visibility = View.GONE
            binding.homeModeContainer.visibility = View.GONE
            binding.gridModeContainer.visibility = View.VISIBLE
            refreshGridTab()
        }
    }

    private fun refreshHomeTab() {
        val rows = repo.buildHomeRows()
        val progress = repo.progressMap.value
        rowAdapter.submit(rows, progress)

        val wasEmptyVisible = binding.emptyStateContainer.visibility == View.VISIBLE
        val hasContent = rows.isNotEmpty()
        binding.rowsRecycler.visibility = if (hasContent) View.VISIBLE else View.GONE
        binding.emptyStateContainer.visibility = if (hasContent) View.GONE else View.VISIBLE

        if (hasContent) {
            if (wasEmptyVisible) {
                binding.rowsRecycler.post {
                    (binding.rowsRecycler.findViewHolderForAdapterPosition(0) as? RowAdapter.HeroVH)?.focusPlay()
                }
            }
            val firstShow = rows.firstOrNull { it.shows.isNotEmpty() }?.shows?.firstOrNull()
            val firstItem = rows.firstOrNull { it.items.isNotEmpty() }?.items?.firstOrNull()
            if (heroItem == null && heroShow == null) {
                if (firstItem != null) updateHeroForItem(firstItem)
                else if (firstShow != null) updateHeroForShow(firstShow)
            } else if (heroItem != null) {
                repo.getItemById(heroItem!!.id)?.let { updateHeroForItem(it) }
                    ?: firstItem?.let { updateHeroForItem(it) }
            }
        } else {
            showWelcomeHero()
        }
    }

    private fun refreshGridTab() {
        val progress = repo.progressMap.value
        binding.btnGridSlideshow.visibility = if (currentTab == "PHOTOS") View.VISIBLE else View.GONE
        binding.filterAll.visibility = if (currentTab == "PHOTOS") View.GONE else View.VISIBLE
        binding.filterAZ.visibility = if (currentTab == "PHOTOS") View.GONE else View.VISIBLE

        when (currentTab) {
            "MOVIES" -> {
                val base = repo.getMovieItems()
                val sorted = if (sortAZ) base.sortedBy { it.displayTitle.lowercase() } else base
                binding.gridTitle.text = "Movies"
                binding.gridCount.text = "${sorted.size} title${if (sorted.size == 1) "" else "s"}"
                binding.gridEmptyMessage.text = "No movies yet. Share a Google Photos album with movies via Settings (⚙)."
                binding.gridEmptyMessage.visibility = if (sorted.isEmpty()) View.VISIBLE else View.GONE
                binding.gridRecycler.layoutManager = GridLayoutManager(this, 6)
                binding.gridRecycler.adapter = CardAdapter(
                    style = RowStyle.MOVIES_POSTER,
                    items = sorted,
                    progressMap = progress,
                    onItemClicked = { openMediaItem(it) }
                )
            }
            "SHOWS" -> {
                val base = repo.buildShows()
                val sorted = if (sortAZ) base.sortedBy { it.title.lowercase() } else base
                binding.gridTitle.text = "TV Shows"
                binding.gridCount.text = "${sorted.size} series"
                binding.gridEmptyMessage.text =
                    "No TV shows detected yet. Episodes with S01E01 / 1x01 or albums marked 'Shows' appear here chronologically."
                binding.gridEmptyMessage.visibility = if (sorted.isEmpty()) View.VISIBLE else View.GONE
                binding.gridRecycler.layoutManager = GridLayoutManager(this, 6)
                binding.gridRecycler.adapter = CardAdapter(
                    style = RowStyle.SHOWS_POSTER,
                    shows = sorted,
                    progressMap = progress,
                    onShowClicked = { openShow(it) }
                )
            }
            "PHOTOS" -> {
                val photos = repo.getPhotoItems()
                binding.gridTitle.text = "Photos"
                binding.gridCount.text = "${photos.size} photo${if (photos.size == 1) "" else "s"}"
                binding.gridEmptyMessage.text = "No photos yet. Share a photo album in Settings (⚙)."
                binding.gridEmptyMessage.visibility = if (photos.isEmpty()) View.VISIBLE else View.GONE
                binding.gridRecycler.layoutManager = GridLayoutManager(this, 4)
                binding.gridRecycler.adapter = CardAdapter(
                    style = RowStyle.PHOTOS_LANDSCAPE,
                    items = photos,
                    progressMap = progress,
                    onItemClicked = { openMediaItem(it) }
                )
            }
        }
    }

    private fun showWelcomeHero() {
        heroItem = null
        heroShow = null
        binding.heroBackdrop.setImageResource(R.color.nf_bg)
    }

    private fun updateHeroForItem(item: MediaItem) {
        heroItem = item
        heroShow = null
        binding.heroBackdrop.loadUrlWithFallback(
            primaryUrl = item.backdropImageUrl,
            fallbackUrl = item.thumbnailUrl(1280, 720)
        )

        val meta = item.movieMetadata
        val parts = mutableListOf<String>()
        meta?.year?.let { parts.add(it.toString()) }
        if (item.displayDuration.isNotBlank()) parts.add(item.displayDuration)
        else meta?.runtimeMinutes?.let { parts.add("${it}m") }
        meta?.rating?.let { parts.add("★ ${"%.1f".format(it)}") }
        if (meta?.contentRating?.isNotBlank() == true) parts.add(meta.contentRating)
        if (meta?.genres?.isNotEmpty() == true) parts.add(meta.genres.take(2).joinToString(" · "))
        if (parts.isEmpty()) {
            parts.add(if (item.isVideo) "${item.width}×${item.height}" else "Photo")
        }

        val overview = when {
            item.isEpisode && item.episodeOverview.isNotBlank() -> item.episodeOverview
            meta?.overview?.isNotBlank() == true -> meta.overview
            item.caption.isNotBlank() && !item.caption.contains("_1080p", true) && !item.caption.endsWith(".mp4", true) -> item.caption
            else -> if (item.isVideo) "Stream directly from your shared Google Photos library." else "Shared Google Photos gallery."
        }

        val prog = repo.progressMap.value[item.id]
        val playLabel = when {
            !item.isVideo -> "▶  View Photo"
            prog != null && prog.isInProgress -> "▶  Resume"
            else -> "▶  Play"
        }

        rowAdapter.updateHero(
            HeroUiState(
                title = item.displayTitle,
                titleLogoUrl = item.titleLogoUrl,
                meta = parts.joinToString("   •   "),
                overview = overview,
                playLabel = playLabel,
                showDetailsButton = true
            ),
            binding.rowsRecycler
        )
    }

    private fun updateHeroForShow(show: ShowGroup) {
        heroShow = show
        heroItem = null
        binding.heroBackdrop.loadUrlWithFallback(
            primaryUrl = show.backdropUrl.orEmpty(),
            fallbackUrl = show.posterUrl
        )

        val parts = mutableListOf<String>()
        show.year?.let { parts.add(it.toString()) }
        parts.add("${show.seasonCount} Season${if (show.seasonCount == 1) "" else "s"}")
        parts.add("${show.episodeCount} Episodes")
        show.rating?.let { parts.add("★ ${"%.1f".format(it)}") }
        if (show.genres.isNotEmpty()) parts.add(show.genres.take(2).joinToString(" · "))

        val ep = show.nextUpEpisode
        val prog = repo.progressMap.value[ep.id]
        val code = ep.episodeCode.ifBlank { "E01" }
        val playLabel = if (prog != null && prog.isInProgress) "▶  Resume $code" else "▶  Play $code"

        rowAdapter.updateHero(
            HeroUiState(
                title = show.title,
                titleLogoUrl = show.logoUrl,
                meta = parts.joinToString("   •   "),
                overview = show.overview,
                playLabel = playLabel,
                showDetailsButton = true
            ),
            binding.rowsRecycler
        )
    }

    private fun launchPlayer(itemId: String, startMs: Long) {
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_ITEM_ID, itemId)
                .putExtra(PlayerActivity.EXTRA_RESUME_MS, startMs)
        )
    }

    private fun openMediaItem(item: MediaItem) {
        if (item.isVideo) {
            startActivity(
                Intent(this, DetailActivity::class.java)
                    .putExtra(DetailActivity.EXTRA_ITEM_ID, item.id)
            )
        } else {
            startActivity(
                Intent(this, PhotoViewerActivity::class.java)
                    .putExtra(PhotoViewerActivity.EXTRA_ALBUM_ID, item.albumId)
                    .putExtra(PhotoViewerActivity.EXTRA_START_ITEM_ID, item.id)
            )
        }
    }

    private fun openShow(show: ShowGroup) {
        startActivity(
            Intent(this, ShowDetailActivity::class.java)
                .putExtra(ShowDetailActivity.EXTRA_SERIES_KEY, show.seriesKey)
        )
    }

    private fun setupSearchOverlay() {
        binding.searchResultsRecycler.layoutManager = GridLayoutManager(this, 6)
        binding.searchResultsRecycler.itemAnimator = null

        binding.btnCloseSearch.setOnClickListener { hideSearchOverlay() }
        binding.btnClearSearch.setOnClickListener {
            binding.searchInput.setText("")
            binding.searchInput.requestFocus()
        }
        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString().orEmpty()
                binding.btnClearSearch.visibility = if (q.isNotBlank()) View.VISIBLE else View.GONE
                updateSearchResults(q)
            }
        })
    }

    private fun openSearchOverlay() {
        binding.searchOverlay.visibility = View.VISIBLE
        updateSearchResults(binding.searchInput.text?.toString().orEmpty())
        binding.searchInput.requestFocus()
    }

    private fun hideSearchOverlay() {
        binding.searchOverlay.visibility = View.GONE
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.searchInput.windowToken, 0)
        binding.navSearch.requestFocus()
    }

    private fun updateSearchResults(rawQuery: String) {
        val trimmed = rawQuery.trim()
        val (shows, items) = repo.searchLibrary(trimmed)
        val total = shows.size + items.size
        binding.searchSummary.text = if (trimmed.isBlank()) {
            "Browse All ($total)"
        } else {
            "$total result${if (total == 1) "" else "s"} for \"$trimmed\""
        }
        binding.searchEmptyMessage.visibility = if (total == 0) View.VISIBLE else View.GONE
        binding.searchResultsRecycler.adapter = CardAdapter(
            style = RowStyle.MOVIES_POSTER,
            items = items,
            shows = shows,
            progressMap = repo.progressMap.value,
            onShowClicked = { show ->
                hideSearchOverlay()
                openShow(show)
            },
            onItemClicked = { item ->
                hideSearchOverlay()
                openMediaItem(item)
            }
        )
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_SEARCH) {
            openSearchOverlay()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BACK && binding.searchOverlay.visibility == View.VISIBLE) {
            hideSearchOverlay()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BACK && binding.quickResumeBackdrop.visibility == View.VISIBLE) {
            hideQuickResume()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
