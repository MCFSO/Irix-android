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
import org.mcfso.irix.data.model.ContainerStats
import org.mcfso.irix.data.model.ImageItem
import org.mcfso.irix.data.model.NetworkItem
import org.mcfso.irix.data.model.NodeConfig
import org.mcfso.irix.data.model.VolumeItem

class ContainersViewModel(application: Application) : AndroidViewModel(application) {

    private val _info = MutableStateFlow<ApiResult<ContainerInfo>?>(null)
    val info: StateFlow<ApiResult<ContainerInfo>?> = _info.asStateFlow()

    private val _list = MutableStateFlow<ApiResult<List<ContainerListItem>>?>(null)
    val list: StateFlow<ApiResult<List<ContainerListItem>>?> = _list.asStateFlow()

    // 镜像 / 卷 / 网络（切页签时懒加载，null = 未加载）
    private val _images = MutableStateFlow<ApiResult<List<ImageItem>>?>(null)
    val images: StateFlow<ApiResult<List<ImageItem>>?> = _images.asStateFlow()

    private val _volumes = MutableStateFlow<ApiResult<List<VolumeItem>>?>(null)
    val volumes: StateFlow<ApiResult<List<VolumeItem>>?> = _volumes.asStateFlow()

    private val _networks = MutableStateFlow<ApiResult<List<NetworkItem>>?>(null)
    val networks: StateFlow<ApiResult<List<NetworkItem>>?> = _networks.asStateFlow()

    // 容器详情（日志 / 统计 / exec）
    private val _detailId = MutableStateFlow<String?>(null)
    val detailId: StateFlow<String?> = _detailId.asStateFlow()

    private val _detailLogs = MutableStateFlow<String>("")
    val detailLogs: StateFlow<String> = _detailLogs.asStateFlow()

    private val _detailStats = MutableStateFlow<ContainerStats?>(null)
    val detailStats: StateFlow<ContainerStats?> = _detailStats.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun load(node: NodeConfig, force: Boolean = false) {
        if (!force && _info.value is ApiResult.Success) return
        _info.value = null
        _list.value = null
        _images.value = null
        _volumes.value = null
        _networks.value = null
        viewModelScope.launch {
            val info = AppContainer.repository.containerInfo(node)
            _info.value = info
            if (info is ApiResult.Success && info.data.available) {
                _list.value = AppContainer.repository.containerList(node)
            }
        }
    }

    /** 懒加载资源页签数据（已加载过则跳过，force 强制刷新）。 */
    fun loadResources(node: NodeConfig, tab: ContainerTab, force: Boolean = false) {
        val already = when (tab) {
            ContainerTab.IMAGES -> _images.value is ApiResult.Success
            ContainerTab.VOLUMES -> _volumes.value is ApiResult.Success
            ContainerTab.NETWORKS -> _networks.value is ApiResult.Success
            ContainerTab.CONTAINERS -> return
        }
        if (!force && already) return
        viewModelScope.launch {
            when (tab) {
                ContainerTab.IMAGES -> _images.value = AppContainer.repository.imageList(node)
                ContainerTab.VOLUMES -> _volumes.value = AppContainer.repository.volumeList(node)
                ContainerTab.NETWORKS -> _networks.value = AppContainer.repository.networkList(node)
                ContainerTab.CONTAINERS -> Unit
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

    // ----- 镜像 / 卷 ------------------------------------------------------

    fun pullImage(node: NodeConfig, name: String) {
        _busy.value = true
        viewModelScope.launch {
            val r = AppContainer.repository.imagePull(node, name)
            _busy.value = false
            _message.value = if (r is ApiResult.Error) r.message
            else getApplication<Application>().getString(R.string.image_pulled)
            _images.value = AppContainer.repository.imageList(node)
        }
    }

    fun removeImage(node: NodeConfig, name: String) {
        viewModelScope.launch {
            val r = AppContainer.repository.imageRemove(node, name)
            _message.value = if (r is ApiResult.Error) r.message
            else getApplication<Application>().getString(R.string.deleted)
            _images.value = AppContainer.repository.imageList(node)
        }
    }

    fun removeVolume(node: NodeConfig, name: String) {
        viewModelScope.launch {
            val r = AppContainer.repository.volumeRemove(node, name)
            _message.value = if (r is ApiResult.Error) r.message
            else getApplication<Application>().getString(R.string.deleted)
            _volumes.value = AppContainer.repository.volumeList(node)
        }
    }

    // ----- 容器详情（日志 / 统计 / exec） ---------------------------------

    fun openDetail(node: NodeConfig, id: String) {
        _detailId.value = id
        _detailLogs.value = ""
        _detailStats.value = null
        viewModelScope.launch {
            val logs = AppContainer.repository.containerLogs(node, id)
            if (logs is ApiResult.Success) _detailLogs.value = logs.data
            val stats = AppContainer.repository.containerStats(node, id)
            if (stats is ApiResult.Success) _detailStats.value = stats.data
        }
    }

    fun refreshDetail(node: NodeConfig, id: String) = openDetail(node, id)

    fun closeDetail() {
        _detailId.value = null
    }

    fun exec(node: NodeConfig, id: String, command: String) {
        if (command.isBlank()) return
        viewModelScope.launch {
            val r = AppContainer.repository.containerExec(node, id, command)
            _message.value = when (r) {
                is ApiResult.Success -> r.data.ifEmpty { getApplication<Application>().getString(R.string.exec_done) }
                is ApiResult.Error -> r.message
            }
            // exec 后刷新日志（命令输出通常立即产生）
            val logs = AppContainer.repository.containerLogs(node, id)
            if (logs is ApiResult.Success) _detailLogs.value = logs.data
        }
    }

    fun consumeMessage() = _message.update { null }
}

enum class ContainerAction { START, STOP, RESTART, KILL }

enum class ContainerTab { CONTAINERS, IMAGES, VOLUMES, NETWORKS }
