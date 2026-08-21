package org.mcfso.irix.ui.containers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Tab
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.mcfso.irix.R
import org.mcfso.irix.data.api.ApiResult
import org.mcfso.irix.data.model.ContainerListItem
import org.mcfso.irix.data.model.ImageItem
import org.mcfso.irix.data.model.NetworkItem
import org.mcfso.irix.data.model.NodeConfig
import org.mcfso.irix.data.model.VolumeItem
import org.mcfso.irix.ui.common.EmptyBox
import org.mcfso.irix.ui.common.ErrorBox
import org.mcfso.irix.ui.common.InfoRow
import org.mcfso.irix.ui.common.LoadingBox
import org.mcfso.irix.ui.common.formatBytes
import org.mcfso.irix.ui.common.rememberMessageSnackbarHostState
import org.mcfso.irix.ui.SessionViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContainersScreen(session: SessionViewModel, vm: ContainersViewModel) {
    val selected by session.selected.collectAsState()
    val info by vm.info.collectAsState()
    val list by vm.list.collectAsState()
    val message by vm.message.collectAsState()
    val snackbarHostState = rememberMessageSnackbarHostState(message) { vm.consumeMessage() }
    var showCreate by remember { mutableStateOf(false) }
    var showPull by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(ContainerTab.CONTAINERS) }
    val detailId by vm.detailId.collectAsState()

    LaunchedEffect(selected?.id) { selected?.let { vm.load(it, force = true) } }
    LaunchedEffect(selected?.id, tab) {
        selected?.let { vm.loadResources(it, tab) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            when (tab) {
                ContainerTab.CONTAINERS -> FloatingActionButton(onClick = { showCreate = true }) {
                    Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.new_container))
                }
                ContainerTab.IMAGES -> FloatingActionButton(onClick = { showPull = true }) {
                    Icon(Icons.Outlined.Download, contentDescription = stringResource(R.string.image_pull))
                }
                else -> Unit
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(R.string.nav_containers), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                IconButton(onClick = { selected?.let { vm.load(it, force = true) } }) {
                    Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.refresh))
                }
            }

            when (val i = info) {
                null -> LoadingBox()
                is ApiResult.Error -> ErrorBox(i.message) { selected?.let { vm.load(it, force = true) } }
                is ApiResult.Success -> {
                    if (!i.data.available) {
                        EmptyBox(stringResource(R.string.container_unavailable, i.data.error ?: ""))
                    } else {
                        Text(
                            stringResource(R.string.container_runtime, i.data.runtime, i.data.platform, i.data.version),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        SecondaryTabRow(selectedTabIndex = tab.ordinal) {
                            Tab(ContainerTab.CONTAINERS.ordinal == tab.ordinal, onClick = { tab = ContainerTab.CONTAINERS }, text = { Text(stringResource(R.string.tab_containers)) })
                            Tab(ContainerTab.IMAGES.ordinal == tab.ordinal, onClick = { tab = ContainerTab.IMAGES }, text = { Text(stringResource(R.string.tab_images)) })
                            Tab(ContainerTab.VOLUMES.ordinal == tab.ordinal, onClick = { tab = ContainerTab.VOLUMES }, text = { Text(stringResource(R.string.tab_volumes)) })
                            Tab(ContainerTab.NETWORKS.ordinal == tab.ordinal, onClick = { tab = ContainerTab.NETWORKS }, text = { Text(stringResource(R.string.tab_networks)) })
                        }
                        Spacer(Modifier.height(8.dp))
                        when (tab) {
                            ContainerTab.CONTAINERS -> ContainersTab(vm, selected!!, onOpenDetail = { vm.openDetail(selected!!, it) })
                            ContainerTab.IMAGES -> ImagesTab(vm, selected!!, onRemove = { vm.removeImage(selected!!, it) })
                            ContainerTab.VOLUMES -> VolumesTab(vm, selected!!, onRemove = { vm.removeVolume(selected!!, it) })
                            ContainerTab.NETWORKS -> NetworksTab(vm)
                        }
                    }
                }
            }
        }
    }

    if (showCreate) {
        val node = selected
        if (node != null) {
            ContainerCreateDialog(
                onDismiss = { showCreate = false },
                onCreate = { body -> vm.create(node, body); showCreate = false },
            )
        } else {
            showCreate = false
        }
    }

    if (showPull) {
        val node = selected
        if (node != null) {
            PullImageDialog(
                onDismiss = { showPull = false },
                onPull = { name -> vm.pullImage(node, name); showPull = false },
            )
        } else {
            showPull = false
        }
    }

    detailId?.let { id ->
        val node = selected
        if (node != null) {
            ContainerDetailDialog(
                id = id,
                node = node,
                vm = vm,
                onDismiss = { vm.closeDetail() },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 页签内容
// ---------------------------------------------------------------------------

@Composable
private fun ContainersTab(vm: ContainersViewModel, node: NodeConfig, onOpenDetail: (String) -> Unit) {
    val list by vm.list.collectAsState()
    when (val l = list) {
        null -> LoadingBox()
        is ApiResult.Error -> ErrorBox(l.message) { vm.load(node, force = true) }
        is ApiResult.Success -> {
            if (l.data.isEmpty()) EmptyBox(stringResource(R.string.containers_empty))
            else LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(l.data, key = { it.id }) { c ->
                    ContainerCard(
                        c,
                        onOpenDetail = { onOpenDetail(c.id) },
                        onControl = { action, id -> vm.control(node, id, action) },
                        onRemove = { vm.remove(node, c.id, force = false) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ContainerCard(
    c: ContainerListItem,
    onOpenDetail: () -> Unit,
    onControl: (ContainerAction, String) -> Unit,
    onRemove: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onOpenDetail,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(c.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "${c.image} · ${c.status}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (c.ports.isNotEmpty()) {
                Text(stringResource(R.string.container_ports, c.ports.joinToString(", ")), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onControl(ContainerAction.START, c.id) }) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = stringResource(R.string.start))
                }
                IconButton(onClick = { onControl(ContainerAction.STOP, c.id) }) {
                    Icon(Icons.Outlined.Stop, contentDescription = stringResource(R.string.stop))
                }
                IconButton(onClick = { onControl(ContainerAction.RESTART, c.id) }) {
                    Icon(Icons.Outlined.RestartAlt, contentDescription = stringResource(R.string.restart))
                }
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = { onRemove(c.id) }) {
                    Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.delete))
                }
            }
        }
    }
}

@Composable
private fun ImagesTab(vm: ContainersViewModel, node: NodeConfig, onRemove: (String) -> Unit) {
    val images by vm.images.collectAsState()
    when (val r = images) {
        null -> LoadingBox()
        is ApiResult.Error -> ErrorBox(r.message) { vm.loadResources(node, ContainerTab.IMAGES, force = true) }
        is ApiResult.Success -> {
            if (r.data.isEmpty()) EmptyBox(stringResource(R.string.images_empty))
            else LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(r.data, key = { it.id }) { img ->
                    ImageRow(img, onRemove)
                }
            }
        }
    }
}

@Composable
private fun ImageRow(img: ImageItem, onRemove: (String) -> Unit) {
    val display = img.tags.firstOrNull() ?: img.id.take(12)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Inventory2, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            Column(Modifier.weight(1f)) {
                Text(display, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(
                    stringResource(R.string.image_size, formatBytes(img.sizeBytes)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { onRemove(display) }) {
                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.delete))
            }
        }
    }
}

@Composable
private fun VolumesTab(vm: ContainersViewModel, node: NodeConfig, onRemove: (String) -> Unit) {
    val volumes by vm.volumes.collectAsState()
    when (val r = volumes) {
        null -> LoadingBox()
        is ApiResult.Error -> ErrorBox(r.message) { vm.loadResources(node, ContainerTab.VOLUMES, force = true) }
        is ApiResult.Success -> {
            if (r.data.isEmpty()) EmptyBox(stringResource(R.string.volumes_empty))
            else LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(r.data, key = { it.name }) { v ->
                    VolumeRow(v, onRemove)
                }
            }
        }
    }
}

@Composable
private fun VolumeRow(v: VolumeItem, onRemove: (String) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            Column(Modifier.weight(1f)) {
                Text(v.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(
                    v.driver,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { onRemove(v.name) }) {
                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.delete))
            }
        }
    }
}

@Composable
private fun NetworksTab(vm: ContainersViewModel) {
    val networks by vm.networks.collectAsState()
    when (val r = networks) {
        null -> LoadingBox()
        is ApiResult.Error -> ErrorBox(r.message)
        is ApiResult.Success -> {
            if (r.data.isEmpty()) EmptyBox(stringResource(R.string.networks_empty))
            else LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(r.data, key = { it.name }) { n ->
                    NetworkRow(n)
                }
            }
        }
    }
}

@Composable
private fun NetworkRow(n: NetworkItem) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Dns, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            Column(Modifier.weight(1f)) {
                Text(n.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(
                    n.driver + (n.subnet?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 容器详情对话框（日志 + 统计 + exec）
// ---------------------------------------------------------------------------

@Composable
private fun ContainerDetailDialog(
    id: String,
    node: NodeConfig,
    vm: ContainersViewModel,
    onDismiss: () -> Unit,
) {
    val logs by vm.detailLogs.collectAsState()
    val stats by vm.detailStats.collectAsState()
    var cmd by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.container_detail_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // 资源统计
                stats?.let { s ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                        Column(Modifier.padding(12.dp)) {
                            InfoRow(stringResource(R.string.info_cpu), "%.1f%%".format(s.cpuPercent))
                            InfoRow(
                                stringResource(R.string.info_memory),
                                "${formatBytes(s.memoryBytes)} / ${formatBytes(s.memoryLimitBytes)}",
                            )
                            InfoRow("RX", formatBytes(s.netRxBytes))
                            InfoRow("TX", formatBytes(s.netTxBytes))
                        }
                    }
                }
                // 日志（等宽滚动）
                Text(stringResource(R.string.container_logs_title), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    logs.ifEmpty { stringResource(R.string.no_log_output) },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState()),
                )
                // exec
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(
                        value = cmd,
                        onValueChange = { cmd = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(stringResource(R.string.exec_placeholder)) },
                        singleLine = true,
                    )
                    IconButton(
                        onClick = { vm.exec(node, id, cmd); cmd = "" },
                        enabled = cmd.isNotBlank(),
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = stringResource(R.string.send))
                    }
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { vm.refreshDetail(node, id) }) { Text(stringResource(R.string.refresh)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) }
            }
        },
    )
}

@Composable
private fun PullImageDialog(onDismiss: () -> Unit, onPull: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.image_pull)) },
        text = {
            OutlinedTextField(
                name,
                { name = it },
                label = { Text(stringResource(R.string.image)) },
                placeholder = { Text("itzg/minecraft-server:latest") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onPull(name.trim()) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.image_pull))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
