package eu.kanade.tachiyomi.data.remoteserver

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Environment
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.storage.DiskUtil
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notificationManager
import eu.kanade.tachiyomi.util.system.notify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.remoteserver.interactor.GetRemoteServers
import tachiyomi.domain.remoteserver.model.RemoteServer
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.i18n.MR
import tachiyomi.i18n.ank.AMR
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

// ANZ -->
class RemoteDownloadManager(
    private val context: Context,
    private val storageManager: StorageManager,
    private val getRemoteServers: GetRemoteServers,
) {
    enum class Status {
        Queued,
        Downloading,
        Paused,
        Completed,
        Failed,
        Cancelled,
    }

    data class RemoteDownloadTask(
        val id: String,
        val serverId: Long,
        val serverName: String,
        val remotePath: String,
        val fileName: String,
        val totalBytes: Long,
        val downloadedBytes: Long,
        val status: Status,
        val errorMessage: String? = null,
        val localLocationDisplay: String = "",
    ) {
        val progressPercent: Int
            get() = if (totalBytes > 0L) {
                ((downloadedBytes * PERCENT_SCALE) / totalBytes).toInt().coerceIn(0, PERCENT_SCALE)
            } else {
                0
            }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queueMutex = Mutex()
    private val activeJobs = ConcurrentHashMap<String, Job>()

    private val _tasks = MutableStateFlow<List<RemoteDownloadTask>>(emptyList())
    val tasks: StateFlow<List<RemoteDownloadTask>> = _tasks.asStateFlow()

    fun enqueue(server: RemoteServer, item: RemoteFileItem) {
        val id = buildTaskId(server.id, item.path)
        val existing = _tasks.value.firstOrNull { it.id == id }
        if (existing != null && (existing.status == Status.Downloading || existing.status == Status.Queued)) {
            return
        }

        val task = RemoteDownloadTask(
            id = id,
            serverId = server.id,
            serverName = server.name,
            remotePath = item.path,
            fileName = item.name,
            totalBytes = item.size.coerceAtLeast(0L),
            downloadedBytes = existing?.downloadedBytes ?: 0L,
            status = Status.Queued,
        )
        upsertTask(task)
        RemoteDownloadService.start(context)
        scope.launch { processQueue() }
    }

    fun retry(taskId: String) {
        val existing = _tasks.value.firstOrNull { it.id == taskId } ?: return
        upsertTask(existing.copy(status = Status.Queued, errorMessage = null))
        RemoteDownloadService.start(context)
        scope.launch { processQueue() }
    }

    fun pause(taskId: String) {
        activeJobs.remove(taskId)?.cancel()
        updateTask(taskId) { it.copy(status = Status.Paused) }
    }

    fun cancel(taskId: String) {
        activeJobs.remove(taskId)?.cancel()
        val task = _tasks.value.firstOrNull { it.id == taskId }
        if (task != null) {
            scope.launch { deletePartialFiles(task) }
            updateTask(taskId) { it.copy(status = Status.Cancelled, downloadedBytes = 0L) }
            context.notificationManager.cancel(notificationIdFor(taskId))
        }
    }

    fun clearFinished() {
        _tasks.update { list ->
            list.filterNot {
                it.status == Status.Completed || it.status == Status.Cancelled
            }
        }
    }

    fun hasActiveOrQueuedTasks(): Boolean {
        return _tasks.value.any { it.status == Status.Queued || it.status == Status.Downloading }
    }

    private suspend fun processQueue() {
        queueMutex.withLock {
            while (true) {
                val nextTask = _tasks.value.firstOrNull { it.status == Status.Queued } ?: break
                val job = scope.launch { executeDownload(nextTask.id) }
                activeJobs[nextTask.id] = job
                job.join()
                activeJobs.remove(nextTask.id)
            }
        }
        if (!hasActiveOrQueuedTasks()) {
            RemoteDownloadService.stop(context)
        }
    }

    private suspend fun executeDownload(taskId: String) {
        val current = _tasks.value.firstOrNull { it.id == taskId } ?: return
        val server = getRemoteServers.awaitById(current.serverId)
        if (server == null) {
            failTask(current, "Server no longer exists")
            return
        }

        updateTask(taskId) { it.copy(status = Status.Downloading, errorMessage = null) }

        try {
            val totalSize = if (current.totalBytes > 0L) {
                current.totalBytes
            } else {
                runCatching { RemoteServerClient.statFileSize(server, current.remotePath) }
                    .getOrDefault(-1L)
                    .coerceAtLeast(0L)
            }
            if (totalSize > 0L) {
                updateTask(taskId) { it.copy(totalBytes = totalSize) }
            }

            val dest = resolveDestination(server.name, current.fileName)
            val finalLocation = downloadToDestination(
                taskId = taskId,
                server = server,
                remotePath = current.remotePath,
                fileName = current.fileName,
                totalSize = totalSize,
                dest = dest,
            )

            val latest = _tasks.value.firstOrNull { it.id == taskId } ?: return
            if (latest.status == Status.Paused || latest.status == Status.Cancelled) {
                return
            }

            val completedTask = latest.copy(
                status = Status.Completed,
                downloadedBytes = if (totalSize > 0L) totalSize else latest.downloadedBytes,
                localLocationDisplay = finalLocation,
            )
            upsertTask(completedTask)
            showCompletedNotification(completedTask)
        } catch (_: CancellationException) {
            // Pause or cancel already updated status
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Remote download failed for ${current.remotePath}" }
            val latest = _tasks.value.firstOrNull { it.id == taskId } ?: current
            if (latest.status != Status.Paused && latest.status != Status.Cancelled) {
                failTask(latest, e.message ?: "Download failed")
            }
        }
    }

    private fun downloadToDestination(
        taskId: String,
        server: RemoteServer,
        remotePath: String,
        fileName: String,
        totalSize: Long,
        dest: DestinationTarget,
    ): String {
        val safeName = DiskUtil.buildValidFilename(fileName)
        return when (dest) {
            is DestinationTarget.LocalDir -> downloadToLocalDir(
                taskId = taskId,
                server = server,
                remotePath = remotePath,
                safeName = safeName,
                totalSize = totalSize,
                dir = dest.dir,
            )
            is DestinationTarget.SafDir -> downloadToSafDir(
                taskId = taskId,
                server = server,
                remotePath = remotePath,
                safeName = safeName,
                totalSize = totalSize,
                dir = dest.dir,
            )
        }
    }

    private fun downloadToLocalDir(
        taskId: String,
        server: RemoteServer,
        remotePath: String,
        safeName: String,
        totalSize: Long,
        dir: File,
    ): String {
        val finalFile = File(dir, safeName)
        val partFile = File(dir, "$safeName$PART_SUFFIX")

        if (finalFile.exists() && totalSize > 0L && finalFile.length() == totalSize) {
            return finalFile.absolutePath
        }

        var existingBytes = if (partFile.exists()) partFile.length().coerceAtLeast(0L) else 0L
        if (totalSize > 0L && existingBytes > totalSize) {
            partFile.delete()
            existingBytes = 0L
        }

        var downloaded = existingBytes
        updateTask(taskId) { it.copy(downloadedBytes = downloaded) }

        RemoteServerClient.openRangeStream(server, remotePath, existingBytes).use { input ->
            FileOutputStream(partFile, existingBytes > 0L).use { output ->
                downloaded = copyWithProgress(
                    taskId = taskId,
                    fileName = safeName,
                    totalSize = totalSize,
                    initialBytes = existingBytes,
                    input = input,
                    output = output,
                )
            }
        }

        if (finalFile.exists()) {
            finalFile.delete()
        }
        if (!partFile.renameTo(finalFile)) {
            throw IOException("Failed to finalize downloaded file")
        }
        updateTask(taskId) { it.copy(downloadedBytes = downloaded) }
        return finalFile.absolutePath
    }

    private fun downloadToSafDir(
        taskId: String,
        server: RemoteServer,
        remotePath: String,
        safeName: String,
        totalSize: Long,
        dir: UniFile,
    ): String {
        val partName = "$safeName$PART_SUFFIX"
        val existingPart = dir.findFile(partName)
        var existingBytes = existingPart?.length()?.coerceAtLeast(0L) ?: 0L
        if (totalSize > 0L && existingBytes > totalSize) {
            existingPart?.delete()
            existingBytes = 0L
        }

        val partFile = existingPart ?: dir.createFile(partName)
            ?: throw IOException("Failed to create destination file")

        var resumeOffset = existingBytes
        val outputStream = try {
            if (resumeOffset > 0L) {
                context.contentResolver.openOutputStream(partFile.uri, "wa")
            } else {
                partFile.openOutputStream()
            }
        } catch (_: Exception) {
            resumeOffset = 0L
            partFile.openOutputStream()
        } ?: throw IOException("Failed to open destination stream")

        updateTask(taskId) { it.copy(downloadedBytes = resumeOffset) }

        var downloaded = resumeOffset
        RemoteServerClient.openRangeStream(server, remotePath, resumeOffset).use { input ->
            outputStream.use { output ->
                downloaded = copyWithProgress(
                    taskId = taskId,
                    fileName = safeName,
                    totalSize = totalSize,
                    initialBytes = resumeOffset,
                    input = input,
                    output = output,
                )
            }
        }

        dir.findFile(safeName)?.delete()
        partFile.renameTo(safeName)
        updateTask(taskId) { it.copy(downloadedBytes = downloaded) }
        return partFile.uri.toString()
    }

    private fun copyWithProgress(
        taskId: String,
        fileName: String,
        totalSize: Long,
        initialBytes: Long,
        input: RemoteServerClient.RemoteStreamHandle,
        output: OutputStream,
    ): Long {
        val buffer = ByteArray(BUFFER_SIZE)
        var totalDownloaded = initialBytes
        var lastUpdateMs = 0L

        while (true) {
            val taskStatus = _tasks.value.firstOrNull { it.id == taskId }?.status
            if (taskStatus == Status.Paused || taskStatus == Status.Cancelled) {
                throw CancellationException("Download stopped")
            }

            val bytesRead = input.read(buffer)
            if (bytesRead <= 0) break

            output.write(buffer, 0, bytesRead)
            totalDownloaded += bytesRead

            val now = System.currentTimeMillis()
            if (now - lastUpdateMs >= NOTIFICATION_THROTTLE_MS) {
                lastUpdateMs = now
                updateTask(taskId) { it.copy(downloadedBytes = totalDownloaded) }
                showProgressNotification(taskId, fileName, totalDownloaded, totalSize)
            }
        }
        output.flush()
        return totalDownloaded
    }

    private fun resolveDestination(serverName: String, @Suppress("UNUSED_PARAMETER") fileName: String): DestinationTarget {
        val sanitizedServer = DiskUtil.buildValidFilename(serverName.ifBlank { "Remote" })
        val configuredDownloads = storageManager.getDownloadsDirectory()
        val configuredFilePath = configuredDownloads?.filePath
        if (configuredFilePath != null) {
            val targetDir = File(File(configuredFilePath, REMOTE_SUBFOLDER), sanitizedServer)
            if (targetDir.exists() || targetDir.mkdirs()) {
                return DestinationTarget.LocalDir(targetDir)
            }
        }
        if (configuredDownloads != null) {
            val remoteDir = configuredDownloads.createDirectory(REMOTE_SUBFOLDER)
            val serverDir = remoteDir?.createDirectory(sanitizedServer)
            if (serverDir != null) {
                return DestinationTarget.SafDir(serverDir)
            }
        }
        val publicDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val fallbackDir = File(File(publicDownloads, FALLBACK_APP_FOLDER), sanitizedServer)
        fallbackDir.mkdirs()
        return DestinationTarget.LocalDir(fallbackDir)
    }

    private fun deletePartialFiles(task: RemoteDownloadTask) {
        val safeName = DiskUtil.buildValidFilename(task.fileName)
        when (val dest = resolveDestination(task.serverName, task.fileName)) {
            is DestinationTarget.LocalDir -> File(dest.dir, "$safeName$PART_SUFFIX").delete()
            is DestinationTarget.SafDir -> dest.dir.findFile("$safeName$PART_SUFFIX")?.delete()
        }
    }

    private fun showProgressNotification(
        taskId: String,
        fileName: String,
        downloadedBytes: Long,
        totalBytes: Long,
    ) {
        val percent = if (totalBytes > 0L) {
            ((downloadedBytes * PERCENT_SCALE) / totalBytes).toInt().coerceIn(0, PERCENT_SCALE)
        } else {
            0
        }
        val cancelIntent = Intent(context, RemoteDownloadService::class.java).apply {
            action = RemoteDownloadService.ACTION_CANCEL_TASK
            putExtra(RemoteDownloadService.EXTRA_TASK_ID, taskId)
        }
        val cancelPendingIntent = PendingIntent.getService(
            context,
            taskId.hashCode(),
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val progressText = if (totalBytes > 0L) {
            "${RemoteFileItem.formatBytes(downloadedBytes)} / ${RemoteFileItem.formatBytes(totalBytes)} ($percent%)"
        } else {
            RemoteFileItem.formatBytes(downloadedBytes)
        }

        val notification = context.notificationBuilder(Notifications.CHANNEL_DOWNLOADER_PROGRESS) {
            setSmallIcon(android.R.drawable.stat_sys_download)
            setContentTitle(fileName)
            setContentText(progressText)
            setOngoing(true)
            setOnlyAlertOnce(true)
            setProgress(PERCENT_SCALE, percent, totalBytes <= 0L)
            addAction(
                R.drawable.ic_close_24dp,
                context.stringResource(MR.strings.action_cancel),
                cancelPendingIntent,
            )
        }.build()

        context.notify(notificationIdFor(taskId), notification)
    }

    private fun showCompletedNotification(task: RemoteDownloadTask) {
        val notification = context.notificationBuilder(Notifications.CHANNEL_DOWNLOADER_PROGRESS) {
            setSmallIcon(android.R.drawable.stat_sys_download_done)
            setContentTitle(context.stringResource(AMR.strings.remote_download_completed, task.fileName))
            setContentText(RemoteFileItem.formatBytes(task.downloadedBytes))
            setAutoCancel(true)
            setOngoing(false)
            setProgress(0, 0, false)
        }.build()

        context.notify(notificationIdFor(task.id), notification)
    }

    private fun failTask(task: RemoteDownloadTask, errorMessage: String) {
        val failed = task.copy(status = Status.Failed, errorMessage = errorMessage)
        upsertTask(failed)

        val retryIntent = Intent(context, RemoteDownloadService::class.java).apply {
            action = RemoteDownloadService.ACTION_RETRY_TASK
            putExtra(RemoteDownloadService.EXTRA_TASK_ID, task.id)
        }
        val retryPendingIntent = PendingIntent.getService(
            context,
            task.id.hashCode() + RETRY_REQ_OFFSET,
            retryIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = context.notificationBuilder(Notifications.CHANNEL_DOWNLOADER_ERROR) {
            setSmallIcon(android.R.drawable.stat_notify_error)
            setContentTitle(context.stringResource(AMR.strings.remote_download_failed, task.fileName))
            setContentText(errorMessage)
            setAutoCancel(true)
            addAction(
                R.drawable.ic_refresh_24dp,
                context.stringResource(MR.strings.action_retry),
                retryPendingIntent,
            )
        }.build()

        context.notify(notificationIdFor(task.id), notification)
    }

    private fun upsertTask(task: RemoteDownloadTask) {
        _tasks.update { list ->
            val idx = list.indexOfFirst { it.id == task.id }
            if (idx == -1) {
                listOf(task) + list
            } else {
                list.toMutableList().apply { this[idx] = task }
            }
        }
    }

    private fun updateTask(taskId: String, transform: (RemoteDownloadTask) -> RemoteDownloadTask) {
        _tasks.update { list ->
            val idx = list.indexOfFirst { it.id == taskId }
            if (idx == -1) {
                list
            } else {
                list.toMutableList().apply { this[idx] = transform(this[idx]) }
            }
        }
    }

    private sealed interface DestinationTarget {
        data class LocalDir(val dir: File) : DestinationTarget
        data class SafDir(val dir: UniFile) : DestinationTarget
    }

    companion object {
        private const val BUFFER_SIZE = 32 * 1024
        private const val NOTIFICATION_THROTTLE_MS = 500L
        private const val PERCENT_SCALE = 100
        private const val RETRY_REQ_OFFSET = 7
        private const val PART_SUFFIX = ".part"
        private const val REMOTE_SUBFOLDER = "Remote"
        private const val FALLBACK_APP_FOLDER = "AniZen"

        fun buildTaskId(serverId: Long, remotePath: String): String = "$serverId:$remotePath"

        private fun notificationIdFor(taskId: String): Int {
            return Notifications.ID_REMOTE_DOWNLOAD_BASE - (taskId.hashCode() and 0x7FFF)
        }
    }
}
// ANZ <--
