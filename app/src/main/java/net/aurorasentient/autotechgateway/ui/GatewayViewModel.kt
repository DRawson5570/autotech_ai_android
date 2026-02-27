package net.aurorasentient.autotechgateway.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import net.aurorasentient.autotechgateway.elm.*
import net.aurorasentient.autotechgateway.service.GatewayService
import net.aurorasentient.autotechgateway.service.GatewayState
import net.aurorasentient.autotechgateway.service.GatewayStatus
import net.aurorasentient.autotechgateway.ui.screens.ScopeState
import net.aurorasentient.autotechgateway.update.AutoUpdater
import net.aurorasentient.autotechgateway.update.UpdateInfo

private const val TAG = "GatewayVM"

/**
 * Main ViewModel — bridges the GatewayService with Compose UI.
 */
class GatewayViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepo = SettingsRepository(application)

    // Service binding
    private var gatewayService: GatewayService? = null
    private var bound = false

    // UI State
    private val _adapters = MutableStateFlow<List<DetectedAdapter>>(emptyList())
    val adapters: StateFlow<List<DetectedAdapter>> = _adapters

    private val _status = MutableStateFlow(GatewayStatus())
    val status: StateFlow<GatewayStatus> = _status

    val settings: StateFlow<AppSettings> = settingsRepo.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    private val _livePids = MutableStateFlow<Map<String, Double>>(emptyMap())
    val livePids: StateFlow<Map<String, Double>> = _livePids

    private val _dtcs = MutableStateFlow<List<DTC>>(emptyList())
    val dtcs: StateFlow<List<DTC>> = _dtcs

    private val _modules = MutableStateFlow<List<ECUModule>>(emptyList())
    val modules: StateFlow<List<ECUModule>> = _modules

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage

    // Scope state
    private val _scopeState = MutableStateFlow(ScopeState())
    val scopeState: StateFlow<ScopeState> = _scopeState
    private val scopeSamples = mutableListOf<ScopeSample>()
    private var sampleCount = 0
    private var sampleStartTime = 0L

    // Auto-updater
    private val autoUpdater = AutoUpdater(application)
    private val _updateInfo = MutableStateFlow<UpdateInfo?>(null)
    val updateInfo: StateFlow<UpdateInfo?> = _updateInfo
    private val _updateProgress = MutableStateFlow(-1f)  // -1 = not downloading
    val updateProgress: StateFlow<Float> = _updateProgress

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as GatewayService.LocalBinder).getService()
            gatewayService = service
            bound = true

            // Observe service status
            viewModelScope.launch {
                service.status.collect { status ->
                    _status.value = status
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            gatewayService = null
            bound = false
        }
    }

    init {
        bindService()

        // Start periodic update checks
        autoUpdater.startPeriodicChecks(viewModelScope) { info ->
            _updateInfo.value = info
            _toastMessage.value = "Update available: v${info.latestVersion}"
        }
    }

    override fun onCleared() {
        if (bound) {
            getApplication<Application>().unbindService(serviceConnection)
            bound = false
        }
        super.onCleared()
    }

    // ── Service Binding ───────────────────────────────────────────

    private fun bindService() {
        val context = getApplication<Application>()
        val intent = Intent(context, GatewayService::class.java)
        context.startForegroundService(intent)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    // ── Adapter Scanning ──────────────────────────────────────────

    fun scanForAdapters() {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val found = AdapterScanner.findBluetoothAdapters(context)

            // Also add WiFi option
            val all = found.toMutableList()
            all.add(DetectedAdapter(
                name = "WiFi Adapter",
                address = settings.value.wifiHost,
                type = ConnectionType.WIFI
            ))

            _adapters.value = all
            Log.i(TAG, "Found ${all.size} adapters")
        }
    }

    // ── Connection ────────────────────────────────────────────────

    fun connectToAdapter(adapter: DetectedAdapter) {
        viewModelScope.launch {
            try {
                if (adapter.type == ConnectionType.WIFI) {
                    val host = settings.value.wifiHost
                    val port = settings.value.wifiPort.toIntOrNull() ?: 35000
                    gatewayService?.connectWifi(host, port)
                } else {
                    gatewayService?.connect(adapter)
                }

                settingsRepo.updateLastAdapter(adapter.address, adapter.type.name)
                _toastMessage.value = "Connected to ${adapter.name}"

                // Detect STN/OBDLink capabilities
                detectAdapterCapabilities()

                // Auto-start tunnel if configured
                if (settings.value.autoTunnel && settings.value.shopId.isNotEmpty()) {
                    startTunnel()
                }
            } catch (e: Exception) {
                _toastMessage.value = "Connection failed: ${e.message}"
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            gatewayService?.disconnect()
            _livePids.value = emptyMap()
            _dtcs.value = emptyList()
            _modules.value = emptyList()
            _toastMessage.value = "Disconnected"
        }
    }

    // ── Tunnel ────────────────────────────────────────────────────

    fun startTunnel() {
        val shopId = settings.value.shopId
        val apiKey = settings.value.apiKey
        if (shopId.isEmpty()) {
            viewModelScope.launch { _toastMessage.value = "Set Shop ID in Settings first" }
            return
        }
        gatewayService?.startTunnel(shopId, apiKey)
    }

    fun stopTunnel() {
        gatewayService?.stopTunnel()
    }

    // ── Diagnostics ───────────────────────────────────────────────

    fun readDtcs() {
        viewModelScope.launch {
            try {
                val dtcs = gatewayService?.protocol?.readAllDtcs() ?: emptyList()
                _dtcs.value = dtcs
                _toastMessage.value = "Found ${dtcs.size} DTC(s)"
            } catch (e: Exception) {
                _toastMessage.value = "DTC read failed: ${e.message}"
            }
        }
    }

    fun clearDtcs() {
        viewModelScope.launch {
            try {
                val ok = gatewayService?.protocol?.clearDtcs() ?: false
                if (ok) {
                    _dtcs.value = emptyList()
                    _toastMessage.value = "DTCs cleared"
                } else {
                    _toastMessage.value = "Failed to clear DTCs"
                }
            } catch (e: Exception) {
                _toastMessage.value = "Clear failed: ${e.message}"
            }
        }
    }

    fun readLivePids() {
        readLivePids(listOf("RPM", "COOLANT_TEMP", "SPEED", "LOAD", "THROTTLE_POS",
            "CTRL_VOLTAGE", "FUEL_LEVEL", "IAT"))
    }

    fun readLivePids(pidNames: List<String>) {
        viewModelScope.launch {
            try {
                val values = gatewayService?.protocol?.readPids(pidNames) ?: emptyMap()
                _livePids.value = values
            } catch (e: Exception) {
                _toastMessage.value = "PID read failed: ${e.message}"
            }
        }
    }

    fun refreshLivePids() {
        val current = _livePids.value.keys.toList()
        if (current.isEmpty()) {
            readLivePids(listOf("RPM", "COOLANT_TEMP", "SPEED", "LOAD", "THROTTLE_POS",
                "CTRL_VOLTAGE", "FUEL_LEVEL", "IAT"))
        } else {
            readLivePids(current)
        }
    }

    fun discoverModules() {
        viewModelScope.launch {
            _isScanning.value = true
            try {
                val vin = gatewayService?.protocol?.readVin()
                val mods = gatewayService?.protocol?.discoverModules(vin) ?: emptyList()
                _modules.value = mods
                _toastMessage.value = "Found ${mods.size} module(s)"
            } catch (e: Exception) {
                _toastMessage.value = "Scan failed: ${e.message}"
            } finally {
                _isScanning.value = false
            }
        }
    }

    // ── Settings ──────────────────────────────────────────────────

    fun updateShopId(shopId: String) {
        viewModelScope.launch { settingsRepo.updateShopId(shopId) }
    }

    fun updateApiKey(apiKey: String) {
        viewModelScope.launch { settingsRepo.updateApiKey(apiKey) }
    }

    fun updateWifiSettings(host: String, port: String) {
        viewModelScope.launch { settingsRepo.updateWifiSettings(host, port) }
    }

    fun updateServerUrl(url: String) {
        viewModelScope.launch { settingsRepo.updateServerUrl(url) }
    }

    fun updateWifiHost(host: String) {
        viewModelScope.launch { settingsRepo.updateWifiHost(host) }
    }

    fun updateWifiPort(port: Int) {
        viewModelScope.launch { settingsRepo.updateWifiPort(port) }
    }

    fun updateAutoConnect(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.updateAutoConnect(enabled) }
    }

    fun updateAutoTunnel(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.updateAutoTunnel(enabled) }
    }

    // ── Scope ─────────────────────────────────────────────────

    fun selectScopePid(pidName: String) {
        _scopeState.value = _scopeState.value.copy(selectedPid = pidName)
    }

    fun startScope() {
        val pid = _scopeState.value.selectedPid
        val protocol = gatewayService?.protocol ?: run {
            _toastMessage.value = "Not connected"
            return
        }

        scopeSamples.clear()
        sampleCount = 0
        sampleStartTime = System.currentTimeMillis()

        _scopeState.value = _scopeState.value.copy(
            isRunning = true,
            samples = emptyList(),
            currentValue = 0.0,
            minValue = 0.0,
            maxValue = 0.0,
            sampleRate = 0f,
            capabilities = protocol.capabilities
        )

        viewModelScope.launch {
            try {
                protocol.startScope(pid) { sample ->
                    scopeSamples.add(sample)
                    sampleCount++

                    // Keep only last 60 seconds of samples
                    val cutoff = System.currentTimeMillis() - 60_000
                    while (scopeSamples.isNotEmpty() && scopeSamples.first().timestampMs < cutoff) {
                        scopeSamples.removeFirst()
                    }

                    // Calculate sample rate
                    val elapsed = (System.currentTimeMillis() - sampleStartTime) / 1000f
                    val rate = if (elapsed > 0) sampleCount / elapsed else 0f

                    _scopeState.value = _scopeState.value.copy(
                        samples = scopeSamples.toList(),
                        currentValue = sample.value,
                        minValue = scopeSamples.minOf { it.value },
                        maxValue = scopeSamples.maxOf { it.value },
                        sampleRate = rate
                    )
                }
            } catch (e: Exception) {
                _toastMessage.value = "Scope error: ${e.message}"
            } finally {
                _scopeState.value = _scopeState.value.copy(isRunning = false)
            }
        }
    }

    fun stopScope() {
        gatewayService?.protocol?.stopScope()
    }

    /**
     * Detect adapter capabilities (STN/OBDLink) after connecting.
     */
    fun detectAdapterCapabilities() {
        viewModelScope.launch {
            try {
                val caps = gatewayService?.protocol?.detectAdapter()
                if (caps != null) {
                    _scopeState.value = _scopeState.value.copy(capabilities = caps)
                    if (caps.isSTN) {
                        _toastMessage.value = "${caps.deviceName} detected — high-speed mode enabled"
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Adapter detection failed: ${e.message}")
            }
        }
    }

    // ── Auto-Update ───────────────────────────────────────────

    fun checkForUpdate() {
        viewModelScope.launch {
            val info = autoUpdater.checkForUpdate()
            _updateInfo.value = info
            if (!info.available) {
                _toastMessage.value = "You're on the latest version (${info.currentVersion})"
            }
        }
    }

    fun installUpdate() {
        val info = _updateInfo.value ?: return
        if (info.downloadUrl.isEmpty()) return

        viewModelScope.launch {
            _updateProgress.value = 0f
            val success = autoUpdater.downloadAndInstall(info.downloadUrl) { progress ->
                _updateProgress.value = progress
            }
            if (!success) {
                _toastMessage.value = "Update download failed"
            }
            _updateProgress.value = -1f
        }
    }

    fun dismissUpdate() {
        _updateInfo.value = null
    }

    fun getAppVersion(): String = autoUpdater.getCurrentVersion()
}
