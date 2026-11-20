package eu.kanade.tachiyomi.ui.remoteserver

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ClearAll
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.data.remoteserver.RemoteDownloadManager
import eu.kanade.tachiyomi.data.remoteserver.RemoteFileItem
import tachiyomi.i18n.MR
import tachiyomi.i18n.ank.AMR
import tachiyomi.presentation.core.i18n.stringResource

// ANZ -->
private const val PERCENT_DIVISOR = 100f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteDownloadsSheet(
    downloads: List<RemoteDownloadManager.RemoteDownloadTask>,
    onPause: (String) -> Unit,
    onRetry: (String) -> Unit,
    onCancel: (String) -> Unit,
    onClearFinished: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(AMR.strings.remote_browser_downloads_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                if (downloads.any {
                        it.status == RemoteDownloadManager.Status.Completed ||
                            it.status == RemoteDownloadManager.Status.Cancelled
                    }
                ) {
                    TextButton(onClick = onClearFinished) {
                        Icon(Icons.Outlined.ClearAll, contentDescription = null)
                        Text(
                            text = stringResource(MR.strings.action_remove),
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (downloads.isEmpty()) {
                Text(
                    text = stringResource(AMR.strings.remote_browser_no_downloads),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(items = downloads, key = { it.id }) { task ->
                        RemoteDownloadTaskRow(
                            task = task,
                            onPause = { onPause(task.id) },
                            onRetry = { onRetry(task.id) },
                            onCancel = { onCancel(task.id) },
                        )
                        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun RemoteDownloadTaskRow(
    task: RemoteDownloadManager.RemoteDownloadTask,
    onPause: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.fileName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val statusText = when (task.status) {
                    RemoteDownloadManager.Status.Queued -> stringResource(MR.strings.download_notifier_download_paused)
                    RemoteDownloadManager.Status.Downloading -> {
                        val downloaded = RemoteFileItem.formatBytes(task.downloadedBytes)
                        if (task.totalBytes > 0L) {
                            "$downloaded / ${RemoteFileItem.formatBytes(task.totalBytes)} (${task.progressPercent}%)"
                        } else {
                            downloaded
                        }
                    }
                    RemoteDownloadManager.Status.Paused ->
                        "${stringResource(MR.strings.paused)} • ${RemoteFileItem.formatBytes(task.downloadedBytes)}"
                    RemoteDownloadManager.Status.Completed ->
                        stringResource(AMR.strings.remote_download_completed, task.fileName)
                    RemoteDownloadManager.Status.Failed ->
                        task.errorMessage ?: stringResource(AMR.strings.remote_download_failed, task.fileName)
                    RemoteDownloadManager.Status.Cancelled ->
                        stringResource(AMR.strings.remote_download_cancelled, task.fileName)
                }
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (task.status == RemoteDownloadManager.Status.Failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            when (task.status) {
                RemoteDownloadManager.Status.Downloading, RemoteDownloadManager.Status.Queued -> {
                    IconButton(onClick = onPause) {
                        Icon(
                            imageVector = Icons.Outlined.Pause,
                            contentDescription = stringResource(MR.strings.action_pause),
                        )
                    }
                    IconButton(onClick = onCancel) {
                        Icon(
                            imageVector = Icons.Outlined.Cancel,
                            contentDescription = stringResource(AMR.strings.remote_browser_cancel_download),
                        )
                    }
                }
                RemoteDownloadManager.Status.Paused, RemoteDownloadManager.Status.Failed -> {
                    IconButton(onClick = onRetry) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = stringResource(AMR.strings.remote_browser_resume_download),
                        )
                    }
                    IconButton(onClick = onCancel) {
                        Icon(
                            imageVector = Icons.Outlined.Cancel,
                            contentDescription = stringResource(AMR.strings.remote_browser_cancel_download),
                        )
                    }
                }
                RemoteDownloadManager.Status.Completed -> {
                    Icon(
                        imageVector = Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(8.dp),
                    )
                }
                RemoteDownloadManager.Status.Cancelled -> Unit
            }
        }

        if (task.status == RemoteDownloadManager.Status.Downloading ||
            task.status == RemoteDownloadManager.Status.Paused
        ) {
            Spacer(modifier = Modifier.height(6.dp))
            if (task.totalBytes > 0L) {
                LinearProgressIndicator(
                    progress = { task.progressPercent / PERCENT_DIVISOR },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else if (task.status == RemoteDownloadManager.Status.Downloading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
// ANZ <--
