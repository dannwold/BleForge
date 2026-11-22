/*
 * ==============================================================================
 * FILE: app/src/main/kotlin/com/bleforge/app/CrashRecoveryActivity.kt
 * CURRENT VERSION: v1.0.18
 * CHANGELOG:
 *   - v1.0.1: Initial isolated crash-recovery screen with restart & clipboard copy.
 *   - v1.0.18: Added full file header, KDoc, and inline comments (no functional changes).
 * HOW IT WORKS:
 *   Runs in a separate `:crash` process (declared in AndroidManifest.xml) so it
 *   can display the captured stack trace even after the main process faults,
 *   letting the user copy the trace or cold-restart MainActivity.
 * ==============================================================================
 */
package com.bleforge.app

import android.content.*
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bleforge.core.ui.*

/**
 * Fallback recovery UI launched by [GlobalCrashHandler] when an uncaught
 * exception occurs in the main BleForge process.
 */
class CrashRecoveryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Read the serialized stack trace passed via intent extras.
        val trace = intent.getStringExtra(EXTRA_STACK_TRACE) ?: "No stack trace captured."
        setContent {
            BleForgeTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = BleForgeColors.Background) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text("BLEFORGE // FAULT RECOVERY", style = MaterialTheme.typography.titleLarge, color = BleForgeColors.Error)
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Clear task stack and relaunch MainActivity in a fresh main process.
                            Button(onClick = {
                                val launch = Intent(this@CrashRecoveryActivity, MainActivity::class.java).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                                }
                                startActivity(launch)
                                finish()
                            }) { Text("Restart App") }
                            // Copy the full stack trace to the system clipboard for bug reporting.
                            OutlinedButton(onClick = {
                                getSystemService(ClipboardManager::class.java)
                                    ?.setPrimaryClip(ClipData.newPlainText("Crash Trace", trace))
                            }) { Text("Copy Trace") }
                        }
                        Spacer(Modifier.height(12.dp))
                        // Scrollable monospace stack trace viewer.
                        Text(trace, style = MaterialTheme.typography.labelMedium, color = BleForgeColors.TextSecondary, modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()))
                    }
                }
            }
        }
    }
    companion object {
        /** Intent extra key holding the formatted exception stack trace. */
        const val EXTRA_STACK_TRACE = "extra_stack_trace"
    }
}
