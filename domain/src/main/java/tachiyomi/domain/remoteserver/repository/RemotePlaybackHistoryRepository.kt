package tachiyomi.domain.remoteserver.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.remoteserver.model.RemotePlaybackHistory

// ANZ -->
interface RemotePlaybackHistoryRepository {
    fun subscribeByServerId(serverId: Long): Flow<List<RemotePlaybackHistory>>
    suspend fun getByServerAndPath(serverId: Long, remoteFilePath: String): RemotePlaybackHistory?
    suspend fun upsert(history: RemotePlaybackHistory)
    suspend fun markCompleted(serverId: Long, remoteFilePath: String, fileName: String, isCompleted: Boolean)
    suspend fun resetProgress(serverId: Long, remoteFilePath: String)
}
// ANZ <--
