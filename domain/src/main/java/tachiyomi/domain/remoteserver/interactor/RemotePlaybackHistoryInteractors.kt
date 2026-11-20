package tachiyomi.domain.remoteserver.interactor

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.remoteserver.model.RemotePlaybackHistory
import tachiyomi.domain.remoteserver.repository.RemotePlaybackHistoryRepository

// ANZ -->
class GetRemotePlaybackHistory(
    private val repository: RemotePlaybackHistoryRepository,
) {
    fun subscribeByServerId(serverId: Long): Flow<List<RemotePlaybackHistory>> =
        repository.subscribeByServerId(serverId)

    suspend fun awaitByServerAndPath(serverId: Long, remoteFilePath: String): RemotePlaybackHistory? =
        repository.getByServerAndPath(serverId, remoteFilePath)
}

class UpsertRemotePlaybackHistory(
    private val repository: RemotePlaybackHistoryRepository,
) {
    suspend fun await(history: RemotePlaybackHistory) {
        repository.upsert(history)
    }

    suspend fun markCompleted(
        serverId: Long,
        remoteFilePath: String,
        fileName: String,
        isCompleted: Boolean,
    ) {
        repository.markCompleted(serverId, remoteFilePath, fileName, isCompleted)
    }

    suspend fun resetProgress(serverId: Long, remoteFilePath: String) {
        repository.resetProgress(serverId, remoteFilePath)
    }
}
// ANZ <--
