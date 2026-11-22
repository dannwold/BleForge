/*
 * ==============================================================================
 * FILE: AdvertisingParser.kt
 * CURRENT VERSION: v1.0.13
 * CHANGELOG:
 *   - v1.0.13: Added Bluetooth SIG Company ID lookup & iBeacon frame decoder so
 *              Scanner cards identify vendors & beacons automatically.
 * ==============================================================================
 */
package com.bleforge.core.model

object AdvertisingParser {
    private val companyNames = mapOf(
        0x004C to "Apple",
        0x0006 to "Microsoft",
        0x00E0 to "Google",
        0x0059 to "Nordic Semi",
        0x0075 to "Samsung",
        0x02E5 to "Espressif",
        0x000D to "Texas Instruments",
        0x0087 to "Garmin",
        0x0157 to "Anhui Huami"
    )

    fun vendorName(manufacturerId: Int?): String? =
        manufacturerId?.let { companyNames[it] ?: "MFG 0x${it.toString(16).uppercase()}" }
}
