package org.mcfso.irix.ui.files

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.mcfso.irix.R
import org.mcfso.irix.data.api.ApiResult
import org.mcfso.irix.data.model.FileEntry
import org.mcfso.irix.data.model.FileType
import org.mcfso.irix.data.model.NodeConfig
import org.mcfso.irix.ui.common.EmptyBox
import org.mcfso.irix.ui.common.ErrorBox
import org.mcfso.irix.ui.common.LoadingBox
import org.mcfso.irix.ui.common.formatBytes
import org.mcfso.irix.ui.common.rememberMessageSnackbarHostState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    uuid: String,
    node: NodeConfig,
    vm: FilesViewModel,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
) {
    LaunchedEffect(uuid, node.id) { vm.open(node, uuid) }
    val state by vm.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    var showMkdir by remember { mutableStateOf(false) }
    var showTouch by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<FileEntry?>(null) }
    val message by vm.message.collectAsState()
    val transferring by vm.transferring.collectAsState()
    val snackbarHostState = rememberMessageSnackbarHostState(message) { vm.consumeMessage() }

    val uploadLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri ->
        uri?.let { vm.upload(context, node, uuid, it) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.files_title, state.uuid.take(8))) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { vm.refresh(node, uuid) }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.refresh))
                    }
                    IconButton(onClick = { showMkdir = true }) {
                        Icon(Icons.Outlined.CreateNewFolder, contentDescription = stringResource(R.string.new_folder))
                    }
                    IconButton(onClick = { showTouch = true }) {
                        Icon(Icons.AutoMirrored.Outlined.InsertDriveFile, contentDescription = stringResource(R.string.new_file))
                    }
                },
            )
        },
        floatingActionButton = {
            androidx.compose.material3.FloatingActionButton(
                onClick = { if (!transferring) uploadLauncher.launch("*/*") },
            ) {
                if (transferring) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.padding(8.dp).size(24.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(Icons.Outlined.Upload, contentDescription = stringResource(R.string.upload_file))
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Breadcrumb(state.pathStack) { vm.backTo(node, uuid, it) }
            when (val r = state.list) {
                null -> if (state.loading) LoadingBox() else EmptyBox(stringResource(R.string.loading_files))
                is ApiResult.Error -> ErrorBox(r.message) { vm.refresh(node, uuid) }
                is ApiResult.Success -> {
                    if (r.data.items.isEmpty()) {
                        EmptyBox(stringResource(R.string.empty_dir))
                    } else {
                        FileList(
                            items = r.data.items,
                            onOpen = { e ->
                                if (e.type == FileType.DIR) vm.navigate(node, uuid, e.name)
                                else onEdit(e.name)
                            },
                            onDownload = { e -> vm.downloadTicket(context, node, uuid, e.name) },
                            onDelete = { e -> vm.delete(node, uuid, listOf(e.name)) },
                            onRename = { e -> renameTarget = e },
                        )
                    }
                }
            }
        }
    }

    if (showMkdir) NameDialog(stringResource(R.string.new_folder), onDismiss = { showMkdir = false }) {
        vm.mkdir(node, uuid, it); showMkdir = false
    }
    if (showTouch) NameDialog(stringResource(R.string.new_file), onDismiss = { showTouch = false }) {
        vm.touch(node, uuid, it); showTouch = false
    }
    renameTarget?.let { e ->
        NameDialog(stringResource(R.string.rename), initial = e.name, onDismiss = { renameTarget = null }) {
            vm.rename(node, uuid, e.name, it); renameTarget = null
        }
    }
}

@Composable
private fun Breadcrumb(stack: List<String>, onBackTo: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        stack.forEachIndexed { i, p ->
            if (i > 0) Text("/", color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { onBackTo(i) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Text(if (i == 0) stringResource(R.string.root) else p.substringAfterLast('/'))
            }
        }
    }
}

@Composable
private fun FileList(
    items: List<FileEntry>,
    onOpen: (FileEntry) -> Unit,
    onDownload: (FileEntry) -> Unit,
    onDelete: (FileEntry) -> Unit,
    onRename: (FileEntry) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp),
    ) {
        items(items, key = { it.name }) { e ->
            FileRow(e, onOpen, onDownload, onDelete, onRename)
        }
    }
}

@Composable
private fun FileRow(
    e: FileEntry,
    onOpen: (FileEntry) -> Unit,
    onDownload: (FileEntry) -> Unit,
    onDelete: (FileEntry) -> Unit,
    onRename: (FileEntry) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth(), onClick = { onOpen(e) }) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (e.type == FileType.DIR) Icons.Outlined.Folder else Icons.AutoMirrored.Outlined.InsertDriveFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(4.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(e.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(
                    if (e.type == FileType.DIR) stringResource(R.string.dir_label) else formatBytes(e.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (e.type == FileType.FILE) {
                IconButton(onClick = { onDownload(e) }) { Icon(Icons.Outlined.Download, contentDescription = stringResource(R.string.download)) }
            }
            IconButton(onClick = { onRename(e) }) { Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.rename)) }
            IconButton(onClick = { onDelete(e) }) { Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.delete)) }
        }
    }
}

@Composable
private fun NameDialog(
    title: String,
    initial: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onConfirm(name.trim()) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
