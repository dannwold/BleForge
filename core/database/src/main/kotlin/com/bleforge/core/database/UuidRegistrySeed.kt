/*
 * ==============================================================================
 * FILE: core/database/src/main/kotlin/com/bleforge/core/database/UuidRegistrySeed.kt
 * CURRENT VERSION: v1.0.18
 * CHANGELOG:
 *   - v1.0.5: Initial offline Bluetooth SIG + Nordic UART Service UUID seed table.
 *   - v1.0.18: Added full file header and KDoc comments (no functional changes).
 * HOW IT WORKS:
 *   Provides defaultEntries() to pre-populate Room's `known_uuids` table on first
 *   launch so GATT inspection works offline before UuidRegistrySync fetches the
 *   full Bluetooth Numbers Database from GitHub.
 * ==============================================================================
 */
package com.bleforge.core.database

/**
 * Offline seed catalog of common Bluetooth SIG adopted services, characteristics,
 * and well-known vendor UUIDs (such as Nordic UART Service).
 */
object UuidRegistrySeed {
    /** Expands a 16-bit Bluetooth SIG short UUID (e.g. `180F`) into its 128-bit base UUID. */
    private fun sigUuid(hex16: String): String =
        "0000${hex16.lowercase()}-0000-1000-8000-00805f9b34fb"

    /** Returns the initial list of [KnownUuidEntity] records inserted on database creation. */
    fun defaultEntries(): List<KnownUuidEntity> = listOf(
        // Bluetooth SIG Adopted Services
        KnownUuidEntity(sigUuid("1800"), "1800", "SERVICE", "Generic Access", "SIG_ADOPTED", "NONE", "Core GAP device name & appearance", false),
        KnownUuidEntity(sigUuid("1801"), "1801", "SERVICE", "Generic Attribute", "SIG_ADOPTED", "NONE", "Service Changed indication support", false),
        KnownUuidEntity(sigUuid("180A"), "180A", "SERVICE", "Device Information", "SIG_ADOPTED", "NONE", "Manufacturer, model, serial, firmware metadata", false),
        KnownUuidEntity(sigUuid("180F"), "180F", "SERVICE", "Battery Service", "SIG_ADOPTED", "NONE", "Exposes battery percentage", false),
        KnownUuidEntity(sigUuid("180D"), "180D", "SERVICE", "Heart Rate", "SIG_ADOPTED", "NONE", "Standard heart rate telemetry", false),
        KnownUuidEntity(sigUuid("1815"), "1815", "SERVICE", "Automation IO", "SIG_ADOPTED", "NONE", "Digital/analog I/O for smart switches & lights", false),
        KnownUuidEntity(sigUuid("1816"), "1816", "SERVICE", "Cycling Speed & Cadence", "SIG_ADOPTED", "NONE", "Wheel/crank revolution telemetry", false),
        KnownUuidEntity(sigUuid("1819"), "1819", "SERVICE", "Location and Navigation", "SIG_ADOPTED", "NONE", "GPS coordinates, speed, heading, elevation", false),
        KnownUuidEntity(sigUuid("181A"), "181A", "SERVICE", "Environmental Sensing", "SIG_ADOPTED", "NONE", "Temperature, humidity, pressure sensors", false),

        // Bluetooth SIG Adopted Characteristics
        KnownUuidEntity(sigUuid("2A00"), "2A00", "CHARACTERISTIC", "Device Name", "SIG_ADOPTED", "UTF8", "Advertised/GAP UTF-8 device name", false),
        KnownUuidEntity(sigUuid("2A19"), "2A19", "CHARACTERISTIC", "Battery Level", "SIG_ADOPTED", "UINT8_PERCENT", "Single byte 0-100%", false),
        KnownUuidEntity(sigUuid("2A24"), "2A24", "CHARACTERISTIC", "Model Number String", "SIG_ADOPTED", "UTF8", "Hardware model string", false),
        KnownUuidEntity(sigUuid("2A25"), "2A25", "CHARACTERISTIC", "Serial Number String", "SIG_ADOPTED", "UTF8", "Device serial number", false),
        KnownUuidEntity(sigUuid("2A26"), "2A26", "CHARACTERISTIC", "Firmware Revision", "SIG_ADOPTED", "UTF8", "Installed firmware version", false),
        KnownUuidEntity(sigUuid("2A29"), "2A29", "CHARACTERISTIC", "Manufacturer Name", "SIG_ADOPTED", "UTF8", "Vendor/OEM name", false),
        KnownUuidEntity(sigUuid("2A37"), "2A37", "CHARACTERISTIC", "Heart Rate Measurement", "SIG_ADOPTED", "UINT8_OR_16", "Flags byte + BPM value", false),
        KnownUuidEntity(sigUuid("2A67"), "2A67", "CHARACTERISTIC", "Location and Speed", "SIG_ADOPTED", "STRUCT_LE", "Bitmask flags + speed, lat/lon, elevation", false),
        KnownUuidEntity(sigUuid("2A68"), "2A68", "CHARACTERISTIC", "Navigation", "SIG_ADOPTED", "STRUCT_LE", "Bearing, heading, remaining distance", false),
        KnownUuidEntity(sigUuid("2A6E"), "2A6E", "CHARACTERISTIC", "Temperature", "SIG_ADOPTED", "SINT16_0_01C", "Signed 16-bit int * 0.01 °C", false),
        KnownUuidEntity(sigUuid("2A6F"), "2A6F", "CHARACTERISTIC", "Humidity", "SIG_ADOPTED", "UINT16_0_01PCT", "Unsigned 16-bit int * 0.01 %", false),

        // Well-Known Vendor UUIDs (Nordic UART Service)
        KnownUuidEntity("6e400001-b5a3-f393-e0a9-e50e24dcca9e", null, "SERVICE", "Nordic UART Service (NUS)", "VENDOR_KNOWN", "NONE", "Serial-over-BLE bridge used by trackers & IoT", false),
        KnownUuidEntity("6e400002-b5a3-f393-e0a9-e50e24dcca9e", null, "CHARACTERISTIC", "NUS RX (Write to Device)", "VENDOR_KNOWN", "UTF8_OR_HEX", "Central writes serial commands here", false),
        KnownUuidEntity("6e400003-b5a3-f393-e0a9-e50e24dcca9e", null, "CHARACTERISTIC", "NUS TX (Notify from Device)", "VENDOR_KNOWN", "UTF8_OR_HEX", "Peripheral streams serial telemetry here", false)
    )
}
