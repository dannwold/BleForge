/*
 * ==============================================================================
 * FILE: feature/bluetooth/src/test/kotlin/com/bleforge/feature/bluetooth/ScanRateLimiterTest.kt
 * CURRENT VERSION: v1.0.18
 * CHANGELOG:
 *   - v1.0.1: Initial unit test for Android's 5-scans-per-30s sliding window.
 *   - v1.0.18: Added full file header and KDoc comments (no functional changes).
 * HOW IT WORKS:
 *   Uses a deterministic fake clock to simulate 5 rapid scans, verifies the 6th
 *   scan returns a positive wait time, and confirms recovery once 30s elapse.
 * ==============================================================================
 */
package com.bleforge.feature.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests verifying [ScanRateLimiter] sliding-window throttling. */
class ScanRateLimiterTest {
    @Test
    fun `allows up to 5 scans in 30 seconds then throttles 6th`() {
        var now = 1_000L
        val limiter = ScanRateLimiter(maxScans = 5, windowMs = 30_000L, clockMs = { now })

        repeat(5) {
            assertEquals(0L, limiter.tryAcquire())
            now += 1_000L
        }

        val retryMs = limiter.tryAcquire()
        assertTrue("Expected positive throttle wait, got $retryMs", retryMs > 0L)

        // Advance past the 30s window for the first scan.
        now += 26_000L
        assertEquals(0L, limiter.tryAcquire())
    }
}
