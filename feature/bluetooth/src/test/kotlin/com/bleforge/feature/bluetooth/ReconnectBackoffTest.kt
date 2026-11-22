/*
 * ==============================================================================
 * FILE: feature/bluetooth/src/test/kotlin/com/bleforge/feature/bluetooth/ReconnectBackoffTest.kt
 * CURRENT VERSION: v1.0.18
 * CHANGELOG:
 *   - v1.0.1: Initial unit test for ReconnectBackoff exponential doubling & reset.
 *   - v1.0.18: Added full file header and KDoc comments (no functional changes).
 * HOW IT WORKS:
 *   Verifies ReconnectBackoff doubles delays from 600ms up to maxAttempts=4,
 *   returns null on the 5th call, and restarts from 600ms after reset().
 * ==============================================================================
 */
package com.bleforge.feature.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Unit tests verifying [ReconnectBackoff] retry limits and reset behavior. */
class ReconnectBackoffTest {
    @Test
    fun `exponential backoff caps at maxAttempts and resets cleanly`() {
        val backoff = ReconnectBackoff(initialDelayMs = 600L, maxDelayMs = 5_000L, maxAttempts = 4)
        assertEquals(600L, backoff.nextDelayMs())
        assertEquals(1200L, backoff.nextDelayMs())
        assertEquals(2400L, backoff.nextDelayMs())
        assertEquals(4800L, backoff.nextDelayMs())
        // Exhausted retries must return null until reset() is called.
        assertNull(backoff.nextDelayMs())
        backoff.reset()
        assertEquals(600L, backoff.nextDelayMs())
    }
}
