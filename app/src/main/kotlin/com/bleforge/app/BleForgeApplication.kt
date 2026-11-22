/*
 * ==============================================================================
 * FILE: app/src/main/kotlin/com/bleforge/app/BleForgeApplication.kt
 * CURRENT VERSION: v1.0.21
 * CHANGELOG:
 *   - v1.0.1: Initial Application class installing GlobalCrashHandler on startup.
 *   - v1.0.18: Added full file header, KDoc, and inline comments (no functional changes).
 *   - v1.0.19: Hoisted appScope, Room DB, scanner, connectionManager, macroEngine,
 *              and serverManager to Application scope so BLE state & connections
 *              survive Activity recreation, window resizing, and app switching.
 *   - v1.0.21: Registered BluetoothAdapter.ACTION_STATE_CHANGED receiver with
 *              ContextCompat.RECEIVER_EXPORTED so toggling Bluetooth off mid-session
 *              cleanly halts scans/servers and alerts GlobalErrorBus.
 * HOW IT WORKS:
 *   1. Installs GlobalCrashHandler as early as possible during process init.
 *   2. Lazily initializes process-scoped BLE managers and Room database so
 *      rotating, resizing, or backgrounding MainActivity never tears down links.
 *   3. Listens for Bluetooth radio state changes and updates scanner/server/UI.
 * ==============================================================================
 */
package com.bleforge.app

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.bleforge.core.database.BleForgeDatabase
import com.bleforge.feature.bluetooth.AndroidBleScanner
import com.bleforge.feature.bluetooth.BleGattServerManager
import com.bleforge.feature.bluetooth.BleMacroEngine
import com.bleforge.feature.bluetooth.NordicBleConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Custom [Application] entry point that installs BleForge's crash-recovery handler
 * and holds process-scoped BLE managers so active scans, GATT links, macros, and
 * virtual peripheral servers survive Activity recreation and app switching.
 */
class BleForgeApplication : Application() {
    /** Process-wide coroutine scope for BLE operations and telemetry streams. */
    val appScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.IO + GlobalErrorBus.coroutineHandler)
    }

    val database: BleForgeDatabase by lazy { BleForgeDatabase.get(applicationContext) }
    val scanner: AndroidBleScanner by lazy { AndroidBleScanner(applicationContext) }
    val connectionManager: NordicBleConnectionManager by lazy {
        NordicBleConnectionManager(applicationContext, appScope)
    }
    val macroEngine: BleMacroEngine by lazy { BleMacroEngine(connectionManager, appScope) }
    val serverManager: BleGattServerManager by lazy { BleGattServerManager(applicationContext) }

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
                scanner.onBluetoothDisabled()
                serverManager.stopServer()
                macroEngine.stop()
                GlobalErrorBus.report("Bluetooth Disabled", "Turn Bluetooth back on to scan, connect, or advertise.")
            } else if (state == BluetoothAdapter.STATE_ON) {
                GlobalErrorBus.clear()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Preserve the system default handler so fatal crashes still delegate cleanly after logging.
        val existingHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(GlobalCrashHandler(this, existingHandler))
        // RECEIVER_EXPORTED is required on Android 14+ for system-wide broadcasts like ACTION_STATE_CHANGED.
        ContextCompat.registerReceiver(
            this,
            bluetoothStateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED
        )
    }
}
