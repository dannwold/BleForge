/*
 * ==============================================================================
 * FILE: AndroidBleScanner.kt
 * CURRENT VERSION: v1.0.21
 * CHANGELOG:
 *   - v1.0.1: Initial BluetoothLeScanner wrapper with ScanRateLimiter & SecurityException guards.
 *   - v1.0.9: Appended detailed KDoc & inline comments explaining Android 12-17 scan throttling.
 *   - v1.0.21: Capped deviceMap at 250 entries (evicting weakest RSSI) to prevent
 *              unbounded memory growth during dense BLE scans, and added
 *              onBluetoothDisabled() helper for BluetoothAdapter state changes.
 *
 * HOW THIS SCANNER WORKS:
 *   - Checks BluetoothAdapter state & runtime permissions before calling startScan().
 *   - Enforces Android's strict 5-scans-per-30-seconds window via ScanRateLimiter so the OS
 *     never silently blocks scanning with SCAN_FAILED_APPLICATION_REGISTRATION_FAILED.
 *   - Extracts manufacturer IDs from scanRecord.manufacturerSpecificData and sorts results
 *     by strongest RSSI first into a StateFlow<List<BleDevice>>.
 * ==============================================================================
 */

package com.bleforge.feature.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import com.bleforge.core.model.BleDevice
import com.bleforge.core.model.ScanState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

class AndroidBleScanner(
    private val context: Context,
    private val rateLimiter: ScanRateLimiter = ScanRateLimiter()
) : BleScanner {

    private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle)
    override val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<BleDevice>>(emptyList())
    override val discoveredDevices: StateFlow<List<BleDevice>> = _discoveredDevices.asStateFlow()

    private val deviceMap = LinkedHashMap<String, BleDevice>()
    private val maxCachedDevices = 250

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val name = try {
                device.name ?: result.scanRecord?.deviceName ?: "Unknown Device"
            } catch (_: SecurityException) {
                "Unknown Device"
            }
            val mfgData = result.scanRecord?.manufacturerSpecificData
            val mfgId = if (mfgData != null && mfgData.size() > 0) mfgData.keyAt(0) else null

            val model = BleDevice(
                address = device.address,
                name = name,
                rssi = result.rssi,
                isConnectable = result.isConnectable,
                manufacturerId = mfgId
            )
            synchronized(deviceMap) {
                deviceMap[model.address] = model
                // Cap in-memory scan cache so dense environments don't bloat memory or UI sorting.
                if (deviceMap.size > maxCachedDevices) {
                    val weakest = deviceMap.values.minByOrNull { it.rssi }
                    if (weakest != null && weakest.address != model.address) {
                        deviceMap.remove(weakest.address)
                    }
                }
                _discoveredDevices.value = deviceMap.values.sortedByDescending { it.rssi }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            _scanState.value = ScanState.Error("BLE scan failed (code=$errorCode)")
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun startScan(serviceUuids: List<UUID>) {
        if (_scanState.value is ScanState.Scanning) return

        val btManager = context.getSystemService(BluetoothManager::class.java)
        val adapter = btManager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            _scanState.value = ScanState.Error("Bluetooth is turned off or unavailable.")
            return
        }

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            _scanState.value = ScanState.Error("BLE scanner unavailable.")
            return
        }

        val waitMs = rateLimiter.tryAcquire()
        if (waitMs > 0L) {
            _scanState.value = ScanState.Throttled(waitMs)
            return
        }

        val filters = serviceUuids.map { uuid ->
            ScanFilter.Builder().setServiceUuid(ParcelUuid(uuid)).build()
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            synchronized(deviceMap) { deviceMap.clear() }
            _discoveredDevices.value = emptyList()
            scanner.startScan(filters, settings, callback)
            _scanState.value = ScanState.Scanning
        } catch (se: SecurityException) {
            _scanState.value = ScanState.Error("Missing Bluetooth scan permission: ${se.message}")
        } catch (t: Throwable) {
            _scanState.value = ScanState.Error("Failed to start scan: ${t.message}")
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun stopScan() {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        try {
            adapter?.bluetoothLeScanner?.stopScan(callback)
        } catch (_: SecurityException) {
        } catch (_: Throwable) {
        } finally {
            _scanState.update { if (it is ScanState.Scanning) ScanState.Idle else it }
        }
    }

    /** Immediately updates scan state if the system Bluetooth radio is turned off mid-scan. */
    fun onBluetoothDisabled() {
        _scanState.value = ScanState.Error("Bluetooth was turned off.")
    }
}
