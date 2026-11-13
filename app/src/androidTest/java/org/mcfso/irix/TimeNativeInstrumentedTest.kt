package org.mcfso.irix

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mcfso.irix.gnss.TimeNative

/**
 * Verifies the native JNI bridge and the Rust time system on a real device/emulator.
 * Run with: ./gradlew :app:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class TimeNativeInstrumentedTest {
    @Before
    fun requireBridge() {
        Assume.assumeTrue(
            "libxmc_time.so is only packaged for arm64-v8a today",
            TimeNative.isAvailable,
        )
    }

    @Test
    fun rustTimeSystemSelfTestPasses() {
        val result = TimeNative.call("self_test", "{}")
        assertNotNull("time_request returned null", result)
        assertTrue("self_test failed: $result", result!!.contains("\"ok\":true"))
    }

    @Test
    fun nowIsPlausible() {
        val ns = TimeNative.nowOrNull()
        assertNotNull(ns)
        // 2020-01-01 .. 2200-01-01
        assertTrue("now out of range: $ns", ns!! > 1_577_836_800_000_000_000L)
    }
}
