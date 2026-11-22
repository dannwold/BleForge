/*
 * ==============================================================================
 * FILE: MainActivity.kt
 * CURRENT VERSION: v1.0.22
 * CHANGELOG:
 *   - v1.0.1: Initial 1-tab Scanner UI.
 *   - v1.0.3: Expanded to 3-tab Scaffold (Scanner, Dashboard, Inspector) + BleForegroundService.
 *   - v1.0.4: Added enableEdgeToEdge() and SignalBars custom Canvas indicator.
 *   - v1.0.5: Added CrashLogStore recovery check + GitHub Release UpdateChecker banner.
 *   - v1.0.6: Wired BleForgeDatabase.knownUuidDao() into GattInspectorScreen.
 *   - v1.0.8: Wired BleMacroEngine & BleMacroDao into DashboardScreen.
 *   - v1.0.9: Appended detailed KDoc & inline comments explaining lifecycle & tab wiring.
 *   - v1.0.19: Hoisted BLE managers to BleForgeApplication, added rememberSaveable +
 *              SaveableStateHolder to preserve state across tab switches, window
 *              resizing, and app switching, and added text overflow guards.
 *   - v1.0.20: Switched to lifecycle-aware collectAsStateWithLifecycle(), passed
 *              app.appScope for tab-persistent BLE streams, added Scanner empty
 *              state, and added Battery Saver / Samsung Sleeping Apps exemption
 *              banner with live ON_RESUME re-check.
 *   - v1.0.22: Re-checked BLUETOOTH_SCAN/CONNECT on ON_RESUME and requested
 *              POST_NOTIFICATIONS on Android 13+ so foreground service controls stay visible.
 * ==============================================================================
 */

package com.bleforge.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bleforge.core.model.*
import com.bleforge.core.ui.*
import com.bleforge.feature.bluetooth.*
import com.bleforge.feature.dashboard.DashboardScreen
import com.bleforge.feature.dashboard.ServerScreen
import com.bleforge.feature.inspector.GattInspectorScreen
import kotlinx.coroutines.*

/**
 * Single-activity Compose host for BleForge's 4-tab diagnostic HUD.
 * Retrieves process-scoped BLE managers from [BleForgeApplication] so active scans,
 * GATT links, macros, and server logs survive resizing, rotation, and app switching.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Surface any prior background crash trace captured before process restart.
        CrashLogStore.consume(applicationContext)?.let { priorTrace ->
            GlobalErrorBus.report("Recovered Prior Crash", priorTrace.lines().firstOrNull() ?: "See crash log")
        }

        // Process-scoped managers live on BleForgeApplication so Activity recreation never drops links.
        val app = application as BleForgeApplication
        val db = app.database
        val scanner = app.scanner
        val connectionManager = app.connectionManager
        val macroEngine = app.macroEngine
        val serverManager = app.serverManager

        setContent {
            BleForgeTheme {
                // Persist active tab and per-tab Compose state across tab switches & config changes.
                var selectedTab by rememberSaveable { mutableIntStateOf(0) }
                val saveableStateHolder = rememberSaveableStateHolder()
                val appError by GlobalErrorBus.currentError.collectAsStateWithLifecycle()
                var availableUpdate by remember { mutableStateOf<AppUpdateInfo?>(null) }

                // Re-check battery optimization exemption on ON_RESUME (e.g. after returning from Samsung Settings).
                val powerManager = remember { getSystemService(PowerManager::class.java) }
                var ignoringBatteryOpt by remember {
                    mutableStateOf(powerManager?.isIgnoringBatteryOptimizations(packageName) ?: true)
                }
                var batteryBannerDismissed by rememberSaveable { mutableStateOf(false) }
                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            ignoringBatteryOpt = powerManager?.isIgnoringBatteryOptimizations(packageName) ?: true
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                LaunchedEffect(Unit) {
                    availableUpdate = UpdateChecker.checkForUpdate()
                }

                Scaffold(
                    containerColor = BleForgeColors.Background,
                    bottomBar = {
                        NavigationBar(containerColor = BleForgeColors.Surface) {
                            listOf("SCANNER", "HUD", "GATT", "SERVER").forEachIndexed { idx, label ->
                                NavigationBarItem(
                                    selected = selectedTab == idx,
                                    onClick = { selectedTab = idx },
                                    icon = {},
                                    label = {
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.labelMedium,
                                            maxLines = 1,
                                            softWrap = false,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                )
                            }
                        }
                    }
                ) { padding ->
                    Column(modifier = Modifier.padding(padding).fillMaxSize()) {
                        if (!ignoringBatteryOpt && !batteryBannerDismissed) {
                            Surface(
                                color = BleForgeColors.Warning.copy(alpha = 0.14f),
                                border = BorderStroke(1.dp, BleForgeColors.Warning.copy(alpha = 0.7f)),
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Battery saver active — set BleForge to Unrestricted so Samsung/OEM sleep doesn't drop BLE.",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = BleForgeColors.Warning,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(onClick = {
                                        runCatching {
                                            startActivity(
                                                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                                    data = Uri.parse("package:$packageName")
                                                }
                                            )
                                        }.onFailure {
                                            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                                        }
                                    }) { Text("UNRESTRICT", maxLines = 1, softWrap = false) }
                                    TextButton(onClick = { batteryBannerDismissed = true }) {
                                        Text("HIDE", maxLines = 1, softWrap = false)
                                    }
                                }
                            }
                        }
                        availableUpdate?.let { upd ->
                            Surface(
                                color = BleForgeColors.Primary.copy(alpha = 0.14f),
                                border = BorderStroke(1.dp, BleForgeColors.Primary),
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Update ${upd.latestTag} ready",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = BleForgeColors.Primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(onClick = {
                                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(upd.downloadUrl)))
                                    }) { Text("INSTALL", maxLines = 1, softWrap = false) }
                                }
                            }
                        }
                        ErrorBanner(error = appError, onDismiss = { GlobalErrorBus.clear() })
                        saveableStateHolder.SaveableStateProvider(key = selectedTab) {
                            when (selectedTab) {
                                0 -> ScannerTab(scanner, connectionManager) { selectedTab = 1 }
                                1 -> DashboardScreen(connectionManager, macroEngine, db.macroDao(), db.profileDao(), streamScope = app.appScope)
                                2 -> GattInspectorScreen(connectionManager, db.knownUuidDao(), streamScope = app.appScope)
                                else -> ServerScreen(serverManager)
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ScannerTab(
        scanner: AndroidBleScanner,
        connectionManager: NordicBleConnectionManager,
        onConnectedNav: () -> Unit
    ) {
        val devices by scanner.discoveredDevices.collectAsStateWithLifecycle()
        val scanState by scanner.scanState.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope { SupervisorJob() + GlobalErrorBus.coroutineHandler }

        fun checkBtPermissions(): Boolean =
            checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

        var hasPermissions by remember { mutableStateOf(checkBtPermissions()) }
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    hasPermissions = checkBtPermissions()
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            hasPermissions = results[Manifest.permission.BLUETOOTH_SCAN] == true &&
                results[Manifest.permission.BLUETOOTH_CONNECT] == true
            if (!hasPermissions) GlobalErrorBus.report("Permissions Needed", "Grant Nearby Devices permissions so BleForge can scan.")
        }

        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Weight(1f) ensures long status/error strings never push the SWEEP button off-screen.
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "BLEFORGE // v${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val statusText = when (val s = scanState) {
                        ScanState.Idle -> "IDLE • ${devices.size} targets"
                        ScanState.Scanning -> "ACTIVE SWEEP • ${devices.size} targets"
                        is ScanState.Throttled -> "RATE GUARD • wait ${s.retryAfterMs / 1000}s"
                        is ScanState.Error -> "ERROR • ${s.message}"
                    }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelMedium,
                        color = BleForgeColors.TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Button(onClick = {
                    if (!hasPermissions) {
                        val reqs = buildList {
                            add(Manifest.permission.BLUETOOTH_SCAN)
                            add(Manifest.permission.BLUETOOTH_CONNECT)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                add(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }.toTypedArray()
                        permissionLauncher.launch(reqs)
                    } else {
                        scope.launch { if (scanState is ScanState.Scanning) scanner.stopScan() else scanner.startScan() }
                    }
                }) {
                    Text(
                        text = if (scanState is ScanState.Scanning) "STOP" else "SWEEP",
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 12.dp)
            ) {
                if (devices.isEmpty()) {
                    item(key = "scanner_empty") {
                        Surface(
                            color = BleForgeColors.Surface,
                            border = BorderStroke(1.dp, BleForgeColors.Border),
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = if (scanState is ScanState.Scanning) "Sweeping for nearby BLE peripherals..." else "No BLE targets discovered yet — tap SWEEP to scan.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = BleForgeColors.TextSecondary,
                                modifier = Modifier.padding(14.dp)
                            )
                        }
                    }
                }
                items(devices, key = { it.address }) { device ->
                    Surface(
                        color = BleForgeColors.Surface,
                        border = BorderStroke(1.dp, BleForgeColors.Border),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth().clickable {
                            scope.launch {
                                scanner.stopScan()
                                startForegroundService(Intent(this@MainActivity, BleForegroundService::class.java))
                                connectionManager.connect(device.address, autoConnect = false)
                                onConnectedNav()
                            }
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = device.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val vendor = AdvertisingParser.vendorName(device.manufacturerId)
                                Text(
                                    text = if (vendor != null) "${device.address} • $vendor" else device.address,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = BleForgeColors.TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "${device.rssi} dBm",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = BleForgeColors.TextSecondary,
                                    maxLines = 1,
                                    softWrap = false
                                )
                                SignalBars(rssi = device.rssi)
                            }
                        }
                    }
                }
            }
        }
    }
}
