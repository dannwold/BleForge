/*
 * ==============================================================================
 * FILE: BleConnectionManager.kt
 * CURRENT VERSION: v1.0.12
 * CHANGELOG:
 *   - v1.0.2: Initial BleConnectionManager interface.
 *   - v1.0.12: Added bondState, bond(), and unbond() for manual BLE bonding control.
 * ==============================================================================
 */
package com.bleforge.feature.bluetooth

import com.bleforge.core.model.*
import kotlinx.coroutines.flow.*
import java.util.UUID

interface BleConnectionManager {
    val connectionState: StateFlow<ConnectionState>
    val bondState: StateFlow<BondState>
    val discoveredServices: StateFlow<List<GattServiceInfo>>
    suspend fun connect(address: String, autoConnect: Boolean = false)
    suspend fun disconnect()
    suspend fun bond(): Result<Unit>
    suspend fun unbond(): Result<Unit>
    suspend fun refreshGattCache()
    suspend fun readCharacteristic(serviceUuid: UUID, charUuid: UUID): Result<ByteArray>
    suspend fun writeCharacteristic(serviceUuid: UUID, charUuid: UUID, data: ByteArray, withResponse: Boolean = true): Result<Unit>
    fun observeNotifications(serviceUuid: UUID, charUuid: UUID): Flow<ByteArray>
    fun startHeartbeat(config: HeartbeatConfig)
    fun stopHeartbeat()
}
