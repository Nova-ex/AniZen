package eu.kanade.tachiyomi.ui.remoteserver

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.data.remoteserver.RemoteDownloadManager
import eu.kanade.tachiyomi.data.remoteserver.RemoteServerClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.remoteserver.interactor.DeleteRemoteServer
import tachiyomi.domain.remoteserver.interactor.GetRemoteServers
import tachiyomi.domain.remoteserver.interactor.SaveRemoteServer
import tachiyomi.domain.remoteserver.model.RemoteServer
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

// ANZ -->
class RemoteServersScreenModel(
    private val getRemoteServers: GetRemoteServers = Injekt.get(),
    private val saveRemoteServer: SaveRemoteServer = Injekt.get(),
    private val deleteRemoteServer: DeleteRemoteServer = Injekt.get(),
    private val remoteDownloadManager: RemoteDownloadManager = Injekt.get(),
) : ScreenModel {

    sealed interface Dialog {
        data class Editor(val server: RemoteServer?) : Dialog
        data class Delete(val server: RemoteServer) : Dialog
        data object Downloads : Dialog
    }

    sealed interface TestConnectionState {
        data object Idle : TestConnectionState
        data object Testing : TestConnectionState
        data class Success(val itemCount: Int, val path: String) : TestConnectionState
        data class Error(val message: String) : TestConnectionState
    }

    @Immutable
    data class State(
        val servers: List<RemoteServer> = emptyList(),
        val downloads: List<RemoteDownloadManager.RemoteDownloadTask> = emptyList(),
        val dialog: Dialog? = null,
        val testState: TestConnectionState = TestConnectionState.Idle,
    )

    private val _dialog = MutableStateFlow<Dialog?>(null)
    private val _testState = MutableStateFlow<TestConnectionState>(TestConnectionState.Idle)

    val state: StateFlow<State> = combine(
        getRemoteServers.subscribeAll(),
        remoteDownloadManager.tasks,
        _dialog,
        _testState,
    ) { servers, downloads, dialog, testState ->
        State(
            servers = servers,
            downloads = downloads,
            dialog = dialog,
            testState = testState,
        )
    }.stateIn(screenModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), State())

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    fun openAddDialog() {
        _testState.update { TestConnectionState.Idle }
        _dialog.update { Dialog.Editor(null) }
    }

    fun openEditDialog(server: RemoteServer) {
        _testState.update { TestConnectionState.Idle }
        _dialog.update { Dialog.Editor(server) }
    }

    fun openDeleteDialog(server: RemoteServer) {
        _dialog.update { Dialog.Delete(server) }
    }

    fun openDownloadsDialog() {
        _dialog.update { Dialog.Downloads }
    }

    fun dismissDialog() {
        _testState.update { TestConnectionState.Idle }
        _dialog.update { null }
    }

    fun clearSnackbar() {
        _snackbarMessage.update { null }
    }

    fun saveServer(server: RemoteServer) {
        screenModelScope.launchIO {
            saveRemoteServer.await(server)
            dismissDialog()
        }
    }

    fun deleteServer(id: Long) {
        screenModelScope.launchIO {
            deleteRemoteServer.await(id)
            dismissDialog()
        }
    }

    fun testConnection(server: RemoteServer, showSnackbar: Boolean = false) {
        screenModelScope.launchIO {
            _testState.update { TestConnectionState.Testing }
            val result = withContext(Dispatchers.IO) {
                RemoteServerClient.testConnection(server)
            }
            result.fold(
                onSuccess = { count ->
                    val path = server.normalizedInitialPath()
                    _testState.update { TestConnectionState.Success(count, path) }
                    if (showSnackbar) {
                        _snackbarMessage.update { "${server.name}: Connected ($count items in $path)" }
                    }
                },
                onFailure = { error ->
                    val message = error.message ?: "Unknown error"
                    _testState.update { TestConnectionState.Error(message) }
                    if (showSnackbar) {
                        _snackbarMessage.update { "${server.name}: $message" }
                    }
                },
            )
        }
    }

    fun pauseDownload(taskId: String) = remoteDownloadManager.pause(taskId)

    fun retryDownload(taskId: String) = remoteDownloadManager.retry(taskId)

    fun cancelDownload(taskId: String) = remoteDownloadManager.cancel(taskId)

    fun clearFinishedDownloads() = remoteDownloadManager.clearFinished()

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
// ANZ <--
