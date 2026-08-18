package org.mcfso.irix.data

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.mcfso.irix.data.api.ApiFactory
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 文件直连传输：基于服务端票据机制真正发起下载/上传。
 *
 * 服务端两段式：先申请带密码票据 → 再走 /download/{password}/{file} 或 /upload/{password} 直传。
 * 下载优先用系统 DownloadManager（可断点、通知栏可见）；上传用 OkHttp multipart。
 */
class FileTransfer {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 用系统 DownloadManager 下载文件到公共下载目录。
     * @param downloadUrl 直连下载地址（已含票据密码）
     * @param fileName 保存的文件名
     * @return DownloadManager 任务 ID（>0 表示已入队），null 表示失败
     */
    fun enqueueDownload(context: Context, downloadUrl: String, fileName: String): Long? {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return null
        val request = DownloadManager.Request(Uri.parse(downloadUrl)).apply {
            setTitle(context.getString(org.mcfso.irix.R.string.transfer_download_title, fileName))
            setDescription(context.getString(org.mcfso.irix.R.string.transfer_download_desc, fileName))
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "irix/$fileName")
        }
        return try {
            dm.enqueue(request)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 流式上传本地文件到节点。
     * @param uploadUrl 直连上传地址（已含票据密码，形如 http://addr/upload/{password}）
     * @param file 本地文件
     * @return 成功与否 + 错误消息
     */
    suspend fun upload(uploadUrl: String, file: File): Pair<Boolean, String?> = withContext(Dispatchers.IO) {
        try {
            val body = file.asRequestBody("application/octet-stream".toMediaType())
            val multipart = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", file.name, body)
                .build()
            val req = Request.Builder()
                .url(uploadUrl)
                .post(multipart)
                .addHeader("X-Requested-With", "XMLHttpRequest")
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) true to null
                else false to "上传失败 (HTTP ${resp.code})"
            }
        } catch (e: Exception) {
            false to (e.message ?: "上传失败")
        }
    }
}
