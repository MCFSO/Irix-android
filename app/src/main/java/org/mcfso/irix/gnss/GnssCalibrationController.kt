package org.mcfso.irix.gnss

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mcfso.irix.gnss.model.GnssTimeSample
import org.mcfso.irix.gnss.model.GnssVerdict
import org.mcfso.irix.gnss.sink.GnssLogStore
import org.mcfso.irix.gnss.sink.GnssSocketServer
import org.mcfso.irix.gnss.wire.GnssDeviceHello
import org.mcfso.irix.gnss.wire.GnssWire
import java.io.File
import java.util.UUID

/** Local collection diagnostics only; no system/app clock is stepped or slewed here. */
data class GnssUiState(
    val running: Boolean = false,
    val capabilities: GnssDeviceCapabilities = GnssDeviceCapabilities(),
    val latest: GnssTimeSample? = null,
    val verdict: GnssVerdict? = null,
    val stats: GnssStats.Snapshot = GnssStats.Snapshot(),
    val activeSource: String? = null,
    val lastSampleMonoNs: Long = 0L,
    val error: String? = null,
    val logLines: Long = 0,
    val logPath: String? = null,
    val socketClients: Int = 0,
    val socketPort: Int? = null,
    val config: GnssConfig = GnssConfig(),
    val recent: List<GnssTimeSample> = emptyList(),
)

class GnssCalibrationController(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = app.getSharedPreferences("gnss_calibration", Context.MODE_PRIVATE)
    private val savedConfig = GnssConfig(
        mode = runCatching { GnssMode.valueOf(prefs.getString("mode", "AUTO")!!) }.getOrDefault(GnssMode.AUTO),
        logEnabled = prefs.getBoolean("log", true),
        socketEnabled = prefs.getBoolean("socket", false),
    )
    private val _state = MutableStateFlow(GnssUiState(config = savedConfig))
    val state: StateFlow<GnssUiState> = _state.asStateFlow()
    private val commands = Channel<() -> Unit>(Channel.UNLIMITED)
    private var sampler: GnssSampler? = null
    private var log: GnssLogStore? = null
    private var server: GnssSocketServer? = null
    private val timeBridge = TimeBridgeClient()
    private var lastTempMono = 0L
    private var sessionId = 0L
    private val validator = GnssSampleValidator()
    private val stats = GnssStats()
    @Volatile private var observedRaw: Boolean? = null
    @Volatile private var accepting = false
    private val sampleQueue = Channel<Incoming>(32)
    private data class Incoming(val session: Long, val sample: GnssTimeSample, val unixMs: Long, val monoNs: Long)

    init {
        refreshCapabilities()
        // Load the native time system off the main thread. If libxmc_time.so is
        // absent (e.g. unsupported ABI), GNSS collection still works.
        scope.launch(Dispatchers.IO) {
            TimeNative.start(File(app.filesDir, "time/state.json").absolutePath)
        }
        // Serialize start/stop/config and sample processing on the same command actor.
        scope.launch { for (command in commands) {
            try { command() } catch (e: Exception) { reportError("GNSS 处理失败：${e.message}") }
        } }
        scope.launch { for (incoming in sampleQueue) {
            commands.send { consume(incoming) }
        } }
    }

    fun refreshCapabilities() {
        val caps = GnssDeviceCapabilities.inspect(app, observedRaw)
        _state.update { it.copy(capabilities = caps) }
    }

    fun reportError(message: String) { _state.update { it.copy(error = message) } }

    fun updateConfig(config: GnssConfig) {
        if (_state.value.running) return
        require(config.socketPort in 1024..65535)
        _state.update { if (it.running) it else it.copy(config = config) }
        prefs.edit().putString("mode", config.mode.name).putBoolean("log", config.logEnabled)
            .putBoolean("socket", config.socketEnabled).apply()
    }

    /** Called only after Service.startForeground succeeds, from a visible Activity. */
    internal fun start() {
        commands.trySend {
            if (accepting) return@trySend
            refreshCapabilities()
            val s = _state.value
            if (!s.capabilities.fineLocationGranted || !s.capabilities.gpsAvailable || !s.capabilities.gpsEnabled) {
                reportError("需要精确定位权限和已开启的 GPS")
                return@trySend
            }
            val config = s.config
            if (config.mode == GnssMode.RAW && android.os.Build.VERSION.SDK_INT < 24 ||
                config.mode == GnssMode.MILLIS && !s.capabilities.gnssClockSupported) {
                reportError("此系统不支持选定来源，请选择自动或 Location")
                return@trySend
            }
            sessionId++
            val token = sessionId
            validator.reset(); stats.reset()
            if (config.logEnabled) log = GnssLogStore(File(app.filesDir, "gnss-logs"))
            if (config.socketEnabled) {
                val socket = GnssSocketServer(scope) {
                    GnssWire.helloFrame(GnssDeviceHello(
                        appVersion = "1.0", sdkInt = s.capabilities.sdkInt,
                        androidVersion = android.os.Build.VERSION.RELEASE,
                        model = s.capabilities.model, rawSupported = observedRaw == true,
                    ))
                }
                server = socket
                if (!socket.start(config.socketPort)) {
                    socket.close(); server = null
                    log?.close(); log = null
                    reportError("本地 socket 无法绑定，请检查端口 ${config.socketPort}")
                    return@trySend
                }
                scope.launch { socket.clientCount.collect { count ->
                    if (sessionId == token) _state.update { it.copy(socketClients = count) }
                } }
                scope.launch { socket.error.collect { error ->
                    if (sessionId == token && error != null) reportError(error)
                } }
            }
            accepting = true
            _state.value = GnssUiState(running = true, capabilities = s.capabilities, config = config, socketPort = server?.port)
            sampler = GnssSampler(app, config,
                onSample = { sample ->
                    if (accepting && token == sessionId) {
                        val rx = Incoming(token, sample, TimeNative.nowUnixMsOrSystem(), SystemClock.elapsedRealtimeNanos())
                        if (!sampleQueue.trySend(rx).isSuccess) reportError("样本队列已满，本帧丢弃；请检查日志存储速度")
                    }
                },
                onRawObserved = { observedRaw = true; refreshCapabilities() },
                onIssue = { message -> if (token == sessionId && accepting) reportError(message) },
            ).also { if (!it.start()) { reportError("无法启动 GPS 采集"); stop() } }
            if (accepting) timeBridge.start()
        }
    }

    private fun batteryTempC(): Double? {
        val intent = app.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        ) ?: return null
        val tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        if (tenths == Int.MIN_VALUE) return null
        return tenths / 10.0
    }

    private fun consume(rx: Incoming) {        if (!accepting || rx.session != sessionId) return
        val sample = rx.sample
        val appAtMono = GnssTimeMath.appUtcNsAtMono(rx.unixMs, rx.monoNs, sample.monoNs)
        val result = validator.evaluate(sample, rx.monoNs, appAtMono)
        stats.record(result.verdict)
        val record = GnssWire.logRecord(sample, result.verdict, rx.unixMs, rx.monoNs)
        log?.append(GnssWire.logLine(record))
        // Transport carries raw facts, NOT local verdict authorization. Receiver validates afresh.
        server?.publish(GnssWire.sampleFrame(sample))
        // Same-device JNI path: xmc_time shares this device's BOOTTIME axis and
        // performs its own validation/fusion, independent of the local diagnostic.
        TimeNative.ingest(GnssWire.argsJson(sample))
        // 电池温度只是晶振环境的代理，不声称等于晶振温度；60s 注入一次。
        if (rx.monoNs - lastTempMono >= 60_000_000_000L) {
            lastTempMono = rx.monoNs
            batteryTempC()?.let { TimeNative.setTemperature(it) }
        }
        // ADB reverse 时间桥：电脑端 xmc_time 的 bridge server 在 127.0.0.1:47820。
        timeBridge.submit(GnssWire.argsJson(sample))
        _state.update { current -> current.copy(
            latest = sample, verdict = result.verdict, stats = stats.snapshot(),
            activeSource = sample.source, lastSampleMonoNs = rx.monoNs,
            logLines = log?.lines ?: 0, logPath = log?.currentFile?.name,
            error = log?.error ?: server?.error?.value,
            recent = (listOf(sample) + current.recent).take(12),
        ) }
    }

    internal fun stop() {
        accepting = false
        commands.trySend {
            sessionId++
            timeBridge.stop()
            sampler?.close(); sampler = null
            server?.close(); server = null
            log?.close() // Retain the object so the completed session can still be exported.
            _state.update { it.copy(running = false, socketClients = 0, socketPort = null) }
        }
    }

    suspend fun exportLog(): File? = withContext(Dispatchers.IO) {
        val directory = File(app.cacheDir, "gnss-export")
        directory.mkdirs()
        val target = File(directory, "gnss-${UUID.randomUUID()}.jsonl")
        val snapshot = log?.snapshot(target)
        if (snapshot != null) {
            directory.listFiles()?.filter { it != target }?.sortedByDescending { it.lastModified() }?.drop(3)?.forEach { it.delete() }
        }
        snapshot
    }
}
