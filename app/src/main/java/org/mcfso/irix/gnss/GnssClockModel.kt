package org.mcfso.irix.gnss

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Diagnostic relative clock regression. Both axes are differenced against the first observation.
 * The intercept is a relative fit residual; clock origins and constant callback delay cancel.
 * Therefore this model cannot identify absolute callback latency and must not align timestamps.
 */
class GnssClockModel(private val capacity: Int = 64) {
    init { require(capacity >= 8) }
    private val gnssNs = LongArray(capacity)
    private val monoNs = LongArray(capacity)
    private var size = 0
    private var head = 0
    data class Estimate(
        val slope: Double,
        /** Relative intercept after subtracting the first sample; not an absolute clock offset. */
        val offsetNs: Double,
        val residualNs: Double,
        val samples: Int,
        val spanNs: Long,
    ) {
        val driftPpb: Double get() = (slope - 1.0) * 1e9
        /** Long-span relative drift diagnostic, not evidence of timestamp alignment. */
        val driftReliable: Boolean get() = spanNs >= 30L * GnssTimeMath.NS_PER_S
        val latencyValid: Boolean get() = false
        /** Absolute callback delay is unidentifiable from these observations. */
        val latencyNs: Long? get() = null
    }
    @Synchronized fun reset() { size = 0; head = 0 }
    @Synchronized fun add(gnssNs: Long, monoNs: Long) {
        if (gnssNs < 0L || monoNs < 0L) return
        if (size > 0) {
            val last = (head - 1 + capacity) % capacity
            if (gnssNs <= this.gnssNs[last] || monoNs <= this.monoNs[last]) return
        }
        this.gnssNs[head] = gnssNs
        this.monoNs[head] = monoNs
        head = (head + 1) % capacity
        if (size < capacity) size++
    }
    @Synchronized fun estimate(): Estimate? {
        if (size < 8) return null
        val oldest = (head - size + capacity) % capacity
        val baseGnss = gnssNs[oldest]
        val baseMono = monoNs[oldest]
        val x = DoubleArray(size) { (gnssNs[(oldest + it) % capacity] - baseGnss).toDouble() }
        val y = DoubleArray(size) { (monoNs[(oldest + it) % capacity] - baseMono).toDouble() }
        val meanX = x.average()
        val meanY = y.average()
        var xx = 0.0
        var xy = 0.0
        for (i in x.indices) { val dx = x[i] - meanX; xx += dx * dx; xy += dx * (y[i] - meanY) }
        if (xx <= 0.0 || !xx.isFinite()) return null
        val slope = xy / xx
        if (!slope.isFinite() || abs(slope - 1.0) > 0.01) return null
        val intercept = meanY - slope * meanX
        // Compute residuals directly instead of subtracting nearly equal epoch-sized sums.
        val rms = sqrt(x.indices.sumOf { val r = y[it] - intercept - slope * x[it]; r * r } / size)
        if (!intercept.isFinite() || !rms.isFinite()) return null
        val newest = (head - 1 + capacity) % capacity
        return Estimate(slope, intercept, rms, size, gnssNs[newest] - baseGnss)
    }
    @Synchronized fun sampleCount(): Int = size
}
