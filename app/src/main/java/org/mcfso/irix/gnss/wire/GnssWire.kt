package org.mcfso.irix.gnss.wire

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.mcfso.irix.gnss.GnssTimeMath
import org.mcfso.irix.gnss.model.GnssLogRecord
import org.mcfso.irix.gnss.model.GnssTimeSample
import org.mcfso.irix.gnss.model.GnssVerdict

/**
 * 线协议编码：与 `docs/android-gnss-time-calibration.md` §5.1 的 `gnss_sample` op 对齐。
 *
 * 三种出口共用同一份 JSON 样本体：
 * - 本地 socket 流（NDJSON，每行一个 `{"op":"gnss_sample","args":{…}}`）；
 * - 落盘日志（JSONL，多一层 `verdict`/`reason`/`offset_ns` 便于复盘）；
 * - 未来同进程 FFI（把 `args` 对象直接映射到 `time_request` 的 args 即可）。
 */
object GnssWire {

    /** 协议版本：字段不兼容地变化时 +1。 */
    const val PROTOCOL_VERSION = 1

    val json: Json = Json {
        // 契约字段必须始终出现（即使等于默认值），因此 encodeDefaults = true；
        // 诊断字段为 null 时省略，避免给 Rust 侧塞一堆 null。
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    /** 单帧样本 → 契约里的 `args` 对象。 */
    fun argsJson(sample: GnssTimeSample): String = json.encodeToString(GnssTimeSample.serializer(), sample)

    /** 单帧样本 → `{"op":"gnss_sample","args":{…}}`。 */
    fun sampleFrame(sample: GnssTimeSample): String =
        """{"op":"gnss_sample","args":${argsJson(sample)}}"""

    /** 连接建立时的握手行，便于接收端确认协议版本与时间轴。 */
    fun helloFrame(hello: GnssDeviceHello): String = json.encodeToString(GnssDeviceHello.serializer(), hello)
        .let { """{"op":"gnss_hello","args":$it}""" }

    /** 落盘日志行（含判定上下文）。 */
    fun logLine(record: GnssLogRecord): String = json.encodeToString(GnssLogRecord.serializer(), record)

    fun verdictTag(verdict: GnssVerdict): String = when (verdict) {
        is GnssVerdict.Accepted -> TAG_ACCEPTED
        is GnssVerdict.Monitoring -> TAG_MONITORING
        is GnssVerdict.Rejected -> TAG_REJECTED
    }

    fun verdictReason(verdict: GnssVerdict): String? = when (verdict) {
        is GnssVerdict.Monitoring -> verdict.reason
        is GnssVerdict.Rejected -> verdict.reason
        else -> null
    }

    /**
     * 组装一条落盘记录。
     *
     * @param receivedUnixMs 本机收到样本的系统 UTC 毫秒（仅用于日志排序，不参与融合）
     * @param receivedMonoNs 本机收到样本的单调时间
     */
    fun logRecord(
        sample: GnssTimeSample,
        verdict: GnssVerdict,
        receivedUnixMs: Long,
        receivedMonoNs: Long,
    ): GnssLogRecord {
        val accepted = verdict as? GnssVerdict.Accepted
        val offsetVsSystemMs = verdict.offsetNs?.let { it / GnssTimeMath.NS_PER_MS.toDouble() }
        return GnssLogRecord(
            receivedUnixMs = receivedUnixMs,
            receivedMonoNs = receivedMonoNs,
            sample = sample,
            verdict = verdictTag(verdict),
            reason = verdictReason(verdict),
            offsetNs = verdict.offsetNs,
            weight = accepted?.weight,
            stepReady = accepted?.stepReady,
            offsetVsSystemMs = offsetVsSystemMs,
        )
    }

    const val TAG_ACCEPTED = "accepted"
    const val TAG_MONITORING = "monitoring"
    const val TAG_REJECTED = "rejected"
}

/** socket 握手：接收端据此判断时间轴与能力。 */
@Serializable
data class GnssDeviceHello(
    val protocol: Int = GnssWire.PROTOCOL_VERSION,
    val app: String = "IriX-Android",
    @SerialName("app_version") val appVersion: String = "",
    @SerialName("android") val androidVersion: String = "",
    @SerialName("sdk_int") val sdkInt: Int = 0,
    val model: String = "",
    /** 是否支持 Raw GNSS（GnssMeasurementsCallback 有回调）。 */
    @SerialName("raw_supported") val rawSupported: Boolean = false,
    /** Device-local boot-time axis, including suspend. Other devices must map it explicitly. */
    @SerialName("mono_axis") val monoAxis: String = "elapsedRealtimeNanos",
    @SerialName("mono_axis_scope") val monoAxisScope: String = "device-local boot time including suspend",
    @SerialName("external_axis_mapping") val externalAxisMapping: String = "External devices must explicitly map this device-local boot-time axis; no shared epoch or Rust zeitstempel alias is implied.",
)
