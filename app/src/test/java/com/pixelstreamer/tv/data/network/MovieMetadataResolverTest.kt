package com.pixelstreamer.tv.data.network

import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class MovieMetadataResolverTest {

    private val resolver = MovieMetadataResolver(OkHttpClient())

    @Test
    fun parsesItunesMovieAndUpscalesPoster() {
        val json = """
            {
              "resultCount": 1,
              "results": [
                {
                  "trackName": "Inception",
                  "artistName": "Christopher Nolan",
                  "releaseDate": "2010-07-16T07:00:00Z",
                  "primaryGenreName": "Sci-Fi & Fantasy",
                  "contentAdvisoryRating": "PG-13",
                  "trackTimeMillis": 8880000,
                  "artworkUrl100": "https://is1-ssl.mzstatic.com/image/thumb/Video/100x100bb.jpg",
                  "longDescription": "A thief who steals corporate secrets through dream-sharing technology."
                }
              ]
            }
        """.trimIndent()

        val meta = resolver.parseItunesResponse(json, "Inception", 2010)
        assertNotNull(meta)
        assertEquals("Inception", meta!!.matchedTitle)
        assertEquals(2010, meta.year)
        assertEquals("Christopher Nolan", meta.director)
        assertEquals(148, meta.runtimeMinutes)
        assertEquals(
            "https://is1-ssl.mzstatic.com/image/thumb/Video/800x1200bb.jpg",
            meta.posterUrl
        )
        assertEquals("iTunes", meta.source)
    }

    @Test
    fun parsesTmdbMovieDetailWithCredits() {
        val json = JSONObject(
            """
            {
              "title": "Interstellar",
              "release_date": "2014-11-05",
              "poster_path": "/poster.jpg",
              "backdrop_path": "/backdrop.jpg",
              "vote_average": 8.4,
              "runtime": 169,
              "overview": "Explorers travel through a wormhole.",
              "genres": [{"id": 18, "name": "Drama"}, {"id": 878, "name": "Science Fiction"}],
              "credits": {
                "cast": [{"name": "Matthew McConaughey"}, {"name": "Anne Hathaway"}],
                "crew": [{"job": "Director", "name": "Christopher Nolan"}]
              }
            }
            """.trimIndent()
        )

        val meta = resolver.parseTmdbDetail(json, isTv = false)
        assertNotNull(meta)
        assertEquals("Interstellar", meta!!.matchedTitle)
        assertEquals(2014, meta.year)
        assertEquals("https://image.tmdb.org/t/p/w500/poster.jpg", meta.posterUrl)
        assertEquals("https://image.tmdb.org/t/p/w1280/backdrop.jpg", meta.backdropUrl)
        assertEquals("Christopher Nolan", meta.director)
        assertEquals(listOf("Matthew McConaughey", "Anne Hathaway"), meta.cast)
        assertEquals("TMDB", meta.source)
    }

    @Test
    fun parsesImdbSuggestionAndScalesPoster() {
        val json = """
            {
              "d": [
                {
                  "i": {
                    "height": 4096,
                    "imageUrl": "https://m.media-amazon.com/images/M/MV5BNGI0._V1_.jpg",
                    "width": 2764
                  },
                  "id": "tt16311594",
                  "l": "F1: The Movie",
                  "qid": "movie",
                  "s": "Brad Pitt, Damson Idris",
                  "y": 2025
                }
              ]
            }
        """.trimIndent()

        val meta = resolver.parseImdbSuggestionResponse(json, "F1 The Movie", null, isTv = false)
        assertNotNull(meta)
        assertEquals("F1: The Movie", meta!!.matchedTitle)
        assertEquals(2025, meta.year)
        assertEquals("https://m.media-amazon.com/images/M/MV5BNGI0._V1_UX600_.jpg", meta.posterUrl)
        assertEquals(listOf("Brad Pitt", "Damson Idris"), meta.cast)
        assertEquals("IMDb", meta.source)
    }

    @Test
    fun parsesTvMazeShowWithChronologicalEpisodes() {
        val json = """
            {
              "name": "Breaking Bad",
              "premiered": "2008-01-20",
              "genres": ["Drama", "Crime"],
              "rating": {"average": 9.2},
              "averageRuntime": 47,
              "summary": "<p>A high school chemistry teacher diagnosed with cancer.</p>",
              "image": {"original": "https://static.tvmaze.com/uploads/images/original_untouched/0/2400.jpg"},
              "_embedded": {
                "episodes": [
                  {
                    "season": 1,
                    "number": 1,
                    "name": "Pilot",
                    "runtime": 58,
                    "summary": "<p>Walter White cooks.</p>",
                    "image": {"original": "https://static.tvmaze.com/ep1.jpg"}
                  },
                  {
                    "season": 1,
                    "number": 2,
                    "name": "Cat's in the Bag...",
                    "runtime": 48,
                    "summary": "<p>Clean up.</p>",
                    "image": {"original": "https://static.tvmaze.com/ep2.jpg"}
                  }
                ]
              }
            }
        """.trimIndent()

        val bundle = resolver.parseTvMazeBundle(json)
        assertNotNull(bundle)
        assertEquals("Breaking Bad", bundle!!.showMeta.matchedTitle)
        assertEquals(2008, bundle.showMeta.year)
        assertEquals(2, bundle.episodesByKey.size)
        assertEquals("Pilot", bundle.episodesByKey[1 to 1]?.title)
        assertEquals("https://static.tvmaze.com/ep2.jpg", bundle.episodesByKey[1 to 2]?.stillUrl)
    }

    @Test
    fun parsesCinemetaMovieWithLogoAndRating() {
        val json = """
            {
              "meta": {
                "id": "tt0816692",
                "imdb_id": "tt0816692",
                "name": "Interstellar",
                "releaseInfo": "2014",
                "imdbRating": "8.7",
                "runtime": "169 min",
                "genres": ["Adventure", "Drama", "Sci-Fi"],
                "director": ["Christopher Nolan"],
                "cast": ["Matthew McConaughey", "Anne Hathaway"],
                "description": "A team of explorers travel through a wormhole in space.",
                "logo": "https://images.metahub.space/logo/medium/tt0816692/img",
                "background": "https://images.metahub.space/background/medium/tt0816692/img",
                "poster": "https://images.metahub.space/poster/medium/tt0816692/img"
              }
            }
        """.trimIndent()

        val bundle = resolver.parseCinemetaBundle(json)
        assertNotNull(bundle)
        val meta = bundle!!.showMeta
        assertEquals("Interstellar", meta.matchedTitle)
        assertEquals(2014, meta.year)
        assertEquals(8.7, meta.rating ?: 0.0, 0.01)
        assertEquals(169, meta.runtimeMinutes)
        assertEquals("Christopher Nolan", meta.director)
        assertEquals("https://images.metahub.space/logo/medium/tt0816692/img", meta.logoUrl)
        assertEquals("https://images.metahub.space/background/medium/tt0816692/img", meta.backdropUrl)
        assertEquals("Cinemeta", meta.source)
    }

    @Test
    fun parsesCinemetaSeriesWithEpisodesAndFallbackLogo() {
        val json = """
            {
              "meta": {
                "imdb_id": "tt0903747",
                "name": "Breaking Bad",
                "releaseInfo": "2008-2013",
                "imdbRating": "9.5",
                "runtime": "49 min",
                "videos": [
                  {
                    "season": 1,
                    "episode": 1,
                    "name": "Pilot",
                    "overview": "Walter White gets a diagnosis.",
                    "thumbnail": "https://episodes.metahub.space/tt0903747/1/1/w780.jpg"
                  }
                ]
              }
            }
        """.trimIndent()

        val bundle = resolver.parseCinemetaBundle(json)
        assertNotNull(bundle)
        assertEquals("Breaking Bad", bundle!!.showMeta.matchedTitle)
        assertEquals(2008, bundle.showMeta.year)
        assertEquals("https://images.metahub.space/logo/medium/tt0903747/img", bundle.showMeta.logoUrl)
        assertEquals("Pilot", bundle.episodesByKey[1 to 1]?.title)
        assertEquals("https://episodes.metahub.space/tt0903747/1/1/w780.jpg", bundle.episodesByKey[1 to 1]?.stillUrl)
    }
}
