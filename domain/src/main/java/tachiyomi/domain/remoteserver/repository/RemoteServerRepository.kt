package tachiyomi.domain.remoteserver.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.remoteserver.model.RemoteServer

// ANZ -->
interface RemoteServerRepository {
    fun subscribeAll(): Flow<List<RemoteServer>>
    suspend fun getAll(): List<RemoteServer>
    suspend fun getById(id: Long): RemoteServer?
    suspend fun insert(server: RemoteServer): Long
    suspend fun update(server: RemoteServer)
    suspend fun delete(id: Long)
}
// ANZ <--
