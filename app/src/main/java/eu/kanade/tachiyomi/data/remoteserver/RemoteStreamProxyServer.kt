package eu.kanade.tachiyomi.data.remoteserver

import eu.kanade.tachiyomi.animesource.model.Track
import fi.iki.elonen.NanoHTTPD
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.remoteserver.model.RemoteProtocol
import tachiyomi.domain.remoteserver.model.RemoteServer
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// ANZ -->
class RemoteStreamProxyServer(
    private val server: RemoteServer,
    private val videoFile: RemoteFileItem,
    private val subtitleFiles: List<RemoteFileItem>,
) : NanoHTTPD("127.0.0.1", 0) {

    private val token: String = UUID.randomUUID().toString()
    private val sftpHolder: RemoteServerClient.SftpSessionHolder? =
        if (server.protocol == RemoteProtocol.SFTP) RemoteServerClient.SftpSessionHolder(server) else null
    private val subtitleCache = ConcurrentHashMap<Int, ByteArray>()

    @Volatile
    private var cachedFileSize: Long = videoFile.size.takeIf { it > 0L } ?: -1L

    fun getVideoUrl(): String = "http://127.0.0.1:$listeningPort/stream?token=$token"

    fun getSubtitleTracks(): List<Track> {
        return subtitleFiles.mapIndexed { index, sub ->
            Track(
                url = "http://127.0.0.1:$listeningPort/sub?token=$token&index=$index",
                lang = sub.name,
            )
        }
    }

    override fun stop() {
        super.stop()
        sftpHolder?.close()
        subtitleCache.clear()
    }

    override fun serve(session: IHTTPSession): Response {
        val requestToken = session.parameters["token"]?.firstOrNull()
        if (requestToken != token) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Forbidden")
        }

        val path = session.uri ?: "/"
        return when {
            path == "/stream" -> serveVideoStream(session)
            path == "/sub" -> serveSubtitle(session)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
    }

    private fun serveVideoStream(session: IHTTPSession): Response {
        val mimeType = RemoteFileItem.mimeTypeForName(videoFile.name)
        val totalSize = resolveFileSize()

        if (session.method == Method.HEAD) {
            val headResponse = newFixedLengthResponse(Response.Status.OK, mimeType, "")
            headResponse.addHeader("Accept-Ranges", "bytes")
            if (totalSize > 0L) {
                headResponse.addHeader("Content-Length", totalSize.toString())
            }
            return headResponse
        }

        val rangeHeader = session.headers["range"]
        if (rangeHeader != null && totalSize > 0L) {
            val parsedRange = parseByteRange(rangeHeader, totalSize)
                ?: return newFixedLengthResponse(
                    Response.Status.RANGE_NOT_SATISFIABLE,
                    MIME_PLAINTEXT,
                    "Invalid range",
                ).apply {
                    addHeader("Content-Range", "bytes */$totalSize")
                }

            val (start, end) = parsedRange
            val contentLength = end - start + 1L
            return try {
                val remoteStream = RemoteServerClient.openRangeStream(
                    server = server,
                    remotePath = videoFile.path,
                    startOffset = start,
                    sftpHolder = sftpHolder,
                )
                val bounded = BoundedRemoteInputStream(remoteStream, contentLength)
                newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mimeType, bounded, contentLength).apply {
                    addHeader("Accept-Ranges", "bytes")
                    addHeader("Content-Range", "bytes $start-$end/$totalSize")
                }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Range stream open failed for ${videoFile.path} ($start-$end)" }
                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message ?: "Stream error")
            }
        }

        return try {
            val remoteStream = RemoteServerClient.openRangeStream(
                server = server,
                remotePath = videoFile.path,
                startOffset = 0L,
                sftpHolder = sftpHolder,
            )
            if (totalSize > 0L) {
                newFixedLengthResponse(Response.Status.OK, mimeType, remoteStream, totalSize).apply {
                    addHeader("Accept-Ranges", "bytes")
                }
            } else {
                newChunkedResponse(Response.Status.OK, mimeType, remoteStream)
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Full stream open failed for ${videoFile.path}" }
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message ?: "Stream error")
        }
    }

    private fun serveSubtitle(session: IHTTPSession): Response {
        val index = session.parameters["index"]?.firstOrNull()?.toIntOrNull()
        val subFile = index?.let { subtitleFiles.getOrNull(it) }
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Subtitle not found")

        return try {
            val bytes = subtitleCache[index] ?: RemoteServerClient.readSmallFileBytes(server, subFile.path).also {
                subtitleCache[index] = it
            }
            val mimeType = RemoteFileItem.mimeTypeForName(subFile.name)
            newFixedLengthResponse(
                Response.Status.OK,
                mimeType,
                ByteArrayInputStream(bytes),
                bytes.size.toLong(),
            )
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to serve remote subtitle ${subFile.path}" }
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message ?: "Subtitle error")
        }
    }

    private fun resolveFileSize(): Long {
        if (cachedFileSize > 0L) return cachedFileSize
        val probed = runCatching { RemoteServerClient.statFileSize(server, videoFile.path) }.getOrDefault(-1L)
        if (probed > 0L) {
            cachedFileSize = probed
        }
        return cachedFileSize
    }

    private fun parseByteRange(rangeHeader: String, totalSize: Long): Pair<Long, Long>? {
        val normalized = rangeHeader.trim()
        if (!normalized.startsWith("bytes=", ignoreCase = true)) return null
        val spec = normalized.substringAfter("=").substringBefore(",").trim()
        val parts = spec.split("-", limit = 2)
        if (parts.size != 2) return null

        val startPart = parts[0].trim()
        val endPart = parts[1].trim()

        val start: Long
        val end: Long
        if (startPart.isEmpty()) {
            val suffix = endPart.toLongOrNull() ?: return null
            if (suffix <= 0L) return null
            start = (totalSize - suffix).coerceAtLeast(0L)
            end = totalSize - 1L
        } else {
            start = startPart.toLongOrNull() ?: return null
            val requestedEnd = if (endPart.isEmpty()) totalSize - 1L else endPart.toLongOrNull() ?: return null
            end = requestedEnd.coerceAtMost(totalSize - 1L)
        }

        if (start < 0L || start > end || start >= totalSize) return null
        return start to end
    }

    private class BoundedRemoteInputStream(
        private val delegate: InputStream,
        private var remaining: Long,
    ) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0L) return -1
            val byteRead = delegate.read()
            if (byteRead != -1) remaining--
            return byteRead
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0L) return -1
            val toRead = minOf(len.toLong(), remaining).toInt()
            val count = delegate.read(b, off, toRead)
            if (count > 0) remaining -= count
            return count
        }

        override fun available(): Int = minOf(delegate.available().toLong(), remaining).toInt()

        override fun close() {
            delegate.close()
        }
    }
}
// ANZ <--
