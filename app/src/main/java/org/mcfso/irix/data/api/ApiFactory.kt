package org.mcfso.irix.data.api

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.mcfso.irix.data.NetworkPolicy
import org.mcfso.irix.data.model.NodeConfig
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

/**
 * 为指定节点构建带认证的 Retrofit NodeApi。
 *
 * apikey 经拦截器注入查询参数（兼容 X-Api-Key 头）。
 * 明文 HTTP 节点由 network_security_config 放行。
 */
object ApiFactory {

    val json: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
        explicitNulls = false
    }

    private val logging = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BASIC
    }

    fun okHttpClient(node: NodeConfig): OkHttpClient {
        val key = node.apiKey
        return OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val req = chain.request()
                val url = if (key.isNotEmpty()) {
                    req.url.newBuilder().addQueryParameter("apikey", key).build()
                } else {
                    req.url
                }
                val builder = req.newBuilder().url(url)
                    .addHeader("X-Requested-With", "XMLHttpRequest")
                if (key.isNotEmpty()) builder.addHeader("X-Api-Key", key)
                chain.proceed(builder.build())
            }
            .addInterceptor(logging)
            .build()
    }

    fun create(node: NodeConfig): NodeApi {
        val base = node.address.trimEnd('/')
        // 应用层明文 HTTP 拦截（详见 NetworkPolicy）：违规直接抛错，
        // 由 apiCall 包装成 ApiResult.Error 展示给用户。
        NetworkPolicy.cleartextViolation(base)?.let { throw IllegalStateException(it) }
        val client = okHttpClient(node)
        val contentType = "application/json".toMediaTypeOrNull()
        return Retrofit.Builder()
            .baseUrl(if (base.endsWith('/')) base else "$base/")
            .client(client)
            .addConverterFactory(json.asConverterFactory(contentType!!))
            .build()
            .create(NodeApi::class.java)
    }
}
