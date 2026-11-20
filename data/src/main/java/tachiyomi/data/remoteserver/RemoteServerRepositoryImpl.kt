package tachiyomi.data.remoteserver

import kotlinx.coroutines.flow.Flow
import tachiyomi.core.common.security.RemoteCredentialCipher
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.remoteserver.model.RemoteProtocol
import tachiyomi.domain.remoteserver.model.RemoteServer
import tachiyomi.domain.remoteserver.repository.RemoteServerRepository

// ANZ -->
class RemoteServerRepositoryImpl(
    private val handler: DatabaseHandler,
) : RemoteServerRepository {

    override fun subscribeAll(): Flow<List<RemoteServer>> {
        return handler.subscribeToList {
            remote_serversQueries.findAll(::mapServer)
        }
    }

    override suspend fun getAll(): List<RemoteServer> {
        return handler.awaitList {
            remote_serversQueries.findAll(::mapServer)
        }
    }

    override suspend fun getById(id: Long): RemoteServer? {
        return handler.awaitOneOrNull {
            remote_serversQueries.findById(id, ::mapServer)
        }
    }

    override suspend fun insert(server: RemoteServer): Long {
        val now = if (server.createdAt > 0L) server.createdAt else System.currentTimeMillis()
        return handler.awaitOneExecutable(inTransaction = true) {
            remote_serversQueries.insert(
                name = server.name.trim(),
                protocol = server.protocol.name,
                host = server.host.trim(),
                port = server.port.toLong(),
                username = server.username.trim(),
                encryptedPassword = RemoteCredentialCipher.encrypt(server.password),
                encryptedPrivateKey = RemoteCredentialCipher.encrypt(server.privateKey),
                encryptedKeyPassphrase = RemoteCredentialCipher.encrypt(server.keyPassphrase),
                initialPath = server.normalizedInitialPath(),
                passiveMode = server.passiveMode,
                ftpsImplicit = server.ftpsImplicit,
                trustSelfSigned = server.trustSelfSigned,
                createdAt = now,
            )
            remote_serversQueries.selectLastInsertedRowId()
        }
    }

    override suspend fun update(server: RemoteServer) {
        handler.await {
            remote_serversQueries.update(
                name = server.name.trim(),
                protocol = server.protocol.name,
                host = server.host.trim(),
                port = server.port.toLong(),
                username = server.username.trim(),
                encryptedPassword = RemoteCredentialCipher.encrypt(server.password),
                encryptedPrivateKey = RemoteCredentialCipher.encrypt(server.privateKey),
                encryptedKeyPassphrase = RemoteCredentialCipher.encrypt(server.keyPassphrase),
                initialPath = server.normalizedInitialPath(),
                passiveMode = server.passiveMode,
                ftpsImplicit = server.ftpsImplicit,
                trustSelfSigned = server.trustSelfSigned,
                id = server.id,
            )
        }
    }

    override suspend fun delete(id: Long) {
        handler.await {
            remote_serversQueries.delete(id)
        }
    }

    private fun mapServer(
        id: Long,
        name: String,
        protocol: String,
        host: String,
        port: Long,
        username: String,
        encryptedPassword: String,
        encryptedPrivateKey: String,
        encryptedKeyPassphrase: String,
        initialPath: String,
        passiveMode: Boolean,
        ftpsImplicit: Boolean,
        trustSelfSigned: Boolean,
        createdAt: Long,
    ): RemoteServer {
        return RemoteServer(
            id = id,
            name = name,
            protocol = RemoteProtocol.fromString(protocol),
            host = host,
            port = port.toInt(),
            username = username,
            password = RemoteCredentialCipher.decrypt(encryptedPassword),
            privateKey = RemoteCredentialCipher.decrypt(encryptedPrivateKey),
            keyPassphrase = RemoteCredentialCipher.decrypt(encryptedKeyPassphrase),
            initialPath = initialPath,
            passiveMode = passiveMode,
            ftpsImplicit = ftpsImplicit,
            trustSelfSigned = trustSelfSigned,
            createdAt = createdAt,
        )
    }
}
// ANZ <--
