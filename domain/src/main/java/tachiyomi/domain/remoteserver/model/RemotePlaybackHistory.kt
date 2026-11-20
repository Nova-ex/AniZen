package tachiyomi.domain.remoteserver.model

// ANZ -->
data class RemotePlaybackHistory(
    val id: Long = 0L,
    val serverId: Long,
    val remoteFilePath: String,
    val fileName: String,
    val lastPositionMs: Long,
    val durationMs: Long,
    val isCompleted: Boolean,
    val updatedAt: Long,
) {
    fun shouldResume(): Boolean = !isCompleted && lastPositionMs > RESUME_MIN_POSITION_MS

    fun resumePositionWithBufferMs(): Long {
        if (!shouldResume()) return 0L
        return (lastPositionMs - RESUME_BUFFER_MS).coerceAtLeast(0L)
    }

    fun progressFraction(): Float {
        if (durationMs <= 0L) return 0f
        return (lastPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    }

    companion object {
        const val RESUME_MIN_POSITION_MS = 5_000L
        const val RESUME_BUFFER_MS = 3_000L
        const val COMPLETED_THRESHOLD_RATIO = 0.90
    }
}
// ANZ <--
