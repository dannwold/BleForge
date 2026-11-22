/*
 * ==============================================================================
 * FILE: BleGattServerManager.kt
 * CURRENT VERSION: v1.0.22
 * CHANGELOG:
 *   - v1.0.14: Created BleGattServerManager using BluetoothGattServer and
 *              BluetoothLeAdvertiser to host virtual BLE peripherals (Battery,
 *              Heart Rate, Nordic UART) and push test notifications.
 *   - v1.0.15: Added Build.VERSION_CODES.TIRAMISU guard for notifyCharacteristicChanged()
 *              so API 31-32 (Android 12) devices never hit NoSuchMethodError.
 *   - v1.0.22: Moved setIncludeDeviceName(true) to scanResponse so 128-bit UUIDs
 *              never exceed the 31-byte legacy advertising limit, sliced read
 *              responses by offset, and closed gattServer if advertiser is null.
 *
 * HOW THIS MANAGER WORKS:
 *   - Opens a local BluetoothGattServer and registers a preset service + CCCD
 *     descriptor (0x2902) so remote centrals can subscribe to notifications.
 *   - Starts BLE advertising via BluetoothLeAdvertiser with the selected service
 *     UUID so other phones/devices can discover and connect to this phone.
 *   - Tracks connected central devices and logs incoming read/write requests.
 * ==============================================================================
 */
package com.bleforge.feature.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class VirtualPeripheralPreset(
    val label: String,
    val serviceUuid: UUID,
    val charUuid: UUID,
    val defaultPayload: ByteArray
) {
    BATTERY(
        label = "Battery Sensor (180F)",
        serviceUuid = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb"),
        charUuid = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb"),
        defaultPayload = byteArrayOf(0x55) // 85%
    ),
    HEART_RATE(
        label = "Heart Rate (180D)",
        serviceUuid = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb"),
        charUuid = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb"),
        defaultPayload = byteArrayOf(0x00, 0x48) // 72 bpm
    ),
    NORDIC_UART(
        label = "Nordic UART (NUS)",
        serviceUuid = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e"),
        charUuid = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e"),
        defaultPayload = "OK\r\n".toByteArray(Charsets.UTF_8)
    )
}

sealed interface ServerState {
    data object Idle : ServerState
    data class Advertising(val presetName: String) : ServerState
    data class Error(val message: String) : ServerState
}

class BleGattServerManager(private val context: Context) {
    private val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val _serverState = MutableStateFlow<ServerState>(ServerState.Idle)
    val serverState: StateFlow<ServerState> = _serverState.asStateFlow()

    private val _serverLogs = MutableStateFlow<List<String>>(emptyList())
    val serverLogs: StateFlow<List<String>> = _serverLogs.asStateFlow()

    private val connectedDevices = ConcurrentHashMap.newKeySet<BluetoothDevice>()
    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var activeCharacteristic: BluetoothGattCharacteristic? = null

    private fun log(msg: String) {
        _serverLogs.value = (listOf(msg) + _serverLogs.value).take(40)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            _serverState.value = ServerState.Error("Advertise failed (code=$errorCode)")
            log("ADV ERR: code=$errorCode")
        }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectedDevices += device
                log("CENTRAL CONNECTED: ${device.address}")
            } else {
                connectedDevices -= device
                log("CENTRAL DISCONNECTED: ${device.address}")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            val full = activeCharacteristic?.value ?: ByteArray(0)
            if (offset < 0 || offset > full.size) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
                return
            }
            val slice = full.copyOfRange(offset, full.size)
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)
            log("READ REQ from ${device.address} (offset=$offset)")
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            val hex = value?.joinToString(" ") { "%02X".format(it) } ?: ""
            log("WRITE REQ from ${device.address}: $hex")
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            log("CCCD SUBSCRIBED by ${device.address}")
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startServer(preset: VirtualPeripheralPreset) {
        stopServer()
        val manager = context.getSystemService(BluetoothManager::class.java)
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            _serverState.value = ServerState.Error("Bluetooth is off")
            return
        }

        try {
            val server = manager.openGattServer(context, serverCallback) ?: run {
                _serverState.value = ServerState.Error("Unable to open GATT server")
                return
            }
            val service = BluetoothGattService(preset.serviceUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            val ch = BluetoothGattCharacteristic(
                preset.charUuid,
                BluetoothGattCharacteristic.PROPERTY_READ or
                    BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
            ).apply {
                value = preset.defaultPayload
                addDescriptor(
                    BluetoothGattDescriptor(
                        cccdUuid,
                        BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
                    )
                )
            }
            service.addCharacteristic(ch)
            server.addService(service)
            gattServer = server
            activeCharacteristic = ch

            val adv = adapter.bluetoothLeAdvertiser ?: run {
                server.close()
                gattServer = null
                activeCharacteristic = null
                _serverState.value = ServerState.Error("BLE Advertising unsupported on this device")
                return
            }
            advertiser = adv
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true)
                .build()
            // Keep primary ADV packet within 31 bytes (128-bit UUIDs + flags); put device name in scanResponse.
            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(ParcelUuid(preset.serviceUuid))
                .build()
            val scanResponse = AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .build()

            adv.startAdvertising(settings, data, scanResponse, advertiseCallback)
            _serverState.value = ServerState.Advertising(preset.label)
            log("SERVER STARTED: ${preset.label}")
        } catch (se: SecurityException) {
            _serverState.value = ServerState.Error("Missing BLUETOOTH_ADVERTISE/CONNECT permission")
        } catch (t: Throwable) {
            _serverState.value = ServerState.Error("Server error: ${t.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun pushNotification(payload: ByteArray) {
        val ch = activeCharacteristic ?: return
        val server = gattServer ?: return
        ch.value = payload
        val hex = payload.joinToString(" ") { "%02X".format(it) }
        connectedDevices.forEach { dev ->
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    server.notifyCharacteristicChanged(dev, ch, false, payload)
                } else {
                    @Suppress("DEPRECATION")
                    server.notifyCharacteristicChanged(dev, ch, false)
                }
            } catch (_: Throwable) {
            }
        }
        log("PUSHED NOTIFY (${connectedDevices.size} centrals): $hex")
    }

    @SuppressLint("MissingPermission")
    fun stopServer() {
        try { advertiser?.stopAdvertising(advertiseCallback) } catch (_: Throwable) {}
        try { gattServer?.close() } catch (_: Throwable) {}
        advertiser = null
        gattServer = null
        activeCharacteristic = null
        connectedDevices.clear()
        _serverState.value = ServerState.Idle
    }
}
