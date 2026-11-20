package eu.kanade.tachiyomi.data.remoteserver

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.notificationBuilder
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.ank.AMR
import uy.kohesive.injekt.injectLazy

// ANZ -->
class RemoteDownloadService : Service() {

    private val downloadManager: RemoteDownloadManager by injectLazy()

    override fun onCreate() {
        super.onCreate()
        val notification = notificationBuilder(Notifications.CHANNEL_DOWNLOADER_PROGRESS) {
            setSmallIcon(android.R.drawable.stat_sys_download)
            setContentTitle(stringResource(AMR.strings.remote_download_channel_name))
            setOngoing(true)
            setOnlyAlertOnce(true)
            setProgress(0, 0, true)
        }.build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                Notifications.ID_REMOTE_DOWNLOAD_FOREGROUND,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(Notifications.ID_REMOTE_DOWNLOAD_FOREGROUND, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL_TASK -> {
                val taskId = intent.getStringExtra(EXTRA_TASK_ID)
                if (taskId != null) {
                    downloadManager.cancel(taskId)
                }
            }
            ACTION_RETRY_TASK -> {
                val taskId = intent.getStringExtra(EXTRA_TASK_ID)
                if (taskId != null) {
                    downloadManager.retry(taskId)
                }
            }
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "eu.kanade.tachiyomi.remote.ACTION_START_DOWNLOAD"
        const val ACTION_STOP = "eu.kanade.tachiyomi.remote.ACTION_STOP_DOWNLOAD"
        const val ACTION_CANCEL_TASK = "eu.kanade.tachiyomi.remote.ACTION_CANCEL_TASK"
        const val ACTION_RETRY_TASK = "eu.kanade.tachiyomi.remote.ACTION_RETRY_TASK"
        const val EXTRA_TASK_ID = "extra_task_id"

        fun start(context: Context) {
            val intent = Intent(context, RemoteDownloadService::class.java).apply {
                action = ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, RemoteDownloadService::class.java).apply {
                action = ACTION_STOP
            }
            runCatching { context.startService(intent) }
        }
    }
}
// ANZ <--
