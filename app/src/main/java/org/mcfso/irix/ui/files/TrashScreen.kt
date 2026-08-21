package org.mcfso.irix.ui.files

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.mcfso.irix.AppContainer
import org.mcfso.irix.R
import org.mcfso.irix.data.api.ApiResult
import org.mcfso.irix.data.model.NodeConfig
import org.mcfso.irix.data.model.TrashEntry
import org.mcfso.irix.data.model.TrashListResponse
import org.mcfso.irix.ui.common.EmptyBox
import org.mcfso.irix.ui.common.ErrorBox
import org.mcfso.irix.ui.common.LoadingBox
import org.mcfso.irix.ui.common.formatBytes
import org.mcfso.irix.ui.common.rememberMessageSnackbarHostState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 回收站 ViewModel：列表 / 恢复 / 永久删除 / 清空。 */
class TrashViewModel(application: android.app.Application) : androidx.lifecycle.AndroidViewModel(application) {

    private val _state = MutableStateFlow<ApiResult<TrashListResponse>?>(null)
    val state: StateFlow<ApiResult<TrashListResponse>?> = _state.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun load(node: NodeConfig, uuid: String) {
        _state.value = null
        viewModelScope.launch {
            _state.value = AppContainer.repository.trashList(node, uuid)
        }
    }

    fun restore(node: NodeConfig, uuid: String, ids: List<String>) {
        viewModelScope.launch {
            val r = AppContainer.repository.trashRestore(node, uuid, ids)
            _message.value = when (r) {
                is ApiResult.Success -> getApplication<android.app.Application>().getString(R.string.trash_restored, r.data.size)
                is ApiResult.Error -> r.message
            }
            load(node, uuid)
        }
    }

    fun deleteForever(node: NodeConfig, uuid: String, ids: List<String>) {
        viewModelScope.launch {
            val r = AppContainer.repository.trashEmpty(node, uuid, ids)
            _message.value = if (r is ApiResult.Error) r.message else null
            load(node, uuid)
        }
    }

    fun emptyAll(node: NodeConfig, uuid: String) {
        viewModelScope.launch {
            val r = AppContainer.repository.trashEmpty(node, uuid, emptyList())
            _message.value = when (r) {
                is ApiResult.Success -> getApplication<android.app.Application>().getString(R.string.trash_emptied)
                is ApiResult.Error -> r.message
            }
            load(node, uuid)
        }
    }

    fun consumeMessage() = _message.update { null }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(
    uuid: String,
    node: NodeConfig,
    vm: TrashViewModel,
    onBack: () -> Unit,
) {
    LaunchedEffect(uuid, node.id) { vm.load(node, uuid) }
    val state by vm.state.collectAsState()
    val message by vm.message.collectAsState()
    val snackbarHostState = rememberMessageSnackbarHostState(message) { vm.consumeMessage() }
    var showEmptyConfirm by remember { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.trash_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { vm.load(node, uuid) }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.refresh))
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val r = state) {
                null -> LoadingBox()
                is ApiResult.Error -> ErrorBox(r.message) { vm.load(node, uuid) }
                is ApiResult.Success -> {
                    if (r.data.items.isEmpty()) {
                        EmptyBox(stringResource(R.string.trash_empty))
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(onClick = { showEmptyConfirm = true }) {
                                Text(stringResource(R.string.trash_empty_all))
                            }
                        }
                        LazyColumn(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(vertical = 4.dp),
                        ) {
                            items(r.data.items, key = { it.id }) { e ->
                                TrashRow(
                                    e,
                                    onRestore = { vm.restore(node, uuid, listOf(e.id)) },
                                    onDeleteForever = { vm.deleteForever(node, uuid, listOf(e.id)) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showEmptyConfirm) {
        AlertDialog(
            onDismissRequest = { showEmptyConfirm = false },
            title = { Text(stringResource(R.string.trash_empty_all)) },
            text = { Text(stringResource(R.string.trash_empty_confirm)) },
            confirmButton = {
                TextButton(onClick = { vm.emptyAll(node, uuid); showEmptyConfirm = false }) {
                    Text(stringResource(R.string.ok))
                }
            },
            dismissButton = { TextButton(onClick = { showEmptyConfirm = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun TrashRow(e: TrashEntry, onRestore: () -> Unit, onDeleteForever: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(e.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(
                    stringResource(R.string.trash_original_path, e.originalPath),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "${formatBytes(e.size)} · ${formatTrashTime(e.deletedAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onRestore) {
                Icon(Icons.Outlined.RestoreFromTrash, contentDescription = stringResource(R.string.trash_restore))
            }
            IconButton(onClick = onDeleteForever) {
                Icon(Icons.Outlined.DeleteForever, contentDescription = stringResource(R.string.trash_delete_forever))
            }
        }
    }
}

/** 回收站删除时间（unix 毫秒 → 本地格式）。 */
private fun formatTrashTime(ms: Long): String {
    if (ms <= 0) return "—"
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
}
