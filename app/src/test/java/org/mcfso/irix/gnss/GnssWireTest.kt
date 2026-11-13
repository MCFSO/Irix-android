package org.mcfso.irix.gnss

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mcfso.irix.gnss.model.GnssConstellation
import org.mcfso.irix.gnss.model.GnssFix
import org.mcfso.irix.gnss.model.GnssSource
import org.mcfso.irix.gnss.model.GnssTimeSample
import org.mcfso.irix.gnss.model.GnssVerdict
import org.mcfso.irix.gnss.wire.GnssDeviceHello
import org.mcfso.irix.gnss.wire.GnssWire

class GnssWireTest {

    private val sample = GnssTimeSample(
        utcNs = 1_767_225_600_000_000_000L,
        monoNs = 5_000_000_000_000L,
        uncertaintyNs = 800L,
        constellation = GnssConstellation.MULTI.wire,
        svCount = 14,
        fix = GnssFix.THREE_D.wire,
        leapSeconds = 18,
        source = GnssSource.RAW.wire,
        clockDiscontinuity = 3,
        ageMs = 12L,
    )

    @Test
    fun `sample frame uses the gnss_sample op with contract keys`() {
        val frame = Json.parseToJsonElement(GnssWire.sampleFrame(sample)).jsonObject
        assertEquals("gnss_sample", frame["op"]!!.jsonPrimitive.content)

        val args = frame["args"]!!.jsonObject
        val expected = setOf(
            "utc_ns", "mono_ns", "uncertainty_ns", "constellation", "sv_count",
            "fix", "leap_seconds", "source", "clock_discontinuity", "age_ms",
        )
        assertEquals(expected, args.keys)
    }

    @Test
    fun `contract values are serialized verbatim`() {
        val args = Json.parseToJsonElement(GnssWire.argsJson(sample)).jsonObject
        assertEquals("1767225600000000000", args["utc_ns"]!!.jsonPrimitive.content)
        assertEquals("5000000000000", args["mono_ns"]!!.jsonPrimitive.content)
        assertEquals("800", args["uncertainty_ns"]!!.jsonPrimitive.content)
        assertEquals("multi", args["constellation"]!!.jsonPrimitive.content)
        assertEquals("14", args["sv_count"]!!.jsonPrimitive.content)
        assertEquals("3d", args["fix"]!!.jsonPrimitive.content)
        assertEquals("18", args["leap_seconds"]!!.jsonPrimitive.content)
        assertEquals("raw", args["source"]!!.jsonPrimitive.content)
        assertEquals("3", args["clock_discontinuity"]!!.jsonPrimitive.content)
        assertEquals("12", args["age_ms"]!!.jsonPrimitive.content)
    }

    @Test
    fun `diagnostics are omitted when null and present when set`() {
        assertFalse(GnssWire.argsJson(sample).contains("latency_ns"))
        val withDiagnostics = sample.copy(latencyNs = 1_500_000L, model = "Pixel", sdkInt = 35)
        val json = GnssWire.argsJson(withDiagnostics)
        assertTrue(json.contains("\"latency_ns\":1500000"))
        assertTrue(json.contains("\"model\":\"Pixel\""))
    }

    @Test
    fun `hello frame advertises protocol and mono axis`() {
        val frame = Json.parseToJsonElement(GnssWire.helloFrame(GnssDeviceHello(sdkInt = 35, rawSupported = true))).jsonObject
        assertEquals("gnss_hello", frame["op"]!!.jsonPrimitive.content)
        val args = frame["args"]!!.jsonObject
        assertEquals("1", args["protocol"]!!.jsonPrimitive.content)
        assertEquals("elapsedRealtimeNanos", args["mono_axis"]!!.jsonPrimitive.content)
        assertEquals("true", args["raw_supported"]!!.jsonPrimitive.content)
    }

    @Test
    fun `log record carries the verdict context`() {
        val verdict = GnssVerdict.Accepted(
            offsetNs = 42_000_000L,
            effectiveUncertaintyNs = 800L,
            weight = 0.999,
            stepReady = true,
            consistentFrames = 3,
        )
        val record = GnssWire.logRecord(sample, verdict, receivedUnixMs = 1_767_225_600_100L, receivedMonoNs = 5_000_000_000_100L)
        val line = Json.parseToJsonElement(GnssWire.logLine(record)).jsonObject
        assertEquals("accepted", line["verdict"]!!.jsonPrimitive.content)
        assertEquals("42000000", line["offset_ns"]!!.jsonPrimitive.content)
        assertEquals("true", line["step_ready"]!!.jsonPrimitive.content)
        assertNull(line["reason"])
        assertEquals(42.0, line["offset_vs_system_ms"]!!.jsonPrimitive.content.toDouble(), 1e-9)
    }

    @Test
    fun `rejected log record keeps the reason and drops offset`() {
        val record = GnssWire.logRecord(
            sample,
            GnssVerdict.Rejected(GnssRejection.STALE),
            receivedUnixMs = 1L,
            receivedMonoNs = 2L,
        )
        val line = Json.parseToJsonElement(GnssWire.logLine(record)).jsonObject
        assertEquals("rejected", line["verdict"]!!.jsonPrimitive.content)
        assertEquals("stale", line["reason"]!!.jsonPrimitive.content)
        assertNull(line["offset_ns"])
        assertNull(line["weight"])
    }
    @Test
    fun `unknown leap seconds remain absent rather than default to eighteen`() {
        val unknown = GnssTimeSample(utcNs = sample.utcNs, monoNs = sample.monoNs, uncertaintyNs = 100L)
        assertNull(unknown.leapSeconds)
        val args = Json.parseToJsonElement(GnssWire.argsJson(unknown)).jsonObject
        assertFalse(args.containsKey("leap_seconds"))
        assertNull(GnssWire.json.decodeFromString(GnssTimeSample.serializer(), GnssWire.argsJson(unknown)).leapSeconds)
    }
    @Test
    fun `alignment diagnostics have explicit snake case wire names`() {
        val diagnostics = sample.copy(alignment = "direct", elapsedRealtimeUncertaintyNs = 12.5, biasUncertaintyNs = 2.5, driftNsPerS = -0.5)
        val args = Json.parseToJsonElement(GnssWire.argsJson(diagnostics)).jsonObject
        assertEquals("direct", args["alignment"]!!.jsonPrimitive.content)
        assertEquals("12.5", args["elapsed_realtime_uncertainty_ns"]!!.jsonPrimitive.content)
        assertEquals("2.5", args["bias_uncertainty_ns"]!!.jsonPrimitive.content)
        assertEquals("-0.5", args["drift_ns_per_s"]!!.jsonPrimitive.content)
    }
    @Test
    fun `hello explicitly requires external device axis mapping`() {
        val args = Json.parseToJsonElement(GnssWire.helloFrame(GnssDeviceHello())).jsonObject["args"]!!.jsonObject
        assertTrue(args["mono_axis_scope"]!!.jsonPrimitive.content.contains("device-local boot time"))
        assertTrue(args["external_axis_mapping"]!!.jsonPrimitive.content.contains("must explicitly map"))
        assertTrue(args["external_axis_mapping"]!!.jsonPrimitive.content.contains("no shared epoch"))
    }}
