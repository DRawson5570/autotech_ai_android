package net.aurorasentient.autotechgateway.tunnel

/**
 * WebSocket tunnel to the Autotech AI production server.
 *
 * Port of reverse_tunnel.py — maintains a persistent WebSocket connection to
 * wss://automotive.aurora-sentient.net/api/scan_tool/gateway/tunnel
 * and relays requests/responses.
 */

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import net.aurorasentient.autotechgateway.AutotechApp
import net.aurorasentient.autotechgateway.elm.*
import okhttp3.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private const val TAG = "GatewayTunnel"

private const val TUNNEL_URL = "wss://automotive.aurora-sentient.net/api/scan_tool/gateway/tunnel"
private const val INITIAL_BACKOFF_MS = 5000L
private const val MAX_BACKOFF_MS = 60000L
private const val BACKOFF_FACTOR = 1.5
private const val MAX_CONCURRENT = 4

class GatewayTunnel(
    private val shopId: String,
    private val apiKey: String,
    private val protocol: OBDProtocol,
    private val connection: ElmConnection
) {
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .pingInterval(60, TimeUnit.SECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MINUTES)  // No read timeout for WebSocket
        .build()

    private var webSocket: WebSocket? = null
    private var backoffMs = INITIAL_BACKOFF_MS
    private var running = false
    private var scope: CoroutineScope? = null

    // Request lifecycle management (mirrors reverse_tunnel.py v1.2.47+)
    private val activeTasks = ConcurrentHashMap<String, Job>()
    private val cancelledIds = ConcurrentHashMap.newKeySet<String>()

    // Sniffer session state
    private var snifferSession: CanSnifferSession? = null
    private var snifferJob: Job? = null
    private var broadcastDecoder: LiveBroadcastDecoder? = null

    @Volatile
    var isRegistered = false
        private set

    /** Callback invoked when a remote /restart request is received. */
    var onRestartRequested: (() -> Unit)? = null

    interface StatusListener {
        fun onTunnelConnected()
        fun onTunnelDisconnected()
        fun onTunnelRegistered()
        fun onTunnelError(message: String)
    }

    var statusListener: StatusListener? = null

    fun start(scope: CoroutineScope) {
        this.scope = scope
        running = true
        scope.launch(Dispatchers.IO) {
            connectLoop()
        }
    }

    fun stop() {
        running = false
        isRegistered = false
        webSocket?.close(1000, "App shutdown")
        webSocket = null
    }

    private suspend fun connectLoop() {
        while (running) {
            try {
                connect()
            } catch (e: Exception) {
                Log.e(TAG, "Tunnel connection error: ${e.message}")
                statusListener?.onTunnelError(e.message ?: "Connection error")
            }

            if (!running) break

            Log.i(TAG, "Reconnecting in ${backoffMs}ms...")
            delay(backoffMs)
            backoffMs = (backoffMs * BACKOFF_FACTOR).toLong().coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    private suspend fun connect() {
        val request = Request.Builder()
            .url(TUNNEL_URL)
            .build()

        val latch = CompletableDeferred<Unit>()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connected, registering as '$shopId'...")
                backoffMs = INITIAL_BACKOFF_MS
                statusListener?.onTunnelConnected()

                // Send registration
                val reg = JsonObject().apply {
                    addProperty("type", "register")
                    addProperty("shop_id", shopId)
                    addProperty("api_key", apiKey)
                    addProperty("version", AutotechApp.VERSION)
                    addProperty("platform", "android")
                }
                ws.send(reg.toString())
            }

            override fun onMessage(ws: WebSocket, text: String) {
                scope?.launch(Dispatchers.IO) {
                    handleMessage(ws, text)
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}")
                isRegistered = false
                statusListener?.onTunnelDisconnected()
                latch.complete(Unit)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed: $code $reason")
                isRegistered = false
                statusListener?.onTunnelDisconnected()
                latch.complete(Unit)
            }
        })

        latch.await()
    }

    private suspend fun handleMessage(ws: WebSocket, text: String) {
        try {
            val msg = JsonParser.parseString(text).asJsonObject
            val type = msg.get("type")?.asString ?: return

            when (type) {
                "registered" -> {
                    isRegistered = true
                    Log.i(TAG, "Registered with server as '$shopId'")
                    statusListener?.onTunnelRegistered()
                }

                "ping" -> {
                    ws.send("""{"type":"pong"}""")
                }

                "request" -> {
                    val id = msg.get("id")?.asString ?: return
                    val method = msg.get("method")?.asString ?: "GET"
                    val path = msg.get("path")?.asString ?: "/"
                    val body = msg.get("body")?.asJsonObject
                    val deadline = msg.get("deadline")?.asDouble ?: 0.0

                    Log.d(TAG, "← Request $id: $method $path")

                    // Check if already expired before starting
                    if (deadline > 0 && System.currentTimeMillis() / 1000.0 > deadline) {
                        Log.i(TAG, "⏭ Dropping expired request $id: $method $path")
                        val resp = JsonObject().apply {
                            addProperty("type", "response")
                            addProperty("id", id)
                            addProperty("status", 408)
                            add("body", errorJson("Request expired before delivery"))
                        }
                        ws.send(resp.toString())
                        return
                    }

                    // Check concurrent request limit
                    if (activeTasks.size >= MAX_CONCURRENT) {
                        Log.w(TAG, "⚠ Rejecting request $id: ${activeTasks.size} already in flight")
                        val resp = JsonObject().apply {
                            addProperty("type", "response")
                            addProperty("id", id)
                            addProperty("status", 429)
                            add("body", errorJson("Gateway busy: ${activeTasks.size} requests in flight"))
                        }
                        ws.send(resp.toString())
                        return
                    }

                    // Run in background and track
                    val job = scope?.launch(Dispatchers.IO) {
                        handleRequestBackground(ws, id, method, path, body, deadline)
                    }
                    if (job != null) {
                        activeTasks[id] = job
                        job.invokeOnCompletion { activeTasks.remove(id) }
                    }
                }

                "cancel" -> {
                    // Proxy timed out — cancel the in-flight request
                    val id = msg.get("id")?.asString ?: return
                    Log.i(TAG, "✋ Cancel received for $id")
                    cancelledIds.add(id)
                    val task = activeTasks[id]
                    if (task != null && task.isActive) {
                        task.cancel()
                        Log.i(TAG, "Cancelled active task $id")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling message: ${e.message}")
        }
    }

    /**
     * Handle a request in a background task with deadline/cancel awareness.
     * Mirrors reverse_tunnel.py _handle_request_background().
     */
    private suspend fun handleRequestBackground(
        ws: WebSocket, id: String, method: String, path: String, body: JsonObject?, deadline: Double
    ) {
        try {
            // Check if already cancelled
            if (id in cancelledIds) {
                cancelledIds.remove(id)
                Log.i(TAG, "⏭ Skipping cancelled request $id: $method $path")
                return
            }

            // Check if deadline already passed
            if (deadline > 0 && System.currentTimeMillis() / 1000.0 > deadline) {
                Log.i(TAG, "⏭ Skipping expired request $id: $method $path")
                val resp = JsonObject().apply {
                    addProperty("type", "response")
                    addProperty("id", id)
                    addProperty("status", 408)
                    add("body", errorJson("Request expired before processing"))
                }
                ws.send(resp.toString())
                return
            }

            val response = handleRequest(method, path, body)

            // If cancelled during execution, still send the response (it's done) but log it
            if (id in cancelledIds) {
                cancelledIds.remove(id)
                Log.i(TAG, "Request $id completed but was cancelled during execution")
            }

            val resp = JsonObject().apply {
                addProperty("type", "response")
                addProperty("id", id)
                addProperty("status", response.status)
                add("body", response.body)
            }
            ws.send(resp.toString())
            Log.d(TAG, "→ Response $id: ${response.status}")
        } catch (e: CancellationException) {
            Log.i(TAG, "✋ Request $id cancelled: $method $path")
        } catch (e: Exception) {
            Log.e(TAG, "Request $id failed: ${e.message}")
        }
    }

    /**
     * Handle a relayed request from the server.
     * Maps server API paths to local protocol calls.
     */
    private suspend fun handleRequest(method: String, path: String, body: JsonObject?): TunnelResponse {
        return try {
            when {
                path == "/" || path == "/status" -> {
                    val status = JsonObject().apply {
                        addProperty("status", "connected")
                        addProperty("adapter", connection.adapterName)
                        addProperty("version", AutotechApp.VERSION)
                        addProperty("platform", "android")
                    }
                    TunnelResponse(200, status)
                }

                path == "/vin" -> {
                    val vin = protocol.readVin()
                    val result = JsonObject().apply {
                        addProperty("vin", vin ?: "")
                        if (vin != null) {
                            val info = VINDecoder.decode(vin)
                            addProperty("year", info.year)
                            addProperty("make", info.make)
                        }
                    }
                    TunnelResponse(200, result)
                }

                path == "/dtcs" -> {
                    val dtcs = protocol.readAllDtcs()
                    val result = JsonObject().apply {
                        addProperty("count", dtcs.size)
                        val arr = com.google.gson.JsonArray()
                        for (dtc in dtcs) {
                            val obj = JsonObject().apply {
                                addProperty("code", dtc.code)
                                addProperty("status", dtc.status)
                            }
                            arr.add(obj)
                        }
                        add("dtcs", arr)
                    }
                    TunnelResponse(200, result)
                }

                path == "/clear-dtcs" -> {
                    val ok = protocol.clearDtcs()
                    val result = JsonObject().apply { addProperty("cleared", ok) }
                    TunnelResponse(200, result)
                }

                path == "/modules" -> {
                    val vin = protocol.readVin()
                    val modules = protocol.discoverModules(vin)
                    val result = JsonObject()
                    val arr = com.google.gson.JsonArray()
                    for (mod in modules) {
                        val obj = JsonObject().apply {
                            addProperty("name", mod.name)
                            addProperty("address", "0x${mod.address.toString(16).uppercase()}")
                            addProperty("bus", mod.bus)
                        }
                        arr.add(obj)
                    }
                    result.add("modules", arr)
                    result.addProperty("count", modules.size)
                    TunnelResponse(200, result)
                }

                path == "/read-did" -> {
                    val moduleAddr = body?.get("module_addr")?.asString?.removePrefix("0x")
                        ?.toIntOrNull(16) ?: return TunnelResponse(400, errorJson("Missing module_addr"))
                    val didsStr = body.get("dids")?.asString ?: return TunnelResponse(400, errorJson("Missing dids"))
                    val bus = body.get("bus")?.asString ?: "HS-CAN"

                    val didList = didsStr.split(",").map { it.trim() }
                    val results = JsonObject()

                    for (didHex in didList) {
                        val did = didHex.toIntOrNull(16) ?: continue
                        val result = protocol.readDid(moduleAddr, did, bus)
                        if (result != null) {
                            val obj = JsonObject().apply {
                                addProperty("raw", result.rawHex)
                                addProperty("decoded", result.decoded)
                                addProperty("description", result.description)
                            }
                            results.add(result.did, obj)
                        }
                    }

                    val response = JsonObject().apply {
                        addProperty("module", "0x${moduleAddr.toString(16).uppercase()}")
                        add("dids", results)
                    }
                    TunnelResponse(200, response)
                }

                path == "/pids" -> {
                    val pidsStr = body?.get("pids")?.asString ?: return TunnelResponse(400, errorJson("Missing pids"))
                    val pidNames = pidsStr.split(",").map { it.trim() }
                    val values = protocol.readPids(pidNames)

                    val result = JsonObject()
                    for ((name, value) in values) {
                        val def = PIDRegistry.resolve(name)
                        val obj = JsonObject().apply {
                            addProperty("value", value)
                            addProperty("unit", def?.unit ?: "")
                        }
                        result.add(name, obj)
                    }
                    TunnelResponse(200, result)
                }

                path == "/uds-raw" -> {
                    val moduleAddr = body?.get("module_addr")?.asString?.removePrefix("0x")
                        ?.toIntOrNull(16) ?: return TunnelResponse(400, errorJson("Missing module_addr"))
                    val command = body.get("command")?.asString ?: return TunnelResponse(400, errorJson("Missing command"))
                    val bus = body.get("bus")?.asString ?: "HS-CAN"

                    val resp = protocol.sendUdsRaw(moduleAddr, command, bus)
                    val result = JsonObject().apply {
                        addProperty("response", resp)
                        addProperty("module", "0x${moduleAddr.toString(16).uppercase()}")
                    }
                    TunnelResponse(200, result)
                }

                path == "/snapshot" -> {
                    val snapshot = protocol.captureSnapshot()
                    val result = JsonObject().apply {
                        addProperty("vin", snapshot.vin)
                        val dtcArr = com.google.gson.JsonArray()
                        for (dtc in snapshot.dtcs) {
                            val obj = JsonObject().apply {
                                addProperty("code", dtc.code)
                                addProperty("status", dtc.status)
                            }
                            dtcArr.add(obj)
                        }
                        add("dtcs", dtcArr)
                        val pidObj = JsonObject()
                        for ((name, value) in snapshot.pids) {
                            pidObj.addProperty(name, value)
                        }
                        add("pids", pidObj)
                    }
                    TunnelResponse(200, result)
                }

                path == "/version" -> {
                    val result = JsonObject().apply {
                        addProperty("version", AutotechApp.VERSION)
                        addProperty("platform", "android")
                        addProperty("adapter", connection.adapterName)
                    }
                    TunnelResponse(200, result)
                }

                path == "/reset-adapter" -> {
                    connection.sendCommand("ATZ", 10000)
                    delay(2000)
                    connection.sendCommand("ATE0")
                    connection.sendCommand("ATL0")
                    connection.sendCommand("ATS0")
                    connection.sendCommand("ATSP0")
                    val result = JsonObject().apply { addProperty("reset", true) }
                    TunnelResponse(200, result)
                }

                path == "/reconnect-adapter" -> {
                    // Force-close and reopen the connection (mirrors server.py v1.2.48+).
                    // Unlike /reset-adapter which sends ATZ through the existing
                    // connection (and can hang if the transport is stuck), this
                    // tears down BT/WiFi entirely and reconnects from scratch.
                    Log.w(TAG, "🔌 Force-reconnecting adapter...")
                    connection.forceReconnect()
                    val result = JsonObject().apply {
                        addProperty("status", "reconnected")
                        addProperty("adapter", connection.adapterName)
                    }
                    TunnelResponse(200, result)
                }

                path == "/restart" -> {
                    // Remotely restart the gateway service.
                    // On Android, we invoke the restart callback which
                    // disconnects + restarts the foreground service.
                    Log.w(TAG, "🔄 Remote restart requested")
                    val callback = onRestartRequested
                    if (callback != null) {
                        // Respond first, then trigger restart
                        scope?.launch {
                            delay(2000)
                            callback()
                        }
                        val result = JsonObject().apply {
                            addProperty("status", "restarting")
                            addProperty("message", "Gateway will restart in ~2 seconds")
                        }
                        TunnelResponse(200, result)
                    } else {
                        TunnelResponse(500, errorJson("Restart not supported in this context"))
                    }
                }

                // ── Sniffer (passive CAN bus capture) ──

                path == "/sniff/start" -> {
                    if (snifferSession?.running == true) {
                        TunnelResponse(409, errorJson("Sniffer already running"))
                    } else if (!protocol.capabilities.supportsSTMA) {
                        TunnelResponse(400, errorJson("Adapter does not support STMA"))
                    } else {
                        val bus = body?.get("bus")?.asString ?: "HS-CAN"
                        val vin = body?.get("vin")?.asString ?: ""
                        val make = body?.get("make")?.asString ?: ""

                        // Load DBC database for broadcast decoding
                        val context = AutotechApp.instance
                        var dbcDb: DBCDatabase? = null
                        var dbcSource = ""
                        if (vin.isNotEmpty()) {
                            dbcDb = loadDbcForVin(context, vin)
                            dbcSource = "VIN $vin"
                        } else if (make.isNotEmpty()) {
                            dbcDb = loadDbcForOem(context, make)
                            dbcSource = "make $make"
                        }

                        broadcastDecoder = if (dbcDb != null && dbcDb.totalMessages > 0) {
                            Log.i(TAG, "DBC decoder loaded from $dbcSource: ${dbcDb.totalMessages} msgs")
                            LiveBroadcastDecoder(dbcDb)
                        } else {
                            Log.i(TAG, "No DBC files loaded — broadcast frames not decoded")
                            null
                        }

                        snifferSession = CanSnifferSession(bus).apply { setRunning(true) }

                        // Launch sniffer coroutine
                        snifferJob = scope?.launch(Dispatchers.IO) {
                            try {
                                protocol.startCanMonitor { timestamp, arbIdStr, dataStr ->
                                    val frame = parseStmaLine("$arbIdStr $dataStr")
                                    if (frame != null) {
                                        snifferSession?.addFrame(frame)
                                        // Auto-decode broadcast frames via DBC
                                        broadcastDecoder?.decodeFrame(
                                            frame.arbId, frame.data, frame.timestamp
                                        )
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Sniffer error: ${e.message}")
                            } finally {
                                snifferSession?.setRunning(false)
                            }
                        }

                        val result = JsonObject().apply {
                            addProperty("status", "started")
                            addProperty("bus", bus)
                            addProperty("dbc_loaded", broadcastDecoder != null)
                            broadcastDecoder?.getStats()?.let { add("dbc_info", it) }
                            addProperty("message", "Sniffer running. Adapter is in passive listen mode." +
                                if (broadcastDecoder != null) " DBC decoder active ($dbcSource)." else "")
                        }
                        TunnelResponse(200, result)
                    }
                }

                path == "/sniff/stop" -> {
                    val session = snifferSession
                    if (session == null) {
                        TunnelResponse(400, errorJson("No active sniffer session"))
                    } else {
                        // Stop the monitor
                        protocol.stopCanMonitor()
                        snifferJob?.join()
                        snifferJob = null

                        // Extract exchanges
                        session.extractExchanges()
                        val didData = session.getDidData()
                        val summary = session.toSummary()
                        val labels = session.getLabels()

                        val result = JsonObject().apply {
                            addProperty("status", "stopped")
                            addProperty("frame_count", summary["frame_count"] as Number)
                            addProperty("exchange_count", summary["exchange_count"] as Number)
                            addProperty("unique_modules", summary["unique_modules"] as Number)
                            addProperty("bus", session.bus)

                            // DID data grouped by module
                            val didObj = JsonObject()
                            for ((module, dids) in didData) {
                                val moduleObj = JsonObject()
                                for ((did, data) in dids) {
                                    val didInfo = JsonObject().apply {
                                        addProperty("data", data)
                                    }
                                    moduleObj.add(did, didInfo)
                                }
                                didObj.add(module, moduleObj)
                            }
                            add("did_data", didObj)

                            // Labels
                            val labelsObj = JsonObject()
                            for ((key, label) in labels) {
                                labelsObj.addProperty(key, label)
                            }
                            add("labels", labelsObj)

                            // Include final DBC-decoded broadcast snapshot
                            broadcastDecoder?.let { decoder ->
                                val broadcast = JsonObject().apply {
                                    add("key_signals", decoder.getKeySignals())
                                    add("all_signals", decoder.getSnapshot())
                                    add("stats", decoder.getStats())
                                }
                                add("broadcast", broadcast)
                            }
                        }

                        snifferSession = null
                        TunnelResponse(200, result)
                    }
                }

                path == "/sniff/frames" -> {
                    val session = snifferSession
                    if (session == null) {
                        TunnelResponse(400, errorJson("No active sniffer session"))
                    } else {
                        session.extractExchanges()
                        val didData = session.getDidData()
                        val summary = session.toSummary()
                        val labels = session.getLabels()

                        val result = JsonObject().apply {
                            addProperty("running", session.running)
                            addProperty("frame_count", summary["frame_count"] as Number)
                            addProperty("exchange_count", summary["exchange_count"] as Number)
                            addProperty("unique_modules", summary["unique_modules"] as Number)
                            addProperty("bus", session.bus)

                            val didObj = JsonObject()
                            for ((module, dids) in didData) {
                                val moduleObj = JsonObject()
                                for ((did, data) in dids) {
                                    val didInfo = JsonObject().apply {
                                        addProperty("data", data)
                                    }
                                    moduleObj.add(did, didInfo)
                                }
                                didObj.add(module, moduleObj)
                            }
                            add("did_data", didObj)

                            val labelsObj = JsonObject()
                            for ((key, label) in labels) {
                                labelsObj.addProperty(key, label)
                            }
                            add("labels", labelsObj)

                            // Include DBC-decoded broadcast data if decoder is active
                            broadcastDecoder?.let { decoder ->
                                val broadcast = JsonObject().apply {
                                    add("key_signals", decoder.getKeySignals())
                                    add("stats", decoder.getStats())
                                }
                                add("broadcast", broadcast)
                            }
                        }
                        TunnelResponse(200, result)
                    }
                }

                path == "/sniff/live" -> {
                    val session = snifferSession
                    val decoder = broadcastDecoder
                    if (session == null) {
                        TunnelResponse(400, errorJson("No active sniffer session"))
                    } else if (decoder == null) {
                        TunnelResponse(400, errorJson("No DBC decoder loaded. Start sniffer with VIN or make."))
                    } else {
                        val result = JsonObject().apply {
                            addProperty("running", session.running)
                            add("key_signals", decoder.getKeySignals())
                            add("all_signals", decoder.getSnapshot())
                            add("stats", decoder.getStats())
                        }
                        TunnelResponse(200, result)
                    }
                }

                path == "/sniff/label" -> {
                    val session = snifferSession
                    if (session == null) {
                        TunnelResponse(400, errorJson("No active sniffer session"))
                    } else {
                        val moduleStr = body?.get("module")?.asString
                        val didStr = body?.get("did")?.asString
                        val labelStr = body?.get("label")?.asString

                        if (moduleStr == null) {
                            TunnelResponse(400, errorJson("Missing module"))
                        } else if (didStr == null) {
                            TunnelResponse(400, errorJson("Missing did"))
                        } else if (labelStr == null) {
                            TunnelResponse(400, errorJson("Missing label"))
                        } else {
                            val normModule = moduleStr.trim().uppercase().let { m ->
                                if (m.startsWith("0X")) "0x${m.removePrefix("0X")}" else "0x$m"
                            }
                            val normDid = didStr.trim().uppercase()

                            session.addLabel(normModule, normDid, labelStr.trim())

                            val result = JsonObject().apply {
                                addProperty("status", "labeled")
                                addProperty("module", normModule)
                                addProperty("did", normDid)
                                addProperty("label", labelStr)
                                addProperty("total_labels", session.getLabels().size as Number)
                            }
                            TunnelResponse(200, result)
                        }
                    }
                }

                else -> {
                    TunnelResponse(404, errorJson("Unknown path: $path"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling $method $path: ${e.message}")
            TunnelResponse(500, errorJson(e.message ?: "Internal error"))
        }
    }

    private fun errorJson(message: String): JsonObject {
        return JsonObject().apply { addProperty("error", message) }
    }

    data class TunnelResponse(val status: Int, val body: JsonObject)
}
