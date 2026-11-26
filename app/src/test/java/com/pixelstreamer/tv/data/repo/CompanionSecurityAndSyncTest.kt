package com.pixelstreamer.tv.data.repo

import com.pixelstreamer.tv.data.model.MediaItem
import com.pixelstreamer.tv.data.model.MovieMetadata
import com.pixelstreamer.tv.data.network.EpisodeMetadataInfo
import com.pixelstreamer.tv.data.network.TvShowBundle
import com.pixelstreamer.tv.server.CompanionWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class CompanionSecurityAndSyncTest {

    @Test
    fun companionServerRejectsMissingOrWrongPinAndAcceptsValidQueryHeaderOrCookie() {
        val pin = "4829"
        assertFalse(CompanionWebServer.isAuthorizedRequest(pin))
        assertFalse(CompanionWebServer.isAuthorizedRequest(pin, queryPin = "0000"))
        assertFalse(CompanionWebServer.isAuthorizedRequest(pin, headerPin = "482"))

        assertTrue(CompanionWebServer.isAuthorizedRequest(pin, queryPin = "4829"))
        assertTrue(CompanionWebServer.isAuthorizedRequest(pin, headerPin = "4829"))
        assertTrue(CompanionWebServer.isAuthorizedRequest(pin, cookiePin = "4829"))

        assertEquals("4829", CompanionWebServer.extractQueryParam("foo=1&pin=4829&bar=2", "pin"))
        assertEquals("4829", CompanionWebServer.extractCookie("other=x; ps_auth=4829; z=1", "ps_auth"))
    }

    @Test
    fun atomicWriteUtf8ReplacesTargetCleanlyWithoutLeavingTmp() {
        val dir = Files.createTempDirectory("ps_atomic_test").toFile()
        try {
            val target = dir.resolve("albums_cache_v1.json")
            MediaRepository.atomicWriteUtf8(target, """{"v":1}""")
            assertEquals("""{"v":1}""", target.readText())

            MediaRepository.atomicWriteUtf8(target, """{"v":2,"ok":true}""")
            assertEquals("""{"v":2,"ok":true}""", target.readText())
            assertFalse(dir.resolve("albums_cache_v1.json.tmp").exists())
            assertFalse(dir.resolve("albums_cache_v1.json.bak").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun applyShowRematchUpdatesAllEpisodesInSeriesWhilePreservingMovies() {
        val ep1 = MediaItem(
            id = "ep1",
            albumId = "alb1",
            albumShareId = "s1",
            albumKey = "k1",
            baseUrl = "https://lh3.googleusercontent.com/pw/1",
            width = 1920,
            height = 1080,
            timestampMs = 0L,
            isVideo = true,
            isEpisode = true,
            seriesTitle = "Messy Show",
            seasonNumber = 1,
            episodeNumber = 1
        )
        val ep2 = ep1.copy(
            id = "ep2",
            baseUrl = "https://lh3.googleusercontent.com/pw/2",
            episodeNumber = 2
        )
        val unrelatedMovie = ep1.copy(
            id = "mov1",
            isEpisode = false,
            seriesTitle = "",
            seasonNumber = null,
            episodeNumber = null,
            customTitle = "Interstellar"
        )

        val bundle = TvShowBundle(
            showMeta = MovieMetadata(
                matchedTitle = "Breaking Bad",
                year = 2008,
                logoUrl = "https://images.metahub.space/logo/medium/tt0903747/img",
                source = "Cinemeta"
            ),
            episodesByKey = mapOf(
                (1 to 1) to EpisodeMetadataInfo(1, 1, "Pilot", "Chem teacher.", "https://still/1.jpg", 58),
                (1 to 2) to EpisodeMetadataInfo(1, 2, "Cat's in the Bag...", "Cleanup.", "https://still/2.jpg", 48)
            )
        )

        val updated = MediaRepository.applyShowRematchToItems(
            items = listOf(ep1, ep2, unrelatedMovie),
            targetIds = setOf("ep1", "ep2"),
            resolvedSeriesName = "Breaking Bad",
            bundle = bundle
        )

        assertEquals("Breaking Bad", updated[0].seriesTitle)
        assertEquals("Breaking Bad · S01 E01", updated[0].displayTitle)
        assertEquals("Pilot", updated[0].episodeTitle)
        assertEquals("https://still/1.jpg", updated[0].episodeStillUrl)
        assertEquals("https://images.metahub.space/logo/medium/tt0903747/img", updated[0].titleLogoUrl)

        assertEquals("Breaking Bad", updated[1].seriesTitle)
        assertEquals("Breaking Bad · S01 E02", updated[1].displayTitle)
        assertEquals("Cat's in the Bag...", updated[1].episodeTitle)
        assertEquals("Cleanup.", updated[1].episodeOverview)
        assertEquals("https://still/2.jpg", updated[1].episodeStillUrl)

        assertEquals("Interstellar", updated[2].displayTitle)
    }
}
