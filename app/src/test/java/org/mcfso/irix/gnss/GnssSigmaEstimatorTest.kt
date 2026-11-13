package org.mcfso.irix.gnss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GnssSigmaEstimatorTest {
    @Test
    fun steadyClockConvergesToFloor() {
        val estimator = GnssSigmaEstimator()
        var result = estimator.add(1_000_000_000_000L, 10_000_000_000L)
        repeat(20) { i ->
            result = estimator.add(
                1_000_000_000_000L + (i + 1) * 1_000_000_000L,
                10_000_000_000L + (i + 1) * 1_000_000_000L,
            )
        }
        assertEquals("empirical", result.source)
        assertTrue("steady clock should be near floor: ${result.sigmaNs}", result.sigmaNs <= 1_000L)
    }

    @Test
    fun jitterRaisesSigma() {
        val estimator = GnssSigmaEstimator()
        var result = estimator.add(1_000_000_000_000L, 10_000_000_000L)
        repeat(40) { i ->
            val jitter = if (i % 2 == 0) 1_000_000L else -1_000_000L
            result = estimator.add(
                1_000_000_000_000L + (i + 1) * 1_000_000_000L + jitter,
                10_000_000_000L + (i + 1) * 1_000_000_000L,
            )
        }
        assertEquals("empirical", result.source)
        assertTrue("1ms alternating jitter should not be 100ns: ${result.sigmaNs}", result.sigmaNs > 500_000L)
    }

    @Test
    fun jumpResetsHistory() {
        val estimator = GnssSigmaEstimator()
        var result = estimator.add(1_000_000_000_000L, 10_000_000_000L)
        repeat(10) { i ->
            result = estimator.add(
                1_000_000_000_000L + (i + 1) * 1_000_000_000L,
                10_000_000_000L + (i + 1) * 1_000_000_000L,
            )
        }
        assertEquals("empirical", result.source)
        // 2s jump: reset and fall back to prior
        result = estimator.add(1_002_000_000_000L, 10_011_000_000_000L)
        assertEquals("prior", result.source)
    }
}
