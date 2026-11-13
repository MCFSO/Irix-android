package org.mcfso.irix.gnss

import org.junit.Assert.*
import org.junit.Test
import org.mcfso.irix.gnss.model.*
import kotlin.math.sqrt

class GnssSampleValidatorTest {
    private val mono = 5_000_000_000_000L
    private val utc = 1_767_225_600_000_000_000L
    private val error = 42_000_000L
    private fun sample(frame: Long = 0L) = GnssTimeSample(
        utcNs = utc + frame * GnssTimeMath.NS_PER_S,
        monoNs = mono + frame * GnssTimeMath.NS_PER_S,
        uncertaintyNs = 800L, svCount = 12, fix = "3d", leapSeconds = 18,
    )
    private fun evaluate(v: GnssSampleValidator, s: GnssTimeSample, now: Long = s.monoNs, offset: Long = error) =
        v.evaluate(s, now, s.utcNs - offset).verdict
    private fun accepted(v: GnssVerdict): GnssVerdict.Accepted {
        assertTrue("Expected accepted: $v", v is GnssVerdict.Accepted)
        return v as GnssVerdict.Accepted
    }
    @Test fun `healthy sample has diagnostic offset and weight`() {
        val result = accepted(evaluate(GnssSampleValidator(), sample()))
        assertEquals(error, result.offsetNs)
        assertEquals(800L, result.effectiveUncertaintyNs)
        assertTrue(result.weight > 0.99)
        assertEquals(1, result.consistentFrames)
        assertFalse(result.stepReady)
    }
    @Test fun `three distinct consistent observations reach diagnostic readiness`() {
        val v = GnssSampleValidator()
        assertFalse(accepted(evaluate(v, sample(0))).stepReady)
        assertFalse(accepted(evaluate(v, sample(1))).stepReady)
        val third = accepted(evaluate(v, sample(2)))
        assertTrue(third.stepReady)
        assertEquals(3, third.consistentFrames)
    }
    @Test fun `duplicate timestamps cannot accumulate three frames`() {
        val v = GnssSampleValidator()
        accepted(evaluate(v, sample()))
        repeat(2) { assertEquals(GnssVerdict.Rejected(GnssRejection.DUPLICATE_OR_OUT_OF_ORDER), evaluate(v, sample())) }
        assertEquals(1, accepted(evaluate(v, sample(1))).consistentFrames)
    }
    @Test fun `cached UTC and decreasing mono are rejected`() {
        val v = GnssSampleValidator()
        accepted(evaluate(v, sample()))
        assertEquals(GnssVerdict.Rejected(GnssRejection.DUPLICATE_OR_OUT_OF_ORDER), evaluate(v, sample(1).copy(utcNs = utc)))
        assertEquals(GnssVerdict.Rejected(GnssRejection.DUPLICATE_OR_OUT_OF_ORDER), evaluate(v, sample(-1)))
    }
    @Test fun `offset inconsistency restarts counter with a fixed application clock fixture`() {
        val v = GnssSampleValidator()
        accepted(evaluate(v, sample()))
        accepted(evaluate(v, sample(1)))
        val jump = accepted(evaluate(v, sample(2), offset = error + GnssTimeMath.NS_PER_S))
        assertEquals(1, jump.consistentFrames)
        assertFalse(jump.stepReady)
    }
    @Test fun `freshness uses nanoseconds at two second boundary`() {
        assertTrue(evaluate(GnssSampleValidator(), sample(), mono + 2_000_000_000L) is GnssVerdict.Accepted)
        assertEquals(GnssVerdict.Rejected(GnssRejection.STALE), evaluate(GnssSampleValidator(), sample(), mono + 2_000_000_001L))
    }
    @Test fun `future boundary and extreme timestamps are safe`() {
        assertTrue(evaluate(GnssSampleValidator(), sample(), mono - 100_000_000L) is GnssVerdict.Accepted)
        assertEquals(GnssVerdict.Rejected(GnssRejection.MONO_FROM_FUTURE), evaluate(GnssSampleValidator(), sample(), mono - 100_000_001L))
        assertEquals(GnssVerdict.Rejected(GnssRejection.STALE), evaluate(GnssSampleValidator(), sample().copy(monoNs = 0L), Long.MAX_VALUE))
        assertEquals(GnssVerdict.Rejected(GnssRejection.INVALID_MONO), evaluate(GnssSampleValidator(), sample().copy(monoNs = Long.MIN_VALUE), Long.MAX_VALUE))
        assertEquals(GnssVerdict.Rejected(GnssRejection.ARITHMETIC_OVERFLOW), GnssSampleValidator().evaluate(sample(), mono, Long.MIN_VALUE).verdict)
    }
    @Test fun `invalid sigma rejected while large sigma remains honest monitoring`() {
        for (sigma in listOf(-1L, 0L, Long.MIN_VALUE)) {
            assertEquals(GnssVerdict.Rejected(GnssRejection.INVALID_UNCERTAINTY), evaluate(GnssSampleValidator(), sample().copy(uncertaintyNs = sigma)))
        }
        for (sigma in listOf(60_000_000L, Long.MAX_VALUE)) {
            val verdict = evaluate(GnssSampleValidator(), sample().copy(uncertaintyNs = sigma)) as GnssVerdict.Monitoring
            assertEquals(sigma, verdict.effectiveUncertaintyNs)
            assertEquals(GnssRejection.UNCERTAINTY_TOO_LARGE, verdict.reason)
        }
        assertEquals(100L, accepted(evaluate(GnssSampleValidator(), sample().copy(uncertaintyNs = 1L))).effectiveUncertaintyNs)
    }
    @Test fun `weak fix and low satellites are monitored`() {
        val weak = evaluate(GnssSampleValidator(), sample().copy(fix = "2d")) as GnssVerdict.Monitoring
        assertEquals(GnssRejection.WEAK_FIX, weak.reason)
        assertEquals((800.0 * sqrt(10.0)).toLong(), weak.effectiveUncertaintyNs)
        assertEquals(GnssRejection.LOW_SV, (evaluate(GnssSampleValidator(), sample().copy(svCount = 3)) as GnssVerdict.Monitoring).reason)
    }
    @Test fun `source changes gaps monitoring and rejection break continuity`() {
        for (interruption in 0..3) {
            val v = GnssSampleValidator()
            accepted(evaluate(v, sample()))
            accepted(evaluate(v, sample(1)))
            val next = when (interruption) {
                0 -> sample(2).copy(source = GnssSource.GNSS_TIME_MILLIS.wire)
                1 -> sample(5)
                2 -> { evaluate(v, sample(2).copy(fix = "2d")); sample(3) }
                else -> { evaluate(v, sample(2).copy(utcNs = 0L)); sample(3) }
            }
            assertEquals(1, accepted(evaluate(v, next)).consistentFrames)
        }
    }
    @Test fun `raw hardware discontinuity rejects once and restarts consistency`() {
        val v = GnssSampleValidator()
        accepted(evaluate(v, sample().copy(clockDiscontinuity = 5)))
        assertEquals(GnssVerdict.Rejected(GnssRejection.CLOCK_DISCONTINUITY), evaluate(v, sample(1).copy(clockDiscontinuity = 6)))
        assertEquals(1, accepted(evaluate(v, sample(2).copy(clockDiscontinuity = 6))).consistentFrames)
    }
    @Test fun `source switch resets raw discontinuity history`() {
        val v = GnssSampleValidator()
        accepted(evaluate(v, sample().copy(clockDiscontinuity = 5)))
        accepted(evaluate(v, sample(1).copy(source = "location", clockDiscontinuity = 9)))
        assertEquals(1, accepted(evaluate(v, sample(2).copy(clockDiscontinuity = 99))).consistentFrames)
    }
    @Test fun `stats use only accepted offsets and rolling extrema`() {
        val stats = GnssStats(3)
        fun accept(offset: Long) { stats.record(GnssVerdict.Accepted(offset, 800L, 1.0, false, 1)) }
        accept(10); accept(20); accept(30)
        stats.record(GnssVerdict.Monitoring(999, 60_000_000L, GnssRejection.UNCERTAINTY_TOO_LARGE))
        stats.record(GnssVerdict.Rejected(GnssRejection.STALE))
        val s = stats.snapshot()
        assertEquals(3L, s.accepted); assertEquals(1L, s.monitoring); assertEquals(1L, s.rejected)
        assertEquals(20.0, s.meanOffsetNs!!, 0.0); assertEquals(10.0, s.sigmaNs!!, 0.0)
        assertEquals(10L, s.minOffsetNs); assertEquals(30L, s.maxOffsetNs)
        assertEquals(0.6, s.validity, 0.0)
        accept(40)
        val rolled = stats.snapshot()
        assertEquals(20L, rolled.minOffsetNs); assertEquals(40L, rolled.maxOffsetNs)
        assertEquals(30.0, rolled.meanOffsetNs!!, 0.0)
        stats.reset()
        assertNull(stats.snapshot().meanOffsetNs); assertNull(stats.snapshot().sigmaNs)
    }
    @Test fun `stats preserve nanosecond jitter around large offsets`() {
        val stats = GnssStats()
        for (i in 0L..2L) stats.record(GnssVerdict.Accepted(utc + i, 100L, 1.0, false, 1))
        assertEquals(1.0, stats.snapshot().sigmaNs!!, 0.0)
    }
    @Test(expected = IllegalArgumentException::class) fun `empty stats window is invalid`() { GnssStats(0) }
}
