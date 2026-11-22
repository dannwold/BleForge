/*
 * ==============================================================================
 * FILE: core/database/src/main/kotlin/com/bleforge/core/database/MacroStorage.kt
 * CURRENT VERSION: v1.0.18
 * CHANGELOG:
 *   - v1.0.6: Initial Room entity/DAO and JSON codec for conditional BLE macros.
 *   - v1.0.18: Added full file header, KDoc, and inline comments (no functional changes).
 * HOW IT WORKS:
 *   Stores user-defined BleMacroEntity rows in Room (`ble_macros` table) with
 *   steps serialized as nested JSON via MacroJsonCodec, supporting Write, Read,
 *   Delay, WhenNotify, IfBytes branching, and Repeat loops.
 * ==============================================================================
 */
package com.bleforge.core.database

import androidx.room.*
import com.bleforge.core.model.ByteCondition
import com.bleforge.core.model.MacroStep
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Room table entity representing a saved conditional BLE macro.
 *
 * @property macroId Unique macro identifier (primary key).
 * @property name Human-readable macro name shown in the Macro Runner UI.
 * @property targetServiceUuid Optional primary GATT service UUID this macro targets.
 * @property stepsJson JSON-serialized list of [MacroStep] instructions.
 */
@Entity(tableName = "ble_macros")
data class BleMacroEntity(
    @PrimaryKey val macroId: String,
    val name: String,
    val targetServiceUuid: String?,
    val stepsJson: String
)

/**
 * Room DAO for observing, saving, and deleting [BleMacroEntity] records.
 */
@Dao
interface BleMacroDao {
    @Query("SELECT * FROM ble_macros ORDER BY name ASC")
    fun observeMacros(): Flow<List<BleMacroEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMacro(macro: BleMacroEntity)

    @Query("DELETE FROM ble_macros WHERE macroId = :id")
    suspend fun deleteMacro(id: String)
}

/**
 * Pure-Kotlin `org.json` serializer/deserializer for recursive [MacroStep] trees
 * and [ByteCondition] predicates (avoids adding Gson/Moshi/kotlinx-serialization).
 */
object MacroJsonCodec {
    /** Serializes a list of [MacroStep] items into a compact JSON array string. */
    fun encode(steps: List<MacroStep>): String = JSONArray().apply {
        steps.forEach { put(encodeStep(it)) }
    }.toString()

    /** Deserializes a JSON array string back into a typed [MacroStep] list. */
    fun decode(json: String): List<MacroStep> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { decodeStep(arr.getJSONObject(it)) }
    }

    private fun encodeStep(step: MacroStep): JSONObject = JSONObject().apply {
        when (step) {
            is MacroStep.Write -> {
                put("type", "WRITE")
                put("service", step.serviceUuid.toString())
                put("char", step.charUuid.toString())
                put("hex", step.hexPayload)
                put("withResponse", step.withResponse)
            }
            is MacroStep.Read -> {
                put("type", "READ")
                put("service", step.serviceUuid.toString())
                put("char", step.charUuid.toString())
            }
            is MacroStep.Delay -> {
                put("type", "DELAY")
                put("ms", step.ms)
            }
            is MacroStep.WhenNotify -> {
                put("type", "WHEN_NOTIFY")
                put("service", step.serviceUuid.toString())
                put("char", step.charUuid.toString())
                put("timeoutMs", step.timeoutMs)
            }
            is MacroStep.IfBytes -> {
                put("type", "IF_BYTES")
                put("cond", encodeCondition(step.condition))
                put("then", JSONArray().apply { step.thenSteps.forEach { put(encodeStep(it)) } })
                put("else", JSONArray().apply { step.elseSteps.forEach { put(encodeStep(it)) } })
            }
            is MacroStep.Repeat -> {
                put("type", "REPEAT")
                put("times", step.times)
                put("steps", JSONArray().apply { step.steps.forEach { put(encodeStep(it)) } })
            }
        }
    }

    private fun decodeStep(o: JSONObject): MacroStep = when (o.getString("type")) {
        "WRITE" -> MacroStep.Write(
            UUID.fromString(o.getString("service")),
            UUID.fromString(o.getString("char")),
            o.getString("hex"),
            o.optBoolean("withResponse", true)
        )
        "READ" -> MacroStep.Read(
            UUID.fromString(o.getString("service")),
            UUID.fromString(o.getString("char"))
        )
        "DELAY" -> MacroStep.Delay(o.getLong("ms"))
        "WHEN_NOTIFY" -> MacroStep.WhenNotify(
            UUID.fromString(o.getString("service")),
            UUID.fromString(o.getString("char")),
            o.optLong("timeoutMs", 5_000L)
        )
        "IF_BYTES" -> {
            val thenArr = o.getJSONArray("then")
            val elseArr = o.optJSONArray("else") ?: JSONArray()
            MacroStep.IfBytes(
                condition = decodeCondition(o.getJSONObject("cond")),
                thenSteps = (0 until thenArr.length()).map { decodeStep(thenArr.getJSONObject(it)) },
                elseSteps = (0 until elseArr.length()).map { decodeStep(elseArr.getJSONObject(it)) }
            )
        }
        "REPEAT" -> {
            val arr = o.getJSONArray("steps")
            MacroStep.Repeat(
                times = o.getInt("times"),
                steps = (0 until arr.length()).map { decodeStep(arr.getJSONObject(it)) }
            )
        }
        else -> error("Unknown macro step type")
    }

    private fun encodeCondition(c: ByteCondition): JSONObject = JSONObject().apply {
        when (c) {
            is ByteCondition.StartsWith -> { put("kind", "STARTS_WITH"); put("hex", c.hexPrefix) }
            is ByteCondition.Equals -> { put("kind", "EQUALS"); put("hex", c.hexExact) }
            is ByteCondition.ByteAtEquals -> { put("kind", "BYTE_EQ"); put("idx", c.index); put("hex", c.byteHex) }
            is ByteCondition.ByteAtGreaterThan -> { put("kind", "BYTE_GT"); put("idx", c.index); put("val", c.threshold) }
        }
    }

    private fun decodeCondition(o: JSONObject): ByteCondition = when (o.getString("kind")) {
        "STARTS_WITH" -> ByteCondition.StartsWith(o.getString("hex"))
        "EQUALS" -> ByteCondition.Equals(o.getString("hex"))
        "BYTE_EQ" -> ByteCondition.ByteAtEquals(o.getInt("idx"), o.getString("hex"))
        "BYTE_GT" -> ByteCondition.ByteAtGreaterThan(o.getInt("idx"), o.getInt("val"))
        else -> error("Unknown condition kind")
    }
}
