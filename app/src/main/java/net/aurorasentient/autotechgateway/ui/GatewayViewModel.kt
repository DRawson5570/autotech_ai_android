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
}
