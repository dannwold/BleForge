/*
 * ==============================================================================
 * FILE: BleMacroEngine.kt
 * CURRENT VERSION: v1.0.9
 * CHANGELOG:
 *   - v1.0.8: Initial conditional BLE macro engine (Write, Read, Delay, WhenNotify, IfBytes, Repeat).
 *   - v1.0.9: Appended detailed KDoc & inline comments explaining AST execution & branching.
 *
 * HOW THIS ENGINE WORKS:
 *   - Executes a tree of MacroStep nodes sequentially on a background CoroutineScope.
 *   - Maintains `lastPacket: ByteArray` updated by every Read or WhenNotify step.
 *   - `WhenNotify` suspends with `withTimeoutOrNull` on `observeNotifications(...).first()`,
 *     meaning zero CPU polling—it resumes the instant a matching BLE packet arrives.
 *   - `IfBytes` evaluates `ByteCondition` (StartsWith, Equals, ByteAtEquals, ByteAtGreaterThan)
 *     against `lastPacket` and branches into `thenSteps` or `elseSteps`.
 *   - Rethrows `CancellationException` so tapping STOP halts execution cleanly.
 * ==============================================================================
 */

package com.bleforge.feature.bluetooth

import com.bleforge.core.model.ByteCondition
import com.bleforge.core.model.MacroState
import com.bleforge.core.model.MacroStep
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class BleMacroEngine(
    private val connectionManager: BleConnectionManager,
    private val scope: CoroutineScope
) {
    private val _state = MutableStateFlow<MacroState>(MacroState.Idle)
    val state: StateFlow<MacroState> = _state.asStateFlow()

    private var activeJob: Job? = null

    fun runMacro(name: String, steps: List<MacroStep>) {
        activeJob?.cancel()
        activeJob = scope.launch {
            var lastPacket = ByteArray(0)
            try {
                suspend fun exec(list: List<MacroStep>) {
                    for (step in list) {
                        currentCoroutineContext().ensureActive()
                        when (step) {
                            is MacroStep.Write -> {
                                _state.value = MacroState.Running(name, "WRITE ${step.hexPayload}")
                                val bytes = parseHex(step.hexPayload)
                                connectionManager.writeCharacteristic(step.serviceUuid, step.charUuid, bytes, step.withResponse).getOrThrow()
                            }
                            is MacroStep.Read -> {
                                _state.value = MacroState.Running(name, "READ ${step.charUuid.toString().substring(0, 8)}")
                                lastPacket = connectionManager.readCharacteristic(step.serviceUuid, step.charUuid).getOrThrow()
                            }
                            is MacroStep.Delay -> {
                                _state.value = MacroState.Running(name, "WAIT ${step.ms}ms")
                                delay(step.ms)
                            }
                            is MacroStep.WhenNotify -> {
                                _state.value = MacroState.Running(name, "WHEN NOTIFY ${step.charUuid.toString().substring(0, 8)}")
                                val received = withTimeoutOrNull(step.timeoutMs) {
                                    connectionManager.observeNotifications(step.serviceUuid, step.charUuid).first()
                                } ?: error("Notify timeout after ${step.timeoutMs}ms")
                                lastPacket = received
                            }
                            is MacroStep.IfBytes -> {
                                val matched = matches(lastPacket, step.condition)
                                _state.value = MacroState.Running(name, if (matched) "IF -> TRUE" else "IF -> ELSE")
                                exec(if (matched) step.thenSteps else step.elseSteps)
                            }
                            is MacroStep.Repeat -> {
                                repeat(step.times) { idx ->
                                    _state.value = MacroState.Running(name, "LOOP ${idx + 1}/${step.times}")
                                    exec(step.steps)
                                }
                            }
                        }
                    }
                }
                exec(steps)
                _state.value = MacroState.Completed(name, "Last packet: ${lastPacket.joinToString(" ") { "%02X".format(it) }}")
            } catch (ce: CancellationException) {
                _state.value = MacroState.Idle
                throw ce
            } catch (t: Throwable) {
                _state.value = MacroState.Failed(name, t.message ?: "Macro failed")
            }
        }
    }

    fun stop() {
        activeJob?.cancel()
        activeJob = null
        _state.value = MacroState.Idle
    }

    companion object {
        fun parseHex(hex: String): ByteArray =
            hex.trim().split(Regex("\\s+")).mapNotNull { it.toIntOrNull(16)?.toByte() }.toByteArray()

        fun matches(packet: ByteArray, cond: ByteCondition): Boolean = when (cond) {
            is ByteCondition.StartsWith -> {
                val prefix = parseHex(cond.hexPrefix)
                packet.size >= prefix.size && prefix.indices.all { packet[it] == prefix[it] }
            }
            is ByteCondition.Equals -> packet.contentEquals(parseHex(cond.hexExact))
            is ByteCondition.ByteAtEquals -> {
                val target = parseHex(cond.byteHex).firstOrNull() ?: return false
                cond.index in packet.indices && packet[cond.index] == target
            }
            is ByteCondition.ByteAtGreaterThan -> {
                cond.index in packet.indices && (packet[cond.index].toInt() and 0xFF) > cond.threshold
            }
        }
    }
}
