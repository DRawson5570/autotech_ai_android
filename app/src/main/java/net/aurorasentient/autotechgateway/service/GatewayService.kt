package net.aurorasentient.autotechgateway.service

/**
 * Android Foreground Service that maintains the ELM327 connection
 * and server tunnel. Runs persistently with a notification.
 *
 * This is the Android equivalent of app.py + reverse_tunnel.py.
 */

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import net.aurorasentient.autotechgateway.AutotechApp
import net.aurorasentient.autotechgateway.R
import net.aurorasentient.autotechgateway.crash.CrashReporter
import net.aurorasentient.autotechgateway.elm.*
import net.aurorasentient.autotechgateway.tunnel.GatewayTunnel
import net.aurorasentient.autotechgateway.MainActivity

private const val TAG = "GatewayService"
private const val NOTIFICATION_ID = 1
private const val KEEPALIVE_INTERVAL_MS = 25000L
private const val IDLE_THRESHOLD_MS = 15000L

enum class GatewayState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    TUNNEL_CONNECTING,
    TUNNEL_ACTIVE,
    ERROR
}

data class GatewayStatus(
    val state: GatewayState = GatewayState.DISCONNECTED,
    val adapterName: String = "",
    val adapterVersion: String = "",
    val vin: String? = null,
    val vehicleInfo: VINDecoder.VINInfo? = null,
    val supportedPidCount: Int = 0,
    val tunnelRegistered: Boolean = false,
    val errorMessage: String? = null,
    val batteryVoltage: Double? = null
)

class GatewayService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): GatewayService = this@GatewayService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null

    // Public state
    private val _status = MutableStateFlow(GatewayStatus())
    val status: StateFlow<GatewayStatus> = _status

    // Components
    var connection: ElmConnection? = null
        private set
    var protocol: OBDProtocol? = null
        private set
    private var tunnel: GatewayTunnel? = null
    private var keepaliveJob: Job? = null

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification("Starting..."))
        acquireWakeLock()
        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.launch {
            disconnect()
        }
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ── Public API ────────────────────────────────────────────────

    /**
     * Connect to an ELM327 adapter.
     */
    suspend fun connect(adapter: DetectedAdapter) {
        try {
            updateState(GatewayState.CONNECTING)
            updateNotification("Connecting to ${adapter.name}...")

            val conn = AdapterScanner.createConnection(applicationContext, adapter)
            conn.connect()

            connection = conn
            val proto = OBDProtocol(conn)
            protocol = proto

            updateState(GatewayState.CONNECTED, adapterName = conn.adapterName, adapterVersion = conn.adapterVersion)
            updateNotification("Connected: ${conn.adapterName}")

            // Read VIN
            val vin = proto.readVin()
            val vinInfo = vin?.let { VINDecoder.decode(it) }

            // Get supported PIDs
            val supported = proto.getSupportedPids()

            // Read battery voltage
            val voltage = proto.readBatteryVoltage()

            _status.value = _status.value.copy(
                vin = vin,
                vehicleInfo = vinInfo,
                supportedPidCount = supported.size,
                batteryVoltage = voltage
            )

            val vinDisplay = if (vinInfo != null) "${vinInfo.year} ${vinInfo.make}" else (vin ?: "Unknown")
            updateNotification("$vinDisplay | ${conn.adapterName}")

            // Start keepalive
            startKeepalive()

            Log.i(TAG, "Connected: ${conn.adapterName} | VIN: $vin | PIDs: ${supported.size}")

        } catch (e: Exception) {
            Log.e(TAG, "Connect failed: ${e.message}", e)
            CrashReporter.reportNonFatal(e, mapOf("context" to "bt_connect", "adapter" to adapter.name))
            updateState(GatewayState.ERROR, errorMessage = e.message)
            updateNotification("Connection failed")
            throw e
        }
    }

    /**
     * Connect to a WiFi adapter.
     */
    suspend fun connectWifi(host: String = "192.168.0.10", port: Int = 35000) {
        try {
            updateState(GatewayState.CONNECTING)

            val conn = AdapterScanner.createWifiConnection(host, port)
            conn.connect()

            connection = conn
            val proto = OBDProtocol(conn)
            protocol = proto

            updateState(GatewayState.CONNECTED, adapterName = conn.adapterName)

            val vin = proto.readVin()
            val vinInfo = vin?.let { VINDecoder.decode(it) }
            val supported = proto.getSupportedPids()
            val voltage = proto.readBatteryVoltage()

            _status.value = _status.value.copy(
                vin = vin,
                vehicleInfo = vinInfo,
                supportedPidCount = supported.size,
                batteryVoltage = voltage
            )

            updateNotification("WiFi: ${conn.adapterName}")
            startKeepalive()

        } catch (e: Exception) {
            CrashReporter.reportNonFatal(e, mapOf("context" to "wifi_connect", "host" to host))
            updateState(GatewayState.ERROR, errorMessage = e.message)
            throw e
        }
    }

    /**
     * Disconnect from adapter and tunnel.
     */
    suspend fun disconnect() {
        keepaliveJob?.cancel()
        tunnel?.stop()
        tunnel = null

        try {
            connection?.disconnect()
        } catch (_: Exception) {}

        connection = null
        protocol = null

        updateState(GatewayState.DISCONNECTED)
        updateNotification("Disconnected")
    }

    /**
     * Start the server tunnel.
     */
    fun startTunnel(shopId: String, apiKey: String) {
        val conn = connection ?: return
        val proto = protocol ?: return

        updateState(GatewayState.TUNNEL_CONNECTING)

        tunnel = GatewayTunnel(shopId, apiKey, proto, conn).apply {
            statusListener = object : GatewayTunnel.StatusListener {
                override fun onTunnelConnected() {
                    Log.i(TAG, "Tunnel connected")
                }

                override fun onTunnelRegistered() {
                    serviceScope.launch {
                        updateState(GatewayState.TUNNEL_ACTIVE, tunnelRegistered = true)
                        updateNotification("Tunnel active | ${_status.value.vin ?: "Connected"}")
                    }
                }

                override fun onTunnelDisconnected() {
                    serviceScope.launch {
                        if (_status.value.state == GatewayState.TUNNEL_ACTIVE) {
                            updateState(GatewayState.CONNECTED, tunnelRegistered = false)
                            updateNotification("Tunnel disconnected | ${_status.value.adapterName}")
                        }
                    }
                }

                override fun onTunnelError(message: String) {
                    Log.e(TAG, "Tunnel error: $message")
                }
            }
            start(serviceScope)
        }
    }

    /**
     * Stop the server tunnel.
     */
    fun stopTunnel() {
        tunnel?.stop()
        tunnel = null
        if (_status.value.state == GatewayState.TUNNEL_ACTIVE) {
            serviceScope.launch {
                updateState(GatewayState.CONNECTED, tunnelRegistered = false)
            }
        }
    }

    // ── Internal ──────────────────────────────────────────────────

    private fun startKeepalive() {
        keepaliveJob?.cancel()
        keepaliveJob = serviceScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(KEEPALIVE_INTERVAL_MS)
                val conn = connection ?: break
                if (!conn.isConnected) break

                val idle = System.currentTimeMillis() - conn.lastActivity
                if (idle > IDLE_THRESHOLD_MS) {
                    try {
                        val voltage = protocol?.readBatteryVoltage()
                        if (voltage != null) {
                            _status.value = _status.value.copy(batteryVoltage = voltage)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Keepalive failed: ${e.message}")
                    }
                }
            }
        }
    }

    private fun updateState(
        state: GatewayState,
        adapterName: String? = null,
        adapterVersion: String? = null,
        tunnelRegistered: Boolean? = null,
        errorMessage: String? = null
    ) {
        _status.value = _status.value.copy(
            state = state,
            adapterName = adapterName ?: _status.value.adapterName,
            adapterVersion = adapterVersion ?: _status.value.adapterVersion,
            tunnelRegistered = tunnelRegistered ?: _status.value.tunnelRegistered,
            errorMessage = errorMessage
        )
    }

    private fun createNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, AutotechApp.CHANNEL_ID)
            .setContentTitle("Autotech Gateway")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            val notification = createNotification(text)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            manager.notify(NOTIFICATION_ID, notification)
        } catch (_: Exception) {}
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "autotechgateway:service").apply {
            acquire(10 * 60 * 1000L)  // 10 minutes max
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }
}
