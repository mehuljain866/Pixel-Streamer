package com.pixelstreamer.tv.data.network

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GooglePhotosAlbumScraperTest {

    private val scraper = GooglePhotosAlbumScraper(OkHttpClient())

    @Test
    fun parsesMixedAlbumWithPhotoAndVideoAtIndex9AndIndex10() {
        val html = """
            <html><head></head><body>
            <script>AF_initDataCallback({key: 'ds:0', hash: '0', data:[], sideChannel: {}});</script>
            <script>AF_initDataCallback({key: 'ds:1', hash: '1', data:[
                null,
                [
                    [
                        "ITEM_PHOTO_1",
                        ["https://lh3.googleusercontent.com/pw/PHOTO_TOKEN", 1920, 1080],
                        1700000000000,
                        "STABLE_1",
                        0,
                        1700000000000,
                        ["ACTOR"],
                        [],
                        2,
                        {"15": 100}
                    ],
                    [
                        "ITEM_VIDEO_1",
                        ["https://lh3.googleusercontent.com/pw/VIDEO_TOKEN", 3840, 2160],
                        1704050314000,
                        "STABLE_2",
                        19800000,
                        1725604667691,
                        ["ACTOR"],
                        [],
                        2,
                        0,
                        {
                            "15": 108,
                            "76647426": [68259, null, 3840, 2160],
                            "396644657": ["Interstellar (2014)"]
                        }
                    ]
                ],
                "",
                ["SHARE_ID_XYZ", "My Movies"]
            ], sideChannel: {}});</script>
            </body></html>
        """.trimIndent()

        val result = scraper.parseAlbumHtml(
            "https://photos.google.com/share/SHARE_ID_XYZ?key=KEY_ABC",
            html
        )

        assertEquals("SHARE_ID_XYZ", result.shareId)
        assertEquals("KEY_ABC", result.albumKey)
        assertEquals("My Movies", result.albumTitle)
        assertEquals(2, result.items.size)

        val photo = result.items[0]
        assertFalse(photo.isVideo)
        assertEquals("ITEM_PHOTO_1", photo.id)
        assertEquals(1920, photo.width)

        val video = result.items[1]
        assertTrue(video.isVideo)
        assertEquals(68259L, video.durationMs)
        assertEquals("Interstellar (2014)", video.caption)
    }

    @Test
    fun parsesPhotoPageCaptionAndItags() {
        val html = """
            <script>AF_initDataCallback({key: 'ds:1', hash: '1', data:[
                [
                    "ITEM_VIDEO_1",
                    ["https://lh3.googleusercontent.com/pw/VIDEO_TOKEN", 1920, 1080],
                    1704050314000,
                    "STABLE",
                    0,
                    0,
                    [],
                    [],
                    2,
                    0,
                    {
                        "76647426": [120000, null, 1920, 1080, null, 4, null, [[37, 1920, 1080], [22, 1280, 720], [18, 640, 360]]],
                        "396644657": ["Blade Runner 2049"]
                    }
                ]
            ], sideChannel: {}});</script>
        """.trimIndent()

        val detail = scraper.parsePhotoPageHtml(html)
        assertNotNull(detail)
        assertEquals("Blade Runner 2049", detail!!.caption)
        assertEquals(120000L, detail.durationMs)
        assertEquals(listOf(37, 22, 18), detail.availableItags)
    }

    @Test
    fun extractsSignedVideoDownloadUrlsAndFilters720pQualities() {
        val html = """
            "https://video-downloads.googleusercontent.com/abc123\u003fexpires\u003d999\u0026itag\u003d37\u0026sig\u003dXYZ"
            "https://video-downloads.googleusercontent.com/def456?expires=999&itag=22&sig=UVW"
        """.trimIndent()
        val urls = scraper.extractSignedStreamUrls(html)
        assertEquals(
            "https://video-downloads.googleusercontent.com/def456?expires=999&itag=22&sig=UVW",
            urls[22]
        )

        val movie720 = com.pixelstreamer.tv.data.model.MediaItem(
            id = "m720",
            albumId = "a1",
            albumShareId = "s1",
            albumKey = "k1",
            baseUrl = "https://lh3.googleusercontent.com/pw/1",
            width = 1280,
            height = 534,
            timestampMs = 0L,
            isVideo = true
        )
        assertFalse(com.pixelstreamer.tv.data.model.VideoQuality.Q1080P in movie720.supportedQualities())
        assertTrue(com.pixelstreamer.tv.data.model.VideoQuality.Q720P in movie720.supportedQualities())

        val movie1080 = movie720.copy(width = 1920, height = 802)
        assertTrue(com.pixelstreamer.tv.data.model.VideoQuality.Q1080P in movie1080.supportedQualities())
    }
}
