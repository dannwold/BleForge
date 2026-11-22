/*
 * ==============================================================================
 * FILE: core/ui/src/main/kotlin/com/bleforge/core/ui/ErrorBanner.kt
 * CURRENT VERSION: v1.0.19
 * CHANGELOG:
 *   - v1.0.1: Initial animated non-fatal error banner for BLE/database/permission faults.
 *   - v1.0.18: Added full file header, KDoc, and inline comments (no functional changes).
 *   - v1.0.19: Added maxLines/ellipsis overflow guards so long error text never clips DISMISS.
 * HOW IT WORKS:
 *   Observes an optional AppError? state, animates in/out with fade transitions,
 *   and renders a high-contrast tactical alert surface with a DISMISS action.
 * ==============================================================================
 */
package com.bleforge.core.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bleforge.core.model.AppError

/**
 * Dismissible top-of-screen banner displaying recoverable errors from [GlobalErrorBus].
 *
 * @param error Current [AppError] to display, or `null` when hidden.
 * @param onDismiss Callback invoked when the user taps `DISMISS`.
 * @param modifier Optional layout [Modifier].
 */
@Composable
fun ErrorBanner(
    error: AppError?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = error != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        if (error != null) {
            Surface(
                color = BleForgeColors.Error.copy(alpha = 0.14f),
                border = BorderStroke(1.dp, BleForgeColors.Error.copy(alpha = 0.6f)),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = error.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = BleForgeColors.Error,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = error.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = BleForgeColors.TextPrimary,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    TextButton(onClick = onDismiss) {
                        Text("DISMISS", color = BleForgeColors.TextPrimary, maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
}
