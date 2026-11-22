/*
 * ==============================================================================
 * FILE: core/model/src/main/kotlin/com/bleforge/core/model/GattModels.kt
 * CURRENT VERSION: v1.0.18
 * CHANGELOG:
 *   - v1.0.2: Initial immutable GATT service, characteristic, and heartbeat models.
 *   - v1.0.18: Added full file header and KDoc comments (no functional changes).
 * HOW IT WORKS:
 *   Defines platform-agnostic GATT data models shared between :feature:bluetooth,
 *   :feature:dashboard, and :feature:inspector without leaking Android SDK types.
 * ==============================================================================
 */
package com.bleforge.core.model

import java.util.UUID

/**
 * Discovered GATT service and its child characteristics.
 */
data class GattServiceInfo(
    val uuid: UUID,
    val name: String,
    val characteristics: List<GattCharacteristicInfo>
)

/**
 * Discovered GATT characteristic with capability flags and latest cached payload.
 */
data class GattCharacteristicInfo(
    val uuid: UUID,
    val serviceUuid: UUID,
    val name: String,
    val isReadable: Boolean,
    val isWritable: Boolean,
    val isWritableNoResponse: Boolean,
    val isNotifiable: Boolean,
    val isIndicatable: Boolean,
    val lastValue: ByteArray? = null
)

/**
 * Periodic keepalive write configuration for peripherals with idle disconnect timers.
 */
data class HeartbeatConfig(
    val enabled: Boolean = false,
    val intervalMs: Long = 10_000L,
    val serviceUuid: UUID,
    val charUuid: UUID,
    val payload: ByteArray? = null
)
