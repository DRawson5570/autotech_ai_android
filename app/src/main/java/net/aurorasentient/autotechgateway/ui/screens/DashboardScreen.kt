package net.aurorasentient.autotechgateway.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.aurorasentient.autotechgateway.elm.ConnectionType
import net.aurorasentient.autotechgateway.elm.DetectedAdapter
import net.aurorasentient.autotechgateway.service.GatewayState
import net.aurorasentient.autotechgateway.service.GatewayStatus
import net.aurorasentient.autotechgateway.ui.theme.*

/**
 * Dashboard / Home screen — shows connection status, vehicle info, quick actions.
 */
@Composable
fun DashboardScreen(
    status: GatewayStatus,
    adapters: List<DetectedAdapter>,
    onScanAdapters: () -> Unit,
    onConnect: (DetectedAdapter) -> Unit,
    onDisconnect: () -> Unit,
    onStartTunnel: () -> Unit,
    onStopTunnel: () -> Unit,
    onNavigateToDiagnostics: () -> Unit,
    onNavigateToModules: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Status Card
        item {
            StatusCard(status)
        }

        // Vehicle Info (when connected)
        if (status.state.ordinal >= GatewayState.CONNECTED.ordinal) {
            item {
                VehicleInfoCard(status)
            }

            // Quick Actions
            item {
                QuickActionsCard(
                    status = status,
                    onStartTunnel = onStartTunnel,
                    onStopTunnel = onStopTunnel,
                    onNavigateToDiagnostics = onNavigateToDiagnostics,
                    onNavigateToModules = onNavigateToModules,
                    onDisconnect = onDisconnect
                )
            }
        }

        // Connection section (when disconnected)
        if (status.state == GatewayState.DISCONNECTED || status.state == GatewayState.ERROR) {
            item {
                AdapterSelectionCard(
                    adapters = adapters,
                    onScan = onScanAdapters,
                    onConnect = onConnect
                )
            }
        }
    }
}

@Composable
private fun StatusCard(status: GatewayStatus) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Status indicator dot
            val (color, label) = when (status.state) {
                GatewayState.DISCONNECTED -> Pair(StatusRed, "Disconnected")
                GatewayState.CONNECTING -> Pair(StatusYellow, "Connecting...")
                GatewayState.CONNECTED -> Pair(StatusGreen, "Connected")
                GatewayState.TUNNEL_CONNECTING -> Pair(StatusYellow, "Tunnel connecting...")
                GatewayState.TUNNEL_ACTIVE -> Pair(AutotechBlue, "Tunnel Active")
                GatewayState.ERROR -> Pair(StatusRed, "Error")
            }

            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(color)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                if (status.adapterName.isNotEmpty()) {
                    Text(
                        text = "${status.adapterName} ${status.adapterVersion}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
                if (status.errorMessage != null) {
                    Text(
                        text = status.errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusRed
                    )
                }
            }

            // Battery voltage
            if (status.batteryVoltage != null) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "%.1fV".format(status.batteryVoltage),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            status.batteryVoltage < 11.8 -> StatusRed
                            status.batteryVoltage < 12.4 -> StatusYellow
                            else -> StatusGreen
                        }
                    )
                    Text(
                        text = "Battery",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun VehicleInfoCard(status: GatewayStatus) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.DirectionsCar, contentDescription = null, tint = AutotechBlue)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Vehicle",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            val vinInfo = status.vehicleInfo
            if (vinInfo != null) {
                InfoRow("Year", vinInfo.year?.toString() ?: "Unknown")
                InfoRow("Make", vinInfo.make ?: "Unknown")
                InfoRow("VIN", status.vin ?: "Unknown")
            } else if (status.vin != null) {
                InfoRow("VIN", status.vin)
            } else {
                Text("No VIN detected", color = TextSecondary)
            }

            Spacer(modifier = Modifier.height(8.dp))
            InfoRow("Supported PIDs", status.supportedPidCount.toString())
        }
    }
}

@Composable
private fun QuickActionsCard(
    status: GatewayStatus,
    onStartTunnel: () -> Unit,
    onStopTunnel: () -> Unit,
    onNavigateToDiagnostics: () -> Unit,
    onNavigateToModules: () -> Unit,
    onDisconnect: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Quick Actions",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Tunnel toggle
            if (status.tunnelRegistered) {
                ActionButton("Stop Server Tunnel", Icons.Default.CloudOff, StatusOrange) { onStopTunnel() }
            } else {
                ActionButton("Start Server Tunnel", Icons.Default.Cloud, AutotechBlue) { onStartTunnel() }
            }

            ActionButton("Diagnostics (DTCs + PIDs)", Icons.Default.BugReport, StatusGreen) { onNavigateToDiagnostics() }
            ActionButton("Module Scan", Icons.Default.Memory, AutotechBlueLight) { onNavigateToModules() }
            ActionButton("Disconnect", Icons.Default.LinkOff, StatusRed) { onDisconnect() }
        }
    }
}

@Composable
private fun ActionButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color),
        shape = RoundedCornerShape(8.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(text)
    }
}

@Composable
private fun AdapterSelectionCard(
    adapters: List<DetectedAdapter>,
    onScan: () -> Unit,
    onConnect: (DetectedAdapter) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Bluetooth, contentDescription = null, tint = AutotechBlue)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Connect to Adapter",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onScan,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AutotechBlue)
            ) {
                Icon(Icons.Default.Search, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Scan for Adapters")
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (adapters.isEmpty()) {
                Text(
                    text = "Pair your OBD adapter in Bluetooth settings first, then tap Scan.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            } else {
                for (adapter in adapters) {
                    AdapterItem(adapter) { onConnect(adapter) }
                }
            }
        }
    }
}

@Composable
private fun AdapterItem(adapter: DetectedAdapter, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = DarkSurface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val icon = when (adapter.type) {
                ConnectionType.BLUETOOTH_CLASSIC -> Icons.Default.Bluetooth
                ConnectionType.BLUETOOTH_LE -> Icons.Default.BluetoothSearching
                ConnectionType.WIFI -> Icons.Default.Wifi
            }
            Icon(icon, contentDescription = null, tint = AutotechBlue)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(adapter.name, fontWeight = FontWeight.Medium, color = TextPrimary)
                Text(
                    text = "${adapter.type.name} | ${adapter.address}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = TextSecondary)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            color = TextPrimary,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}
