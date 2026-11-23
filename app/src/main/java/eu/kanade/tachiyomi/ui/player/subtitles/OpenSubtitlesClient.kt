// ANZ -->
package eu.kanade.tachiyomi.ui.player.subtitles

import android.content.Context
import androidx.compose.runtime.Immutable
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.ui.player.settings.SubtitlePreferences
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import logcat.LogPriority
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

enum class OnlineSubtitleLanguage(
    val code: String,
    val iso6392: String,
) {
    ARABIC("ar", "ara"),
    ENGLISH("en", "eng"),
    ALL("all", "all"),
    ;

    companion object {
        fun fromCode(code: String?): OnlineSubtitleLanguage {
            return when (code?.trim()?.lowercase(Locale.ROOT)) {
                "ar", "ara", "arabic" -> ARABIC
                "en", "eng", "english" -> ENGLISH
                else -> ALL
            }
        }
    }
}

@Immutable
data class OnlineSubtitleResult(
    val id: String,
    val title: String,
    val releaseName: String,
    val languageCode: String,
    val languageLabel: String,
    val format: String,
    val downloadCount: Int = 0,
    val sourceLabel: String,
    val downloadUrl: String? = null,
    val fileId: Long? = null,
)

@Immutable
data class OnlineSubtitleUiState(
    val query: String = "",
    val season: String = "",
    val episode: String = "",
    val language: OnlineSubtitleLanguage = OnlineSubtitleLanguage.ARABIC,
    val isSearching: Boolean = false,
    val hasSearched: Boolean = false,
    val downloadingId: String? = null,
    val results: List<OnlineSubtitleResult> = emptyList(),
    val errorMessage: String? = null,
)

class OpenSubtitlesClient(
    networkHelper: NetworkHelper,
    private val subtitlePreferences: SubtitlePreferences,
) {
    private val client = networkHelper.client.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    @Volatile
    private var cachedJwtToken: String? = null

    @Volatile
    private var cachedAuthSignature: String? = null

    suspend fun search(
        query: String,
        season: Int?,
        episode: Int?,
        language: OnlineSubtitleLanguage,
    ): List<OnlineSubtitleResult> = withIOContext {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return@withIOContext emptyList()

        val apiKey = subtitlePreferences.openSubtitlesApiKey().get().trim()
        val combined = mutableListOf<OnlineSubtitleResult>()

        if (apiKey.isNotBlank()) {
            runCatching {
                searchOpenSubtitlesV1(
                    apiKey = apiKey,
                    query = cleanQuery,
                    season = season,
                    episode = episode,
                    language = language,
                )
            }.onSuccess {
                combined.addAll(it)
            }.onFailure {
                logcat(LogPriority.WARN, it) { "OpenSubtitles.com v1 search failed; falling back to public endpoints" }
            }
        }

        if (combined.isEmpty()) {
            coroutineScope {
                val stremioDeferred = async {
                    runCatching {
                        searchStremioOpenSubtitlesV3(
                            query = cleanQuery,
                            season = season,
                            episode = episode,
                            language = language,
                        )
                    }.onFailure {
                        logcat(LogPriority.WARN, it) { "Stremio OpenSubtitles v3 search failed" }
                    }.getOrDefault(emptyList())
                }
                val legacyRestDeferred = async {
                    runCatching {
                        searchOpenSubtitlesOrgRest(
                            query = cleanQuery,
                            season = season,
                            episode = episode,
                            language = language,
                        )
                    }.onFailure {
                        logcat(LogPriority.WARN, it) { "OpenSubtitles.org public REST search failed" }
                    }.getOrDefault(emptyList())
                }
                combined.addAll(stremioDeferred.await())
                combined.addAll(legacyRestDeferred.await())
            }
        }

        combined
            .filter { item ->
                language == OnlineSubtitleLanguage.ALL ||
                    item.languageCode.equals(language.code, ignoreCase = true)
            }
            .distinctBy { it.fileId?.let { id -> "v1:$id" } ?: it.downloadUrl ?: it.id }
            .sortedWith(
                compareByDescending<OnlineSubtitleResult> {
                    if (language != OnlineSubtitleLanguage.ALL && it.languageCode == language.code) 1 else 0
                }
                    .thenByDescending { it.downloadCount }
                    .thenBy { it.releaseName.lowercase(Locale.ROOT) },
            )
    }

    suspend fun downloadToCache(
        context: Context,
        result: OnlineSubtitleResult,
    ): File = withIOContext {
        val rawDownload = if (result.fileId != null) {
            downloadFromOpenSubtitlesV1(result.fileId)
        } else {
            val url = result.downloadUrl?.takeIf { it.isNotBlank() }
                ?: error("Missing subtitle download URL")
            val bytes = fetchBytes(url, includeLegacyUserAgent = url.contains("opensubtitles.org", ignoreCase = true))
            DownloadedPayload(bytes = bytes, suggestedName = result.releaseName, formatHint = result.format)
        }

        val extracted = extractSubtitlePayload(
            bytes = rawDownload.bytes,
            formatHint = rawDownload.formatHint.ifBlank { result.format },
        )

        val cacheDir = File(context.cacheDir, CACHE_SUBDIR).apply { mkdirs() }
        pruneOldCacheFiles(cacheDir)

        val safeBase = (rawDownload.suggestedName.ifBlank { result.releaseName.ifBlank { result.title } })
            .replace(FILE_NAME_SANITIZE_REGEX, "_")
            .trim('_')
            .take(MAX_FILENAME_LENGTH)
            .ifBlank { "subtitle" }
        val ext = normalizeSubtitleExtension(extracted.extension)
        val outFile = File(cacheDir, "${safeBase}_${result.languageCode}_${System.currentTimeMillis()}.$ext")
        outFile.writeBytes(extracted.bytes)
        outFile
    }

    private fun searchOpenSubtitlesV1(
        apiKey: String,
        query: String,
        season: Int?,
        episode: Int?,
        language: OnlineSubtitleLanguage,
    ): List<OnlineSubtitleResult> {
        val urlBuilder = "$OPENSUBTITLES_V1_BASE/subtitles".toHttpUrl().newBuilder()
            .addQueryParameter("query", query)
        if (language != OnlineSubtitleLanguage.ALL) {
            urlBuilder.addQueryParameter("languages", language.code)
        }
        if (episode != null && episode > 0) {
            urlBuilder.addQueryParameter("episode_number", episode.toString())
            if (season != null && season > 0) {
                urlBuilder.addQueryParameter("season_number", season.toString())
            }
        }

        val request = Request.Builder()
            .url(urlBuilder.build())
            .header("Api-Key", apiKey)
            .header("User-Agent", OPENSUBTITLES_V1_USER_AGENT)
            .header("Accept", "application/json")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("OpenSubtitles.com HTTP ${response.code}")
            }
            val body = response.body.string()
            val root = JSONObject(body)
            val data = root.optJSONArray("data") ?: return emptyList()
            val results = mutableListOf<OnlineSubtitleResult>()
            for (i in 0 until data.length()) {
                val entry = data.optJSONObject(i) ?: continue
                val attrs = entry.optJSONObject("attributes") ?: continue
                val files = attrs.optJSONArray("files") ?: continue
                val firstFile = files.optJSONObject(0) ?: continue
                val fileId = firstFile.optLong("file_id", -1L).takeIf { it > 0L } ?: continue
                val fileName = firstFile.optString("file_name", "").trim()
                val release = attrs.optString("release", "").trim().ifBlank { fileName }
                val featureTitle = attrs.optJSONObject("feature_details")?.optString("title", "").orEmpty().trim()
                val langCode = normalizeLanguageCode(attrs.optString("language", ""))
                val format = inferExtensionFromName(fileName) ?: "srt"
                val downloads = attrs.optInt("download_count", 0)
                results.add(
                    OnlineSubtitleResult(
                        id = "v1_$fileId",
                        title = featureTitle.ifBlank { release.ifBlank { query } },
                        releaseName = release.ifBlank { featureTitle.ifBlank { "Subtitle #$fileId" } },
                        languageCode = langCode,
                        languageLabel = languageDisplayName(langCode),
                        format = format,
                        downloadCount = downloads,
                        sourceLabel = "OpenSubtitles.com",
                        fileId = fileId,
                    ),
                )
            }
            return results
        }
    }

    private fun downloadFromOpenSubtitlesV1(fileId: Long): DownloadedPayload {
        val apiKey = subtitlePreferences.openSubtitlesApiKey().get().trim()
        if (apiKey.isBlank()) error("OpenSubtitles API key not configured")

        val jwt = resolveJwtTokenIfConfigured(apiKey)
        val payload = JSONObject().put("file_id", fileId).toString()
        val requestBuilder = Request.Builder()
            .url("$OPENSUBTITLES_V1_BASE/download")
            .header("Api-Key", apiKey)
            .header("User-Agent", OPENSUBTITLES_V1_USER_AGENT)
            .header("Accept", "application/json")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
        if (!jwt.isNullOrBlank()) {
            requestBuilder.header("Authorization", "Bearer $jwt")
        }

        val (downloadLink, fileName) = client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                error("OpenSubtitles.com download HTTP ${response.code}")
            }
            val json = JSONObject(response.body.string())
            val link = json.optString("link", "").trim()
            val name = json.optString("file_name", "").trim()
            if (link.isBlank()) error("OpenSubtitles.com did not return a download link")
            link to name
        }

        val bytes = fetchBytes(downloadLink, includeLegacyUserAgent = false)
        return DownloadedPayload(
            bytes = bytes,
            suggestedName = fileName,
            formatHint = inferExtensionFromName(fileName) ?: "srt",
        )
    }

    private fun resolveJwtTokenIfConfigured(apiKey: String): String? {
        val username = subtitlePreferences.openSubtitlesUsername().get().trim()
        val password = subtitlePreferences.openSubtitlesPassword().get()
        if (username.isBlank() || password.isBlank()) return null

        val signature = "$apiKey|$username|$password"
        if (cachedAuthSignature == signature && !cachedJwtToken.isNullOrBlank()) {
            return cachedJwtToken
        }

        val body = JSONObject()
            .put("username", username)
            .put("password", password)
            .toString()

        val request = Request.Builder()
            .url("$OPENSUBTITLES_V1_BASE/login")
            .header("Api-Key", apiKey)
            .header("User-Agent", OPENSUBTITLES_V1_USER_AGENT)
            .header("Accept", "application/json")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val token = JSONObject(response.body.string()).optString("token", "").trim()
                if (token.isNotBlank()) {
                    cachedAuthSignature = signature
                    cachedJwtToken = token
                    token
                } else {
                    null
                }
            }
        }.getOrNull()
    }

    private fun searchStremioOpenSubtitlesV3(
        query: String,
        season: Int?,
        episode: Int?,
        language: OnlineSubtitleLanguage,
    ): List<OnlineSubtitleResult> {
        val metas = mutableListOf<CinemetaEntry>()
        if (episode != null && episode > 0) {
            metas.addAll(searchCinemeta(query, type = "series").take(MAX_CINEMETA_CANDIDATES))
        }
        if (metas.isEmpty()) {
            metas.addAll(searchCinemeta(query, type = "series").take(MAX_CINEMETA_CANDIDATES))
            metas.addAll(searchCinemeta(query, type = "movie").take(1))
        }
        if (metas.isEmpty()) return emptyList()

        val results = mutableListOf<OnlineSubtitleResult>()
        val targetSeason = season?.takeIf { it > 0 } ?: 1

        for (meta in metas) {
            val subEndpoint = if (meta.type == "series" && episode != null && episode > 0) {
                "$STREMIO_OPENSUBTITLES_V3_BASE/subtitles/series/${meta.imdbId}:$targetSeason:$episode.json"
            } else if (meta.type == "movie") {
                "$STREMIO_OPENSUBTITLES_V3_BASE/subtitles/movie/${meta.imdbId}.json"
            } else {
                "$STREMIO_OPENSUBTITLES_V3_BASE/subtitles/series/${meta.imdbId}:$targetSeason:1.json"
            }

            val request = Request.Builder()
                .url(subEndpoint)
                .header("User-Agent", BROWSER_USER_AGENT)
                .header("Accept", "application/json")
                .get()
                .build()

            runCatching {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val root = JSONObject(response.body.string())
                    val subs = root.optJSONArray("subtitles") ?: return@use
                    for (i in 0 until subs.length()) {
                        val sub = subs.optJSONObject(i) ?: continue
                        val url = sub.optString("url", "").trim()
                        if (url.isBlank()) continue
                        val langCode = normalizeLanguageCode(sub.optString("lang", ""))
                        if (language != OnlineSubtitleLanguage.ALL && langCode != language.code) continue
                        val subId = sub.optString("id", "").ifBlank { "stremio_${meta.imdbId}_$i" }
                        val epTag = if (meta.type == "series" && episode != null && episode > 0) {
                            " S${targetSeason.toString().padStart(2, '0')}E${episode.toString().padStart(2, '0')}"
                        } else {
                            ""
                        }
                        val format = inferExtensionFromName(url) ?: "srt"
                        results.add(
                            OnlineSubtitleResult(
                                id = "stremio_$subId",
                                title = meta.name,
                                releaseName = "${meta.name}$epTag • ${languageDisplayName(langCode)} (#${i + 1})",
                                languageCode = langCode,
                                languageLabel = languageDisplayName(langCode),
                                format = format,
                                downloadCount = (subs.length() - i).coerceAtLeast(1),
                                sourceLabel = "OpenSubtitles v3",
                                downloadUrl = url,
                            ),
                        )
                    }
                }
            }
            if (results.size >= MAX_STREMIO_RESULTS) break
        }

        return results.take(MAX_STREMIO_RESULTS)
    }

    private fun searchCinemeta(query: String, type: String): List<CinemetaEntry> {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name()).replace("+", "%20")
        val url = "$CINEMETA_BASE/catalog/$type/top/search=$encoded.json"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", BROWSER_USER_AGENT)
            .header("Accept", "application/json")
            .get()
            .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                val metas = JSONObject(response.body.string()).optJSONArray("metas") ?: return@use emptyList()
                buildList {
                    for (i in 0 until metas.length()) {
                        val item = metas.optJSONObject(i) ?: continue
                        val id = item.optString("imdb_id", "").ifBlank { item.optString("id", "") }.trim()
                        val name = item.optString("name", "").trim()
                        if (id.startsWith("tt") && name.isNotBlank()) {
                            add(CinemetaEntry(imdbId = id, name = name, type = type))
                        }
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun searchOpenSubtitlesOrgRest(
        query: String,
        season: Int?,
        episode: Int?,
        language: OnlineSubtitleLanguage,
    ): List<OnlineSubtitleResult> {
        // OpenSubtitles.org legacy REST requires path parameters sorted alphabetically.
        val segments = mutableListOf<String>()
        if (episode != null && episode > 0) {
            segments.add("episode-$episode")
        }
        val encodedQuery = URLEncoder.encode(query.lowercase(Locale.ROOT), Charsets.UTF_8.name())
            .replace("+", "%20")
        segments.add("query-$encodedQuery")
        if (episode != null && episode > 0 && season != null && season > 0) {
            segments.add("season-$season")
        }
        segments.add("sublanguageid-${language.iso6392}")

        val url = "$OPENSUBTITLES_ORG_REST_BASE/${segments.joinToString("/")}"
        val request = Request.Builder()
            .url(url)
            .header("X-User-Agent", LEGACY_REST_USER_AGENT)
            .header("User-Agent", LEGACY_REST_USER_AGENT)
            .header("Accept", "application/json")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val text = response.body.string().trim()
            if (!text.startsWith("[")) return emptyList()
            val array = JSONArray(text)
            val results = mutableListOf<OnlineSubtitleResult>()
            for (i in 0 until array.length().coerceAtMost(MAX_LEGACY_RESULTS)) {
                val obj = array.optJSONObject(i) ?: continue
                val downloadLink = obj.optString("SubDownloadLink", "").trim()
                if (downloadLink.isBlank()) continue
                val subFileName = obj.optString("SubFileName", "").trim()
                val release = obj.optString("MovieReleaseName", "").trim().ifBlank { subFileName }
                val movieName = obj.optString("MovieName", "").trim()
                val rawLang = obj.optString("ISO639", "").ifBlank { obj.optString("SubLanguageID", "") }
                val langCode = normalizeLanguageCode(rawLang)
                val langLabel = obj.optString("LanguageName", "").trim().ifBlank { languageDisplayName(langCode) }
                val format = obj.optString("SubFormat", "").trim().lowercase(Locale.ROOT)
                    .ifBlank { inferExtensionFromName(subFileName) ?: "srt" }
                val downloads = obj.optString("SubDownloadsCnt", "0").toIntOrNull() ?: 0
                val id = obj.optString("IDSubtitleFile", "").ifBlank { "org_$i" }
                results.add(
                    OnlineSubtitleResult(
                        id = "org_$id",
                        title = movieName.ifBlank { query },
                        releaseName = release.ifBlank { subFileName.ifBlank { movieName.ifBlank { query } } },
                        languageCode = langCode,
                        languageLabel = langLabel,
                        format = format,
                        downloadCount = downloads,
                        sourceLabel = "OpenSubtitles.org",
                        downloadUrl = downloadLink,
                    ),
                )
            }
            return results
        }
    }

    private fun fetchBytes(url: String, includeLegacyUserAgent: Boolean): ByteArray {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", if (includeLegacyUserAgent) LEGACY_REST_USER_AGENT else BROWSER_USER_AGENT)
            .get()
        if (includeLegacyUserAgent) {
            builder.header("X-User-Agent", LEGACY_REST_USER_AGENT)
        }
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                error("Download HTTP ${response.code}")
            }
            return response.body.bytes()
        }
    }

    private fun extractSubtitlePayload(bytes: ByteArray, formatHint: String): ExtractedSubtitle {
        if (bytes.size >= 2 && bytes[0] == GZIP_MAGIC_0 && bytes[1] == GZIP_MAGIC_1) {
            val unzipped = GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
            return ExtractedSubtitle(bytes = unzipped, extension = formatHint.ifBlank { "srt" })
        }

        if (bytes.size >= 2 && bytes[0] == ZIP_MAGIC_0 && bytes[1] == ZIP_MAGIC_1) {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val ext = inferExtensionFromName(entry.name)
                        if (ext != null) {
                            return ExtractedSubtitle(bytes = zis.readBytes(), extension = ext)
                        }
                    }
                    entry = zis.nextEntry
                }
            }
        }

        return ExtractedSubtitle(bytes = bytes, extension = formatHint.ifBlank { "srt" })
    }

    private fun pruneOldCacheFiles(cacheDir: File) {
        val files = cacheDir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        if (files.size > MAX_CACHED_SUBTITLE_FILES) {
            files.drop(MAX_CACHED_SUBTITLE_FILES).forEach { runCatching { it.delete() } }
        }
    }

    private data class CinemetaEntry(
        val imdbId: String,
        val name: String,
        val type: String,
    )

    private data class DownloadedPayload(
        val bytes: ByteArray,
        val suggestedName: String,
        val formatHint: String,
    )

    private data class ExtractedSubtitle(
        val bytes: ByteArray,
        val extension: String,
    )

    companion object {
        private const val OPENSUBTITLES_V1_BASE = "https://api.opensubtitles.com/api/v1"
        private const val OPENSUBTITLES_ORG_REST_BASE = "https://rest.opensubtitles.org/search"
        private const val STREMIO_OPENSUBTITLES_V3_BASE = "https://opensubtitles-v3.strem.io"
        private const val CINEMETA_BASE = "https://v3-cinemeta.strem.io"
        private const val OPENSUBTITLES_V1_USER_AGENT = "AniZen v2.7"
        private const val LEGACY_REST_USER_AGENT = "VLSub 0.11.1"
        private const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
        private const val CACHE_SUBDIR = "online_subtitles"
        private const val CONNECT_TIMEOUT_SECONDS = 15L
        private const val READ_TIMEOUT_SECONDS = 20L
        private const val MAX_CINEMETA_CANDIDATES = 2
        private const val MAX_STREMIO_RESULTS = 25
        private const val MAX_LEGACY_RESULTS = 25
        private const val MAX_CACHED_SUBTITLE_FILES = 24
        private const val MAX_FILENAME_LENGTH = 64
        private const val GZIP_MAGIC_0: Byte = 0x1F.toByte()
        private const val GZIP_MAGIC_1: Byte = 0x8B.toByte()
        private const val ZIP_MAGIC_0: Byte = 0x50.toByte()
        private const val ZIP_MAGIC_1: Byte = 0x4B.toByte()
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val FILE_NAME_SANITIZE_REGEX = Regex("[^a-zA-Z0-9._-]+")
        private val SUPPORTED_SUBTITLE_EXTENSIONS = setOf("srt", "ass", "vtt", "ssa")

        internal fun normalizeLanguageCode(raw: String?): String {
            val token = raw?.trim()?.lowercase(Locale.ROOT)?.substringBefore('-')?.substringBefore('_').orEmpty()
            return when (token) {
                "ar", "ara", "arabic" -> "ar"
                "en", "eng", "english" -> "en"
                "fr", "fra", "fre", "french" -> "fr"
                "es", "spa", "spanish" -> "es"
                "de", "deu", "ger", "german" -> "de"
                "ja", "jpn", "japanese" -> "ja"
                "pt", "por", "portuguese" -> "pt"
                "ru", "rus", "russian" -> "ru"
                "tr", "tur", "turkish" -> "tr"
                "it", "ita", "italian" -> "it"
                "ko", "kor", "korean" -> "ko"
                "zh", "zho", "chi", "chinese" -> "zh"
                else -> token.ifBlank { "und" }
            }
        }

        internal fun languageDisplayName(code: String): String {
            return when (code.lowercase(Locale.ROOT)) {
                "ar" -> "Arabic"
                "en" -> "English"
                "fr" -> "French"
                "es" -> "Spanish"
                "de" -> "German"
                "ja" -> "Japanese"
                "pt" -> "Portuguese"
                "ru" -> "Russian"
                "tr" -> "Turkish"
                "it" -> "Italian"
                "ko" -> "Korean"
                "zh" -> "Chinese"
                else -> code.uppercase(Locale.ROOT)
            }
        }

        internal fun inferExtensionFromName(nameOrUrl: String?): String? {
            if (nameOrUrl.isNullOrBlank()) return null
            val cleaned = nameOrUrl.substringBefore('?').substringBefore('#')
                .removeSuffix(".gz")
                .removeSuffix(".zip")
            val ext = cleaned.substringAfterLast('.', "").lowercase(Locale.ROOT)
            return ext.takeIf { it in SUPPORTED_SUBTITLE_EXTENSIONS }
        }

        internal fun normalizeSubtitleExtension(ext: String?): String {
            val lower = ext?.trim()?.lowercase(Locale.ROOT).orEmpty()
            return if (lower in SUPPORTED_SUBTITLE_EXTENSIONS) lower else "srt"
        }
    }
}
// ANZ <--
