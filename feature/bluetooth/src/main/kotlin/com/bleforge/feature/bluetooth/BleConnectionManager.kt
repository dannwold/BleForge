package com.bleforge.feature.bluetooth

import com.bleforge.core.model.*
import kotlinx.coroutines.flow.*
import java.util.UUID

interface BleConnectionManager {
    val connectionState: StateFlow<ConnectionState>
    val bondState: StateFlow<BondState>
    val discoveredServices: StateFlow<List<GattServiceInfo>>
    suspend fun connect(address: String, autoConnect: Boolean = false)
    suspend fun disconnectAndClose()
    suspend fun bond(): Result<Unit>
    suspend fun unbond(): Result<Unit>
    suspend fun refreshGattCache()
    suspend fun readCharacteristic(serviceUuid: UUID, charUuid: UUID): Result<ByteArray>
    suspend fun writeCharacteristic(serviceUuid: UUID, charUuid: UUID, data: ByteArray, withResponse: Boolean = true): Result<Unit>
    fun observeNotifications(serviceUuid: UUID, charUuid: UUID): Flow<ByteArray>
    fun startHeartbeat(config: HeartbeatConfig)
    fun stopHeartbeat()
}
