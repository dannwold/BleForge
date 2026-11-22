/*
 * ==============================================================================
 * FILE: feature/bluetooth/src/main/kotlin/com/bleforge/feature/bluetooth/BleForegroundService.kt
 * CURRENT VERSION: v1.0.20
 * CHANGELOG:
 *   - v1.0.1: Initial connectedDevice foreground service for persistent BLE links.
 *   - v1.0.18: Added full file header, KDoc, and inline comments (no functional changes).
 *   - v1.0.20: Added tap-to-return PendingIntent on foreground notification and
 *              BLUETOOTH_ADVERTISE fallback check for peripheral server mode.
 * HOW IT WORKS:
 *   Verifies BLUETOOTH_CONNECT (or BLUETOOTH_ADVERTISE) permission before promoting
 *   itself to a foreground service with FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
 *   so active GATT links, heartbeats, and virtual peripheral servers survive
 *   backgrounding and OEM battery savers.
 * ==============================================================================
 */
package com.bleforge.feature.bluetooth

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Foreground service keeping BLE telemetry, notifications, virtual peripheral
 * advertising, and heartbeats alive when BleForge is backgrounded.
 */
class BleForegroundService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android 14+ throws SecurityException if a connectedDevice FGS starts without BT permissions.
        val hasConnect = checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        val hasAdvertise = checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
        if (!hasConnect && !hasAdvertise) {
            stopSelf()
            return START_NOT_STICKY
        }
        val channelId = "bleforge_connection"
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(channelId, "BLE Active Connection", NotificationManager.IMPORTANCE_LOW)
        )
        // Tapping the persistent notification brings the user back to BleForge's launcher activity.
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("BleForge Active Link")
            .setContentText("Maintaining BLE telemetry, heartbeat & GATT server")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
        startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
