package eu.kanade.tachiyomi.ui.remoteserver

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.data.remoteserver.RemoteDownloadManager
import eu.kanade.tachiyomi.data.remoteserver.RemoteFileItem
import eu.kanade.tachiyomi.data.remoteserver.RemoteServerClient
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.remoteserver.interactor.GetRemotePlaybackHistory
import tachiyomi.domain.remoteserver.interactor.GetRemoteServers
import tachiyomi.domain.remoteserver.interactor.UpsertRemotePlaybackHistory
import tachiyomi.domain.remoteserver.model.RemotePlaybackHistory
import tachiyomi.domain.remoteserver.model.RemoteServer
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

// ANZ -->
class RemoteBrowserScreenModel(
    private val serverId: Long,
    private val getRemoteServers: GetRemoteServers = Injekt.get(),
    private val getRemotePlaybackHistory: GetRemotePlaybackHistory = Injekt.get(),
    private val upsertRemotePlaybackHistory: UpsertRemotePlaybackHistory = Injekt.get(),
    private val remoteDownloadManager: RemoteDownloadManager = Injekt.get(),
) : ScreenModel {

    @Immutable
    data class Breadcrumb(
        val label: String,
        val path: String,
    )

    @Immutable
    data class State(
        val server: RemoteServer? = null,
        val currentPath: String = "/",
        val initialPath: String = "/",
        val allItems: List<RemoteFileItem> = emptyList(),
        val visibleItems: List<RemoteFileItem> = emptyList(),
        val breadcrumbs: List<Breadcrumb> = listOf(Breadcrumb("/", "/")),
        val historyByPath: Map<String, RemotePlaybackHistory> = emptyMap(),
        val downloadsByTaskId: Map<String, RemoteDownloadManager.RemoteDownloadTask> = emptyMap(),
        val allDownloads: List<RemoteDownloadManager.RemoteDownloadTask> = emptyList(),
        val videosOnlyFilter: Boolean = false,
        val isLoading: Boolean = true,
        val isRefreshing: Boolean = false,
        val errorMessage: String? = null,
        val selectedItemForSheet: RemoteFileItem? = null,
        val showDownloadsSheet: Boolean = false,
    ) {
        val canNavigateUp: Boolean
            get() = currentPath != initialPath && currentPath != "/"
    }

    private data class BrowserUiState(
        val server: RemoteServer? = null,
        val currentPath: String = "/",
        val initialPath: String = "/",
        val items: List<RemoteFileItem> = emptyList(),
        val videosOnlyFilter: Boolean = false,
        val isLoading: Boolean = true,
        val isRefreshing: Boolean = false,
        val errorMessage: String? = null,
        val selectedItemForSheet: RemoteFileItem? = null,
        val showDownloadsSheet: Boolean = false,
    )

    private val uiState = MutableStateFlow(BrowserUiState())
    private var loadJob: Job? = null

    val state: StateFlow<State> = combine(
        uiState,
        getRemotePlaybackHistory.subscribeByServerId(serverId),
        remoteDownloadManager.tasks,
    ) { ui, historyList, downloads ->
        val filtered = if (ui.videosOnlyFilter) {
            ui.items.filter { it.isDirectory || it.isVideo || it.isSubtitle }
        } else {
            ui.items
        }
        State(
            server = ui.server,
            currentPath = ui.currentPath,
            initialPath = ui.initialPath,
            allItems = ui.items,
            visibleItems = filtered,
            breadcrumbs = buildBreadcrumbs(ui.currentPath),
            historyByPath = historyList.associateBy { it.remoteFilePath },
            downloadsByTaskId = downloads.associateBy { it.id },
            allDownloads = downloads,
            videosOnlyFilter = ui.videosOnlyFilter,
            isLoading = ui.isLoading,
            isRefreshing = ui.isRefreshing,
            errorMessage = ui.errorMessage,
            selectedItemForSheet = ui.selectedItemForSheet,
            showDownloadsSheet = ui.showDownloadsSheet,
        )
    }.stateIn(screenModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), State())

    init {
        screenModelScope.launchIO {
            val server = getRemoteServers.awaitById(serverId)
            if (server == null) {
                uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Server not found",
                    )
                }
                return@launchIO
            }
            val startPath = server.normalizedInitialPath()
            uiState.update {
                it.copy(
                    server = server,
                    currentPath = startPath,
                    initialPath = startPath,
                )
            }
            loadDirectory(server = server, path = startPath, isPullRefresh = false)
        }
    }

    fun navigateTo(path: String) {
        val server = uiState.value.server ?: return
        val normalized = RemoteFileItem.normalizePath(path)
        uiState.update { it.copy(currentPath = normalized, selectedItemForSheet = null) }
        loadDirectory(server = server, path = normalized, isPullRefresh = false)
    }

    fun navigateUp(): Boolean {
        val current = uiState.value.currentPath
        val initial = uiState.value.initialPath
        if (current == initial || current == "/") return false
        val parent = RemoteFileItem.parentPath(current)
        navigateTo(parent)
        return true
    }

    fun refresh() {
        val server = uiState.value.server ?: return
        loadDirectory(server = server, path = uiState.value.currentPath, isPullRefresh = true)
    }

    fun toggleVideosOnlyFilter() {
        uiState.update { it.copy(videosOnlyFilter = !it.videosOnlyFilter) }
    }

    fun openItemActions(item: RemoteFileItem) {
        uiState.update { it.copy(selectedItemForSheet = item) }
    }

    fun closeItemActions() {
        uiState.update { it.copy(selectedItemForSheet = null) }
    }

    fun openDownloadsSheet() {
        uiState.update { it.copy(showDownloadsSheet = true) }
    }

    fun closeDownloadsSheet() {
        uiState.update { it.copy(showDownloadsSheet = false) }
    }

    fun matchingSubtitlesFor(video: RemoteFileItem): List<RemoteFileItem> {
        val items = uiState.value.items
        val subtitleFiles = items.filter { it.isSubtitle }
        if (subtitleFiles.isEmpty()) return emptyList()

        val videoStem = video.name.substringBeforeLast('.')
        val matchedByStem = subtitleFiles.filter { sub ->
            val subStem = sub.name.substringBeforeLast('.')
            subStem.equals(videoStem, ignoreCase = true) ||
                subStem.startsWith("$videoStem.", ignoreCase = true) ||
                subStem.startsWith("${videoStem}_", ignoreCase = true) ||
                subStem.startsWith("$videoStem ", ignoreCase = true)
        }
        if (matchedByStem.isNotEmpty()) return matchedByStem

        val videoCount = items.count { it.isVideo }
        return if (videoCount <= 1) subtitleFiles else emptyList()
    }

    fun downloadFile(item: RemoteFileItem) {
        val server = uiState.value.server ?: return
        remoteDownloadManager.enqueue(server, item)
    }

    fun pauseDownload(taskId: String) = remoteDownloadManager.pause(taskId)

    fun retryDownload(taskId: String) = remoteDownloadManager.retry(taskId)

    fun cancelDownload(taskId: String) = remoteDownloadManager.cancel(taskId)

    fun clearFinishedDownloads() = remoteDownloadManager.clearFinished()

    fun markWatched(item: RemoteFileItem, watched: Boolean) {
        screenModelScope.launchIO {
            upsertRemotePlaybackHistory.markCompleted(
                serverId = serverId,
                remoteFilePath = item.path,
                fileName = item.name,
                isCompleted = watched,
            )
            closeItemActions()
        }
    }

    private fun loadDirectory(server: RemoteServer, path: String, isPullRefresh: Boolean) {
        loadJob?.cancel()
        loadJob = screenModelScope.launchIO {
            uiState.update {
                it.copy(
                    isLoading = !isPullRefresh && it.items.isEmpty(),
                    isRefreshing = isPullRefresh,
                    errorMessage = null,
                )
            }
            val result = withContext(Dispatchers.IO) {
                runCatching { RemoteServerClient.listDirectory(server, path) }
            }
            result.fold(
                onSuccess = { listed ->
                    val sorted = listed.sortedWith { a, b ->
                        when {
                            a.isDirectory != b.isDirectory -> if (a.isDirectory) -1 else 1
                            else -> a.name.compareToCaseInsensitiveNaturalOrder(b.name)
                        }
                    }
                    uiState.update {
                        it.copy(
                            items = sorted,
                            isLoading = false,
                            isRefreshing = false,
                            errorMessage = null,
                        )
                    }
                },
                onFailure = { error ->
                    uiState.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            errorMessage = error.message ?: "Failed to list directory",
                        )
                    }
                },
            )
        }
    }

    private fun buildBreadcrumbs(path: String): List<Breadcrumb> {
        val normalized = RemoteFileItem.normalizePath(path)
        if (normalized == "/") return listOf(Breadcrumb("/", "/"))
        val segments = normalized.trimStart('/').split('/').filter { it.isNotEmpty() }
        val result = mutableListOf(Breadcrumb("/", "/"))
        var acc = ""
        for (segment in segments) {
            acc = "$acc/$segment"
            result.add(Breadcrumb(segment, acc))
        }
        return result
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
// ANZ <--
