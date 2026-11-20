// ANZ -->
package eu.kanade.tachiyomi.data.track

/**
 * Normalizes anime/movie/series titles from Arabic CloudStream extensions before searching
 * external tracker APIs (AniList, MyAnimeList, SIMKL) so trailing Arabic streaming tags such as
 * `مترجم`, `مدبلج`, `الموسم`, `الحلقة`, `انمي`, `فيلم` do not break automatic title matching.
 */
object TrackerTitleNormalizer {

    // Arabic diacritics (harakat/tashkeel) and tatweel
    private val ARABIC_DIACRITICS_REGEX = Regex("[\u0610-\u061A\u064B-\u065F\u0670\u0640]")

    // Arabic / Western digits used right after season/episode words
    private const val DIGITS_PATTERN = "[0-9\u0660-\u0669\u06F0-\u06F9]+"

    // Arabic ordinal words commonly paired with الموسم / الجزء / الحلقة
    private const val ORDINAL_WORDS =
        "(?:ال[أإآا]?ول[ىي]?|الثان[يی]|الثالث|الرابع|الخامس|السادس|السابع|الثامن|التاسع|العاشر|" +
            "الاخير[ةه]?|الأخير[ةه]?|ال[أإآا]?خيرة)"

    // Multi-word Arabic streaming phrases (stripped first)
    private val ARABIC_PHRASE_PATTERNS = listOf(
        Regex("(?<![\\p{L}\\p{N}])(?:اون|أون)\\s+لاين(?![\\p{L}\\p{N}])"),
        Regex("(?<![\\p{L}\\p{N}])بجود[ةه]\\s+عالي[ةه](?![\\p{L}\\p{N}])"),
        Regex("(?<![\\p{L}\\p{N}])جميع\\s+الحلقات(?![\\p{L}\\p{N}])"),
        Regex("(?<![\\p{L}\\p{N}])حلق[ةه]\\s+خاص[ةه](?![\\p{L}\\p{N}])"),
        Regex("(?<![\\p{L}\\p{N}])بدون\\s+حجب(?![\\p{L}\\p{N}])"),
        Regex("(?<![\\p{L}\\p{N}])النسخ[ةه]\\s+ال[أإآا]?صلي[ةه](?![\\p{L}\\p{N}])"),
        Regex("(?<![\\p{L}\\p{N}])(?:ال)?(?:موسم|جزء)\\s+(?:$ORDINAL_WORDS|$DIGITS_PATTERN)(?![\\p{L}\\p{N}])"),
        Regex("(?<![\\p{L}\\p{N}])(?:ال)?حلق[ةه]\\s+(?:$ORDINAL_WORDS|$DIGITS_PATTERN)(?![\\p{L}\\p{N}])"),
        Regex("(?<![\\p{L}\\p{N}])(?:ال)?حلقات\\s+$DIGITS_PATTERN(?:\\s*[-–—]\\s*$DIGITS_PATTERN)?(?![\\p{L}\\p{N}])"),
    )

    // Single-word Arabic streaming/category tags
    private val ARABIC_TAG_WORDS_REGEX = Regex(
        "(?<![\\p{L}\\p{N}])(?:" +
            "مترجم[ةه]?|" +
            "مدبلج[ةه]?|" +
            "اونلاين|أونلاين|" +
            "كامل[ةه]?|" +
            "مشاهد[ةه]|" +
            "تحميل|" +
            "الموسم|موسم|" +
            "الجزء|جزء|" +
            "الحلق[ةه]|حلق[ةه]|الحلقات|" +
            "انم[يیى]|أنم[يیى]|" +
            "فيلم|فلم|افلام|أفلام|" +
            "مسلسل(?:ات)?|" +
            "كرتون|كارتون|" +
            "اوفا|أوفا|" +
            "بالعرب[يی][ةه]?|عرب[يی][ةه]?|" +
            "الاخير[ةه]?|الأخير[ةه]?" +
            ")(?![\\p{L}\\p{N}])",
    )

    // Latin quality/release noise that often accompanies Arabic CloudStream titles
    private val QUALITY_NOISE_REGEX = Regex(
        "(?i)(?:\\[|\\(|\\b)(?:1080p|720p|480p|360p|2160p|4k|uhd|fhd|hd|bluray|blu-ray|web-dl|webrip|hdtv)(?:\\]|\\)|\\b)",
    )

    private val EMPTY_BRACKETS_REGEX = Regex("[(\\[{【«]\\s*[)\\]}】»]")
    private val EDGE_PUNCTUATION_REGEX = Regex("^[\\s\\-|:/•·–—.,_]+|[\\s\\-|:/•·–—.,_]+$")
    private val MULTI_SPACE_REGEX = Regex("\\s{2,}")

    /**
     * Returns true if [title] contains any recognized Arabic streaming tag or suffix.
     */
    fun containsArabicTags(title: String): Boolean {
        val stripped = ARABIC_DIACRITICS_REGEX.replace(title, "")
        return ARABIC_PHRASE_PATTERNS.any { it.containsMatchIn(stripped) } ||
            ARABIC_TAG_WORDS_REGEX.containsMatchIn(stripped)
    }

    /**
     * Cleans [rawTitle] for automated tracker lookup (AniList, MAL, SIMKL).
     * If stripping produces an empty string, falls back to the trimmed original title.
     */
    fun normalizeForAutoMatch(rawTitle: String): String {
        val trimmed = rawTitle.trim()
        if (trimmed.isEmpty()) return trimmed

        // Preserve MyAnimeList special query prefixes
        if (trimmed.startsWith("id:") || trimmed.startsWith("my:")) {
            return trimmed
        }

        if (!containsArabicTags(trimmed)) {
            return trimmed
        }

        var cleaned = ARABIC_DIACRITICS_REGEX.replace(trimmed, "")

        for (pattern in ARABIC_PHRASE_PATTERNS) {
            cleaned = pattern.replace(cleaned, " ")
        }
        cleaned = ARABIC_TAG_WORDS_REGEX.replace(cleaned, " ")
        cleaned = QUALITY_NOISE_REGEX.replace(cleaned, " ")
        cleaned = EMPTY_BRACKETS_REGEX.replace(cleaned, " ")
        cleaned = MULTI_SPACE_REGEX.replace(cleaned, " ").trim()
        cleaned = EDGE_PUNCTUATION_REGEX.replace(cleaned, "").trim()

        return cleaned.ifBlank { trimmed }
    }
}
// ANZ <--
