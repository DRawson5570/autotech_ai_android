package net.aurorasentient.autotechgateway.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.aurorasentient.autotechgateway.ui.theme.*

/**
 * Module discovery screen — scan for ECUs, read DIDs.
 */

data class ModuleItem(
    val name: String,
    val address: String,
    val bus: String,
    val protocol: String = ""
)

@Composable
fun ModulesScreen(
    modules: List<ModuleItem>,
    isScanning: Boolean,
    onDiscoverModules: () -> Unit,
    onReadDid: (moduleAddress: String, did: String, bus: String) -> Unit
) {
    var selectedModule by remember { mutableStateOf<ModuleItem?>(null) }
    var didInput by remember { mutableStateOf("") }
    var didResults by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Scan button
        Button(
            onClick = onDiscoverModules,
            enabled = !isScanning,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AutotechBlue)
        ) {
            if (isScanning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = TextPrimary,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Scanning modules... (this may take 90+ seconds)")
            } else {
                Icon(Icons.Default.Memory, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Discover Modules")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (modules.isEmpty() && !isScanning) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.SettingsInputComponent,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("No modules discovered yet", color = TextSecondary)
                    Text(
                        "Tap Discover Modules to scan the vehicle bus",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
        } else {
            // Module list
            Text(
                text = "${modules.size} module${if (modules.size != 1) "s" else ""} found",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(modules) { module ->
                    ModuleCard(
                        module = module,
                        isSelected = selectedModule == module,
                        onClick = { selectedModule = if (selectedModule == module) null else module }
                    )
                }
            }
        }

        // DID reader panel (when a module is selected)
        if (selectedModule != null) {
            Spacer(modifier = Modifier.height(12.dp))
            DIDReaderPanel(
                module = selectedModule!!,
                didInput = didInput,
                onDidInputChange = { didInput = it },
                onReadDid = { addr, did, bus ->
                    onReadDid(addr, did, bus)
                },
                didResults = didResults
            )
        }
    }
}

@Composable
private fun ModuleCard(
    module: ModuleItem,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) AutotechBlue.copy(alpha = 0.15f) else DarkSurfaceVariant
        ),
        border = if (isSelected) {
            CardDefaults.outlinedCardBorder().copy(
                // using the border parameter to indicate selection
            )
        } else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.DeveloperBoard,
                contentDescription = null,
                tint = if (isSelected) AutotechBlue else TextSecondary
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = module.name,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = module.address,
                        style = MaterialTheme.typography.bodySmall,
                        color = AutotechBlueLight
                    )
                    Text(
                        text = module.bus,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    if (module.protocol.isNotEmpty()) {
                        Text(
                            text = module.protocol,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            }
            if (isSelected) {
                Icon(Icons.Default.ExpandLess, contentDescription = null, tint = AutotechBlue)
            } else {
                Icon(Icons.Default.ExpandMore, contentDescription = null, tint = TextSecondary)
            }
        }
    }
}

@Composable
private fun DIDReaderPanel(
    module: ModuleItem,
    didInput: String,
    onDidInputChange: (String) -> Unit,
    onReadDid: (String, String, String) -> Unit,
    didResults: Map<String, String>
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "Read DID — ${module.name}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = AutotechBlue
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = didInput,
                    onValueChange = {
                        // Allow only hex chars
                        onDidInputChange(it.filter { c -> c.isDigit() || c in 'A'..'F' || c in 'a'..'f' }.take(4))
                    },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("DID e.g. F190", color = TextSecondary) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AutotechBlue,
                        unfocusedBorderColor = TextSecondary,
                        cursorColor = AutotechBlue,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )
                Button(
                    onClick = { onReadDid(module.address, didInput, module.bus) },
                    enabled = didInput.length >= 2,
                    colors = ButtonDefaults.buttonColors(containerColor = AutotechBlue)
                ) {
                    Text("Read")
                }
            }

            // Common DIDs quick buttons
            Spacer(modifier = Modifier.height(8.dp))
            Text("Common DIDs:", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val commonDids = listOf("F190" to "VIN", "F194" to "SW Ver", "F186" to "Session", "DD01" to "Ford DD01")
                commonDids.forEach { (did, label) ->
                    AssistChip(
                        onClick = {
                            onDidInputChange(did)
                            onReadDid(module.address, did, module.bus)
                        },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = DarkSurfaceVariant,
                            labelColor = AutotechBlueLight
                        )
                    )
                }
            }

            // Results
            if (didResults.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                didResults.forEach { (did, value) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                    ) {
                        Text(
                            text = "0x$did: ",
                            color = AutotechBlue,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = value,
                            color = TextPrimary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}
