/*
 * ==============================================================================
 * FILE: feature/bluetooth/src/main/kotlin/com/bleforge/feature/bluetooth/ScanRateLimiter.kt
 * CURRENT VERSION: v1.0.18
 * CHANGELOG:
 *   - v1.0.1: Initial sliding-window BLE scan rate limiter.
 *   - v1.0.18: Added full file header, KDoc, and inline comments (no functional changes).
 * HOW IT WORKS:
 *   Tracks scan start timestamps in a synchronized ArrayDeque over a 30-second
 *   sliding window; returns 0L when permitted or the remaining wait time in ms
 *   when Android's 5-scans-per-30s limit would otherwise be exceeded.
 * ==============================================================================
 */
package com.bleforge.feature.bluetooth

/**
 * Enforces Android's 5-scans-per-30-seconds limit so the stack never
 * hits silent SCAN_FAILED_APPLICATION_REGISTRATION_FAILED throttling.
 *
 * @property maxScans Maximum allowed scan starts within [windowMs].
 * @property windowMs Sliding window duration in milliseconds.
 * @property clockMs Injectable time source for deterministic unit testing.
 */
class ScanRateLimiter(
    private val maxScans: Int = 5,
    private val windowMs: Long = 30_000L,
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) {
    private val timestamps = ArrayDeque<Long>()

    /**
     * Attempts to reserve a scan slot. Returns `0L` if allowed immediately,
     * or a positive wait duration in milliseconds until the oldest slot expires.
     */
    @Synchronized
    fun tryAcquire(): Long {
        val now = clockMs()
        // Evict expired timestamps outside the active sliding window.
        while (timestamps.isNotEmpty() && now - timestamps.first() >= windowMs) {
            timestamps.removeFirst()
        }
        return if (timestamps.size < maxScans) {
            timestamps.addLast(now)
            0L
        } else {
            val oldest = timestamps.first()
            (windowMs - (now - oldest)).coerceAtLeast(1L)
        }
    }
}
