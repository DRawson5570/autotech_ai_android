package net.aurorasentient.autotechgateway.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import net.aurorasentient.autotechgateway.ui.AppSettings
import net.aurorasentient.autotechgateway.ui.theme.*

/**
 * Settings screen — shop config, connection preferences, about.
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onUpdateShopId: (String) -> Unit,
    onUpdateApiKey: (String) -> Unit,
    onUpdateServerUrl: (String) -> Unit,
    onUpdateWifiHost: (String) -> Unit,
    onUpdateWifiPort: (Int) -> Unit,
    onUpdateAutoConnect: (Boolean) -> Unit,
    onUpdateAutoTunnel: (Boolean) -> Unit,
    appVersion: String
) {
    var editingShopId by remember(settings.shopId) { mutableStateOf(settings.shopId) }
    var editingApiKey by remember(settings.apiKey) { mutableStateOf(settings.apiKey) }
    var editingServerUrl by remember(settings.serverUrl) { mutableStateOf(settings.serverUrl) }
    var editingWifiHost by remember(settings.wifiHost) { mutableStateOf(settings.wifiHost) }
    var editingWifiPort by remember(settings.wifiPort) { mutableStateOf(settings.wifiPort) }
    var showApiKey by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Shop Configuration
        SectionCard("Shop Configuration", Icons.Default.Store) {
            SettingsTextField(
                label = "Shop ID",
                value = editingShopId,
                onValueChange = { editingShopId = it },
                onDone = { onUpdateShopId(editingShopId) },
                placeholder = "e.g. andersons"
            )

            Spacer(modifier = Modifier.height(8.dp))

            SettingsTextField(
                label = "API Key",
                value = editingApiKey,
                onValueChange = { editingApiKey = it },
                onDone = { onUpdateApiKey(editingApiKey) },
                placeholder = "Your gateway API key",
                visualTransformation = if (showApiKey) VisualTransformation.None
                    else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showApiKey = !showApiKey }) {
                        Icon(
                            if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = "Toggle visibility",
                            tint = TextSecondary
                        )
                    }
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            SettingsTextField(
                label = "Server URL",
                value = editingServerUrl,
                onValueChange = { editingServerUrl = it },
                onDone = { onUpdateServerUrl(editingServerUrl) },
                placeholder = "wss://automotive.aurora-sentient.net"
            )
        }

        // WiFi Adapter Settings
        SectionCard("WiFi Adapter", Icons.Default.Wifi) {
            SettingsTextField(
                label = "Host",
                value = editingWifiHost,
                onValueChange = { editingWifiHost = it },
                onDone = { onUpdateWifiHost(editingWifiHost) },
                placeholder = "192.168.0.10"
            )

            Spacer(modifier = Modifier.height(8.dp))

            SettingsTextField(
                label = "Port",
                value = editingWifiPort,
                onValueChange = { editingWifiPort = it.filter { c -> c.isDigit() } },
                onDone = { editingWifiPort.toIntOrNull()?.let { onUpdateWifiPort(it) } },
                placeholder = "35000"
            )
        }

        // Behavior
        SectionCard("Behavior", Icons.Default.Settings) {
            SettingsToggle(
                label = "Auto-connect",
                description = "Automatically connect to last adapter on startup",
                checked = settings.autoConnect,
                onCheckedChange = onUpdateAutoConnect
            )

            HorizontalDivider(color = DarkSurface, modifier = Modifier.padding(vertical = 4.dp))

            SettingsToggle(
                label = "Auto-tunnel",
                description = "Automatically start server tunnel after connecting",
                checked = settings.autoTunnel,
                onCheckedChange = onUpdateAutoTunnel
            )
        }

        // About
        SectionCard("About", Icons.Default.Info) {
            InfoRow2("App Version", appVersion)
            InfoRow2("Platform", "Android")
            InfoRow2("Protocol", "ELM327 / OBD-II / UDS")
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun SectionCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable ColumnScope.() -> Unit
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
                Icon(icon, contentDescription = null, tint = AutotechBlue)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun SettingsTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onDone: () -> Unit,
    placeholder: String,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: @Composable (() -> Unit)? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text(placeholder, color = TextSecondary.copy(alpha = 0.5f)) },
        visualTransformation = visualTransformation,
        trailingIcon = trailingIcon,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = AutotechBlue,
            unfocusedBorderColor = TextSecondary.copy(alpha = 0.3f),
            focusedLabelColor = AutotechBlue,
            unfocusedLabelColor = TextSecondary,
            cursorColor = AutotechBlue,
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary
        )
    )
    // Save when focus is lost — for simplicity, save on each change
    LaunchedEffect(value) {
        kotlinx.coroutines.delay(500) // debounce
        onDone()
    }
}

@Composable
private fun SettingsToggle(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = TextPrimary, fontWeight = FontWeight.Medium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = AutotechBlue,
                checkedTrackColor = AutotechBlue.copy(alpha = 0.3f),
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = DarkSurface
            )
        )
    }
}

@Composable
private fun InfoRow2(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
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
