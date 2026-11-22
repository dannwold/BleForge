/*
 * ==============================================================================
 * FILE: feature/bluetooth/src/test/kotlin/com/bleforge/feature/bluetooth/BleMacroEngineTest.kt
 * CURRENT VERSION: v1.0.18
 * CHANGELOG:
 *   - v1.0.6: Initial coroutine unit test for WhenNotify + IfBytes macro branching.
 *   - v1.0.18: Added full file header, KDoc, and inline comments (no functional changes).
 * HOW IT WORKS:
 *   Injects an in-memory fake BleConnectionManager that emits `0xAA 0x05` on
 *   observeNotifications(), runs a WhenNotify -> IfBytes macro, and verifies
 *   only the `then` branch write (`0x11 0x22`) executes.
 * ==============================================================================
 */
package com.bleforge.feature.bluetooth

import com.bleforge.core.model.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** Coroutine unit tests for [BleMacroEngine] conditional branching. */
@OptIn(ExperimentalCoroutinesApi::class)
class BleMacroEngineTest {
    private val svc = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    private val ch = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

    @Test
    fun `whenNotify triggers then or else branch based on payload`() = runTest {
        val writes = mutableListOf<ByteArray>()
        // Fake BleConnectionManager recording writes and emitting a deterministic notify packet.
        val fakeMgr = object : BleConnectionManager {
            override val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
            override val bondState = MutableStateFlow(BondState.NONE)
            override val discoveredServices = MutableStateFlow<List<GattServiceInfo>>(emptyList())
            override suspend fun connect(address: String, autoConnect: Boolean) {}
            override suspend fun disconnect() {}
            override suspend fun bond() = Result.success(Unit)
            override suspend fun unbond() = Result.success(Unit)
            override suspend fun refreshGattCache() {}
            override suspend fun readCharacteristic(serviceUuid: UUID, charUuid: UUID) = Result.success(byteArrayOf(0x01))
            override suspend fun writeCharacteristic(serviceUuid: UUID, charUuid: UUID, data: ByteArray, withResponse: Boolean): Result<Unit> {
                writes += data
                return Result.success(Unit)
            }
            override fun observeNotifications(serviceUuid: UUID, charUuid: UUID) = flowOf(byteArrayOf(0xAA.toByte(), 0x05))
            override fun startHeartbeat(config: HeartbeatConfig) {}
            override fun stopHeartbeat() {}
        }

        val engine = BleMacroEngine(fakeMgr, this)
        engine.runMacro(
            "TestBranch",
            listOf(
                MacroStep.WhenNotify(svc, ch, timeoutMs = 1000L),
                MacroStep.IfBytes(
                    condition = ByteCondition.StartsWith("AA"),
                    thenSteps = listOf(MacroStep.Write(svc, ch, "11 22")),
                    elseSteps = listOf(MacroStep.Write(svc, ch, "99"))
                )
            )
        )
        advanceUntilIdle()
        assertEquals(1, writes.size)
        assertTrue(writes.first().contentEquals(byteArrayOf(0x11, 0x22)))
        assertTrue(engine.state.value is MacroState.Completed)
    }
}
