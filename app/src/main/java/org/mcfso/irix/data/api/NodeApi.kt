package org.mcfso.irix.data.api

import org.mcfso.irix.data.model.ApiResponse
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
import org.mcfso.irix.data.model.DownloadCoreBody
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
import org.mcfso.irix.data.model.ImportInstanceBody
import org.mcfso.irix.data.model.InstanceConfig
import org.mcfso.irix.data.model.InstanceDetail
import org.mcfso.irix.data.model.InstanceListResponse
import org.mcfso.irix.data.model.InstanceStats
import org.mcfso.irix.data.model.JavaInstallBody
import org.mcfso.irix.data.model.JavaRuntimeResponse
import org.mcfso.irix.data.model.JobResult
import org.mcfso.irix.data.model.LoadStatus
import org.mcfso.irix.data.model.NetworkItem
import org.mcfso.irix.data.model.OverviewResponse
import org.mcfso.irix.data.model.TaskProgress
import org.mcfso.irix.data.model.TrashBody
import org.mcfso.irix.data.model.TrashIdsBody
import org.mcfso.irix.data.model.TrashListResponse
import org.mcfso.irix.data.model.UploadTicketResponse
import org.mcfso.irix.data.model.UuidResult
import org.mcfso.irix.data.model.VolumeItem
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * IriX 节点 HTTP API（MCSM 风格）。
 *
 * 认证经 OkHttp 拦截器统一注入 apikey（查询参数或请求头），此处不重复传。
 * 响应统一为 ApiResponse<T>；列表端点的 data 为裸数组。
 */
interface NodeApi {

    // ----- 概览 / 负载 -----------------------------------------------------

    @GET("/api/overview")
    suspend fun overview(): ApiResponse<OverviewResponse>

    @GET("/api/load")
    suspend fun load(): ApiResponse<LoadStatus>

    // ----- 实例管理 -------------------------------------------------------

    @GET("/api/service/remote_service_instances")
    suspend fun instanceList(
        @Query("daemonId") daemonId: String? = null,
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 100,
        @Query("instance_name") instanceName: String? = null,
        @Query("status") status: String? = null,
    ): ApiResponse<InstanceListResponse>

    @GET("/api/instance")
    suspend fun instanceDetail(
        @Query("uuid") uuid: String,
        @Query("daemonId") daemonId: String? = null,
    ): ApiResponse<InstanceDetail>

    @POST("/api/instance")
    suspend fun instanceCreate(
        @Query("daemonId") daemonId: String? = null,
        @Body config: InstanceConfig,
    ): ApiResponse<CreateInstanceResponse>

    @PUT("/api/instance")
    suspend fun instanceUpdate(
        @Query("uuid") uuid: String,
        @Query("daemonId") daemonId: String? = null,
        @Body config: InstanceConfig,
    ): ApiResponse<UuidResult>

    @DELETE("/api/instance")
    suspend fun instanceDelete(
        @Query("daemonId") daemonId: String? = null,
        @Body body: DeleteInstanceBody,
    ): ApiResponse<List<String>>

    @GET("/api/protected_instance/open")
    suspend fun instanceStart(@Query("uuid") uuid: String): ApiResponse<UuidResult>

    @GET("/api/protected_instance/stop")
    suspend fun instanceStop(@Query("uuid") uuid: String): ApiResponse<UuidResult>

    @GET("/api/protected_instance/restart")
    suspend fun instanceRestart(@Query("uuid") uuid: String): ApiResponse<UuidResult>

    @GET("/api/protected_instance/kill")
    suspend fun instanceKill(@Query("uuid") uuid: String): ApiResponse<UuidResult>

    @GET("/api/protected_instance/command")
    suspend fun instanceCommand(
        @Query("uuid") uuid: String,
        @Query("command") command: String,
    ): ApiResponse<UuidResult>

    @GET("/api/protected_instance/outputlog")
    suspend fun instanceOutputLog(
        @Query("uuid") uuid: String,
        @Query("size") size: Int? = null,
    ): ApiResponse<String>

    // ----- 实例级扩展（日志持久化 / 运行指标 / 导入 / 核心下载） -----------

    @GET("/api/instance/logs")
    suspend fun instanceLogs(
        @Query("uuid") uuid: String,
        @Query("daemonId") daemonId: String? = null,
        @Query("tail") tail: Int? = null,
        @Query("since") since: Long? = null,
    ): ApiResponse<String>

    @DELETE("/api/instance/logs")
    suspend fun instanceLogsClear(
        @Query("uuid") uuid: String,
        @Query("daemonId") daemonId: String? = null,
    ): ApiResponse<Boolean>

    @GET("/api/instance/stats")
    suspend fun instanceStats(
        @Query("uuid") uuid: String,
        @Query("daemonId") daemonId: String? = null,
    ): ApiResponse<InstanceStats>

    @POST("/api/instance/import")
    suspend fun instanceImport(
        @Body body: ImportInstanceBody,
    ): ApiResponse<JobResult>

    @POST("/api/instance/download-core")
    suspend fun downloadCore(
        @Body body: DownloadCoreBody,
    ): ApiResponse<JobResult>

    @GET("/api/instance/download-core-progress")
    suspend fun downloadCoreProgress(
        @Query("jobId") jobId: String,
    ): ApiResponse<TaskProgress>

    // ----- Java 运行时 / JDK 安装（§4.2.1 / §4.2.2） ---------------------

    @GET("/api/runtime/java")
    suspend fun runtimeJava(): ApiResponse<JavaRuntimeResponse>

    @POST("/api/runtime/java/install")
    suspend fun javaInstall(@Body body: JavaInstallBody): ApiResponse<JobResult>

    @GET("/api/runtime/java/install-progress")
    suspend fun javaInstallProgress(@Query("jobId") jobId: String): ApiResponse<TaskProgress>

    @DELETE("/api/runtime/java")
    suspend fun javaUninstall(@Query("major") major: Int): ApiResponse<Boolean>

    // ----- 文件管理 -------------------------------------------------------

    @GET("/api/files/list")
    suspend fun fileList(
        @Query("uuid") uuid: String,
        @Query("target") target: String,
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 100,
    ): ApiResponse<FileListResponse>

    @PUT("/api/files/")
    suspend fun fileRead(
        @Query("uuid") uuid: String,
        @Body body: FileReadBody,
    ): ApiResponse<String>

    @PUT("/api/files/")
    suspend fun fileWrite(
        @Query("uuid") uuid: String,
        @Body body: FileWriteBody,
    ): ApiResponse<Boolean>

    @DELETE("/api/files")
    suspend fun fileDelete(
        @Query("uuid") uuid: String,
        @Body body: FileDeleteBody,
    ): ApiResponse<Boolean>

    @PUT("/api/files/move")
    suspend fun fileMove(
        @Query("uuid") uuid: String,
        @Body body: FileMoveCopyBody,
    ): ApiResponse<Boolean>

    @POST("/api/files/copy")
    suspend fun fileCopy(
        @Query("uuid") uuid: String,
        @Body body: FileMoveCopyBody,
    ): ApiResponse<Boolean>

    @POST("/api/files/compress")
    suspend fun fileCompress(
        @Query("uuid") uuid: String,
        @Body body: FileCompressBody,
    ): ApiResponse<Boolean>

    @POST("/api/files/mkdir")
    suspend fun fileMkdir(
        @Query("uuid") uuid: String,
        @Body body: FileMkdirBody,
    ): ApiResponse<Boolean>

    @POST("/api/files/touch")
    suspend fun fileTouch(
        @Query("uuid") uuid: String,
        @Body body: FileMkdirBody,
    ): ApiResponse<Boolean>

    @POST("/api/files/download")
    suspend fun fileDownloadTicket(
        @Query("uuid") uuid: String,
        @Query("file_name") fileName: String,
    ): ApiResponse<DownloadTicketResponse>

    @POST("/api/files/upload")
    suspend fun fileUploadTicket(
        @Query("uuid") uuid: String,
        @Query("upload_dir") uploadDir: String,
    ): ApiResponse<UploadTicketResponse>

    // ----- 实例级回收站（§4.6） ------------------------------------------

    @POST("/api/files/trash")
    suspend fun fileTrash(
        @Query("uuid") uuid: String,
        @Body body: TrashBody,
    ): ApiResponse<Boolean>

    @GET("/api/files/trash/list")
    suspend fun trashList(
        @Query("uuid") uuid: String,
        @Query("daemonId") daemonId: String? = null,
    ): ApiResponse<TrashListResponse>

    @POST("/api/files/trash/restore")
    suspend fun trashRestore(
        @Query("uuid") uuid: String,
        @Body body: TrashIdsBody,
    ): ApiResponse<Map<String, String>>

    @POST("/api/files/trash/empty")
    suspend fun trashEmpty(
        @Query("uuid") uuid: String,
        @Body body: TrashIdsBody,
    ): ApiResponse<Boolean>

    // ----- 容器环境（Docker / Bastille） ----------------------------------

    @GET("/api/container/info")
    suspend fun containerInfo(): ApiResponse<ContainerInfo>

    @GET("/api/container/ps")
    suspend fun containerList(@Query("all") all: Int = 1): ApiResponse<List<ContainerListItem>>

    @POST("/api/container/create")
    suspend fun containerCreate(@Body body: ContainerCreateBody): ApiResponse<ContainerCreateResult>

    @POST("/api/container/{id}/start")
    suspend fun containerStart(@Path("id") id: String): ApiResponse<Boolean>

    @POST("/api/container/{id}/stop")
    suspend fun containerStop(@Path("id") id: String): ApiResponse<Boolean>

    @POST("/api/container/{id}/restart")
    suspend fun containerRestart(@Path("id") id: String): ApiResponse<Boolean>

    @POST("/api/container/{id}/kill")
    suspend fun containerKill(@Path("id") id: String): ApiResponse<Boolean>

    @DELETE("/api/container/{id}")
    suspend fun containerRemove(
        @Path("id") id: String,
        @Query("force") force: Int = 0,
    ): ApiResponse<Boolean>

    @GET("/api/container/{id}/logs")
    suspend fun containerLogs(
        @Path("id") id: String,
        @Query("tail") tail: Int = 200,
    ): ApiResponse<String>

    @POST("/api/container/{id}/exec")
    suspend fun containerExec(
        @Path("id") id: String,
        @Body body: Map<String, String>,
    ): ApiResponse<String>

    @GET("/api/container/{id}/stats")
    suspend fun containerStats(@Path("id") id: String): ApiResponse<ContainerStats>

    @POST("/api/container/{id}/clone")
    suspend fun containerClone(
        @Path("id") id: String,
        @Body body: ContainerCloneBody,
    ): ApiResponse<ContainerCreateResult>

    @POST("/api/container/{id}/limits")
    suspend fun containerLimits(
        @Path("id") id: String,
        @Body body: ContainerLimitsBody,
    ): ApiResponse<Boolean>

    @GET("/api/image/list")
    suspend fun imageList(): ApiResponse<List<ImageItem>>

    @POST("/api/image/pull")
    suspend fun imagePull(@Body body: ImagePullBody): ApiResponse<Boolean>

    @POST("/api/image/build")
    suspend fun imageBuild(@Body body: ImageBuildBody): ApiResponse<Map<String, String>>

    @GET("/api/image/build-progress")
    suspend fun imageBuildProgress(@Query("jobId") jobId: String): ApiResponse<BuildProgress>

    @DELETE("/api/image/{name}")
    suspend fun imageRemove(@Path("name") name: String): ApiResponse<Boolean>

    @GET("/api/volume/list")
    suspend fun volumeList(): ApiResponse<List<VolumeItem>>

    @DELETE("/api/volume/{name}")
    suspend fun volumeRemove(@Path("name") name: String): ApiResponse<Boolean>

    @GET("/api/network/list")
    suspend fun networkList(): ApiResponse<List<NetworkItem>>
}
