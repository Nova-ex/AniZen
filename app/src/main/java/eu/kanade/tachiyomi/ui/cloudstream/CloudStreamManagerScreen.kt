// ANZ -->
package eu.kanade.tachiyomi.ui.cloudstream

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData
import eu.kanade.presentation.components.AppBar
import tachiyomi.i18n.MR
import tachiyomi.i18n.ank.AMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import java.util.Locale

private const val MAX_DESCRIPTION_LINES = 2
private val FAB_BOTTOM_PADDING = 88.dp

object CloudStreamManagerScreen : Screen {
    private fun readResolve(): Any = CloudStreamManagerScreen

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { CloudStreamManagerScreenModel() }
        val state by screenModel.state.collectAsState()
        val snackbarMessage by screenModel.snackbarMessage.collectAsState()
        val snackbarHostState = remember { SnackbarHostState() }

        LaunchedEffect(snackbarMessage) {
            val msg = snackbarMessage ?: return@LaunchedEffect
            snackbarHostState.showSnackbar(msg)
            screenModel.consumeSnackbar()
        }

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(AMR.strings.label_cloudstream_extensions),
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                    actions = {
                        if (state.updatablePlugins.isNotEmpty()) {
                            IconButton(onClick = screenModel::updateAllPlugins) {
                                Icon(
                                    imageVector = Icons.Outlined.SystemUpdateAlt,
                                    contentDescription = stringResource(AMR.strings.cloudstream_action_update_all),
                                )
                            }
                        }
                        IconButton(
                            onClick = screenModel::refreshRepositories,
                            enabled = !state.isRefreshing,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = stringResource(AMR.strings.cloudstream_action_refresh),
                            )
                        }
                    },
                )
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            floatingActionButton = {
                FloatingActionButton(onClick = screenModel::openAddRepoDialog) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = stringResource(AMR.strings.cloudstream_add_repo),
                    )
                }
            },
        ) { contentPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
            ) {
                if (state.isRefreshing) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                TabSelector(
                    selectedTab = state.selectedTab,
                    installedCount = state.installedPlugins.size,
                    reposCount = state.repositories.size,
                    onSelectTab = screenModel::selectTab,
                )

                when (state.selectedTab) {
                    CloudStreamManagerScreenModel.Tab.EXTENSIONS -> {
                        ExtensionsTabContent(
                            state = state,
                            onSearchQueryChange = screenModel::updateSearchQuery,
                            onSelectFilter = screenModel::selectPluginFilter,
                            onSelectLanguage = screenModel::selectLanguageFilter,
                            onInstallOrUpdate = screenModel::installOrUpdatePlugin,
                            onUninstall = screenModel::uninstallPlugin,
                        )
                    }
                    CloudStreamManagerScreenModel.Tab.REPOSITORIES -> {
                        RepositoriesTabContent(
                            repositories = state.repositories,
                            repoPluginCounts = state.repoPluginCounts,
                            onDeleteRepo = screenModel::requestDeleteRepo,
                        )
                    }
                }
            }
        }

        if (state.showAddRepoDialog) {
            AddRepositoryDialog(
                isLoading = state.isAddingRepo,
                error = state.addRepoError,
                onDismiss = screenModel::dismissAddRepoDialog,
                onConfirm = screenModel::addRepository,
            )
        }

        state.repoPendingDelete?.let { repo ->
            DeleteRepositoryDialog(
                repo = repo,
                onDismiss = screenModel::dismissDeleteRepo,
                onConfirm = { screenModel.deleteRepository(repo) },
            )
        }
    }
}

@Composable
private fun TabSelector(
    selectedTab: CloudStreamManagerScreenModel.Tab,
    installedCount: Int,
    reposCount: Int,
    onSelectTab: (CloudStreamManagerScreenModel.Tab) -> Unit,
) {
    val tabs = listOf(
        CloudStreamManagerScreenModel.Tab.EXTENSIONS to
            "${stringResource(AMR.strings.cloudstream_tab_plugins)} ($installedCount)",
        CloudStreamManagerScreenModel.Tab.REPOSITORIES to
            "${stringResource(AMR.strings.cloudstream_tab_repos)} ($reposCount)",
    )
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        tabs.forEachIndexed { index, (tab, label) ->
            SegmentedButton(
                selected = selectedTab == tab,
                onClick = { onSelectTab(tab) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = tabs.size),
            ) {
                Text(text = label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ExtensionsTabContent(
    state: CloudStreamManagerScreenModel.State,
    onSearchQueryChange: (String) -> Unit,
    onSelectFilter: (CloudStreamManagerScreenModel.PluginFilter) -> Unit,
    onSelectLanguage: (String?) -> Unit,
    onInstallOrUpdate: (com.lagradost.cloudstream3.plugins.SitePlugin, Boolean) -> Unit,
    onUninstall: (com.lagradost.cloudstream3.plugins.PluginManager.PluginData) -> Unit,
) {
    val rows = state.filteredRows

    OutlinedTextField(
        value = state.searchQuery,
        onValueChange = onSearchQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        singleLine = true,
        placeholder = { Text(text = stringResource(AMR.strings.cloudstream_search_hint)) },
        leadingIcon = {
            Icon(imageVector = Icons.Outlined.Search, contentDescription = null)
        },
        trailingIcon = {
            if (state.searchQuery.isNotEmpty()) {
                IconButton(onClick = { onSearchQueryChange("") }) {
                    Icon(imageVector = Icons.Outlined.Clear, contentDescription = null)
                }
            }
        },
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = state.pluginFilter == CloudStreamManagerScreenModel.PluginFilter.ALL,
            onClick = { onSelectFilter(CloudStreamManagerScreenModel.PluginFilter.ALL) },
            label = { Text(stringResource(AMR.strings.cloudstream_filter_all)) },
        )
        FilterChip(
            selected = state.pluginFilter == CloudStreamManagerScreenModel.PluginFilter.INSTALLED,
            onClick = { onSelectFilter(CloudStreamManagerScreenModel.PluginFilter.INSTALLED) },
            label = { Text(stringResource(AMR.strings.cloudstream_filter_installed)) },
        )
        if (state.updatablePlugins.isNotEmpty()) {
            FilterChip(
                selected = state.pluginFilter == CloudStreamManagerScreenModel.PluginFilter.UPDATES,
                onClick = { onSelectFilter(CloudStreamManagerScreenModel.PluginFilter.UPDATES) },
                label = {
                    Text("${stringResource(AMR.strings.cloudstream_filter_updates)} (${state.updatablePlugins.size})")
                },
            )
        }
        state.availableLanguages.forEach { lang ->
            val selected = state.selectedLanguage.equals(lang, ignoreCase = true)
            FilterChip(
                selected = selected,
                onClick = { onSelectLanguage(if (selected) null else lang) },
                label = { Text(lang.uppercase(Locale.ROOT)) },
            )
        }
    }

    if (rows.isEmpty()) {
        EmptyScreen(
            stringRes = AMR.strings.cloudstream_plugins_empty,
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = FAB_BOTTOM_PADDING),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(
            items = rows,
            key = { "${it.repositoryUrl.orEmpty()}|${it.internalName}" },
        ) { row ->
            PluginItemCard(
                row = row,
                isBusy = row.busyKey in state.busyPluginKeys,
                onInstallOrUpdate = {
                    row.availablePlugin?.let { sitePlugin ->
                        onInstallOrUpdate(sitePlugin, row.hasUpdate)
                    }
                },
                onUninstall = {
                    row.installedPlugin?.let(onUninstall)
                },
            )
        }
    }
}

@Composable
private fun PluginItemCard(
    row: CloudStreamManagerScreenModel.PluginDisplayRow,
    isBusy: Boolean,
    onInstallOrUpdate: () -> Unit,
    onUninstall: () -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (!row.iconUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = row.iconUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Outlined.Extension,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = buildList {
                        add(row.language.uppercase(Locale.ROOT))
                        if (row.displayVersion.isNotBlank()) add(row.displayVersion)
                        add(row.repositoryName)
                    }.joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!row.description.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = row.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = MAX_DESCRIPTION_LINES,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            if (isBusy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 2.5.dp,
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (row.hasUpdate && row.availablePlugin != null) {
                        FilledTonalButton(onClick = onInstallOrUpdate) {
                            Text(text = stringResource(AMR.strings.cloudstream_action_update))
                        }
                    } else if (!row.isInstalled && row.availablePlugin != null) {
                        FilledTonalButton(onClick = onInstallOrUpdate) {
                            Text(text = stringResource(AMR.strings.cloudstream_action_install))
                        }
                    }

                    if (row.isInstalled) {
                        if (row.hasUpdate) {
                            IconButton(onClick = onUninstall) {
                                Icon(
                                    imageVector = Icons.Outlined.Delete,
                                    contentDescription = stringResource(AMR.strings.cloudstream_action_uninstall),
                                )
                            }
                        } else {
                            OutlinedButton(onClick = onUninstall) {
                                Text(text = stringResource(AMR.strings.cloudstream_action_uninstall))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RepositoriesTabContent(
    repositories: List<RepositoryData>,
    repoPluginCounts: Map<String, Int>,
    onDeleteRepo: (RepositoryData) -> Unit,
) {
    if (repositories.isEmpty()) {
        EmptyScreen(
            stringRes = AMR.strings.cloudstream_repos_empty,
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = FAB_BOTTOM_PADDING),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(
            items = repositories,
            key = { it.url },
        ) { repo ->
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CloudDownload,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = repo.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = repo.url,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val count = repoPluginCounts[repo.url]
                        if (count != null) {
                            Text(
                                text = "$count extensions",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    IconButton(onClick = { onDeleteRepo(repo) }) {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = stringResource(MR.strings.action_delete),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AddRepositoryDialog(
    isLoading: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var urlInput by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = { Text(text = stringResource(AMR.strings.cloudstream_add_repo)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(AMR.strings.cloudstream_add_repo_message),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading,
                    singleLine = true,
                    label = { Text(stringResource(AMR.strings.cloudstream_add_repo_hint)) },
                    isError = error != null,
                    supportingText = error?.let { msg ->
                        { Text(text = msg) }
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(urlInput) },
                enabled = !isLoading && urlInput.isNotBlank(),
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(text = stringResource(MR.strings.action_add))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isLoading) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
    )
}

@Composable
private fun DeleteRepositoryDialog(
    repo: RepositoryData,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(MR.strings.action_delete)) },
        text = {
            Text(text = stringResource(AMR.strings.cloudstream_confirm_delete_repo, repo.name))
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(MR.strings.action_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
    )
}
// ANZ <--
