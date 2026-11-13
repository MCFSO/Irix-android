package org.mcfso.irix.gnss.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ---------------------------------------------------------------------------
// 枚举：与 docs/gnss-time-integration.md 的线协议取值严格一致
// ---------------------------------------------------------------------------

/** 定位质量（对接契约的 `fix` 字段）。 */
enum class GnssFix(val wire: String) {
    NONE("none"),
    TWO_D("2d"),
    THREE_D("3d"),
    ;

    companion object {
        fun fromWire(value: String?): GnssFix = entries.firstOrNull { it.wire == value } ?: NONE
    }
}

/** 样本采集路径（对接契约的 `source` 字段）。 */
enum class GnssSource(val wire: String) {
    /** 方案 B：Raw GNSS（GnssClock + GnssMeasurement）。 */
    RAW("raw"),

    /** Wire label for public SystemClock.currentGnssTimeClock() (API 29+). LocationManager.getGnssTimeMillis is not public API. */
    GNSS_TIME_MILLIS("gnss_time_millis"),

    /** 方案 C：Location 回调（`Location.time` + `elapsedRealtimeNanos`）。 */
    LOCATION("location"),
    ;

    companion object {
        fun fromWire(value: String?): GnssSource? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * 时间源星座。线协议只允许 gps / bds / galileo / glonass / multi，
 * 避免 Rust 侧枚举解析出现未定义值：QZSS 并入 gps（时间与 GPS 同源），
 * 其它系统在多星座混用时归为 multi。
 */
enum class GnssConstellation(val wire: String) {
    GPS("gps"),
    BDS("bds"),
    GALILEO("galileo"),
    GLONASS("glonass"),
    MULTI("multi"),
    ;

    companion object {
        fun fromWire(value: String?): GnssConstellation = entries.firstOrNull { it.wire == value } ?: GPS
    }
}

// ---------------------------------------------------------------------------
// 采集样本
// ---------------------------------------------------------------------------

/**
 * 一条 GNSS 时间样本，字段与 `docs/android-gnss-time-calibration.md` §5.1 的
 * `gnss_sample` op 参数一一对应。
 *
 * `mono_ns` 固定使用 `SystemClock.elapsedRealtimeNanos()`（CLOCK_BOOTTIME，含休眠），
 * 它是本设备、本次启动的时间轴；外部设备必须显式映射，不能假设与 Rust zeitstempel 有相同原点。
 */
@Serializable
data class GnssTimeSample(
    /** GNSS 导出的 UTC 纳秒（Unix epoch）。 */
    @SerialName("utc_ns") val utcNs: Long,
    /** 同一时刻的 CPU 单调时间（elapsedRealtimeNanos）。 */
    @SerialName("mono_ns") val monoNs: Long,
    /** 1σ 不确定度（纳秒），必须诚实上报。 */
    @SerialName("uncertainty_ns") val uncertaintyNs: Long,
    @SerialName("constellation") val constellation: String = GnssConstellation.GPS.wire,
    /** 参与解算的卫星数。 */
    @SerialName("sv_count") val svCount: Int = 0,
    @SerialName("fix") val fix: String = GnssFix.NONE.wire,
    /** Observed GPS-UTC leap seconds; null means unavailable, never assume a current value. */
    @SerialName("leap_seconds") val leapSeconds: Int? = null,
    @SerialName("source") val source: String = GnssSource.RAW.wire,
    /** GnssClock.hardwareClockDiscontinuityCount，变化时该帧作废。 */
    @SerialName("clock_discontinuity") val clockDiscontinuity: Int = 0,
    /** 从测量到送达本模块的年龄（毫秒，可选）。 */
    @SerialName("age_ms") val ageMs: Long = 0,
    // ---- 以下为诊断字段：Rust 侧可忽略，仅用于日志与问题定位 ----
    /** BDT-UTC 偏移（秒），仅当星座为 bds 且厂商给出时填写。 */
    @SerialName("bdt_utc_offset_s") val bdtUtcOffsetS: Int? = null,
    /** 最强卫星载噪比（dB-Hz）。 */
    @SerialName("cn0_db_hz") val cn0DbHz: Double? = null,
    /** GNSS 硬件时钟相对 CPU 单调时钟的漂移（ppb）。 */
    @SerialName("drift_ppb") val driftPpb: Double? = null,
    /** Optional independently measured latency; the relative clock regression cannot populate this. */
    @SerialName("latency_ns") val latencyNs: Long? = null,
    /** Timestamp alignment method, e.g. direct elapsed-realtime timestamp or callback fallback. */
    @SerialName("alignment") val alignment: String? = null,
    @SerialName("elapsed_realtime_uncertainty_ns") val elapsedRealtimeUncertaintyNs: Double? = null,
    @SerialName("bias_uncertainty_ns") val biasUncertaintyNs: Double? = null,
    @SerialName("drift_ns_per_s") val driftNsPerS: Double? = null,
    /** hardware | empirical | prior | monitoring；解释 uncertainty_ns 的来源。 */
    @SerialName("uncertainty_source") val uncertaintySource: String? = null,
    @SerialName("sdk_int") val sdkInt: Int? = null,
    @SerialName("model") val model: String? = null,
) {
    val offsetUncertaintyNs: Long get() = uncertaintyNs
}

// ---------------------------------------------------------------------------
// 校验结果
// ---------------------------------------------------------------------------

/** Local diagnostic quality only; no clock actuation or remote/Rust acceptance is implied. */
sealed interface GnssVerdict {
    /** offset = utc_ns - 本机应用时钟在同一 mono 时刻的 UTC。 */
    val offsetNs: Long?
    val effectiveUncertaintyNs: Long

    data class Accepted(
        override val offsetNs: Long,
        override val effectiveUncertaintyNs: Long,
        /** Heuristic score relative to an assumed NTP sigma, 0..1; not a calibrated fusion gain. */
        val weight: Double,
        /** Legacy name for local consistency readiness only. Never authorizes a clock step. */
        val stepReady: Boolean,
        /** 当前连续一致帧数。 */
        val consistentFrames: Int,
    ) : GnssVerdict

    /** Monitoring diagnostic; effective uncertainty retains declared sigma, optionally inflated for weak fixes. */
    data class Monitoring(
        override val offsetNs: Long,
        override val effectiveUncertaintyNs: Long,
        val reason: String,
    ) : GnssVerdict

    data class Rejected(val reason: String) : GnssVerdict {
        override val offsetNs: Long? get() = null
        override val effectiveUncertaintyNs: Long get() = -1
    }
}

// ---------------------------------------------------------------------------
// 日志记录
// ---------------------------------------------------------------------------

/** 单个样本的落盘记录（JSONL），用于 24h 保持测试的原始数据。 */
@Serializable
data class GnssLogRecord(
    /** 本机收到样本的 Unix 毫秒时间。 */
    @SerialName("t") val receivedUnixMs: Long,
    /** 本机收到样本的单调纳秒时间。 */
    @SerialName("mono_rx_ns") val receivedMonoNs: Long,
    @SerialName("sample") val sample: GnssTimeSample,
    /** accepted / monitoring / rejected。 */
    @SerialName("verdict") val verdict: String,
    /** 拒绝/降权原因，accepted 时为 null。 */
    @SerialName("reason") val reason: String? = null,
    @SerialName("offset_ns") val offsetNs: Long? = null,
    @SerialName("weight") val weight: Double? = null,
    @SerialName("step_ready") val stepReady: Boolean? = null,
    /** 本机系统时钟与 GNSS UTC 的偏差（毫秒，便于快速肉眼检查）。 */
    @SerialName("offset_vs_system_ms") val offsetVsSystemMs: Double? = null,
)
