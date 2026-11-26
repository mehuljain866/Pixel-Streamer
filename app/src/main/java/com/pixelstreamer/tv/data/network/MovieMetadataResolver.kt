package com.pixelstreamer.tv.data.network

import com.pixelstreamer.tv.data.model.MovieMetadata
import com.pixelstreamer.tv.util.ParsedTitle
import com.pixelstreamer.tv.util.TitleCleaner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

data class EpisodeMetadataInfo(
    val season: Int,
    val episode: Int,
    val title: String,
    val overview: String,
    val stillUrl: String?,
    val runtimeMinutes: Int?
)

data class TvShowBundle(
    val showMeta: MovieMetadata,
    val episodesByKey: Map<Pair<Int, Int>, EpisodeMetadataInfo>
)

class MovieMetadataResolver(private val httpClient: OkHttpClient) {

    suspend fun resolve(
        rawTitle: String,
        tmdbKey: String = "",
        forceLookup: Boolean = false
    ): MovieMetadata? = withContext(Dispatchers.IO) {
        val parsed = TitleCleaner.parse(rawTitle)
        if (parsed.queryTitle.isBlank()) return@withContext null
        if (parsed.isLikelyCameraClip && !forceLookup) return@withContext null

        if (tmdbKey.isNotBlank()) {
            queryTmdb(parsed, tmdbKey)?.let { return@withContext it }
        }

        if (parsed.isEpisode || parsed.season != null) {
            resolveShowBundle(parsed.queryTitle, tmdbKey)?.showMeta?.let { return@withContext it }
        }

        // Keyless primary movie matcher: IMDb Suggestion -> Cinemeta (Logo + 16:9 Backdrop + Rating) -> Wikipedia
        queryImdbAndCinemeta(parsed)?.let { return@withContext it }

        queryItunesMovies(parsed)?.let { return@withContext it }

        null
    }

    suspend fun resolveShowBundle(
        seriesTitle: String,
        tmdbKey: String = ""
    ): TvShowBundle? = withContext(Dispatchers.IO) {
        val cleanSeries = TitleCleaner.cleanDisplayTitle(seriesTitle)
        if (cleanSeries.isBlank()) return@withContext null

        val imdbHit = queryImdbSuggestion(cleanSeries, null, isTv = true)
        val cinemetaBundle = imdbHit?.second?.let { imdbId ->
            queryCinemetaBundle("series", imdbId)
        }
        val tvMazeBundle = queryTvMazeBundle(cleanSeries)
        val tmdbShow = if (tmdbKey.isNotBlank()) {
            queryTmdb(ParsedTitle(queryTitle = cleanSeries, season = 1, isEpisode = true), tmdbKey)
        } else null

        val baseMeta = cinemetaBundle?.showMeta ?: tvMazeBundle?.showMeta ?: imdbHit?.first ?: tmdbShow
        if (baseMeta != null) {
            val mergedMeta = baseMeta.copy(
                posterUrl = tmdbShow?.posterUrl ?: cinemetaBundle?.showMeta?.posterUrl ?: tvMazeBundle?.showMeta?.posterUrl ?: baseMeta.posterUrl,
                backdropUrl = tmdbShow?.backdropUrl ?: cinemetaBundle?.showMeta?.backdropUrl ?: tvMazeBundle?.showMeta?.backdropUrl ?: baseMeta.backdropUrl,
                logoUrl = tmdbShow?.logoUrl ?: cinemetaBundle?.showMeta?.logoUrl ?: imdbHit?.first?.logoUrl,
                rating = tmdbShow?.rating ?: cinemetaBundle?.showMeta?.rating ?: tvMazeBundle?.showMeta?.rating,
                overview = tmdbShow?.overview?.ifBlank { cinemetaBundle?.showMeta?.overview.orEmpty() }
                    ?.ifBlank { tvMazeBundle?.showMeta?.overview.orEmpty() }
                    ?: baseMeta.overview,
                genres = baseMeta.genres.ifEmpty { tvMazeBundle?.showMeta?.genres ?: emptyList() }
            )
            // Prefer TVmaze episode stills/overviews, fill gaps from Cinemeta
            val combinedEpisodes = mutableMapOf<Pair<Int, Int>, EpisodeMetadataInfo>()
            cinemetaBundle?.episodesByKey?.let { combinedEpisodes.putAll(it) }
            tvMazeBundle?.episodesByKey?.forEach { (k, tvEp) ->
                val cm = combinedEpisodes[k]
                combinedEpisodes[k] = tvEp.copy(
                    title = tvEp.title.ifBlank { cm?.title.orEmpty() },
                    overview = tvEp.overview.ifBlank { cm?.overview.orEmpty() },
                    stillUrl = tvEp.stillUrl ?: cm?.stillUrl
                )
            }
            return@withContext TvShowBundle(showMeta = mergedMeta, episodesByKey = combinedEpisodes)
        }

        null
    }

    private fun queryImdbAndCinemeta(parsed: ParsedTitle): MovieMetadata? {
        val attempts = buildList {
            add(parsed.queryTitle)
            val words = parsed.queryTitle.split(" ").filter { it.isNotBlank() }
            if (words.size > 4) add(words.take(4).joinToString(" "))
            if (words.size > 2) add(words.take(2).joinToString(" "))
        }.distinct()

        for (candidate in attempts) {
            val hit = queryImdbSuggestion(
                candidate,
                parsed.year,
                isTv = parsed.isEpisode || parsed.season != null
            ) ?: continue
            val (imdbMeta, imdbId) = hit
            val cinemeta = imdbId?.let { queryCinemetaBundle("movie", it)?.showMeta }
            if (cinemeta != null) {
                return cinemeta.copy(
                    posterUrl = cinemeta.posterUrl ?: imdbMeta.posterUrl,
                    backdropUrl = cinemeta.backdropUrl ?: imdbMeta.backdropUrl,
                    logoUrl = cinemeta.logoUrl ?: imdbMeta.logoUrl,
                    cast = cinemeta.cast.ifEmpty { imdbMeta.cast },
                    year = cinemeta.year ?: imdbMeta.year
                )
            }
            val wiki = queryWikipediaSummary(imdbMeta.matchedTitle, imdbMeta.year)
            return imdbMeta.copy(
                overview = wiki?.first.orEmpty().ifBlank { imdbMeta.overview },
                backdropUrl = imdbMeta.backdropUrl ?: wiki?.second ?: imdbMeta.posterUrl
            )
        }
        return null
    }

    private fun queryImdbSuggestion(
        queryTitle: String,
        targetYear: Int?,
        isTv: Boolean
    ): Pair<MovieMetadata, String?>? {
        return try {
            val firstCh = queryTitle.lowercase().firstOrNull { it.isLetterOrDigit() } ?: 'a'
            val encoded = URLEncoder.encode(queryTitle.lowercase(), "UTF-8").replace("+", "%20")
            val url = "https://v3.sg.media-imdb.com/suggestion/$firstCh/$encoded.json"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", GooglePhotosAlbumScraper.DESKTOP_UA)
                .build()
            val body = httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.string() ?: return null
            }
            parseImdbSuggestionWithId(body, queryTitle, targetYear, isTv)
        } catch (_: Exception) {
            null
        }
    }

    fun parseImdbSuggestionResponse(
        jsonBody: String,
        queryTitle: String,
        targetYear: Int?,
        isTv: Boolean
    ): MovieMetadata? = parseImdbSuggestionWithId(jsonBody, queryTitle, targetYear, isTv)?.first

    private fun parseImdbSuggestionWithId(
        jsonBody: String,
        queryTitle: String,
        targetYear: Int?,
        isTv: Boolean
    ): Pair<MovieMetadata, String?>? {
        val root = JSONObject(jsonBody)
        val arr = root.optJSONArray("d") ?: return null
        if (arr.length() == 0) return null

        val qNorm = normalizeForMatch(queryTitle)
        var bestObj: JSONObject? = null
        var bestScore = Int.MIN_VALUE

        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val label = obj.optString("l")
            if (label.isBlank()) continue
            val qid = obj.optString("qid")
            val hasImg = obj.optJSONObject("i")?.optString("imageUrl")?.isNotBlank() == true
            val year = if (obj.has("y") && !obj.isNull("y")) obj.optInt("y") else null

            var score = 0
            val lNorm = normalizeForMatch(label)
            if (lNorm == qNorm) score += 120
            else if (lNorm.startsWith(qNorm) || qNorm.startsWith(lNorm)) score += 75
            else if (lNorm.contains(qNorm)) score += 45

            if (targetYear != null && year == targetYear) score += 50
            if (!isTv && (qid == "movie" || qid == "tvMovie")) score += 40
            if (isTv && (qid == "tvSeries" || qid == "tvMiniSeries")) score += 40
            if (qid == "short" || qid == "videoGame") score -= 40
            if (hasImg) score += 25

            if (score > bestScore) {
                bestScore = score
                bestObj = obj
            }
        }

        val chosen = bestObj ?: return null
        if (bestScore < 25) return null

        val imdbId = chosen.optString("id").takeIf { it.startsWith("tt") }
        val title = chosen.optString("l")
        val year = if (chosen.has("y") && !chosen.isNull("y")) chosen.optInt("y") else null
        val rawPoster = chosen.optJSONObject("i")?.optString("imageUrl")?.takeIf { it.isNotBlank() }
        val posterUrl = rawPoster?.replace(Regex("""\._V1_.*\.jpg$"""), "._V1_UX600_.jpg")
        val backdropUrl = imdbId?.let { "https://images.metahub.space/background/medium/$it/img" }
            ?: rawPoster?.replace(Regex("""\._V1_.*\.jpg$"""), "._V1_UX1280_.jpg")
        val logoUrl = imdbId?.let { "https://images.metahub.space/logo/medium/$it/img" }
        val cast = chosen.optString("s")
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val meta = MovieMetadata(
            matchedTitle = title,
            year = year,
            posterUrl = posterUrl,
            backdropUrl = backdropUrl,
            logoUrl = logoUrl,
            cast = cast,
            source = "IMDb"
        )
        return meta to imdbId
    }

    fun parseCinemetaBundle(jsonBody: String): TvShowBundle? {
        val root = JSONObject(jsonBody)
        val meta = root.optJSONObject("meta") ?: return null
        val name = meta.optString("name").ifBlank { return null }
        val imdbId = meta.optString("imdb_id").ifBlank { meta.optString("id") }

        val year = meta.optString("releaseInfo").take(4).toIntOrNull()
            ?: meta.optInt("year", 0).takeIf { it > 0 }
        val rating = meta.optString("imdbRating").toDoubleOrNull()
            ?: meta.optDouble("imdbRating", 0.0).takeIf { it > 0.0 }

        val runtimeStr = meta.optString("runtime")
        val runtimeMin = Regex("""(\d+)""").find(runtimeStr)?.groupValues?.get(1)?.toIntOrNull()

        val genresArr = meta.optJSONArray("genres") ?: JSONArray()
        val genres = (0 until genresArr.length()).mapNotNull {
            genresArr.optString(it).takeIf { g -> g.isNotBlank() }
        }
        val castArr = meta.optJSONArray("cast") ?: JSONArray()
        val cast = (0 until minOf(5, castArr.length())).mapNotNull {
            castArr.optString(it).takeIf { c -> c.isNotBlank() }
        }
        val dirArr = meta.optJSONArray("director") ?: JSONArray()
        val director = (0 until dirArr.length()).mapNotNull {
            dirArr.optString(it).takeIf { d -> d.isNotBlank() }
        }.joinToString(", ")

        val logo = meta.optString("logo").takeIf { it.isNotBlank() }
            ?: imdbId.takeIf { it.startsWith("tt") }?.let { "https://images.metahub.space/logo/medium/$it/img" }
        val bg = meta.optString("background").takeIf { it.isNotBlank() }
            ?: imdbId.takeIf { it.startsWith("tt") }?.let { "https://images.metahub.space/background/medium/$it/img" }
        val poster = meta.optString("poster").takeIf { it.isNotBlank() }
            ?: imdbId.takeIf { it.startsWith("tt") }?.let { "https://images.metahub.space/poster/medium/$it/img" }

        val episodesMap = mutableMapOf<Pair<Int, Int>, EpisodeMetadataInfo>()
        val videosArr = meta.optJSONArray("videos") ?: JSONArray()
        for (i in 0 until videosArr.length()) {
            val v = videosArr.optJSONObject(i) ?: continue
            val s = v.optInt("season", -1)
            val e = v.optInt("episode", -1).takeIf { it > 0 } ?: v.optInt("number", -1)
            if (s <= 0 || e <= 0) continue
            episodesMap[s to e] = EpisodeMetadataInfo(
                season = s,
                episode = e,
                title = v.optString("title").ifBlank { v.optString("name") },
                overview = v.optString("overview"),
                stillUrl = v.optString("thumbnail").takeIf { it.isNotBlank() },
                runtimeMinutes = runtimeMin
            )
        }

        val movieMeta = MovieMetadata(
            matchedTitle = name,
            year = year,
            overview = meta.optString("description"),
            posterUrl = poster,
            backdropUrl = bg ?: poster,
            logoUrl = logo,
            genres = genres,
            rating = rating,
            runtimeMinutes = runtimeMin,
            director = director,
            cast = cast,
            source = "Cinemeta"
        )
        return TvShowBundle(showMeta = movieMeta, episodesByKey = episodesMap)
    }

    private fun queryCinemetaBundle(kind: String, imdbId: String): TvShowBundle? {
        return try {
            val req = Request.Builder()
                .url("https://v3-cinemeta.strem.io/meta/$kind/$imdbId.json")
                .header("User-Agent", GooglePhotosAlbumScraper.DESKTOP_UA)
                .build()
            val body = httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.string() ?: return null
            }
            parseCinemetaBundle(body)
        } catch (_: Exception) {
            null
        }
    }

    private fun queryWikipediaSummary(title: String, year: Int?): Pair<String, String?>? {
        val slugs = buildList {
            val clean = title.replace(":", "")
            if (year != null) add("$clean (${year} film)")
            add("$clean (film)")
            add(clean)
        }.distinct()

        for (slug in slugs) {
            try {
                val encoded = URLEncoder.encode(slug.replace(" ", "_"), "UTF-8").replace("+", "%20")
                val req = Request.Builder()
                    .url("https://en.wikipedia.org/api/rest_v1/page/summary/$encoded")
                    .header("User-Agent", "PixelStreamerTV/1.0")
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val body = resp.body?.string() ?: return@use
                    val json = JSONObject(body)
                    if (json.optString("type") == "disambiguation") return@use
                    val extract = json.optString("extract").trim()
                    if (extract.isNotBlank()) {
                        val img = json.optJSONObject("originalimage")?.optString("source")
                            ?.takeIf { it.isNotBlank() }
                        return extract to img
                    }
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun normalizeForMatch(s: String): String {
        return s.lowercase()
            .replace(Regex("""[^a-z0-9\s]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun queryTmdb(parsed: ParsedTitle, apiKeyOrToken: String): MovieMetadata? {
        return try {
            val encoded = URLEncoder.encode(parsed.queryTitle, "UTF-8")
            val isBearer = apiKeyOrToken.length > 45
            val isTv = parsed.isEpisode || parsed.season != null
            val endpoint = if (isTv) "search/tv" else "search/movie"
            val yearParam = if (parsed.year != null && !isTv) "&year=${parsed.year}" else ""
            val authParam = if (isBearer) "" else "&api_key=${URLEncoder.encode(apiKeyOrToken, "UTF-8")}"
            val searchUrl = "https://api.themoviedb.org/3/$endpoint?query=$encoded$yearParam$authParam"

            val searchReqBuilder = Request.Builder().url(searchUrl)
            if (isBearer) searchReqBuilder.header("Authorization", "Bearer $apiKeyOrToken")

            val searchJson = httpClient.newCall(searchReqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return null
                JSONObject(resp.body?.string() ?: return null)
            }
            val results = searchJson.optJSONArray("results") ?: return null
            if (results.length() == 0) return null

            val first = results.getJSONObject(0)
            val tmdbId = first.optInt("id")
            val detailType = if (isTv) "tv" else "movie"
            val detailUrl =
                "https://api.themoviedb.org/3/$detailType/$tmdbId?append_to_response=credits,images&include_image_language=en,null$authParam"
            val detailReqBuilder = Request.Builder().url(detailUrl)
            if (isBearer) detailReqBuilder.header("Authorization", "Bearer $apiKeyOrToken")

            val detail = httpClient.newCall(detailReqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) first else JSONObject(resp.body?.string() ?: "{}")
            }

            parseTmdbDetail(detail, isTv = isTv)
        } catch (_: Exception) {
            null
        }
    }

    fun parseTmdbDetail(detail: JSONObject, isTv: Boolean): MovieMetadata? {
        val title = detail.optString(if (isTv) "name" else "title").ifBlank {
            detail.optString("original_title")
        }
        if (title.isBlank()) return null

        val dateStr = detail.optString(if (isTv) "first_air_date" else "release_date")
        val year = dateStr.take(4).toIntOrNull()
        val posterPath = detail.optString("poster_path").takeIf { it.isNotBlank() && it != "null" }
        val backdropPath = detail.optString("backdrop_path").takeIf { it.isNotBlank() && it != "null" }

        val logosArr = detail.optJSONObject("images")?.optJSONArray("logos")
        val logoPath = logosArr?.optJSONObject(0)?.optString("file_path")
            ?.takeIf { it.isNotBlank() && !it.endsWith(".svg", true) }

        val genresArr = detail.optJSONArray("genres")
        val genres = mutableListOf<String>()
        if (genresArr != null) {
            for (i in 0 until genresArr.length()) {
                genresArr.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }?.let { genres.add(it) }
            }
        }

        val credits = detail.optJSONObject("credits")
        val cast = mutableListOf<String>()
        val castArr = credits?.optJSONArray("cast")
        if (castArr != null) {
            for (i in 0 until minOf(5, castArr.length())) {
                castArr.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }?.let { cast.add(it) }
            }
        }

        var director = ""
        val crewArr = credits?.optJSONArray("crew")
        if (crewArr != null) {
            for (i in 0 until crewArr.length()) {
                val member = crewArr.optJSONObject(i) ?: continue
                if (member.optString("job").equals("Director", ignoreCase = true)) {
                    director = member.optString("name")
                    break
                }
            }
        }

        val runtime = detail.optInt("runtime", 0).takeIf { it > 0 }
        val vote = detail.optDouble("vote_average", 0.0).takeIf { it > 0.0 }

        return MovieMetadata(
            matchedTitle = title,
            year = year,
            overview = detail.optString("overview"),
            posterUrl = posterPath?.let { "https://image.tmdb.org/t/p/w500$it" },
            backdropUrl = backdropPath?.let { "https://image.tmdb.org/t/p/w1280$it" }
                ?: posterPath?.let { "https://image.tmdb.org/t/p/w780$it" },
            logoUrl = logoPath?.let { "https://image.tmdb.org/t/p/w500$it" },
            genres = genres,
            rating = vote,
            runtimeMinutes = runtime,
            director = director,
            cast = cast,
            tagline = detail.optString("tagline"),
            source = "TMDB"
        )
    }

    private fun queryItunesMovies(parsed: ParsedTitle): MovieMetadata? {
        return try {
            val encoded = URLEncoder.encode(parsed.queryTitle, "UTF-8")
            val req = Request.Builder()
                .url("https://itunes.apple.com/search?term=$encoded&entity=movie&limit=8")
                .build()
            val body = httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.string() ?: return null
            }
            parseItunesResponse(body, parsed.queryTitle, parsed.year)
        } catch (_: Exception) {
            null
        }
    }

    fun parseItunesResponse(jsonBody: String, queryTitle: String, targetYear: Int?): MovieMetadata? {
        val root = JSONObject(jsonBody)
        val results = root.optJSONArray("results") ?: return null
        if (results.length() == 0) return null

        var bestObj: JSONObject? = null
        var bestScore = Int.MIN_VALUE
        val qNorm = queryTitle.lowercase().trim()

        for (i in 0 until results.length()) {
            val obj = results.optJSONObject(i) ?: continue
            val name = obj.optString("trackName")
            if (name.isBlank()) continue
            val year = obj.optString("releaseDate").take(4).toIntOrNull()
            var score = 0
            val nNorm = name.lowercase().trim()
            if (nNorm == qNorm) score += 100
            else if (nNorm.startsWith(qNorm) || qNorm.startsWith(nNorm)) score += 60
            else if (nNorm.contains(qNorm)) score += 30
            if (targetYear != null && year == targetYear) score += 50
            if (score > bestScore) {
                bestScore = score
                bestObj = obj
            }
        }

        val chosen = bestObj ?: return null
        val title = chosen.optString("trackName")
        val art100 = chosen.optString("artworkUrl100")
        val posterUrl = art100.takeIf { it.isNotBlank() }
            ?.replace(Regex("""\d+x\d+bb"""), "800x1200bb")
        val backdropUrl = art100.takeIf { it.isNotBlank() }
            ?.replace(Regex("""\d+x\d+bb"""), "1400x1400bb")
        val year = chosen.optString("releaseDate").take(4).toIntOrNull()
        val genre = chosen.optString("primaryGenreName").takeIf { it.isNotBlank() }
        val runtimeMs = chosen.optLong("trackTimeMillis", 0L)
        val runtimeMin = if (runtimeMs > 0L) (runtimeMs / 60_000L).toInt() else null

        return MovieMetadata(
            matchedTitle = title,
            year = year,
            overview = chosen.optString("longDescription").ifBlank { chosen.optString("shortDescription") },
            posterUrl = posterUrl,
            backdropUrl = backdropUrl,
            genres = listOfNotNull(genre),
            contentRating = chosen.optString("contentAdvisoryRating"),
            runtimeMinutes = runtimeMin,
            director = chosen.optString("artistName"),
            source = "iTunes"
        )
    }

    fun parseTvMazeBundle(jsonBody: String): TvShowBundle? {
        val obj = JSONObject(jsonBody)
        val name = obj.optString("name").ifBlank { return null }
        val imageObj = obj.optJSONObject("image")
        val poster = imageObj?.optString("original")?.ifBlank { imageObj.optString("medium") }
        val genresArr = obj.optJSONArray("genres") ?: JSONArray()
        val genres = (0 until genresArr.length()).mapNotNull {
            genresArr.optString(it).takeIf { g -> g.isNotBlank() }
        }
        val summary = obj.optString("summary").replace(Regex("<[^>]+>"), "").trim()
        val year = obj.optString("premiered").take(4).toIntOrNull()
        val rating = obj.optJSONObject("rating")?.optDouble("average", 0.0)?.takeIf { it > 0.0 }

        val episodesMap = mutableMapOf<Pair<Int, Int>, EpisodeMetadataInfo>()
        val epsArr = obj.optJSONObject("_embedded")?.optJSONArray("episodes") ?: JSONArray()
        var firstEpBackdrop: String? = null
        for (i in 0 until epsArr.length()) {
            val epObj = epsArr.optJSONObject(i) ?: continue
            val s = epObj.optInt("season", -1)
            val e = epObj.optInt("number", -1)
            if (s <= 0 || e <= 0) continue
            val epImg = epObj.optJSONObject("image")
            val still = epImg?.optString("original")?.ifBlank { epImg.optString("medium") }
            if (firstEpBackdrop == null && !still.isNullOrBlank()) firstEpBackdrop = still
            episodesMap[s to e] = EpisodeMetadataInfo(
                season = s,
                episode = e,
                title = epObj.optString("name"),
                overview = epObj.optString("summary").replace(Regex("<[^>]+>"), "").trim(),
                stillUrl = still,
                runtimeMinutes = epObj.optInt("runtime", 0).takeIf { it > 0 }
            )
        }

        val showMeta = MovieMetadata(
            matchedTitle = name,
            year = year,
            overview = summary,
            posterUrl = poster,
            backdropUrl = firstEpBackdrop ?: poster,
            genres = genres,
            rating = rating,
            runtimeMinutes = obj.optInt("averageRuntime", 0).takeIf { it > 0 },
            source = "TVmaze"
        )
        return TvShowBundle(showMeta = showMeta, episodesByKey = episodesMap)
    }

    private fun queryTvMazeBundle(queryTitle: String): TvShowBundle? {
        return try {
            val encoded = URLEncoder.encode(queryTitle, "UTF-8")
            val req = Request.Builder()
                .url("https://api.tvmaze.com/singlesearch/shows?q=$encoded&embed=episodes")
                .build()
            val body = httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.string() ?: return null
            }
            parseTvMazeBundle(body)
        } catch (_: Exception) {
            null
        }
    }
}
