/*
 * ==============================================================================
 * FILE: core/model/src/test/kotlin/com/bleforge/core/model/ByteDecoderTest.kt
 * CURRENT VERSION: v1.0.18
 * CHANGELOG:
 *   - v1.0.3: Initial unit tests for ByteDecoder endianness, scaling, and bounds checks.
 *   - v1.0.18: Added full file header and KDoc comments (no functional changes).
 * HOW IT WORKS:
 *   Exercises ByteDecoder.decode() across UINT8, UINT16_LE, UINT16_BE, linear
 *   scaling, and out-of-bounds null safety on a 3-byte sample packet.
 * ==============================================================================
 */
package com.bleforge.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Unit tests verifying [ByteDecoder] numeric decoding and bounds guards. */
class ByteDecoderTest {
    @Test
    fun `decodes UINT8, UINT16_LE, UINT16_BE with scale and bounds check`() {
        val sample = byteArrayOf(0x10, 0x02, 0x01) // [16, 0x0102 LE = 258]
        assertEquals(16f, ByteDecoder.decode(sample, TelemetryFormat.UINT8, 0)!!, 0.01f)
        assertEquals(258f, ByteDecoder.decode(sample, TelemetryFormat.UINT16_LE, 1)!!, 0.01f)
        assertEquals(513f, ByteDecoder.decode(sample, TelemetryFormat.UINT16_BE, 1)!!, 0.01f)
        assertEquals(25.8f, ByteDecoder.decode(sample, TelemetryFormat.UINT16_LE, 1, scale = 0.1f)!!, 0.01f)
        // Insufficient bytes for a 4-byte FLOAT32 must return null instead of throwing.
        assertNull(ByteDecoder.decode(sample, TelemetryFormat.FLOAT32_LE, 0))
    }
}
