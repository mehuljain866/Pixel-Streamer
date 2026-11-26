package com.pixelstreamer.tv.util

data class ParsedTitle(
    val queryTitle: String,
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitleHint: String = "",
    val isEpisode: Boolean = false,
    val isLikelyCameraClip: Boolean = false
)

object TitleCleaner {

    private val cameraClipRegex = Regex(
        """^(?:PXL|IMG|VID|MVIMG|DSC|DCIM|MVI|GOPR|GP\d+|DJI|WA|VID-?\d{8}|Screenshot|Screen_Recording|WhatsApp[\s_-]Video)[\s_-].*""",
        RegexOption.IGNORE_CASE
    )
    private val datePrefixRegex = Regex("""^\d{8}[_-]\d{6}.*""")
    private val extensionRegex = Regex("""\.(?:mp4|mkv|avi|mov|webm|m4v|ts|flv|wmv)$""", RegexOption.IGNORE_CASE)
    private val bracketJunkRegex = Regex("""(\[[^\]]*\]|\((?!(?:19|20)\d{2}\))[^)]*\))""")

    // Matches S01E02, S01.E02, S1 E2, 1x02, Season 1 Episode 2, Season 1 Ep 2
    private val fullEpisodeRegex = Regex(
        """\bS(\d{1,2})[\s._-]*E(\d{1,3})(?:[\s._-]*E\d{1,3})?\b|\b(\d{1,2})x(\d{1,3})\b|\bSeason[\s._-]*(\d{1,2})[\s._-]*(?:Episode|Ep)[\s._-]*(\d{1,3})\b""",
        RegexOption.IGNORE_CASE
    )
    // Matches Episode 5, Ep 05, E05 (without season)
    private val shortEpisodeRegex = Regex(
        """\b(?:Episode|Ep)[\s._-]*(\d{1,3})\b|\bE(\d{1,3})\b""",
        RegexOption.IGNORE_CASE
    )
    // Matches leading number like "03 - Pilot" or "03. Pilot" inside a Shows album
    private val leadingNumRegex = Regex("""^(\d{1,3})[\s._-]+(.+)$""")

    private val trailingSeasonInAlbumRegex = Regex(
        """\b(?:Season|S)[\s._-]*(\d{1,2})\b.*$""",
        RegexOption.IGNORE_CASE
    )

    private val releaseTagsRegex = Regex(
        """\b(?:2160p|1080p|720p|480p|360p|4k|uhd|fhd|hd|sd|hdr10\+?|hdr|dv|dovi|dolby|vision|bluray|blu-ray|bdrip|brrip|web-dl|webdl|webrip|web|hdrip|dvdrip|hdts|camrip|predvd|x264|x265|h264|h265|hevc|av1|xvid|aac|ac3|eac3|ddp5\.?1|dd5\.?1|dts|atmos|truehd|10bit|8bit|remux|proper|repack|extended|unrated|directors\.?cut|hindi|english|eng|dual[\s._-]?audio|esub|msubs|yify|yts|rarbg|sparks|evo|fgt|tigole|qxr|psa|pahe|galaxyrg|hdhub4u|vegamovies|filmyzilla|mkvcinemas)\b""",
        RegexOption.IGNORE_CASE
    )
    private val trailingHashRegex = Regex("""(?:^|\s)[a-f0-9]{7,}$""", RegexOption.IGNORE_CASE)
    private val yearRegex = Regex("""\b((?:19|20)\d{2})\b""")

    fun cleanDisplayTitle(rawInput: String): String {
        val parsed = parse(rawInput)
        return parsed.queryTitle.ifBlank { rawInput.replace(extensionRegex, "").trim() }
    }

    fun normalizeSeriesKey(seriesTitle: String): String {
        return seriesTitle.lowercase()
            .replace(Regex("""[^a-z0-9]"""), "")
            .trim()
    }

    fun parse(
        rawInput: String,
        albumTitleHint: String = "",
        forceShowAlbum: Boolean = false
    ): ParsedTitle {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) {
            return ParsedTitle("", isLikelyCameraClip = true)
        }

        val noExt = trimmed.replace(extensionRegex, "")
        if (cameraClipRegex.matches(noExt) || datePrefixRegex.matches(noExt)) {
            return ParsedTitle(
                queryTitle = noExt.replace('_', ' ').replace('-', ' ').replace(Regex("\\s+"), " ").trim(),
                isLikelyCameraClip = true
            )
        }

        var working = noExt
            .replace(bracketJunkRegex, " ")
            .replace('.', ' ')
            .replace('_', ' ')
            .replace('-', ' ')
            .replace('+', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()

        var season: Int? = null
        var episode: Int? = null
        var episodeTitleHint = ""
        var isEpisode = false

        val (albumSeriesClean, albumSeasonHint) = parseAlbumHint(albumTitleHint)

        val fullMatch = fullEpisodeRegex.find(working)
        if (fullMatch != null) {
            season = (fullMatch.groupValues[1]
                .ifEmpty { fullMatch.groupValues[3] }
                .ifEmpty { fullMatch.groupValues[5] }).toIntOrNull()
            episode = (fullMatch.groupValues[2]
                .ifEmpty { fullMatch.groupValues[4] }
                .ifEmpty { fullMatch.groupValues[6] }).toIntOrNull()
            isEpisode = season != null && episode != null

            val before = working.substring(0, fullMatch.range.first).trim()
            val afterRaw = working.substring(fullMatch.range.last + 1).trim()
            episodeTitleHint = cleanTrailingJunk(afterRaw)
            working = before
        } else {
            val shortMatch = shortEpisodeRegex.find(working)
            if (shortMatch != null && (forceShowAlbum || shortMatch.range.first > 1)) {
                episode = (shortMatch.groupValues[1].ifEmpty { shortMatch.groupValues[2] }).toIntOrNull()
                if (episode != null) {
                    season = albumSeasonHint ?: 1
                    isEpisode = true
                    val before = working.substring(0, shortMatch.range.first).trim()
                    val afterRaw = working.substring(shortMatch.range.last + 1).trim()
                    episodeTitleHint = cleanTrailingJunk(afterRaw)
                    working = before
                }
            } else if (forceShowAlbum) {
                val leadMatch = leadingNumRegex.find(working)
                val leadNum = leadMatch?.groupValues?.get(1)?.toIntOrNull()
                if (leadMatch != null && leadNum != null && leadNum in 1..999 && leadNum !in 1900..2099) {
                    season = albumSeasonHint ?: 1
                    episode = leadNum
                    isEpisode = true
                    episodeTitleHint = cleanTrailingJunk(leadMatch.groupValues[2])
                    working = ""
                }
            }
        }

        // If the filename only contained S01E02 (empty prefix), fall back to the cleaned album name!
        if (isEpisode && working.isBlank() && albumSeriesClean.isNotBlank()) {
            working = albumSeriesClean
        }

        // Trim everything from the first release/quality/language tag onward when a title precedes it
        val tagMatch = releaseTagsRegex.find(working)
        if (tagMatch != null && tagMatch.range.first > 2) {
            working = working.substring(0, tagMatch.range.first).trim()
        }

        // Extract trailing/parenthesized year when there is a title before it
        var year: Int? = null
        val parenYear = Regex("""\(\s*((?:19|20)\d{2})\s*\)""").find(working)
        if (parenYear != null && parenYear.range.first > 1) {
            year = parenYear.groupValues[1].toIntOrNull()
            working = working.removeRange(parenYear.range).trim()
        } else {
            val matches = yearRegex.findAll(working).toList()
            val last = matches.lastOrNull()
            if (last != null && last.range.first >= 2) {
                val prefix = working.substring(0, last.range.first).trim()
                if (prefix.length >= 2) {
                    year = last.groupValues[1].toIntOrNull()
                    working = prefix
                }
            }
        }

        working = cleanTrailingJunk(working)

        if (working.isBlank()) {
            working = if (isEpisode && albumSeriesClean.isNotBlank()) {
                albumSeriesClean
            } else {
                noExt.replace('.', ' ').replace('_', ' ').replace('-', ' ')
                    .replace(Regex("\\s+"), " ").trim()
            }
        }

        if (!isEpisode && forceShowAlbum) {
            // Even without episode number, keep under Shows if album explicitly marked Shows
            isEpisode = true
            season = albumSeasonHint ?: 1
        }

        return ParsedTitle(
            queryTitle = working,
            year = year,
            season = season,
            episode = episode,
            episodeTitleHint = episodeTitleHint,
            isEpisode = isEpisode,
            isLikelyCameraClip = false
        )
    }

    private fun parseAlbumHint(rawAlbum: String): Pair<String, Int?> {
        val cleaned = rawAlbum
            .replace('.', ' ')
            .replace('_', ' ')
            .replace('-', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
        if (cleaned.isBlank()) return "" to null
        val seasonMatch = trailingSeasonInAlbumRegex.find(cleaned)
        if (seasonMatch != null) {
            val s = seasonMatch.groupValues[1].toIntOrNull()
            val titlePart = cleaned.substring(0, seasonMatch.range.first).trim()
            return titlePart to s
        }
        return cleaned to null
    }

    private fun cleanTrailingJunk(raw: String): String {
        if (raw.isBlank()) return ""
        var w = raw
        val tagMatch = releaseTagsRegex.find(w)
        if (tagMatch != null) {
            w = if (tagMatch.range.first > 1) w.substring(0, tagMatch.range.first) else ""
        }
        return w
            .replace(releaseTagsRegex, " ")
            .replace(trailingHashRegex, " ")
            .replace(Regex("""[-–—:]+$"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}
