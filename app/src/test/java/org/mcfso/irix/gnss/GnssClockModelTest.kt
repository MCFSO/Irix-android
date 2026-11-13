package org.mcfso.irix.gnss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GnssClockModelTest {

    private val gnssBase = 1_000_000_000_000_000_000L
    private val monoBase = 5_000_000_000_000L
    private val step = GnssTimeMath.NS_PER_S

    @Test
    fun `estimate needs enough samples`() {
        val model = GnssClockModel()
        repeat(5) { i -> model.add(gnssBase + i * step, monoBase + i * step) }
        assertNull(model.estimate())
        assertEquals(5, model.sampleCount())
    }

    @Test
    fun `constant callback latency cancels and cannot be measured`() {
        val model = GnssClockModel()
        val latency = 1_500_000L // 1.5ms 固定回调延迟
        repeat(20) { i ->
            model.add(gnssBase + i * step, monoBase + i * step + latency)
        }
        val estimate = model.estimate()
        assertNotNull(estimate)
        requireNotNull(estimate)
        assertEquals(1.0, estimate.slope, 1e-9)
        assertEquals(0.0, estimate.offsetNs, 1.0)
        assertNull(estimate.latencyNs)
        assertTrue(!estimate.latencyValid)
        assertTrue(estimate.residualNs < 1.0)
        // 20s 窗口还不足以宣称漂移可信
        assertTrue(!estimate.driftReliable)
    }

    @Test
    fun `slope deviation reports drift in ppb`() {
        val model = GnssClockModel()
        val driftPpb = 100.0
        val slope = 1.0 + driftPpb / 1e9
        repeat(40) { i ->
            model.add(gnssBase + i * step, monoBase + (i * step * slope).toLong())
        }
        val estimate = model.estimate()!!
        assertEquals(driftPpb, estimate.driftPpb, 1.0)
        assertTrue(estimate.driftReliable)
    }

    @Test
    fun `non monotonic observations are dropped`() {
        val model = GnssClockModel()
        repeat(10) { i -> model.add(gnssBase + i * step, monoBase + i * step) }
        assertEquals(10, model.sampleCount())

        // GNSS 时间倒退 1s：丢弃
        model.add(gnssBase + 8 * step, monoBase + 11 * step)
        assertEquals(10, model.sampleCount())

        // CPU 单调时间倒退：丢弃
        model.add(gnssBase + 11 * step, monoBase + 8 * step)
        assertEquals(10, model.sampleCount())
    }

    @Test
    fun `reset clears window`() {
        val model = GnssClockModel()
        repeat(10) { i -> model.add(gnssBase + i * step, monoBase + i * step) }
        model.reset()
        assertEquals(0, model.sampleCount())
        assertNull(model.estimate())
    }
    @Test
    fun `duplicates and extreme invalid inputs do not pollute regression`() {
        val model = GnssClockModel()
        model.add(Long.MIN_VALUE, Long.MAX_VALUE)
        assertEquals(0, model.sampleCount())
        repeat(10) { i -> model.add(gnssBase + i * step, monoBase + i * step) }
        model.add(gnssBase + 9 * step, monoBase + 10 * step)
        assertEquals(10, model.sampleCount())
        assertNull(model.estimate()!!.latencyNs)
    }
    @Test
    fun `residual detects nanosecond jitter without cancellation`() {
        val model = GnssClockModel()
        repeat(40) { i -> model.add(gnssBase + i * step, monoBase + i * step + if (i % 2 == 0) 1L else -1L) }
        val estimate = model.estimate()!!
        assertTrue(estimate.residualNs > 0.9)
        assertTrue(estimate.residualNs < 1.1)
    }}
