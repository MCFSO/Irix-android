package org.mcfso.irix.ui.nodes

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.mcfso.irix.AppContainer
import org.mcfso.irix.R
import org.mcfso.irix.data.NetworkPolicy
import org.mcfso.irix.data.model.NodeConfig
import org.mcfso.irix.ui.SessionViewModel
import org.mcfso.irix.ui.common.EmptyBox

@Composable
fun NodesScreen(session: SessionViewModel) {
    val nodes by session.nodes.collectAsState()
    val selected by session.selected.collectAsState()
    val scope = rememberCoroutineScope()
    var showEditor by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<NodeConfig?>(null) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = null; showEditor = true }) {
                Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.add_node))
            }
        },
    ) { padding ->
        if (nodes.isEmpty()) {
            EmptyBox(stringResource(R.string.nodes_empty_hint), Modifier.padding(padding))
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
        ) {
            items(nodes, key = { it.id }) { node ->
                NodeCard(
                    node = node,
                    isSelected = node.id == selected?.id,
                    onSelect = { scope.launch { AppContainer.nodeStore.select(node.id) } },
                    onEdit = { editing = node; showEditor = true },
                    onDelete = { scope.launch { AppContainer.nodeStore.delete(node.id) } },
                )
            }
        }
    }

    if (showEditor) {
        NodeEditorDialog(
            existing = editing,
            onDismiss = { showEditor = false },
            onSave = { name, address, apiKey ->
                scope.launch {
                    if (editing == null) AppContainer.nodeStore.add(name, address, apiKey)
                    else AppContainer.nodeStore.update(editing!!.id, name, address, apiKey)
                }
                showEditor = false
            },
        )
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun NodeCard(
    node: NodeConfig,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        onClick = onSelect,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = isSelected, onClick = onSelect)
            Spacer(Modifier.height(4.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(node.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    node.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (node.apiKey.isNotEmpty()) {
                    Text(stringResource(R.string.key_configured), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.edit)) }
            IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.delete)) }
        }
    }
}

@Composable
private fun NodeEditorDialog(
    existing: NodeConfig?,
    onDismiss: () -> Unit,
    onSave: (name: String, address: String, apiKey: String) -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var address by remember { mutableStateOf(existing?.address ?: "http://") }
    var apiKey by remember { mutableStateOf(existing?.apiKey ?: "") }

    // 地址校验：scheme 必须为 http/https；明文 HTTP 仅限私有地址（NetworkPolicy）
    val schemeOk = address.startsWith("http://", ignoreCase = true) ||
        address.startsWith("https://", ignoreCase = true)
    val addressError = when {
        address.isBlank() -> null
        !schemeOk -> stringResource(R.string.address_scheme_required)
        address.startsWith("http://", ignoreCase = true) -> NetworkPolicy.cleartextViolation(address)
        else -> null
    }
    val valid = name.isNotBlank() && schemeOk && addressError == null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (existing == null) R.string.add_node else R.string.edit_node)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text(stringResource(R.string.address)) },
                    placeholder = { Text(stringResource(R.string.address_placeholder)) },
                    supportingText = addressError?.let { err -> { Text(err) } },
                    isError = addressError != null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text(stringResource(R.string.api_key_optional)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim(), address.trim(), apiKey.trim()) }, enabled = valid) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
