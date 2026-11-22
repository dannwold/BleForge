/*
 * ==============================================================================
 * FILE: ByteDecoder.kt
 * CURRENT VERSION: v1.0.13
 * CHANGELOG:
 *   - v1.0.13: Created ByteDecoder & GaugeTarget for binding raw BLE notification
 *              bytes directly to live HUD TacticalArcGauge dials & Sparklines.
 *
 * HOW THIS FILE WORKS:
 *   - Supports UINT8, UINT16_LE, UINT16_BE, SINT16_LE, and FLOAT32_LE decoding
 *     at any byte offset, with configurable multiplier scale & offset.
 *   - Used by DashboardScreen to turn raw BLE telemetry streams into live gauges.
 * ==============================================================================
 */
package com.bleforge.core.model

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

enum class GaugeTarget(val label: String) {
    SPEED("SPEED"),
    RPM("ENGINE RPM"),
    COOLANT("COOLANT")
}

enum class TelemetryFormat(val label: String) {
    UINT8("UINT8"),
    UINT16_LE("UINT16_LE"),
    UINT16_BE("UINT16_BE"),
    SINT16_LE("SINT16_LE"),
    FLOAT32_LE("FLOAT32_LE")
}

data class GaugeBinding(
    val target: GaugeTarget,
    val serviceUuid: UUID,
    val charUuid: UUID,
    val format: TelemetryFormat = TelemetryFormat.UINT16_LE,
    val byteOffset: Int = 0,
    val scale: Float = 1f
)

object ByteDecoder {
    fun decode(bytes: ByteArray, format: TelemetryFormat, offset: Int = 0, scale: Float = 1f): Float? {
        if (offset < 0 || offset >= bytes.size) return null
        val raw: Float = when (format) {
            TelemetryFormat.UINT8 -> (bytes[offset].toInt() and 0xFF).toFloat()
            TelemetryFormat.UINT16_LE -> {
                if (offset + 2 > bytes.size) return null
                ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt().and(0xFFFF).toFloat()
            }
            TelemetryFormat.UINT16_BE -> {
                if (offset + 2 > bytes.size) return null
                ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.BIG_ENDIAN).short.toInt().and(0xFFFF).toFloat()
            }
            TelemetryFormat.SINT16_LE -> {
                if (offset + 2 > bytes.size) return null
                ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toFloat()
            }
            TelemetryFormat.FLOAT32_LE -> {
                if (offset + 4 > bytes.size) return null
                ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).float
            }
        }
        return raw * scale
    }
}
