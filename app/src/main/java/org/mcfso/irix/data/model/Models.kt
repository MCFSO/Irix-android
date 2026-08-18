package org.mcfso.irix.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * MCSM 风格的统一响应体。status != 200 时 data 为错误消息。
 */
@Serializable
data class ApiResponse<T>(
    val status: Int,
    val data: T? = null,
    val time: Long = 0L,
)

// ---------------------------------------------------------------------------
// 概览 / 资源监控
// ---------------------------------------------------------------------------

@Serializable
data class OverviewResponse(
    val version: String = "",
    @SerialName("specifiedDaemonVersion") val specifiedVersion: String = "",
    val process: ProcessInfo = ProcessInfo(),
    val system: OverviewSystem = OverviewSystem(),
    val remote: List<RemoteDaemon> = emptyList(),
    @SerialName("remoteCount") val remoteCount: RemoteCount = RemoteCount(),
)

@Serializable
data class ProcessInfo(
    val cpu: Double = 0.0,
    val memory: Long = 0L,
    val cwd: String = "",
)

@Serializable
data class OverviewSystem(
    val type: String = "",
    val platform: String = "",
    val hostname: String = "",
    val release: String = "",
    val version: String = "",
    val uptime: Long = 0L,
    val totalmem: Long = 0L,
    val freemem: Long = 0L,
    @SerialName("cpuUsage") val cpuUsage: Double = 0.0,
    @SerialName("memUsage") val memUsage: Double = 0.0,
    val diskusage: Double = 0.0,
    val disktotal: Long = 0L,
    val diskused: Long = 0L,
    @SerialName("networkDownload") val networkDownload: Double = 0.0,
    @SerialName("networkUpload") val networkUpload: Double = 0.0,
    val node: String = "",
    val time: Long = 0L,
    val cwd: String = "",
)

@Serializable
data class RemoteCount(
    val available: Int = 0,
    val total: Int = 0,
)

@Serializable
data class RemoteDaemon(
    val uuid: String = "",
    val ip: String = "",
    val port: Int = 0,
    val remarks: String = "",
    val version: String = "",
    val available: Boolean = false,
    val instance: RemoteInstance = RemoteInstance(),
    val system: OverviewSystem = OverviewSystem(),
)

@Serializable
data class RemoteInstance(
    val running: Int = 0,
    val total: Int = 0,
)

@Serializable
data class LoadStatus(
    val state: String = "normal",
    val since: Long = 0L,
    val gomaxprocs: Int = 0,
    @SerialName("gcPercent") val gcPercent: Int = 0,
    @SerialName("cpuBusy") val cpuBusy: Double = 0.0,
    val goroutines: Int = 0,
    @SerialName("heapAlloc") val heapAlloc: Long = 0L,
    @SerialName("numCPU") val numCPU: Int = 0,
)

// ---------------------------------------------------------------------------
// 实例管理
// ---------------------------------------------------------------------------

/** 实例状态：-1=忙碌 0=已关闭 1=停止中 2=启动中 3=运行中 */
object InstanceStatus {
    const val BUSY = -1
    const val STOPPED = 0
    const val STOPPING = 1
    const val STARTING = 2
    const val RUNNING = 3

    fun isRunningLike(v: Int): Boolean = v == RUNNING || v == STARTING
}

@Serializable
data class EventTask(
    @SerialName("autoStart") val autoStart: Boolean = false,
    @SerialName("autoRestart") val autoRestart: Boolean = false,
    val ignore: Boolean = false,
)

@Serializable
data class PingConfig(
    val ip: String = "",
    val port: Int = 25565,
    val type: Int = 0,
)

@Serializable
data class InstanceConfig(
    val nickname: String = "",
    @SerialName("startCommand") val startCommand: String = "",
    @SerialName("stopCommand") val stopCommand: String = "stop",
    val cwd: String = "",
    val ie: String = "utf-8",
    val oe: String = "utf-8",
    @SerialName("createDatetime") val createDatetime: Long = 0L,
    @SerialName("lastDatetime") val lastDatetime: Long = 0L,
    val type: String = "universal",
    val tag: List<String> = emptyList(),
    @SerialName("endTime") val endTime: Long = 0L,
    @SerialName("fileCode") val fileCode: String = "utf-8",
    @SerialName("processType") val processType: String = "universal",
    @SerialName("updateCommand") val updateCommand: String = "",
    @SerialName("actionCommandList") val actionCommandList: List<String> = emptyList(),
    val crlf: Int = 2,
    @SerialName("eventTask") val eventTask: EventTask = EventTask(),
    @SerialName("pingConfig") val pingConfig: PingConfig = PingConfig(),
)

@Serializable
data class InstanceDetail(
    val config: InstanceConfig = InstanceConfig(),
    @SerialName("instanceUuid") val instanceUuid: String = "",
    val started: Int = 0,
    val status: Int = InstanceStatus.STOPPED,
    val space: Long = 0L,
    val processInfo: InstanceProcessInfo = InstanceProcessInfo(),
)

@Serializable
data class InstanceProcessInfo(
    val cpu: Double = 0.0,
    val memory: Long = 0L,
    val ppid: Int = 0,
    val pid: Int = 0,
    val ctime: Long = 0L,
    val elapsed: Long = 0L,
    val timestamp: Long = 0L,
)

@Serializable
data class InstanceListResponse(
    val page: Int = 1,
    val pageSize: Int = 100,
    val total: Int = 0,
    val maxPage: Int = 1,
    val data: List<InstanceDetail> = emptyList(),
)

@Serializable
data class CreateInstanceResponse(
    @SerialName("instanceUuid") val instanceUuid: String = "",
    val config: InstanceConfig = InstanceConfig(),
)

@Serializable
data class DeleteInstanceBody(
    val uuids: List<String>,
    @SerialName("deleteFile") val deleteFile: Boolean = false,
)

@Serializable
data class UuidResult(
    @SerialName("instanceUuid") val instanceUuid: String = "",
)

// ---------------------------------------------------------------------------
// 文件管理
// ---------------------------------------------------------------------------

/** 文件类型：0=目录 1=文件 */
object FileType {
    const val DIR = 0
    const val FILE = 1
}

@Serializable
data class FileEntry(
    val name: String = "",
    val size: Long = 0L,
    val time: String = "",
    val mode: Int = 0,
    val type: Int = FileType.FILE,
    val mtime: String = "",
    val sha256: String = "",
)

@Serializable
data class FileListResponse(
    val items: List<FileEntry> = emptyList(),
    val page: Int = 0,
    val pageSize: Int = 100,
    val total: Int = 0,
    @SerialName("absolutePath") val absolutePath: String = "/",
)

@Serializable
data class FileWriteBody(
    val target: String,
    val text: String? = null,
)

@Serializable
data class FileReadBody(
    val target: String,
)

@Serializable
data class FileDeleteBody(
    val targets: List<String>,
)

@Serializable
data class FileMoveCopyBody(
    val targets: List<List<String>>,
)

@Serializable
data class FileCompressBody(
    val type: Int,
    val code: String = "utf-8",
    val source: String,
    val targets: List<String> = emptyList(),
)

@Serializable
data class FileMkdirBody(
    val target: String,
)

@Serializable
data class DownloadTicketResponse(
    val password: String = "",
    val addr: String = "",
)

@Serializable
data class UploadTicketResponse(
    val password: String = "",
    val addr: String = "",
    @SerialName("upload_dir") val uploadDir: String = "/",
)

// ---------------------------------------------------------------------------
// 容器环境
// ---------------------------------------------------------------------------

@Serializable
data class ContainerInfo(
    val runtime: String = "",
    val platform: String = "",
    val version: String = "",
    val available: Boolean = false,
    val error: String? = null,
)

@Serializable
data class ContainerListItem(
    val id: String = "",
    val name: String = "",
    val image: String = "",
    val status: String = "",
    val state: String = "",
    val ports: List<String> = emptyList(),
    val createdAt: String = "",
    @SerialName("restartPolicy") val restartPolicy: String = "",
)

@Serializable
data class ContainerCreateBody(
    val name: String,
    val image: String,
    val command: String? = null,
    val workdir: String? = null,
    val ports: List<String> = emptyList(),
    val volumes: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    @SerialName("restartPolicy") val restartPolicy: String? = null,
    @SerialName("memoryLimitMb") val memoryLimitMb: Int? = null,
    val cpus: Double? = null,
    @SerialName("diskLimitMb") val diskLimitMb: Int? = null,
)

@Serializable
data class ContainerCreateResult(
    val id: String = "",
    val name: String = "",
    val image: String = "",
)

@Serializable
data class ContainerStats(
    @SerialName("cpuPercent") val cpuPercent: Double = 0.0,
    @SerialName("memoryBytes") val memoryBytes: Long = 0L,
    @SerialName("memoryLimitBytes") val memoryLimitBytes: Long = 0L,
    @SerialName("netRxBytes") val netRxBytes: Long = 0L,
    @SerialName("netTxBytes") val netTxBytes: Long = 0L,
)

@Serializable
data class ImageItem(
    val id: String = "",
    val tags: List<String> = emptyList(),
    @SerialName("sizeBytes") val sizeBytes: Long = 0L,
    val createdAt: String = "",
)

@Serializable
data class ImagePullBody(
    val name: String,
)

@Serializable
data class ImageBuildBody(
    val dockerfile: String,
    val name: String,
    val tag: String,
)

@Serializable
data class BuildProgress(
    val status: String = "",
    val log: List<String> = emptyList(),
    val image: String = "",
)

@Serializable
data class VolumeItem(
    val name: String = "",
    val driver: String = "",
    val mountpoint: String = "",
)

@Serializable
data class NetworkItem(
    val name: String = "",
    val driver: String = "",
    val subnet: String? = null,
)

@Serializable
data class ContainerLimitsBody(
    @SerialName("memoryMb") val memoryMb: Int? = null,
    val cpus: Double? = null,
)

@Serializable
data class ContainerCloneBody(
    val name: String,
)
