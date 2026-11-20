package eu.kanade.tachiyomi.ui.remoteserver

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.FilterAltOff
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.tachiyomi.data.remoteserver.RemoteDownloadManager
import eu.kanade.tachiyomi.data.remoteserver.RemoteFileItem
import eu.kanade.tachiyomi.ui.player.PlayerActivity
import tachiyomi.domain.remoteserver.model.RemotePlaybackHistory
import tachiyomi.i18n.MR
import tachiyomi.i18n.ank.AMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ANZ -->
private const val PERCENT_DIVISOR = 100f
private const val MUTED_ALPHA = 0.65f

data class RemoteBrowserScreen(
    val serverId: Long,
) : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel(tag = "remote-browser-$serverId") {
            RemoteBrowserScreenModel(serverId)
        }
        val state by screenModel.state.collectAsState()
        val refreshState = rememberPullToRefreshState()
        val dateFormatter = remember {
            SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault())
        }

        BackHandler(enabled = state.canNavigateUp) {
            screenModel.navigateUp()
        }

        fun launchPlayer(item: RemoteFileItem, startOverrideMs: Long? = null) {
            val subs = screenModel.matchingSubtitlesFor(item)
            val intent = PlayerActivity.newRemoteIntent(
                context = context,
                serverId = serverId,
                remoteFilePath = item.path,
                fileName = item.name,
                fileSize = item.size,
                modifiedAtMillis = item.modifiedAtMillis,
                subtitlePaths = subs.map { it.path },
                subtitleNames = subs.map { it.name },
                startPositionOverrideMs = startOverrideMs,
            )
            context.startActivity(intent)
        }

        val activeDownloadsCount = state.allDownloads.count {
            it.status == RemoteDownloadManager.Status.Downloading ||
                it.status == RemoteDownloadManager.Status.Queued
        }

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = state.server?.name ?: stringResource(AMR.strings.label_remote_servers),
                    subtitle = state.currentPath,
                    navigateUp = {
                        if (!screenModel.navigateUp()) {
                            navigator.pop()
                        }
                    },
                    actions = {
                        IconButton(onClick = screenModel::toggleVideosOnlyFilter) {
                            Icon(
                                imageVector = if (state.videosOnlyFilter) {
                                    Icons.Outlined.FilterAltOff
                                } else {
                                    Icons.Outlined.FilterAlt
                                },
                                contentDescription = stringResource(
                                    if (state.videosOnlyFilter) {
                                        AMR.strings.remote_browser_show_all
                                    } else {
                                        AMR.strings.remote_browser_filter_videos
                                    },
                                ),
                            )
                        }
                        IconButton(onClick = screenModel::openDownloadsSheet) {
                            BadgedBox(
                                badge = {
                                    if (activeDownloadsCount > 0) {
                                        Badge { Text(activeDownloadsCount.toString()) }
                                    }
                                },
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Download,
                                    contentDescription = stringResource(AMR.strings.remote_browser_downloads_title),
                                )
                            }
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = contentPadding.calculateTopPadding()),
            ) {
                BreadcrumbsBar(
                    breadcrumbs = state.breadcrumbs,
                    onClickBreadcrumb = { screenModel.navigateTo(it.path) },
                )
                HorizontalDivider()

                PullToRefreshBox(
                    isRefreshing = state.isRefreshing,
                    onRefresh = screenModel::refresh,
                    state = refreshState,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    when {
                        state.isLoading -> {
                            LoadingScreen()
                        }
                        state.errorMessage != null && state.visibleItems.isEmpty() -> {
                            RemoteErrorState(
                                message = stringResource(
                                    AMR.strings.remote_browser_load_error,
                                    state.errorMessage.orEmpty(),
                                ),
                                onRetry = screenModel::refresh,
                            )
                        }
                        state.visibleItems.isEmpty() -> {
                            EmptyScreen(stringRes = AMR.strings.remote_browser_empty_folder)
                        }
                        else -> {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(
                                    bottom = contentPadding.calculateBottomPadding() + 16.dp,
                                ),
                            ) {
                                items(
                                    items = state.visibleItems,
                                    key = { it.path },
                                ) { item ->
                                    val taskId = RemoteDownloadManager.buildTaskId(serverId, item.path)
                                    val downloadTask = state.downloadsByTaskId[taskId]
                                    val history = state.historyByPath[item.path]

                                    RemoteFileRow(
                                        item = item,
                                        history = history,
                                        downloadTask = downloadTask,
                                        dateFormatter = dateFormatter,
                                        onClick = {
                                            when {
                                                item.isDirectory -> screenModel.navigateTo(item.path)
                                                item.isVideo -> launchPlayer(item)
                                                else -> screenModel.openItemActions(item)
                                            }
                                        },
                                        onLongClick = {
                                            if (!item.isDirectory) {
                                                screenModel.openItemActions(item)
                                            }
                                        },
                                        onDownloadAction = {
                                            when (downloadTask?.status) {
                                                RemoteDownloadManager.Status.Downloading,
                                                RemoteDownloadManager.Status.Queued,
                                                -> screenModel.pauseDownload(taskId)
                                                RemoteDownloadManager.Status.Paused,
                                                RemoteDownloadManager.Status.Failed,
                                                -> screenModel.retryDownload(taskId)
                                                else -> screenModel.downloadFile(item)
                                            }
                                        },
                                    )
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
                }
            }
        }

        state.selectedItemForSheet?.let { selected ->
            val history = state.historyByPath[selected.path]
            val taskId = RemoteDownloadManager.buildTaskId(serverId, selected.path)
            val downloadTask = state.downloadsByTaskId[taskId]
            RemoteFileActionSheet(
                item = selected,
                history = history,
                downloadTask = downloadTask,
                onPlay = {
                    screenModel.closeItemActions()
                    launchPlayer(selected)
                },
                onPlayFromStart = {
                    screenModel.closeItemActions()
                    launchPlayer(selected, startOverrideMs = 0L)
                },
                onToggleWatched = {
                    val markWatched = history?.isCompleted != true
                    screenModel.markWatched(selected, markWatched)
                },
                onDownload = {
                    screenModel.closeItemActions()
                    when (downloadTask?.status) {
                        RemoteDownloadManager.Status.Paused,
                        RemoteDownloadManager.Status.Failed,
                        -> screenModel.retryDownload(taskId)
                        else -> screenModel.downloadFile(selected)
                    }
                },
                onCancelDownload = {
                    screenModel.closeItemActions()
                    screenModel.cancelDownload(taskId)
                },
                onDismiss = screenModel::closeItemActions,
            )
        }

        if (state.showDownloadsSheet) {
            RemoteDownloadsSheet(
                downloads = state.allDownloads,
                onPause = screenModel::pauseDownload,
                onRetry = screenModel::retryDownload,
                onCancel = screenModel::cancelDownload,
                onClearFinished = screenModel::clearFinishedDownloads,
                onDismiss = screenModel::closeDownloadsSheet,
            )
        }
    }
}

@Composable
private fun BreadcrumbsBar(
    breadcrumbs: List<RemoteBrowserScreenModel.Breadcrumb>,
    onClickBreadcrumb: (RemoteBrowserScreenModel.Breadcrumb) -> Unit,
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(breadcrumbs) { index, crumb ->
            if (index > 0) {
                Icon(
                    imageVector = Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            AssistChip(
                onClick = { onClickBreadcrumb(crumb) },
                label = {
                    Text(
                        text = crumb.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RemoteFileRow(
    item: RemoteFileItem,
    history: RemotePlaybackHistory?,
    downloadTask: RemoteDownloadManager.RemoteDownloadTask?,
    dateFormatter: SimpleDateFormat,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDownloadAction: () -> Unit,
) {
    val containerTint = if (item.isVideo) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
    } else {
        MaterialTheme.colorScheme.surface
    }

    Surface(color = containerTint) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val icon = when {
                    item.isDirectory -> Icons.Outlined.Folder
                    item.isVideo -> Icons.Outlined.Movie
                    item.isSubtitle -> Icons.Outlined.Subtitles
                    else -> Icons.AutoMirrored.Outlined.InsertDriveFile
                }
                val iconTint = when {
                    item.isDirectory || item.isVideo -> MaterialTheme.colorScheme.primary
                    item.isSubtitle -> MaterialTheme.colorScheme.secondary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = MUTED_ALPHA)
                }

                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(28.dp),
                )

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (item.isDirectory || item.isVideo) FontWeight.Medium else FontWeight.Normal,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )

                    val metaParts = buildList {
                        if (!item.isDirectory && item.size > 0L) {
                            add(RemoteFileItem.formatBytes(item.size))
                        }
                        if (item.modifiedAtMillis > 0L) {
                            add(dateFormatter.format(Date(item.modifiedAtMillis)))
                        }
                    }
                    if (metaParts.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = metaParts.joinToString(" • "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    if (item.isVideo && history != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        when {
                            history.isCompleted -> {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.CheckCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(14.dp),
                                    )
                                    Text(
                                        text = stringResource(AMR.strings.remote_browser_watched),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            history.shouldResume() -> {
                                val formatted = RemoteFileItem.formatDurationHms(history.lastPositionMs)
                                Text(
                                    text = stringResource(AMR.strings.remote_browser_resume_from, formatted),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    }
                }

                if (!item.isDirectory) {
                    Spacer(modifier = Modifier.width(8.dp))
                    DownloadTrailingControl(
                        task = downloadTask,
                        onClick = onDownloadAction,
                    )
                }
            }

            if (item.isVideo && history != null && history.shouldResume() && history.durationMs > 0L) {
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { history.progressFraction() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun DownloadTrailingControl(
    task: RemoteDownloadManager.RemoteDownloadTask?,
    onClick: () -> Unit,
) {
    when (task?.status) {
        RemoteDownloadManager.Status.Downloading -> {
            IconButton(onClick = onClick) {
                Box(contentAlignment = Alignment.Center) {
                    if (task.totalBytes > 0L) {
                        CircularProgressIndicator(
                            progress = { task.progressPercent / PERCENT_DIVISOR },
                            modifier = Modifier.size(26.dp),
                            strokeWidth = 2.5.dp,
                        )
                    } else {
                        CircularProgressIndicator(
                            modifier = Modifier.size(26.dp),
                            strokeWidth = 2.5.dp,
                        )
                    }
                    Icon(
                        imageVector = Icons.Outlined.Pause,
                        contentDescription = stringResource(MR.strings.action_pause),
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
        RemoteDownloadManager.Status.Queued -> {
            IconButton(onClick = onClick) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.5.dp,
                )
            }
        }
        RemoteDownloadManager.Status.Paused, RemoteDownloadManager.Status.Failed -> {
            IconButton(onClick = onClick) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = stringResource(AMR.strings.remote_browser_retry_download),
                    tint = if (task.status == RemoteDownloadManager.Status.Failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
        }
        RemoteDownloadManager.Status.Completed -> {
            Icon(
                imageVector = Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(12.dp),
            )
        }
        else -> {
            IconButton(onClick = onClick) {
                Icon(
                    imageVector = Icons.Outlined.Download,
                    contentDescription = stringResource(AMR.strings.remote_browser_download),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemoteFileActionSheet(
    item: RemoteFileItem,
    history: RemotePlaybackHistory?,
    downloadTask: RemoteDownloadManager.RemoteDownloadTask?,
    onPlay: () -> Unit,
    onPlayFromStart: () -> Unit,
    onToggleWatched: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            HorizontalDivider()

            if (item.isVideo) {
                val resumeLabel = if (history?.shouldResume() == true) {
                    stringResource(
                        AMR.strings.remote_browser_resume_from,
                        RemoteFileItem.formatDurationHms(history.lastPositionMs),
                    )
                } else {
                    stringResource(MR.strings.action_play)
                }
                ListItem(
                    headlineContent = { Text(resumeLabel) },
                    leadingIcon = { Icon(Icons.Outlined.PlayArrow, contentDescription = null) },
                    modifier = Modifier.combinedClickable(onClick = onPlay),
                )
                if (history?.shouldResume() == true) {
                    ListItem(
                        headlineContent = { Text(stringResource(AMR.strings.remote_browser_play_from_start)) },
                        leadingIcon = { Icon(Icons.Outlined.Replay, contentDescription = null) },
                        modifier = Modifier.combinedClickable(onClick = onPlayFromStart),
                    )
                }
                val isWatched = history?.isCompleted == true
                ListItem(
                    headlineContent = {
                        Text(
                            stringResource(
                                if (isWatched) {
                                    AMR.strings.remote_browser_mark_unwatched
                                } else {
                                    AMR.strings.remote_browser_mark_watched
                                },
                            ),
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = if (isWatched) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = null,
                        )
                    },
                    modifier = Modifier.combinedClickable(onClick = onToggleWatched),
                )
            }

            if (downloadTask?.status == RemoteDownloadManager.Status.Downloading ||
                downloadTask?.status == RemoteDownloadManager.Status.Queued
            ) {
                ListItem(
                    headlineContent = { Text(stringResource(AMR.strings.remote_browser_cancel_download)) },
                    leadingIcon = { Icon(Icons.Outlined.Download, contentDescription = null) },
                    modifier = Modifier.combinedClickable(onClick = onCancelDownload),
                )
            } else {
                val downloadLabel = when (downloadTask?.status) {
                    RemoteDownloadManager.Status.Paused -> stringResource(AMR.strings.remote_browser_resume_download)
                    RemoteDownloadManager.Status.Failed -> stringResource(AMR.strings.remote_browser_retry_download)
                    else -> stringResource(AMR.strings.remote_browser_download)
                }
                ListItem(
                    headlineContent = { Text(downloadLabel) },
                    leadingIcon = { Icon(Icons.Outlined.Download, contentDescription = null) },
                    modifier = Modifier.combinedClickable(onClick = onDownload),
                )
            }
        }
    }
}

@Composable
private fun RemoteErrorState(
    message: String,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRetry) {
            Icon(Icons.Outlined.Refresh, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(MR.strings.action_retry))
        }
    }
}
// ANZ <--
