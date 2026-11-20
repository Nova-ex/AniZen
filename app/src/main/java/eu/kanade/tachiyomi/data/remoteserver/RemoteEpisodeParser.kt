package eu.kanade.tachiyomi.data.remoteserver

import tachiyomi.domain.episode.service.EpisodeRecognition

// ANZ -->
object RemoteEpisodeParser {

    data class ParsedRemoteEpisode(
        val seasonNumber: Int?,
        val episodeNumber: Float,
        val displayName: String,
        val scanlator: String?,
    )

    private val BRACKET_TAG_REGEX = Regex("""\[[^\]]*\]|\{[^}]*\}""")
    private val PAREN_META_REGEX = Regex(
        """(?i)\((?:\d{4}|(?:\d{3,4}p)|(?:4k)|(?:bd|bdrip|web-dl|webrip|dvd|hdtv|hevc|x264|x265|av1|flac|aac|""" +
            """10bit|8bit|dual[\s._-]?audio|multi[\s._-]?(?:audio|subs?)|batch|complete|uncensored)[^)]*)\)""",
    )
    private val TRAILING_RELEASE_REGEX = Regex(
        """(?i)(?:\s*[-–—]\s*|\s+)(?:(?:complete|batch|dual[\s._-]?audio|multi[\s._-]?audio|uncensored|bluray|""" +
            """bdrip|web-dl|webrip|hdtv|hevc|x265|x264|av1|10bit|8bit|aac|flac|\d{3,4}p|4k)\b.*)+$""",
    )
    private val TRAILING_SEASON_REGEX = Regex(
        """(?i)(?:\s*[-–—]\s*|\s+)(?:season\s*\d+|s\d{1,2})\s*$""",
    )
    private val SXXEXX_REGEX = Regex(
        """(?i)(?:^|[\s._\-\[(])S(\d{1,2})\s*E(\d{1,4}(?:\.\d+)?)(?:v\d+)?(?=$|[\s._\-\])v])""",
    )
    private val NXNN_REGEX = Regex(
        """(?i)(?:^|[\s._\-\[(])(\d{1,2})x(\d{1,4}(?:\.\d+)?)(?:v\d+)?(?=$|[\s._\-\])])""",
    )
    private val EPISODE_WORD_REGEX = Regex(
        """(?i)(?:^|[\s._\-\[(])(?:episodes?|ep\.?|e)\s*(\d{1,4}(?:\.\d+)?)(?:v\d+)?(?=$|[\s._\-\])])""",
    )
    private val DASH_EPISODE_REGEX = Regex(
        """(?i)\s[-–—]\s(\d{1,4}(?:\.\d+)?)(?:v\d+)?(?=$|[\s._\-\[(])""",
    )
    private val FOLDER_SEASON_REGEX = Regex(
        """(?i)(?:^|/|[\s._\-\[(])(?:season|s)\s*0*(\d{1,2})(?=$|/|\s|\.|_|-|\])""",
    )
    private val EXTRA_TAG_REGEX = Regex("""(?i)\b(?:ova|ona|specials?|sp|extra|nced|ncop|pv|trailer)\b""")
    private val RESOLUTION_TOKEN_REGEX = Regex("""(?i)\b(?:\d{3,4}p|4k|x264|x265|h264|h265|10bit|8bit)\b""")
    private val WHITESPACE_REGEX = Regex("""\s+""")

    private const val DECIMAL_EPSILON = 0.001f

    fun cleanAnimeTitle(folderName: String): String {
        var cleaned = folderName.trim()
        cleaned = BRACKET_TAG_REGEX.replace(cleaned, " ")
        cleaned = PAREN_META_REGEX.replace(cleaned, " ")
        cleaned = cleaned.replace('_', ' ')
        if (!cleaned.contains(' ') && cleaned.count { it == '.' } >= 2) {
            cleaned = cleaned.replace('.', ' ')
        }
        cleaned = TRAILING_RELEASE_REGEX.replace(cleaned, "")
        cleaned = TRAILING_SEASON_REGEX.replace(cleaned, "")
        cleaned = WHITESPACE_REGEX.replace(cleaned, " ").trim(' ', '-', '–', '—', '_')
        return cleaned.ifBlank { folderName.trim() }
    }

    fun parseEpisode(
        fileName: String,
        relativeSubfolder: String,
        animeTitle: String,
    ): ParsedRemoteEpisode {
        val baseName = fileName.substringBeforeLast('.')
        val stripped = stripNoiseForParsing(baseName)

        var seasonNumber = parseSeasonFromSubfolder(relativeSubfolder)
        var episodeNumber = -1f

        val sxxMatch = SXXEXX_REGEX.find(stripped)
        if (sxxMatch != null) {
            seasonNumber = sxxMatch.groupValues[1].toIntOrNull() ?: seasonNumber
            episodeNumber = sxxMatch.groupValues[2].toFloatOrNull() ?: -1f
        }

        if (episodeNumber < 0f) {
            val nxMatch = NXNN_REGEX.find(stripped)
            if (nxMatch != null) {
                seasonNumber = nxMatch.groupValues[1].toIntOrNull() ?: seasonNumber
                episodeNumber = nxMatch.groupValues[2].toFloatOrNull() ?: -1f
            }
        }

        if (episodeNumber < 0f) {
            val epWordMatch = EPISODE_WORD_REGEX.find(stripped)
            if (epWordMatch != null) {
                episodeNumber = epWordMatch.groupValues[1].toFloatOrNull() ?: -1f
            }
        }

        if (episodeNumber < 0f) {
            val dashMatch = DASH_EPISODE_REGEX.find(stripped)
            if (dashMatch != null) {
                episodeNumber = dashMatch.groupValues[1].toFloatOrNull() ?: -1f
            }
        }

        if (episodeNumber < 0f) {
            val recognized = EpisodeRecognition.parseEpisodeNumber(animeTitle, stripped, -1.0)
            if (recognized >= 0.0) {
                episodeNumber = recognized.toFloat()
            }
        }

        if (seasonNumber == null && isSpecialsFolder(relativeSubfolder)) {
            seasonNumber = 0
        }

        val displayName = buildDisplayName(
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            fallbackName = stripped.ifBlank { baseName },
        )
        val scanlator = relativeSubfolder.takeIf { it.isNotBlank() }

        return ParsedRemoteEpisode(
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            displayName = displayName,
            scanlator = scanlator,
        )
    }

    private fun stripNoiseForParsing(raw: String): String {
        var result = BRACKET_TAG_REGEX.replace(raw, " ")
        result = PAREN_META_REGEX.replace(result, " ")
        result = RESOLUTION_TOKEN_REGEX.replace(result, " ")
        result = result.replace('_', ' ')
        if (!result.contains(' ') && result.count { it == '.' } >= 2) {
            result = result.replace('.', ' ')
        }
        return WHITESPACE_REGEX.replace(result, " ").trim()
    }

    private fun parseSeasonFromSubfolder(relativeSubfolder: String): Int? {
        if (relativeSubfolder.isBlank()) return null
        val match = FOLDER_SEASON_REGEX.find(relativeSubfolder)
        return match?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun isSpecialsFolder(relativeSubfolder: String): Boolean {
        return relativeSubfolder.isNotBlank() && EXTRA_TAG_REGEX.containsMatchIn(relativeSubfolder)
    }

    private fun buildDisplayName(
        seasonNumber: Int?,
        episodeNumber: Float,
        fallbackName: String,
    ): String {
        val seasonPrefix = seasonNumber?.let { "Season $it" }
        return if (episodeNumber >= 0f) {
            val formattedNumber = formatEpisodeNumber(episodeNumber)
            if (seasonPrefix != null) {
                "$seasonPrefix - Episode $formattedNumber"
            } else {
                "Episode $formattedNumber"
            }
        } else {
            if (seasonPrefix != null && !fallbackName.contains("season", ignoreCase = true)) {
                "$seasonPrefix - $fallbackName"
            } else {
                fallbackName
            }
        }
    }

    fun formatEpisodeNumber(episodeNumber: Float): String {
        val intPart = episodeNumber.toInt()
        return if (kotlin.math.abs(episodeNumber - intPart) < DECIMAL_EPSILON) {
            intPart.toString().padStart(2, '0')
        } else {
            episodeNumber.toString()
        }
    }
}
// ANZ <--
