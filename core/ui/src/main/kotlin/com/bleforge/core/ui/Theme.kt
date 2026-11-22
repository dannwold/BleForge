/*
 * ==============================================================================
 * FILE: core/ui/src/main/kotlin/com/bleforge/core/ui/Theme.kt
 * CURRENT VERSION: v1.0.18
 * CHANGELOG:
 *   - v1.0.1: Initial tactical dark Material 3 palette and monospace-paired typography.
 *   - v1.0.18: Added full file header, KDoc, and inline comments (no functional changes).
 * HOW IT WORKS:
 *   Defines BleForgeColors (high-contrast slate surfaces + cyan/green/amber/red
 *   telemetry accents) and wraps MaterialTheme with TacticalDarkScheme and
 *   TacticalTypography for consistent HUD styling across all feature modules.
 * ==============================================================================
 */
package com.bleforge.core.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Central color tokens for BleForge's tactical dark diagnostic HUD.
 */
object BleForgeColors {
    val Background = Color(0xFF0B0F14)
    val Surface = Color(0xFF131A22)
    val SurfaceElevated = Color(0xFF1C2530)
    val Primary = Color(0xFF38BDF8)      // Cyan telemetry accent
    val Secondary = Color(0xFF22C55E)    // Signal / Ready green
    val Warning = Color(0xFFF59E0B)      // Throttled / Weak RSSI
    val Error = Color(0xFFEF4444)        // GATT / Crash alert
    val TextPrimary = Color(0xFFF1F5F9)
    val TextSecondary = Color(0xFF94A3B8)
    val Border = Color(0xFF263241)
}

/** Material 3 dark color scheme mapped to [BleForgeColors]. */
private val TacticalDarkScheme = darkColorScheme(
    primary = BleForgeColors.Primary,
    secondary = BleForgeColors.Secondary,
    background = BleForgeColors.Background,
    surface = BleForgeColors.Surface,
    surfaceVariant = BleForgeColors.SurfaceElevated,
    error = BleForgeColors.Error,
    onPrimary = Color(0xFF06131F),
    onBackground = BleForgeColors.TextPrimary,
    onSurface = BleForgeColors.TextPrimary,
    onSurfaceVariant = BleForgeColors.TextSecondary
)

/** Compact, data-dense typography pairing sans-serif headers with monospace telemetry labels. */
private val TacticalTypography = Typography(
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        letterSpacing = 0.2.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 13.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp
    )
)

/**
 * Root Material 3 theme wrapper for all BleForge screens and recovery activities.
 */
@Composable
fun BleForgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TacticalDarkScheme,
        typography = TacticalTypography,
        content = content
    )
}
