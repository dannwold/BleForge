/*
 * ==============================================================================
 * FILE: CommandPresets.kt
 * CURRENT VERSION: v1.0.11
 * CHANGELOG:
 *   - v1.0.11: Created PayloadDataType, GattWriteMode, PayloadEncoder, and
 *              StandardCommandCatalog (AT commands + binary control presets).
 *
 * HOW THIS FILE WORKS:
 *   - Defines supported BLE payload encodings (Hex, UTF-8 text, 8/16/32-bit integers,
 *     and 32-bit IEEE-754 floats in Little Endian byte order).
 *   - Provides PayloadEncoder.encode() to convert user-typed strings or preset values
 *     into raw ByteArrays ready for BleConnectionManager.writeCharacteristic().
 *   - Provides StandardCommandCatalog with documented AT and binary diagnostic presets.
 * ==============================================================================
 */
package com.bleforge.core.model

import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class PayloadDataType(val label: String, val hint: String) {
    HEX("HEX", "Space-separated hex bytes (e.g. 01 FF)"),
    UTF8_CRLF("AT / UTF8+CRLF", "ASCII/UTF-8 text + \r\n (e.g. ATI)"),
    UTF8_RAW("UTF-8", "Plain UTF-8 text without newline"),
    UINT8("UINT8", "Unsigned 8-bit integer (0..255)"),
    UINT16_LE("UINT16_LE", "Unsigned 16-bit Little Endian (0..65535)"),
    UINT32_LE("UINT32_LE", "Unsigned 32-bit Little Endian"),
    INT16_LE("INT16_LE", "Signed 16-bit Little Endian (-32768..32767)"),
    FLOAT32_LE("FLOAT32_LE", "32-bit IEEE-754 Float Little Endian")
}

enum class GattWriteMode(val label: String, val withResponse: Boolean, val guidance: String) {
    REQUEST_WITH_RESPONSE(
        label = "Request (ACK)",
        withResponse = true,
        guidance = "Recommended for config/control writes: waits for ATT Write Response and surfaces GATT errors."
    ),
    COMMAND_NO_RESPONSE(
        label = "Command (No-ACK)",
        withResponse = false,
        guidance = "Recommended for high-rate UART/LED streaming: fire-and-forget without waiting for ACK."
    )
}

data class PresetCommand(
    val id: String,
    val category: String,
    val label: String,
    val dataType: PayloadDataType,
    val rawInput: String,
    val recommendedMode: GattWriteMode,
    val description: String
)

object PayloadEncoder {
    /**
     * Converts a user-entered string into raw bytes according to the selected PayloadDataType.
     */
    fun encode(input: String, type: PayloadDataType): Result<ByteArray> = runCatching {
        val trimmed = input.trim()
        when (type) {
            PayloadDataType.HEX -> {
                if (trimmed.isEmpty()) return@runCatching ByteArray(0)
                trimmed.split(Regex("\\s+")).map { token ->
                    require(token.length in 1..2) { "Invalid hex byte '$token'" }
                    token.toInt(16).toByte()
                }.toByteArray()
            }
            PayloadDataType.UTF8_CRLF -> (trimmed + "\r\n").toByteArray(Charsets.UTF_8)
            PayloadDataType.UTF8_RAW -> trimmed.toByteArray(Charsets.UTF_8)
            PayloadDataType.UINT8 -> {
                val v = trimmed.toInt()
                require(v in 0..255) { "UINT8 must be 0..255" }
                byteArrayOf(v.toByte())
            }
            PayloadDataType.UINT16_LE -> {
                val v = trimmed.toInt()
                require(v in 0..65535) { "UINT16 must be 0..65535" }
                ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()
            }
            PayloadDataType.UINT32_LE -> {
                val v = trimmed.toLong()
                require(v in 0L..0xFFFF_FFFFL) { "UINT32 must be 0..4294967295" }
                ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v.toInt()).array()
            }
            PayloadDataType.INT16_LE -> {
                val v = trimmed.toShort()
                ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v).array()
            }
            PayloadDataType.FLOAT32_LE -> {
                val v = trimmed.toFloat()
                ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(v).array()
            }
        }
    }
}

object StandardCommandCatalog {
    val presets: List<PresetCommand> = listOf(
        // Standard AT Diagnostic Commands (for BLE UART / Serial / Tracker Bridges)
        PresetCommand(
            id = "at_ping",
            category = "AT",
            label = "AT (Ping)",
            dataType = PayloadDataType.UTF8_CRLF,
            rawInput = "AT",
            recommendedMode = GattWriteMode.REQUEST_WITH_RESPONSE,
            description = "Tests serial/UART responsiveness; compliant devices reply 'OK'."
        ),
        PresetCommand(
            id = "at_info",
            category = "AT",
            label = "ATI (Info)",
            dataType = PayloadDataType.UTF8_CRLF,
            rawInput = "ATI",
            recommendedMode = GattWriteMode.REQUEST_WITH_RESPONSE,
            description = "Requests product identification and firmware build string."
        ),
        PresetCommand(
            id = "at_ver",
            category = "AT",
            label = "AT+VER?",
            dataType = PayloadDataType.UTF8_CRLF,
            rawInput = "AT+VER?",
            recommendedMode = GattWriteMode.REQUEST_WITH_RESPONSE,
            description = "Queries firmware/protocol version on common IoT BLE modules."
        ),
        PresetCommand(
            id = "at_help",
            category = "AT",
            label = "AT+HELP",
            dataType = PayloadDataType.UTF8_CRLF,
            rawInput = "AT+HELP",
            recommendedMode = GattWriteMode.REQUEST_WITH_RESPONSE,
            description = "Asks UART bridge to list supported AT commands (if enabled)."
        ),

        // Standard Binary Diagnostic & Control Commands
        PresetCommand(
            id = "bin_off",
            category = "BINARY",
            label = "0x00 (Off/Idle)",
            dataType = PayloadDataType.HEX,
            rawInput = "00",
            recommendedMode = GattWriteMode.REQUEST_WITH_RESPONSE,
            description = "Standard binary OFF / Standby / No-Alert value."
        ),
        PresetCommand(
            id = "bin_on",
            category = "BINARY",
            label = "0x01 (On/Active)",
            dataType = PayloadDataType.HEX,
            rawInput = "01",
            recommendedMode = GattWriteMode.REQUEST_WITH_RESPONSE,
            description = "Standard binary ON / Mild Alert / Enable value."
        ),
        PresetCommand(
            id = "bin_high",
            category = "BINARY",
            label = "0x02 (High/Mode2)",
            dataType = PayloadDataType.HEX,
            rawInput = "02",
            recommendedMode = GattWriteMode.REQUEST_WITH_RESPONSE,
            description = "Standard High Alert (Immediate Alert) or secondary mode."
        ),
        PresetCommand(
            id = "bin_max",
            category = "BINARY",
            label = "0xFF (Max Byte)",
            dataType = PayloadDataType.HEX,
            rawInput = "FF",
            recommendedMode = GattWriteMode.COMMAND_NO_RESPONSE,
            description = "Full-scale 8-bit value (e.g. 100% brightness/duty cycle)."
        ),
        PresetCommand(
            id = "bin_u16_one",
            category = "BINARY",
            label = "01 00 (U16=1)",
            dataType = PayloadDataType.HEX,
            rawInput = "01 00",
            recommendedMode = GattWriteMode.REQUEST_WITH_RESPONSE,
            description = "16-bit Little-Endian integer 1 for 2-byte control registers."
        )
    )
}
