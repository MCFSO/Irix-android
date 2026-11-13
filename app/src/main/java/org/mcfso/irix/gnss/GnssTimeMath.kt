package org.mcfso.irix.gnss

import org.mcfso.irix.gnss.model.GnssConstellation
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToLong

/** Pure time arithmetic. UTC uses Unix nanoseconds; mono uses this device's BOOTTIME axis. */
object GnssTimeMath {
    const val NS_PER_MS = 1_000_000L
    const val NS_PER_S = 1_000_000_000L
    const val NS_PER_US = 1_000L
    const val GPS_UNIX_EPOCH_OFFSET_SECONDS = 315_964_800L
    const val GPS_UNIX_EPOCH_OFFSET_NS = GPS_UNIX_EPOCH_OFFSET_SECONDS * NS_PER_S
    const val MIN_VALID_UTC_NS = 1_577_836_800L * NS_PER_S
    const val MAX_VALID_UTC_NS = 7_258_118_400L * NS_PER_S
    const val MIN_UNCERTAINTY_NS = 100L
    /** Above this threshold a sample is monitoring data, not locally accepted. */
    const val MAX_UNCERTAINTY_NS = 50L * NS_PER_MS
    const val MILLIS_SOURCE_FLOOR_NS = 2L * NS_PER_MS
    const val LOCATION_SOURCE_FLOOR_NS = 10L * NS_PER_MS
    const val DEFAULT_RAW_UNCERTAINTY_NS = 1L * NS_PER_MS
    /** Conservative allowance, not a measured callback latency. */
    const val DEFAULT_CALLBACK_LATENCY_NS = 2L * NS_PER_MS

    fun isPlausibleUtcNs(utcNs: Long): Boolean = utcNs in MIN_VALID_UTC_NS..MAX_VALID_UTC_NS

    /**
     * GPS time starts at 1980-01-06, not the Unix epoch.
     * UTC = timeNanos - fullBiasNanos - rounded(biasNanos) + GPS epoch - GPS-UTC leap.
     * Epoch-sized values stay integral to preserve nanosecond bias precision.
     * Missing leap/full bias, nonfinite bias, or overflowing intermediates return null.
     * The collector checks Android's hasLeapSecond flag.
     */
    fun utcNsFromGnssClock(
        timeNanos: Long,
        hasFullBiasNanos: Boolean,
        fullBiasNanos: Long,
        biasNanos: Double,
        leapSeconds: Int?,
    ): Long? {
        if (!hasFullBiasNanos || leapSeconds == null || leapSeconds < 0 || !biasNanos.isFinite()) return null
        // Double(Long.MAX_VALUE) is 2^63 and cannot safely convert to Long.
        if (biasNanos >= 9_223_372_036_854_775_808.0 || biasNanos < -9_223_372_036_854_775_808.0) return null
        return try {
            val gpsNs = Math.subtractExact(Math.subtractExact(timeNanos, fullBiasNanos), biasNanos.roundToLong())
            if (gpsNs < 0L) return null
            Math.subtractExact(
                Math.addExact(gpsNs, GPS_UNIX_EPOCH_OFFSET_NS),
                Math.multiplyExact(leapSeconds.toLong(), NS_PER_S),
            ).takeIf { it >= 0L }
        } catch (_: ArithmeticException) {
            null
        }
    }

    /** Conservative additive budget; invalid numeric inputs cannot produce a small sigma. */
    fun rawUncertaintyNs(
        biasUncertaintyNs: Double?,
        driftUncertaintyNsPerSec: Double?,
        ageSeconds: Double,
        latencyNs: Long,
    ): Long {
        if (!ageSeconds.isFinite() || ageSeconds < 0.0 || latencyNs < 0L) return Long.MAX_VALUE
        if (biasUncertaintyNs != null && (!biasUncertaintyNs.isFinite() || biasUncertaintyNs < 0.0)) return Long.MAX_VALUE
        if (driftUncertaintyNsPerSec != null && (!driftUncertaintyNsPerSec.isFinite() || driftUncertaintyNsPerSec < 0.0)) return Long.MAX_VALUE
        val total = (biasUncertaintyNs ?: DEFAULT_RAW_UNCERTAINTY_NS.toDouble()) +
            (driftUncertaintyNsPerSec ?: 0.0) * ageSeconds + latencyNs.toDouble()
        return if (!total.isFinite() || total >= Long.MAX_VALUE.toDouble()) Long.MAX_VALUE else total.roundToLong()
    }

    private fun ageBudgetNs(ageMs: Long, baseNs: Long): Long = try {
        Math.addExact(baseNs, Math.multiplyExact(max(0L, ageMs), NS_PER_MS))
    } catch (_: ArithmeticException) {
        Long.MAX_VALUE
    }

    /** Integer millisecond source: call duration plus age, with a 2 ms floor. */
    fun millisUncertaintyNs(callDurationNs: Long, ageMs: Long): Long =
        max(MILLIS_SOURCE_FLOOR_NS, ageBudgetNs(ageMs, max(0L, callDurationNs)))

    fun locationUncertaintyNs(ageMs: Long): Long = ageBudgetNs(ageMs, LOCATION_SOURCE_FLOOR_NS)

    /** Legacy name: minimum only. Never hide a declared sigma above the monitoring threshold. */
    fun clampUncertaintyNs(uncertaintyNs: Long): Long = uncertaintyNs.coerceAtLeast(MIN_UNCERTAINTY_NS)

    /** Checked projection; invalid extreme input throws ArithmeticException instead of wrapping. */
    fun appUtcNsAtMono(nowUtcMs: Long, nowMonoNs: Long, targetMonoNs: Long): Long =
        Math.addExact(Math.multiplyExact(nowUtcMs, NS_PER_MS), Math.subtractExact(targetMonoNs, nowMonoNs))

    fun offsetNs(gnssUtcNs: Long, appUtcNs: Long): Long = Math.subtractExact(gnssUtcNs, appUtcNs)

    fun combineSigmaNs(aNs: Double, bNs: Double): Double = hypot(aNs, bNs)

    /** Diagnostic heuristic against an assumed NTP sigma; not a calibrated fusion gain. */
    fun fusionWeight(sigmaNs: Double, ntpReferenceSigmaNs: Double = 10.0 * NS_PER_MS): Double {
        if (!sigmaNs.isFinite() || sigmaNs < 0.0 || !ntpReferenceSigmaNs.isFinite() || ntpReferenceSigmaNs <= 0.0) return 0.0
        val ratio = sigmaNs / ntpReferenceSigmaNs
        return 1.0 / (1.0 + ratio * ratio)
    }
}

/** Android constellation integers are independent of Android for JVM tests. */
object GnssConstellations {
    const val GPS = 1
    const val SBAS = 2
    const val GLONASS = 3
    const val QZSS = 4
    const val BEIDOU = 5
    const val GALILEO = 6
    const val IRNSS = 7

    /** QZSS uses GPS time; ignore SBAS/IRNSS as independent clock sources. */
    fun fold(usedConstellationTypes: Set<Int>): GnssConstellation {
        val gpsLike = QZSS in usedConstellationTypes || GPS in usedConstellationTypes
        val systems = buildSet {
            if (gpsLike) add(GnssConstellation.GPS)
            if (BEIDOU in usedConstellationTypes) add(GnssConstellation.BDS)
            if (GALILEO in usedConstellationTypes) add(GnssConstellation.GALILEO)
            if (GLONASS in usedConstellationTypes) add(GnssConstellation.GLONASS)
        }
        return when {
            systems.size > 1 -> GnssConstellation.MULTI
            systems.size == 1 -> systems.first()
            else -> GnssConstellation.GPS
        }
    }

    fun displayName(constellation: GnssConstellation): String = when (constellation) {
        GnssConstellation.GPS -> "GPS"
        GnssConstellation.BDS -> "北斗"
        GnssConstellation.GALILEO -> "Galileo"
        GnssConstellation.GLONASS -> "GLONASS"
        GnssConstellation.MULTI -> "多星座"
    }
}
