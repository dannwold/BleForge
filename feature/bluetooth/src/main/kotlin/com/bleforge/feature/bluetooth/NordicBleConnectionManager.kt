/*
 * ==============================================================================
 * FILE: NordicBleConnectionManager.kt
 * CURRENT VERSION: v1.0.23
 * CHANGELOG:
 *   - v1.0.1: Initial BleManager wrapper with MTU 247 handshake, priority HIGH, & heartbeat.
 *   - v1.0.4: Fixed CancellationException swallowing in connect() and switched lookup maps
 *             to ConcurrentHashMap for thread-safe GATT discovery callbacks.
 *   - v1.0.9: Appended detailed KDoc & inline comments explaining handshake & GATT 133 recovery.
 *   - v1.0.12: Added bondState, bond() via ensureBond(), and unbond() via removeBond().
 *   - v1.0.21: Rethrew CancellationException in all suspend runCatching/try-catch blocks
 *             so structured coroutine cancellation is never swallowed.
 *   - v1.0.22: Made disconnect() consistently suspend and added DisconnectRequest support.
 *   - v1.0.23: Kept the inherited Nordic disconnect() request signature and added suspendDisconnect().
 * ==============================================================================
 */
package com.bleforge.feature.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import com.bleforge.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.ConnectionPriorityRequest
import no.nordicsemi.android.ble.ktx.asFlow
import no.nordicsemi.android.ble.ktx.suspend
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class NordicBleConnectionManager(
    context: Context,
    private val scope: CoroutineScope,
    private val backoff: ReconnectBackoff = ReconnectBackoff()
) : BleManager(context), BleConnectionManager {

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _bondState = MutableStateFlow(BondState.NONE)
    override val bondState: StateFlow<BondState> = _bondState.asStateFlow()

    private val _discoveredServices = MutableStateFlow<List<GattServiceInfo>>(emptyList())
    override val discoveredServices: StateFlow<List<GattServiceInfo>> = _discoveredServices.asStateFlow()

    private val charLookup = ConcurrentHashMap<Pair<UUID, UUID>, BluetoothGattCharacteristic>()
    private val activeSubscriptions = ConcurrentHashMap.newKeySet<Pair<UUID, UUID>>()
    private var heartbeatJob: Job? = null
    private var currentAddress: String? = null

    /** Runs [block] and returns [Result], always rethrowing [CancellationException]. */
    private suspend inline fun <T> runSuspendCatching(crossinline block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Result.failure(t)
        }

    @SuppressLint("MissingPermission")
    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
        _bondState.value = when (gatt.device.bondState) {
            BluetoothDevice.BOND_BONDED -> BondState.BONDED
            BluetoothDevice.BOND_BONDING -> BondState.BONDING
            else -> BondState.NONE
        }
        val mapped = mutableListOf<GattServiceInfo>()
        charLookup.clear()
        gatt.services.forEach { service ->
            val chars = service.characteristics.map { ch ->
                charLookup[service.uuid to ch.uuid] = ch
                val props = ch.properties
                GattCharacteristicInfo(
                    uuid = ch.uuid,
                    serviceUuid = service.uuid,
                    name = ch.uuid.toString().substring(0, 8),
                    isReadable = props and BluetoothGattCharacteristic.PROPERTY_READ != 0,
                    isWritable = props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0,
                    isWritableNoResponse = props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0,
                    isNotifiable = props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0,
                    isIndicatable = props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
                )
            }
            mapped += GattServiceInfo(service.uuid, service.uuid.toString().substring(0, 8), chars)
        }
        _discoveredServices.value = mapped
        return true
    }

    override fun initialize() {
        val addr = currentAddress ?: "unknown"
        _connectionState.value = ConnectionState.Discovering(addr)
        requestMtu(247)
            .with { _, mtu ->
                _connectionState.value = ConnectionState.Ready(addr, mtu)
                backoff.reset()
            }
            .fail { _, _ -> _connectionState.value = ConnectionState.Ready(addr, 23) }
            .enqueue()
        requestConnectionPriority(ConnectionPriorityRequest.CONNECTION_PRIORITY_HIGH).enqueue()
        activeSubscriptions.forEach { (sUuid, cUuid) ->
            charLookup[sUuid to cUuid]?.let { enableNotifications(it).enqueue() }
        }
    }

    override fun onServicesInvalidated() {
        stopHeartbeat()
        charLookup.clear()
        _discoveredServices.value = emptyList()
        _connectionState.value = ConnectionState.Disconnected
        _bondState.value = BondState.NONE
    }

    @SuppressLint("MissingPermission")
    override suspend fun connect(address: String, autoConnect: Boolean) {
        currentAddress = address
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        val device = adapter?.getRemoteDevice(address) ?: run {
            _connectionState.value = ConnectionState.Error(address, -1, "Bluetooth adapter unavailable")
            return
        }
        _connectionState.value = ConnectionState.Connecting(address)
        try {
            connect(device).useAutoConnect(autoConnect).timeout(15_000L).retry(3, 600).suspend()
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            try { suspendDisconnect() } catch (ce: CancellationException) { throw ce } catch (_: Throwable) {}
            close()
            val delayMs = backoff.nextDelayMs()
            _connectionState.value = ConnectionState.Error(address, 133, t.message ?: "Connection failed")
            if (delayMs != null && !autoConnect) {
                delay(delayMs)
                connect(address, autoConnect = false)
            }
        }
    }

    override suspend fun disconnect() {
        suspendDisconnect()
    }

    private suspend fun suspendDisconnect() {
        val addr = currentAddress ?: "unknown"
        _connectionState.value = ConnectionState.Disconnecting(addr)
        stopHeartbeat()
        try { super.disconnect().suspend() } catch (ce: CancellationException) { throw ce } catch (_: Throwable) {}
        close()
        _connectionState.value = ConnectionState.Disconnected
    }

    override suspend fun bond(): Result<Unit> = runSuspendCatching {
        _bondState.value = BondState.BONDING
        ensureBond().suspend()
        _bondState.value = BondState.BONDED
    }.onFailure { _bondState.value = BondState.NONE }

    override suspend fun unbond(): Result<Unit> = runSuspendCatching {
        removeBond().suspend()
        _bondState.value = BondState.NONE
    }

    override suspend fun refreshGattCache() {
        try { refreshDeviceCache().suspend() } catch (ce: CancellationException) { throw ce } catch (_: Throwable) {}
    }

    override suspend fun readCharacteristic(serviceUuid: UUID, charUuid: UUID): Result<ByteArray> = runSuspendCatching {
        val ch = charLookup[serviceUuid to charUuid] ?: error("Characteristic not found")
        withTimeout(10_000L) { readCharacteristic(ch).suspend().value ?: ByteArray(0) }
    }

    override suspend fun writeCharacteristic(serviceUuid: UUID, charUuid: UUID, data: ByteArray, withResponse: Boolean): Result<Unit> = runSuspendCatching {
        val ch = charLookup[serviceUuid to charUuid] ?: error("Characteristic not found")
        val type = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        withTimeout(10_000L) { writeCharacteristic(ch, data, type).split().suspend() }
        Unit
    }

    override fun observeNotifications(serviceUuid: UUID, charUuid: UUID): Flow<ByteArray> {
        val ch = charLookup[serviceUuid to charUuid] ?: return emptyFlow()
        activeSubscriptions += (serviceUuid to charUuid)
        val flow = setNotificationCallback(ch).asFlow().map { it.value ?: ByteArray(0) }
        enableNotifications(ch).enqueue()
        return flow
    }

    override fun startHeartbeat(config: HeartbeatConfig) {
        stopHeartbeat()
        if (!config.enabled) return
        heartbeatJob = scope.launch {
            val payload = config.payload
            while (isActive) {
                delay(config.intervalMs)
                if (payload == null) readCharacteristic(config.serviceUuid, config.charUuid)
                else writeCharacteristic(config.serviceUuid, config.charUuid, payload, withResponse = false)
            }
        }
    }

    override fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }
}
