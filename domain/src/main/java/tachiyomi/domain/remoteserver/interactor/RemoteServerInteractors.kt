package tachiyomi.domain.remoteserver.interactor

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.remoteserver.model.RemoteServer
import tachiyomi.domain.remoteserver.repository.RemoteServerRepository

// ANZ -->
class GetRemoteServers(
    private val repository: RemoteServerRepository,
) {
    fun subscribeAll(): Flow<List<RemoteServer>> = repository.subscribeAll()

    suspend fun awaitAll(): List<RemoteServer> = repository.getAll()

    suspend fun awaitById(id: Long): RemoteServer? = repository.getById(id)
}

class SaveRemoteServer(
    private val repository: RemoteServerRepository,
) {
    suspend fun await(server: RemoteServer): Long {
        return if (server.id == 0L) {
            repository.insert(server)
        } else {
            repository.update(server)
            server.id
        }
    }
}

class DeleteRemoteServer(
    private val repository: RemoteServerRepository,
) {
    suspend fun await(id: Long) {
        repository.delete(id)
    }
}
// ANZ <--
