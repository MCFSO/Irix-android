package org.mcfso.irix.ui.files

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.mcfso.irix.AppContainer
import org.mcfso.irix.R
import org.mcfso.irix.data.api.ApiResult
import org.mcfso.irix.data.model.FileEntry
import org.mcfso.irix.data.model.FileListResponse
import org.mcfso.irix.data.model.NodeConfig

class FilesViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(FilesState())
    val state: StateFlow<FilesState> = _state.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _transferring = MutableStateFlow(false)
    val transferring: StateFlow<Boolean> = _transferring.asStateFlow()

    /** pathStack[0] = "/"（cwd 根）。navigate 进入子目录时 push。 */
    fun open(node: NodeConfig, uuid: String) {
        _state.update { it.copy(node = node, uuid = uuid, pathStack = listOf("/")) }
        list(node, uuid, "/")
    }

    fun navigate(node: NodeConfig, uuid: String, dir: String) {
        val current = _state.value.pathStack
        val next = current + (current.last().trimEnd('/') + "/" + dir.trimStart('/')).let { it.replace("//", "/") }
        _state.update { it.copy(pathStack = next) }
        list(node, uuid, next.last())
    }

    fun backTo(node: NodeConfig, uuid: String, index: Int) {
        val stack = _state.value.pathStack
        if (index < 0 || index >= stack.size) return
        val newStack = stack.subList(0, index + 1).toList()
        _state.update { it.copy(pathStack = newStack) }
        list(node, uuid, newStack.last())
    }

    fun refresh(node: NodeConfig, uuid: String) {
        list(node, uuid, _state.value.pathStack.last())
    }

    private fun list(node: NodeConfig, uuid: String, target: String) {
        _state.update { it.copy(list = null, loading = true, current = target) }
        viewModelScope.launch {
            val r = AppContainer.repository.fileList(node, uuid, target)
            _state.update { it.copy(list = r, loading = false) }
        }
    }

    fun read(node: NodeConfig, uuid: String, target: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            val r = AppContainer.repository.fileRead(node, uuid, target)
            onResult(if (r is ApiResult.Success) r.data else null)
            if (r is ApiResult.Error) _message.value = r.message
        }
    }

    fun write(node: NodeConfig, uuid: String, target: String, text: String) {
        viewModelScope.launch {
            val r = AppContainer.repository.fileWrite(node, uuid, target, text)
            _message.value = when (r) {
                is ApiResult.Success -> getApplication<Application>().getString(R.string.saved)
                is ApiResult.Error -> r.message
            }
            refresh(node, uuid)
        }
    }

    fun mkdir(node: NodeConfig, uuid: String, name: String) {
        val target = joinPath(_state.value.pathStack.last(), name)
        viewModelScope.launch {
            val r = AppContainer.repository.fileMkdir(node, uuid, target)
            _message.value = if (r is ApiResult.Error) r.message else null
            refresh(node, uuid)
        }
    }

    fun touch(node: NodeConfig, uuid: String, name: String) {
        val target = joinPath(_state.value.pathStack.last(), name)
        viewModelScope.launch {
            val r = AppContainer.repository.fileTouch(node, uuid, target)
            _message.value = if (r is ApiResult.Error) r.message else null
            refresh(node, uuid)
        }
    }

    fun delete(node: NodeConfig, uuid: String, targets: List<String>) {
        val abs = targets.map { joinPath(_state.value.pathStack.last(), it) }
        viewModelScope.launch {
            val r = AppContainer.repository.fileDelete(node, uuid, abs)
            _message.value = if (r is ApiResult.Error) r.message else getApplication<Application>().getString(R.string.deleted)
            refresh(node, uuid)
        }
    }

    fun rename(node: NodeConfig, uuid: String, oldName: String, newName: String) {
        val base = _state.value.pathStack.last()
        val src = joinPath(base, oldName)
        val dst = joinPath(base, newName)
        viewModelScope.launch {
            val r = AppContainer.repository.fileMove(node, uuid, listOf(src to dst))
            _message.value = if (r is ApiResult.Error) r.message else getApplication<Application>().getString(R.string.renamed)
            refresh(node, uuid)
        }
    }

    fun downloadTicket(
        context: android.content.Context,
        node: NodeConfig,
        uuid: String,
        fileName: String,
    ) {
        viewModelScope.launch {
            val r = AppContainer.repository.fileDownloadTicket(node, uuid, fileName)
            when (r) {
                is ApiResult.Success -> {
                    val t = r.data
                    val url = "${schemeOf(node)}://${t.addr}/${t.password}/${Uri.encode(fileName)}"
                    val id = AppContainer.fileTransfer.enqueueDownload(context, url, fileName)
                    _message.value = if (id != null) {
                        getApplication<Application>().getString(R.string.download_started)
                    } else {
                        getApplication<Application>().getString(R.string.download_enqueue_failed)
                    }
                }
                is ApiResult.Error -> _message.value = r.message
            }
        }
    }

    /** 从系统文件选择器上传：查文件名 → 拷到缓存（IO 线程）→ multipart 直传。 */
    fun upload(
        context: android.content.Context,
        node: NodeConfig,
        uuid: String,
        uri: Uri,
    ) {
        val dir = _state.value.pathStack.last()
        if (_transferring.value) return
        _transferring.value = true
        viewModelScope.launch {
            try {
                // 解析显示名（IO）
                val name = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                        val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
                    } ?: uri.lastPathSegment ?: "upload.bin"
                }
                // 拷贝到应用缓存目录（IO），避免大文件在主线程/直读 Uri 失败
                val cacheFile = java.io.File(context.cacheDir, name)
                val copyOk = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            cacheFile.outputStream().use { out -> input.copyTo(out) }
                        } != null
                    } catch (e: Exception) {
                        false
                    }
                }
                if (!copyOk) {
                    _message.value = getApplication<Application>().getString(R.string.read_picker_failed)
                    return@launch
                }
                // 申请上传票据 → 直连上传
                val ticket = AppContainer.repository.fileUploadTicket(node, uuid, dir)
                when (ticket) {
                    is ApiResult.Success -> {
                        val t = ticket.data
                        val url = "${schemeOf(node)}://${t.addr}/upload/${t.password}"
                        val (ok, msg) = AppContainer.fileTransfer.upload(url, cacheFile)
                        _message.value = if (ok) {
                            getApplication<Application>().getString(R.string.upload_done, name)
                        } else {
                            msg ?: getApplication<Application>().getString(R.string.upload_failed)
                        }
                        refresh(node, uuid)
                    }
                    is ApiResult.Error -> _message.value = ticket.message
                }
                cacheFile.delete()
            } finally {
                _transferring.value = false
            }
        }
    }

    fun consumeMessage() = _message.update { null }

    /** 直连传输协议跟随节点地址：https 节点走 https 票据通道。 */
    private fun schemeOf(node: NodeConfig): String =
        if (node.address.startsWith("https://", ignoreCase = true)) "https" else "http"

    private fun joinPath(base: String, name: String): String {
        val b = base.trimEnd('/')
        return if (name.startsWith("/")) name else "$b/$name".replace("//", "/")
    }
}

data class FilesState(
    val node: NodeConfig? = null,
    val uuid: String = "",
    val pathStack: List<String> = listOf("/"),
    val current: String = "/",
    val list: ApiResult<FileListResponse>? = null,
    val loading: Boolean = false,
)
