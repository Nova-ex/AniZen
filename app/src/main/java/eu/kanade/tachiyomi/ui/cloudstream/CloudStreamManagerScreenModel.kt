// ANZ -->
package eu.kanade.tachiyomi.ui.cloudstream

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.plugins.SitePlugin
import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData
import eu.kanade.tachiyomi.data.cloudstream.CloudStreamAnimeSource
import eu.kanade.tachiyomi.data.cloudstream.CloudStreamManager
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.core.common.util.lang.launchIO
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Locale

class CloudStreamManagerScreenModel(
    private val cloudStreamManager: CloudStreamManager = Injekt.get(),
) : StateScreenModel<CloudStreamManagerScreenModel.State>(State()) {

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    init {
        screenModelScope.launch {
            combine(
                cloudStreamManager.reposFlow,
                cloudStreamManager.installedPluginsFlow,
            ) { repos, installed ->
                repos to installed
            }.collect { (repos, installed) ->
                mutableState.update { current ->
                    current.copy(
                        repositories = repos,
                        installedPlugins = installed,
                    )
                }
            }
        }

        screenModelScope.launchIO {
            cloudStreamManager.loadInstalledPluginsOnStartup()
            refreshRepositories()
        }
    }

    fun consumeSnackbar() {
        _snackbarMessage.value = null
    }

    fun selectTab(tab: Tab) {
        mutableState.update { it.copy(selectedTab = tab) }
    }

    fun updateSearchQuery(query: String) {
        mutableState.update { it.copy(searchQuery = query) }
    }

    fun selectPluginFilter(filter: PluginFilter) {
        mutableState.update { it.copy(pluginFilter = filter) }
    }

    fun selectLanguageFilter(lang: String?) {
        mutableState.update { it.copy(selectedLanguage = lang) }
    }

    fun openAddRepoDialog() {
        mutableState.update { it.copy(showAddRepoDialog = true, addRepoError = null) }
    }

    fun dismissAddRepoDialog() {
        mutableState.update { it.copy(showAddRepoDialog = false, isAddingRepo = false, addRepoError = null) }
    }

    fun requestDeleteRepo(repo: RepositoryData) {
        mutableState.update { it.copy(repoPendingDelete = repo) }
    }

    fun dismissDeleteRepo() {
        mutableState.update { it.copy(repoPendingDelete = null) }
    }

    fun addRepository(rawUrl: String) {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank()) {
            mutableState.update { it.copy(addRepoError = "Please enter a repository URL") }
            return
        }

        mutableState.update { it.copy(isAddingRepo = true, addRepoError = null) }
        screenModelScope.launchIO {
            val result = cloudStreamManager.addRepository(trimmed)
            result.fold(
                onSuccess = { repo ->
                    mutableState.update {
                        it.copy(
                            showAddRepoDialog = false,
                            isAddingRepo = false,
                            addRepoError = null,
                        )
                    }
                    _snackbarMessage.value = "Added repository: ${repo.name}"
                    refreshRepositories()
                },
                onFailure = { error ->
                    mutableState.update {
                        it.copy(
                            isAddingRepo = false,
                            addRepoError = error.message ?: "Failed to add repository",
                        )
                    }
                },
            )
        }
    }

    fun deleteRepository(repo: RepositoryData) {
        mutableState.update { it.copy(repoPendingDelete = null) }
        screenModelScope.launchIO {
            cloudStreamManager.removeRepository(repo.url)
            mutableState.update { current ->
                current.copy(
                    repoPluginCounts = current.repoPluginCounts - repo.url,
                    availablePlugins = current.availablePlugins.filterNot { it.repositoryUrl == repo.url },
                )
            }
            _snackbarMessage.value = "Removed repository: ${repo.name}"
        }
    }

    fun refreshRepositories() {
        screenModelScope.launchIO {
            val repos = cloudStreamManager.reposFlow.value
            if (repos.isEmpty()) {
                mutableState.update {
                    it.copy(
                        isRefreshing = false,
                        availablePlugins = emptyList(),
                        repoPluginCounts = emptyMap(),
                    )
                }
                return@launchIO
            }

            mutableState.update { it.copy(isRefreshing = true) }
            val fetched = coroutineScope {
                repos.map { repo ->
                    async {
                        val plugins = cloudStreamManager.fetchRepoPlugins(repo.url).getOrElse { emptyList() }
                        repo to plugins
                    }
                }.awaitAll()
            }

            val counts = fetched.associate { (repo, plugins) -> repo.url to plugins.size }
            val allPlugins = fetched
                .flatMap { (repo, plugins) ->
                    plugins.map { plugin ->
                        AvailablePluginItem(
                            sitePlugin = plugin,
                            repositoryName = repo.name,
                            repositoryUrl = repo.url,
                            normalizedLang = CloudStreamAnimeSource.normalizeLangCode(plugin.language),
                        )
                    }
                }
                .distinctBy { "${it.repositoryUrl}|${it.sitePlugin.internalName}" }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.sitePlugin.name })

            mutableState.update {
                it.copy(
                    isRefreshing = false,
                    availablePlugins = allPlugins,
                    repoPluginCounts = counts,
                )
            }
        }
    }

    fun installOrUpdatePlugin(plugin: SitePlugin, isUpdate: Boolean) {
        val key = pluginBusyKey(plugin.repositoryUrl, plugin.internalName)
        mutableState.update { it.copy(busyPluginKeys = it.busyPluginKeys + key) }
        screenModelScope.launchIO {
            val result = cloudStreamManager.installOrUpdatePlugin(plugin)
            mutableState.update { it.copy(busyPluginKeys = it.busyPluginKeys - key) }
            result.fold(
                onSuccess = {
                    _snackbarMessage.value = if (isUpdate) {
                        "Updated ${plugin.name}"
                    } else {
                        "Installed ${plugin.name}"
                    }
                },
                onFailure = { error ->
                    _snackbarMessage.value = error.message ?: "Failed to install ${plugin.name}"
                },
            )
        }
    }

    fun updateAllPlugins() {
        val updates = state.value.updatablePlugins
        if (updates.isEmpty()) return
        screenModelScope.launchIO {
            var updatedCount = 0
            for (item in updates) {
                val key = pluginBusyKey(item.repositoryUrl, item.sitePlugin.internalName)
                mutableState.update { it.copy(busyPluginKeys = it.busyPluginKeys + key) }
                val res = cloudStreamManager.installOrUpdatePlugin(item.sitePlugin)
                mutableState.update { it.copy(busyPluginKeys = it.busyPluginKeys - key) }
                if (res.isSuccess) {
                    updatedCount++
                }
            }
            _snackbarMessage.value = "Updated $updatedCount extension(s)"
        }
    }

    fun uninstallPlugin(installed: PluginManager.PluginData) {
        val key = pluginBusyKey(installed.repositoryUrl, installed.internalName)
        mutableState.update { it.copy(busyPluginKeys = it.busyPluginKeys + key) }
        screenModelScope.launchIO {
            val removed = cloudStreamManager.uninstallPlugin(installed.filePath)
            mutableState.update { it.copy(busyPluginKeys = it.busyPluginKeys - key) }
            _snackbarMessage.value = if (removed) {
                "Uninstalled ${installed.name}"
            } else {
                "Failed to uninstall ${installed.name}"
            }
        }
    }

    enum class Tab {
        EXTENSIONS,
        REPOSITORIES,
    }

    enum class PluginFilter {
        ALL,
        INSTALLED,
        UPDATES,
    }

    @Immutable
    data class AvailablePluginItem(
        val sitePlugin: SitePlugin,
        val repositoryName: String,
        val repositoryUrl: String,
        val normalizedLang: String,
    )

    @Immutable
    data class PluginDisplayRow(
        val internalName: String,
        val name: String,
        val description: String?,
        val iconUrl: String?,
        val language: String,
        val repositoryName: String,
        val repositoryUrl: String?,
        val availablePlugin: SitePlugin?,
        val installedPlugin: PluginManager.PluginData?,
        val busyKey: String,
    ) {
        val isInstalled: Boolean get() = installedPlugin != null
        val hasUpdate: Boolean
            get() = installedPlugin != null &&
                availablePlugin != null &&
                availablePlugin.version > installedPlugin.version
        val displayVersion: String
            get() = when {
                hasUpdate -> "v${installedPlugin!!.version} → v${availablePlugin!!.version}"
                installedPlugin != null -> "v${installedPlugin.version}"
                availablePlugin != null -> "v${availablePlugin.version}"
                else -> ""
            }
    }

    @Immutable
    data class State(
        val selectedTab: Tab = Tab.EXTENSIONS,
        val repositories: List<RepositoryData> = emptyList(),
        val installedPlugins: List<PluginManager.PluginData> = emptyList(),
        val availablePlugins: List<AvailablePluginItem> = emptyList(),
        val repoPluginCounts: Map<String, Int> = emptyMap(),
        val searchQuery: String = "",
        val pluginFilter: PluginFilter = PluginFilter.ALL,
        val selectedLanguage: String? = null,
        val isRefreshing: Boolean = false,
        val busyPluginKeys: Set<String> = emptySet(),
        val showAddRepoDialog: Boolean = false,
        val isAddingRepo: Boolean = false,
        val addRepoError: String? = null,
        val repoPendingDelete: RepositoryData? = null,
    ) {
        val allRows: List<PluginDisplayRow>
            get() {
                val installedByKey = installedPlugins.associateBy {
                    "${it.repositoryUrl.orEmpty()}|${it.internalName}"
                }
                val installedByName = installedPlugins.associateBy { it.internalName }
                val matchedInstalledPaths = mutableSetOf<String>()

                val rows = mutableListOf<PluginDisplayRow>()
                availablePlugins.forEach { item ->
                    val installed = installedByKey["${item.repositoryUrl}|${item.sitePlugin.internalName}"]
                        ?: installedByName[item.sitePlugin.internalName]
                    if (installed != null) {
                        matchedInstalledPaths.add(installed.filePath)
                    }
                    rows.add(
                        PluginDisplayRow(
                            internalName = item.sitePlugin.internalName,
                            name = item.sitePlugin.name,
                            description = item.sitePlugin.description,
                            iconUrl = item.sitePlugin.iconUrl,
                            language = item.normalizedLang,
                            repositoryName = item.repositoryName,
                            repositoryUrl = item.repositoryUrl,
                            availablePlugin = item.sitePlugin,
                            installedPlugin = installed,
                            busyKey = pluginBusyKey(item.repositoryUrl, item.sitePlugin.internalName),
                        ),
                    )
                }

                // Include any installed plugin whose repository is not currently fetched
                installedPlugins
                    .filterNot { it.filePath in matchedInstalledPaths }
                    .forEach { installed ->
                        rows.add(
                            PluginDisplayRow(
                                internalName = installed.internalName,
                                name = installed.name,
                                description = installed.description,
                                iconUrl = installed.iconUrl,
                                language = CloudStreamAnimeSource.normalizeLangCode(installed.language),
                                repositoryName = repositories.firstOrNull { it.url == installed.repositoryUrl }?.name
                                    ?: "Installed",
                                repositoryUrl = installed.repositoryUrl,
                                availablePlugin = null,
                                installedPlugin = installed,
                                busyKey = pluginBusyKey(installed.repositoryUrl, installed.internalName),
                            ),
                        )
                    }

                return rows.sortedWith(
                    compareByDescending<PluginDisplayRow> { it.hasUpdate }
                        .thenByDescending { it.isInstalled }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
                )
            }

        val updatablePlugins: List<AvailablePluginItem>
            get() {
                val installedByName = installedPlugins.associateBy { it.internalName }
                return availablePlugins.filter { item ->
                    val installed = installedByName[item.sitePlugin.internalName]
                    installed != null && item.sitePlugin.version > installed.version
                }
            }

        val availableLanguages: List<String>
            get() = allRows
                .map { it.language.lowercase(Locale.ROOT) }
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()

        val filteredRows: List<PluginDisplayRow>
            get() {
                val query = searchQuery.trim()
                return allRows.filter { row ->
                    val matchesFilter = when (pluginFilter) {
                        PluginFilter.ALL -> true
                        PluginFilter.INSTALLED -> row.isInstalled
                        PluginFilter.UPDATES -> row.hasUpdate
                    }
                    val matchesLang = selectedLanguage == null ||
                        row.language.equals(selectedLanguage, ignoreCase = true)
                    val matchesQuery = query.isBlank() ||
                        row.name.contains(query, ignoreCase = true) ||
                        row.internalName.contains(query, ignoreCase = true) ||
                        row.repositoryName.contains(query, ignoreCase = true) ||
                        (row.description?.contains(query, ignoreCase = true) == true)
                    matchesFilter && matchesLang && matchesQuery
                }
            }
    }

    companion object {
        fun pluginBusyKey(repositoryUrl: String?, internalName: String): String {
            return "${repositoryUrl.orEmpty()}|$internalName"
        }
    }
}
// ANZ <--
