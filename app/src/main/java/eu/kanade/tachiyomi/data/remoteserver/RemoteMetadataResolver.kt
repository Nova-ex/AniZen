package eu.kanade.tachiyomi.data.remoteserver

import android.content.Context
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.POST
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import logcat.LogPriority
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.remoteserver.model.RemoteServer
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale

// ANZ -->
class RemoteMetadataResolver(
    private val context: Context,
    private val networkHelper: NetworkHelper,
    private val json: Json,
) {

    @Serializable
    data class RemoteDetailsJson(
        val title: String? = null,
        val author: String? = null,
        val artist: String? = null,
        val description: String? = null,
        val genre: List<String>? = null,
        val status: String? = null,
    )

    @Serializable
    data class CachedRemoteMetadata(
        val title: String? = null,
        val thumbnailUrl: String? = null,
        val localCoverPath: String? = null,
        val author: String? = null,
        val artist: String? = null,
        val description: String? = null,
        val genres: List<String> = emptyList(),
        val status: Int = SAnime.UNKNOWN,
        val checkedAtMillis: Long = 0L,
    )

    suspend fun resolve(
        server: RemoteServer,
        folderPath: String,
        folderName: String,
        listingClient: RemoteListingClient? = null,
        forceRefresh: Boolean = false,
    ): CachedRemoteMetadata {
        val cacheKey = "${server.id}|${RemoteFileItem.normalizePath(folderPath)}"
        val cacheFile = metadataCacheFile(cacheKey)
        val cached = if (cacheFile.exists()) {
            runCatching { json.decodeFromString<CachedRemoteMetadata>(cacheFile.readText()) }.getOrNull()
        } else {
            null
        }

        val now = System.currentTimeMillis()
        val hasValidLocalCover = cached?.localCoverPath?.let { path ->
            File(path).let { it.exists() && it.length() > 0L }
        } ?: false
        val cacheFresh = cached != null &&
            (now - cached.checkedAtMillis) < METADATA_TTL_MS &&
            (cached.localCoverPath == null || hasValidLocalCover)

        if (!forceRefresh && cacheFresh && cached != null) {
            return cached
        }

        var title = folderName
        var localCoverUri: String? = null
        var localCoverFilePath: String? = null
        var author: String? = null
        var artist: String? = null
        var description: String? = null
        var genres: List<String> = emptyList()
        var status = SAnime.UNKNOWN

        val remoteFiles = runCatching {
            listingClient?.listDirectory(folderPath)
                ?: RemoteServerClient.listDirectory(server, folderPath)
        }.getOrElse { emptyList() }

        val coverItem = findBestCoverItem(remoteFiles)
        if (coverItem != null) {
            val savedCover = ensureCoverCached(server, cacheKey, coverItem, listingClient, forceRefresh)
            if (savedCover != null) {
                localCoverFilePath = savedCover.absolutePath
                localCoverUri = "file://${savedCover.absolutePath}"
            }
        }

        val detailsItem = remoteFiles.firstOrNull {
            !it.isDirectory && it.name.equals(DETAILS_JSON_NAME, ignoreCase = true)
        }
        if (detailsItem != null) {
            val parsedDetails = readDetailsJson(server, detailsItem.path, listingClient)
            if (parsedDetails != null) {
                if (!parsedDetails.title.isNullOrBlank()) title = parsedDetails.title
                if (!parsedDetails.author.isNullOrBlank()) author = parsedDetails.author
                if (!parsedDetails.artist.isNullOrBlank()) artist = parsedDetails.artist
                if (!parsedDetails.description.isNullOrBlank()) description = parsedDetails.description
                if (!parsedDetails.genre.isNullOrEmpty()) genres = parsedDetails.genre
                status = parseStatusString(parsedDetails.status)
            }
        }

        val needsOnlineFallback = localCoverUri == null || description.isNullOrBlank() || genres.isEmpty()
        if (needsOnlineFallback) {
            val cleanedQuery = RemoteEpisodeParser.cleanAnimeTitle(folderName)
            val onlineMeta = fetchOnlineMetadata(cleanedQuery)
            if (onlineMeta != null) {
                if (localCoverUri == null && !onlineMeta.coverUrl.isNullOrBlank()) {
                    localCoverUri = onlineMeta.coverUrl
                }
                if (description.isNullOrBlank() && !onlineMeta.description.isNullOrBlank()) {
                    description = onlineMeta.description
                }
                if (genres.isEmpty() && onlineMeta.genres.isNotEmpty()) {
                    genres = onlineMeta.genres
                }
                if (author.isNullOrBlank() && !onlineMeta.studio.isNullOrBlank()) {
                    author = onlineMeta.studio
                }
                if (status == SAnime.UNKNOWN && onlineMeta.status != SAnime.UNKNOWN) {
                    status = onlineMeta.status
                }
            }
        }

        val resolved = CachedRemoteMetadata(
            title = title,
            thumbnailUrl = localCoverUri,
            localCoverPath = localCoverFilePath,
            author = author,
            artist = artist,
            description = description,
            genres = genres,
            status = status,
            checkedAtMillis = now,
        )
        runCatching {
            cacheFile.parentFile?.mkdirs()
            cacheFile.writeText(json.encodeToString(resolved))
        }
        return resolved
    }

    private fun findBestCoverItem(files: List<RemoteFileItem>): RemoteFileItem? {
        for (preferredName in COVER_CANDIDATE_NAMES) {
            val match = files.firstOrNull { !it.isDirectory && it.name.equals(preferredName, ignoreCase = true) }
            if (match != null) return match
        }
        return null
    }

    private fun ensureCoverCached(
        server: RemoteServer,
        cacheKey: String,
        coverItem: RemoteFileItem,
        listingClient: RemoteListingClient?,
        forceRefresh: Boolean,
    ): File? {
        val ext = coverItem.extension.ifBlank { "jpg" }
        val target = File(coversDir(), "${sha256(cacheKey)}.$ext")
        if (!forceRefresh && target.exists() && target.length() > 0L) {
            return target
        }
        return runCatching {
            val bytes = listingClient?.readSmallFileBytes(coverItem.path, MAX_COVER_BYTES)
                ?: RemoteServerClient.readSmallFileBytes(server, coverItem.path, MAX_COVER_BYTES)
            if (bytes.isEmpty()) return null
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
            target
        }.onFailure {
            logcat(LogPriority.WARN, it) { "Failed to download remote cover: ${coverItem.path}" }
        }.getOrNull()
    }

    private fun readDetailsJson(
        server: RemoteServer,
        detailsPath: String,
        listingClient: RemoteListingClient?,
    ): RemoteDetailsJson? {
        return runCatching {
            val bytes = listingClient?.readSmallFileBytes(detailsPath, MAX_JSON_BYTES)
                ?: RemoteServerClient.readSmallFileBytes(server, detailsPath, MAX_JSON_BYTES)
            json.decodeFromString<RemoteDetailsJson>(bytes.toString(Charsets.UTF_8))
        }.onFailure {
            logcat(LogPriority.WARN, it) { "Failed to parse remote details.json at $detailsPath" }
        }.getOrNull()
    }

    private data class OnlineMetadata(
        val coverUrl: String?,
        val description: String?,
        val genres: List<String>,
        val studio: String?,
        val status: Int,
    )

    private suspend fun fetchOnlineMetadata(query: String): OnlineMetadata? {
        if (query.isBlank()) return null
        return onlineSemaphore.withPermit {
            fetchFromAniList(query) ?: fetchFromJikan(query)
        }
    }

    private fun fetchFromAniList(query: String): OnlineMetadata? {
        return runCatching {
            val payload = JsonObject(
                mapOf(
                    "query" to kotlinx.serialization.json.JsonPrimitive(ANILIST_QUERY),
                    "variables" to JsonObject(
                        mapOf("search" to kotlinx.serialization.json.JsonPrimitive(query)),
                    ),
                ),
            )
            val body = json.encodeToString(JsonObject.serializer(), payload)
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = POST(ANILIST_GRAPHQL_URL, body = body)
            networkHelper.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                val raw = response.body?.string() ?: return@runCatching null
                val root = json.parseToJsonElement(raw).jsonObject
                val media = root["data"]?.jsonObject?.get("Media")?.jsonObject ?: return@runCatching null

                val coverObj = media["coverImage"]?.jsonObject
                val coverUrl = coverObj?.get("extraLarge")?.jsonPrimitive?.contentOrNull
                    ?: coverObj?.get("large")?.jsonPrimitive?.contentOrNull

                val rawDesc = media["description"]?.jsonPrimitive?.contentOrNull
                val cleanDesc = rawDesc?.let(::cleanHtmlText)

                val genres = media["genres"]?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    ?: emptyList()

                val studio = media["studios"]?.jsonObject
                    ?.get("nodes")?.jsonArray
                    ?.firstOrNull()?.jsonObject
                    ?.get("name")?.jsonPrimitive?.contentOrNull

                val status = parseAniListStatus(media["status"]?.jsonPrimitive?.contentOrNull)

                OnlineMetadata(
                    coverUrl = coverUrl,
                    description = cleanDesc,
                    genres = genres,
                    studio = studio,
                    status = status,
                )
            }
        }.onFailure {
            logcat(LogPriority.DEBUG, it) { "AniList metadata lookup failed for '$query'" }
        }.getOrNull()
    }

    private fun fetchFromJikan(query: String): OnlineMetadata? {
        return runCatching {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val request = Request.Builder()
                .url("$JIKAN_SEARCH_URL?q=$encoded&limit=1")
                .build()
            networkHelper.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                val raw = response.body?.string() ?: return@runCatching null
                val root = json.parseToJsonElement(raw).jsonObject
                val first = root["data"]?.jsonArray?.firstOrNull()?.jsonObject ?: return@runCatching null

                val imagesObj = first["images"]?.jsonObject?.get("jpg")?.jsonObject
                val coverUrl = imagesObj?.get("large_image_url")?.jsonPrimitive?.contentOrNull
                    ?: imagesObj?.get("image_url")?.jsonPrimitive?.contentOrNull

                val synopsis = first["synopsis"]?.jsonPrimitive?.contentOrNull?.let(::cleanHtmlText)
                val genres = extractNamedArray(first["genres"]?.jsonArray)
                val studio = extractNamedArray(first["studios"]?.jsonArray).firstOrNull()
                val status = parseJikanStatus(first["status"]?.jsonPrimitive?.contentOrNull)

                OnlineMetadata(
                    coverUrl = coverUrl,
                    description = synopsis,
                    genres = genres,
                    studio = studio,
                    status = status,
                )
            }
        }.onFailure {
            logcat(LogPriority.DEBUG, it) { "Jikan metadata lookup failed for '$query'" }
        }.getOrNull()
    }

    private fun extractNamedArray(array: JsonArray?): List<String> {
        if (array == null) return emptyList()
        return array.mapNotNull { element ->
            element.jsonObject["name"]?.jsonPrimitive?.contentOrNull
        }
    }

    private fun cleanHtmlText(raw: String): String {
        return raw
            .replace(BR_TAG_REGEX, "\n")
            .replace(HTML_TAG_REGEX, "")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .trim()
    }

    private fun parseStatusString(status: String?): Int {
        return when (status?.trim()?.lowercase(Locale.ROOT)) {
            "1", "ongoing", "releasing", "currently airing" -> SAnime.ONGOING
            "2", "completed", "finished", "finished airing" -> SAnime.COMPLETED
            "3", "licensed" -> SAnime.LICENSED
            "4", "publishing finished" -> SAnime.PUBLISHING_FINISHED
            "5", "cancelled" -> SAnime.CANCELLED
            "6", "on hiatus", "hiatus" -> SAnime.ON_HIATUS
            else -> SAnime.UNKNOWN
        }
    }

    private fun parseAniListStatus(status: String?): Int {
        return when (status?.uppercase(Locale.ROOT)) {
            "RELEASING" -> SAnime.ONGOING
            "FINISHED" -> SAnime.COMPLETED
            "CANCELLED" -> SAnime.CANCELLED
            "HIATUS" -> SAnime.ON_HIATUS
            else -> SAnime.UNKNOWN
        }
    }

    private fun parseJikanStatus(status: String?): Int {
        return when {
            status == null -> SAnime.UNKNOWN
            status.contains("Currently Airing", ignoreCase = true) -> SAnime.ONGOING
            status.contains("Finished Airing", ignoreCase = true) -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    private fun coversDir(): File = File(context.cacheDir, "remote_covers")

    private fun metadataCacheFile(cacheKey: String): File {
        return File(File(context.cacheDir, "remote_metadata"), "${sha256(cacheKey)}.json")
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val DETAILS_JSON_NAME = "details.json"
        private const val MAX_COVER_BYTES = 10 * 1024 * 1024
        private const val MAX_JSON_BYTES = 2 * 1024 * 1024
        private const val METADATA_TTL_MS = 6 * 60 * 60 * 1000L
        private const val MAX_CONCURRENT_ONLINE_LOOKUPS = 2
        private const val ANILIST_GRAPHQL_URL = "https://graphql.anilist.co"
        private const val JIKAN_SEARCH_URL = "https://api.jikan.moe/v4/anime"

        private val COVER_CANDIDATE_NAMES = listOf(
            "cover.jpg",
            "cover.jpeg",
            "cover.png",
            "poster.jpg",
            "poster.jpeg",
            "poster.png",
            "folder.jpg",
            "folder.jpeg",
            "folder.png",
        )
        private val BR_TAG_REGEX = Regex("""(?i)<br\s*/?>""")
        private val HTML_TAG_REGEX = Regex("""<[^>]+>""")
        private val onlineSemaphore = Semaphore(MAX_CONCURRENT_ONLINE_LOOKUPS)

        private const val ANILIST_QUERY = """
            query (${'$'}search: String) {
              Media(search: ${'$'}search, type: ANIME, sort: SEARCH_MATCH) {
                title { romaji english native }
                description(asHtml: false)
                status
                genres
                coverImage { extraLarge large }
                studios(isMain: true) { nodes { name } }
              }
            }
        """
    }
}
// ANZ <--
