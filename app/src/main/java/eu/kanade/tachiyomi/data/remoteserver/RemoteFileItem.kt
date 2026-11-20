package eu.kanade.tachiyomi.data.remoteserver

import java.util.Locale

// ANZ -->
data class RemoteFileItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val modifiedAtMillis: Long,
) {
    val extension: String
        get() = name.substringAfterLast('.', "").lowercase(Locale.ROOT)

    val isVideo: Boolean
        get() = !isDirectory && extension in VIDEO_EXTENSIONS

    val isSubtitle: Boolean
        get() = !isDirectory && extension in SUBTITLE_EXTENSIONS

    companion object {
        val VIDEO_EXTENSIONS = setOf("mkv", "mp4", "avi", "webm", "mov", "m4v", "ts")
        val SUBTITLE_EXTENSIONS = setOf("ass", "srt", "vtt", "ssa")

        private const val BYTES_PER_KIB = 1024L
        private const val SECONDS_PER_MINUTE = 60L
        private const val SECONDS_PER_HOUR = 3600L
        private const val MS_PER_SECOND = 1000L

        fun joinPath(parent: String, child: String): String {
            val cleanParent = parent.trimEnd('/')
            val cleanChild = child.trimStart('/')
            return if (cleanParent.isEmpty()) "/$cleanChild" else "$cleanParent/$cleanChild"
        }

        fun parentPath(path: String): String {
            val trimmed = path.trimEnd('/')
            if (trimmed.isEmpty() || trimmed == "/") return "/"
            val idx = trimmed.lastIndexOf('/')
            return if (idx <= 0) "/" else trimmed.substring(0, idx)
        }

        fun normalizePath(path: String): String {
            val trimmed = path.trim()
            if (trimmed.isEmpty() || trimmed == "/") return "/"
            val withLeading = if (trimmed.startsWith('/')) trimmed else "/$trimmed"
            return withLeading.trimEnd('/').ifEmpty { "/" }
        }

        fun formatBytes(bytes: Long): String {
            if (bytes < 0L) return ""
            if (bytes < BYTES_PER_KIB) return "$bytes B"
            val kib = bytes.toDouble() / BYTES_PER_KIB
            if (kib < BYTES_PER_KIB) return String.format(Locale.US, "%.1f KB", kib)
            val mib = kib / BYTES_PER_KIB
            if (mib < BYTES_PER_KIB) return String.format(Locale.US, "%.1f MB", mib)
            val gib = mib / BYTES_PER_KIB
            return String.format(Locale.US, "%.2f GB", gib)
        }

        fun formatDurationHms(positionMs: Long): String {
            val totalSeconds = (positionMs / MS_PER_SECOND).coerceAtLeast(0L)
            val hours = totalSeconds / SECONDS_PER_HOUR
            val minutes = (totalSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
            val seconds = totalSeconds % SECONDS_PER_MINUTE
            return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        }

        fun mimeTypeForName(fileName: String): String {
            return when (fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
                "mkv" -> "video/x-matroska"
                "mp4", "m4v" -> "video/mp4"
                "avi" -> "video/x-msvideo"
                "webm" -> "video/webm"
                "mov" -> "video/quicktime"
                "ts" -> "video/mp2t"
                "vtt" -> "text/vtt; charset=utf-8"
                "srt", "ass", "ssa" -> "text/plain; charset=utf-8"
                else -> "application/octet-stream"
            }
        }
    }
}
// ANZ <--
