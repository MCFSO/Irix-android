package org.mcfso.irix.gnss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mcfso.irix.gnss.model.GnssConstellation

class GnssTimeMathTest {

    private val utc2026 = 1_767_225_600L * GnssTimeMath.NS_PER_S // 2026-01-01T00:00:00Z

    @Test
    fun `raw clock to utc subtracts full bias and leap seconds`() {
        val fullBias = -1_000_000_000_000_000_000L
        val gpsNs = utc2026 - GnssTimeMath.GPS_UNIX_EPOCH_OFFSET_NS + 18L * GnssTimeMath.NS_PER_S
        val timeNanos = gpsNs + fullBias

        val utcNs = GnssTimeMath.utcNsFromGnssClock(
            timeNanos = timeNanos,
            hasFullBiasNanos = true,
            fullBiasNanos = fullBias,
            biasNanos = 0.0,
            leapSeconds = 18,
        )

        assertEquals(utc2026, utcNs)
    }

    @Test
    fun `fractional bias is rounded not truncated`() {
        val fullBias = -1_000_000_000_000_000_000L
        val gpsNs = utc2026 - GnssTimeMath.GPS_UNIX_EPOCH_OFFSET_NS + 18L * GnssTimeMath.NS_PER_S
        val timeNanos = gpsNs + fullBias

        // biasNanos 0.6 → 1ns，UTC 应比无 bias 时少 1ns
        val utcNs = GnssTimeMath.utcNsFromGnssClock(timeNanos, true, fullBias, 0.6, 18)
        assertEquals(utc2026 - 1, utcNs)
    }

    @Test
    fun `missing full bias yields no absolute utc`() {
        assertNull(GnssTimeMath.utcNsFromGnssClock(1_000L, hasFullBiasNanos = false, 0L, 0.0, 18))
        assertNull(GnssTimeMath.utcNsFromGnssClock(1_000L, true, 0L, 0.0, null))
    }

    @Test
    fun `plausible utc range is 2020 to 2200`() {
        assertFalse(GnssTimeMath.isPlausibleUtcNs(1_000_000_000_000_000_000L)) // 2001
        assertTrue(GnssTimeMath.isPlausibleUtcNs(utc2026))
        assertTrue(GnssTimeMath.isPlausibleUtcNs(GnssTimeMath.MAX_VALID_UTC_NS))
        assertFalse(GnssTimeMath.isPlausibleUtcNs(GnssTimeMath.MAX_VALID_UTC_NS + 1))
    }

    @Test
    fun `millis source uncertainty floors at two milliseconds`() {
        assertEquals(2L * GnssTimeMath.NS_PER_MS, GnssTimeMath.millisUncertaintyNs(0L, 0L))
        // 调用耗时 3ms 时按调用耗时计
        assertEquals(3L * GnssTimeMath.NS_PER_MS, GnssTimeMath.millisUncertaintyNs(3L * GnssTimeMath.NS_PER_MS, 0L))
        // 年龄累加
        assertEquals(2L * GnssTimeMath.NS_PER_MS, GnssTimeMath.millisUncertaintyNs(GnssTimeMath.NS_PER_MS, 1L))
    }

    @Test
    fun `uncertainty has a floor but no downward cap`() {
        assertEquals(GnssTimeMath.MIN_UNCERTAINTY_NS, GnssTimeMath.clampUncertaintyNs(1L))
        assertEquals(100L * GnssTimeMath.NS_PER_MS, GnssTimeMath.clampUncertaintyNs(100L * GnssTimeMath.NS_PER_MS))
    }

    @Test
    fun `raw uncertainty grows with age and latency`() {
        val base = GnssTimeMath.rawUncertaintyNs(500.0, 10.0, 1.0, 2_000_000L)
        assertEquals(500.0 + 10.0 + 2_000_000.0, base.toDouble(), 1.0)

        val older = GnssTimeMath.rawUncertaintyNs(500.0, 10.0, 10.0, 2_000_000L)
        assertTrue(older > base)
    }

    @Test
    fun `raw uncertainty falls back to one millisecond without bias uncertainty`() {
        val ns = GnssTimeMath.rawUncertaintyNs(null, null, 0.0, 0L)
        assertEquals(GnssTimeMath.DEFAULT_RAW_UNCERTAINTY_NS, ns)
    }

    @Test
    fun `app clock projection and offset`() {
        val nowUtcMs = 1_767_225_600_000L
        val nowMono = 5_000_000_000_000L
        val appUtcAtNow = GnssTimeMath.appUtcNsAtMono(nowUtcMs, nowMono, nowMono)
        assertEquals(nowUtcMs * GnssTimeMath.NS_PER_MS, appUtcAtNow)

        // 早了 1s 的样本：应用时钟应回退 1s
        assertEquals(
            nowUtcMs * GnssTimeMath.NS_PER_MS - GnssTimeMath.NS_PER_S,
            GnssTimeMath.appUtcNsAtMono(nowUtcMs, nowMono, nowMono - GnssTimeMath.NS_PER_S),
        )

        assertEquals(42L, GnssTimeMath.offsetNs(1_000_000_042L, 1_000_000_000L))
    }

    @Test
    fun `fusion weight is variance ratio against ntp reference`() {
        assertEquals(1.0, GnssTimeMath.fusionWeight(0.0), 1e-12)
        assertEquals(0.5, GnssTimeMath.fusionWeight(10.0 * GnssTimeMath.NS_PER_MS), 1e-6)
        // 1ms 的 σ 对 10ms 的 NTP 基准几乎不降权
        assertTrue(GnssTimeMath.fusionWeight(1.0 * GnssTimeMath.NS_PER_MS) > 0.98)
    }

    @Test
    fun `constellations fold into contract values`() {
        assertEquals(GnssConstellation.GPS, GnssConstellations.fold(setOf(GnssConstellations.GPS)))
        // QZSS 与 GPS 时间同源
        assertEquals(GnssConstellation.GPS, GnssConstellations.fold(setOf(GnssConstellations.QZSS)))
        assertEquals(GnssConstellation.BDS, GnssConstellations.fold(setOf(GnssConstellations.BEIDOU)))
        assertEquals(
            GnssConstellation.MULTI,
            GnssConstellations.fold(setOf(GnssConstellations.GPS, GnssConstellations.BEIDOU)),
        )
        assertEquals(
            GnssConstellation.MULTI,
            GnssConstellations.fold(setOf(GnssConstellations.GLONASS, GnssConstellations.GALILEO)),
        )
        // SBAS 不提供授权时钟，单独出现时退回 gps
        assertEquals(GnssConstellation.GPS, GnssConstellations.fold(setOf(GnssConstellations.SBAS)))
        assertEquals(GnssConstellation.GPS, GnssConstellations.fold(emptySet()))
    }
    @Test
    fun `GPS epoch zero maps to Unix 1980 epoch`() {
        assertEquals(GnssTimeMath.GPS_UNIX_EPOCH_OFFSET_NS,
            GnssTimeMath.utcNsFromGnssClock(0L, true, 0L, 0.0, 0))
    }

    @Test
    fun `realistic hardware clock preserves individual nanoseconds`() {
        val hardware = 5_000_000_000_000L
        val gps = utc2026 - GnssTimeMath.GPS_UNIX_EPOCH_OFFSET_NS + 18L * GnssTimeMath.NS_PER_S
        val fullBias = hardware - gps
        for (ns in 0L..255L) {
            assertEquals(utc2026 + ns - 1L,
                GnssTimeMath.utcNsFromGnssClock(hardware + ns, true, fullBias, 0.6, 18))
        }
    }

    @Test
    fun `nonfinite bias and overflowing raw arithmetic cannot yield utc`() {
        for (bias in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.MAX_VALUE, Long.MAX_VALUE.toDouble())) {
            assertNull(GnssTimeMath.utcNsFromGnssClock(1L, true, -1L, bias, 18))
        }
        assertNull(GnssTimeMath.utcNsFromGnssClock(Long.MAX_VALUE, true, -1L, 0.0, 18))
        assertNull(GnssTimeMath.utcNsFromGnssClock(Long.MIN_VALUE, true, 1L, 0.0, 18))
        assertNull(GnssTimeMath.utcNsFromGnssClock(Long.MAX_VALUE, true, 0L, 0.0, 0))
        assertNull(GnssTimeMath.utcNsFromGnssClock(1L, true, 0L, 0.0, -1))
    }

    @Test
    fun `invalid uncertainty and age overflow never wrap to small sigma`() {
        assertEquals(Long.MAX_VALUE, GnssTimeMath.rawUncertaintyNs(Double.NaN, null, 0.0, 0L))
        assertEquals(Long.MAX_VALUE, GnssTimeMath.rawUncertaintyNs(-1.0, null, 0.0, 0L))
        assertEquals(Long.MAX_VALUE, GnssTimeMath.rawUncertaintyNs(1.0, Double.POSITIVE_INFINITY, 1.0, 0L))
        assertEquals(Long.MAX_VALUE, GnssTimeMath.rawUncertaintyNs(null, null, Double.NaN, 0L))
        assertEquals(Long.MAX_VALUE, GnssTimeMath.millisUncertaintyNs(1L, Long.MAX_VALUE))
        assertEquals(Long.MAX_VALUE, GnssTimeMath.locationUncertaintyNs(Long.MAX_VALUE))
        assertEquals(0.0, GnssTimeMath.fusionWeight(Double.NaN), 0.0)
        assertEquals(0.0, GnssTimeMath.fusionWeight(-1.0), 0.0)
        assertTrue(GnssTimeMath.combineSigmaNs(1e300, 1e300).isFinite())
    }

    @Test(expected = ArithmeticException::class)
    fun `projection rejects epoch multiplication overflow`() {
        GnssTimeMath.appUtcNsAtMono(Long.MAX_VALUE, 0L, 0L)
    }

    @Test(expected = ArithmeticException::class)
    fun `offset rejects overflow`() {
        GnssTimeMath.offsetNs(Long.MAX_VALUE, -1L)
    }}
