package net.aurorasentient.autotechgateway.elm

/**
 * Transport layer for ELM327 communication.
 *
 * Mirrors connection.py from the Python gateway.
 * Handles Bluetooth Classic (SPP), BLE, and WiFi connections.
 */

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

private const val TAG = "ElmConnection"

/** Standard SPP UUID for Bluetooth Classic serial communication */
private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

/** Common BLE service/characteristic UUIDs used by OBD adapters */
private val BLE_SERVICE_UUIDS = listOf(
    UUID.fromString("0000FFF0-0000-1000-8000-00805F9B34FB"),  // Common Chinese clones
    UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB"),  // OBDLink / Veepeak
    UUID.fromString("E7810A71-73AE-499D-8C15-FAA9AEF0C3F2"),  // OBDLink MX+
)
private val BLE_CHAR_UUIDS = listOf(
    UUID.fromString("0000FFF1-0000-1000-8000-00805F9B34FB"),
    UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB"),
    UUID.fromString("BEF8D6C9-9C21-4C9E-B632-BD58C1009F9F"),
)

/** Default WiFi adapter address (most WiFi ELM327 clones) */
private const val WIFI_DEFAULT_HOST = "192.168.0.10"
private const val WIFI_DEFAULT_PORT = 35000

/** Timeouts */
private const val DEFAULT_TIMEOUT_MS = 5000L
private const val RESET_TIMEOUT_MS = 10000L
private const val WIFI_CONNECT_TIMEOUT_MS = 5000
private const val WAKE_RETRIES = 5
private const val WAKE_TIMEOUT_MS = 2000L

enum class ConnectionType {
    BLUETOOTH_CLASSIC,
    BLUETOOTH_LE,
    WIFI
}

data class ConnectionConfig(
    val type: ConnectionType,
    val address: String = "",      // BT MAC or WiFi host
    val port: Int = WIFI_DEFAULT_PORT,  // WiFi port
    val timeout: Long = DEFAULT_TIMEOUT_MS
)

/**
 * Detected ELM327 adapter info, returned by scanning.
 */
data class DetectedAdapter(
    val name: String,
    val address: String,
    val type: ConnectionType,
    val version: String = "",
    val bluetoothDevice: BluetoothDevice? = null
)

/**
 * Abstract base for ELM327 transport. Implementations handle
 * the actual byte I/O over Bluetooth or WiFi.
 */
sealed class ElmConnection {

    protected val commandLock = Mutex()
    var lastActivity: Long = System.currentTimeMillis()
        protected set
    var isConnected: Boolean = false
        protected set
    var adapterName: String = ""
        protected set
    var adapterVersion: String = ""
        protected set

    /**
     * Open the physical connection and run the AT init sequence.
     */
    abstract suspend fun connect()

    /**
     * Close the physical connection.
     */
    abstract suspend fun disconnect()

    /**
     * Send an AT/OBD command and return the cleaned response.
     * Thread-safe via mutex.
     */
    suspend fun sendCommand(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): String {
        commandLock.withLock {
            return sendCommandInternal(command, timeoutMs)
        }
    }

    protected abstract suspend fun sendCommandInternal(command: String, timeoutMs: Long): String

    /**
     * Run the standard AT initialization sequence.
     * ATZ → ATE0 → ATL0 → ATS0 → ATSP0
     */
    protected suspend fun initializeAdapter() {
        // Reset
        val resetResp = sendCommandInternal("ATZ", RESET_TIMEOUT_MS)
        delay(2000)

        // Parse adapter info from ATZ response
        parseAdapterInfo(resetResp)

        // Echo off
        sendCommandInternal("ATE0", DEFAULT_TIMEOUT_MS)
        // Linefeeds off
        sendCommandInternal("ATL0", DEFAULT_TIMEOUT_MS)
        // Spaces off
        sendCommandInternal("ATS0", DEFAULT_TIMEOUT_MS)
        // Auto-detect protocol
        sendCommandInternal("ATSP0", DEFAULT_TIMEOUT_MS)

        Log.i(TAG, "Adapter initialized: $adapterName v$adapterVersion")
    }

    private fun parseAdapterInfo(response: String) {
        val lines = response.lines().map { it.trim() }.filter { it.isNotEmpty() }
        for (line in lines) {
            if (line.contains("ELM") || line.contains("STN") || line.contains("OBDLink")) {
                adapterName = line.substringBefore(" v").substringBefore(" V").trim()
                val vMatch = Regex("""v?(\d+\.\d+\S*)""", RegexOption.IGNORE_CASE).find(line)
                adapterVersion = vMatch?.groupValues?.getOrNull(1) ?: ""
                return
            }
        }
        adapterName = "Unknown ELM327"
    }

    /**
     * Clean an ELM327 response: strip prompt, remove echo, trim whitespace.
     */
    protected fun cleanResponse(raw: String, sentCommand: String): String {
        var cleaned = raw
            .replace(">", "")
            .replace("\u0000", "")
            .trim()

        // Remove echo line if present
        val lines = cleaned.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size > 1) {
            val first = lines[0].uppercase()
            if (first == sentCommand.uppercase() ||
                first.startsWith(sentCommand.uppercase().take(3))) {
                return lines.drop(1).joinToString("\n")
            }
        }

        // Check if first line looks like a response vs echo
        if (lines.isNotEmpty()) {
            val first = lines[0].uppercase()
            val isResponse = first.startsWith("4") || first.startsWith("7E") ||
                first.startsWith("7F") || first == "OK" || first == "NO DATA" ||
                first.startsWith("SEARCHING") || first.startsWith("ELM") ||
                first.startsWith("STN") || first.startsWith("?")
            if (!isResponse && lines.size > 1) {
                return lines.drop(1).joinToString("\n")
            }
        }

        return cleaned
    }
}

// ─────────────────────────────────────────────────────────────────────
//  Bluetooth Classic (SPP) Connection
// ─────────────────────────────────────────────────────────────────────

@SuppressLint("MissingPermission")
class BluetoothClassicConnection(
    private val device: BluetoothDevice,
    private val config: ConnectionConfig
) : ElmConnection() {

    private var socket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    override suspend fun connect() {
        withContext(Dispatchers.IO) {
        Log.i(TAG, "Connecting to BT Classic: ${device.name} (${device.address})")

        socket = device.createRfcommSocketToServiceRecord(SPP_UUID)

        try {
            socket!!.connect()
        } catch (e: IOException) {
            // Fallback: reflection-based connect for problematic devices
            Log.w(TAG, "Standard connect failed, trying reflection fallback")
            socket?.close()
            val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
            socket = m.invoke(device, 1) as BluetoothSocket
            socket!!.connect()
        }

        inputStream = socket!!.inputStream
        outputStream = socket!!.outputStream
        isConnected = true

        // Wake up sleeping adapters
        wakeUp()

        // AT init
        initializeAdapter()

        Log.i(TAG, "BT Classic connected to ${device.name}")
    }
    }

    override suspend fun disconnect() {
        withContext(Dispatchers.IO) {
        isConnected = false
        try { inputStream?.close() } catch (_: Exception) {}
        try { outputStream?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        inputStream = null
        outputStream = null
        socket = null
        Log.i(TAG, "BT Classic disconnected")
    }
    }

    override suspend fun sendCommandInternal(command: String, timeoutMs: Long): String =
        withContext(Dispatchers.IO) {
            val os = outputStream ?: throw IOException("Not connected")
            val ins = inputStream ?: throw IOException("Not connected")

            // Flush input
            while (ins.available() > 0) ins.read()

            // Send command
            os.write("$command\r".toByteArray(Charsets.US_ASCII))
            os.flush()
            lastActivity = System.currentTimeMillis()

            // Read until '>' prompt
            val response = readUntilPrompt(ins, timeoutMs)
            cleanResponse(response, command)
        }

    private suspend fun readUntilPrompt(ins: InputStream, timeoutMs: Long): String {
        val buffer = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs

        while (System.currentTimeMillis() < deadline) {
            if (ins.available() > 0) {
                val b = ins.read()
                if (b == -1) break
                val c = b.toChar()
                buffer.append(c)
                if (c == '>') break
            } else {
                delay(10)
            }
        }
        return buffer.toString()
    }

    private suspend fun wakeUp() {
        val ins = inputStream ?: return
        val os = outputStream ?: return

        for (attempt in 1..WAKE_RETRIES) {
            try {
                os.write("\r\n".toByteArray())
                os.flush()
                val resp = withTimeoutOrNull(WAKE_TIMEOUT_MS) {
                    readUntilPrompt(ins, WAKE_TIMEOUT_MS)
                }
                if (resp != null && resp.contains(">")) {
                    Log.d(TAG, "Adapter awake after attempt $attempt")
                    return
                }
            } catch (_: Exception) {}
            delay(500)
        }
        Log.w(TAG, "Adapter may not be fully awake after $WAKE_RETRIES attempts")
    }
}

// ─────────────────────────────────────────────────────────────────────
//  Bluetooth LE (BLE) Connection
// ─────────────────────────────────────────────────────────────────────

@SuppressLint("MissingPermission")
class BleConnection(
    private val context: Context,
    private val device: BluetoothDevice,
    private val config: ConnectionConfig
) : ElmConnection() {

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var readChar: BluetoothGattCharacteristic? = null
    private val responseQueue = ConcurrentLinkedQueue<String>()
    private val responseBuffer = StringBuilder()

    @Volatile
    private var connectionReady = false
    private val connectLatch = CompletableDeferred<Boolean>()
    private val servicesLatch = CompletableDeferred<Boolean>()

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "BLE connected, discovering services...")
                    g.discoverServices()
                    connectLatch.complete(true)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    isConnected = false
                    connectionReady = false
                    Log.i(TAG, "BLE disconnected")
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                findCharacteristics(g)
                servicesLatch.complete(true)
            } else {
                servicesLatch.complete(false)
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val data = String(characteristic.value, Charsets.US_ASCII)
            responseBuffer.append(data)
            if (data.contains(">")) {
                responseQueue.add(responseBuffer.toString())
                responseBuffer.clear()
            }
        }
    }

    private fun findCharacteristics(g: BluetoothGatt) {
        for (serviceUuid in BLE_SERVICE_UUIDS) {
            val service = g.getService(serviceUuid) ?: continue
            for (charUuid in BLE_CHAR_UUIDS) {
                val char = service.getCharacteristic(charUuid) ?: continue
                val props = char.properties
                if (props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ||
                    props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
                    writeChar = char
                }
                if (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ||
                    props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) {
                    readChar = char
                    g.setCharacteristicNotification(char, true)
                    // Enable notifications via descriptor
                    val desc = char.getDescriptor(
                        UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
                    )
                    if (desc != null) {
                        desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        g.writeDescriptor(desc)
                    }
                }
            }
            if (writeChar != null && readChar != null) {
                connectionReady = true
                Log.i(TAG, "BLE characteristics found on service $serviceUuid")
                return
            }
        }
        Log.w(TAG, "Could not find suitable BLE characteristics")
    }

    override suspend fun connect() {
        Log.i(TAG, "Connecting to BLE: ${device.name} (${device.address})")

        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)

        withTimeout(10000) { connectLatch.await() }
        withTimeout(10000) { servicesLatch.await() }

        if (!connectionReady) {
            throw IOException("BLE: Could not find OBD characteristics")
        }

        isConnected = true
        initializeAdapter()
        Log.i(TAG, "BLE connected to ${device.name}")
    }

    override suspend fun disconnect() {
        isConnected = false
        connectionReady = false
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        writeChar = null
        readChar = null
    }

    @Suppress("DEPRECATION")
    override suspend fun sendCommandInternal(command: String, timeoutMs: Long): String {
        val wChar = writeChar ?: throw IOException("BLE not connected")
        val g = gatt ?: throw IOException("BLE not connected")

        responseQueue.clear()
        responseBuffer.clear()

        // BLE has 20-byte MTU by default; chunk if needed
        val bytes = "$command\r".toByteArray(Charsets.US_ASCII)
        val chunkSize = 20
        for (i in bytes.indices step chunkSize) {
            val chunk = bytes.sliceArray(i until minOf(i + chunkSize, bytes.size))
            wChar.value = chunk
            g.writeCharacteristic(wChar)
            delay(50)  // Give BLE stack time between chunks
        }
        lastActivity = System.currentTimeMillis()

        // Wait for response
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val resp = responseQueue.poll()
            if (resp != null) {
                return cleanResponse(resp, command)
            }
            delay(20)
        }

        // Timeout — return whatever we have
        val partial = responseBuffer.toString()
        responseBuffer.clear()
        return if (partial.isNotEmpty()) cleanResponse(partial, command) else ""
    }
}

// ─────────────────────────────────────────────────────────────────────
//  WiFi Connection
// ─────────────────────────────────────────────────────────────────────

class WiFiConnection(
    private val config: ConnectionConfig
) : ElmConnection() {

    private var socket: Socket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    override suspend fun connect() {
        withContext(Dispatchers.IO) {
        val host = config.address.ifEmpty { WIFI_DEFAULT_HOST }
        val port = config.port

        Log.i(TAG, "Connecting to WiFi ELM327: $host:$port")

        socket = Socket().apply {
            connect(InetSocketAddress(host, port), WIFI_CONNECT_TIMEOUT_MS)
            soTimeout = config.timeout.toInt()
        }
        inputStream = socket!!.getInputStream()
        outputStream = socket!!.getOutputStream()
        isConnected = true

        initializeAdapter()
        Log.i(TAG, "WiFi connected to $host:$port")
    }
    }

    override suspend fun disconnect() {
        withContext(Dispatchers.IO) {
        isConnected = false
        try { inputStream?.close() } catch (_: Exception) {}
        try { outputStream?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        inputStream = null
        outputStream = null
        socket = null
        Log.i(TAG, "WiFi disconnected")
    }
    }

    override suspend fun sendCommandInternal(command: String, timeoutMs: Long): String =
        withContext(Dispatchers.IO) {
            val os = outputStream ?: throw IOException("Not connected")
            val ins = inputStream ?: throw IOException("Not connected")

            while (ins.available() > 0) ins.read()

            os.write("$command\r".toByteArray(Charsets.US_ASCII))
            os.flush()
            lastActivity = System.currentTimeMillis()

            val buffer = StringBuilder()
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (ins.available() > 0) {
                    val b = ins.read()
                    if (b == -1) break
                    val c = b.toChar()
                    buffer.append(c)
                    if (c == '>') break
                } else {
                    delay(10)
                }
            }
            cleanResponse(buffer.toString(), command)
        }
}

// ─────────────────────────────────────────────────────────────────────
//  Adapter Discovery
// ─────────────────────────────────────────────────────────────────────

@SuppressLint("MissingPermission")
object AdapterScanner {

    /**
     * Scan for paired Bluetooth devices that look like OBD adapters.
     */
    fun findBluetoothAdapters(context: Context): List<DetectedAdapter> {
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            ?: return emptyList()
        val btAdapter = btManager.adapter ?: return emptyList()

        val bondedDevices = btAdapter.bondedDevices ?: return emptyList()
        val adapters = mutableListOf<DetectedAdapter>()

        for (device in bondedDevices) {
            val name = device.name?.uppercase() ?: continue
            if (name.contains("OBD") || name.contains("ELM") ||
                name.contains("OBDLINK") || name.contains("VEEPEAK") ||
                name.contains("VGATE") || name.contains("KONNWEI") ||
                name.contains("BIMMER") || name.contains("CARISTA") ||
                name.contains("AUTOPHIX") || name.contains("SCAN")) {

                val type = if (device.type == BluetoothDevice.DEVICE_TYPE_LE) {
                    ConnectionType.BLUETOOTH_LE
                } else {
                    ConnectionType.BLUETOOTH_CLASSIC
                }

                adapters.add(DetectedAdapter(
                    name = device.name ?: "Unknown",
                    address = device.address,
                    type = type,
                    bluetoothDevice = device
                ))
            }
        }

        Log.i(TAG, "Found ${adapters.size} potential OBD adapters in paired devices")
        return adapters
    }

    /**
     * Create a connection for a detected adapter.
     */
    fun createConnection(context: Context, adapter: DetectedAdapter): ElmConnection {
        val config = ConnectionConfig(type = adapter.type, address = adapter.address)
        return when (adapter.type) {
            ConnectionType.BLUETOOTH_CLASSIC -> {
                BluetoothClassicConnection(adapter.bluetoothDevice!!, config)
            }
            ConnectionType.BLUETOOTH_LE -> {
                BleConnection(context, adapter.bluetoothDevice!!, config)
            }
            ConnectionType.WIFI -> {
                WiFiConnection(config)
            }
        }
    }

    /**
     * Create a WiFi connection with default or custom address.
     */
    fun createWifiConnection(host: String = WIFI_DEFAULT_HOST, port: Int = WIFI_DEFAULT_PORT): ElmConnection {
        return WiFiConnection(ConnectionConfig(
            type = ConnectionType.WIFI,
            address = host,
            port = port
        ))
    }
}
