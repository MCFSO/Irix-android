package org.mcfso.irix.ui.containers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
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
import org.mcfso.irix.data.model.ContainerListItem
import org.mcfso.irix.data.model.NodeConfig
import org.mcfso.irix.ui.common.EmptyBox
import org.mcfso.irix.ui.common.ErrorBox
import org.mcfso.irix.ui.common.LoadingBox
import org.mcfso.irix.ui.common.rememberMessageSnackbarHostState
import org.mcfso.irix.ui.SessionViewModel

@Composable
fun ContainersScreen(session: SessionViewModel, vm: ContainersViewModel) {
    val selected by session.selected.collectAsState()
    val info by vm.info.collectAsState()
    val list by vm.list.collectAsState()
    val message by vm.message.collectAsState()
    val snackbarHostState = rememberMessageSnackbarHostState(message) { vm.consumeMessage() }
    var showCreate by remember { mutableStateOf(false) }

    LaunchedEffect(selected?.id) { selected?.let { vm.load(it, force = true) } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreate = true }) {
                Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.new_container))
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
                        when (val l = list) {
                            null -> LoadingBox()
                            is ApiResult.Error -> ErrorBox(l.message) { selected?.let { vm.load(it, force = true) } }
                            is ApiResult.Success -> {
                                if (l.data.isEmpty()) EmptyBox(stringResource(R.string.containers_empty))
                                else ContainerList(
                                    items = l.data,
                                    node = selected!!,
                                    onControl = { action, id -> vm.control(selected!!, id, action) },
                                    onRemove = { id -> vm.remove(selected!!, id, force = false) },
                                )
                            }
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
}

@Composable
private fun ContainerList(
    items: List<ContainerListItem>,
    node: NodeConfig,
    onControl: (ContainerAction, String) -> Unit,
    onRemove: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp),
    ) {
        items(items, key = { it.id }) { c ->
            ContainerCard(c, node, onControl, onRemove)
        }
    }
}

@Composable
private fun ContainerCard(
    c: ContainerListItem,
    node: NodeConfig,
    onControl: (ContainerAction, String) -> Unit,
    onRemove: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
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
