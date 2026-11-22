<!--
  FILE: README.md
  CURRENT VERSION: v1.0.10
  CHANGELOG:
    - v1.0.10: Created root GitHub README.md documenting BleForge architecture, features,
               Android 17 safeguards, UUID registry sync, conditional macros, and CI/CD.
-->

# BleForge

**BleForge** is a native Android (Kotlin + Jetpack Compose) Bluetooth Low Energy (BLE) diagnostic cockpit, tactical telemetry HUD, and conditional automation tool built for **Android 12+ (`minSdk = 31`)** and targeting **Android 17 (`targetSdk = 37`)**.

It goes beyond standard hex-only GATT browsers by combining resilient connection management, a pre-seeded & syncable UUID knowledge base, live visual gauges, and a `WHEN` / `IF` / `ELSE` macro engine.

---

## Core Capabilities

### 1. Tactical 3-Tab Interface
* **`SCANNER`:** Rate-limited BLE sweep (`ScanRateLimiter` prevents Android's 5-scans-per-30s silent lockout) with `SignalBars` RSSI meters and manufacturer IDs. Tapping a target starts the `connectedDevice` foreground service and runs an ordered handshake (`connect` → `discoverServices` → `MTU 247` → `HIGH` connection priority).
* **`HUD` (Telemetry & Macro Deck):** Custom Compose `Canvas` gauges (`TacticalArcGauge`, `LiveSparkline`), a smart-device relay switch & `HueControlStrip`, and a 1-tap **Conditional Macro Deck**.
* **`GATT` (Inspector & UUID DB):** Displays discovered services and characteristics resolved against Room, an 8-byte `ByteDiffGrid` that flashes changed bytes in cyan on live notifications, manual Read (`R`) / Write (`W`) / Notify (`N`) controls, **`+DB`** to document newly discovered UUIDs, and **`SYNC SIG DB`** to import the full [Nordic Bluetooth Numbers Database](https://github.com/nordicsemi/bluetooth-numbers-database).

### 2. Resilient Android 12–17 BLE Engine
* Built on [Nordic's Android-BLE-Library](https://github.com/nordicsemi/Android-BLE-Library) (`ble-ktx:2.11.0`) for serialized GATT command queuing and automatic long-write MTU splitting.
* **GATT 133 Recovery:** Explicitly rethrows `CancellationException`, tears down cleanly with `disconnect()` + `close()`, and retries with exponential backoff (`ReconnectBackoff`).
* **Keep-Alive Heartbeat:** Configurable periodic read/write loop to prevent peripheral idle timeouts.
* **3-Layer Crash & Error Handler:** `GlobalErrorBus` (`CoroutineExceptionHandler`) surfaces non-fatal errors in an animated `ErrorBanner`, while `GlobalCrashHandler` + `CrashLogStore` persist fatal stack traces to disk and launch `CrashRecoveryActivity` so you never have to dig through `logcat`.

### 3. Conditional BLE Macro Engine (`WHEN` / `IF` / `ELSE`)
Saved in Room (`ble_macros`) as JSON and executed sequentially with zero polling:
* `WhenNotify`: Suspends until a characteristic emits a notification (or times out).
* `IfBytes`: Evaluates `StartsWith`, `Equals`, `ByteAtEquals`, or `ByteAtGreaterThan` against the last packet and branches into `thenSteps` or `elseSteps`.
* `Write`, `Read`, `Delay`, and `Repeat` steps.

---

## Module Architecture

| Module | Responsibility |
| :--- | :--- |
| `:app` | `MainActivity`, `BleForgeApplication`, `GlobalErrorHandler`, `CrashRecoveryActivity`, `UpdateChecker`, adaptive icon |
| `:core:model` | `BleDevice`, `ScanState`, `ConnectionState`, `GattServiceInfo`, `HeartbeatConfig`, `MacroStep`, `ByteCondition` |
| `:core:ui` | `Theme.kt` (tactical dark palette), `ErrorBanner`, `TacticalArcGauge`, `LiveSparkline`, `SignalBars`, `HueControlStrip`, `ByteDiffGrid` |
| `:core:database` | Room v3 (`BleForgeDatabase`), `DeviceProfileDao`, `KnownUuidDao`, `BleMacroDao`, `UuidRegistrySeed`, `UuidRegistrySync` |
| `:feature:bluetooth` | `AndroidBleScanner`, `ScanRateLimiter`, `NordicBleConnectionManager`, `ReconnectBackoff`, `BleForegroundService`, `BleMacroEngine` |
| `:feature:dashboard` | `DashboardScreen` (live HUD gauges, smart-device controls, Macro Deck) |
| `:feature:inspector` | `GattInspectorScreen` (GATT tree, `ByteDiffGrid`, `+DB` modal, `SYNC SIG DB`) |

---

## Building & CI/CD (GitHub Actions)

* **Toolchain:** Gradle `9.6.0`, AGP `9.2.0` (built-in Kotlin), Compose BOM `2026.09.00`, Room `2.8.4`, KSP `2.3.12`, JDK 17.
* **Zero-Setup Cloud Builds:** `.github/workflows/android-ci.yml` installs Gradle `9.6.0` and caches both Gradle dependencies and Android 17 (API 37) SDK + licenses automatically.
* **Triggers:** Runs on every push/PR to `main`/`master`, or manually via **Actions → Android CI → Run workflow**.
* **Auto-Versioning & In-App Updates:** Each CI run sets `versionCode = GITHUB_RUN_NUMBER` (`versionName = "1.0.<run>"`) and publishes `app-debug.apk` as a tagged GitHub Release (`v1.0.<run>`). To enable the in-app update banner, set `GITHUB_REPO` in `app/build.gradle.kts` to `"your-username/your-repo"`.
