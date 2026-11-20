package eu.kanade.tachiyomi.source

import android.content.Context
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.remoteserver.RemoteAnimeSource
import eu.kanade.tachiyomi.data.remoteserver.RemoteMetadataResolver
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.online.HttpSource
import exh.source.BlacklistedSources
import exh.source.DelegatedHttpSource
import exh.source.EnhancedHttpSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import tachiyomi.domain.remoteserver.interactor.GetRemoteServers
import tachiyomi.domain.remoteserver.model.toSourceId
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.repository.StubSourceRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.localanime.LocalAnimeSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.ConcurrentHashMap

class AndroidSourceManager(
    private val context: Context,
    private val extensionManager: ExtensionManager,
    private val sourceRepository: StubSourceRepository,
) : SourceManager {

    private val _isInitialized = MutableStateFlow(false)
    override val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private val downloadManager: DownloadManager by injectLazy()

    // ANZ -->
    private val getRemoteServers: GetRemoteServers by injectLazy()
    private val networkHelper: NetworkHelper by injectLazy()
    private val json: Json by injectLazy()
    private val remoteMetadataResolver by lazy {
        RemoteMetadataResolver(context, networkHelper, json)
    }
    // ANZ <--

    private val scope = CoroutineScope(Job() + Dispatchers.IO)

    private val sourcesMapFlow = MutableStateFlow(ConcurrentHashMap<Long, Source>())

    private val stubSourcesMap = ConcurrentHashMap<Long, StubSource>()

    override val catalogueSources: Flow<List<CatalogueSource>> = combine(
        sourcesMapFlow,
        isInitialized,
    ) { sources, initialized ->
        sources to initialized
    }
        .filter { it.second }
        .map { it.first.values.filterIsInstance<CatalogueSource>() }

    init {
        scope.launch {
            combine(
                extensionManager.installedExtensionsFlow,
                extensionManager.isInitialized,
                // ANZ -->
                getRemoteServers.subscribeAll(),
                // ANZ <--
            ) { extensions, isExtensionManagerInitialized, remoteServers ->
                Triple(extensions, isExtensionManagerInitialized, remoteServers)
            }.collectLatest { (extensions, isExtensionManagerInitialized, remoteServers) ->
                if (!isExtensionManagerInitialized) return@collectLatest

                val previousMap = sourcesMapFlow.value
                val mutableMap = ConcurrentHashMap<Long, Source>(
                    mapOf(
                        LocalAnimeSource.ID to LocalAnimeSource(
                            context,
                            Injekt.get(),
                            Injekt.get(),
                            Injekt.get(),
                        ),
                    ),
                )
                extensions.forEach { extension: eu.kanade.tachiyomi.extension.model.Extension.Installed ->
                    extension.sources.forEach {
                        mutableMap[it.id] = it
                        registerStubSource(StubSource.from(it))
                    }
                }
                // ANZ -->
                remoteServers.forEach { server ->
                    val existing = previousMap[server.toSourceId()] as? RemoteAnimeSource
                    val remoteSource = if (existing != null && existing.server == server) {
                        existing
                    } else {
                        RemoteAnimeSource(
                            server = server,
                            context = context,
                            metadataResolver = remoteMetadataResolver,
                            json = json,
                        )
                    }
                    mutableMap[remoteSource.id] = remoteSource
                    registerStubSource(StubSource.from(remoteSource))
                }
                // ANZ <--
                sourcesMapFlow.value = mutableMap
                _isInitialized.value = true
            }
        }

        scope.launch {
            sourceRepository.subscribeAll()
                .collectLatest { sources ->
                    val mutableMap = stubSourcesMap.toMutableMap()
                    sources.forEach {
                        mutableMap[it.id] = it
                    }
                }
        }
    }

    override fun get(sourceKey: Long): Source? {
        return sourcesMapFlow.value[sourceKey]
    }

    override fun getOrStub(sourceKey: Long): Source {
        return sourcesMapFlow.value[sourceKey] ?: stubSourcesMap.getOrPut(sourceKey) {
            // Return empty stub immediately to avoid UI thread lag
            scope.launch {
                createStubSource(sourceKey)
            }
            StubSource(id = sourceKey, lang = "", name = "")
        }
    }

    override fun getOnlineSources(): List<HttpSource> {
        return sourcesMapFlow.value.values.filterIsInstance<HttpSource>()
    }

    override fun getCatalogueSources(): List<CatalogueSource> {
        return sourcesMapFlow.value.values.filterIsInstance<CatalogueSource>()
    }

    override fun getStubSources(): List<StubSource> {
        val onlineSourceIds = getOnlineSources().map { it.id }
        return stubSourcesMap.values.filterNot { it.id in onlineSourceIds }
    }

    // SY -->
    override fun getVisibleOnlineSources(): List<HttpSource> {
        return sourcesMapFlow.value.values
            .filterIsInstance<HttpSource>()
            .filter {
                it.id !in BlacklistedSources.HIDDEN_SOURCES
            }
    }

    override fun getVisibleCatalogueSources(): List<CatalogueSource> {
        return sourcesMapFlow.value.values
            .filterIsInstance<CatalogueSource>()
            .filter {
                it.id !in BlacklistedSources.HIDDEN_SOURCES
            }
    }

    fun getDelegatedCatalogueSources(): List<DelegatedHttpSource> {
        return sourcesMapFlow.value.values
            .filterIsInstance<EnhancedHttpSource>()
            .mapNotNull { enhancedHttpSource ->
                enhancedHttpSource.enhancedSource as? DelegatedHttpSource
            }
    }
    // SY <--

    private fun registerStubSource(source: StubSource) {
        scope.launch {
            val dbSource = sourceRepository.getStubSource(source.id)
            if (dbSource == source) return@launch
            sourceRepository.upsertStubSource(source.id, source.lang, source.name)
            if (dbSource != null) {
                downloadManager.renameSource(dbSource, source)
            }
        }
    }

    private suspend fun createStubSource(id: Long): StubSource {
        sourceRepository.getStubSource(id)?.let {
            return it
        }
        extensionManager.getSourceData(id)?.let {
            registerStubSource(it)
            return it
        }
        return StubSource(id = id, lang = "", name = "")
    }
}
