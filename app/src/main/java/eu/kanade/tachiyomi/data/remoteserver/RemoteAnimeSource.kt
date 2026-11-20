package eu.kanade.tachiyomi.data.remoteserver

import android.content.Context
import eu.kanade.tachiyomi.animesource.UnmeteredSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.HttpServer
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import logcat.LogPriority
import okhttp3.Request
import okhttp3.Response
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.remoteserver.model.RemoteServer
import tachiyomi.domain.remoteserver.model.toSourceId
import java.util.Locale

// ANZ -->
class RemoteAnimeSource(
    val server: RemoteServer,
    private val context: Context,
    private val metadataResolver: RemoteMetadataResolver,
    private val json: Json,
) : AnimeHttpSource(), UnmeteredSource {

    private val sourceToken = RemoteHttpServer.newToken()

    override val id: Long = server.toSourceId()

    override val name: String = server.name.ifBlank { server.host }

    override val lang: String = "other"

    override val baseUrl: String = "http://localhost"

    override val supportsLatest: Boolean = true

    override val supportsRelatedAnimes: Boolean = false

    @Volatile
    private var cachedRootFolders: List<RemoteFileItem> = emptyList()

    @Volatile
    private var cachedRootAtMillis: Long = 0L

    @Serializable
    private data class RemoteEpisodeJson(
        val url: String? = null,
        val name: String? = null,
        val date_upload: Long? = null,
        val episode_number: Float? = null,
        val scanlator: String? = null,
    )

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val filters = AnimeFilterList(listOf(RemoteOrderBy.Popular(context)))
        return getSearchAnime(page, "", filters)
    }

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val filters = AnimeFilterList(listOf(RemoteOrderBy.Latest(context)))
        return getSearchAnime(page, "", filters)
    }

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        return withIOContext {
            RemoteServerClient.openListingClient(server).use { client ->
                val folders = loadRootFolders(client, forceRefresh = page <= 1)

                val filtered = if (query.isBlank()) {
                    folders
                } else {
                    folders.filter { folder ->
                        folder.name.contains(query, ignoreCase = true) ||
                            RemoteEpisodeParser.cleanAnimeTitle(folder.name).contains(query, ignoreCase = true)
                    }
                }

                val orderBy = filters.filterIsInstance<RemoteOrderBy>().firstOrNull()
                val sortByDate = orderBy?.state?.index == 1
                val ascending = orderBy?.state?.ascending ?: true

                val comparator: Comparator<RemoteFileItem> = if (sortByDate) {
                    compareBy<RemoteFileItem> { it.modifiedAtMillis }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                } else {
                    compareBy<RemoteFileItem, String>(String.CASE_INSENSITIVE_ORDER) {
                        RemoteEpisodeParser.cleanAnimeTitle(it.name)
                    }
                }

                val sorted = if (ascending) {
                    filtered.sortedWith(comparator)
                } else {
                    filtered.sortedWith(comparator.reversed())
                }

                val startIndex = ((page - 1).coerceAtLeast(0)) * PAGE_SIZE
                if (startIndex >= sorted.size) {
                    return@use AnimesPage(emptyList(), hasNextPage = false)
                }
                val endIndex = minOf(sorted.size, startIndex + PAGE_SIZE)
                val slice = sorted.subList(startIndex, endIndex)
                val hasNextPage = endIndex < sorted.size

                val animes = slice.map { folder ->
                    val cleanTitle = RemoteEpisodeParser.cleanAnimeTitle(folder.name)
                    val meta = runCatching {
                        metadataResolver.resolve(
                            server = server,
                            folderPath = folder.path,
                            folderName = folder.name,
                            listingClient = client,
                            forceRefresh = false,
                        )
                    }.getOrNull()

                    SAnime.create().apply {
                        url = folder.path
                        title = meta?.title?.takeIf { it.isNotBlank() } ?: cleanTitle
                        thumbnail_url = meta?.thumbnailUrl
                        author = meta?.author
                        artist = meta?.artist
                        description = meta?.description
                        genre = meta?.genres?.takeIf { it.isNotEmpty() }?.joinToString(", ")
                        status = meta?.status ?: SAnime.UNKNOWN
                    }
                }

                AnimesPage(animes, hasNextPage)
            }
        }
    }

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        return withIOContext {
            val folderPath = RemoteFileItem.normalizePath(anime.url)
            val folderName = folderPath.substringAfterLast('/').ifBlank { anime.title }
            val cleanTitle = RemoteEpisodeParser.cleanAnimeTitle(folderName)

            val meta = RemoteServerClient.openListingClient(server).use { client ->
                metadataResolver.resolve(
                    server = server,
                    folderPath = folderPath,
                    folderName = folderName,
                    listingClient = client,
                    forceRefresh = true,
                )
            }

            SAnime.create().apply {
                url = anime.url
                title = meta.title?.takeIf { it.isNotBlank() } ?: cleanTitle
                thumbnail_url = meta.thumbnailUrl ?: anime.thumbnail_url
                author = meta.author
                artist = meta.artist
                description = meta.description
                genre = meta.genres.takeIf { it.isNotEmpty() }?.joinToString(", ")
                status = meta.status
                initialized = true
            }
        }
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        return withIOContext {
            val folderPath = RemoteFileItem.normalizePath(anime.url)
            val animeTitle = anime.title.ifBlank {
                RemoteEpisodeParser.cleanAnimeTitle(folderPath.substringAfterLast('/'))
            }

            RemoteServerClient.openListingClient(server).use { client ->
                val allEntries = RemoteServerClient.listDirectoryRecursive(
                    server = server,
                    remotePath = folderPath,
                    maxDepth = MAX_EPISODE_SCAN_DEPTH,
                    client = client,
                )

                val episodesJsonMap = parseEpisodesJson(
                    client = client,
                    entries = allEntries,
                )

                val videoEntries = allEntries.filter { it.item.isVideo }
                val sortable = videoEntries.map { entry ->
                    val item = entry.item
                    val relativePath = if (entry.relativeSubfolder.isEmpty()) {
                        item.name
                    } else {
                        "${entry.relativeSubfolder}/${item.name}"
                    }
                    val override = episodesJsonMap[relativePath] ?: episodesJsonMap[item.name]
                    val parsed = RemoteEpisodeParser.parseEpisode(
                        fileName = item.name,
                        relativeSubfolder = entry.relativeSubfolder,
                        animeTitle = animeTitle,
                    )

                    val finalEpisodeNumber = override?.episode_number ?: parsed.episodeNumber
                    val finalName = override?.name?.takeIf { it.isNotBlank() } ?: parsed.displayName
                    val finalDate = override?.date_upload ?: item.modifiedAtMillis
                    val finalScanlator = override?.scanlator?.takeIf { it.isNotBlank() } ?: parsed.scanlator

                    val episode = SEpisode.create().apply {
                        url = item.path
                        name = finalName
                        episode_number = finalEpisodeNumber
                        date_upload = finalDate
                        scanlator = finalScanlator
                    }

                    SortableEpisode(
                        episode = episode,
                        seasonSort = parsed.seasonNumber ?: DEFAULT_SEASON,
                        fileName = item.name,
                    )
                }

                sortable
                    .sortedWith(
                        compareByDescending<SortableEpisode> { it.seasonSort }
                            .thenByDescending { it.episode.episode_number }
                            .thenByDescending(String.CASE_INSENSITIVE_ORDER) { it.fileName },
                    )
                    .map { it.episode }
            }
        }
    }

    override fun getFilterList(): AnimeFilterList {
        return AnimeFilterList(listOf(RemoteOrderBy.Popular(context)))
    }

    override fun createHttpServer(): HttpServer = RemoteHttpServer(server, sourceToken)

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        return withIOContext {
            val remoteFilePath = RemoteFileItem.normalizePath(episode.url)
            val parentDir = RemoteFileItem.parentPath(remoteFilePath)
            val videoFileName = remoteFilePath.substringAfterLast('/')
            val videoBaseName = videoFileName.substringBeforeLast('.')

            val siblingItems = runCatching {
                RemoteServerClient.listDirectory(server, parentDir)
            }.getOrElse { emptyList() }

            val subtitleTracks = siblingItems
                .asSequence()
                .filter { it.isSubtitle }
                .filter { sub ->
                    val subBase = sub.name.substringBeforeLast('.')
                    subBase.equals(videoBaseName, ignoreCase = true) ||
                        subBase.startsWith("$videoBaseName.", ignoreCase = true) ||
                        subBase.startsWith("${videoBaseName}_", ignoreCase = true) ||
                        subBase.startsWith("$videoBaseName ", ignoreCase = true)
                }
                .map { sub ->
                    Track(
                        url = RemoteHttpServer.buildSubtitleUrl(sub.path, sourceToken),
                        lang = extractSubtitleLabel(sub.name, videoBaseName),
                    )
                }
                .toList()

            val video = Video(
                videoUrl = RemoteHttpServer.buildStreamUrl(remoteFilePath, sourceToken),
                videoTitle = "${server.protocol.name} (${server.host})",
                preferred = true,
                subtitleTracks = subtitleTracks,
            )

            listOf(
                Hoster(
                    hosterUrl = HttpServer.PLACEHOLDER_URL,
                    hosterName = name,
                    videoList = listOf(video),
                ),
            )
        }
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> = hoster.videoList ?: emptyList()

    override suspend fun getVideoList(episode: SEpisode): List<Video> = getHosterList(episode).flatMap { it.videoList.orEmpty() }

    override fun getAnimeUrl(anime: SAnime): String {
        val proto = server.protocol.name.lowercase(Locale.ROOT)
        return "$proto://${server.host}:${server.port}${RemoteFileItem.normalizePath(anime.url)}"
    }

    override fun getEpisodeUrl(episode: SEpisode): String {
        val proto = server.protocol.name.lowercase(Locale.ROOT)
        return "$proto://${server.host}:${server.port}${RemoteFileItem.normalizePath(episode.url)}"
    }

    override fun popularAnimeRequest(page: Int): Request = throw UnsupportedOperationException("Not used")

    override fun popularAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException("Not used")

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        throw UnsupportedOperationException("Not used")

    override fun searchAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException("Not used")

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException("Not used")

    override fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException("Not used")

    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException("Not used")

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException("Not used")

    override fun episodeVideoParse(response: Response): SEpisode = throw UnsupportedOperationException("Not used")

    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException("Not used")

    override fun videoListParse(response: Response, hoster: Hoster): List<Video> = throw UnsupportedOperationException("Not used")

    override fun videoListParse(response: Response): List<Video> = throw UnsupportedOperationException("Not used")

    override fun videoUrlParse(response: Response): String = throw UnsupportedOperationException("Not used")

    private fun loadRootFolders(
        client: RemoteListingClient,
        forceRefresh: Boolean,
    ): List<RemoteFileItem> {
        val now = System.currentTimeMillis()
        if (!forceRefresh && cachedRootFolders.isNotEmpty() && (now - cachedRootAtMillis) < ROOT_CACHE_TTL_MS) {
            return cachedRootFolders
        }
        val items = client.listDirectory(server.normalizedInitialPath())
            .filter { it.isDirectory && !it.name.startsWith('.') }
        cachedRootFolders = items
        cachedRootAtMillis = now
        return items
    }

    private fun parseEpisodesJson(
        client: RemoteListingClient,
        entries: List<RemoteFileEntry>,
    ): Map<String, RemoteEpisodeJson> {
        val episodesFile = entries.firstOrNull {
            it.relativeSubfolder.isEmpty() && it.item.name.equals(EPISODES_JSON_NAME, ignoreCase = true)
        } ?: return emptyMap()

        return runCatching {
            val bytes = client.readSmallFileBytes(episodesFile.item.path)
            val parsed = json.decodeFromString<List<RemoteEpisodeJson>>(bytes.toString(Charsets.UTF_8))
            parsed.mapNotNull { entry ->
                val key = entry.url?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                key to entry
            }.toMap()
        }.onFailure {
            logcat(LogPriority.WARN, it) { "Failed to parse remote episodes.json" }
        }.getOrElse { emptyMap() }
    }

    private fun extractSubtitleLabel(subName: String, videoBaseName: String): String {
        val ext = subName.substringAfterLast('.', "").uppercase(Locale.ROOT)
        val withoutExt = subName.substringBeforeLast('.')
        val suffix = if (withoutExt.length > videoBaseName.length) {
            withoutExt.substring(videoBaseName.length).trim('.', '_', '-', ' ')
        } else {
            ""
        }
        return if (suffix.isNotEmpty()) "$suffix ($ext)" else "Subtitles ($ext)"
    }

    private data class SortableEpisode(
        val episode: SEpisode,
        val seasonSort: Int,
        val fileName: String,
    )

    companion object {
        private const val PAGE_SIZE = 24
        private const val MAX_EPISODE_SCAN_DEPTH = 3
        private const val DEFAULT_SEASON = 1
        private const val ROOT_CACHE_TTL_MS = 30_000L
        private const val EPISODES_JSON_NAME = "episodes.json"
    }
}
// ANZ <--
