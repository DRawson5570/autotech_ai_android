package net.aurorasentient.autotechgateway

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
        // Start foreground service now that permissions are resolved
        viewModel.ensureServiceStarted()
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
        } else {
            // All permissions already granted, start service immediately
            viewModel.ensureServiceStarted()
        }
    }
}

// --- Navigation ---

enum class Screen(val label: String, val icon: ImageVector) {
    DASHBOARD("Dashboard", Icons.Default.Dashboard),
    DIAGNOSTICS("Diagnostics", Icons.Default.BugReport),
    SCOPE("Scope", Icons.Default.ShowChart),
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
    val scopeState by viewModel.scopeState.collectAsState()
    val updateInfo by viewModel.updateInfo.collectAsState()
    val updateProgress by viewModel.updateProgress.collectAsState()
    val showUnsupportedAdapter by viewModel.showUnsupportedAdapterDialog.collectAsState()
    val batteryOptNeeded by viewModel.batteryOptimizationNeeded.collectAsState()
    val authLoading by viewModel.authLoading.collectAsState()
    val authError by viewModel.authError.collectAsState()

    // ── Auth gate ─────────────────────────────────────────────────
    // Must be signed in before anything else
    if (!settings.isLoggedIn) {
        AuthScreen(
            isLoading = authLoading,
            errorMessage = authError,
            onSignIn = { email, password -> viewModel.signIn(email, password) },
            onSignUp = { email, name, password -> viewModel.signUp(email, name, password) },
            onClearError = { viewModel.clearAuthError() }
        )
        return
    }

    // Re-check battery optimization when app resumes (user may have just changed it)
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.checkBatteryOptimization()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Unsupported adapter dialog
    if (showUnsupportedAdapter) {
        UnsupportedAdapterDialog(
            onDismiss = { viewModel.dismissUnsupportedAdapterDialog() },
            onShopOBDLink = {
                viewModel.dismissUnsupportedAdapterDialog()
            }
        )
    }

    // Show onboarding on first launch
    if (!settings.onboardingComplete) {
        OnboardingScreen(
            onComplete = { viewModel.completeOnboarding() }
        )
        return
    }
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
        Column(modifier = Modifier.padding(padding)) {
            // Battery optimization warning banner — persistent until fixed
            if (batteryOptNeeded && status.state.ordinal >= GatewayState.CONNECTED.ordinal) {
                BatteryOptimizationBanner(
                    onFixNow = { viewModel.requestBatteryOptimizationExemption() }
                )
            }

            Box(modifier = Modifier.weight(1f)) {
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
                        val pid = net.aurorasentient.autotechgateway.elm.PIDRegistry.getByName(name)
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
                Screen.SCOPE -> ScopeScreen(
                    scopeState = scopeState,
                    onSelectPid = { viewModel.selectScopePid(it) },
                    onStartScope = { viewModel.startScope() },
                    onStopScope = { viewModel.stopScope() }
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
                    onCheckForUpdate = { viewModel.checkForUpdate() },
                    onInstallUpdate = { viewModel.installUpdate() },
                    onSignOut = { viewModel.signOut() },
                    updateInfo = updateInfo,
                    updateProgress = updateProgress,
                    appVersion = viewModel.getAppVersion()
                )
            }
        }

        // Toast Snackbar
        if (toastMessage != null) {
            Snackbar(
                containerColor = DarkSurfaceVariant,
                contentColor = TextPrimary
            ) {
                Text(toastMessage!!)
            }
        }
        } // Column
    }
}

// ── Unsupported Adapter Dialog ──────────────────────────────────

private const val OBDLINK_STORE_URL = "https://www.obdlink.com/products/"

@Composable
fun UnsupportedAdapterDialog(
    onDismiss: () -> Unit,
    onShopOBDLink: () -> Unit
) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkSurface,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        icon = {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = StatusYellow,
                modifier = Modifier.size(40.dp)
            )
        },
        title = {
            Text(
                "Unsupported Adapter",
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "This app requires an OBDLink adapter with an STN chip for reliable, " +
                    "professional-grade diagnostics.",
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
                Text(
                    "Cheap ELM327 clones lack the advanced protocols needed for " +
                    "multi-bus scanning, enhanced diagnostics, and stable connections.",
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
                Text(
                    "Compatible adapters:",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = TextPrimary
                )
                Column(modifier = Modifier.padding(start = 8.dp)) {
                    Text("• OBDLink MX+  (Bluetooth)", fontSize = 13.sp)
                    Text("• OBDLink EX   (USB)", fontSize = 13.sp)
                    Text("• OBDLink LX   (Bluetooth)", fontSize = 13.sp)
                    Text("• OBDLink CX   (BLE)", fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onShopOBDLink()
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(OBDLINK_STORE_URL))
                    context.startActivity(intent)
                },
                colors = ButtonDefaults.buttonColors(containerColor = AutotechBlue)
            ) {
                Icon(Icons.Default.ShoppingCart, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Shop OBDLink")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Dismiss", color = TextSecondary)
            }
        }
    )
}

// ── Battery Optimization Banner ─────────────────────────────────

/**
 * Persistent warning banner shown when battery optimization is enabled.
 * Cannot be dismissed — only goes away when the user actually exempts the app.
 */
@Composable
fun BatteryOptimizationBanner(onFixNow: () -> Unit) {
    Surface(
        color = StatusYellow.copy(alpha = 0.15f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                Icons.Default.BatteryAlert,
                contentDescription = null,
                tint = StatusYellow,
                modifier = Modifier.size(24.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Battery optimization will disconnect your adapter",
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    lineHeight = 16.sp
                )
                Text(
                    "Disable battery optimization for Autotech Gateway to maintain a stable Bluetooth connection.",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 15.sp
                )
            }
            Button(
                onClick = onFixNow,
                colors = ButtonDefaults.buttonColors(containerColor = StatusYellow),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text("Fix Now", color = DarkBackground, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
