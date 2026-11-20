package eu.kanade.tachiyomi.ui.remoteserver

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreenModel
import tachiyomi.domain.remoteserver.model.RemoteProtocol
import tachiyomi.domain.remoteserver.model.RemoteServer
import tachiyomi.domain.remoteserver.model.toSourceId
import tachiyomi.i18n.MR
import tachiyomi.i18n.ank.AMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen

// ANZ -->
private const val MAX_PORT = 65535

object RemoteServersScreen : Screen {
    private fun readResolve(): Any = RemoteServersScreen

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { RemoteServersScreenModel() }
        val state by screenModel.state.collectAsState()
        val snackbarMessage by screenModel.snackbarMessage.collectAsState()
        val snackbarHostState = remember { SnackbarHostState() }

        LaunchedEffect(snackbarMessage) {
            val msg = snackbarMessage ?: return@LaunchedEffect
            snackbarHostState.showSnackbar(msg)
            screenModel.clearSnackbar()
        }

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(AMR.strings.label_remote_servers),
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
            floatingActionButton = {
                FloatingActionButton(onClick = screenModel::openAddDialog) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = stringResource(AMR.strings.action_add_remote_server),
                    )
                }
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        ) { contentPadding ->
            if (state.servers.isEmpty()) {
                EmptyScreen(
                    stringRes = AMR.strings.remote_servers_empty,
                    modifier = Modifier.padding(contentPadding),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = contentPadding.calculateTopPadding() + 12.dp,
                        bottom = contentPadding.calculateBottomPadding() + 88.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(
                        items = state.servers,
                        key = { it.id },
                    ) { server ->
                        RemoteServerCard(
                            server = server,
                            onClick = {
                                navigator.push(
                                    BrowseSourceScreen(
                                        server.toSourceId(),
                                        BrowseSourceScreenModel.Listing.Popular.query,
                                    ),
                                )
                            },
                            onEdit = { screenModel.openEditDialog(server) },
                            onTest = { screenModel.testConnection(server, showSnackbar = true) },
                            onDelete = { screenModel.openDeleteDialog(server) },
                        )
                    }
                }
            }
        }

        when (val dialog = state.dialog) {
            is RemoteServersScreenModel.Dialog.Editor -> {
                RemoteServerEditorDialog(
                    initialServer = dialog.server,
                    testState = state.testState,
                    onTestConnection = { screenModel.testConnection(it, showSnackbar = false) },
                    onSave = screenModel::saveServer,
                    onDismiss = screenModel::dismissDialog,
                )
            }
            is RemoteServersScreenModel.Dialog.Delete -> {
                AlertDialog(
                    onDismissRequest = screenModel::dismissDialog,
                    title = { Text(stringResource(AMR.strings.action_delete_remote_server)) },
                    text = { Text(stringResource(AMR.strings.confirm_delete_remote_server, dialog.server.name)) },
                    confirmButton = {
                        TextButton(onClick = { screenModel.deleteServer(dialog.server.id) }) {
                            Text(
                                text = stringResource(MR.strings.action_delete),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = screenModel::dismissDialog) {
                            Text(stringResource(MR.strings.action_cancel))
                        }
                    },
                )
            }
            null -> Unit
        }
    }
}

@Composable
private fun RemoteServerCard(
    server: RemoteServer,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onTest: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.medium,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Dns,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(10.dp),
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = server.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            text = server.protocol.displayName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                val userPrefix = if (server.username.isNotBlank()) "${server.username}@" else ""
                Text(
                    text = "$userPrefix${server.host}:${server.port}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = server.normalizedInitialPath(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Outlined.MoreVert,
                        contentDescription = null,
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(AMR.strings.action_edit_remote_server)) },
                        leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onEdit()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(AMR.strings.action_test_connection)) },
                        leadingIcon = { Icon(Icons.Outlined.NetworkCheck, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onTest()
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(AMR.strings.action_delete_remote_server),
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun RemoteServerEditorDialog(
    initialServer: RemoteServer?,
    testState: RemoteServersScreenModel.TestConnectionState,
    onTestConnection: (RemoteServer) -> Unit,
    onSave: (RemoteServer) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current

    var name by remember(initialServer) { mutableStateOf(initialServer?.name.orEmpty()) }
    var protocol by remember(initialServer) { mutableStateOf(initialServer?.protocol ?: RemoteProtocol.FTP) }
    var host by remember(initialServer) { mutableStateOf(initialServer?.host.orEmpty()) }
    var portText by remember(initialServer) {
        mutableStateOf((initialServer?.port ?: RemoteProtocol.FTP.defaultPort).toString())
    }
    var username by remember(initialServer) { mutableStateOf(initialServer?.username.orEmpty()) }
    var password by remember(initialServer) { mutableStateOf(initialServer?.password.orEmpty()) }
    var privateKey by remember(initialServer) { mutableStateOf(initialServer?.privateKey.orEmpty()) }
    var keyPassphrase by remember(initialServer) { mutableStateOf(initialServer?.keyPassphrase.orEmpty()) }
    var initialPath by remember(initialServer) { mutableStateOf(initialServer?.initialPath ?: "/") }
    var passiveMode by remember(initialServer) { mutableStateOf(initialServer?.passiveMode ?: true) }
    var ftpsImplicit by remember(initialServer) { mutableStateOf(initialServer?.ftpsImplicit ?: false) }
    var trustSelfSigned by remember(initialServer) { mutableStateOf(initialServer?.trustSelfSigned ?: false) }

    var showPassword by remember { mutableStateOf(false) }
    var showPassphrase by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf(false) }

    val keyFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()?.let { content ->
                privateKey = content
            }
        }
    }

    fun buildCandidateServer(): RemoteServer? {
        val trimmedName = name.trim()
        val trimmedHost = host.trim()
        val parsedPort = portText.trim().toIntOrNull()
        if (trimmedName.isEmpty() || trimmedHost.isEmpty() || parsedPort == null || parsedPort !in 1..MAX_PORT) {
            return null
        }
        return RemoteServer(
            id = initialServer?.id ?: 0L,
            name = trimmedName,
            protocol = protocol,
            host = trimmedHost,
            port = parsedPort,
            username = username.trim(),
            password = password,
            privateKey = if (protocol == RemoteProtocol.SFTP) privateKey.trim() else "",
            keyPassphrase = if (protocol == RemoteProtocol.SFTP) keyPassphrase else "",
            initialPath = initialPath.trim().ifEmpty { "/" },
            passiveMode = passiveMode,
            ftpsImplicit = ftpsImplicit,
            trustSelfSigned = trustSelfSigned,
            createdAt = initialServer?.createdAt ?: 0L,
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(
                    if (initialServer == null) {
                        AMR.strings.action_add_remote_server
                    } else {
                        AMR.strings.action_edit_remote_server
                    },
                ),
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        validationError = false
                    },
                    label = { Text(stringResource(AMR.strings.remote_server_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    text = stringResource(AMR.strings.remote_server_protocol),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    RemoteProtocol.entries.forEachIndexed { index, item ->
                        SegmentedButton(
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = RemoteProtocol.entries.size,
                            ),
                            selected = protocol == item,
                            onClick = {
                                val oldDefault = protocol.defaultPort.toString()
                                protocol = item
                                if (portText.isBlank() || portText == oldDefault) {
                                    portText = item.defaultPort.toString()
                                }
                            },
                            label = { Text(item.displayName) },
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = host,
                        onValueChange = {
                            host = it
                            validationError = false
                        },
                        label = { Text(stringResource(AMR.strings.remote_server_host)) },
                        singleLine = true,
                        modifier = Modifier.weight(2f),
                    )
                    OutlinedTextField(
                        value = portText,
                        onValueChange = {
                            portText = it.filter(Char::isDigit)
                            validationError = false
                        },
                        label = { Text(stringResource(AMR.strings.remote_server_port)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }

                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(stringResource(AMR.strings.remote_server_username)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(AMR.strings.remote_server_password)) },
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                imageVector = if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = null,
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                if (protocol == RemoteProtocol.SFTP) {
                    OutlinedTextField(
                        value = privateKey,
                        onValueChange = { privateKey = it },
                        label = { Text(stringResource(AMR.strings.remote_server_private_key)) },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(
                        onClick = { keyFileLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Key,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(AMR.strings.remote_server_import_key))
                    }
                    OutlinedTextField(
                        value = keyPassphrase,
                        onValueChange = { keyPassphrase = it },
                        label = { Text(stringResource(AMR.strings.remote_server_key_passphrase)) },
                        singleLine = true,
                        visualTransformation = if (showPassphrase) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            IconButton(onClick = { showPassphrase = !showPassphrase }) {
                                Icon(
                                    imageVector = if (showPassphrase) {
                                        Icons.Outlined.VisibilityOff
                                    } else {
                                        Icons.Outlined.Visibility
                                    },
                                    contentDescription = null,
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                OutlinedTextField(
                    value = initialPath,
                    onValueChange = { initialPath = it },
                    label = { Text(stringResource(AMR.strings.remote_server_initial_path)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (protocol == RemoteProtocol.FTP || protocol == RemoteProtocol.FTPS) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = stringResource(AMR.strings.remote_server_passive_mode),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Switch(
                            checked = passiveMode,
                            onCheckedChange = { passiveMode = it },
                        )
                    }
                }

                if (protocol == RemoteProtocol.FTPS) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                text = stringResource(AMR.strings.remote_server_ftps_implicit),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = stringResource(AMR.strings.remote_server_ftps_implicit_summary),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = ftpsImplicit,
                            onCheckedChange = { ftpsImplicit = it },
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                text = stringResource(AMR.strings.remote_server_trust_self_signed),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = stringResource(AMR.strings.remote_server_trust_self_signed_summary),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = trustSelfSigned,
                            onCheckedChange = { trustSelfSigned = it },
                        )
                    }
                }

                OutlinedButton(
                    onClick = {
                        val candidate = buildCandidateServer()
                        if (candidate == null) {
                            validationError = true
                        } else {
                            onTestConnection(candidate)
                        }
                    },
                    enabled = testState != RemoteServersScreenModel.TestConnectionState.Testing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (testState == RemoteServersScreenModel.TestConnectionState.Testing) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(AMR.strings.remote_server_testing))
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.NetworkCheck,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(AMR.strings.action_test_connection))
                    }
                }

                when (testState) {
                    is RemoteServersScreenModel.TestConnectionState.Success -> {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = stringResource(
                                    AMR.strings.remote_server_test_success,
                                    testState.itemCount,
                                    testState.path,
                                ),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(10.dp),
                            )
                        }
                    }
                    is RemoteServersScreenModel.TestConnectionState.Error -> {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = stringResource(
                                    AMR.strings.remote_server_test_failed,
                                    testState.message,
                                ),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(10.dp),
                            )
                        }
                    }
                    else -> Unit
                }

                if (validationError) {
                    Text(
                        text = stringResource(AMR.strings.remote_server_validation_error),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val candidate = buildCandidateServer()
                    if (candidate == null) {
                        validationError = true
                    } else {
                        onSave(candidate)
                    }
                },
            ) {
                Text(stringResource(MR.strings.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(MR.strings.action_cancel))
            }
        },
    )
}
// ANZ <--
