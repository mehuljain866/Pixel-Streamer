package com.pixelstreamer.tv.data.network

import com.pixelstreamer.tv.data.model.MediaItem
import com.pixelstreamer.tv.data.model.VideoQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamPreIndexerTest {

    @Test
    fun computesHeadAndTailWindowsForFreshStart() {
        val tenMb = 10L * 1024L * 1024L
        val ranges = StreamPreIndexer.computePrefetchRanges(
            totalBytes = tenMb,
            resumePosMs = 0L,
            durationMs = 3_600_000L
        )
        assertEquals(2, ranges.size)
        assertEquals(0L to (768L * 1024L), ranges[0])
        assertEquals((tenMb - 512L * 1024L) to (512L * 1024L), ranges[1])
    }

    @Test
    fun computesHeadResumeWindowAndTailWhenResumingMidMovie() {
        val oneGb = 1024L * 1024L * 1024L
        val ranges = StreamPreIndexer.computePrefetchRanges(
            totalBytes = oneGb,
            resumePosMs = 1_800_000L, // 50% of 1 hour
            durationMs = 3_600_000L
        )
        assertEquals(3, ranges.size)
        assertEquals(0L to (256L * 1024L), ranges[0])
        val expectedApprox = (oneGb / 2L) - (64L * 1024L)
        assertEquals(expectedApprox to (768L * 1024L), ranges[1])
        assertEquals((oneGb - 512L * 1024L) to (512L * 1024L), ranges[2])
    }

    @Test
    fun cacheKeysRemainStableAcrossExpiredTokensButVaryByQuality() {
        val item = MediaItem(
            id = "vid1",
            albumId = "alb1",
            albumShareId = "s1",
            albumKey = "k1",
            baseUrl = "https://lh3.googleusercontent.com/pw/ABC",
            width = 1920,
            height = 1080,
            timestampMs = 0L,
            isVideo = true
        )
        val autoKey1 = StreamPreIndexer.cacheKeyFor(item, VideoQuality.AUTO)
        val autoKey2 = StreamPreIndexer.cacheKeyFor(item, VideoQuality.AUTO)
        val q720Key = StreamPreIndexer.cacheKeyFor(item, VideoQuality.Q720P)
        assertEquals(autoKey1, autoKey2)
        assertNotEquals(autoKey1, q720Key)
        assertTrue(autoKey1.startsWith("ps2_vid1_AUTO_"))
    }
}
