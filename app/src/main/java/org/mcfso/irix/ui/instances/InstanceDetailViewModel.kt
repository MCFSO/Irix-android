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
import org.mcfso.irix.data.model.InstanceStats
import org.mcfso.irix.data.model.JavaRuntimeResponse
import org.mcfso.irix.data.model.NodeConfig
import org.mcfso.irix.data.model.TaskProgress

class InstanceDetailViewModel(application: Application) : AndroidViewModel(application) {

    private val _detail = MutableStateFlow<ApiResult<InstanceDetail>?>(null)
    val detail: StateFlow<ApiResult<InstanceDetail>?> = _detail.asStateFlow()

    private val _log = MutableStateFlow<String>("")
    val log: StateFlow<String> = _log.asStateFlow()

    private val _stats = MutableStateFlow<InstanceStats?>(null)
    val stats: StateFlow<InstanceStats?> = _stats.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    // 工具 tab 状态：Java 运行时 / JDK 安装进度 / 核心下载进度
    private val _java = MutableStateFlow<ApiResult<JavaRuntimeResponse>?>(null)
    val java: StateFlow<ApiResult<JavaRuntimeResponse>?> = _java.asStateFlow()

    private val _javaJob = MutableStateFlow<TaskProgress?>(null)
    val javaJob: StateFlow<TaskProgress?> = _javaJob.asStateFlow()

    private val _coreJob = MutableStateFlow<TaskProgress?>(null)
    val coreJob: StateFlow<TaskProgress?> = _coreJob.asStateFlow()

    private var pollJob: Job? = null
    private var logJob: Job? = null
    private var javaJobPoll: Job? = null
    private var coreJobPoll: Job? = null

    private var currentUuid: String = ""

    fun load(node: NodeConfig, uuid: String, poll: Boolean = true) {
        currentUuid = uuid
        viewModelScope.launch {
            val r = AppContainer.repository.instanceDetail(node, uuid)
            _detail.value = r
            // 同一轮拉取运行指标，避免单独轮询
            fetchStats(node, uuid)
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
                fetchStats(node, uuid)
            }
        }
    }

    private suspend fun fetchStats(node: NodeConfig, uuid: String) {
        val r = AppContainer.repository.instanceStats(node, uuid)
        if (r is ApiResult.Success) _stats.value = r.data
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    fun loadLog(node: NodeConfig, uuid: String, size: Int = 500, auto: Boolean = false) {
        if (auto) {
            logJob?.cancel()
            logJob = viewModelScope.launch {
                while (true) {
                    fetchLog(node, uuid, size)
                    delay(3000)
                }
            }
        } else {
            viewModelScope.launch { fetchLog(node, uuid, size) }
        }
    }

    /** 优先走持久化日志接口（GET /api/instance/logs）；失败回退 outputlog，兼容旧节点。 */
    private suspend fun fetchLog(node: NodeConfig, uuid: String, size: Int) {
        val r = AppContainer.repository.instanceLogs(node, uuid, tail = size)
        when (r) {
            is ApiResult.Success -> _log.value = r.data
            is ApiResult.Error -> {
                val fallback = AppContainer.repository.instanceOutputLog(node, uuid, size)
                if (fallback is ApiResult.Success) _log.value = fallback.data
                else _message.value = r.message
            }
        }
    }

    fun clearLog(node: NodeConfig, uuid: String) {
        viewModelScope.launch {
            val r = AppContainer.repository.instanceLogsClear(node, uuid)
            _message.value = when (r) {
                is ApiResult.Success -> {
                    _log.value = ""
                    getApplication<Application>().getString(org.mcfso.irix.R.string.log_cleared)
                }
                is ApiResult.Error -> r.message
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

    // ----- 工具 tab：Java 运行时 / JDK 安装 -------------------------------

    fun loadJava(node: NodeConfig) {
        viewModelScope.launch {
            _java.value = AppContainer.repository.runtimeJava(node)
        }
    }

    fun installJava(node: NodeConfig, major: Int) {
        if (major < 8 || major > 30) return
        _busy.value = true
        viewModelScope.launch {
            val r = AppContainer.repository.javaInstall(node, major)
            _busy.value = false
            when (r) {
                is ApiResult.Success -> pollJavaJob(node, r.data.jobId)
                is ApiResult.Error -> _message.value = r.message
            }
        }
    }

    private fun pollJavaJob(node: NodeConfig, jobId: String) {
        javaJobPoll?.cancel()
        javaJobPoll = viewModelScope.launch {
            while (true) {
                delay(1500)
                val r = AppContainer.repository.javaInstallProgress(node, jobId)
                if (r is ApiResult.Success) {
                    _javaJob.value = r.data
                    if (r.data.status == "done" || r.data.status == "failed") break
                } else break
            }
        }
    }

    fun uninstallJava(node: NodeConfig, major: Int) {
        viewModelScope.launch {
            val r = AppContainer.repository.javaUninstall(node, major)
            _message.value = if (r is ApiResult.Error) r.message
            else getApplication<Application>().getString(org.mcfso.irix.R.string.jdk_uninstalled)
            if (r is ApiResult.Success) loadJava(node)
        }
    }

    // ----- 工具 tab：核心下载 ---------------------------------------------

    fun downloadCore(node: NodeConfig, uuid: String, url: String, fileName: String, sha512: String) {
        _busy.value = true
        viewModelScope.launch {
            val r = AppContainer.repository.downloadCore(node, uuid, url, fileName, sha512)
            _busy.value = false
            when (r) {
                is ApiResult.Success -> pollCoreJob(node, r.data.jobId)
                is ApiResult.Error -> _message.value = r.message
            }
        }
    }

    private fun pollCoreJob(node: NodeConfig, jobId: String) {
        coreJobPoll?.cancel()
        coreJobPoll = viewModelScope.launch {
            while (true) {
                delay(1500)
                val r = AppContainer.repository.downloadCoreProgress(node, jobId)
                if (r is ApiResult.Success) {
                    _coreJob.value = r.data
                    if (r.data.status == "done" || r.data.status == "failed") break
                } else break
            }
        }
    }

    fun consumeMessage() = _message.update { null }
}
