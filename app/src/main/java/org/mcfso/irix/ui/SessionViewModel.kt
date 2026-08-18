package org.mcfso.irix.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.mcfso.irix.AppContainer
import org.mcfso.irix.data.api.ApiResult
import org.mcfso.irix.data.model.NodeConfig
import org.mcfso.irix.data.model.OverviewResponse

/** 全局会话 ViewModel：当前选中节点 + 概览刷新状态。 */
class SessionViewModel : ViewModel() {

    val nodes: StateFlow<List<NodeConfig>> =
        AppContainer.nodeStore.nodes.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val selected: StateFlow<NodeConfig?> =
        AppContainer.nodeStore.selectedNode.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _overview = MutableStateFlow<ApiResult<OverviewResponse>?>(null)
    val overview: StateFlow<ApiResult<OverviewResponse>?> = _overview.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    private var pollJob: Job? = null

    fun consumeToast() { _toast.value = null }

    fun selectNode(id: String) {
        viewModelScope.launch { AppContainer.nodeStore.select(id) }
    }

    /** 拉取概览。force=true 即使已有数据也刷新。 */
    fun refreshOverview(force: Boolean = false) {
        val node = selected.value ?: return
        if (!force && _overview.value is ApiResult.Success) return
        _refreshing.value = true
        viewModelScope.launch {
            val result = AppContainer.repository.overview(node)
            _overview.value = result
            _refreshing.value = false
            if (result is ApiResult.Error) _toast.value = result.message
        }
    }

    /** 启动 15s 周期轮询概览（对齐服务端文档约定的多机模式监控周期）。
     *  轮询错误不弹提示（概览页 ErrorBox 会展示），避免每 15s 一次 Snackbar 刷屏。 */
    fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (true) {
                val node = selected.value
                if (node != null) {
                    _overview.value = AppContainer.repository.overview(node)
                }
                delay(15_000)
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    override fun onCleared() {
        super.onCleared()
        stopPolling()
    }
}
