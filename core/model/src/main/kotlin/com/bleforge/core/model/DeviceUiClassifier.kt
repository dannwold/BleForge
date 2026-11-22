/*
 * ==============================================================================
 * FILE: DeviceUiClassifier.kt
 * CURRENT VERSION: v1.0.12
 * CHANGELOG:
 *   - v1.0.12: Created DeviceUiClassifier to automatically select HUD modules
 *              (Smart Light, Telemetry Gauges, Battery, or Generic GATT) from
 *              discovered GATT service UUIDs and saved Room profile categories.
 *
 * HOW THIS CLASS WORKS:
 *   - Inspects the 16-bit short UUIDs of discovered services and optional Room profile
 *     category ("SMART_LIGHT", "VEHICLE_TELEMETRY", etc.).
 *   - Returns a Set<DeviceUiKind> so DashboardScreen only mounts relevant controls.
 * ==============================================================================
 */
package com.bleforge.core.model

enum class DeviceUiKind {
    SMART_LIGHT,
    TELEMETRY_HUD,
    BATTERY_CARD,
    GENERIC_GATT
}

object DeviceUiClassifier {
    fun classify(services: List<GattServiceInfo>, savedCategory: String? = null): Set<DeviceUiKind> {
        val kinds = mutableSetOf<DeviceUiKind>()
        val shortIds = services.map { it.uuid.toString().lowercase().substring(4, 8) }.toSet()

        if (savedCategory == "SMART_LIGHT" || "1815" in shortIds) {
            kinds += DeviceUiKind.SMART_LIGHT
        }
        if (savedCategory == "VEHICLE_TELEMETRY" || "1819" in shortIds || "1816" in shortIds || "181a" in shortIds) {
            kinds += DeviceUiKind.TELEMETRY_HUD
        }
        if ("180f" in shortIds) {
            kinds += DeviceUiKind.BATTERY_CARD
        }
        if (kinds.isEmpty()) {
            kinds += DeviceUiKind.GENERIC_GATT
        }
        return kinds
    }
}
