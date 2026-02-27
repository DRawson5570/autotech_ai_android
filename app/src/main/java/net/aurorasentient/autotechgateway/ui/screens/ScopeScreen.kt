package net.aurorasentient.autotechgateway.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.aurorasentient.autotechgateway.elm.AdapterCapabilities
import net.aurorasentient.autotechgateway.elm.ScopeSample
import net.aurorasentient.autotechgateway.ui.theme.*

/**
 * Scope data model for the UI.
 */
data class ScopeState(
    val isRunning: Boolean = false,
    val selectedPid: String = "RPM",
    val samples: List<ScopeSample> = emptyList(),
    val sampleRate: Float = 0f,          // Current samples/sec
    val currentValue: Double = 0.0,
    val minValue: Double = 0.0,
    val maxValue: Double = 0.0,
    val capabilities: AdapterCapabilities = AdapterCapabilities()
)

/** Quick-select PIDs ideal for scope viewing */
val SCOPE_PIDS = listOf(
    "RPM" to "Engine RPM",
    "MAP" to "MAP kPa",
    "THROTTLE_POS" to "Throttle %",
    "MAF" to "MAF g/s",
    "SPEED" to "Speed km/h",
    "IAT" to "Intake Air °C",
    "COOLANT_TEMP" to "Coolant °C",
    "STFT_B1" to "STFT B1 %",
    "LTFT_B1" to "LTFT B1 %",
    "TIMING_ADV" to "Timing °",
    "LOAD" to "Engine Load %",
    "CTRL_VOLTAGE" to "Voltage V"
)

/** Time window options for scope display */
val TIME_WINDOWS = listOf(
    5_000L to "5s",
    10_000L to "10s",
    30_000L to "30s",
    60_000L to "1m",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScopeScreen(
    scopeState: ScopeState,
    onSelectPid: (String) -> Unit,
    onStartScope: () -> Unit,
    onStopScope: () -> Unit
) {
    var timeWindowMs by remember { mutableStateOf(10_000L) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(12.dp)
    ) {
        // ── Adapter Info Banner ──────────────────────────────────
        if (scopeState.capabilities.isSTN) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = StatusGreen.copy(alpha = 0.15f)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Speed, contentDescription = null, tint = StatusGreen, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "${scopeState.capabilities.deviceName} — High-speed mode" +
                                if (scopeState.capabilities.supportsSTPX) " (STPX)" else "",
                        color = StatusGreen,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // ── PID Selector ────────────────────────────────────────
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(SCOPE_PIDS) { (pid, label) ->
                FilterChip(
                    selected = scopeState.selectedPid == pid,
                    onClick = {
                        if (!scopeState.isRunning) onSelectPid(pid)
                    },
                    label = { Text(label, fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AutotechBlue,
                        selectedLabelColor = TextOnPrimary,
                        containerColor = DarkSurfaceVariant,
                        labelColor = TextSecondary
                    ),
                    modifier = Modifier.height(32.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // ── Scope Chart ─────────────────────────────────────────
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0A0A0A)),
            shape = RoundedCornerShape(8.dp)
        ) {
            Box(modifier = Modifier.fillMaxSize().padding(4.dp)) {
                ScopeChart(
                    samples = scopeState.samples,
                    timeWindowMs = timeWindowMs,
                    pidName = scopeState.selectedPid,
                    lineColor = ScopeGreen,
                    modifier = Modifier.fillMaxSize()
                )

                // Grid lines label overlay
                if (scopeState.samples.isNotEmpty()) {
                    // Current value display (top-right)
                    Text(
                        text = "%.1f".format(scopeState.currentValue),
                        color = ScopeGreen,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // ── Stats Bar ───────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            StatChip("MIN", "%.1f".format(scopeState.minValue), AutotechBlueLight)
            StatChip("CUR", "%.1f".format(scopeState.currentValue), ScopeGreen)
            StatChip("MAX", "%.1f".format(scopeState.maxValue), StatusRed)
            StatChip("Hz", "%.0f".format(scopeState.sampleRate), StatusYellow)
        }

        Spacer(modifier = Modifier.height(8.dp))

        // ── Time Window Selector ────────────────────────────────
        @OptIn(ExperimentalMaterial3Api::class)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            for ((ms, label) in TIME_WINDOWS) {
                FilterChip(
                    selected = timeWindowMs == ms,
                    onClick = { timeWindowMs = ms },
                    label = { Text(label, fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = DarkSurfaceVariant,
                        selectedLabelColor = AutotechBlue,
                        containerColor = DarkSurface,
                        labelColor = TextSecondary
                    ),
                    modifier = Modifier.height(28.dp)
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // Start / Stop button
            Button(
                onClick = { if (scopeState.isRunning) onStopScope() else onStartScope() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (scopeState.isRunning) StatusRed else StatusGreen
                ),
                modifier = Modifier.height(36.dp),
                contentPadding = PaddingValues(horizontal = 20.dp)
            ) {
                Icon(
                    if (scopeState.isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    if (scopeState.isRunning) "Stop" else "Start",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun StatChip(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Medium)
        Text(value, color = color, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}

// ─────────────────────────────────────────────────────────────────────
//  Canvas-based real-time scope chart
// ─────────────────────────────────────────────────────────────────────

@Composable
fun ScopeChart(
    samples: List<ScopeSample>,
    timeWindowMs: Long,
    pidName: String,
    lineColor: Color,
    modifier: Modifier = Modifier
) {
    val gridColor = Color(0xFF1A3A1A)
    val gridTextColor = Color(0xFF3A7A3A)

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val padding = 40f

        val chartLeft = padding
        val chartRight = w - 10f
        val chartTop = 10f
        val chartBottom = h - padding
        val chartW = chartRight - chartLeft
        val chartH = chartBottom - chartTop

        if (samples.isEmpty()) {
            // Draw empty grid
            drawScopeGrid(chartLeft, chartTop, chartW, chartH, gridColor, 0.0, 100.0, gridTextColor)
            return@Canvas
        }

        // Time window
        val now = samples.last().timestampMs
        val windowStart = now - timeWindowMs
        val visible = samples.filter { it.timestampMs >= windowStart }
        if (visible.isEmpty()) return@Canvas

        // Auto-range Y axis
        val minVal = visible.minOf { it.value }
        val maxVal = visible.maxOf { it.value }
        val margin = ((maxVal - minVal) * 0.1).coerceAtLeast(1.0)
        val yMin = minVal - margin
        val yMax = maxVal + margin
        val yRange = yMax - yMin

        // Draw grid
        drawScopeGrid(chartLeft, chartTop, chartW, chartH, gridColor, yMin, yMax, gridTextColor)

        // Draw waveform
        val path = Path()
        var first = true
        for (sample in visible) {
            val x = chartLeft + ((sample.timestampMs - windowStart).toFloat() / timeWindowMs) * chartW
            val y = chartBottom - ((sample.value - yMin).toFloat() / yRange.toFloat()) * chartH

            if (first) {
                path.moveTo(x, y)
                first = false
            } else {
                path.lineTo(x, y)
            }
        }

        drawPath(
            path = path,
            color = lineColor,
            style = Stroke(width = 2.5f)
        )

        // Draw glow effect (wider, semi-transparent)
        drawPath(
            path = path,
            color = lineColor.copy(alpha = 0.2f),
            style = Stroke(width = 8f)
        )
    }
}

private fun DrawScope.drawScopeGrid(
    left: Float, top: Float, width: Float, height: Float,
    gridColor: Color, yMin: Double, yMax: Double, textColor: Color
) {
    val hLines = 5
    val vLines = 8

    // Horizontal grid lines
    for (i in 0..hLines) {
        val y = top + (height / hLines) * i
        drawLine(
            color = gridColor,
            start = Offset(left, y),
            end = Offset(left + width, y),
            strokeWidth = 1f
        )

        // Y-axis labels
        val value = yMax - ((yMax - yMin) / hLines) * i
        drawContext.canvas.nativeCanvas.drawText(
            "%.0f".format(value),
            2f,
            y + 4f,
            android.graphics.Paint().apply {
                color = textColor.hashCode()
                textSize = 22f
                isAntiAlias = true
            }
        )
    }

    // Vertical grid lines
    for (i in 0..vLines) {
        val x = left + (width / vLines) * i
        drawLine(
            color = gridColor,
            start = Offset(x, top),
            end = Offset(x, top + height),
            strokeWidth = 1f
        )
    }

    // Border
    drawRect(
        color = gridColor.copy(alpha = 0.8f),
        topLeft = Offset(left, top),
        size = androidx.compose.ui.geometry.Size(width, height),
        style = Stroke(width = 1.5f)
    )
}

// Scope-specific colors
val ScopeGreen = Color(0xFF00FF41)     // Classic oscilloscope green
val ScopeAmber = Color(0xFFFFAA00)     // Amber trace option
val ScopeCyan = Color(0xFF00E5FF)      // Cyan trace option
