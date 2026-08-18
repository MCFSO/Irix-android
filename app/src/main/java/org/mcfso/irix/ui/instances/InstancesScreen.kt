package org.mcfso.irix.ui.instances

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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.mcfso.irix.R
import org.mcfso.irix.data.api.ApiResult
import org.mcfso.irix.data.model.InstanceDetail
import org.mcfso.irix.data.model.InstanceStatus
import org.mcfso.irix.data.model.NodeConfig
import org.mcfso.irix.ui.common.EmptyBox
import org.mcfso.irix.ui.common.ErrorBox
import org.mcfso.irix.ui.common.LoadingBox
import org.mcfso.irix.ui.common.instanceStatusLabel
import org.mcfso.irix.ui.common.rememberMessageSnackbarHostState
import org.mcfso.irix.ui.common.statusColor
import org.mcfso.irix.ui.SessionViewModel

@Composable
fun InstancesScreen(
    session: SessionViewModel,
    vm: InstancesViewModel,
    onOpenInstance: (String) -> Unit,
    onAdd: () -> Unit,
) {
    val selected by session.selected.collectAsState()
    val state by vm.state.collectAsState()
    val snackbarHostState = rememberMessageSnackbarHostState(state.lastMessage) { vm.consumeMessage() }

    LaunchedEffect(selected?.id) {
        selected?.let { vm.load(it, force = true) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) { Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.new_instance)) }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(R.string.nav_instances), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                IconButton(onClick = { selected?.let { vm.load(it, force = true) } }) {
                    Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.refresh))
                }
            }

            when (val list = state.list) {
                null -> if (state.loading) LoadingBox() else EmptyBox(stringResource(R.string.select_node_hint))
                is ApiResult.Error -> ErrorBox(list.message) { selected?.let { vm.load(it, force = true) } }
                is ApiResult.Success -> {
                    val items = list.data.data
                    if (items.isEmpty()) {
                        EmptyBox(stringResource(R.string.instances_empty_hint))
                    } else {
                        InstanceList(items, onOpenInstance)
                    }
                }
            }
        }
    }
}

@Composable
private fun InstanceList(items: List<InstanceDetail>, onOpen: (String) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp),
    ) {
        items(items, key = { it.instanceUuid }) { inst ->
            InstanceRow(inst, onClick = { onOpen(inst.instanceUuid) })
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun InstanceRow(inst: InstanceDetail, onClick: () -> Unit) {
    androidx.compose.material3.Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ColorDot(statusColor(inst.status))
            Spacer(Modifier.height(4.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    inst.config.nickname.ifEmpty { stringResource(R.string.unnamed_instance) },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    instanceStatusLabel(inst.status),
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColor(inst.status),
                )
                Text(
                    inst.instanceUuid.take(8),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ColorDot(color: Color) {
    Row(modifier = Modifier.padding(end = 12.dp)) {
        androidx.compose.foundation.Canvas(modifier = Modifier.size(12.dp)) {
            drawCircle(color)
        }
    }
}
