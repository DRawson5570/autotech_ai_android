package net.aurorasentient.autotechgateway.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.aurorasentient.autotechgateway.elm.PIDRegistry
import net.aurorasentient.autotechgateway.ui.theme.*

/**
 * Diagnostics screen — live PIDs and DTC management.
 */

// --- DTC Tab ---

data class DTCItem(
    val code: String,
    val description: String,
    val type: String  // "stored", "pending", "permanent"
)

@Composable
fun DiagnosticsScreen(
    livePids: Map<String, String>,
    dtcs: List<DTCItem>,
    isReading: Boolean,
    onReadDtcs: () -> Unit,
    onClearDtcs: () -> Unit,
    onReadLivePids: () -> Unit,
    onRefreshPids: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Live Data", "DTCs")

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = DarkSurface,
            contentColor = AutotechBlue
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = {
                        Text(
                            title,
                            color = if (selectedTab == index) AutotechBlue else TextSecondary
                        )
                    }
                )
            }
        }

        when (selectedTab) {
            0 -> LiveDataTab(livePids, onReadLivePids, onRefreshPids, isReading)
            1 -> DTCTab(dtcs, onReadDtcs, onClearDtcs, isReading)
        }
    }
}

@Composable
private fun LiveDataTab(
    livePids: Map<String, String>,
    onReadLivePids: () -> Unit,
    onRefreshPids: () -> Unit,
    isReading: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onReadLivePids,
                enabled = !isReading,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = AutotechBlue)
            ) {
                if (isReading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = TextPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Reading...")
                } else {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Read PIDs")
                }
            }

            if (livePids.isNotEmpty()) {
                OutlinedButton(
                    onClick = onRefreshPids,
                    enabled = !isReading,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Refresh")
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (livePids.isEmpty() && !isReading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Speed,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Tap Read PIDs to capture live data",
                        color = TextSecondary,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(livePids.entries.toList()) { (name, value) ->
                    PidCard(name, value)
                }
            }
        }
    }
}

@Composable
private fun PidCard(name: String, value: String) {
    // Parse value and unit
    val parts = value.split(" ", limit = 2)
    val numericVal = parts.getOrNull(0) ?: value
    val unit = parts.getOrNull(1) ?: ""

    // Choose gauge color based on PID
    val gaugeColor = when {
        name.contains("COOLANT", ignoreCase = true) -> {
            val temp = numericVal.toDoubleOrNull()
            when {
                temp == null -> GaugeBlue
                temp < 60 -> GaugeBlue
                temp < 100 -> GaugeGreen
                temp < 110 -> GaugeOrange
                else -> GaugeRed
            }
        }
        name.contains("RPM", ignoreCase = true) -> {
            val rpm = numericVal.toDoubleOrNull()
            when {
                rpm == null -> GaugeGreen
                rpm < 3000 -> GaugeGreen
                rpm < 5000 -> GaugeOrange
                else -> GaugeRed
            }
        }
        name.contains("VOLTAGE", ignoreCase = true) || name.contains("BATTERY", ignoreCase = true) -> {
            val volts = numericVal.toDoubleOrNull()
            when {
                volts == null -> GaugeGreen
                volts < 11.8 -> GaugeRed
                volts < 12.4 -> GaugeOrange
                else -> GaugeGreen
            }
        }
        else -> AutotechBlue
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = name.replace("_", " "),
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                maxLines = 2
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = numericVal,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = gaugeColor
            )
            if (unit.isNotEmpty()) {
                Text(
                    text = unit,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
        }
    }
}

@Composable
private fun DTCTab(
    dtcs: List<DTCItem>,
    onReadDtcs: () -> Unit,
    onClearDtcs: () -> Unit,
    isReading: Boolean
) {
    var showClearConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onReadDtcs,
                enabled = !isReading,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = AutotechBlue)
            ) {
                if (isReading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = TextPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Reading...")
                } else {
                    Icon(Icons.Default.BugReport, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Read DTCs")
                }
            }

            if (dtcs.isNotEmpty()) {
                Button(
                    onClick = { showClearConfirm = true },
                    enabled = !isReading,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = StatusRed)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Clear")
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // DTC count summary
        if (dtcs.isNotEmpty()) {
            val stored = dtcs.count { it.type == "stored" }
            val pending = dtcs.count { it.type == "pending" }
            val permanent = dtcs.count { it.type == "permanent" }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurface)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    DTCCountBadge("Stored", stored, DTCStored)
                    DTCCountBadge("Pending", pending, DTCPending)
                    DTCCountBadge("Permanent", permanent, DTCPermanent)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (dtcs.isEmpty() && !isReading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = StatusGreen
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("No DTCs found", color = TextSecondary)
                    Text(
                        "Tap Read DTCs to check for trouble codes",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(dtcs) { dtc ->
                    DTCCard(dtc)
                }
            }
        }
    }

    // Clear confirmation dialog
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear DTCs?") },
            text = {
                Text(
                    "This will reset the check engine light and clear all stored " +
                    "diagnostic trouble codes. Pending and permanent codes may reappear " +
                    "if the underlying issue is not resolved."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirm = false
                        onClearDtcs()
                    }
                ) {
                    Text("Clear", color = StatusRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("Cancel")
                }
            },
            containerColor = DarkSurfaceVariant
        )
    }
}

@Composable
private fun DTCCountBadge(
    label: String,
    count: Int,
    color: androidx.compose.ui.graphics.Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = if (count > 0) color else TextSecondary
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary
        )
    }
}

@Composable
private fun DTCCard(dtc: DTCItem) {
    val typeColor = when (dtc.type) {
        "stored" -> DTCStored
        "pending" -> DTCPending
        "permanent" -> DTCPermanent
        else -> TextSecondary
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Type indicator
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(typeColor)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = dtc.code,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = dtc.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }

            Surface(
                color = typeColor.copy(alpha = 0.15f),
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    text = dtc.type.uppercase(),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = typeColor,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
