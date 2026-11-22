/*
 * ==============================================================================
 * FILE: app/src/main/kotlin/com/bleforge/app/UpdateChecker.kt
 * CURRENT VERSION: v1.0.21
 * CHANGELOG:
 *   - v1.0.7: Added lightweight GitHub Releases update checker using BuildConfig.GITHUB_REPO.
 *   - v1.0.18: Added full file header, KDoc, and inline comments (no functional changes).
 *   - v1.0.21: Wrapped HttpURLConnection in try/finally disconnect() and .use {}
 *              stream closing, and rethrew CancellationException.
 * HOW IT WORKS:
 *   Queries the GitHub REST API `/releases/latest` endpoint on Dispatchers.IO,
 *   parses the trailing run number from `tag_name` (e.g. `v1.0.42` -> 42),
 *   compares it against BuildConfig.VERSION_CODE, and returns the `.apk` asset URL.
 * ==============================================================================
 */
package com.bleforge.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Metadata for an available GitHub Release APK update.
 *
 * @property latestTag Release tag name (e.g. `v1.0.42`).
 * @property downloadUrl Direct `.apk` asset URL or fallback release page URL.
 */
data class AppUpdateInfo(val latestTag: String, val downloadUrl: String)

/**
 * Checks GitHub Releases for newer CI-built BleForge APKs without third-party HTTP libraries.
 */
object UpdateChecker {
    /**
     * Queries GitHub for the latest release of [BuildConfig.GITHUB_REPO].
     * Returns `null` if no repo is configured, the network fails, or the current build is up to date.
     */
    suspend fun checkForUpdate(): AppUpdateInfo? = withContext(Dispatchers.IO) {
        val repo = BuildConfig.GITHUB_REPO.trim()
        if (repo.isEmpty()) return@withContext null
        var conn: HttpURLConnection? = null
        try {
            conn = (URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                connectTimeout = 5_000
                readTimeout = 5_000
            }
            if (conn.responseCode != 200) return@withContext null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val tag = json.optString("tag_name")
            // CI tags use `v1.0.<run_number>`, matching BuildConfig.VERSION_CODE.
            val remoteRun = tag.substringAfterLast(".").toIntOrNull() ?: return@withContext null
            if (remoteRun <= BuildConfig.VERSION_CODE) return@withContext null
            val assets = json.optJSONArray("assets")
            // Prefer the direct APK asset download link; fall back to the release HTML page.
            val apkUrl = (0 until (assets?.length() ?: 0))
                .mapNotNull { assets?.optJSONObject(it)?.optString("browser_download_url") }
                .firstOrNull { it.endsWith(".apk") }
                ?: json.optString("html_url")
            AppUpdateInfo(latestTag = tag, downloadUrl = apkUrl)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}
