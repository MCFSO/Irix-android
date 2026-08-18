package org.mcfso.irix.data

import org.mcfso.irix.data.api.ApiFactory
import org.mcfso.irix.data.api.ApiResult
import org.mcfso.irix.data.api.NodeApi
import org.mcfso.irix.data.api.apiCall
import org.mcfso.irix.data.model.BuildProgress
import org.mcfso.irix.data.model.ContainerCloneBody
import org.mcfso.irix.data.model.ContainerCreateBody
import org.mcfso.irix.data.model.ContainerCreateResult
import org.mcfso.irix.data.model.ContainerInfo
import org.mcfso.irix.data.model.ContainerLimitsBody
import org.mcfso.irix.data.model.ContainerListItem
import org.mcfso.irix.data.model.ContainerStats
import org.mcfso.irix.data.model.CreateInstanceResponse
import org.mcfso.irix.data.model.DeleteInstanceBody
import org.mcfso.irix.data.model.DownloadTicketResponse
import org.mcfso.irix.data.model.FileCompressBody
import org.mcfso.irix.data.model.FileDeleteBody
import org.mcfso.irix.data.model.FileListResponse
import org.mcfso.irix.data.model.FileMkdirBody
import org.mcfso.irix.data.model.FileMoveCopyBody
import org.mcfso.irix.data.model.FileReadBody
import org.mcfso.irix.data.model.FileWriteBody
import org.mcfso.irix.data.model.ImageBuildBody
import org.mcfso.irix.data.model.ImageItem
import org.mcfso.irix.data.model.ImagePullBody
import org.mcfso.irix.data.model.InstanceConfig
import org.mcfso.irix.data.model.InstanceDetail
import org.mcfso.irix.data.model.InstanceListResponse
import org.mcfso.irix.data.model.LoadStatus
import org.mcfso.irix.data.model.NetworkItem
import org.mcfso.irix.data.model.NodeConfig
import org.mcfso.irix.data.model.OverviewResponse
import org.mcfso.irix.data.model.UploadTicketResponse
import org.mcfso.irix.data.model.UuidResult
import org.mcfso.irix.data.model.VolumeItem

/**
 * 节点 API 仓库：以指定节点（或当前选中节点）发起调用。
 * 每次 API 调用基于节点地址/密钥构建 Retrofit 客户端；客户端已设连接超时。
 */
class NodeRepository {

    private fun api(node: NodeConfig): NodeApi = ApiFactory.create(node)

    // ----- 概览 / 负载 -----------------------------------------------------

    suspend fun overview(node: NodeConfig): ApiResult<OverviewResponse> =
        apiCall { api(node).overview() }

    suspend fun load(node: NodeConfig): ApiResult<LoadStatus> =
        apiCall { api(node).load() }

    // ----- 实例管理 -------------------------------------------------------

    suspend fun instanceList(
        node: NodeConfig,
        page: Int = 1,
        pageSize: Int = 100,
        name: String? = null,
        status: String? = null,
    ): ApiResult<InstanceListResponse> =
        apiCall { api(node).instanceList(null, page, pageSize, name, status) }

    suspend fun instanceDetail(node: NodeConfig, uuid: String): ApiResult<InstanceDetail> =
        apiCall { api(node).instanceDetail(uuid) }

    suspend fun instanceCreate(node: NodeConfig, config: InstanceConfig): ApiResult<CreateInstanceResponse> =
        apiCall { api(node).instanceCreate(null, config) }

    suspend fun instanceUpdate(node: NodeConfig, uuid: String, config: InstanceConfig): ApiResult<UuidResult> =
        apiCall { api(node).instanceUpdate(uuid, null, config) }

    suspend fun instanceDelete(node: NodeConfig, uuids: List<String>, deleteFile: Boolean): ApiResult<List<String>> =
        apiCall { api(node).instanceDelete(null, DeleteInstanceBody(uuids, deleteFile)) }

    suspend fun instanceStart(node: NodeConfig, uuid: String): ApiResult<UuidResult> =
        apiCall { api(node).instanceStart(uuid) }

    suspend fun instanceStop(node: NodeConfig, uuid: String): ApiResult<UuidResult> =
        apiCall { api(node).instanceStop(uuid) }

    suspend fun instanceRestart(node: NodeConfig, uuid: String): ApiResult<UuidResult> =
        apiCall { api(node).instanceRestart(uuid) }

    suspend fun instanceKill(node: NodeConfig, uuid: String): ApiResult<UuidResult> =
        apiCall { api(node).instanceKill(uuid) }

    suspend fun instanceCommand(node: NodeConfig, uuid: String, command: String): ApiResult<UuidResult> =
        apiCall { api(node).instanceCommand(uuid, command) }

    suspend fun instanceOutputLog(node: NodeConfig, uuid: String, size: Int? = null): ApiResult<String> =
        apiCall { api(node).instanceOutputLog(uuid, size) }

    // ----- 文件管理 -------------------------------------------------------

    suspend fun fileList(node: NodeConfig, uuid: String, target: String, page: Int = 1): ApiResult<FileListResponse> =
        apiCall { api(node).fileList(uuid, target, page) }

    suspend fun fileRead(node: NodeConfig, uuid: String, target: String): ApiResult<String> =
        apiCall { api(node).fileRead(uuid, FileReadBody(target)) }

    suspend fun fileWrite(node: NodeConfig, uuid: String, target: String, text: String): ApiResult<Boolean> =
        apiCall { api(node).fileWrite(uuid, FileWriteBody(target, text)) }

    suspend fun fileDelete(node: NodeConfig, uuid: String, targets: List<String>): ApiResult<Boolean> =
        apiCall { api(node).fileDelete(uuid, FileDeleteBody(targets)) }

    suspend fun fileMove(node: NodeConfig, uuid: String, pairs: List<Pair<String, String>>): ApiResult<Boolean> =
        apiCall { api(node).fileMove(uuid, FileMoveCopyBody(pairs.map { it.toList() })) }

    suspend fun fileCopy(node: NodeConfig, uuid: String, pairs: List<Pair<String, String>>): ApiResult<Boolean> =
        apiCall { api(node).fileCopy(uuid, FileMoveCopyBody(pairs.map { it.toList() })) }

    suspend fun fileCompress(node: NodeConfig, uuid: String, source: String, targets: List<String>): ApiResult<Boolean> =
        apiCall { api(node).fileCompress(uuid, FileCompressBody(type = 1, source = source, targets = targets)) }

    suspend fun fileDecompress(node: NodeConfig, uuid: String, source: String, dest: String): ApiResult<Boolean> =
        apiCall { api(node).fileCompress(uuid, FileCompressBody(type = 2, source = source, targets = listOf(dest))) }

    suspend fun fileMkdir(node: NodeConfig, uuid: String, target: String): ApiResult<Boolean> =
        apiCall { api(node).fileMkdir(uuid, FileMkdirBody(target)) }

    suspend fun fileTouch(node: NodeConfig, uuid: String, target: String): ApiResult<Boolean> =
        apiCall { api(node).fileTouch(uuid, FileMkdirBody(target)) }

    suspend fun fileDownloadTicket(node: NodeConfig, uuid: String, fileName: String): ApiResult<DownloadTicketResponse> =
        apiCall { api(node).fileDownloadTicket(uuid, fileName) }

    suspend fun fileUploadTicket(node: NodeConfig, uuid: String, uploadDir: String): ApiResult<UploadTicketResponse> =
        apiCall { api(node).fileUploadTicket(uuid, uploadDir) }

    // ----- 容器环境 -------------------------------------------------------

    suspend fun containerInfo(node: NodeConfig): ApiResult<ContainerInfo> =
        apiCall { api(node).containerInfo() }

    suspend fun containerList(node: NodeConfig): ApiResult<List<ContainerListItem>> =
        apiCall { api(node).containerList() }

    suspend fun containerCreate(node: NodeConfig, body: ContainerCreateBody): ApiResult<ContainerCreateResult> =
        apiCall { api(node).containerCreate(body) }

    suspend fun containerStart(node: NodeConfig, id: String): ApiResult<Boolean> =
        apiCall { api(node).containerStart(id) }

    suspend fun containerStop(node: NodeConfig, id: String): ApiResult<Boolean> =
        apiCall { api(node).containerStop(id) }

    suspend fun containerRestart(node: NodeConfig, id: String): ApiResult<Boolean> =
        apiCall { api(node).containerRestart(id) }

    suspend fun containerKill(node: NodeConfig, id: String): ApiResult<Boolean> =
        apiCall { api(node).containerKill(id) }

    suspend fun containerRemove(node: NodeConfig, id: String, force: Boolean): ApiResult<Boolean> =
        apiCall { api(node).containerRemove(id, if (force) 1 else 0) }

    suspend fun containerLogs(node: NodeConfig, id: String, tail: Int = 200): ApiResult<String> =
        apiCall { api(node).containerLogs(id, tail) }

    suspend fun containerExec(node: NodeConfig, id: String, command: String): ApiResult<String> =
        apiCall { api(node).containerExec(id, mapOf("command" to command)) }

    suspend fun containerStats(node: NodeConfig, id: String): ApiResult<ContainerStats> =
        apiCall { api(node).containerStats(id) }

    suspend fun containerClone(node: NodeConfig, id: String, name: String): ApiResult<ContainerCreateResult> =
        apiCall { api(node).containerClone(id, ContainerCloneBody(name)) }

    suspend fun containerLimits(node: NodeConfig, id: String, memoryMb: Int?, cpus: Double?): ApiResult<Boolean> =
        apiCall { api(node).containerLimits(id, ContainerLimitsBody(memoryMb, cpus)) }

    suspend fun imageList(node: NodeConfig): ApiResult<List<ImageItem>> =
        apiCall { api(node).imageList() }

    suspend fun imagePull(node: NodeConfig, name: String): ApiResult<Boolean> =
        apiCall { api(node).imagePull(ImagePullBody(name)) }

    suspend fun imageBuild(node: NodeConfig, dockerfile: String, name: String, tag: String): ApiResult<Map<String, String>> =
        apiCall { api(node).imageBuild(ImageBuildBody(dockerfile, name, tag)) }

    suspend fun imageBuildProgress(node: NodeConfig, jobId: String): ApiResult<BuildProgress> =
        apiCall { api(node).imageBuildProgress(jobId) }

    suspend fun imageRemove(node: NodeConfig, name: String): ApiResult<Boolean> =
        apiCall { api(node).imageRemove(name) }

    suspend fun volumeList(node: NodeConfig): ApiResult<List<VolumeItem>> =
        apiCall { api(node).volumeList() }

    suspend fun volumeRemove(node: NodeConfig, name: String): ApiResult<Boolean> =
        apiCall { api(node).volumeRemove(name) }

    suspend fun networkList(node: NodeConfig): ApiResult<List<NetworkItem>> =
        apiCall { api(node).networkList() }
}
