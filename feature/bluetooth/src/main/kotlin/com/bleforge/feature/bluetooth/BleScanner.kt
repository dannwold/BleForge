/*
 * ==============================================================================
 * FILE: feature/bluetooth/src/main/kotlin/com/bleforge/feature/bluetooth/BleScanner.kt
 * CURRENT VERSION: v1.0.18
 * CHANGELOG:
 *   - v1.0.1: Initial BleScanner abstraction for AndroidBleScanner & test fakes.
 *   - v1.0.18: Added full file header and KDoc comments (no functional changes).
 * HOW IT WORKS:
 *   Exposes reactive StateFlows for scan lifecycle state and discovered devices,
 *   along with suspend start/stop operations for service-UUID-filtered BLE scans.
 * ==============================================================================
 */
package com.bleforge.feature.bluetooth

import com.bleforge.core.model.BleDevice
import com.bleforge.core.model.ScanState
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * Contract for BLE peripheral discovery implementations.
 */
interface BleScanner {
    /** Current scan lifecycle state (Idle, Scanning, Throttled, Error). */
    val scanState: StateFlow<ScanState>

    /** Deduplicated list of discovered peripherals sorted by signal/discovery order. */
    val discoveredDevices: StateFlow<List<BleDevice>>

    /** Starts a BLE scan, optionally filtered to [serviceUuids]. */
    suspend fun startScan(serviceUuids: List<UUID> = emptyList())

    /** Stops any active BLE scan and resets state to Idle. */
    suspend fun stopScan()
}
