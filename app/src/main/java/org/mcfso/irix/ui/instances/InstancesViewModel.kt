package org.mcfso.irix.ui.instances

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.mcfso.irix.AppContainer
import org.mcfso.irix.R
import org.mcfso.irix.data.api.ApiResult
import org.mcfso.irix.data.model.InstanceConfig
import org.mcfso.irix.data.model.InstanceDetail
import org.mcfso.irix.data.model.InstanceListResponse
import org.mcfso.irix.data.model.NodeConfig

class InstancesViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(InstancesState())
    val state: StateFlow<InstancesState> = _state.asStateFlow()

    fun load(node: NodeConfig, force: Boolean = false) {
        if (!force && _state.value.list is ApiResult.Success) return
        _state.update { it.copy(list = null, loading = true) }
        viewModelScope.launch {
            val result = AppContainer.repository.instanceList(node)
            _state.update { it.copy(list = result, loading = false) }
        }
    }

    fun control(node: NodeConfig, uuid: String, action: InstanceAction) {
        viewModelScope.launch {
            val r: ApiResult<*> = when (action) {
                InstanceAction.START -> AppContainer.repository.instanceStart(node, uuid)
                InstanceAction.STOP -> AppContainer.repository.instanceStop(node, uuid)
                InstanceAction.RESTART -> AppContainer.repository.instanceRestart(node, uuid)
                InstanceAction.KILL -> AppContainer.repository.instanceKill(node, uuid)
            }
            _state.update { it.copy(lastMessage = if (r is ApiResult.Error) r.message else null) }
            load(node, force = true)
        }
    }

    fun create(node: NodeConfig, config: InstanceConfig) {
        viewModelScope.launch {
            val r = AppContainer.repository.instanceCreate(node, config)
            _state.update {
                it.copy(lastMessage = if (r is ApiResult.Error) r.message else getApplication<Application>().getString(R.string.instance_created))
            }
            load(node, force = true)
        }
    }

    /** 导入节点上的目录为实例（POST /api/instance/import）。 */
    fun importInstance(node: NodeConfig, path: String, nickname: String) {
        viewModelScope.launch {
            val r = AppContainer.repository.instanceImport(node, path, nickname)
            _state.update {
                it.copy(lastMessage = if (r is ApiResult.Error) r.message else getApplication<Application>().getString(R.string.instance_imported))
            }
            load(node, force = true)
        }
    }

    fun delete(node: NodeConfig, uuids: List<String>, deleteFile: Boolean) {
        viewModelScope.launch {
            val r = AppContainer.repository.instanceDelete(node, uuids, deleteFile)
            val msg = when (r) {
                is ApiResult.Error -> r.message
                is ApiResult.Success -> getApplication<Application>().getString(R.string.instances_deleted, r.data.size)
            }
            _state.update { it.copy(lastMessage = msg) }
            load(node, force = true)
        }
    }

    fun consumeMessage() = _state.update { it.copy(lastMessage = null) }
}

data class InstancesState(
    val list: ApiResult<InstanceListResponse>? = null,
    val loading: Boolean = false,
    val lastMessage: String? = null,
)

enum class InstanceAction { START, STOP, RESTART, KILL }
