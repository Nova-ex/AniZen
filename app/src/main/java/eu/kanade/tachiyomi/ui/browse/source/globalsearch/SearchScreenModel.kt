package eu.kanade.tachiyomi.ui.browse.source.globalsearch

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.produceState
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.util.ioCoroutineScope
import eu.kanade.tachiyomi.data.cloudstream.CloudStreamAnimeSource
import eu.kanade.tachiyomi.data.cloudstream.CloudStreamManager
import eu.kanade.tachiyomi.data.cloudstream.isCloudStreamSourceId
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.mutate
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import tachiyomi.core.common.preference.toggle
import tachiyomi.domain.anime.interactor.GetAnime
import tachiyomi.domain.anime.interactor.NetworkToLocalAnime
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.anime.model.toDomainAnime
import tachiyomi.domain.remoteserver.model.isRemoteServerSourceId
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

abstract class SearchScreenModel(
    initialState: State = State(),
    private val sourcePreferences: SourcePreferences = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val extensionManager: ExtensionManager = Injekt.get(),
    private val networkToLocalAnime: NetworkToLocalAnime = Injekt.get(),
    private val getAnime: GetAnime = Injekt.get(),
    private val preferences: SourcePreferences = Injekt.get(),
    // ANZ -->
    private val cloudStreamManager: CloudStreamManager = Injekt.get(),
    // ANZ <--
) : StateScreenModel<SearchScreenModel.State>(initialState) {

    // Bounded parallelism without leaking a fixed thread pool:
    // the previous newFixedThreadPool(5) was never shut down.
    private val coroutineDispatcher = Dispatchers.IO.limitedParallelism(5)
    private var searchJob: Job? = null

    // ANZ -->
    private val enabledLanguages: Set<String>
        get() = sourcePreferences.enabledLanguages().get()
    private val disabledSources: Set<String>
        get() = sourcePreferences.disabledSources().get()
    protected val pinnedSources: Set<String>
        get() = sourcePreferences.pinnedSources().get()
    // ANZ <--

    private var lastQuery: String? = null
    private var lastSourceFilter: SourceFilter? = null

    protected var extensionFilter: String? = null

    private val sortComparator = { map: Map<CatalogueSource, SearchItemResult> ->
        compareBy<CatalogueSource>(
            { (map[it] as? SearchItemResult.Success)?.isEmpty ?: true },
            { "${it.id}" !in pinnedSources },
            { "${it.name.lowercase()} (${it.lang})" },
        )
    }

    init {
        screenModelScope.launch {
            preferences.globalSearchFilterState().changes().collectLatest { state ->
                mutableState.update { it.copy(onlyShowHasResults = state) }
            }
        }
    }

    @Composable
    fun getAnime(initialAnime: Anime): androidx.compose.runtime.State<Anime> {
        return produceState(initialValue = initialAnime) {
            getAnime.subscribe(initialAnime.url, initialAnime.source)
                .filterNotNull()
                .collectLatest { anime ->
                    value = anime
                }
        }
    }

    open fun getEnabledSources(): List<CatalogueSource> {
        // ANZ -->
        val activeLanguages = enabledLanguages
        val disabled = disabledSources
        val pinned = pinnedSources
        return sourceManager.getCatalogueSources()
            .filter { source ->
                (
                    source.lang in activeLanguages ||
                        source.id.isCloudStreamSourceId() ||
                        source.id.isRemoteServerSourceId()
                    ) && "${source.id}" !in disabled
            }
            .sortedWith(
                compareBy(
                    { "${it.id}" !in pinned },
                    { "${it.name.lowercase()} (${it.lang})" },
                ),
            )
        // ANZ <--
    }

    private fun getSelectedSources(): List<CatalogueSource> {
        val enabledSources = getEnabledSources()

        val filter = extensionFilter
        if (filter.isNullOrEmpty()) {
            return enabledSources
        }

        // ANZ -->
        val extensionSources = extensionManager.installedExtensionsFlow.value
            .filter { it: eu.kanade.tachiyomi.extension.model.Extension.Installed -> it.pkgName == filter }
            .flatMap { it: eu.kanade.tachiyomi.extension.model.Extension.Installed -> it.sources }
            .filterIsInstance<CatalogueSource>()
            .filter { it in enabledSources }

        val cloudStreamMatches = enabledSources
            .filterIsInstance<CloudStreamAnimeSource>()
            .filter { source ->
                source.pluginPackageName.equals(filter, ignoreCase = true) ||
                    source.pluginInternalName?.equals(filter, ignoreCase = true) == true ||
                    source.id.toString() == filter
            }

        return (extensionSources + cloudStreamMatches).distinctBy { it.id }
        // ANZ <--
    }

    fun updateSearchQuery(query: String?) {
        mutableState.update { it.copy(searchQuery = query) }
    }

    fun setSourceFilter(filter: SourceFilter) {
        mutableState.update { it.copy(sourceFilter = filter) }
        search()
    }

    fun toggleFilterResults() {
        preferences.globalSearchFilterState().toggle()
    }

    fun search() {
        val query = state.value.searchQuery
        val sourceFilter = state.value.actualSourceFilter

        if (query.isNullOrBlank()) return
        val sameQuery = this.lastQuery == query
        if (sameQuery && this.lastSourceFilter == sourceFilter) return

        this.lastQuery = query
        this.lastSourceFilter = sourceFilter

        searchJob?.cancel()
        searchJob = ioCoroutineScope.launch {
            // ANZ -->
            if (!cloudStreamManager.isInitialized.value) {
                withTimeoutOrNull(10_000L) {
                    cloudStreamManager.awaitInitialized()
                }
            }
            if (!sourceManager.isInitialized.value) {
                withTimeoutOrNull(10_000L) {
                    sourceManager.isInitialized.first { it }
                }
            }
            // ANZ <--

            val sources = getSelectedSources()

            // Reuse previous results if possible
            if (sameQuery) {
                val existingResults = state.value.items
                updateItems(
                    sources
                        .associateWith { existingResults[it] ?: SearchItemResult.Loading }
                        .toPersistentMap(),
                )
            } else {
                updateItems(
                    sources
                        .associateWith { SearchItemResult.Loading }
                        .toPersistentMap(),
                )
            }

            sources.map { source ->
                async {
                    if (state.value.items[source] !is SearchItemResult.Loading) {
                        return@async
                    }
                    try {
                        val page = withContext(coroutineDispatcher) {
                            source.getSearchAnime(1, query, FilterList())
                        }

                        // ANZ -->
                        // Some sources list the same title twice on one page. Each entry then resolves
                        // to the same local anime id, and the global search card row keys its LazyRow by
                        // that id, so a duplicate crashes the app with
                        // IllegalArgumentException: Key "gs-<id>" was already used.
                        // Keep the first occurrence of each id. (FeedScreenModel de-duplicates the same way.)
                        val titles = page.animes
                            .map { networkToLocalAnime.await(it.toDomainAnime(source.id)) }
                            .distinctBy { it.id }
                        // ANZ <--

                        if (isActive) {
                            updateItem(source, SearchItemResult.Success(titles))
                        }
                    } catch (e: Exception) {
                        if (isActive) {
                            updateItem(source, SearchItemResult.Error(e))
                        }
                    }
                }
            }
                .awaitAll()
        }
    }

    private fun updateItems(items: PersistentMap<CatalogueSource, SearchItemResult>) {
        mutableState.update {
            it.copy(
                items = items
                    .toSortedMap(sortComparator(items))
                    .toPersistentMap(),
            )
        }
    }

    private fun updateItem(source: CatalogueSource, result: SearchItemResult) {
        val newItems = state.value.items.mutate {
            it[source] = result
        }
        updateItems(newItems)
    }

    @Immutable
    data class State(
        val fromSourceId: Long? = null,
        val searchQuery: String? = null,
        val sourceFilter: SourceFilter? = null,
        val onlyShowHasResults: Boolean = false,
        val items: PersistentMap<CatalogueSource, SearchItemResult> = persistentMapOf(),
    ) {
        val actualSourceFilter: SourceFilter
            get() = sourceFilter ?: if (Injekt.get<SourcePreferences>().pinnedSources().get().isEmpty()) {
                SourceFilter.All
            } else {
                SourceFilter.PinnedOnly
            }

        val progress: Int = items.count { it.value !is SearchItemResult.Loading }
        val total: Int = items.size
        val filteredItems = items.filter { (_, result) -> result.isVisible(onlyShowHasResults) }
    }
}

enum class SourceFilter {
    All,
    PinnedOnly,
}

sealed interface SearchItemResult {
    data object Loading : SearchItemResult

    data class Error(
        val throwable: Throwable,
    ) : SearchItemResult

    data class Success(
        val result: List<Anime>,
    ) : SearchItemResult {
        val isEmpty: Boolean
            get() = result.isEmpty()
    }

    fun isVisible(onlyShowHasResults: Boolean): Boolean {
        return !onlyShowHasResults || (this is Success && !this.isEmpty)
    }
}
