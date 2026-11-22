/*
 * ==============================================================================
 * FILE: core/model/src/main/kotlin/com/bleforge/core/model/MacroModels.kt
 * CURRENT VERSION: v1.0.18
 * CHANGELOG:
 *   - v1.0.6: Initial sealed AST models for conditional BLE macros and execution state.
 *   - v1.0.18: Added full file header and KDoc comments (no functional changes).
 * HOW IT WORKS:
 *   Defines ByteCondition predicates, recursive MacroStep instructions, and
 *   MacroState progress states consumed by BleMacroEngine and MacroJsonCodec.
 * ==============================================================================
 */
package com.bleforge.core.model

import java.util.UUID

/**
 * Byte-level predicate evaluated against the latest GATT read/notify payload.
 */
sealed interface ByteCondition {
    data class StartsWith(val hexPrefix: String) : ByteCondition
    data class Equals(val hexExact: String) : ByteCondition
    data class ByteAtEquals(val index: Int, val byteHex: String) : ByteCondition
    data class ByteAtGreaterThan(val index: Int, val threshold: Int) : ByteCondition
}

/**
 * Executable step in a conditional BLE automation macro.
 */
sealed interface MacroStep {
    data class Write(
        val serviceUuid: UUID,
        val charUuid: UUID,
        val hexPayload: String,
        val withResponse: Boolean = true
    ) : MacroStep

    data class Read(
        val serviceUuid: UUID,
        val charUuid: UUID
    ) : MacroStep

    data class Delay(val ms: Long) : MacroStep

    data class WhenNotify(
        val serviceUuid: UUID,
        val charUuid: UUID,
        val timeoutMs: Long = 5_000L
    ) : MacroStep

    data class IfBytes(
        val condition: ByteCondition,
        val thenSteps: List<MacroStep>,
        val elseSteps: List<MacroStep> = emptyList()
    ) : MacroStep

    data class Repeat(
        val times: Int,
        val steps: List<MacroStep>
    ) : MacroStep
}

/**
 * Observable lifecycle state of [BleMacroEngine] exposed to the Dashboard UI.
 */
sealed interface MacroState {
    data object Idle : MacroState
    data class Running(val macroName: String, val detail: String) : MacroState
    data class Completed(val macroName: String, val summary: String) : MacroState
    data class Failed(val macroName: String, val reason: String) : MacroState
}
