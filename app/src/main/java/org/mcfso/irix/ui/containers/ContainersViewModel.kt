package org.mcfso.irix.ui.containers

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
import org.mcfso.irix.data.model.ContainerCreateBody
import org.mcfso.irix.data.model.ContainerInfo
import org.mcfso.irix.data.model.ContainerListItem
import org.mcfso.irix.data.model.NodeConfig

class ContainersViewModel(application: Application) : AndroidViewModel(application) {

    private val _info = MutableStateFlow<ApiResult<ContainerInfo>?>(null)
    val info: StateFlow<ApiResult<ContainerInfo>?> = _info.asStateFlow()

    private val _list = MutableStateFlow<ApiResult<List<ContainerListItem>>?>(null)
    val list: StateFlow<ApiResult<List<ContainerListItem>>?> = _list.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun load(node: NodeConfig, force: Boolean = false) {
        if (!force && _info.value is ApiResult.Success) return
        _info.value = null
        _list.value = null
        viewModelScope.launch {
            val info = AppContainer.repository.containerInfo(node)
            _info.value = info
            if (info is ApiResult.Success && info.data.available) {
                _list.value = AppContainer.repository.containerList(node)
            }
        }
    }

    fun control(node: NodeConfig, id: String, action: ContainerAction) {
        _busy.value = true
        viewModelScope.launch {
            val r: ApiResult<*> = when (action) {
                ContainerAction.START -> AppContainer.repository.containerStart(node, id)
                ContainerAction.STOP -> AppContainer.repository.containerStop(node, id)
                ContainerAction.RESTART -> AppContainer.repository.containerRestart(node, id)
                ContainerAction.KILL -> AppContainer.repository.containerKill(node, id)
            }
            _busy.value = false
            _message.value = if (r is ApiResult.Error) r.message else null
            _list.value = AppContainer.repository.containerList(node)
        }
    }

    fun remove(node: NodeConfig, id: String, force: Boolean) {
        _busy.value = true
        viewModelScope.launch {
            val r = AppContainer.repository.containerRemove(node, id, force)
            _busy.value = false
            _message.value = if (r is ApiResult.Error) r.message
            else getApplication<Application>().getString(R.string.deleted)
            _list.value = AppContainer.repository.containerList(node)
        }
    }

    fun create(node: NodeConfig, body: ContainerCreateBody) {
        _busy.value = true
        viewModelScope.launch {
            val r = AppContainer.repository.containerCreate(node, body)
            _busy.value = false
            _message.value = when (r) {
                is ApiResult.Success -> getApplication<Application>().getString(R.string.container_created)
                is ApiResult.Error -> r.message
            }
            _list.value = AppContainer.repository.containerList(node)
        }
    }

    fun consumeMessage() = _message.update { null }
}

enum class ContainerAction { START, STOP, RESTART, KILL }
