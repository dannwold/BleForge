/*
 * ==============================================================================
 * FILE: ServerScreen.kt
 * CURRENT VERSION: v1.0.22
 * CHANGELOG:
 *   - v1.0.14: Created ServerScreen for hosting virtual BLE peripherals,
 *              pushing test notifications, and viewing central read/write logs.
 *   - v1.0.19: Switched selectedPreset & testByte to rememberSaveable and used
 *              FlowRow for preset chips and action buttons so narrow/resized
 *              screens never clip controls.
 *   - v1.0.20: Switched to collectAsStateWithLifecycle(), added stable keys to
 *              log LazyColumn items, added empty-state message, and started
 *              BleForegroundService when advertising so backgrounding/battery
 *              savers don't kill the virtual GATT server.
 *   - v1.0.21: Fixed prepended-log LazyColumn key to include reverse index + hash
 *              so prepending new log lines never reuses the top row's key.
 *   - v1.0.22: Re-checked BLUETOOTH_ADVERTISE/CONNECT on ON_RESUME so permission
 *              changes in Settings take effect immediately.
 * HOW IT WORKS:
 *   Lets the user pick a VirtualPeripheralPreset, grant BLUETOOTH_ADVERTISE &
 *   BLUETOOTH_CONNECT permissions, start/stop BleGattServerManager, push test
 *   notify bytes, and inspect live central read/write logs.
 * ==============================================================================
 */
package com.bleforge.feature.dashboard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bleforge.core.ui.BleForgeColors
import com.bleforge.feature.bluetooth.BleForegroundService
import com.bleforge.feature.bluetooth.BleGattServerManager
import com.bleforge.feature.bluetooth.ServerState
import com.bleforge.feature.bluetooth.VirtualPeripheralPreset

/**
 * Virtual BLE peripheral server console for advertising preset GATT profiles
 * and pushing test notifications to connected centrals.
 */
@Composable
fun ServerScreen(serverManager: BleGattServerManager, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state by serverManager.serverState.collectAsStateWithLifecycle()
    val logs by serverManager.serverLogs.collectAsStateWithLifecycle()

    // Persist preset selection and test counter across tab switches and resizing.
    var selectedPresetName by rememberSaveable { mutableStateOf(VirtualPeripheralPreset.BATTERY.name) }
    val selectedPreset = VirtualPeripheralPreset.entries.firstOrNull { it.name == selectedPresetName }
        ?: VirtualPeripheralPreset.BATTERY
    var testByte by rememberSaveable { mutableIntStateOf(0x42) }

    fun checkAdvPerms(): Boolean =
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    var hasAdvPerm by remember { mutableStateOf(checkAdvPerms()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasAdvPerm = checkAdvPerms()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        hasAdvPerm = res.values.all { it }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 12.dp)
    ) {
        item(key = "server_title") {
            Text(
                text = "VIRTUAL PERIPHERAL // SERVER",
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        item(key = "server_controls") {
            Surface(
                color = BleForgeColors.Surface,
                border = BorderStroke(1.dp, BleForgeColors.Border),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val statusText = when (val s = state) {
                        ServerState.Idle -> "SERVER IDLE"
                        is ServerState.Advertising -> "ADVERTISING: ${s.presetName}"
                        is ServerState.Error -> "ERROR: ${s.message}"
                    }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelMedium,
                        color = BleForgeColors.Primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        VirtualPeripheralPreset.entries.forEach { preset ->
                            FilterChip(
                                selected = selectedPreset == preset,
                                onClick = { selectedPresetName = preset.name },
                                label = {
                                    Text(
                                        text = preset.name,
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            )
                        }
                    }

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Button(onClick = {
                            if (!hasAdvPerm) {
                                permLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT))
                            } else if (state is ServerState.Advertising) {
                                serverManager.stopServer()
                            } else {
                                // Promote to foreground service so OEM battery savers don't kill peripheral mode.
                                context.startForegroundService(Intent(context, BleForegroundService::class.java))
                                serverManager.startServer(selectedPreset)
                            }
                        }) {
                            Text(
                                text = if (state is ServerState.Advertising) "STOP SERVER" else "START SERVER",
                                maxLines = 1,
                                softWrap = false
                            )
                        }

                        if (state is ServerState.Advertising) {
                            OutlinedButton(onClick = {
                                testByte = (testByte + 1) and 0xFF
                                serverManager.pushNotification(byteArrayOf(testByte.toByte()))
                            }) {
                                Text(
                                    text = "PUSH TEST (0x${testByte.toString(16).uppercase()})",
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                }
            }
        }

        item(key = "server_log_header") {
            Text("SERVER EVENT LOG", style = MaterialTheme.typography.labelMedium, color = BleForgeColors.TextSecondary)
        }

        if (logs.isEmpty()) {
            item(key = "server_log_empty") {
                Text(
                    text = "No server events yet — tap START SERVER to begin advertising.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = BleForgeColors.TextSecondary
                )
            }
        }

        itemsIndexed(logs, key = { idx, line -> "log_${logs.size - idx}_${line.hashCode()}" }) { _, line ->
            Text(line, style = MaterialTheme.typography.labelMedium, color = BleForgeColors.TextPrimary)
        }
    }
}
