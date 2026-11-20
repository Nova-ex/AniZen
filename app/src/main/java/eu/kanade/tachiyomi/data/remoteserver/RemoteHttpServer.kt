package eu.kanade.tachiyomi.data.remoteserver

import android.net.Uri
import eu.kanade.tachiyomi.animesource.model.HttpServer
import fi.iki.elonen.NanoHTTPD
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.remoteserver.model.RemoteProtocol
import tachiyomi.domain.remoteserver.model.RemoteServer
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// ANZ -->
class RemoteHttpServer(
    private val server: RemoteServer,
    private val authToken: String,
) : HttpServer() {

    private val sftpHolder: RemoteServerClient.SftpSessionHolder? =
        if (server.protocol == RemoteProtocol.SFTP) RemoteServerClient.SftpSessionHolder(server) else null

    private val fileSizeCache = ConcurrentHashMap<String, Long>()
    private val subtitleCache = ConcurrentHashMap<String, ByteArray>()

    override fun stop() {
        runCatching { sftpHolder?.close() }
        super.stop()
    }

    override fun serve(session: IHTTPSession): Response {
        val token = session.parameters["token"]?.firstOrNull()
        if (token != authToken) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Forbidden")
        }

        val remotePath = session.parameters["path"]?.firstOrNull()
        if (remotePath.isNullOrBlank()) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing path")
        }

        val normalizedPath = RemoteFileItem.normalizePath(remotePath)
        return when (session.uri) {
            PATH_SUB -> serveSubtitle(normalizedPath)
            PATH_STREAM -> serveMediaStream(session, normalizedPath)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
    }

    private fun serveSubtitle(remotePath: String): Response {
        return try {
            val bytes = subtitleCache[remotePath] ?: run {
                val fetched = if (sftpHolder != null) {
                    sftpHolder.readSmallFileBytes(remotePath)
                } else {
                    RemoteServerClient.readSmallFileBytes(server, remotePath)
                }
                subtitleCache[remotePath] = fetched
                fetched
            }
            val fileName = remotePath.substringAfterLast('/')
            val mimeType = RemoteFileItem.mimeTypeForName(fileName)
            newFixedLengthResponse(
                Response.Status.OK,
                mimeType,
                ByteArrayInputStream(bytes),
                bytes.size.toLong(),
            )
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Subtitle stream error for $remotePath" }
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message ?: "Error")
        }
    }

    private fun serveMediaStream(session: IHTTPSession, remotePath: String): Response {
        return try {
            val fileSize = resolveFileSize(remotePath)
            val fileName = remotePath.substringAfterLast('/')
            val mimeType = RemoteFileItem.mimeTypeForName(fileName)

            if (session.method == Method.HEAD) {
                val headResponse = if (fileSize >= 0L) {
                    newFixedLengthResponse(Response.Status.OK, mimeType, null, fileSize)
                } else {
                    newFixedLengthResponse(Response.Status.OK, mimeType, "")
                }
                headResponse.addHeader("Accept-Ranges", "bytes")
                return headResponse
            }

            val rangeHeader = session.headers["range"] ?: session.headers["Range"]
            if (rangeHeader != null && fileSize > 0L) {
                val parsedRange = parseByteRange(rangeHeader, fileSize)
                    ?: return rangeNotSatisfiableResponse(fileSize)

                val (start, end) = parsedRange
                val contentLength = end - start + 1L
                val rawStream = RemoteServerClient.openRangeStream(
                    server = server,
                    remotePath = remotePath,
                    startOffset = start,
                    sftpHolder = sftpHolder,
                )
                val bounded = BoundedInputStream(rawStream, contentLength)
                return newFixedLengthResponse(
                    Response.Status.PARTIAL_CONTENT,
                    mimeType,
                    bounded,
                    contentLength,
                ).apply {
                    addHeader("Accept-Ranges", "bytes")
                    addHeader("Content-Range", "bytes $start-$end/$fileSize")
                }
            }

            val rawStream = RemoteServerClient.openRangeStream(
                server = server,
                remotePath = remotePath,
                startOffset = 0L,
                sftpHolder = sftpHolder,
            )
            val response = if (fileSize > 0L) {
                newFixedLengthResponse(Response.Status.OK, mimeType, rawStream, fileSize)
            } else {
                newChunkedResponse(Response.Status.OK, mimeType, rawStream)
            }
            response.addHeader("Accept-Ranges", "bytes")
            response
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Media stream error for $remotePath" }
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message ?: "Stream error")
        }
    }

    private fun resolveFileSize(remotePath: String): Long {
        fileSizeCache[remotePath]?.let { return it }
        val size = if (sftpHolder != null) {
            sftpHolder.statFileSize(remotePath)
        } else {
            RemoteServerClient.statFileSize(server, remotePath)
        }
        if (size > 0L) {
            fileSizeCache[remotePath] = size
        }
        return size
    }

    private fun parseByteRange(rangeHeader: String, totalSize: Long): Pair<Long, Long>? {
        val value = rangeHeader.trim()
        if (!value.startsWith("bytes=", ignoreCase = true)) return null
        val rangeSpec = value.substringAfter("bytes=").substringBefore(',').trim()
        val parts = rangeSpec.split('-', limit = 2)
        if (parts.size != 2) return null

        val startPart = parts[0].trim()
        val endPart = parts[1].trim()

        val start: Long
        val end: Long
        if (startPart.isEmpty()) {
            val suffixLen = endPart.toLongOrNull() ?: return null
            if (suffixLen <= 0L) return null
            start = (totalSize - suffixLen).coerceAtLeast(0L)
            end = totalSize - 1L
        } else {
            start = startPart.toLongOrNull() ?: return null
            end = if (endPart.isEmpty()) {
                totalSize - 1L
            } else {
                (endPart.toLongOrNull() ?: return null).coerceAtMost(totalSize - 1L)
            }
        }
        if (start < 0L || start >= totalSize || end < start) return null
        return start to end
    }

    private fun rangeNotSatisfiableResponse(totalSize: Long): Response {
        return newFixedLengthResponse(
            Response.Status.RANGE_NOT_SATISFIABLE,
            MIME_PLAINTEXT,
            "",
        ).apply {
            addHeader("Content-Range", "bytes */$totalSize")
        }
    }

    private class BoundedInputStream(
        delegate: InputStream,
        private var remaining: Long,
    ) : FilterInputStream(delegate) {
        override fun read(): Int {
            if (remaining <= 0L) return -1
            val b = super.read()
            if (b != -1) remaining--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0L) return -1
            val toRead = minOf(len.toLong(), remaining).toInt()
            val count = super.read(b, off, toRead)
            if (count > 0) remaining -= count
            return count
        }

        override fun available(): Int = minOf(super.available().toLong(), remaining).toInt()
    }

    companion object {
        const val PATH_STREAM = "/stream"
        const val PATH_SUB = "/sub"

        fun newToken(): String = UUID.randomUUID().toString()

        fun buildStreamUrl(remotePath: String, authToken: String): String {
            return "$PLACEHOLDER_URL$PATH_STREAM?path=${Uri.encode(remotePath)}&token=${Uri.encode(authToken)}"
        }

        fun buildSubtitleUrl(remotePath: String, authToken: String): String {
            return "$PLACEHOLDER_URL$PATH_SUB?path=${Uri.encode(remotePath)}&token=${Uri.encode(authToken)}"
        }
    }
}
// ANZ <--
