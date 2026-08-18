package org.mcfso.irix.ui.instances

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.mcfso.irix.AppContainer
import org.mcfso.irix.data.api.ApiResult
import org.mcfso.irix.data.model.InstanceConfig
import org.mcfso.irix.data.model.InstanceDetail
import org.mcfso.irix.data.model.NodeConfig

class InstanceDetailViewModel(application: Application) : AndroidViewModel(application) {

    private val _detail = MutableStateFlow<ApiResult<InstanceDetail>?>(null)
    val detail: StateFlow<ApiResult<InstanceDetail>?> = _detail.asStateFlow()

    private val _log = MutableStateFlow<String>("")
    val log: StateFlow<String> = _log.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private var pollJob: Job? = null
    private var logJob: Job? = null

    fun load(node: NodeConfig, uuid: String, poll: Boolean = true) {
        viewModelScope.launch {
            val r = AppContainer.repository.instanceDetail(node, uuid)
            _detail.value = r
        }
        if (poll) startPolling(node, uuid)
    }

    private fun startPolling(node: NodeConfig, uuid: String) {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (true) {
                delay(3000)
                val r = AppContainer.repository.instanceDetail(node, uuid)
                _detail.value = r
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    fun loadLog(node: NodeConfig, uuid: String, size: Int = 128, auto: Boolean = false) {
        if (auto) {
            logJob?.cancel()
            logJob = viewModelScope.launch {
                while (true) {
                    val r = AppContainer.repository.instanceOutputLog(node, uuid, size)
                    if (r is ApiResult.Success) _log.value = r.data
                    delay(3000)
                }
            }
        } else {
            viewModelScope.launch {
                val r = AppContainer.repository.instanceOutputLog(node, uuid, size)
                if (r is ApiResult.Success) _log.value = r.data
                else if (r is ApiResult.Error) _message.value = r.message
            }
        }
    }

    fun stopLog() {
        logJob?.cancel()
        logJob = null
    }

    fun command(node: NodeConfig, uuid: String, cmd: String) {
        if (cmd.isBlank()) return
        _busy.value = true
        viewModelScope.launch {
            val r = AppContainer.repository.instanceCommand(node, uuid, cmd)
            _busy.value = false
            if (r is ApiResult.Error) _message.value = r.message
            else loadLog(node, uuid)
        }
    }

    fun control(node: NodeConfig, uuid: String, action: InstanceAction) {
        _busy.value = true
        viewModelScope.launch {
            val r: ApiResult<*> = when (action) {
                InstanceAction.START -> AppContainer.repository.instanceStart(node, uuid)
                InstanceAction.STOP -> AppContainer.repository.instanceStop(node, uuid)
                InstanceAction.RESTART -> AppContainer.repository.instanceRestart(node, uuid)
                InstanceAction.KILL -> AppContainer.repository.instanceKill(node, uuid)
            }
            _busy.value = false
            _message.value = if (r is ApiResult.Error) r.message else null
            load(node, uuid, poll = false)
        }
    }

    fun update(node: NodeConfig, uuid: String, config: InstanceConfig) {
        _busy.value = true
        viewModelScope.launch {
            val r = AppContainer.repository.instanceUpdate(node, uuid, config)
            _busy.value = false
            _message.value = if (r is ApiResult.Error) r.message
            else getApplication<Application>().getString(org.mcfso.irix.R.string.config_saved)
            load(node, uuid, poll = false)
        }
    }

    fun consumeMessage() = _message.update { null }
}
