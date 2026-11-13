package org.mcfso.irix.gnss

import android.os.SystemClock
import android.util.Log
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * ADB reverse 时间桥客户端。
 *
 * 电脑端 `xmc_time` 在 `127.0.0.1:47820` 监听；电脑执行：
 *
 *     adb reverse tcp:47820 tcp:47820
 *
 * 之后手机连接 `127.0.0.1:47820` 会经 USB 到达电脑。协议：
 * `gnss_hello` → `gnss_ping`（测 RTT）→ `gnss_sample`（带 rtt_ns）。
 *
 * 断线自动重连；没有 adb reverse 时连接失败是预期行为，不影响本地采集。
 */
class TimeBridgeClient(
    private val host: String = "127.0.0.1",
    private val port: Int = 47820,
    private val token: String = "irix-adb",
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val samples = Channel<String>(Channel.CONFLATED)
    @Volatile private var running = false
    private var job: Job? = null
    private var pingId = 0L

    @Synchronized
    fun start() {
        if (running) return
        running = true
        job = scope.launch { runLoop() }
    }

    @Synchronized
    fun stop() {
        running = false
        job?.cancel()
        job = null
    }

    /** 提交一帧 `gnss_sample` 的 args JSON；只保留最新一帧。 */
    fun submit(argsJson: String) {
        if (running) samples.trySend(argsJson)
    }

    private suspend fun runLoop() {
        while (running && scope.isActive) {
            try {
                Socket().use { socket ->
                    socket.soTimeout = 3_000
                    socket.connect(InetSocketAddress(host, port), 1_000)
                    val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                    val writer = BufferedWriter(
                        OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8),
                    )
                    if (!hello(reader, writer)) {
                        delay(2_000)
                        return@use
                    }
                    // 断线期间缓存的旧样本不再发送（age 会过期）。
                    while (samples.tryReceive().isSuccess) { /* drain */ }

                    var rttNs = 1_000_000L
                    var lastPing = 0L
                    while (running && scope.isActive) {
                        val item = withTimeoutOrNull(5_000L) { samples.receive() }
                        val now = SystemClock.elapsedRealtimeNanos()
                        if (item == null || now - lastPing >= 5_000_000_000L) {
                            rttNs = ping(reader, writer)
                            lastPing = now
                        }
                        if (item != null) {
                            sendSample(reader, writer, item, rttNs)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "GNSS bridge disconnected: ${e.message}")
                delay(2_000)
            }
        }
    }

    private fun hello(reader: BufferedReader, writer: BufferedWriter): Boolean {
        val args = JSONObject()
            .put("protocol", 1)
            .put("token", token)
            .put("app", "IriX-Android")
            .put("mono_axis_scope", "device_boot")
            .put("external_axis_mapping", "required")
        val response = exchange(
            reader,
            writer,
            JSONObject().put("op", "gnss_hello").put("args", args),
        )
        return response.optBoolean("ok", false)
    }

    private fun ping(reader: BufferedReader, writer: BufferedWriter): Long {
        val t0 = SystemClock.elapsedRealtimeNanos()
        val id = ++pingId
        val response = exchange(
            reader,
            writer,
            JSONObject().put("op", "gnss_ping").put(
                "args",
                JSONObject().put("id", id).put("t0_ns", t0),
            ),
        )
        if (!response.optBoolean("ok", false)) {
            throw IllegalStateException(response.optString("error", "ping rejected"))
        }
        return (SystemClock.elapsedRealtimeNanos() - t0)
            .coerceIn(100_000L, 500_000_000L)
    }

    private fun sendSample(
        reader: BufferedReader,
        writer: BufferedWriter,
        argsJson: String,
        rttNs: Long,
    ) {
        // 手机只负责测 RTT；电脑用 rtt/2 作为单程传输延迟。
        val args = JSONObject(argsJson).put("rtt_ns", rttNs)
        val response = exchange(
            reader,
            writer,
            JSONObject().put("op", "gnss_sample").put("args", args),
        )
        if (!response.optBoolean("ok", false)) {
            Log.d(TAG, "GNSS sample rejected: ${response.optString("error")}")
        }
    }

    private fun exchange(
        reader: BufferedReader,
        writer: BufferedWriter,
        value: JSONObject,
    ): JSONObject {
        writer.write(value.toString())
        writer.write("\n")
        writer.flush()
        val line = reader.readLine() ?: throw IllegalStateException("bridge EOF")
        return JSONObject(line)
    }

    private companion object {
        const val TAG = "IrixGnssBridge"
    }
}
