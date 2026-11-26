package com.pixelstreamer.tv.data.repo

import android.content.Context
import com.pixelstreamer.tv.data.model.AlbumSource
import com.pixelstreamer.tv.data.model.AlbumType
import com.pixelstreamer.tv.data.model.HeroSlide
import com.pixelstreamer.tv.data.model.HomeRow
import com.pixelstreamer.tv.data.model.MediaItem
import com.pixelstreamer.tv.data.model.MovieMetadata
import com.pixelstreamer.tv.data.model.PlaybackProgress
import com.pixelstreamer.tv.data.model.RowStyle
import com.pixelstreamer.tv.data.model.ShowGroup
import com.pixelstreamer.tv.data.model.VideoQuality
import com.pixelstreamer.tv.data.network.GooglePhotosAlbumScraper
import com.pixelstreamer.tv.data.network.MovieMetadataResolver
import com.pixelstreamer.tv.data.network.StreamPreIndexer
import com.pixelstreamer.tv.data.network.TvShowBundle
import com.pixelstreamer.tv.util.TitleCleaner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.TreeMap
import java.util.UUID

class MediaRepository(
    private val context: Context,
    private val scraper: GooglePhotosAlbumScraper,
    private val metadataResolver: MovieMetadataResolver,
    private val preIndexer: StreamPreIndexer? = null
) {
    private val prefs = context.getSharedPreferences("pixelstreamer_prefs", Context.MODE_PRIVATE)
    private val cacheFile = File(context.filesDir, "albums_cache_v1.json")
    private val stateMutex = Mutex()
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlightAlbumAdds = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private val _albums = MutableStateFlow<List<AlbumSource>>(emptyList())
    val albums: StateFlow<List<AlbumSource>> = _albums.asStateFlow()

    private val _itemsByAlbum = MutableStateFlow<Map<String, List<MediaItem>>>(emptyMap())
    val itemsByAlbum: StateFlow<Map<String, List<MediaItem>>> = _itemsByAlbum.asStateFlow()

    private val _progressMap = MutableStateFlow<Map<String, PlaybackProgress>>(emptyMap())
    val progressMap: StateFlow<Map<String, PlaybackProgress>> = _progressMap.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _syncError = MutableStateFlow<String?>(null)
    val syncError: StateFlow<String?> = _syncError.asStateFlow()

    init {
        loadFromDisk()
    }

    var tmdbApiKey: String
        get() = prefs.getString(KEY_TMDB_API_KEY, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_TMDB_API_KEY, value.trim()).apply()
        }

    var defaultQuality: VideoQuality
        get() {
            val name = prefs.getString(KEY_DEFAULT_QUALITY, VideoQuality.AUTO.name).orEmpty()
            return VideoQuality.entries.firstOrNull { it.name == name } ?: VideoQuality.AUTO
        }
        set(value) {
            prefs.edit().putString(KEY_DEFAULT_QUALITY, value.name).apply()
        }

    var slideshowSeconds: Int
        get() = prefs.getInt(KEY_SLIDESHOW_SEC, 8).coerceIn(3, 60)
        set(value) {
            prefs.edit().putInt(KEY_SLIDESHOW_SEC, value.coerceIn(3, 60)).apply()
        }

    fun clearError() {
        _syncError.value = null
    }

    suspend fun addAndSyncAlbum(
        url: String,
        customTitle: String = "",
        type: AlbumType = AlbumType.AUTO
    ): Result<AlbumSource> = withContext(Dispatchers.IO) {
        val cleanUrl = scraper.extractUrlFromText(url)
            ?: return@withContext Result.failure(IllegalArgumentException("Invalid Google Photos link"))
        if (!inFlightAlbumAdds.add(cleanUrl)) {
            return@withContext Result.failure(IllegalStateException("Album is already syncing"))
        }
        try {
            _isSyncing.value = true
            _syncError.value = null

            val existingMatch = _albums.value.firstOrNull { it.url == cleanUrl || it.resolvedUrl == cleanUrl }
            val albumId = existingMatch?.id ?: UUID.randomUUID().toString().take(12)
            val initialSource = AlbumSource(
                id = albumId,
                url = cleanUrl,
                customTitle = customTitle.trim().ifBlank { existingMatch?.customTitle.orEmpty() },
                type = if (type != AlbumType.AUTO) type else (existingMatch?.type ?: AlbumType.AUTO),
                enabled = true
            )

            // Phase 1 (fast): scrape album + parse episodes/titles and publish immediately so TV & phone update fast.
            val existingById = _itemsByAlbum.value[albumId].orEmpty().associateBy { it.id }
            val scraped = scraper.fetchAlbum(cleanUrl, cachedItemsById = existingById)
            val initialParsed = buildInitialParsedItems(initialSource, scraped, existingById)
            val initialAlbum = initialSource.copy(
                resolvedUrl = scraped.resolvedUrl,
                title = scraped.albumTitle,
                lastSyncedAt = System.currentTimeMillis(),
                itemCount = initialParsed.size
            )

            val savedAlbum = stateMutex.withLock {
                val existing = _albums.value.firstOrNull {
                    it.resolvedUrl == initialAlbum.resolvedUrl || it.url == cleanUrl
                }
                val finalAlbum = if (existing != null) {
                    initialAlbum.copy(
                        id = existing.id,
                        customTitle = customTitle.trim().ifBlank { existing.customTitle },
                        type = if (type != AlbumType.AUTO) type else existing.type
                    )
                } else {
                    initialAlbum
                }
                val updatedItems = mergeUserOverrides(
                    existingItems = _itemsByAlbum.value[finalAlbum.id].orEmpty(),
                    freshItems = initialParsed.map { it.copy(albumId = finalAlbum.id) }
                )
                val updatedAlbums = _albums.value.filterNot { it.id == finalAlbum.id } + finalAlbum
                _albums.value = updatedAlbums
                _itemsByAlbum.value = _itemsByAlbum.value.toMutableMap().apply {
                    put(finalAlbum.id, updatedItems)
                }
                saveToDisk()
                finalAlbum
            }

            // Phase 2 (background): fetch posters, Title Logos, and episode details without blocking phone/TV response.
            backgroundScope.launch {
                runCatching { enrichAlbumMetadataInBackground(savedAlbum.id) }
                runCatching { preIndexContinueWatchingAndHero() }
            }

            Result.success(savedAlbum)
        } catch (e: Exception) {
            _syncError.value = e.message ?: "Failed to sync shared album"
            Result.failure(e)
        } finally {
            inFlightAlbumAdds.remove(cleanUrl)
            _isSyncing.value = false
        }
    }

    suspend fun syncAllEnabledAlbums() = withContext(Dispatchers.IO) {
        val currentAlbums = _albums.value.filter { it.enabled }
        if (currentAlbums.isEmpty()) return@withContext
        _isSyncing.value = true
        _syncError.value = null
        try {
            for (album in currentAlbums) {
                try {
                    val synced = syncSingleAlbumInternal(album)
                    stateMutex.withLock {
                        val merged = mergeUserOverrides(
                            existingItems = _itemsByAlbum.value[album.id].orEmpty(),
                            freshItems = synced.second.map { it.copy(albumId = album.id) }
                        )
                        // Merge onto latest AlbumSource inside lock so concurrent enable/title/type edits aren't lost.
                        _albums.value = _albums.value.map { current ->
                            if (current.id == album.id) {
                                current.copy(
                                    resolvedUrl = synced.first.resolvedUrl,
                                    title = synced.first.title,
                                    lastSyncedAt = synced.first.lastSyncedAt,
                                    itemCount = merged.size
                                )
                            } else {
                                current
                            }
                        }
                        _itemsByAlbum.value = _itemsByAlbum.value.toMutableMap().apply {
                            put(album.id, merged)
                        }
                        saveToDisk()
                    }
                } catch (e: Exception) {
                    _syncError.value = "Album '${album.displayTitle}': ${e.message}"
                }
            }
        } finally {
            _isSyncing.value = false
        }
    }

    private fun buildInitialParsedItems(
        album: AlbumSource,
        scraped: com.pixelstreamer.tv.data.network.ScrapedAlbumResult,
        existingById: Map<String, MediaItem>
    ): List<MediaItem> {
        val albumDisplayHint = album.customTitle.ifBlank { scraped.albumTitle }
        val forceShowAlbum = album.type == AlbumType.SHOWS
        return scraped.items.map { item ->
            val prev = existingById[item.id]
            val customTitle = prev?.customTitle.orEmpty()
            val candidateTitle = customTitle
                .ifBlank { item.caption }
                .ifBlank { item.originalFilename }
            val parsed = if (item.isVideo && candidateTitle.isNotBlank() && album.type != AlbumType.PHOTOS) {
                TitleCleaner.parse(
                    rawInput = candidateTitle,
                    albumTitleHint = albumDisplayHint,
                    forceShowAlbum = forceShowAlbum
                )
            } else {
                null
            }
            if (parsed != null && parsed.isEpisode && album.type != AlbumType.MOVIES) {
                item.copy(
                    albumId = album.id,
                    customTitle = customTitle,
                    hidden = prev?.hidden ?: false,
                    movieMetadata = prev?.movieMetadata,
                    isEpisode = true,
                    seriesTitle = prev?.seriesTitle?.ifBlank { parsed.queryTitle } ?: parsed.queryTitle,
                    seasonNumber = parsed.season ?: 1,
                    episodeNumber = parsed.episode,
                    episodeTitle = prev?.episodeTitle?.ifBlank { parsed.episodeTitleHint } ?: parsed.episodeTitleHint,
                    episodeOverview = prev?.episodeOverview.orEmpty(),
                    episodeStillUrl = prev?.episodeStillUrl
                )
            } else {
                item.copy(
                    albumId = album.id,
                    customTitle = customTitle,
                    hidden = prev?.hidden ?: false,
                    movieMetadata = prev?.movieMetadata,
                    isEpisode = false
                )
            }
        }
    }

    private suspend fun enrichAlbumMetadataInBackground(albumId: String) {
        val album = _albums.value.firstOrNull { it.id == albumId } ?: return
        val itemsSnapshot = _itemsByAlbum.value[albumId].orEmpty()
        if (itemsSnapshot.isEmpty()) return

        val albumDisplayHint = album.customTitle.ifBlank { album.title }
        val forceShowAlbum = album.type == AlbumType.SHOWS
        val enriched = resolveMetadataForItems(itemsSnapshot, album, albumDisplayHint, forceShowAlbum)

        stateMutex.withLock {
            val current = _itemsByAlbum.value[albumId].orEmpty()
            val enrichedById = enriched.associateBy { it.id }
            val merged = current.map { cur ->
                val fresh = enrichedById[cur.id] ?: return@map cur
                cur.copy(
                    seriesTitle = fresh.seriesTitle.ifBlank { cur.seriesTitle },
                    episodeTitle = fresh.episodeTitle.ifBlank { cur.episodeTitle },
                    episodeOverview = fresh.episodeOverview.ifBlank { cur.episodeOverview },
                    episodeStillUrl = fresh.episodeStillUrl ?: cur.episodeStillUrl,
                    movieMetadata = fresh.movieMetadata ?: cur.movieMetadata
                )
            }
            _itemsByAlbum.value = _itemsByAlbum.value.toMutableMap().apply { put(albumId, merged) }
            saveToDisk()
        }
    }

    private suspend fun syncSingleAlbumInternal(album: AlbumSource): Pair<AlbumSource, List<MediaItem>> {
        val existingById = _itemsByAlbum.value[album.id].orEmpty().associateBy { it.id }
        val scraped = scraper.fetchAlbum(album.url, cachedItemsById = existingById)
        val albumDisplayHint = album.customTitle.ifBlank { scraped.albumTitle }
        val forceShowAlbum = album.type == AlbumType.SHOWS

        val resolvedItems = resolveMetadataForItems(scraped.items, album, albumDisplayHint, forceShowAlbum, existingById)

        val updatedAlbum = album.copy(
            resolvedUrl = scraped.resolvedUrl,
            title = scraped.albumTitle,
            lastSyncedAt = System.currentTimeMillis(),
            itemCount = resolvedItems.size
        )
        return updatedAlbum to resolvedItems
    }

    private suspend fun resolveMetadataForItems(
        items: List<MediaItem>,
        album: AlbumSource,
        albumDisplayHint: String,
        forceShowAlbum: Boolean,
        existingById: Map<String, MediaItem> = emptyMap()
    ): List<MediaItem> {
        val parsedPairs = items.map { item ->
            val prev = existingById[item.id] ?: item
            val customTitle = prev.customTitle
            val candidateTitle = customTitle
                .ifBlank { item.caption }
                .ifBlank { item.originalFilename }
            val parsed = if (item.isVideo && candidateTitle.isNotBlank() && album.type != AlbumType.PHOTOS) {
                TitleCleaner.parse(
                    rawInput = candidateTitle,
                    albumTitleHint = albumDisplayHint,
                    forceShowAlbum = forceShowAlbum
                )
            } else {
                null
            }
            Triple(item, prev, parsed)
        }

        val showBundles = java.util.concurrent.ConcurrentHashMap<String, TvShowBundle?>()
        val uniqueShowKeys = parsedPairs
            .mapNotNull { (_, prev, p) ->
                if (p != null && p.isEpisode && p.queryTitle.isNotBlank() && (prev.movieMetadata == null || prev.movieMetadata.logoUrl.isNullOrBlank())) {
                    TitleCleaner.normalizeSeriesKey(p.queryTitle) to p.queryTitle
                } else null
            }
            .distinctBy { it.first }

        coroutineScope {
            uniqueShowKeys.map { (key, title) ->
                async {
                    showBundles[key] = metadataResolver.resolveShowBundle(title, tmdbApiKey)
                }
            }.awaitAll()
        }

        val shouldLookupMovies = album.type == AlbumType.MOVIES || album.type == AlbumType.AUTO
        return coroutineScope {
            val sem = Semaphore(6)
            parsedPairs.map { (item, prev, parsed) ->
                async {
                    sem.withPermit {
                        val customTitle = prev.customTitle
                        val hidden = prev.hidden
                        val candidateTitle = customTitle
                            .ifBlank { item.caption }
                            .ifBlank { item.originalFilename }

                        if (parsed != null && parsed.isEpisode && album.type != AlbumType.MOVIES) {
                            val seriesKey = TitleCleaner.normalizeSeriesKey(parsed.queryTitle)
                            val bundle = showBundles[seriesKey]
                            val s = parsed.season ?: 1
                            val e = parsed.episode
                            val epInfo = if (e != null) bundle?.episodesByKey?.get(s to e) else null
                            val showMeta = bundle?.showMeta ?: prev.movieMetadata
                            val resolvedSeriesName = showMeta?.matchedTitle?.ifBlank { parsed.queryTitle } ?: parsed.queryTitle

                            item.copy(
                                albumId = album.id,
                                customTitle = customTitle,
                                hidden = hidden,
                                movieMetadata = showMeta,
                                isEpisode = true,
                                seriesTitle = resolvedSeriesName,
                                seasonNumber = s,
                                episodeNumber = e,
                                episodeTitle = epInfo?.title?.ifBlank { prev.episodeTitle.ifBlank { parsed.episodeTitleHint } }
                                    ?: prev.episodeTitle.ifBlank { parsed.episodeTitleHint },
                                episodeOverview = epInfo?.overview?.ifBlank { prev.episodeOverview } ?: prev.episodeOverview,
                                episodeStillUrl = epInfo?.stillUrl ?: prev.episodeStillUrl
                            )
                        } else {
                            val shouldMatch = item.isVideo && shouldLookupMovies && candidateTitle.isNotBlank()
                            val cachedMeta = prev.movieMetadata
                            val needsRefresh = cachedMeta == null || cachedMeta.logoUrl.isNullOrBlank()
                            val metadata = if (shouldMatch && needsRefresh) {
                                metadataResolver.resolve(
                                    rawTitle = candidateTitle,
                                    tmdbKey = tmdbApiKey,
                                    forceLookup = album.type == AlbumType.MOVIES || customTitle.isNotBlank() || item.caption.isNotBlank()
                                ) ?: cachedMeta
                            } else {
                                cachedMeta
                            }
                            item.copy(
                                albumId = album.id,
                                customTitle = customTitle,
                                hidden = hidden,
                                movieMetadata = metadata,
                                isEpisode = false
                            )
                        }
                    }
                }
            }.awaitAll()
        }
    }

    private fun mergeUserOverrides(
        existingItems: List<MediaItem>,
        freshItems: List<MediaItem>
    ): List<MediaItem> {
        val prevMap = existingItems.associateBy { it.id }
        return freshItems.map { fresh ->
            val prev = prevMap[fresh.id] ?: return@map fresh
            fresh.copy(
                customTitle = prev.customTitle,
                hidden = prev.hidden,
                seriesTitle = if (prev.movieMetadata != null && prev.seriesTitle.isNotBlank()) prev.seriesTitle else fresh.seriesTitle,
                episodeTitle = fresh.episodeTitle.ifBlank { prev.episodeTitle },
                episodeOverview = fresh.episodeOverview.ifBlank { prev.episodeOverview },
                episodeStillUrl = fresh.episodeStillUrl ?: prev.episodeStillUrl,
                movieMetadata = fresh.movieMetadata ?: prev.movieMetadata
            )
        }
    }

    suspend fun toggleAlbumEnabled(albumId: String, enabled: Boolean) {
        stateMutex.withLock {
            _albums.value = _albums.value.map { if (it.id == albumId) it.copy(enabled = enabled) else it }
            saveToDisk()
        }
    }

    suspend fun updateAlbumMeta(albumId: String, customTitle: String, type: AlbumType) {
        stateMutex.withLock {
            _albums.value = _albums.value.map {
                if (it.id == albumId) it.copy(customTitle = customTitle.trim(), type = type) else it
            }
            saveToDisk()
        }
    }

    suspend fun removeAlbum(albumId: String) {
        stateMutex.withLock {
            _albums.value = _albums.value.filterNot { it.id == albumId }
            _itemsByAlbum.value = _itemsByAlbum.value.toMutableMap().apply { remove(albumId) }
            saveToDisk()
        }
    }

    suspend fun toggleItemHidden(albumId: String, itemId: String, hidden: Boolean) {
        setItemsVisibility(albumId, mapOf(itemId to hidden))
    }

    suspend fun setItemsVisibility(albumId: String, hiddenById: Map<String, Boolean>) {
        if (hiddenById.isEmpty()) return
        stateMutex.withLock {
            val existing = _itemsByAlbum.value[albumId].orEmpty()
            val updated = applyVisibilityUpdates(existing, hiddenById)
            _itemsByAlbum.value = _itemsByAlbum.value.toMutableMap().apply { put(albumId, updated) }
            saveToDisk()
        }
    }

    suspend fun setCustomShowTitleAndRematch(
        seriesKey: String,
        newSeriesTitle: String
    ): Pair<String, MovieMetadata?> {
        val clean = newSeriesTitle.trim()
        if (clean.isBlank()) return seriesKey to null

        val bundle = metadataResolver.resolveShowBundle(clean, tmdbApiKey)
            ?: metadataResolver.resolve(clean, tmdbApiKey, forceLookup = true)?.let {
                TvShowBundle(showMeta = it, episodesByKey = emptyMap())
            }
        val resolvedSeriesName = bundle?.showMeta?.matchedTitle?.ifBlank { clean } ?: clean
        val newSeriesKey = TitleCleaner.normalizeSeriesKey(resolvedSeriesName).ifBlank {
            TitleCleaner.normalizeSeriesKey(clean)
        }

        stateMutex.withLock {
            val matchingIds = _itemsByAlbum.value.values
                .flatten()
                .filter { it.isVideo && it.isEpisode && TitleCleaner.normalizeSeriesKey(it.seriesDisplayTitle) == seriesKey }
                .map { it.id }
                .toSet()

            val updatedMap = _itemsByAlbum.value.mapValues { (_, list) ->
                applyShowRematchToItems(list, matchingIds, resolvedSeriesName, bundle)
            }
            _itemsByAlbum.value = updatedMap
            saveToDisk()
        }
        return newSeriesKey to bundle?.showMeta
    }

    suspend fun setCustomMovieTitleAndRematch(
        albumId: String,
        itemId: String,
        newTitle: String
    ): MovieMetadata? {
        val clean = newTitle.trim()
        val existing = getItemById(itemId)
        if (existing?.isEpisode == true && clean.isNotBlank()) {
            val key = TitleCleaner.normalizeSeriesKey(existing.seriesDisplayTitle).ifBlank { existing.albumId }
            return setCustomShowTitleAndRematch(key, clean).second
        }
        val resolved = if (clean.isNotBlank()) {
            metadataResolver.resolve(clean, tmdbApiKey, forceLookup = true)
        } else {
            null
        }
        stateMutex.withLock {
            val list = _itemsByAlbum.value[albumId].orEmpty().map { item ->
                if (item.id == itemId) {
                    item.copy(customTitle = clean, movieMetadata = resolved)
                } else {
                    item
                }
            }
            _itemsByAlbum.value = _itemsByAlbum.value.toMutableMap().apply { put(albumId, list) }
            saveToDisk()
        }
        return resolved
    }

    suspend fun savePlaybackProgress(mediaId: String, positionMs: Long, durationMs: Long) {
        stateMutex.withLock {
            val updated = _progressMap.value.toMutableMap()
            updated[mediaId] = PlaybackProgress(
                mediaId = mediaId,
                positionMs = positionMs,
                durationMs = durationMs,
                updatedAt = System.currentTimeMillis()
            )
            _progressMap.value = updated
            saveToDisk()
        }
    }

    fun getItemById(itemId: String): MediaItem? {
        for (list in _itemsByAlbum.value.values) {
            list.firstOrNull { it.id == itemId }?.let { return it }
        }
        return null
    }

    fun getAlbumItems(albumId: String, includeHidden: Boolean = false): List<MediaItem> {
        val list = _itemsByAlbum.value[albumId].orEmpty()
        return if (includeHidden) list else list.filter { !it.hidden }
    }

    fun getVisibleItems(): List<MediaItem> {
        val enabledIds = _albums.value.filter { it.enabled }.map { it.id }.toSet()
        return _itemsByAlbum.value
            .filterKeys { it in enabledIds }
            .values
            .flatten()
            .filter { !it.hidden }
    }

    fun getMovieItems(): List<MediaItem> {
        return getVisibleItems().filter { it.isVideo && !it.isEpisode }
    }

    fun getPhotoItems(): List<MediaItem> {
        return getVisibleItems().filter { !it.isVideo }
    }

    fun buildShows(): List<ShowGroup> {
        val progress = _progressMap.value
        val episodes = getVisibleItems().filter { it.isVideo && it.isEpisode }
        if (episodes.isEmpty()) return emptyList()

        val grouped = episodes.groupBy {
            TitleCleaner.normalizeSeriesKey(it.seriesDisplayTitle).ifBlank { it.albumId }
        }

        return grouped.map { (key, eps) ->
            val sorted = eps.sortedWith(
                compareBy<MediaItem>(
                    { it.seasonNumber ?: 1 },
                    { it.episodeNumber ?: Int.MAX_VALUE },
                    { it.episodeRowLabel }
                )
            )
            val seasons = TreeMap<Int, List<MediaItem>>()
            sorted.groupBy { it.seasonNumber ?: 1 }.forEach { (sNum, sList) ->
                seasons[sNum] = sList
            }

            // Pick Next Up chronologically: most recently in-progress, else episode right after last finished, else S01E01
            val inProgress = sorted
                .filter { progress[it.id]?.isInProgress == true }
                .maxByOrNull { progress[it.id]?.updatedAt ?: 0L }
            val nextUp = inProgress ?: run {
                val lastFinishedIdx = sorted.indexOfLast { progress[it.id]?.isFinished == true }
                if (lastFinishedIdx >= 0 && lastFinishedIdx + 1 < sorted.size) {
                    sorted[lastFinishedIdx + 1]
                } else {
                    sorted.first()
                }
            }

            val rep = sorted.firstOrNull { it.movieMetadata != null } ?: sorted.first()
            val meta = rep.movieMetadata
            ShowGroup(
                seriesKey = key,
                title = meta?.matchedTitle?.ifBlank { rep.seriesDisplayTitle } ?: rep.seriesDisplayTitle,
                posterUrl = meta?.posterUrl ?: rep.thumbnailUrl(600, 900),
                backdropUrl = meta?.backdropUrl ?: nextUp.backdropImageUrl,
                logoUrl = meta?.logoUrl,
                overview = meta?.overview?.takeIf { it.isNotBlank() }
                    ?: "${seasons.size} Season${if (seasons.size == 1) "" else "s"} · ${sorted.size} Episodes",
                year = meta?.year,
                rating = meta?.rating,
                genres = meta?.genres ?: emptyList(),
                seasons = seasons,
                allEpisodesChronological = sorted,
                nextUpEpisode = nextUp
            )
        }.sortedBy { it.title.lowercase() }
    }

    fun getShowByKey(seriesKey: String): ShowGroup? {
        return buildShows().firstOrNull { it.seriesKey == seriesKey }
    }

    fun searchLibrary(rawQuery: String): Pair<List<ShowGroup>, List<MediaItem>> {
        val q = rawQuery.trim().lowercase()
        if (q.isBlank()) return emptyList<ShowGroup>() to emptyList()
        val matchingShows = buildShows().filter { show ->
            show.title.lowercase().contains(q) ||
                show.genres.any { it.lowercase().contains(q) } ||
                show.allEpisodesChronological.any { ep ->
                    ep.episodeTitle.lowercase().contains(q) || ep.episodeCode.lowercase().contains(q)
                }
        }
        val matchingMedia = getVisibleItems().filter { item ->
            !item.isEpisode && (
                item.displayTitle.lowercase().contains(q) ||
                    item.originalFilename.lowercase().contains(q) ||
                    item.caption.lowercase().contains(q) ||
                    item.movieMetadata?.genres?.any { it.lowercase().contains(q) } == true ||
                    item.movieMetadata?.cast?.any { it.lowercase().contains(q) } == true
                )
        }
        return matchingShows to matchingMedia
    }

    fun getNextChronologicalVideo(item: MediaItem): MediaItem? {
        if (item.isEpisode) {
            val key = TitleCleaner.normalizeSeriesKey(item.seriesDisplayTitle).ifBlank { item.albumId }
            val eps = getShowByKey(key)?.allEpisodesChronological.orEmpty()
            val idx = eps.indexOfFirst { it.id == item.id }
            return if (idx >= 0 && idx + 1 < eps.size) eps[idx + 1] else null
        }
        val movies = getAlbumItems(item.albumId).filter { it.isVideo && !it.isEpisode }
        val idx = movies.indexOfFirst { it.id == item.id }
        return if (idx >= 0 && idx + 1 < movies.size) movies[idx + 1] else null
    }

    fun buildHomeRows(): List<HomeRow> {
        val progress = _progressMap.value
        val rows = mutableListOf<HomeRow>()

        val allVideos = getVisibleItems().filter { it.isVideo }
        val inProgressVideos = allVideos
            .filter { progress[it.id]?.isInProgress == true }
            .sortedByDescending { progress[it.id]?.updatedAt ?: 0L }

        if (inProgressVideos.isNotEmpty()) {
            // Keep at most 1 active episode per show in Continue Watching
            val seenShows = mutableSetOf<String>()
            val deduped = inProgressVideos.filter { item ->
                if (!item.isEpisode) true
                else seenShows.add(TitleCleaner.normalizeSeriesKey(item.seriesDisplayTitle))
            }
            rows.add(
                HomeRow(
                    id = "continue_watching",
                    title = "Continue Watching",
                    subtitle = "${deduped.size} active",
                    rowStyle = RowStyle.CONTINUE_WATCHING,
                    items = deduped
                )
            )
        }

        val shows = buildShows()
        if (shows.isNotEmpty()) {
            rows.add(
                HomeRow(
                    id = "tv_shows",
                    title = "TV Shows",
                    subtitle = "${shows.size} series",
                    rowStyle = RowStyle.SHOWS_POSTER,
                    shows = shows
                )
            )
        }

        val movies = getMovieItems()
        if (movies.isNotEmpty()) {
            rows.add(
                HomeRow(
                    id = "movies",
                    title = "Movies",
                    subtitle = "${movies.size} title${if (movies.size == 1) "" else "s"}",
                    rowStyle = RowStyle.MOVIES_POSTER,
                    items = movies
                )
            )
        }

        val photos = getPhotoItems()
        if (photos.isNotEmpty()) {
            rows.add(
                HomeRow(
                    id = "photos",
                    title = "Photos",
                    subtitle = "${photos.size} photo${if (photos.size == 1) "" else "s"}",
                    rowStyle = RowStyle.PHOTOS_LANDSCAPE,
                    items = photos
                )
            )
        }

        return rows
    }

    fun buildHeroSlides(
        randomSeed: Long = System.currentTimeMillis(),
        maxSlides: Int = 6
    ): List<HeroSlide> {
        val visibleVideos = getVisibleItems().filter { it.isVideo }
        val shows = buildShows()
        val slides = buildHeroSlides(
            allVideos = visibleVideos,
            shows = shows,
            progressMap = _progressMap.value,
            randomSeed = randomSeed,
            maxSlides = maxSlides
        )
        if (slides.isNotEmpty()) return slides
        val firstPhoto = getPhotoItems().firstOrNull() ?: return emptyList()
        return listOf(HeroSlide(id = "item_${firstPhoto.id}", item = firstPhoto, isContinueWatching = false))
    }

    suspend fun resolveFreshStreamInfo(
        item: MediaItem,
        quality: VideoQuality
    ): com.pixelstreamer.tv.data.network.ResolvedStreamInfo {
        val indexer = preIndexer
        if (indexer != null) {
            return indexer.getOrResolveStreamInfo(item, quality, scraper)
        }
        return scraper.resolveFreshStreamInfo(item, quality)
    }

    suspend fun resolveFreshPlaybackUrl(item: MediaItem, quality: VideoQuality): String {
        return resolveFreshStreamInfo(item, quality).url
    }

    /**
     * Re-scrapes the album HTML once (reusing cached metadata/filenames) to refresh
     * `lh3.googleusercontent.com` base URLs when a long playback session encounters token expiry.
     */
    suspend fun refreshItemForPlayback(albumId: String, itemId: String): MediaItem? = withContext(Dispatchers.IO) {
        val album = _albums.value.firstOrNull { it.id == albumId } ?: return@withContext null
        return@withContext try {
            val existing = _itemsByAlbum.value[album.id].orEmpty()
            val existingById = existing.associateBy { it.id }
            val scraped = scraper.fetchAlbum(album.url, cachedItemsById = existingById)
            val freshBaseUrlById = scraped.items.associate { it.id to it.baseUrl }
            stateMutex.withLock {
                val latest = _itemsByAlbum.value[album.id].orEmpty()
                val updated = latest.map { cur ->
                    val freshBase = freshBaseUrlById[cur.id]
                    if (!freshBase.isNullOrBlank() && freshBase != cur.baseUrl) cur.copy(baseUrl = freshBase) else cur
                }
                _itemsByAlbum.value = _itemsByAlbum.value.toMutableMap().apply { put(album.id, updated) }
                saveToDisk()
                updated.firstOrNull { it.id == itemId }
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Immediately refreshes CDN links and pre-indexes byte headers/tail moov/resume offsets
     * for active Continue Watching items (plus the top featured item) on app launch.
     */
    suspend fun preIndexContinueWatchingAndHero() = withContext(Dispatchers.IO) {
        val indexer = preIndexer ?: return@withContext
        val progress = _progressMap.value
        val visibleVideos = getVisibleItems().filter { it.isVideo }
        if (visibleVideos.isEmpty()) return@withContext

        val inProgress = visibleVideos
            .filter { progress[it.id]?.isInProgress == true }
            .sortedByDescending { progress[it.id]?.updatedAt ?: 0L }

        val targets = mutableListOf<Pair<MediaItem, Long>>()
        val seenIds = mutableSetOf<String>()
        for (item in inProgress) {
            if (targets.size >= 3) break
            if (seenIds.add(item.id)) {
                targets.add(item to (progress[item.id]?.positionMs ?: 0L))
            }
        }
        if (targets.size < 3) {
            for (item in visibleVideos) {
                if (targets.size >= 3) break
                if (seenIds.add(item.id)) {
                    targets.add(item to 0L)
                }
            }
        }

        coroutineScope {
            targets.map { (item, posMs) ->
                async {
                    val q = item.normalizeQuality(defaultQuality)
                    indexer.preIndexItem(item, q, posMs, scraper)
                }
            }.awaitAll()
        }
    }

    suspend fun preIndexSingleItem(
        item: MediaItem,
        quality: VideoQuality = defaultQuality,
        resumePosMs: Long? = null
    ) {
        val indexer = preIndexer ?: return
        val prog = _progressMap.value[item.id]
        val pos = resumePosMs ?: if (prog != null && prog.isInProgress) prog.positionMs else 0L
        val normalized = item.normalizeQuality(quality)
        indexer.preIndexItem(item, normalized, pos, scraper)
    }

    private fun saveToDisk() {
        try {
            val root = JSONObject()
            val albumsArr = JSONArray()
            for (a in _albums.value) {
                albumsArr.put(
                    JSONObject().apply {
                        put("id", a.id)
                        put("url", a.url)
                        put("resolvedUrl", a.resolvedUrl)
                        put("title", a.title)
                        put("customTitle", a.customTitle)
                        put("type", a.type.name)
                        put("enabled", a.enabled)
                        put("lastSyncedAt", a.lastSyncedAt)
                        put("itemCount", a.itemCount)
                    }
                )
            }
            root.put("albums", albumsArr)

            val itemsObj = JSONObject()
            for ((albId, list) in _itemsByAlbum.value) {
                val arr = JSONArray()
                for (item in list) {
                    arr.put(itemToJson(item))
                }
                itemsObj.put(albId, arr)
            }
            root.put("itemsByAlbum", itemsObj)

            val progArr = JSONArray()
            for (p in _progressMap.value.values) {
                progArr.put(
                    JSONObject().apply {
                        put("mediaId", p.mediaId)
                        put("positionMs", p.positionMs)
                        put("durationMs", p.durationMs)
                        put("updatedAt", p.updatedAt)
                    }
                )
            }
            root.put("progress", progArr)

            atomicWriteUtf8(cacheFile, root.toString())
        } catch (_: Exception) {
        }
    }

    private fun loadFromDisk() {
        if (!cacheFile.exists()) return
        try {
            val root = JSONObject(cacheFile.readText())
            val albumsArr = root.optJSONArray("albums") ?: JSONArray()
            val loadedAlbums = mutableListOf<AlbumSource>()
            for (i in 0 until albumsArr.length()) {
                val o = albumsArr.optJSONObject(i) ?: continue
                val type = AlbumType.entries.firstOrNull { it.name == o.optString("type") } ?: AlbumType.AUTO
                loadedAlbums.add(
                    AlbumSource(
                        id = o.optString("id"),
                        url = o.optString("url"),
                        resolvedUrl = o.optString("resolvedUrl"),
                        title = o.optString("title"),
                        customTitle = o.optString("customTitle"),
                        type = type,
                        enabled = o.optBoolean("enabled", true),
                        lastSyncedAt = o.optLong("lastSyncedAt", 0L),
                        itemCount = o.optInt("itemCount", 0)
                    )
                )
            }
            _albums.value = loadedAlbums

            val itemsObj = root.optJSONObject("itemsByAlbum") ?: JSONObject()
            val loadedItems = mutableMapOf<String, List<MediaItem>>()
            for (key in itemsObj.keys()) {
                val arr = itemsObj.optJSONArray(key) ?: continue
                val list = mutableListOf<MediaItem>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    jsonToItem(o)?.let { list.add(it) }
                }
                loadedItems[key] = list
            }
            _itemsByAlbum.value = loadedItems

            val progArr = root.optJSONArray("progress") ?: JSONArray()
            val loadedProg = mutableMapOf<String, PlaybackProgress>()
            for (i in 0 until progArr.length()) {
                val o = progArr.optJSONObject(i) ?: continue
                val id = o.optString("mediaId")
                if (id.isNotBlank()) {
                    loadedProg[id] = PlaybackProgress(
                        mediaId = id,
                        positionMs = o.optLong("positionMs", 0L),
                        durationMs = o.optLong("durationMs", 0L),
                        updatedAt = o.optLong("updatedAt", 0L)
                    )
                }
            }
            _progressMap.value = loadedProg
        } catch (_: Exception) {
        }
    }

    private fun itemToJson(item: MediaItem): JSONObject {
        val obj = JSONObject().apply {
            put("id", item.id)
            put("albumId", item.albumId)
            put("albumShareId", item.albumShareId)
            put("albumKey", item.albumKey)
            put("baseUrl", item.baseUrl)
            put("width", item.width)
            put("height", item.height)
            put("timestampMs", item.timestampMs)
            put("isVideo", item.isVideo)
            put("durationMs", item.durationMs)
            put("originalFilename", item.originalFilename)
            put("caption", item.caption)
            put("customTitle", item.customTitle)
            put("fileSizeBytes", item.fileSizeBytes)
            put("hidden", item.hidden)
            put("availableItags", JSONArray(item.availableItags))
            put("isEpisode", item.isEpisode)
            put("seriesTitle", item.seriesTitle)
            put("seasonNumber", item.seasonNumber ?: JSONObject.NULL)
            put("episodeNumber", item.episodeNumber ?: JSONObject.NULL)
            put("episodeTitle", item.episodeTitle)
            put("episodeOverview", item.episodeOverview)
            put("episodeStillUrl", item.episodeStillUrl ?: JSONObject.NULL)
        }
        item.movieMetadata?.let { m ->
            obj.put("movieMetadata", JSONObject().apply {
                put("matchedTitle", m.matchedTitle)
                put("year", m.year ?: JSONObject.NULL)
                put("overview", m.overview)
                put("posterUrl", m.posterUrl ?: JSONObject.NULL)
                put("backdropUrl", m.backdropUrl ?: JSONObject.NULL)
                put("logoUrl", m.logoUrl ?: JSONObject.NULL)
                put("genres", JSONArray(m.genres))
                put("rating", m.rating ?: JSONObject.NULL)
                put("contentRating", m.contentRating)
                put("runtimeMinutes", m.runtimeMinutes ?: JSONObject.NULL)
                put("director", m.director)
                put("cast", JSONArray(m.cast))
                put("tagline", m.tagline)
                put("source", m.source)
            })
        }
        return obj
    }

    private fun jsonToItem(o: JSONObject): MediaItem? {
        val id = o.optString("id").takeIf { it.isNotBlank() } ?: return null
        val itagArr = o.optJSONArray("availableItags") ?: JSONArray()
        val itags = (0 until itagArr.length()).map { itagArr.optInt(it) }.filter { it > 0 }

        val mObj = o.optJSONObject("movieMetadata")
        val meta = mObj?.let { m ->
            MovieMetadata(
                matchedTitle = m.optString("matchedTitle"),
                year = if (m.isNull("year")) null else m.optInt("year"),
                overview = m.optString("overview"),
                posterUrl = if (m.isNull("posterUrl")) null else m.optString("posterUrl").takeIf { it.isNotBlank() },
                backdropUrl = if (m.isNull("backdropUrl")) null else m.optString("backdropUrl").takeIf { it.isNotBlank() },
                logoUrl = if (m.isNull("logoUrl")) null else m.optString("logoUrl").takeIf { it.isNotBlank() },
                genres = (m.optJSONArray("genres") ?: JSONArray()).let { ga ->
                    (0 until ga.length()).mapNotNull { ga.optString(it).takeIf { s -> s.isNotBlank() } }
                },
                rating = if (m.isNull("rating")) null else m.optDouble("rating"),
                contentRating = m.optString("contentRating"),
                runtimeMinutes = if (m.isNull("runtimeMinutes")) null else m.optInt("runtimeMinutes"),
                director = m.optString("director"),
                cast = (m.optJSONArray("cast") ?: JSONArray()).let { ca ->
                    (0 until ca.length()).mapNotNull { ca.optString(it).takeIf { s -> s.isNotBlank() } }
                },
                tagline = m.optString("tagline"),
                source = m.optString("source")
            )
        }

        return MediaItem(
            id = id,
            albumId = o.optString("albumId"),
            albumShareId = o.optString("albumShareId"),
            albumKey = o.optString("albumKey"),
            baseUrl = o.optString("baseUrl"),
            width = o.optInt("width", 1920),
            height = o.optInt("height", 1080),
            timestampMs = o.optLong("timestampMs", 0L),
            isVideo = o.optBoolean("isVideo", false),
            durationMs = o.optLong("durationMs", 0L),
            originalFilename = o.optString("originalFilename"),
            caption = o.optString("caption"),
            customTitle = o.optString("customTitle"),
            availableItags = itags,
            fileSizeBytes = o.optLong("fileSizeBytes", 0L),
            hidden = o.optBoolean("hidden", false),
            movieMetadata = meta,
            isEpisode = o.optBoolean("isEpisode", false),
            seriesTitle = o.optString("seriesTitle"),
            seasonNumber = if (o.isNull("seasonNumber")) null else o.optInt("seasonNumber"),
            episodeNumber = if (o.isNull("episodeNumber")) null else o.optInt("episodeNumber"),
            episodeTitle = o.optString("episodeTitle"),
            episodeOverview = o.optString("episodeOverview"),
            episodeStillUrl = if (o.isNull("episodeStillUrl")) null else o.optString("episodeStillUrl").takeIf { it.isNotBlank() }
        )
    }

    companion object {
        private const val KEY_TMDB_API_KEY = "tmdb_api_key"
        private const val KEY_DEFAULT_QUALITY = "default_quality"
        private const val KEY_SLIDESHOW_SEC = "slideshow_seconds"

        fun buildHeroSlides(
            allVideos: List<MediaItem>,
            shows: List<ShowGroup>,
            progressMap: Map<String, PlaybackProgress>,
            randomSeed: Long = 0L,
            maxSlides: Int = 6
        ): List<HeroSlide> {
            val slides = mutableListOf<HeroSlide>()
            val usedItemIds = mutableSetOf<String>()
            val usedSeriesKeys = mutableSetOf<String>()
            val showsByKey = shows.associateBy { it.seriesKey }

            val inProgress = allVideos
                .filter { progressMap[it.id]?.isInProgress == true }
                .sortedByDescending { progressMap[it.id]?.updatedAt ?: 0L }

            for (video in inProgress) {
                if (slides.size >= 2 || slides.size >= maxSlides) break
                if (video.isEpisode) {
                    val key = TitleCleaner.normalizeSeriesKey(video.seriesDisplayTitle)
                    if (!usedSeriesKeys.add(key)) continue
                    slides.add(
                        HeroSlide(
                            id = "cw_${video.id}",
                            item = video,
                            show = showsByKey[key],
                            isContinueWatching = true
                        )
                    )
                } else if (usedItemIds.add(video.id)) {
                    slides.add(
                        HeroSlide(
                            id = "cw_${video.id}",
                            item = video,
                            isContinueWatching = true
                        )
                    )
                }
            }

            if (slides.size < maxSlides) {
                val recPool = mutableListOf<HeroSlide>()
                for (show in shows) {
                    if (show.seriesKey !in usedSeriesKeys) {
                        recPool.add(HeroSlide(id = "show_${show.seriesKey}", show = show))
                    }
                }
                for (movie in allVideos) {
                    if (!movie.isEpisode && movie.id !in usedItemIds) {
                        recPool.add(HeroSlide(id = "item_${movie.id}", item = movie))
                    }
                }
                val shuffled = recPool.shuffled(kotlin.random.Random(randomSeed))
                for (candidate in shuffled) {
                    if (slides.size >= maxSlides) break
                    slides.add(candidate)
                }
            }
            return slides
        }

        fun applyVisibilityUpdates(
            items: List<MediaItem>,
            hiddenById: Map<String, Boolean>
        ): List<MediaItem> {
            return items.map { item ->
                val newHidden = hiddenById[item.id]
                if (newHidden != null && item.hidden != newHidden) item.copy(hidden = newHidden) else item
            }
        }

        fun applyShowRematchToItems(
            items: List<MediaItem>,
            targetIds: Set<String>,
            resolvedSeriesName: String,
            bundle: TvShowBundle?
        ): List<MediaItem> {
            return items.map { item ->
                if (item.id !in targetIds) {
                    item
                } else {
                    val s = item.seasonNumber ?: 1
                    val e = item.episodeNumber
                    val epInfo = if (e != null) bundle?.episodesByKey?.get(s to e) else null
                    item.copy(
                        seriesTitle = resolvedSeriesName,
                        customTitle = "",
                        movieMetadata = bundle?.showMeta ?: item.movieMetadata,
                        episodeTitle = epInfo?.title?.ifBlank { item.episodeTitle } ?: item.episodeTitle,
                        episodeOverview = epInfo?.overview?.ifBlank { item.episodeOverview } ?: item.episodeOverview,
                        episodeStillUrl = epInfo?.stillUrl ?: item.episodeStillUrl
                    )
                }
            }
        }

        fun atomicWriteUtf8(target: File, content: String) {
            val parent = target.parentFile
            if (parent != null && !parent.exists()) parent.mkdirs()
            val tmp = File(parent, "${target.name}.tmp")
            FileOutputStream(tmp).use { fos ->
                fos.write(content.toByteArray(Charsets.UTF_8))
                fos.flush()
                runCatching { fos.fd.sync() }
            }
            if (!tmp.renameTo(target)) {
                val backup = if (target.exists()) File(parent, "${target.name}.bak") else null
                if (backup != null) {
                    target.copyTo(backup, overwrite = true)
                    target.delete()
                }
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
                backup?.delete()
            }
        }
    }
}
