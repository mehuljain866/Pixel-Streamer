package com.pixelstreamer.tv.ui.player

import com.pixelstreamer.tv.data.model.MediaItem
import com.pixelstreamer.tv.data.model.PlaybackProgress
import com.pixelstreamer.tv.data.model.ShowGroup
import com.pixelstreamer.tv.data.repo.MediaRepository
import com.pixelstreamer.tv.util.TitleCleaner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeroCarouselAndPlayerMathTest {

    @Test
    fun buildHeroSlides_putsLatestContinueWatchingFirstFollowedByUniqueRandomRecommendations() {
        val m1 = videoItem("m1", "Movie One")
        val m2 = videoItem("m2", "Movie Two")
        val m3 = videoItem("m3", "Movie Three")
        val ep1 = videoItem("ep1", "Show S01E01", isEpisode = true, seriesTitle = "Show A")
        val ep2 = videoItem("ep2", "Show S01E02", isEpisode = true, seriesTitle = "Show A")

        val show = ShowGroup(
            seriesKey = TitleCleaner.normalizeSeriesKey("Show A"),
            title = "Show A",
            posterUrl = null,
            backdropUrl = null,
            logoUrl = null,
            overview = "",
            year = 2024,
            rating = 8.4,
            genres = emptyList(),
            seasons = mapOf(1 to listOf(ep1, ep2)),
            allEpisodesChronological = listOf(ep1, ep2),
            nextUpEpisode = ep2
        )

        val progress = mapOf(
            "m1" to PlaybackProgress("m1", positionMs = 120_000L, durationMs = 3_600_000L, updatedAt = 100L),
            "ep1" to PlaybackProgress("ep1", positionMs = 300_000L, durationMs = 2_400_000L, updatedAt = 500L)
        )

        val slides = MediaRepository.buildHeroSlides(
            allVideos = listOf(m1, m2, m3, ep1, ep2),
            shows = listOf(show),
            progressMap = progress,
            randomSeed = 42L,
            maxSlides = 6
        )

        assertTrue(slides.size >= 4)
        // Most recent Continue Watching (ep1 @ updatedAt=500) must be slide 0!
        assertEquals("ep1", slides[0].item?.id)
        assertTrue(slides[0].isContinueWatching)
        // Second Continue Watching (m1 @ updatedAt=100) next
        assertEquals("m1", slides[1].item?.id)
        assertTrue(slides[1].isContinueWatching)
        // Remaining slides are non-Continue-Watching unique recommendations (m2, m3) without duplicating Show A
        val recIds = slides.drop(2).mapNotNull { it.item?.id ?: it.show?.seriesKey }.toSet()
        assertFalse(show.seriesKey in recIds)
        assertTrue("m2" in recIds && "m3" in recIds)
        assertEquals(recIds.size, slides.drop(2).size)
    }

    @Test
    fun playerSeekMath_clampsCleanlyAndAvoidsNegativeOrOverflow() {
        assertEquals(0L, PlayerActivity.computeSeekTargetMs(5_000L, -10_000L, 60_000L))
        assertEquals(59_000L, PlayerActivity.computeSeekTargetMs(55_000L, 10_000L, 60_000L))
        assertEquals(16_000L, PlayerActivity.computeSeekTargetMs(6_000L, 10_000L, 0L))

        assertEquals(0, PlayerActivity.computeProgressPermille(10_000L, 0L))
        assertEquals(500, PlayerActivity.computeProgressPermille(30_000L, 60_000L))
        assertEquals(1000, PlayerActivity.computeProgressPermille(90_000L, 60_000L))
    }

    @Test
    fun pipAspectRatio_clampsExtremeRatiosForAndroidPipBounds() {
        assertEquals(16 to 9, PlayerActivity.computeSafePipAspectRatio(0, 0))
        assertEquals(1920 to 1080, PlayerActivity.computeSafePipAspectRatio(1920, 1080))
        assertEquals(235 to 100, PlayerActivity.computeSafePipAspectRatio(5000, 1000))
        assertEquals(45 to 100, PlayerActivity.computeSafePipAspectRatio(200, 1000))
    }

    private fun videoItem(
        id: String,
        title: String,
        isEpisode: Boolean = false,
        seriesTitle: String = ""
    ) = MediaItem(
        id = id,
        albumId = "alb",
        albumShareId = "sh",
        albumKey = "k",
        baseUrl = "https://lh3.googleusercontent.com/pw/$id",
        width = 1920,
        height = 1080,
        timestampMs = 0L,
        isVideo = true,
        customTitle = title,
        isEpisode = isEpisode,
        seriesTitle = seriesTitle
    )
}
