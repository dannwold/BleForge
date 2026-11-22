/*
 * ==============================================================================
 * FILE: core/ui/src/main/kotlin/com/bleforge/core/ui/TacticalComponents.kt
 * CURRENT VERSION: v1.0.19
 * CHANGELOG:
 *   - v1.0.2: Initial custom Canvas TacticalArcGauge, LiveSparkline, HueControlStrip, ByteDiffGrid.
 *   - v1.0.4: Added SignalBars RSSI strength indicator.
 *   - v1.0.18: Added full file header, KDoc, and inline comments (no functional changes).
 *   - v1.0.19: Added maxLines/ellipsis guards on TacticalArcGauge labels for narrow/resized screens.
 * HOW IT WORKS:
 *   Provides zero-dependency Jetpack Compose Canvas widgets for live telemetry
 *   visualization (arc gauges, sparklines, RSSI bars), interactive HSV hue
 *   scrubbing, and byte-level packet diff highlighting.
 * ==============================================================================
 */
package com.bleforge.core.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * 270-degree animated Canvas arc gauge with tabular monospace readout.
 *
 * @param label Short metric label rendered below the dial.
 * @param value Current numeric reading.
 * @param maxValue Upper bound used to normalize the arc sweep (`0f..1f`).
 * @param unit Unit suffix (e.g. `%`, `dBm`, `BPM`).
 * @param accent Primary sweep and label color.
 */
@Composable
fun TacticalArcGauge(
    label: String,
    value: Float,
    maxValue: Float,
    unit: String,
    accent: Color = BleForgeColors.Primary,
    modifier: Modifier = Modifier
) {
    // Smoothly animate needle sweep when telemetry updates arrive.
    val animated by animateFloatAsState(
        targetValue = (value / maxValue).coerceIn(0f, 1f),
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "gaugeSweep"
    )
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(BleForgeColors.Surface)
            .border(1.dp, BleForgeColors.Border, RoundedCornerShape(10.dp))
            .padding(10.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(modifier = Modifier.size(84.dp), contentAlignment = Alignment.Center) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val stroke = Stroke(width = 7.dp.toPx(), cap = StrokeCap.Round)
                    val pad = 6.dp.toPx()
                    val arcSize = Size(size.width - pad * 2, size.height - pad * 2)
                    // Background track arc (135° to 405°).
                    drawArc(
                        color = BleForgeColors.SurfaceElevated,
                        startAngle = 135f,
                        sweepAngle = 270f,
                        useCenter = false,
                        topLeft = Offset(pad, pad),
                        size = arcSize,
                        style = stroke
                    )
                    // Active gradient sweep proportional to normalized value.
                    drawArc(
                        brush = Brush.sweepGradient(listOf(accent.copy(alpha = 0.5f), accent)),
                        startAngle = 135f,
                        sweepAngle = 270f * animated,
                        useCenter = false,
                        topLeft = Offset(pad, pad),
                        size = arcSize,
                        style = stroke
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = value.roundToInt().toString(),
                        maxLines = 1,
                        softWrap = false,
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            fontFeatureSettings = "tnum", // Tabular numerals prevent jitter on live updates.
                            color = BleForgeColors.TextPrimary
                        )
                    )
                    Text(unit, style = MaterialTheme.typography.labelMedium, color = BleForgeColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * Real-time Canvas sparkline chart with vertical gradient fill and trailing point marker.
 *
 * @param samples Ordered numeric samples (requires at least 2 points to render).
 * @param accent Line and fill tint color.
 */
@Composable
fun LiveSparkline(
    samples: List<Float>,
    accent: Color = BleForgeColors.Secondary,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(BleForgeColors.Background)
            .border(1.dp, BleForgeColors.Border, RoundedCornerShape(8.dp))
            .padding(6.dp)
    ) {
        if (samples.size < 2) return@Canvas
        val min = samples.minOrNull() ?: 0f
        // Guard against flat-line division by zero when all samples are identical.
        val max = (samples.maxOrNull() ?: 1f).let { if (it == min) min + 1f else it }
        val stepX = size.width / (samples.size - 1).coerceAtLeast(1)

        val linePath = Path()
        val fillPath = Path()
        samples.forEachIndexed { idx, v ->
            val x = idx * stepX
            val y = size.height - ((v - min) / (max - min)) * size.height
            if (idx == 0) {
                linePath.moveTo(x, y)
                fillPath.moveTo(x, size.height)
                fillPath.lineTo(x, y)
            } else {
                linePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }
        fillPath.lineTo(size.width, size.height)
        fillPath.close()

        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(listOf(accent.copy(alpha = 0.28f), Color.Transparent))
        )
        drawPath(path = linePath, color = accent, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        val lastY = size.height - ((samples.last() - min) / (max - min)) * size.height
        drawCircle(color = accent, radius = 3.5.dp.toPx(), center = Offset(size.width, lastY))
    }
}

/**
 * Four-bar RSSI signal strength indicator colored by link quality thresholds.
 *
 * @param rssi Received signal strength in dBm (e.g. `-52`).
 */
@Composable
fun SignalBars(rssi: Int, modifier: Modifier = Modifier) {
    val level = when {
        rssi >= -55 -> 4
        rssi >= -70 -> 3
        rssi >= -85 -> 2
        else -> 1
    }
    val activeColor = when (level) {
        4, 3 -> BleForgeColors.Secondary
        2 -> BleForgeColors.Warning
        else -> BleForgeColors.Error
    }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        for (bar in 1..4) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height((bar * 4 + 4).dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(if (bar <= level) activeColor else BleForgeColors.SurfaceElevated)
            )
        }
    }
}

/**
 * Interactive horizontal HSV hue scrubber for RGB/smart-lighting GATT control.
 * Supports both tap-to-jump and continuous horizontal drag gestures.
 *
 * @param hueFraction Normalized hue position (`0f..1f`).
 * @param onHueChange Callback invoked with the updated normalized hue.
 */
@Composable
fun HueControlStrip(
    hueFraction: Float,
    onHueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val rainbow = remember {
        (0..6).map { Color.hsv(it * 60f, 0.9f, 1f) }
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp)
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, BleForgeColors.Border, RoundedCornerShape(6.dp))
            .pointerInput(Unit) {
                detectTapGestures { offset -> onHueChange((offset.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    onHueChange((change.position.x / size.width).coerceIn(0f, 1f))
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(brush = Brush.horizontalGradient(rainbow))
            val thumbX = hueFraction.coerceIn(0f, 1f) * size.width
            drawCircle(color = Color.White, radius = 9.dp.toPx(), center = Offset(thumbX, size.height / 2f), style = Stroke(2.5.dp.toPx()))
        }
    }
}

/**
 * 8-byte-per-row hex dump grid that highlights bytes that changed since [previous].
 *
 * @param current Latest received or transmitted payload.
 * @param previous Prior payload for byte-level diff highlighting, or `null`.
 */
@Composable
fun ByteDiffGrid(
    current: ByteArray,
    previous: ByteArray?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(BleForgeColors.Background)
            .border(1.dp, BleForgeColors.Border, RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (current.isEmpty()) {
            Text("NO PACKET CAPTURED", style = MaterialTheme.typography.labelMedium, color = BleForgeColors.TextSecondary)
        } else {
            val rows = current.toList().chunked(8)
            rows.forEachIndexed { rowIdx, rowBytes ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Row offset prefix (00:, 08:, 10:, ...).
                    Text(
                        text = "%02X:".format(rowIdx * 8),
                        style = MaterialTheme.typography.labelMedium,
                        color = BleForgeColors.TextSecondary
                    )
                    rowBytes.forEachIndexed { colIdx, b ->
                        val absIdx = rowIdx * 8 + colIdx
                        val changed = previous != null && absIdx < previous.size && previous[absIdx] != b
                        Text(
                            text = "%02X".format(b),
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = if (changed) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 12.sp,
                                color = if (changed) BleForgeColors.Primary else BleForgeColors.TextPrimary
                            )
                        )
                    }
                }
            }
        }
    }
}
