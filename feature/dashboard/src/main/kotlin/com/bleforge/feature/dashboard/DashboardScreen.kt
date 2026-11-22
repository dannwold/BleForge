/*
 * ==============================================================================
 * FILE: DashboardScreen.kt
 * CURRENT VERSION: v1.0.20
 * CHANGELOG:
 *   - v1.0.3: Initial Telemetry & Smart Device Dashboard.
 *   - v1.0.4: Upgraded to custom Canvas TacticalArcGauge, LiveSparkline, & HueControlStrip.
 *   - v1.0.8: Added Conditional Macro Deck (WHEN / IF / ELSE).
 *   - v1.0.12: Added DeviceUiClassifier auto-adaptive layout + Bond/Unbond controls.
 *   - v1.0.13: Added 1-tap REFRESH CACHE, EXPORT CSV session share, and live
 *              characteristic-to-gauge byte binding (ByteDecoder).
 *   - v1.0.19: Switched UI state to rememberSaveable (survives tab switches,
 *              resizing, and app switching) and replaced fixed button/gauge Rows
 *              with FlowRow / BoxWithConstraints so labels never clip.
 *   - v1.0.20: Switched to collectAsStateWithLifecycle(), added streamScope so
 *              BIND STREAM survives tab switches, and added macro empty state.
 * ==============================================================================
 */
package com.bleforge.feature.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bleforge.core.database.*
import com.bleforge.core.model.*
import com.bleforge.core.ui.*
import com.bleforge.feature.bluetooth.BleConnectionManager
import com.bleforge.feature.bluetooth.BleMacroEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Adaptive telemetry HUD, smart-device control deck, and conditional macro runner.
 */
@Composable
fun DashboardScreen(
    connectionManager: BleConnectionManager,
    macroEngine: BleMacroEngine,
    macroDao: BleMacroDao,
    profileDao: DeviceProfileDao,
    streamScope: CoroutineScope = rememberCoroutineScope(),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val connState by connectionManager.connectionState.collectAsStateWithLifecycle()
    val bondState by connectionManager.bondState.collectAsStateWithLifecycle()
    val services by connectionManager.discoveredServices.collectAsStateWithLifecycle()
    val macroState by macroEngine.state.collectAsStateWithLifecycle()
    val savedMacros by macroDao.observeMacros().collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()

    val uiKinds = remember(services) { DeviceUiClassifier.classify(services) }
    val currentAddr = (connState as? ConnectionState.Ready)?.address ?: ""

    // Saveable UI state survives tab switches, window resizing, and backgrounding.
    var speedKph by rememberSaveable { mutableFloatStateOf(0f) }
    var rpm by rememberSaveable { mutableFloatStateOf(0f) }
    var coolantC by rememberSaveable { mutableFloatStateOf(82f) }
    val telemetryStream = rememberSaveable(
        saver = listSaver(
            save = { it.toList() },
            restore = { mutableStateListOf<Float>().apply { addAll(it) } }
        )
    ) { mutableStateListOf(12f, 18f, 15f, 24f, 30f, 28f, 35f) }
    var lightOn by rememberSaveable { mutableStateOf(false) }
    var hue by rememberSaveable { mutableFloatStateOf(0.55f) }
    var exportStatus by rememberSaveable { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "ADAPTIVE HUD // ${uiKinds.joinToString(" • ") { it.name }}",
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        Surface(
            color = BleForgeColors.Surface,
            border = BorderStroke(1.dp, BleForgeColors.Border),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val statusLabel = when (val s = connState) {
                    ConnectionState.Disconnected -> "LINK OFFLINE"
                    is ConnectionState.Connecting -> "HANDSHAKE -> ${s.address}"
                    is ConnectionState.Connected -> "CONNECTED -> ${s.address}"
                    is ConnectionState.Discovering -> "DISCOVERING GATT -> ${s.address}"
                    is ConnectionState.Ready -> "ACTIVE LINK • ${s.address} • MTU ${s.mtu}"
                    is ConnectionState.Disconnecting -> "TEARDOWN..."
                    is ConnectionState.Error -> "FAULT (${s.status}): ${s.message}"
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = statusLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = BleForgeColors.Primary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "BOND: ${bondState.name}",
                            style = MaterialTheme.typography.labelMedium,
                            color = BleForgeColors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    OutlinedButton(onClick = {
                        scope.launch {
                            if (bondState == BondState.BONDED) connectionManager.unbond()
                            else connectionManager.bond()
                        }
                    }) {
                        Text(if (bondState == BondState.BONDED) "UNBOND" else "BOND", maxLines = 1, softWrap = false)
                    }
                }

                // FlowRow wraps action buttons cleanly on narrow/split-screen windows without clipping.
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedButton(onClick = {
                        scope.launch { connectionManager.refreshGattCache() }
                    }) { Text("REFRESH CACHE", maxLines = 1, softWrap = false) }

                    OutlinedButton(onClick = {
                        scope.launch {
                            val res = SessionCsvExporter.exportAndShare(context, profileDao, currentAddr)
                            exportStatus = res.fold(
                                onSuccess = { n -> "Exported $n telemetry rows" },
                                onFailure = { e -> "Export failed: ${e.message}" }
                            )
                        }
                    }) { Text("EXPORT CSV", maxLines = 1, softWrap = false) }

                    OutlinedButton(onClick = {
                        // Bind first notifiable characteristic to SPEED gauge + log to Room using process-wide streamScope.
                        val streamCh = services.flatMap { it.characteristics }.firstOrNull { it.isNotifiable }
                        if (streamCh != null) {
                            streamScope.launch {
                                connectionManager.observeNotifications(streamCh.serviceUuid, streamCh.uuid).collect { b ->
                                    val decoded = ByteDecoder.decode(b, TelemetryFormat.UINT8, 0) ?: 0f
                                    speedKph = decoded
                                    if (telemetryStream.size > 30) telemetryStream.removeAt(0)
                                    telemetryStream.add(decoded)
                                    profileDao.insertLog(
                                        TelemetryLogEntity(
                                            deviceAddress = currentAddr.ifBlank { "unknown" },
                                            timestampMs = System.currentTimeMillis(),
                                            label = "SPEED",
                                            valueText = decoded.toString(),
                                            rawHex = b.joinToString(" ") { "%02X".format(it) }
                                        )
                                    )
                                }
                            }
                        }
                    }) { Text("BIND STREAM", maxLines = 1, softWrap = false) }
                }

                exportStatus?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelMedium,
                        color = BleForgeColors.Secondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                LiveSparkline(samples = telemetryStream)
            }
        }

        if (DeviceUiKind.TELEMETRY_HUD in uiKinds || DeviceUiKind.GENERIC_GATT in uiKinds) {
            // Adapt between 3-up row on wide screens and wrapping FlowRow on narrow split-screen windows.
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                if (maxWidth >= 330.dp) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TacticalArcGauge("SPEED", speedKph, 160f, "km/h", BleForgeColors.Primary, Modifier.weight(1f))
                        TacticalArcGauge("ENGINE", rpm, 4000f, "RPM", BleForgeColors.Secondary, Modifier.weight(1f))
                        TacticalArcGauge("COOLANT", coolantC, 120f, "°C", BleForgeColors.Warning, Modifier.weight(1f))
                    }
                } else {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TacticalArcGauge("SPEED", speedKph, 160f, "km/h", BleForgeColors.Primary, Modifier.widthIn(min = 110.dp))
                        TacticalArcGauge("ENGINE", rpm, 4000f, "RPM", BleForgeColors.Secondary, Modifier.widthIn(min = 110.dp))
                        TacticalArcGauge("COOLANT", coolantC, 120f, "°C", BleForgeColors.Warning, Modifier.widthIn(min = 110.dp))
                    }
                }
            }
        }

        if (DeviceUiKind.SMART_LIGHT in uiKinds || DeviceUiKind.GENERIC_GATT in uiKinds) {
            Surface(
                color = BleForgeColors.Surface,
                border = BorderStroke(1.dp, BleForgeColors.Border),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("SMART DEVICE DECK", style = MaterialTheme.typography.titleMedium)
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (lightOn) "RELAY: ENERGIZED" else "RELAY: STANDBY",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(checked = lightOn, onCheckedChange = { checked ->
                            lightOn = checked
                            val target = services.flatMap { it.characteristics }.firstOrNull { it.isWritable || it.isWritableNoResponse }
                            if (target != null) {
                                scope.launch {
                                    connectionManager.writeCharacteristic(target.serviceUuid, target.uuid, byteArrayOf(if (checked) 0x01 else 0x00), target.isWritable)
                                }
                            }
                        })
                    }
                    Text("SPECTRUM HUE", style = MaterialTheme.typography.labelMedium, color = BleForgeColors.TextSecondary)
                    HueControlStrip(hueFraction = hue, onHueChange = { hue = it })
                }
            }
        }

        Surface(
            color = BleForgeColors.Surface,
            border = BorderStroke(1.dp, BleForgeColors.Border),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "CONDITIONAL MACRO DECK",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    OutlinedButton(onClick = {
                        val firstNotif = services.flatMap { it.characteristics }.firstOrNull { it.isNotifiable }
                        val firstWrite = services.flatMap { it.characteristics }.firstOrNull { it.isWritable || it.isWritableNoResponse }
                        if (firstNotif != null && firstWrite != null) {
                            val steps = listOf(
                                MacroStep.WhenNotify(firstNotif.serviceUuid, firstNotif.uuid, 5_000L),
                                MacroStep.IfBytes(
                                    condition = ByteCondition.StartsWith("01"),
                                    thenSteps = listOf(MacroStep.Write(firstWrite.serviceUuid, firstWrite.uuid, "02")),
                                    elseSteps = listOf(MacroStep.Write(firstWrite.serviceUuid, firstWrite.uuid, "00"))
                                )
                            )
                            scope.launch {
                                macroDao.upsertMacro(
                                    BleMacroEntity(
                                        macroId = UUID.randomUUID().toString(),
                                        name = "When 01 -> 02 Else 00",
                                        targetServiceUuid = firstNotif.serviceUuid.toString(),
                                        stepsJson = MacroJsonCodec.encode(steps)
                                    )
                                )
                            }
                        }
                    }) { Text("+ SAMPLE IF/ELSE", maxLines = 1, softWrap = false) }
                }

                val macroStatusText = when (val m = macroState) {
                    MacroState.Idle -> "ENGINE IDLE"
                    is MacroState.Running -> "RUN [${m.macroName}]: ${m.detail}"
                    is MacroState.Completed -> "DONE [${m.macroName}]: ${m.summary}"
                    is MacroState.Failed -> "FAIL [${m.macroName}]: ${m.reason}"
                }
                Text(
                    text = macroStatusText,
                    style = MaterialTheme.typography.labelMedium,
                    color = BleForgeColors.Primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                if (savedMacros.isEmpty()) {
                    Text(
                        text = "No saved macros yet — connect to a GATT device and tap + SAMPLE IF/ELSE.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = BleForgeColors.TextSecondary
                    )
                }

                savedMacros.forEach { macro ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = macro.name,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Button(onClick = {
                                macroEngine.runMacro(macro.name, MacroJsonCodec.decode(macro.stepsJson))
                            }) { Text("RUN", maxLines = 1, softWrap = false) }
                            if (macroState is MacroState.Running) {
                                OutlinedButton(onClick = { macroEngine.stop() }) {
                                    Text("STOP", maxLines = 1, softWrap = false)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
