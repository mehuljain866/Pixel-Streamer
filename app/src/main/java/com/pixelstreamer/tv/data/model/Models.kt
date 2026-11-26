package com.pixelstreamer.tv.data.model

import com.pixelstreamer.tv.util.TitleCleaner

enum class AlbumType(val label: String) {
    AUTO("Auto"),
    MOVIES("Movies"),
    SHOWS("Shows"),
    PHOTOS("Photos")
}

enum class VideoQuality(val label: String, val itag: Int?) {
    AUTO("Auto (Best Quality)", null),
    Q1080P("1080p Full HD", 37),
    Q720P("720p HD", 22),
    Q360P("360p Data Saver", 18),
    ORIGINAL("Original Source", -1)
}

data class AlbumSource(
    val id: String,
    val url: String,
    val resolvedUrl: String = url,
    val title: String = "Shared Album",
    val customTitle: String = "",
    val type: AlbumType = AlbumType.AUTO,
    val enabled: Boolean = true,
    val lastSyncedAt: Long = 0L,
    val itemCount: Int = 0
) {
    val displayTitle: String
        get() = customTitle.ifBlank { title }.ifBlank { "Shared Album" }
}

data class MovieMetadata(
    val matchedTitle: String,
    val year: Int? = null,
    val overview: String = "",
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val logoUrl: String? = null,
    val genres: List<String> = emptyList(),
    val rating: Double? = null,
    val contentRating: String = "",
    val runtimeMinutes: Int? = null,
    val director: String = "",
    val cast: List<String> = emptyList(),
    val tagline: String = "",
    val source: String = ""
)

data class MediaItem(
    val id: String,
    val albumId: String,
    val albumShareId: String,
    val albumKey: String,
    val baseUrl: String,
    val width: Int,
    val height: Int,
    val timestampMs: Long,
    val isVideo: Boolean,
    val durationMs: Long = 0L,
    val originalFilename: String = "",
    val caption: String = "",
    val customTitle: String = "",
    val availableItags: List<Int> = emptyList(),
    val fileSizeBytes: Long = 0L,
    val hidden: Boolean = false,
    val movieMetadata: MovieMetadata? = null,
    val isEpisode: Boolean = false,
    val seriesTitle: String = "",
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val episodeTitle: String = "",
    val episodeOverview: String = "",
    val episodeStillUrl: String? = null
) {
    fun thumbnailUrl(maxW: Int = 960, maxH: Int = 540): String {
        val noOverlay = if (isVideo) "-no" else ""
        return "$baseUrl=w$maxW-h$maxH$noOverlay"
    }

    fun fullPhotoUrl(maxW: Int = 3840, maxH: Int = 2160): String =
        "$baseUrl=w$maxW-h$maxH"

    val episodeCode: String
        get() {
            val s = seasonNumber ?: return ""
            val e = episodeNumber ?: return ""
            return "S%02d E%02d".format(s, e)
        }

    val episodeRowLabel: String
        get() {
            val e = episodeNumber?.let { "$it. " }.orEmpty()
            val t = episodeTitle.ifBlank {
                TitleCleaner.cleanDisplayTitle(caption.ifBlank { originalFilename })
            }.ifBlank { "Episode ${episodeNumber ?: ""}".trim() }
            return "$e$t"
        }

    val displayTitle: String
        get() = when {
            customTitle.isNotBlank() -> customTitle
            isEpisode -> {
                val show = seriesTitle.ifBlank { movieMetadata?.matchedTitle ?: "Show" }
                val code = episodeCode
                if (code.isNotBlank()) "$show · $code" else show
            }
            movieMetadata != null && movieMetadata.matchedTitle.isNotBlank() -> movieMetadata.matchedTitle
            caption.isNotBlank() -> TitleCleaner.cleanDisplayTitle(caption)
            originalFilename.isNotBlank() -> TitleCleaner.cleanDisplayTitle(originalFilename)
            else -> if (isVideo) "Video" else "Photo"
        }

    val seriesDisplayTitle: String
        get() = seriesTitle.ifBlank { movieMetadata?.matchedTitle ?: displayTitle }

    val titleLogoUrl: String?
        get() = movieMetadata?.logoUrl?.takeIf { it.isNotBlank() }

    val posterImageUrl: String
        get() = movieMetadata?.posterUrl ?: thumbnailUrl(600, 900)

    val backdropImageUrl: String
        get() = when {
            isEpisode && !episodeStillUrl.isNullOrBlank() -> episodeStillUrl
            !movieMetadata?.backdropUrl.isNullOrBlank() -> movieMetadata!!.backdropUrl!!
            else -> thumbnailUrl(1920, 1080)
        }

    val landscapeCardUrl: String
        get() = when {
            isEpisode && !episodeStillUrl.isNullOrBlank() -> episodeStillUrl
            !movieMetadata?.backdropUrl.isNullOrBlank() -> movieMetadata!!.backdropUrl!!
            else -> thumbnailUrl(960, 540)
        }

    val displayDuration: String
        get() {
            val totalSec = (durationMs / 1000L).coerceAtLeast(0L)
            if (totalSec <= 0L) return ""
            val h = totalSec / 3600
            val m = (totalSec % 3600) / 60
            val s = totalSec % 60
            return when {
                h > 0 -> "${h}h ${m}m"
                m > 0 -> "${m}m ${s}s"
                else -> "${s}s"
            }
        }

    val supports1080p: Boolean
        get() = width >= 1600 || height >= 960 || 37 in availableItags || 137 in availableItags

    val supports720p: Boolean
        get() = supports1080p || width >= 1100 || height >= 640 || 22 in availableItags || 136 in availableItags

    fun supportedQualities(): List<VideoQuality> {
        val list = mutableListOf(VideoQuality.AUTO)
        if (supports1080p) list.add(VideoQuality.Q1080P)
        if (supports720p) list.add(VideoQuality.Q720P)
        list.add(VideoQuality.Q360P)
        list.add(VideoQuality.ORIGINAL)
        return list
    }

    fun normalizeQuality(requested: VideoQuality): VideoQuality {
        val supported = supportedQualities()
        return if (requested in supported) requested else VideoQuality.AUTO
    }
}

data class ShowGroup(
    val seriesKey: String,
    val title: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val logoUrl: String?,
    val overview: String,
    val year: Int?,
    val rating: Double?,
    val genres: List<String>,
    val seasons: Map<Int, List<MediaItem>>,
    val allEpisodesChronological: List<MediaItem>,
    val nextUpEpisode: MediaItem
) {
    val seasonCount: Int get() = seasons.size
    val episodeCount: Int get() = allEpisodesChronological.size
}

data class PlaybackProgress(
    val mediaId: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long
) {
    val fraction: Float
        get() = if (durationMs > 0L) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f

    val isInProgress: Boolean
        get() = positionMs >= 15_000L && (durationMs <= 0L || fraction < 0.94f)

    val isFinished: Boolean
        get() = durationMs > 0L && fraction >= 0.94f

    val remainingText: String
        get() {
            val remSec = ((durationMs - positionMs).coerceAtLeast(0L)) / 1000L
            if (remSec <= 0L) return ""
            val h = remSec / 3600
            val m = (remSec % 3600) / 60
            return when {
                h > 0 && m > 0 -> "${h}hr ${m}m left"
                h > 0 -> "${h}hr left"
                m > 0 -> "${m}m left"
                else -> "<1m left"
            }
        }
}

data class HomeRow(
    val id: String,
    val title: String,
    val subtitle: String = "",
    val rowStyle: RowStyle,
    val items: List<MediaItem> = emptyList(),
    val shows: List<ShowGroup> = emptyList()
)

enum class RowStyle {
    CONTINUE_WATCHING,
    SHOWS_POSTER,
    MOVIES_POSTER,
    PHOTOS_LANDSCAPE
}
