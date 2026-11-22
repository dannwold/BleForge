/*
 * ==============================================================================
 * FILE: core/model/src/test/kotlin/com/bleforge/core/model/PayloadEncoderTest.kt
 * CURRENT VERSION: v1.0.18
 * CHANGELOG:
 *   - v1.0.5: Initial unit tests for PayloadEncoder UTF-8 CRLF, HEX, UINT16_LE, and validation.
 *   - v1.0.18: Added full file header and KDoc comments (no functional changes).
 * HOW IT WORKS:
 *   Verifies PayloadEncoder.encode() produces exact byte arrays for AT+CRLF,
 *   spaced hex, and little-endian 16-bit integers while failing cleanly on overflow.
 * ==============================================================================
 */
package com.bleforge.core.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests verifying [PayloadEncoder] command serialization and range validation. */
class PayloadEncoderTest {
    @Test
    fun `encodes AT CRLF, HEX, and UINT16_LE accurately`() {
        val atBytes = PayloadEncoder.encode("AT", PayloadDataType.UTF8_CRLF).getOrThrow()
        assertArrayEquals(byteArrayOf(0x41, 0x54, 0x0D, 0x0A), atBytes)

        val hexBytes = PayloadEncoder.encode("01 FF", PayloadDataType.HEX).getOrThrow()
        assertArrayEquals(byteArrayOf(0x01, 0xFF.toByte()), hexBytes)

        val u16Bytes = PayloadEncoder.encode("258", PayloadDataType.UINT16_LE).getOrThrow()
        assertArrayEquals(byteArrayOf(0x02, 0x01), u16Bytes)

        // Values > 255 must fail Result validation for UINT8.
        assertTrue(PayloadEncoder.encode("300", PayloadDataType.UINT8).isFailure)
    }
}
