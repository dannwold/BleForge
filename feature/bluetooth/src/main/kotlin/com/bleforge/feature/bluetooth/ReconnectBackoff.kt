/*
 * ==============================================================================
 * FILE: feature/bluetooth/src/main/kotlin/com/bleforge/feature/bluetooth/ReconnectBackoff.kt
 * CURRENT VERSION: v1.0.18
 * CHANGELOG:
 *   - v1.0.1: Initial exponential reconnect backoff helper.
 *   - v1.0.18: Added full file header, KDoc, and inline comments (no functional changes).
 * HOW IT WORKS:
 *   Computes exponentially increasing reconnect delays (`initialDelayMs * 2^attempt`)
 *   capped at `maxDelayMs`, returning `null` once `maxAttempts` is exhausted so
 *   dropped BLE links don't spin-loop the radio.
 * ==============================================================================
 */
package com.bleforge.feature.bluetooth

import kotlin.math.min

/**
 * Thread-safe exponential backoff calculator for automatic BLE reconnection attempts.
 *
 * @property initialDelayMs Base delay in milliseconds for the first retry.
 * @property maxDelayMs Upper cap on any single retry delay.
 * @property maxAttempts Maximum consecutive retry attempts before giving up.
 */
class ReconnectBackoff(
    private val initialDelayMs: Long = 600L,
    private val maxDelayMs: Long = 15_000L,
    private val maxAttempts: Int = 5
) {
    private var attempt = 0

    /**
     * Returns the next backoff delay in milliseconds, or `null` if [maxAttempts]
     * has been reached.
     */
    @Synchronized
    fun nextDelayMs(): Long? {
        if (attempt >= maxAttempts) return null
        val delay = min(maxDelayMs, initialDelayMs * (1L shl attempt))
        attempt++
        return delay
    }

    /** Resets the retry counter after a successful GATT connection. */
    @Synchronized
    fun reset() { attempt = 0 }
}
