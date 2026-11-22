/*
 * ==============================================================================
 * FILE: UuidRegistrySync.kt
 * CURRENT VERSION: v1.0.21
 * CHANGELOG:
 *   - v1.0.7: Initial 1-tap HTTPS sync from Nordic Semiconductor's bluetooth-numbers-database.
 *   - v1.0.9: Appended detailed KDoc & inline comments explaining UUID normalization & IGNORE merge.
 *   - v1.0.21: Wrapped HttpURLConnection in try/finally disconnect() + .use {}
 *              stream closing and rethrew CancellationException.
 *
 * HOW THIS SYNC WORKS:
 *   - Fetches service_uuids.json and characteristic_uuids.json from GitHub over HTTPS.
 *   - Expands 4-char 16-bit SIG UUIDs (e.g. "180F") into full 128-bit Bluetooth Base UUIDs
 *     ("0000180f-0000-1000-8000-00805f9b34fb") so lookups match Android's BluetoothGatt UUIDs.
 *   - Inserts via OnConflictStrategy.IGNORE so user-discovered (+DB) entries are never overwritten.
 * ==============================================================================
 */

package com.bleforge.core.database

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

object UuidRegistrySync {
    private const val SERVICES_URL =
        "https://raw.githubusercontent.com/NordicSemiconductor/bluetooth-numbers-database/master/v1/service_uuids.json"
    private const val CHARS_URL =
        "https://raw.githubusercontent.com/NordicSemiconductor/bluetooth-numbers-database/master/v1/characteristic_uuids.json"

    suspend fun syncFromNordicDatabase(dao: KnownUuidDao): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val services = fetchAndParse(SERVICES_URL, "SERVICE")
            val chars = fetchAndParse(CHARS_URL, "CHARACTERISTIC")
            val all = services + chars
            dao.insertAll(all)
            Result.success(all.size)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private fun fetchAndParse(urlString: String, kind: String): List<KnownUuidEntity> {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode} fetching $kind UUIDs")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONArray(body)
            val out = ArrayList<KnownUuidEntity>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val rawUuid = obj.optString("uuid").trim()
                val name = obj.optString("name").trim()
                val source = obj.optString("source", "gss").uppercase()
                if (rawUuid.isEmpty() || name.isEmpty()) continue

                val normalized = if (rawUuid.length == 4) {
                    "0000${rawUuid.lowercase()}-0000-1000-8000-00805f9b34fb"
                } else {
                    rawUuid.lowercase()
                }
                val shortCode = if (rawUuid.length == 4) rawUuid.uppercase() else null
                out += KnownUuidEntity(
                    uuid = normalized,
                    shortCode = shortCode,
                    kind = kind,
                    name = name,
                    category = source,
                    decodeFormat = "SIG_SPEC",
                    functionNotes = "Synced from Nordic Bluetooth Numbers DB ($source)",
                    isUserDiscovered = false
                )
            }
            return out
        } finally {
            conn?.disconnect()
        }
    }
}
