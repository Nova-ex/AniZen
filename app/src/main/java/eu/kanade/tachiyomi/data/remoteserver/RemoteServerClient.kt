package eu.kanade.tachiyomi.data.remoteserver

import android.annotation.SuppressLint
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpATTRS
import com.jcraft.jsch.SftpException
import logcat.LogPriority
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.remoteserver.model.RemoteProtocol
import tachiyomi.domain.remoteserver.model.RemoteServer
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Duration
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

// ANZ -->
object RemoteServerClient {

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val DATA_TIMEOUT_SECONDS = 30L
    private const val KEEP_ALIVE_INTERVAL_MS = 15_000
    private const val KEEP_ALIVE_COUNT_MAX = 3
    private const val BUFFER_SIZE = 8192
    private const val MS_PER_SECOND = 1000L
    private const val MAX_SUBTITLE_BYTES = 8 * 1024 * 1024

    class RemoteStreamHandle(
        val inputStream: InputStream,
        private val onClose: () -> Unit,
    ) : InputStream() {
        private var closed = false

        override fun read(): Int = inputStream.read()

        override fun read(b: ByteArray, off: Int, len: Int): Int = inputStream.read(b, off, len)

        override fun skip(n: Long): Long = inputStream.skip(n)

        override fun available(): Int = inputStream.available()

        override fun close() {
            if (closed) return
            closed = true
            try {
                inputStream.close()
            } finally {
                onClose()
            }
        }
    }

    class SftpSessionHolder(
        private val server: RemoteServer,
    ) : Closeable {
        private var session: Session? = null
        private val lock = Any()

        fun openSftpChannel(): ChannelSftp {
            synchronized(lock) {
                val activeSession = ensureSessionLocked()
                val channel = activeSession.openChannel("sftp") as ChannelSftp
                channel.connect(CONNECT_TIMEOUT_MS)
                return channel
            }
        }

        private fun ensureSessionLocked(): Session {
            val existing = session
            if (existing != null && existing.isConnected) {
                return existing
            }
            val created = createSftpSession(server)
            session = created
            return created
        }

        override fun close() {
            synchronized(lock) {
                runCatching { session?.disconnect() }
                session = null
            }
        }
    }

    fun testConnection(server: RemoteServer): Result<Int> {
        return runCatching {
            val items = listDirectory(server, server.normalizedInitialPath())
            items.size
        }
    }

    fun listDirectory(server: RemoteServer, remotePath: String): List<RemoteFileItem> {
        val targetPath = RemoteFileItem.normalizePath(remotePath)
        return when (server.protocol) {
            RemoteProtocol.FTP, RemoteProtocol.FTPS -> listFtpDirectory(server, targetPath)
            RemoteProtocol.SFTP -> listSftpDirectory(server, targetPath)
        }
    }

    fun statFileSize(server: RemoteServer, remotePath: String): Long {
        val targetPath = RemoteFileItem.normalizePath(remotePath)
        return when (server.protocol) {
            RemoteProtocol.FTP, RemoteProtocol.FTPS -> statFtpFileSize(server, targetPath)
            RemoteProtocol.SFTP -> statSftpFileSize(server, targetPath)
        }
    }

    fun openRangeStream(
        server: RemoteServer,
        remotePath: String,
        startOffset: Long,
        sftpHolder: SftpSessionHolder? = null,
    ): RemoteStreamHandle {
        val targetPath = RemoteFileItem.normalizePath(remotePath)
        return when (server.protocol) {
            RemoteProtocol.FTP, RemoteProtocol.FTPS -> openFtpRangeStream(server, targetPath, startOffset)
            RemoteProtocol.SFTP -> openSftpRangeStream(server, targetPath, startOffset, sftpHolder)
        }
    }

    fun readSmallFileBytes(
        server: RemoteServer,
        remotePath: String,
        maxBytes: Int = MAX_SUBTITLE_BYTES,
    ): ByteArray {
        return openRangeStream(server, remotePath, 0L).use { stream ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER_SIZE)
            var total = 0
            while (total < maxBytes) {
                val read = stream.read(buffer, 0, minOf(buffer.size, maxBytes - total))
                if (read <= 0) break
                out.write(buffer, 0, read)
                total += read
            }
            out.toByteArray()
        }
    }

    private fun listFtpDirectory(server: RemoteServer, targetPath: String): List<RemoteFileItem> {
        val client = createConnectedFtpClient(server)
        return try {
            val files: Array<FTPFile> = client.listFiles(targetPath) ?: emptyArray()
            files.mapNotNull { file ->
                val name = file.name ?: return@mapNotNull null
                if (name == "." || name == "..") return@mapNotNull null
                val fullPath = RemoteFileItem.joinPath(targetPath, name)
                val isDir = if (file.isSymbolicLink) {
                    runCatching { client.changeWorkingDirectory(fullPath) }.getOrDefault(false)
                } else {
                    file.isDirectory
                }
                RemoteFileItem(
                    name = name,
                    path = fullPath,
                    isDirectory = isDir,
                    size = if (isDir) 0L else file.size.coerceAtLeast(0L),
                    modifiedAtMillis = file.timestamp?.timeInMillis ?: 0L,
                )
            }
        } finally {
            disconnectFtpQuietly(client)
        }
    }

    private fun statFtpFileSize(server: RemoteServer, targetPath: String): Long {
        val client = createConnectedFtpClient(server)
        return try {
            val sizeReply = runCatching { client.getSize(targetPath)?.toLongOrNull() }.getOrNull()
            if (sizeReply != null && sizeReply >= 0L) {
                return sizeReply
            }
            val files = client.listFiles(targetPath)
            files?.firstOrNull { !it.isDirectory }?.size ?: -1L
        } finally {
            disconnectFtpQuietly(client)
        }
    }

    private fun openFtpRangeStream(
        server: RemoteServer,
        targetPath: String,
        startOffset: Long,
    ): RemoteStreamHandle {
        val client = createConnectedFtpClient(server)
        return try {
            if (startOffset > 0L) {
                client.restartOffset = startOffset
            }
            val stream = client.retrieveFileStream(targetPath)
                ?: throw IOException(
                    "FTP retrieve failed (${client.replyCode}): ${client.replyString?.trim().orEmpty()}",
                )
            RemoteStreamHandle(stream) {
                runCatching { client.abort() }
                disconnectFtpQuietly(client)
            }
        } catch (e: Exception) {
            disconnectFtpQuietly(client)
            throw e
        }
    }

    private fun createConnectedFtpClient(server: RemoteServer): FTPClient {
        val client: FTPClient = when (server.protocol) {
            RemoteProtocol.FTPS -> createFtpsClient(server)
            else -> FTPClient()
        }
        client.controlEncoding = "UTF-8"
        client.connectTimeout = CONNECT_TIMEOUT_MS
        client.defaultTimeout = CONNECT_TIMEOUT_MS
        client.setDataTimeout(Duration.ofSeconds(DATA_TIMEOUT_SECONDS))

        try {
            client.connect(server.host, server.port)
            if (!FTPReply.isPositiveCompletion(client.replyCode)) {
                throw IOException("FTP server refused connection (${client.replyCode}): ${client.replyString?.trim()}")
            }
            client.soTimeout = CONNECT_TIMEOUT_MS

            val user = server.username.ifEmpty { "anonymous" }
            val pass = if (server.username.isEmpty() && server.password.isEmpty()) "anonymous@" else server.password
            if (!client.login(user, pass)) {
                throw IOException("FTP login failed (${client.replyCode}): ${client.replyString?.trim()}")
            }

            if (client is FTPSClient) {
                runCatching {
                    client.execPBSZ(0)
                    client.execPROT("P")
                }.onFailure {
                    logcat(LogPriority.WARN, it) { "FTPS PBSZ/PROT P command returned warning" }
                }
            }

            client.setFileType(FTP.BINARY_FILE_TYPE)
            if (server.passiveMode) {
                client.enterLocalPassiveMode()
            } else {
                client.enterLocalActiveMode()
            }
            return client
        } catch (e: Exception) {
            disconnectFtpQuietly(client)
            throw e
        }
    }

    @SuppressLint("TrustAllX509TrustManager", "CustomX509TrustManager")
    private fun createFtpsClient(server: RemoteServer): FTPSClient {
        return if (server.trustSelfSigned) {
            val trustAllManager = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
            }
            FTPSClient(server.ftpsImplicit, sslContext).apply {
                trustManager = trustAllManager
                hostnameVerifier = null
            }
        } else {
            FTPSClient(server.ftpsImplicit)
        }
    }

    private fun disconnectFtpQuietly(client: FTPClient) {
        runCatching {
            if (client.isConnected) {
                client.logout()
            }
        }
        runCatching {
            if (client.isConnected) {
                client.disconnect()
            }
        }
    }

    private fun listSftpDirectory(server: RemoteServer, targetPath: String): List<RemoteFileItem> {
        val session = createSftpSession(server)
        var channel: ChannelSftp? = null
        return try {
            channel = session.openChannel("sftp") as ChannelSftp
            channel.connect(CONNECT_TIMEOUT_MS)
            val entries = channel.ls(targetPath)
            entries.mapNotNull { entry ->
                val filename = entry.filename ?: return@mapNotNull null
                if (filename == "." || filename == "..") return@mapNotNull null
                val fullPath = RemoteFileItem.joinPath(targetPath, filename)
                val attrs: SftpATTRS = resolveSftpAttrs(channel, fullPath, entry.attrs)
                val isDir = attrs.isDir
                RemoteFileItem(
                    name = filename,
                    path = fullPath,
                    isDirectory = isDir,
                    size = if (isDir) 0L else attrs.size.coerceAtLeast(0L),
                    modifiedAtMillis = attrs.mTime.toLong() * MS_PER_SECOND,
                )
            }
        } finally {
            runCatching { channel?.disconnect() }
            runCatching { session.disconnect() }
        }
    }

    private fun resolveSftpAttrs(
        channel: ChannelSftp,
        fullPath: String,
        original: SftpATTRS,
    ): SftpATTRS {
        if (!original.isLink) return original
        return try {
            channel.stat(fullPath)
        } catch (_: SftpException) {
            original
        }
    }

    private fun statSftpFileSize(server: RemoteServer, targetPath: String): Long {
        val session = createSftpSession(server)
        var channel: ChannelSftp? = null
        return try {
            channel = session.openChannel("sftp") as ChannelSftp
            channel.connect(CONNECT_TIMEOUT_MS)
            val attrs = channel.stat(targetPath)
            if (attrs.isDir) -1L else attrs.size
        } finally {
            runCatching { channel?.disconnect() }
            runCatching { session.disconnect() }
        }
    }

    private fun openSftpRangeStream(
        server: RemoteServer,
        targetPath: String,
        startOffset: Long,
        sftpHolder: SftpSessionHolder?,
    ): RemoteStreamHandle {
        if (sftpHolder != null) {
            val channel = sftpHolder.openSftpChannel()
            return try {
                val stream = channel.get(targetPath, null, startOffset.coerceAtLeast(0L))
                RemoteStreamHandle(stream) {
                    runCatching { channel.disconnect() }
                }
            } catch (e: Exception) {
                runCatching { channel.disconnect() }
                throw e
            }
        }

        val session = createSftpSession(server)
        var channel: ChannelSftp? = null
        return try {
            channel = session.openChannel("sftp") as ChannelSftp
            channel.connect(CONNECT_TIMEOUT_MS)
            val stream = channel.get(targetPath, null, startOffset.coerceAtLeast(0L))
            RemoteStreamHandle(stream) {
                runCatching { channel.disconnect() }
                runCatching { session.disconnect() }
            }
        } catch (e: Exception) {
            runCatching { channel?.disconnect() }
            runCatching { session.disconnect() }
            throw e
        }
    }

    private fun createSftpSession(server: RemoteServer): Session {
        val jsch = JSch()
        if (server.privateKey.isNotBlank()) {
            val passphraseBytes = server.keyPassphrase
                .takeIf { it.isNotEmpty() }
                ?.toByteArray(Charsets.UTF_8)
            jsch.addIdentity(
                "anizen-key-${server.id}",
                server.privateKey.toByteArray(Charsets.UTF_8),
                null,
                passphraseBytes,
            )
        }
        val session = jsch.getSession(server.username, server.host, server.port)
        session.setConfig("StrictHostKeyChecking", "no")
        session.setConfig("PreferredAuthentications", "publickey,password,keyboard-interactive")
        if (server.password.isNotEmpty()) {
            session.setPassword(server.password.toByteArray(Charsets.UTF_8))
        }
        session.timeout = CONNECT_TIMEOUT_MS
        session.setServerAliveInterval(KEEP_ALIVE_INTERVAL_MS)
        session.setServerAliveCountMax(KEEP_ALIVE_COUNT_MAX)
        session.connect(CONNECT_TIMEOUT_MS)
        return session
    }
}
// ANZ <--
