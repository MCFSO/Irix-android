package org.mcfso.irix.gnss

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssMeasurementRequest
import android.location.GnssMeasurementsEvent
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import androidx.core.content.ContextCompat
import org.mcfso.irix.gnss.model.GnssFix
import org.mcfso.irix.gnss.model.GnssSource
import org.mcfso.irix.gnss.model.GnssTimeSample
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToLong
import kotlin.math.sqrt

enum class GnssMode { AUTO, RAW, MILLIS, LOCATION }

data class GnssConfig(
    val mode: GnssMode = GnssMode.AUTO,
    val logEnabled: Boolean = true,
    val socketEnabled: Boolean = false,
    val socketPort: Int = 47810,
)

data class GnssDeviceCapabilities(
    val sdkInt: Int = Build.VERSION.SDK_INT,
    val model: String = "${Build.MANUFACTURER} ${Build.MODEL}",
    val gpsAvailable: Boolean = false,
    val gpsEnabled: Boolean = false,
    val fineLocationGranted: Boolean = false,
    /** null = unobserved; indoor silence does not prove unsupported hardware. */
    val rawSupported: Boolean? = if (Build.VERSION.SDK_INT < 24) false else null,
    val gnssClockSupported: Boolean = Build.VERSION.SDK_INT >= 29,
) {
    companion object {
        fun inspect(context: Context, observedRaw: Boolean? = null): GnssDeviceCapabilities {
            val lm = context.getSystemService(LocationManager::class.java)
            val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            return GnssDeviceCapabilities(
                gpsAvailable = lm?.allProviders?.contains(LocationManager.GPS_PROVIDER) == true,
                gpsEnabled = runCatching { lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true }.getOrDefault(false),
                fineLocationGranted = fine,
                rawSupported = if (Build.VERSION.SDK_INT < 24) false else observedRaw,
            )
        }
    }
}

/** All registrations and timestamp association run on one worker. No coordinates are retained. */
internal class GnssSampler(
    context: Context,
    private val config: GnssConfig,
    private val onSample: (GnssTimeSample) -> Unit,
    private val onRawObserved: () -> Unit,
    private val onIssue: (String) -> Unit,
) {
    private val lm = context.getSystemService(LocationManager::class.java)
    private val thread = HandlerThread("irix-gnss", Process.THREAD_PRIORITY_DISPLAY)
    private lateinit var handler: Handler
    @Volatile private var stopped = false
    private var lastFix: Location? = null
    private var usedSvs = 0
    private var bestCn0 = 0.0
    private var usedSystems = emptySet<Int>()
    private var statusMono = 0L
    private var gsaFix = GnssFix.NONE
    private var gsaMono = 0L
    private var lastRawMono = 0L
    private var lastEmittedMono = 0L
    private var rawCallback: GnssMeasurementsEvent.Callback? = null
    private var statusCallback: GnssStatus.Callback? = null
    private var nmeaListener: OnNmeaMessageListener? = null
    private var lastIssue: String? = null
    private val sigmaEstimator = GnssSigmaEstimator()

    private fun issue(message: String) {
        if (message != lastIssue) { lastIssue = message; onIssue(message) }
    }

    /** Vendor "unknown" sentinels are often >1s; never let them become the 1σ. */
    private fun saneSigma(value: Double?): Double? =
        value?.takeIf { it.isFinite() && it >= 0.0 && it <= 1_000_000_000.0 }

    private val locations = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (stopped || location.provider != LocationManager.GPS_PROVIDER || location.isFromMockProvider) return
            lastFix = Location(location)
        }
        override fun onProviderDisabled(provider: String) {
            lastFix = null; statusMono = 0L; gsaMono = 0L
            issue("GPS 已关闭，等待重新开启")
        }
        override fun onProviderEnabled(provider: String) { lastIssue = null }
        @Deprecated("Legacy callback required on Android 6")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        val manager = lm ?: return false
        sigmaEstimator.reset()
        thread.start()
        handler = Handler(thread.looper)
        handler.post {
            try {
                manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, locations, thread.looper)
                if (Build.VERSION.SDK_INT >= 24) registerGnss(manager)
                if (config.mode == GnssMode.RAW && Build.VERSION.SDK_INT < 24) issue("此系统不支持 Raw GNSS，请选择自动或 Location")
                if (config.mode == GnssMode.MILLIS && Build.VERSION.SDK_INT < 29) issue("GNSS 毫秒时钟需要 Android 10+，请选自动或 Location")
                handler.post(tick)
            } catch (e: SecurityException) { issue("定位权限已撤销，请停止后重新授权") }
            catch (e: IllegalArgumentException) { issue("无法注册 GPS：${e.message}") }
        }
        return true
    }

    @SuppressLint("MissingPermission", "NewApi")
    private fun registerGnss(manager: LocationManager) {
        val status = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                var used = 0
                var cn0 = 0.0
                val systems = mutableSetOf<Int>()
                for (i in 0 until status.satelliteCount) {
                    if (status.usedInFix(i)) { used++; systems.add(status.getConstellationType(i)) }
                    cn0 = max(cn0, status.getCn0DbHz(i).toDouble())
                }
                usedSvs = used; usedSystems = systems; bestCn0 = cn0
                statusMono = SystemClock.elapsedRealtimeNanos()
            }
        }
        statusCallback = status
        if (!manager.registerGnssStatusCallback(status, handler)) issue("设备拒绝卫星状态回调，fix/SV 将标为未知")
        val nmea = OnNmeaMessageListener { message, _ ->
            // GSA declares fix dimension; satellite count or altitude alone cannot prove 3D.
            val fields = message.substringBefore('*').split(',')
            if (fields.size > 2 && fields[0].endsWith("GSA")) {
                gsaFix = when (fields[2]) { "3" -> GnssFix.THREE_D; "2" -> GnssFix.TWO_D; else -> GnssFix.NONE }
                gsaMono = SystemClock.elapsedRealtimeNanos()
            }
        }
        nmeaListener = nmea
        manager.addNmeaListener(nmea, handler)
        if (config.mode != GnssMode.AUTO && config.mode != GnssMode.RAW) return
        val callback = object : GnssMeasurementsEvent.Callback() {
            override fun onGnssMeasurementsReceived(event: GnssMeasurementsEvent) {
                if (stopped) return
                onRawObserved()
                val now = SystemClock.elapsedRealtimeNanos()
                val clock = event.clock
                if (!clock.hasFullBiasNanos() || !clock.hasLeapSecond()) {
                    issue("Raw GNSS 缺少绝对时间或闰秒，自动模式使用毫秒/Location 降级")
                    return
                }
                val bias = if (clock.hasBiasNanos()) clock.biasNanos else 0.0
                val utc = GnssTimeMath.utcNsFromGnssClock(clock.timeNanos, true, clock.fullBiasNanos, bias, clock.leapSecond) ?: return
                val paired = Build.VERSION.SDK_INT >= 29 && clock.hasElapsedRealtimeNanos()
                val mono = if (paired) clock.elapsedRealtimeNanos else now
                if (mono <= 0L || mono > now || now - mono > 2_000_000_000L) return
                val alignSigma = if (paired && clock.hasElapsedRealtimeUncertaintyNanos()) clock.elapsedRealtimeUncertaintyNanos else null
                val biasSigma = if (clock.hasBiasUncertaintyNanos()) clock.biasUncertaintyNanos else null
                val timeSigma = if (clock.hasTimeUncertaintyNanos()) clock.timeUncertaintyNanos else null
                // 部分机型把“未知”上报成 131071/131072 秒之类的哨兵值；不能把这种
                // 数字直接当 1σ，否则每帧都会被本地判定为 uncertainty_too_large。
                val a = saneSigma(alignSigma)
                val b = saneSigma(biasSigma)
                val h = saneSigma(timeSigma)
                val degraded = a == null ||
                    (biasSigma != null && b == null) ||
                    (timeSigma != null && h == null)
                val validAlignment = paired
                // 厂商哨兵不可用时，用相邻 UTC/单调节拍差的 MAD 估计真实抖动；
                // 8 帧以内给 20ms 先验，之后动态收敛，而不是恒定的 2ms。
                val empirical = if (paired) {
                    sigmaEstimator.add(utc, mono)
                } else {
                    GnssSigmaEstimator.Estimate(50_000_001L, "monitoring")
                }
                val hwSigma = if (b != null || a != null || h != null) {
                    val biasNs = b ?: 0.0
                    val alignNs = a ?: 0.0
                    val timeNs = h ?: 0.0
                    sqrt(biasNs * biasNs + alignNs * alignNs + timeNs * timeNs)
                } else {
                    null
                }
                val sigma = if (validAlignment) {
                    max(hwSigma ?: 0.0, empirical.sigmaNs.toDouble())
                        .roundToLong()
                        .coerceAtLeast(100L)
                } else {
                    50_000_001L // unbounded alignment: explicitly monitoring-only
                }
                val uncertaintySource = when {
                    !validAlignment -> "monitoring"
                    hwSigma != null && hwSigma >= empirical.sigmaNs -> "hardware"
                    else -> empirical.source
                }
                if (config.mode == GnssMode.AUTO && !validAlignment) {
                    issue("Raw GNSS 无可靠同帧对齐，使用毫秒/Location 降级")
                    return
                }
                val sample = GnssTimeSample(
                    utcNs = utc, monoNs = mono, uncertaintyNs = sigma,
                    constellation = GnssConstellations.fold(freshSystems(now)).wire,
                    svCount = freshSvs(now), fix = freshFix(now).wire,
                    leapSeconds = clock.leapSecond, source = GnssSource.RAW.wire,
                    clockDiscontinuity = clock.hardwareClockDiscontinuityCount,
                    ageMs = (now - mono) / 1_000_000L,
                    cn0DbHz = bestCn0.takeIf { it.isFinite() },
                    driftPpb = if (clock.hasDriftNanosPerSecond()) clock.driftNanosPerSecond.takeIf { it.isFinite() } else null,
                    latencyNs = if (paired) now - mono else null,
                    sdkInt = Build.VERSION.SDK_INT, model = Build.MODEL,
                    alignment = if (degraded) "gnss_elapsed_realtime_fallback" else "gnss_elapsed_realtime",
                    elapsedRealtimeUncertaintyNs = alignSigma?.takeIf { it.isFinite() && it >= 0.0 },
                    biasUncertaintyNs = biasSigma?.takeIf { it.isFinite() && it >= 0.0 },
                    driftNsPerS = if (clock.hasDriftNanosPerSecond()) clock.driftNanosPerSecond.takeIf { it.isFinite() } else null,
                    uncertaintySource = uncertaintySource,
                )
                if (mono > lastRawMono) { lastRawMono = mono; emit(sample) }
            }
        }
        rawCallback = callback
        var registered = false
        if (Build.VERSION.SDK_INT >= 31) {
            // Full tracking 关闭芯片的批处理/占空比，显著缩短 measurement→callback
            // 的 age_ms；失败时回退旧注册方式。
            runCatching {
                val request = GnssMeasurementRequest.Builder()
                    .setFullTracking(true)
                    .build()
                val executor = java.util.concurrent.Executor { command ->
                    handler.post(command)
                }
                manager.registerGnssMeasurementsCallback(request, executor, callback)
            }.getOrDefault(false).let { registered = it }
        }
        if (!registered && !manager.registerGnssMeasurementsCallback(callback, handler)) {
            issue("设备暂未接受 Raw GNSS 注册，等待降级数据")
        }
    }

    private fun freshSystems(now: Long) = if (statusMono > 0L && now - statusMono <= 2_000_000_000L) usedSystems else emptySet()
    private fun freshSvs(now: Long) = if (statusMono > 0L && now - statusMono <= 2_000_000_000L) usedSvs else 0
    private fun freshFix(now: Long) = if (gsaMono > 0L && now - gsaMono <= 2_000_000_000L) gsaFix else GnssFix.NONE

    private fun emit(sample: GnssTimeSample) {
        if (stopped || sample.monoNs <= lastEmittedMono) return
        lastEmittedMono = sample.monoNs; lastIssue = null
        onSample(sample)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (stopped) return
            try { fallbackTick() }
            catch (e: SecurityException) { issue("定位权限已撤销，请停止后重新授权") }
            catch (e: RuntimeException) { issue("GNSS 读取失败：${e.message}") }
            if (!stopped) handler.postDelayed(this, 1_000L)
        }
    }

    private fun fallbackTick() {
        if (config.mode == GnssMode.RAW) return
        val now = SystemClock.elapsedRealtimeNanos()
        if (config.mode == GnssMode.AUTO && lastRawMono > 0L && now - lastRawMono <= 2_000_000_000L) return
        val fix = lastFix
        if (fix == null || fix.elapsedRealtimeNanos <= 0L || now - fix.elapsedRealtimeNanos !in 0L..2_000_000_000L) {
            issue("等待新的 GPS 定位；旧定位不作为新校准样本")
            return
        }
        if ((config.mode == GnssMode.AUTO || config.mode == GnssMode.MILLIS) && Build.VERSION.SDK_INT >= 29) {
            try {
                val before = SystemClock.elapsedRealtimeNanos()
                val utcMs = SystemClock.currentGnssTimeClock().millis()
                val after = SystemClock.elapsedRealtimeNanos()
                emit(GnssTimeSample(
                    utcNs = Math.multiplyExact(utcMs, 1_000_000L), monoNs = before + (after - before) / 2,
                    uncertaintyNs = max(2_000_000L, after - before + 1_000_000L),
                    constellation = GnssConstellations.fold(freshSystems(now)).wire,
                    svCount = freshSvs(now), fix = freshFix(now).wire,
                    source = GnssSource.GNSS_TIME_MILLIS.wire,
                    sdkInt = Build.VERSION.SDK_INT, model = Build.MODEL, alignment = "gnss_clock_bracket",
                ))
                return
            } catch (e: java.time.DateTimeException) { issue("GNSS 时钟尚未得到定位，等待 Location 降级") }
        }
        if (config.mode == GnssMode.MILLIS) return
        emit(GnssTimeSample(
            utcNs = fix.time * 1_000_000L, monoNs = fix.elapsedRealtimeNanos,
            uncertaintyNs = 10_000_000L,
            constellation = GnssConstellations.fold(freshSystems(now)).wire,
            svCount = freshSvs(now), fix = freshFix(now).wire,
            source = GnssSource.LOCATION.wire, ageMs = (now - fix.elapsedRealtimeNanos) / 1_000_000L,
            sdkInt = Build.VERSION.SDK_INT, model = Build.MODEL, alignment = "location_fix",
        ))
    }

    fun close() {
        if (stopped) return
        stopped = true
        if (!::handler.isInitialized) return
        handler.removeCallbacksAndMessages(null)
        handler.post {
            lm?.removeUpdates(locations)
            if (Build.VERSION.SDK_INT >= 24) {
                rawCallback?.let { lm?.unregisterGnssMeasurementsCallback(it) }
                statusCallback?.let { lm?.unregisterGnssStatusCallback(it) }
                nmeaListener?.let { lm?.removeNmeaListener(it) }
            }
            thread.quitSafely()
        }
    }
}
