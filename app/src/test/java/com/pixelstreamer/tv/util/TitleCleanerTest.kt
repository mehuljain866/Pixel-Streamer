package com.pixelstreamer.tv.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleCleanerTest {

    @Test
    fun cleansStandardMovieReleaseFilename() {
        val parsed = TitleCleaner.parse("Inception.2010.1080p.BluRay.x264.YIFY.mp4")
        assertFalse(parsed.isLikelyCameraClip)
        assertFalse(parsed.isEpisode)
        assertEquals("Inception", parsed.queryTitle)
        assertEquals(2010, parsed.year)
        assertNull(parsed.season)
    }

    @Test
    fun cleansHyphensUnderscoresAndExtension() {
        val f1 = TitleCleaner.parse("F1-The-Movie_1080p.mp4")
        assertEquals("F1 The Movie", f1.queryTitle)

        val turtles = TitleCleaner.parse("Turtles-All-the-Way-Down_1080p.mp4")
        assertEquals("Turtles All the Way Down", turtles.queryTitle)

        val cruella = TitleCleaner.parse("Cruella_1080p.mp4")
        assertEquals("Cruella", cruella.queryTitle)
    }

    @Test
    fun cleansUnderscoresAndParenthesizedYear() {
        val parsed = TitleCleaner.parse("Dune_Part_Two_(2024)_2160p_WEB-DL.mkv")
        assertFalse(parsed.isLikelyCameraClip)
        assertEquals("Dune Part Two", parsed.queryTitle)
        assertEquals(2024, parsed.year)
    }

    @Test
    fun extractsTvEpisodeNumbersAcrossMessyFormats() {
        val s02e05 = TitleCleaner.parse("Breaking.Bad.S02E05.Breakage.720p.HDTV.mp4")
        assertTrue(s02e05.isEpisode)
        assertEquals("Breaking Bad", s02e05.queryTitle)
        assertEquals(2, s02e05.season)
        assertEquals(5, s02e05.episode)
        assertEquals("Breakage", s02e05.episodeTitleHint)

        val xFormat = TitleCleaner.parse("How-I-Met-Your-Mother_9x06_x264.mkv")
        assertTrue(xFormat.isEpisode)
        assertEquals("How I Met Your Mother", xFormat.queryTitle)
        assertEquals(9, xFormat.season)
        assertEquals(6, xFormat.episode)

        val albumFallback = TitleCleaner.parse(
            "Episode_04_The_Body.mp4",
            albumTitleHint = "Stranger Things Season 2",
            forceShowAlbum = true
        )
        assertTrue(albumFallback.isEpisode)
        assertEquals("Stranger Things", albumFallback.queryTitle)
        assertEquals(2, albumFallback.season)
        assertEquals(4, albumFallback.episode)
        assertEquals("The Body", albumFallback.episodeTitleHint)
    }

    @Test
    fun detectsCameraClipFilename() {
        val parsed = TitleCleaner.parse("PXL_20231231_191725033~2.mp4")
        assertTrue(parsed.isLikelyCameraClip)
    }
}
