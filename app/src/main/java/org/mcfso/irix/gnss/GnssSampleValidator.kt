package org.mcfso.irix.gnss

import org.mcfso.irix.gnss.model.GnssFix
import org.mcfso.irix.gnss.model.GnssSource
import org.mcfso.irix.gnss.model.GnssTimeSample
import org.mcfso.irix.gnss.model.GnssVerdict
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** Local diagnostic result; neither disciplines a clock nor predicts remote acceptance. */
data class ValidatedSample(val sample: GnssTimeSample, val ageMs: Long, val verdict: GnssVerdict)

/** Device-local BOOTTIME checks. Freshness is nanosecond-exact; consistency is diagnostic only. */
class GnssSampleValidator(private val config: GnssValidatorConfig = GnssValidatorConfig()) {
    private var lastRawDiscontinuity: Int? = null
    private var lastSource: String? = null
    private var lastMonoNs: Long? = null
    private var lastUtcNs: Long? = null
    private var consistentFrames = 0
    private var referenceOffsetNs = 0L
    private var referenceSigmaNs = 0.0
    private val maxAgeNs = Math.multiplyExact(config.maxAgeMs, GnssTimeMath.NS_PER_MS)
    private val maxFutureSkewNs = Math.multiplyExact(config.maxFutureSkewMs, GnssTimeMath.NS_PER_MS)
    init {
        require(config.maxAgeMs in 0..2_000L) { "Freshness cannot exceed two seconds" }
        require(config.maxFutureSkewMs >= 0L)
        require(config.requiredConsistentFrames > 0 && config.weakFixSvThreshold >= 0)
        require(config.consistencySigmas.isFinite() && config.consistencySigmas > 0.0)
        require(config.ntpReferenceSigmaNs.isFinite() && config.ntpReferenceSigmaNs > 0.0)
    }
    fun reset() {
        lastRawDiscontinuity = null
        lastSource = null
        lastMonoNs = null
        lastUtcNs = null
        clearConsistency()
    }
    private fun clearConsistency() {
        consistentFrames = 0
        referenceOffsetNs = 0L
        referenceSigmaNs = 0.0
    }
    fun evaluate(sample: GnssTimeSample, nowMonoNs: Long, appUtcNsAtSampleMono: Long): ValidatedSample {
        if (lastSource != sample.source) {
            clearConsistency()
            lastRawDiscontinuity = null
            lastMonoNs = null
            lastUtcNs = null
            lastSource = sample.source
        }
        if (sample.monoNs < 0L || nowMonoNs < 0L) return reject(sample, 0L, GnssRejection.INVALID_MONO)
        // Nonnegative BOOTTIME operands make subtraction safe across the Long range.
        val ageNs = nowMonoNs - sample.monoNs
        val ageMs = max(0L, ageNs / GnssTimeMath.NS_PER_MS)
        if (!GnssTimeMath.isPlausibleUtcNs(sample.utcNs)) return reject(sample, ageMs, GnssRejection.UTC_OUT_OF_RANGE)
        if (ageNs < -maxFutureSkewNs) return reject(sample, ageMs, GnssRejection.MONO_FROM_FUTURE)
        if (ageNs > maxAgeNs) return reject(sample, ageMs, GnssRejection.STALE)
        if (sample.uncertaintyNs <= 0L) return reject(sample, ageMs, GnssRejection.INVALID_UNCERTAINTY)
        val offset = try { GnssTimeMath.offsetNs(sample.utcNs, appUtcNsAtSampleMono) }
        catch (_: ArithmeticException) { return reject(sample, ageMs, GnssRejection.ARITHMETIC_OVERFLOW) }
        if (sample.source == GnssSource.RAW.wire) {
            val previous = lastRawDiscontinuity
            lastRawDiscontinuity = sample.clockDiscontinuity
            if (previous != null && previous != sample.clockDiscontinuity) {
                lastMonoNs = sample.monoNs
                lastUtcNs = sample.utcNs
                return reject(sample, ageMs, GnssRejection.CLOCK_DISCONTINUITY)
            }
        }
        val previousMono = lastMonoNs
        // Cached UTC is a duplicate even if a caller stamps a new callback mono.
        if (previousMono != null && (sample.monoNs <= previousMono || sample.utcNs == lastUtcNs)) {
            return reject(sample, ageMs, GnssRejection.DUPLICATE_OR_OUT_OF_ORDER)
        }
        if (previousMono != null && sample.monoNs - previousMono > maxAgeNs) clearConsistency()
        lastMonoNs = sample.monoNs
        lastUtcNs = sample.utcNs
        val declared = sample.uncertaintyNs
        if (declared > GnssTimeMath.MAX_UNCERTAINTY_NS) {
            clearConsistency()
            return ValidatedSample(sample, ageMs, GnssVerdict.Monitoring(offset, declared, GnssRejection.UNCERTAINTY_TOO_LARGE))
        }
        val sigma = GnssTimeMath.clampUncertaintyNs(declared).toDouble()
        val weakReason = when {
            GnssFix.fromWire(sample.fix) != GnssFix.THREE_D -> GnssRejection.WEAK_FIX
            sample.svCount < config.weakFixSvThreshold -> GnssRejection.LOW_SV
            else -> null
        }
        if (weakReason != null) {
            clearConsistency()
            return ValidatedSample(sample, ageMs, GnssVerdict.Monitoring(offset, (sigma * sqrt(WEAK_VARIANCE_FACTOR)).toLong(), weakReason))
        }
        val delta = try { Math.subtractExact(offset, referenceOffsetNs).toDouble() }
        catch (_: ArithmeticException) { Double.POSITIVE_INFINITY }
        val consistent = consistentFrames > 0 && abs(delta) <= config.consistencySigmas * GnssTimeMath.combineSigmaNs(sigma, referenceSigmaNs)
        consistentFrames = if (consistent) (consistentFrames.toLong() + 1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else 1
        referenceOffsetNs = offset
        referenceSigmaNs = sigma
        return ValidatedSample(sample, ageMs, GnssVerdict.Accepted(
            offset, sigma.toLong(), GnssTimeMath.fusionWeight(sigma, config.ntpReferenceSigmaNs),
            consistentFrames >= config.requiredConsistentFrames, consistentFrames,
        ))
    }
    private fun reject(sample: GnssTimeSample, ageMs: Long, reason: String): ValidatedSample {
        clearConsistency()
        return ValidatedSample(sample, ageMs, GnssVerdict.Rejected(reason))
    }
    companion object { const val WEAK_VARIANCE_FACTOR = 10.0 }
}

data class GnssValidatorConfig(
    /** Local freshness bound, at most 2 s. */
    val maxAgeMs: Long = 2_000,
    val maxFutureSkewMs: Long = 100,
    val weakFixSvThreshold: Int = 5,
    /** Distinct consistent observations for the diagnostic stepReady field. */
    val requiredConsistentFrames: Int = 3,
    val consistencySigmas: Double = 5.0,
    /** Assumed reference for a heuristic weight, not measured NTP performance. */
    val ntpReferenceSigmaNs: Double = 10.0 * GnssTimeMath.NS_PER_MS,
)
object GnssRejection {
    const val UTC_OUT_OF_RANGE = "utc_out_of_range"
    const val MONO_FROM_FUTURE = "mono_from_future"
    const val INVALID_MONO = "invalid_mono"
    const val STALE = "stale"
    const val INVALID_UNCERTAINTY = "invalid_uncertainty"
    const val UNCERTAINTY_TOO_LARGE = "uncertainty_too_large"
    const val ARITHMETIC_OVERFLOW = "arithmetic_overflow"
    const val DUPLICATE_OR_OUT_OF_ORDER = "duplicate_or_out_of_order"
    const val CLOCK_DISCONTINUITY = "clock_discontinuity"
    const val WEAK_FIX = "weak_fix"
    const val LOW_SV = "low_sv"
}
/** Lifetime verdict counts; rolling accepted-only mean, sample sigma and extrema. */
class GnssStats(private val windowSize: Int = 120) {
    init { require(windowSize > 0) }
    private val recentOffsetsNs = ArrayDeque<Long>(windowSize)
    private var accepted = 0L
    private var monitoring = 0L
    private var rejected = 0L
    private val rejectionReasons = mutableMapOf<String, Long>()
    data class Snapshot(
        val accepted: Long = 0,
        val monitoring: Long = 0,
        val rejected: Long = 0,
        /** Accepted offsets in the rolling window, null if empty. */
        val meanOffsetNs: Double? = null,
        /** Sample standard deviation (n-1), null until two accepted offsets exist. */
        val sigmaNs: Double? = null,
        /** Extrema of the same rolling accepted-only window. */
        val minOffsetNs: Long? = null,
        val maxOffsetNs: Long? = null,
        val validity: Double = 0.0,
        val rejectionReasons: Map<String, Long> = emptyMap(),
    ) { val total: Long get() = accepted + monitoring + rejected }
    fun reset() {
        recentOffsetsNs.clear()
        accepted = 0L
        monitoring = 0L
        rejected = 0L
        rejectionReasons.clear()
    }
    fun record(verdict: GnssVerdict) {
        when (verdict) {
            is GnssVerdict.Accepted -> {
                accepted++
                if (recentOffsetsNs.size == windowSize) recentOffsetsNs.removeFirst()
                recentOffsetsNs.addLast(verdict.offsetNs)
            }
            is GnssVerdict.Monitoring -> monitoring++
            is GnssVerdict.Rejected -> {
                rejected++
                rejectionReasons[verdict.reason] = (rejectionReasons[verdict.reason] ?: 0L) + 1L
            }
        }
    }
    fun snapshot(): Snapshot {
        val n = recentOffsetsNs.size
        // Center before conversion to retain small jitter for large offsets.
        val origin = recentOffsetsNs.firstOrNull() ?: 0L
        fun relative(value: Long): Double = try { Math.subtractExact(value, origin).toDouble() }
        catch (_: ArithmeticException) { value.toDouble() - origin.toDouble() }
        val relativeMean = if (n == 0) 0.0 else recentOffsetsNs.sumOf { relative(it) } / n
        val mean = if (n == 0) null else origin.toDouble() + relativeMean
        val sigma = if (n < 2) null else sqrt(recentOffsetsNs.sumOf {
            val d = relative(it) - relativeMean
            d * d
        } / (n - 1))
        val total = accepted + monitoring + rejected
        return Snapshot(accepted, monitoring, rejected, mean, sigma, recentOffsetsNs.minOrNull(), recentOffsetsNs.maxOrNull(),
            if (total == 0L) 0.0 else accepted.toDouble() / total, rejectionReasons.toMap())
    }
}
