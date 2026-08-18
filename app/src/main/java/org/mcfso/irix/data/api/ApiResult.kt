package org.mcfso.irix.data.api

import org.mcfso.irix.data.model.ApiResponse

/**
 * 统一的 API 调用结果。
 * - Success：业务 status == 200，携带 data
 * - Error：业务 status != 200 或网络/解析失败，携带可展示消息
 */
sealed interface ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>
    data class Error(val message: String, val status: Int? = null) : ApiResult<Nothing>
}

/**
 * 把一次挂起的 Api 调用包成 ApiResult，捕获网络/序列化/业务错误。
 * 业务 status != 200 时，data 为错误消息字符串。
 */
suspend inline fun <reified T> apiCall(crossinline block: suspend () -> ApiResponse<T>): ApiResult<T> {
    return try {
        val resp = block()
        if (resp.status == 200 && resp.data != null) {
            ApiResult.Success(resp.data)
        } else if (resp.status != 200) {
            val msg = (resp.data as? String) ?: "请求失败 (HTTP ${resp.status})"
            ApiResult.Error(msg, resp.status)
        } else {
            ApiResult.Error("响应数据为空")
        }
    } catch (e: retrofit2.HttpException) {
        ApiResult.Error("HTTP ${e.code()}: ${e.message()}", e.code())
    } catch (e: java.net.UnknownHostException) {
        ApiResult.Error("无法连接节点：未知主机")
    } catch (e: java.net.ConnectException) {
        ApiResult.Error("无法连接节点：连接被拒绝")
    } catch (e: java.net.SocketTimeoutException) {
        ApiResult.Error("连接节点超时")
    } catch (e: kotlinx.serialization.SerializationException) {
        ApiResult.Error("响应解析失败：${e.message ?: "数据格式错误"}")
    } catch (e: Exception) {
        ApiResult.Error(e.message ?: "未知错误")
    }
}
