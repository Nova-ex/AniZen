// ANZ -->
package eu.kanade.tachiyomi.data.cloudstream

import android.webkit.CookieManager
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import logcat.LogPriority
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import java.net.URI
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class CloudStreamAnimeSource(
    val provider: MainAPI,
    val iconUrl: String? = null,
    val pluginInternalName: String? = null,
) : AnimeHttpSource() {

    override val lang: String = normalizeLangCode(provider.lang)

    override val id: Long = computeCloudStreamSourceId(provider.name, lang)

    override val name: String = provider.name

    override val baseUrl: String = provider.mainUrl.ifBlank { FALLBACK_BASE_URL }

    override val supportsLatest: Boolean = provider.hasMainPage && provider.mainPage.size > 1

    override val supportsRelatedAnimes: Boolean = false

    private val loadCache = ConcurrentHashMap<String, CachedLoad>()

    private data class CachedLoad(
        val response: LoadResponse,
        val cachedAtMillis: Long,
    )

    private class CategoryFilter(
        categories: Array<String>,
    ) : AnimeFilter.Select<String>("Category", categories)

    override fun getFilterList(): AnimeFilterList {
        val sections = provider.mainPage
        if (sections.isEmpty()) return AnimeFilterList()
        val labels = sections.map { it.name.ifBlank { provider.name } }.toTypedArray()
        return AnimeFilterList(listOf(CategoryFilter(labels)))
    }

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        return withIOContext {
            loadMainPageSection(sectionIndex = 0, page = page)
        }
    }

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        return withIOContext {
            val latestIndex = if (provider.mainPage.size > 1) 1 else 0
            loadMainPageSection(sectionIndex = latestIndex, page = page)
        }
    }

    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage {
        return withIOContext {
            val trimmedQuery = query.trim()
            if (trimmedQuery.isBlank()) {
                val categoryFilter = filters.filterIsInstance<CategoryFilter>().firstOrNull()
                val index = categoryFilter?.state ?: 0
                return@withIOContext loadMainPageSection(sectionIndex = index, page = page)
            }

            val pagedResponse = runCatching {
                provider.search(trimmedQuery, page)
            }.onFailure {
                logcat(LogPriority.WARN, it) { "CloudStream search(query, page) failed for ${provider.name}" }
            }.getOrNull()

            if (pagedResponse != null && pagedResponse.items.isNotEmpty()) {
                val animes = pagedResponse.items
                    .distinctBy { it.url }
                    .map { it.toSAnime() }
                return@withIOContext AnimesPage(animes, hasNextPage = pagedResponse.hasNext)
            }

            if (page == 1) {
                val simpleItems = runCatching {
                    provider.search(trimmedQuery)
                }.onFailure {
                    logcat(LogPriority.WARN, it) { "CloudStream search(query) failed for ${provider.name}" }
                }.getOrNull().orEmpty()

                val animes = simpleItems
                    .distinctBy { it.url }
                    .map { it.toSAnime() }
                return@withIOContext AnimesPage(animes, hasNextPage = false)
            }

            AnimesPage(emptyList(), hasNextPage = false)
        }
    }

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        return withIOContext {
            val loaded = loadDetailsCached(anime.url, forceRefresh = true)
                ?: return@withIOContext anime.apply { initialized = true }

            SAnime.create().apply {
                url = loaded.url.ifBlank { anime.url }
                title = loaded.name.ifBlank { anime.title }
                thumbnail_url = loaded.posterUrl?.takeIf { it.isNotBlank() }
                    ?: loaded.backgroundPosterUrl?.takeIf { it.isNotBlank() }
                    ?: anime.thumbnail_url
                description = buildDescription(loaded)
                genre = loaded.tags
                    ?.map { it.trim() }
                    ?.filter { it.isNotBlank() }
                    ?.distinct()
                    ?.takeIf { it.isNotEmpty() }
                    ?.joinToString(", ")
                status = resolveShowStatus(loaded)
                author = loaded.actors
                    ?.mapNotNull { it.actor.name.takeIf(String::isNotBlank) }
                    ?.take(MAX_AUTHORS_TO_DISPLAY)
                    ?.takeIf { it.isNotEmpty() }
                    ?.joinToString(", ")
                initialized = true
            }
        }
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        return withIOContext {
            val loaded = loadDetailsCached(anime.url, forceRefresh = false)
                ?: return@withIOContext emptyList()

            when (loaded) {
                is TvSeriesLoadResponse -> mapEpisodesList(loaded.episodes, scanlatorLabel = null)
                is AnimeLoadResponse -> {
                    val multipleGroups = loaded.episodes.size > 1
                    val mapped = mutableListOf<SortableEpisode>()
                    loaded.episodes.forEach { (dubStatus, epList) ->
                        val label = if (multipleGroups) dubStatus.name else null
                        epList.forEachIndexed { idx, ep ->
                            mapped.add(ep.toSortableEpisode(idx + 1, label))
                        }
                    }
                    mapped
                        .distinctBy { it.episode.url }
                        .sortedWith(EPISODE_DESC_COMPARATOR)
                        .map { it.episode }
                }
                is MovieLoadResponse -> {
                    if (loaded.dataUrl.isBlank()) {
                        emptyList()
                    } else {
                        listOf(
                            SEpisode.create().apply {
                                url = loaded.dataUrl
                                name = loaded.name.ifBlank { "Movie" }
                                episode_number = 1f
                                date_upload = 0L
                            },
                        )
                    }
                }
                is LiveStreamLoadResponse -> {
                    if (loaded.dataUrl.isBlank()) {
                        emptyList()
                    } else {
                        listOf(
                            SEpisode.create().apply {
                                url = loaded.dataUrl
                                name = loaded.name.ifBlank { "Live" }
                                episode_number = 1f
                                date_upload = 0L
                            },
                        )
                    }
                }
                else -> emptyList()
            }
        }
    }

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        return withIOContext {
            val data = episode.url
            if (isInvalidLinkData(data)) return@withIOContext emptyList()

            val links = CopyOnWriteArrayList<ExtractorLink>()
            val subtitles = CopyOnWriteArrayList<SubtitleFile>()

            invokeLoadLinksWithFallbacks(
                data = data,
                onSubtitle = { subtitles.add(it) },
                onLink = { links.add(it) },
            )

            if (links.isEmpty()) return@withIOContext emptyList()

            val subtitleTracks = subtitles
                .filter { it.url.isNotBlank() }
                .distinctBy { it.url }
                .map { sub ->
                    Track(
                        url = sub.url,
                        lang = sub.lang.ifBlank { "Subtitle" },
                    )
                }

            val sortedLinks = links
                .filter { it.url.isNotBlank() }
                .distinctBy { "${it.url}|${it.referer}|${it.quality}" }
                .sortedByDescending { it.quality }

            val groupedBySource = sortedLinks.groupBy { link ->
                link.source.takeIf { it.isNotBlank() }
                    ?: link.name.takeIf { it.isNotBlank() }
                    ?: provider.name
            }

            groupedBySource.entries.map { (hosterName, hosterLinks) ->
                val videos = hosterLinks.mapIndexed { idx, link ->
                    link.toAniZenVideo(
                        subtitleTracks = subtitleTracks,
                        preferred = idx == 0,
                    )
                }
                Hoster(
                    hosterUrl = hosterLinks.first().url,
                    hosterName = hosterName,
                    videoList = videos,
                )
            }
        }
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        return hoster.videoList ?: emptyList()
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        return getHosterList(episode).flatMap { it.videoList.orEmpty() }
    }

    override fun getAnimeUrl(anime: SAnime): String {
        return anime.url.takeIf { it.startsWith("http", ignoreCase = true) } ?: baseUrl
    }

    override fun getEpisodeUrl(episode: SEpisode): String {
        return episode.url.takeIf { it.startsWith("http", ignoreCase = true) } ?: baseUrl
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

    private suspend fun loadMainPageSection(sectionIndex: Int, page: Int): AnimesPage {
        val section: MainPageData? = provider.mainPage.getOrNull(sectionIndex) ?: provider.mainPage.firstOrNull()
        val request = if (section != null) {
            MainPageRequest(
                name = section.name,
                data = section.data,
                horizontalImages = section.horizontalImages,
            )
        } else {
            if (!provider.hasMainPage) return AnimesPage(emptyList(), hasNextPage = false)
            MainPageRequest(name = provider.name, data = "", horizontalImages = false)
        }

        val response = runCatching {
            provider.getMainPage(page, request)
        }.onFailure {
            logcat(LogPriority.WARN, it) { "CloudStream getMainPage failed for ${provider.name} (page=$page)" }
        }.getOrNull() ?: return AnimesPage(emptyList(), hasNextPage = false)

        val items = response.items
            .flatMap { it.list }
            .distinctBy { it.url }
            .map { it.toSAnime() }

        return AnimesPage(
            animes = items,
            hasNextPage = response.hasNext && items.isNotEmpty(),
        )
    }

    private suspend fun loadDetailsCached(url: String, forceRefresh: Boolean): LoadResponse? {
        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            val cached = loadCache[url]
            if (cached != null && (now - cached.cachedAtMillis) < DETAILS_CACHE_TTL_MS) {
                return cached.response
            }
        }
        val loaded = runCatching {
            provider.load(url)
        }.onFailure {
            logcat(LogPriority.WARN, it) { "CloudStream load() failed for ${provider.name}: $url" }
        }.getOrNull()

        if (loaded != null) {
            if (loadCache.size > MAX_DETAILS_CACHE_SIZE) {
                loadCache.clear()
            }
            loadCache[url] = CachedLoad(loaded, now)
        }
        return loaded
    }

    private suspend fun invokeLoadLinksWithFallbacks(
        data: String,
        onSubtitle: (SubtitleFile) -> Unit,
        onLink: (ExtractorLink) -> Unit,
    ) {
        var foundAny = false
        val trackingLinkCallback: (ExtractorLink) -> Unit = { link ->
            foundAny = true
            onLink(link)
        }

        try {
            provider.loadLinks(
                data = data,
                isCasting = false,
                subtitleCallback = onSubtitle,
                callback = trackingLinkCallback,
            )
        } catch (e: Throwable) {
            val isJsonError = e.javaClass.name.contains("JsonParseException") ||
                e.message?.contains("Unrecognized token") == true ||
                e.message?.contains("was expecting") == true

            if (isJsonError && data.startsWith("http", ignoreCase = true)) {
                val wrapped = "[{\"source\":\"$data\"}]"
                runCatching {
                    provider.loadLinks(
                        data = wrapped,
                        isCasting = false,
                        subtitleCallback = onSubtitle,
                        callback = trackingLinkCallback,
                    )
                }.onFailure {
                    logcat(LogPriority.WARN, it) { "CloudStream wrapped loadLinks retry failed for ${provider.name}" }
                }
            } else {
                logcat(LogPriority.WARN, e) { "CloudStream loadLinks failed for ${provider.name}" }
            }
        }

        if (!foundAny && data.startsWith("http", ignoreCase = true) && !data.contains("[{") && !data.contains("{\"")) {
            val extractedData = runCatching {
                when (val res = provider.load(data)) {
                    is MovieLoadResponse -> res.dataUrl
                    is TvSeriesLoadResponse -> res.episodes.firstOrNull()?.data
                    is AnimeLoadResponse -> res.episodes.values.firstOrNull()?.firstOrNull()?.data
                    is LiveStreamLoadResponse -> res.dataUrl
                    else -> null
                }
            }.getOrNull()

            if (!extractedData.isNullOrBlank() && !isInvalidLinkData(extractedData) && extractedData != data) {
                runCatching {
                    provider.loadLinks(
                        data = extractedData,
                        isCasting = false,
                        subtitleCallback = onSubtitle,
                        callback = trackingLinkCallback,
                    )
                }.onFailure {
                    logcat(LogPriority.WARN, it) { "CloudStream smart fallback loadLinks failed for ${provider.name}" }
                }
            }

            if (!foundAny) {
                runCatching {
                    loadExtractor(data, provider.mainUrl, onSubtitle, trackingLinkCallback)
                }.onFailure {
                    logcat(LogPriority.WARN, it) { "CloudStream direct loadExtractor fallback failed for $data" }
                }
            }
        }
    }

    private fun ExtractorLink.toAniZenVideo(
        subtitleTracks: List<Track>,
        preferred: Boolean,
    ): Video {
        val mergedHeaders = buildStreamHeaders(
            rawHeaders = this.headers,
            linkReferer = this.referer,
            streamUrl = this.url,
        )
        val qLabel = formatQualityLabel(this.quality)
        val displayTitle = buildString {
            append(this@toAniZenVideo.name.ifBlank { this@toAniZenVideo.source.ifBlank { provider.name } })
            if (qLabel.isNotEmpty() && !this.contains(qLabel, ignoreCase = true)) {
                append(" - ")
                append(qLabel)
            }
        }
        val audioTrackList = this.audioTracks
            .filter { it.url.isNotBlank() }
            .mapIndexed { idx, audio ->
                Track(
                    url = audio.url,
                    lang = "Audio ${idx + 1}",
                )
            }

        return Video(
            videoUrl = this.url,
            videoTitle = displayTitle,
            headers = mergedHeaders,
            preferred = preferred,
            subtitleTracks = subtitleTracks,
            audioTracks = audioTrackList,
        )
    }

    private fun buildStreamHeaders(
        rawHeaders: Map<String, String>,
        linkReferer: String,
        streamUrl: String,
    ): Headers {
        val map = LinkedHashMap<String, String>()
        rawHeaders.forEach { (k, v) ->
            if (k.isNotBlank() && v.isNotBlank()) {
                map[k.trim()] = v.trim()
            }
        }

        if (map.keys.none { it.equals("Referer", ignoreCase = true) } && linkReferer.isNotBlank()) {
            map["Referer"] = linkReferer
        }

        val refererValue = map.entries.firstOrNull { it.key.equals("Referer", ignoreCase = true) }?.value
        if (!refererValue.isNullOrBlank() && map.keys.none { it.equals("Origin", ignoreCase = true) }) {
            runCatching {
                val uri = URI(refererValue)
                if (!uri.scheme.isNullOrBlank() && !uri.host.isNullOrBlank()) {
                    val portPart = if (uri.port != -1) ":${uri.port}" else ""
                    map["Origin"] = "${uri.scheme}://${uri.host}$portPart"
                }
            }
        }

        if (map.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            map["User-Agent"] = USER_AGENT
        }

        if (map.keys.none { it.equals("Cookie", ignoreCase = true) }) {
            val cookie = runCatching {
                val cm = CookieManager.getInstance()
                cm.getCookie(streamUrl)?.takeIf { it.isNotBlank() }
                    ?: refererValue?.let { cm.getCookie(it)?.takeIf(String::isNotBlank) }
            }.getOrNull()
            if (!cookie.isNullOrBlank()) {
                map["Cookie"] = cookie
            }
        }

        return Headers.Builder().apply {
            map.forEach { (key, value) ->
                runCatching { addUnsafeNonAscii(key, value) }
                    .onFailure { runCatching { add(key, value) } }
            }
        }.build()
    }

    private fun SearchResponse.toSAnime(): SAnime {
        return SAnime.create().apply {
            url = this@toSAnime.url
            title = this@toSAnime.name
            thumbnail_url = this@toSAnime.posterUrl
        }
    }

    private fun mapEpisodesList(episodes: List<Episode>, scanlatorLabel: String?): List<SEpisode> {
        return episodes
            .mapIndexed { idx, ep -> ep.toSortableEpisode(idx + 1, scanlatorLabel) }
            .distinctBy { it.episode.url }
            .sortedWith(EPISODE_DESC_COMPARATOR)
            .map { it.episode }
    }

    private fun Episode.toSortableEpisode(fallbackIndex: Int, scanlatorLabel: String?): SortableEpisode {
        val seasonNum = this.season ?: DEFAULT_SEASON
        val epNum = this.episode ?: fallbackIndex
        val titleText = buildString {
            if (this@toSortableEpisode.season != null && this@toSortableEpisode.season!! > 0) {
                append("S")
                append(this@toSortableEpisode.season)
                append(" ")
            }
            val cleanName = this@toSortableEpisode.name?.trim().orEmpty()
            if (cleanName.isNotBlank()) {
                if (this@toSortableEpisode.episode != null &&
                    !cleanName.contains(epNum.toString())
                ) {
                    append("E")
                    append(epNum)
                    append(" - ")
                }
                append(cleanName)
            } else {
                append("Episode ")
                append(epNum)
            }
        }

        val episode = SEpisode.create().apply {
            url = this@toSortableEpisode.data
            name = titleText
            episode_number = epNum.toFloat()
            date_upload = this@toSortableEpisode.date ?: 0L
            scanlator = scanlatorLabel
        }
        return SortableEpisode(
            episode = episode,
            seasonSort = seasonNum,
            episodeSort = epNum.toFloat(),
            orderIndex = fallbackIndex,
        )
    }

    private fun buildDescription(loaded: LoadResponse): String? {
        val parts = mutableListOf<String>()
        loaded.plot?.trim()?.takeIf { it.isNotBlank() }?.let { parts.add(it) }

        val metaLine = buildList {
            loaded.year?.let { add("Year: $it") }
            loaded.score?.toDouble(SCORE_SCALE)?.let {
                if (it > 0.0) add(String.format(Locale.US, "Rating: %.1f/10", it))
            }
            loaded.duration?.let { if (it > 0) add("${it}m") }
        }.joinToString(" • ")

        if (metaLine.isNotBlank()) {
            parts.add(metaLine)
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
    }

    private fun resolveShowStatus(loaded: LoadResponse): Int {
        val showStatus = when (loaded) {
            is AnimeLoadResponse -> loaded.showStatus
            is TvSeriesLoadResponse -> loaded.showStatus
            else -> null
        }
        return when (showStatus) {
            ShowStatus.Ongoing -> SAnime.ONGOING
            ShowStatus.Completed -> SAnime.COMPLETED
            null -> SAnime.UNKNOWN
        }
    }

    private data class SortableEpisode(
        val episode: SEpisode,
        val seasonSort: Int,
        val episodeSort: Float,
        val orderIndex: Int,
    )

    companion object {
        private const val FALLBACK_BASE_URL = "https://localhost"
        private const val DETAILS_CACHE_TTL_MS = 30_000L
        private const val MAX_DETAILS_CACHE_SIZE = 32
        private const val DEFAULT_SEASON = 1
        private const val MAX_AUTHORS_TO_DISPLAY = 5
        private const val SCORE_SCALE = 10
        private const val QUALITY_UNKNOWN = 400
        private const val QUALITY_4K = 2160
        private const val QUALITY_1080P = 1080
        private const val QUALITY_720P = 720
        private const val QUALITY_480P = 480
        private const val QUALITY_360P = 360

        private val EPISODE_DESC_COMPARATOR = compareByDescending<SortableEpisode> { it.seasonSort }
            .thenByDescending { it.episodeSort }
            .thenByDescending { it.orderIndex }

        private fun isInvalidLinkData(data: String): Boolean {
            val trimmed = data.trim()
            return trimmed.isEmpty() || trimmed == "[]" || trimmed == "about:blank"
        }

        private fun formatQualityLabel(quality: Int): String = when {
            quality <= 0 || quality == QUALITY_UNKNOWN -> ""
            quality >= QUALITY_4K -> "4K"
            quality >= QUALITY_1080P -> "1080p"
            quality >= QUALITY_720P -> "720p"
            quality >= QUALITY_480P -> "480p"
            quality >= QUALITY_360P -> "360p"
            else -> "${quality}p"
        }

        internal fun normalizeLangCode(raw: String?): String {
            val cleaned = raw?.trim()?.lowercase(Locale.ROOT).orEmpty()
            if (cleaned.isBlank()) return "all"
            return when {
                cleaned == "arabic" || cleaned.startsWith("ar") -> "ar"
                cleaned == "english" || cleaned.startsWith("en") -> "en"
                cleaned == "multi" || cleaned == "all" || cleaned == "universal" -> "all"
                cleaned.contains('-') -> cleaned.substringBefore('-')
                cleaned.contains('_') -> cleaned.substringBefore('_')
                else -> cleaned
            }
        }
    }
}
// ANZ <--
