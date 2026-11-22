/*
 * ==============================================================================
 * FILE: GattInspectorScreen.kt
 * CURRENT VERSION: v1.0.20
 * CHANGELOG:
 *   - v1.0.3: Initial GATT service/characteristic list with R / W / N buttons.
 *   - v1.0.4: Added ByteDiffGrid for live packet byte-diff highlighting.
 *   - v1.0.6: Added KnownUuidDao resolution and +DB modal for custom UUIDs.
 *   - v1.0.7: Added SYNC SIG DB button for live Nordic Bluetooth Numbers import.
 *   - v1.0.11: Added full Command Composer (Data Type selector, Request vs Command
 *              write mode toggle, live encoded hex preview, and StandardCommandCatalog
 *              AT & binary presets with descriptions and safe usage recommendations).
 *   - v1.0.19: Unified screen into a single scrollable LazyColumn with imePadding(),
 *              switched characteristic actions to FlowRow so names/buttons never clip,
 *              added rememberSaveable for all composer & packet states, and made
 *              the +DB AlertDialog scrollable on compact/resized screens.
 *   - v1.0.20: Switched to collectAsStateWithLifecycle(), added streamScope so
 *              notify subscriptions survive tab switches, and added empty state.
 *
 * HOW THIS SCREEN WORKS:
 *   - Resolves every discovered Service/Characteristic UUID against Room's known_uuids.
 *   - Lets the user pick a PayloadDataType (HEX, AT/UTF8+CRLF, UINT8/16/32, FLOAT32),
 *     choose GattWriteMode (Request w/ ACK vs Command w/o ACK), or tap a documented
 *     AT/Binary preset that auto-fills the payload and explains what it does.
 *   - Displays real-time encoded bytes before writing so there are no surprises.
 * ==============================================================================
 */
package com.bleforge.feature.inspector

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bleforge.core.database.KnownUuidDao
import com.bleforge.core.database.KnownUuidEntity
import com.bleforge.core.database.UuidRegistrySync
import com.bleforge.core.model.*
import com.bleforge.core.ui.*
import com.bleforge.feature.bluetooth.BleConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Interactive GATT service/characteristic inspector, Command Composer, and UUID DB editor.
 */
@Composable
fun GattInspectorScreen(
    connectionManager: BleConnectionManager,
    knownUuidDao: KnownUuidDao,
    streamScope: CoroutineScope = rememberCoroutineScope(),
    modifier: Modifier = Modifier
) {
    val services by connectionManager.discoveredServices.collectAsStateWithLifecycle()
    val knownList by knownUuidDao.observeAllUuids().collectAsStateWithLifecycle(initialValue = emptyList())
    val knownMap = remember(knownList) { knownList.associateBy { it.uuid.lowercase() } }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Persist composer inputs, status, and captured bytes across tab switches, resizing, and backgrounding.
    var statusLine by rememberSaveable { mutableStateOf("Select a preset or type a payload, then tap W on a characteristic") }
    var selectedDataTypeName by rememberSaveable { mutableStateOf(PayloadDataType.HEX.name) }
    val selectedDataType = PayloadDataType.entries.firstOrNull { it.name == selectedDataTypeName } ?: PayloadDataType.HEX
    var selectedWriteModeName by rememberSaveable { mutableStateOf(GattWriteMode.REQUEST_WITH_RESPONSE.name) }
    val selectedWriteMode = GattWriteMode.entries.firstOrNull { it.name == selectedWriteModeName } ?: GattWriteMode.REQUEST_WITH_RESPONSE
    var rawInput by rememberSaveable { mutableStateOf("01") }
    var activePresetNote by rememberSaveable { mutableStateOf("Tip: Start with Read (R) or safe AT/0x00/0x01 queries using Request (ACK) to see GATT errors.") }

    var currentBytes by rememberSaveable { mutableStateOf(ByteArray(0)) }
    var previousBytes by rememberSaveable { mutableStateOf<ByteArray?>(null) }
    var activeNotifyJob by remember { mutableStateOf<Job?>(null) }
    var editingCharUuid by rememberSaveable { mutableStateOf<String?>(null) }
    val editingChar = remember(editingCharUuid, services) {
        editingCharUuid?.let { id ->
            services.flatMap { it.characteristics }.firstOrNull { it.uuid.toString() == id }
        }
    }
    var syncing by remember { mutableStateOf(false) }

    val encodedPreview = remember(rawInput, selectedDataType) {
        PayloadEncoder.encode(rawInput, selectedDataType)
    }

    // Unified LazyColumn ensures Command Composer + GATT tree never crush each other in split-screen/landscape.
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        item(key = "inspector_header") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "GATT // UUID DB (${knownList.size})",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                OutlinedButton(
                    enabled = !syncing,
                    onClick = {
                        syncing = true
                        statusLine = "Syncing Nordic Bluetooth Numbers DB..."
                        scope.launch {
                            val res = UuidRegistrySync.syncFromNordicDatabase(knownUuidDao)
                            statusLine = res.fold(
                                onSuccess = { count -> "Synced $count official UUIDs into Room DB" },
                                onFailure = { e -> "Sync failed: ${e.message}" }
                            )
                            syncing = false
                        }
                    }
                ) { Text(if (syncing) "SYNCING..." else "SYNC SIG DB", maxLines = 1, softWrap = false) }
            }
        }

        item(key = "inspector_status") {
            Text(
                text = statusLine,
                style = MaterialTheme.typography.labelMedium,
                color = BleForgeColors.Primary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }

        item(key = "inspector_diff") {
            ByteDiffGrid(current = currentBytes, previous = previousBytes)
        }

        // Command Composer Card
        item(key = "inspector_composer") {
            Surface(
                color = BleForgeColors.SurfaceElevated,
                border = BorderStroke(1.dp, BleForgeColors.Border),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Data Type Selector
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        PayloadDataType.entries.forEach { dt ->
                            FilterChip(
                                selected = selectedDataType == dt,
                                onClick = { selectedDataTypeName = dt.name },
                                label = { Text(dt.label, style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false) }
                            )
                        }
                    }

                    // Request (ACK) vs Command (No-ACK) Toggle
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        GattWriteMode.entries.forEach { mode ->
                            FilterChip(
                                selected = selectedWriteMode == mode,
                                onClick = { selectedWriteModeName = mode.name },
                                label = { Text(mode.label, style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false) }
                            )
                        }
                    }
                    Text(selectedWriteMode.guidance, style = MaterialTheme.typography.labelMedium, color = BleForgeColors.TextSecondary)

                    // Preset Commands (AT & Binary)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        StandardCommandCatalog.presets.forEach { preset ->
                            AssistChip(
                                onClick = {
                                    selectedDataTypeName = preset.dataType.name
                                    rawInput = preset.rawInput
                                    selectedWriteModeName = preset.recommendedMode.name
                                    activePresetNote = "${preset.label}: ${preset.description}"
                                },
                                label = { Text(preset.label, style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false) }
                            )
                        }
                    }
                    Text(activePresetNote, style = MaterialTheme.typography.labelMedium, color = BleForgeColors.Secondary)

                    OutlinedTextField(
                        value = rawInput,
                        onValueChange = { rawInput = it },
                        label = { Text(selectedDataType.hint, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    val previewText = encodedPreview.fold(
                        onSuccess = { b -> "Encoded (${b.size}B): ${b.joinToString(" ") { "%02X".format(it) }}" },
                        onFailure = { e -> "Encode Error: ${e.message}" }
                    )
                    Text(previewText, style = MaterialTheme.typography.labelMedium, color = BleForgeColors.Primary)
                }
            }
        }

        if (services.isEmpty()) {
            item(key = "inspector_empty") {
                Surface(
                    color = BleForgeColors.Surface,
                    border = BorderStroke(1.dp, BleForgeColors.Border),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "No GATT services discovered yet — connect to a device from SCANNER.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = BleForgeColors.TextSecondary,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }
        }

        items(services, key = { it.uuid }) { service ->
            val svcKnown = knownMap[service.uuid.toString().lowercase()]
            Surface(
                color = BleForgeColors.Surface,
                border = BorderStroke(1.dp, BleForgeColors.Border),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = svcKnown?.let { "${it.name} (${service.uuid.toString().substring(0, 8)})" } ?: "SVC ${service.uuid}",
                        style = MaterialTheme.typography.labelMedium,
                        color = BleForgeColors.Secondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    service.characteristics.forEach { ch ->
                        val chKnown = knownMap[ch.uuid.toString().lowercase()]
                        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = chKnown?.name ?: ch.uuid.toString().substring(0, 8),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (chKnown?.isUserDiscovered == true) BleForgeColors.Primary else BleForgeColors.TextPrimary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (chKnown != null) {
                                Text(
                                    text = "${chKnown.decodeFormat} • ${chKnown.functionNotes}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = BleForgeColors.TextSecondary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            // FlowRow keeps action buttons readable and prevents squeezing characteristic names.
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                if (ch.isReadable) TextButton(onClick = {
                                    scope.launch {
                                        connectionManager.readCharacteristic(ch.serviceUuid, ch.uuid).fold(
                                            onSuccess = { b ->
                                                previousBytes = currentBytes
                                                currentBytes = b
                                                statusLine = "READ ${chKnown?.name ?: ch.name} (${b.size}B)"
                                            },
                                            onFailure = { e -> statusLine = "READ ERR: ${e.message}" }
                                        )
                                    }
                                }) { Text("R", maxLines = 1, softWrap = false) }

                                if (ch.isWritable || ch.isWritableNoResponse) TextButton(onClick = {
                                    scope.launch {
                                        val bytes = encodedPreview.getOrElse {
                                            statusLine = "Invalid payload: ${it.message}"
                                            return@launch
                                        }
                                        // Respect characteristic support while honoring user's Request/Command preference
                                        val useResponse = when {
                                            selectedWriteMode.withResponse && ch.isWritable -> true
                                            !selectedWriteMode.withResponse && ch.isWritableNoResponse -> false
                                            else -> ch.isWritable
                                        }
                                        statusLine = connectionManager.writeCharacteristic(ch.serviceUuid, ch.uuid, bytes, useResponse).fold(
                                            onSuccess = { "SENT ${bytes.size}B (${if (useResponse) "REQ" else "CMD"}) -> ${chKnown?.name ?: ch.name}" },
                                            onFailure = { e -> "WRITE ERR: ${e.message}" }
                                        )
                                    }
                                }) { Text("W", maxLines = 1, softWrap = false) }

                                if (ch.isNotifiable || ch.isIndicatable) TextButton(onClick = {
                                    activeNotifyJob?.cancel()
                                    activeNotifyJob = streamScope.launch {
                                        statusLine = "SUBSCRIBED -> ${chKnown?.name ?: ch.name}"
                                        connectionManager.observeNotifications(ch.serviceUuid, ch.uuid).collect { b ->
                                            previousBytes = currentBytes
                                            currentBytes = b
                                            statusLine = "NOTIFY ${chKnown?.name ?: ch.name} (${b.size}B)"
                                        }
                                    }
                                }) { Text("N", maxLines = 1, softWrap = false) }

                                TextButton(onClick = { editingCharUuid = ch.uuid.toString() }) {
                                    Text("+DB", maxLines = 1, softWrap = false)
                                }

                                if (chKnown == null) TextButton(onClick = {
                                    val url = "https://www.google.com/search?q=\"${ch.uuid}\"+bluetooth+gatt"
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                }) { Text("WEB", maxLines = 1, softWrap = false) }
                            }
                        }
                    }
                }
            }
        }
    }

    editingChar?.let { target ->
        val existing = knownMap[target.uuid.toString().lowercase()]
        var nameInput by rememberSaveable(target.uuid) { mutableStateOf(existing?.name ?: "") }
        var formatInput by rememberSaveable(target.uuid) { mutableStateOf(existing?.decodeFormat ?: "RAW_HEX") }
        var notesInput by rememberSaveable(target.uuid) { mutableStateOf(existing?.functionNotes ?: "") }

        AlertDialog(
            onDismissRequest = { editingCharUuid = null },
            containerColor = BleForgeColors.Surface,
            title = { Text("Save Discovered UUID") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(target.uuid.toString(), style = MaterialTheme.typography.labelMedium, color = BleForgeColors.TextSecondary)
                    OutlinedTextField(value = nameInput, onValueChange = { nameInput = it }, label = { Text("Function Name (e.g. Odometer)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = formatInput, onValueChange = { formatInput = it }, label = { Text("Decode Format (UINT16_LE, UTF8...)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = notesInput, onValueChange = { notesInput = it }, label = { Text("How It Works / Byte Notes") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        knownUuidDao.upsertUuid(
                            KnownUuidEntity(
                                uuid = target.uuid.toString().lowercase(),
                                shortCode = null,
                                kind = "CHARACTERISTIC",
                                name = nameInput.ifBlank { "Custom Characteristic" },
                                category = "USER_DISCOVERED",
                                decodeFormat = formatInput.ifBlank { "RAW_HEX" },
                                functionNotes = notesInput,
                                isUserDiscovered = true
                            )
                        )
                        editingCharUuid = null
                    }
                }) { Text("SAVE", maxLines = 1, softWrap = false) }
            },
            dismissButton = {
                TextButton(onClick = { editingCharUuid = null }) { Text("CANCEL", maxLines = 1, softWrap = false) }
            }
        )
    }
}
