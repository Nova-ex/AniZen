package tachiyomi.data.remoteserver

import kotlinx.coroutines.flow.Flow
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.remoteserver.model.RemotePlaybackHistory
import tachiyomi.domain.remoteserver.repository.RemotePlaybackHistoryRepository

// ANZ -->
class RemotePlaybackHistoryRepositoryImpl(
    private val handler: DatabaseHandler,
) : RemotePlaybackHistoryRepository {

    override fun subscribeByServerId(serverId: Long): Flow<List<RemotePlaybackHistory>> {
        return handler.subscribeToList {
            remote_playback_historyQueries.findByServerId(serverId, ::mapHistory)
        }
    }

    override suspend fun getByServerAndPath(serverId: Long, remoteFilePath: String): RemotePlaybackHistory? {
        return handler.awaitOneOrNull {
            remote_playback_historyQueries.findByServerAndPath(serverId, remoteFilePath, ::mapHistory)
        }
    }

    override suspend fun upsert(history: RemotePlaybackHistory) {
        handler.await {
            remote_playback_historyQueries.upsert(
                serverId = history.serverId,
                remoteFilePath = history.remoteFilePath,
                fileName = history.fileName,
                lastPositionMs = history.lastPositionMs,
                durationMs = history.durationMs,
                isCompleted = history.isCompleted,
                updatedAt = history.updatedAt,
            )
        }
    }

    override suspend fun markCompleted(
        serverId: Long,
        remoteFilePath: String,
        fileName: String,
        isCompleted: Boolean,
    ) {
        handler.await {
            remote_playback_historyQueries.markCompleted(
                serverId = serverId,
                remoteFilePath = remoteFilePath,
                fileName = fileName,
                isCompleted = isCompleted,
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    override suspend fun resetProgress(serverId: Long, remoteFilePath: String) {
        handler.await {
            remote_playback_historyQueries.resetProgress(serverId, remoteFilePath)
        }
    }

    private fun mapHistory(
        id: Long,
        serverId: Long,
        remoteFilePath: String,
        fileName: String,
        lastPositionMs: Long,
        durationMs: Long,
        isCompleted: Boolean,
        updatedAt: Long,
    ): RemotePlaybackHistory {
        return RemotePlaybackHistory(
            id = id,
            serverId = serverId,
            remoteFilePath = remoteFilePath,
            fileName = fileName,
            lastPositionMs = lastPositionMs,
            durationMs = durationMs,
            isCompleted = isCompleted,
            updatedAt = updatedAt,
        )
    }
}
// ANZ <--
