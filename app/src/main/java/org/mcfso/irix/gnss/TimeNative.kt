package org.mcfso.irix.gnss

/**
 * JNI bridge to the Rust `xmc_time` time system.
 *
 * Native Android apps cannot use Flutter's `dart:ffi`; this object loads
 * `libxmc_time.so` plus a tiny JNI shim (`libirix_time_jni.so`) and forwards
 * `time_request(op, args)` / `xmc_time_now_unix_ns` calls.
 *
 * The JNI shim lives in `app/src/main/cpp/time_jni.c`; the Rust library is
 * copied to `app/src/main/jniLibs/<abi>/libxmc_time.so` by
 * `tool/build_xmc_time_android.ps1`.
 */
object TimeNative {
    private val loaded: Boolean = try {
        System.loadLibrary("xmc_time")
        System.loadLibrary("irix_time_jni")
        true
    } catch (_: Throwable) {
        false
    }

    val isAvailable: Boolean get() = loaded

    @Volatile
    private var started = false

    private external fun request(op: String, args: String): String

    private external fun nowUnixNs(): Long

    /** Current application UTC nanoseconds, or null when the native library is absent. */
    fun nowOrNull(): Long? = if (loaded) {
        runCatching { nowUnixNs() }.getOrNull()
    } else {
        null
    }

    /** Calibrated app time in ms, falling back to the OS clock if the bridge is absent. */
    fun nowUnixMsOrSystem(): Long = nowOrNull()?.div(1_000_000) ?: System.currentTimeMillis()

    /**
     * Initialize the Rust clock. `auto_start=false`: GNSS samples drive the clock;
     * NTP can be enabled later by calling `request("configure", {\"auto_start\":true})`.
     */
    @Synchronized
    fun start(statePath: String): Boolean {
        if (!loaded || started) return loaded
        val args = """{"state_path":${jsonString(statePath)},"auto_start":false}"""
        val result = runCatching { request("start", args) }.getOrNull() ?: return false
        started = true
        return result.contains("\"ok\":true")
    }

    /** Feed one `gnss_sample` argument object (from `GnssWire.argsJson`). */
    fun ingest(argsJson: String): String? {
        if (!loaded) return null
        return runCatching { request("gnss_sample", argsJson) }.getOrNull()
    }

    /** Battery/thermal proxy temperature; Rust learns an equivalent temp coefficient. */
    fun setTemperature(celsius: Double): Boolean {
        if (!loaded || !celsius.isFinite()) return false
        val args = """{"celsius":$celsius}"""
        return runCatching { request("temperature", args).contains("\"ok\":true") }
            .getOrDefault(false)
    }

    /** Forward an arbitrary `time_request` op (status/configure/now/...). */
    fun call(op: String, argsJson: String = "{}"): String? {
        if (!loaded) return null
        return runCatching { request(op, argsJson) }.getOrNull()
    }

    private fun jsonString(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
