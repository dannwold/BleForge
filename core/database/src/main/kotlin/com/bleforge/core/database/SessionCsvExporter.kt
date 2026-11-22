/*
 * ==============================================================================
 * FILE: SessionCsvExporter.kt
 * CURRENT VERSION: v1.0.22
 * CHANGELOG:
 *   - v1.0.13: Created SessionCsvExporter to export Room telemetry_logs to a
 *              shareable CSV file via Android FileProvider.
 *   - v1.0.22: Fixed disconnected export (uses observeAllLogs() when address is
 *              blank instead of broken "%" equality), added RFC-4180 CSV escaping,
 *              pruned old export files (keep latest 5), and launched chooser on Main.
 * ==============================================================================
 */
package com.bleforge.core.database

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

object SessionCsvExporter {
    private fun escapeCsv(raw: String): String {
        val needsQuotes = raw.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuotes) "\"${raw.replace("\"", "\"\"")}\"" else raw
    }

    suspend fun exportAndShare(context: Context, dao: DeviceProfileDao, address: String): Result<Int> {
        return try {
            val (file, count) = withContext(Dispatchers.IO) {
                val logs = if (address.isBlank()) {
                    dao.observeAllLogs().first()
                } else {
                    dao.observeLogs(address).first()
                }
                val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                // Prune older cached CSV exports so cacheDir doesn't grow unboundedly.
                dir.listFiles()
                    ?.sortedByDescending { it.lastModified() }
                    ?.drop(4)
                    ?.forEach { it.delete() }

                val file = File(dir, "bleforge_session_${System.currentTimeMillis()}.csv")
                file.bufferedWriter().use { out ->
                    out.appendLine("timestamp_ms,device_address,label,value_text,raw_hex")
                    logs.forEach { row ->
                        out.appendLine(
                            "${row.timestampMs},${escapeCsv(row.deviceAddress)},${escapeCsv(row.label)},${escapeCsv(row.valueText)},${escapeCsv(row.rawHex)}"
                        )
                    }
                }
                file to logs.size
            }
            withContext(Dispatchers.Main) {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(Intent.createChooser(share, "Export BleForge Session CSV").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            Result.success(count)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }
}
