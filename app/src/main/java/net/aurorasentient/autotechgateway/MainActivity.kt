package net.aurorasentient.autotechgateway

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.ContextCompat
import net.aurorasentient.autotechgateway.elm.ConnectionType
import net.aurorasentient.autotechgateway.elm.DTC
import net.aurorasentient.autotechgateway.elm.ECUModule
import net.aurorasentient.autotechgateway.service.GatewayState
import net.aurorasentient.autotechgateway.ui.GatewayViewModel
import net.aurorasentient.autotechgateway.ui.screens.*
import net.aurorasentient.autotechgateway.ui.theme.*

class MainActivity : ComponentActivity() {

    private val viewModel: GatewayViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val allGranted = grants.values.all { it }
        if (!allGranted) {
            Toast.makeText(this, "Bluetooth permissions required for OBD connection", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissions()

        setContent {
            AutotechGatewayTheme {
                MainApp(viewModel)
            }
        }
    }

    private fun requestPermissions() {
        val needed = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.BLUETOOTH_CONNECT)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            // Android <12
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.BLUETOOTH)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.BLUETOOTH_ADMIN)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }
}

// --- Navigation ---

enum class Screen(val label: String, val icon: ImageVector) {
    DASHBOARD("Dashboard", Icons.Default.Dashboard),
    DIAGNOSTICS("Diagnostics", Icons.Default.BugReport),
    MODULES("Modules", Icons.Default.Memory),
    SETTINGS("Settings", Icons.Default.Settings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainApp(viewModel: GatewayViewModel) {
    var currentScreen by remember { mutableStateOf(Screen.DASHBOARD) }

    val status by viewModel.status.collectAsState()
    val adapters by viewModel.adapters.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val livePids by viewModel.livePids.collectAsState()
    val dtcs by viewModel.dtcs.collectAsState()
    val modules by viewModel.modules.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val toastMessage by viewModel.toastMessage.collectAsState()

    // Show toasts
    LaunchedEffect(toastMessage) {
        // Toast is handled at the platform level; we show a snackbar instead
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Autotech Gateway",
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DarkSurface
                ),
                actions = {
                    // Connection status indicator in toolbar
                    val stateIcon = when (status.state) {
                        GatewayState.DISCONNECTED -> Icons.Default.LinkOff
                        GatewayState.CONNECTING -> Icons.Default.Sync
                        GatewayState.CONNECTED -> Icons.Default.Link
                        GatewayState.TUNNEL_CONNECTING -> Icons.Default.CloudSync
                        GatewayState.TUNNEL_ACTIVE -> Icons.Default.CloudDone
                        GatewayState.ERROR -> Icons.Default.Error
                    }
                    val stateColor = when (status.state) {
                        GatewayState.DISCONNECTED -> StatusRed
                        GatewayState.CONNECTING, GatewayState.TUNNEL_CONNECTING -> StatusYellow
                        GatewayState.CONNECTED -> StatusGreen
                        GatewayState.TUNNEL_ACTIVE -> AutotechBlue
                        GatewayState.ERROR -> StatusRed
                    }
                    Icon(stateIcon, contentDescription = "Status", tint = stateColor)
                    Spacer(modifier = Modifier.width(androidx.compose.ui.unit.Dp(12f)))
                }
            )
        },
        bottomBar = {
            NavigationBar(containerColor = DarkSurface) {
                Screen.entries.forEach { screen ->
                    NavigationBarItem(
                        selected = currentScreen == screen,
                        onClick = { currentScreen = screen },
                        icon = {
                            Icon(screen.icon, contentDescription = screen.label)
                        },
                        label = { Text(screen.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = AutotechBlue,
                            selectedTextColor = AutotechBlue,
                            unselectedIconColor = TextSecondary,
                            unselectedTextColor = TextSecondary,
                            indicatorColor = AutotechBlue.copy(alpha = 0.12f)
                        )
                    )
                }
            }
        },
        containerColor = DarkBackground
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (currentScreen) {
                Screen.DASHBOARD -> DashboardScreen(
                    status = status,
                    adapters = adapters,
                    onScanAdapters = { viewModel.scanForAdapters() },
                    onConnect = { viewModel.connectToAdapter(it) },
                    onDisconnect = { viewModel.disconnect() },
                    onStartTunnel = { viewModel.startTunnel() },
                    onStopTunnel = { viewModel.stopTunnel() },
                    onNavigateToDiagnostics = { currentScreen = Screen.DIAGNOSTICS },
                    onNavigateToModules = { currentScreen = Screen.MODULES }
                )
                Screen.DIAGNOSTICS -> DiagnosticsScreen(
                    livePids = livePids.mapValues { (name, value) ->
                        val pid = net.aurorasentient.autotechgateway.elm.PIDRegistry.findByName(name)
                        if (pid != null) "%.1f %s".format(value, pid.unit)
                        else "%.1f".format(value)
                    },
                    dtcs = dtcs.map { DTCItem(it.code, it.description, it.status) },
                    isReading = isScanning,
                    onReadDtcs = { viewModel.readDtcs() },
                    onClearDtcs = { viewModel.clearDtcs() },
                    onReadLivePids = { viewModel.readLivePids() },
                    onRefreshPids = { viewModel.refreshLivePids() }
                )
                Screen.MODULES -> ModulesScreen(
                    modules = modules.map {
                        ModuleItem(
                            name = it.name,
                            address = "0x%03X".format(it.address),
                            bus = it.bus
                        )
                    },
                    isScanning = isScanning,
                    onDiscoverModules = { viewModel.discoverModules() },
                    onReadDid = { addr, did, bus -> /* TODO: wire DID read */ }
                )
                Screen.SETTINGS -> SettingsScreen(
                    settings = settings,
                    onUpdateShopId = { viewModel.updateShopId(it) },
                    onUpdateApiKey = { viewModel.updateApiKey(it) },
                    onUpdateServerUrl = { viewModel.updateServerUrl(it) },
                    onUpdateWifiHost = { viewModel.updateWifiHost(it) },
                    onUpdateWifiPort = { viewModel.updateWifiPort(it) },
                    onUpdateAutoConnect = { viewModel.updateAutoConnect(it) },
                    onUpdateAutoTunnel = { viewModel.updateAutoTunnel(it) },
                    appVersion = "1.0.0"
                )
            }
        }

        // Toast Snackbar
        if (toastMessage != null) {
            Snackbar(
                modifier = Modifier.padding(padding),
                containerColor = DarkSurfaceVariant,
                contentColor = TextPrimary
            ) {
                Text(toastMessage!!)
            }
        }
    }
}
