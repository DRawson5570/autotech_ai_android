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
import java.util.concurrent.TimeUnit

private const val TAG = "GatewayTunnel"

private const val TUNNEL_URL = "wss://automotive.aurora-sentient.net/api/scan_tool/gateway/tunnel"
private const val INITIAL_BACKOFF_MS = 5000L
private const val MAX_BACKOFF_MS = 60000L
private const val BACKOFF_FACTOR = 1.5

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

    @Volatile
    var isRegistered = false
        private set

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

                    Log.d(TAG, "Request: $method $path")

                    val response = handleRequest(method, path, body)

                    val resp = JsonObject().apply {
                        addProperty("type", "response")
                        addProperty("id", id)
                        addProperty("status", response.status)
                        add("body", response.body)
                    }
                    ws.send(resp.toString())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling message: ${e.message}")
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
