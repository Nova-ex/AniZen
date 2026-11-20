// ANZ -->
package eu.kanade.tachiyomi.data.cloudstream

import android.content.Context
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.plugins.RepositoryManager
import com.lagradost.cloudstream3.plugins.SitePlugin
import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData
import com.lagradost.cloudstream3.utils.DataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class CloudStreamManager(
    private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initialized = AtomicBoolean(false)

    private val _sourcesFlow = MutableStateFlow<List<CloudStreamAnimeSource>>(emptyList())
    val sourcesFlow: StateFlow<List<CloudStreamAnimeSource>> = _sourcesFlow.asStateFlow()

    private val _reposFlow = MutableStateFlow<List<RepositoryData>>(emptyList())
    val reposFlow: StateFlow<List<RepositoryData>> = _reposFlow.asStateFlow()

    private val _installedPluginsFlow = MutableStateFlow<List<PluginManager.PluginData>>(emptyList())
    val installedPluginsFlow: StateFlow<List<PluginManager.PluginData>> = _installedPluginsFlow.asStateFlow()

    private val sourceIconCache = ConcurrentHashMap<Long, String>()
    private val sourcePluginNameCache = ConcurrentHashMap<Long, String>()

    init {
        initRuntime()
        scope.launch {
            PluginManager.pluginsChangedFlow.collect {
                refreshStateFromRuntime()
            }
        }
        scope.launch {
            RepositoryManager.reposChangedFlow.collect {
                _reposFlow.value = RepositoryManager.getRepositories().toList()
            }
        }
    }

    fun initRuntime() {
        CloudStreamApp.context = context.applicationContext
        MainActivity.context = context.applicationContext
        DataStore.init(context.applicationContext)
    }

    suspend fun loadInstalledPluginsOnStartup() {
        if (!initialized.compareAndSet(false, true)) return
        withIOContext {
            initRuntime()
            _reposFlow.value = RepositoryManager.getRepositories().toList()
            runCatching {
                PluginManager.loadAllPlugins(context)
            }.onFailure {
                logcat(LogPriority.ERROR, it) { "Failed loading CloudStream .cs3 plugins on startup" }
            }
            refreshStateFromRuntime()
        }
    }

    fun refreshStateFromRuntime() {
        val installed = PluginManager.getPluginsOnline().toList()
        _installedPluginsFlow.value = installed
        _reposFlow.value = RepositoryManager.getRepositories().toList()

        val pluginByPath = installed.associateBy { it.filePath }
        val activeProviders = synchronized(APIHolder.allProviders) {
            APIHolder.allProviders.toList()
        }

        val mappedSources = activeProviders
            .distinctBy { "${it.name}|${it.lang}|${it.sourcePlugin}" }
            .map { provider ->
                val meta = provider.sourcePlugin?.let { pluginByPath[it] }
                val source = CloudStreamAnimeSource(
                    provider = provider,
                    iconUrl = meta?.iconUrl,
                    pluginInternalName = meta?.internalName,
                )
                meta?.iconUrl?.takeIf { it.isNotBlank() }?.let { icon ->
                    sourceIconCache[source.id] = icon
                }
                meta?.name?.takeIf { it.isNotBlank() }?.let { pluginName ->
                    sourcePluginNameCache[source.id] = pluginName
                }
                source
            }
            .distinctBy { it.id }

        _sourcesFlow.value = mappedSources
    }

    suspend fun addRepository(rawUrl: String): Result<RepositoryData> = withIOContext {
        runCatching {
            initRuntime()
            val resolvedUrl = RepositoryManager.parseRepoUrl(rawUrl)
                ?: error("Invalid repository URL")
            val parsed = RepositoryManager.parseRepository(resolvedUrl)
                ?: error("Could not read repository JSON from URL")
            val repoData = RepositoryData(
                iconUrl = parsed.iconUrl,
                name = parsed.name.ifBlank { resolvedUrl },
                url = resolvedUrl,
            )
            RepositoryManager.addRepository(repoData)
            _reposFlow.value = RepositoryManager.getRepositories().toList()
            repoData
        }
    }

    suspend fun removeRepository(repoUrl: String) = withIOContext {
        initRuntime()
        RepositoryManager.removeRepository(context, repoUrl)
        refreshStateFromRuntime()
    }

    suspend fun fetchRepoPlugins(repoUrl: String): Result<List<SitePlugin>> = withIOContext {
        runCatching {
            initRuntime()
            RepositoryManager.getRepoPlugins(repoUrl)?.map { it.second }
                ?: error("Failed to fetch plugin list from repository")
        }
    }

    suspend fun installOrUpdatePlugin(plugin: SitePlugin): Result<PluginManager.PluginData> = withIOContext {
        initRuntime()
        val result = PluginManager.downloadAndInstallPlugin(context, plugin)
        refreshStateFromRuntime()
        result
    }

    suspend fun uninstallPlugin(filePath: String): Boolean = withIOContext {
        initRuntime()
        val removed = PluginManager.deletePlugin(filePath)
        refreshStateFromRuntime()
        removed
    }

    fun getIconUrlForSource(sourceId: Long): String? = sourceIconCache[sourceId]

    fun getPluginNameForSource(sourceId: Long): String? = sourcePluginNameCache[sourceId]
}
// ANZ <--
