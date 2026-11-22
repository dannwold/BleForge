/*
 * ==============================================================================
 * FILE: BleModels.kt
 * CURRENT VERSION: v1.0.12
 * CHANGELOG:
 *   - v1.0.1: Initial BleDevice, ScanState, ConnectionState, AppError models.
 *   - v1.0.12: Added BondState (NONE, BONDING, BONDED) for LE Secure Connections UI.
 * ==============================================================================
 */
package com.bleforge.core.model

data class BleDevice(
    val address: String,
    val name: String,
    val rssi: Int,
    val isConnectable: Boolean,
    val manufacturerId: Int? = null,
    val lastSeenMs: Long = System.currentTimeMillis()
)

enum class BondState { NONE, BONDING, BONDED }

sealed interface ScanState {
    data object Idle : ScanState
    data object Scanning : ScanState
    data class Throttled(val retryAfterMs: Long) : ScanState
    data class Error(val message: String) : ScanState
}

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data class Connecting(val address: String) : ConnectionState
    data class Connected(val address: String) : ConnectionState
    data class Discovering(val address: String) : ConnectionState
    data class Ready(val address: String, val mtu: Int) : ConnectionState
    data class Disconnecting(val address: String) : ConnectionState
    data class Error(val address: String, val status: Int, val message: String) : ConnectionState
}

data class AppError(
    val title: String,
    val message: String,
    val throwable: Throwable? = null
)
