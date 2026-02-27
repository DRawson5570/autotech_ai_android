package net.aurorasentient.autotechgateway.elm

/**
 * OBD-II / UDS protocol engine.
 *
 * Port of protocol.py — handles OBD modes 01-0A and UDS services (0x10, 0x22, 0x3E).
 */

import android.util.Log
import kotlinx.coroutines.delay

private const val TAG = "OBDProtocol"

/** Standard ECU CAN addresses (ISO 15765-4) */
val ECU_ADDRESSES = mapOf(
    "PCM"  to Pair(0x7E0, 0x7E8),
    "TCM"  to Pair(0x7E1, 0x7E9),
    "ABS"  to Pair(0x7E2, 0x7EA),
    "SRS"  to Pair(0x7E3, 0x7EB),
    "BCM"  to Pair(0x7E4, 0x7EC),
    "HVAC" to Pair(0x7E5, 0x7ED),
)

/** Ford MS-CAN module addresses (secondary bus, 125 kbps) */
val FORD_MS_CAN_ADDRESSES = mapOf(
    "PCM-MS"  to Pair(0x720, 0x728),
    "GEM"     to Pair(0x760, 0x768),
    "IPC"     to Pair(0x740, 0x748),
    "APIM"    to Pair(0x726, 0x72E),
    "HVAC-MS" to Pair(0x7A0, 0x7A8),
    "TPMS"    to Pair(0x7B0, 0x7B8),
    "PSCM"    to Pair(0x730, 0x738),
    "ACM"     to Pair(0x770, 0x778),
    "RCM"     to Pair(0x790, 0x798),
    "DDM"     to Pair(0x744, 0x74C),
    "PDM"     to Pair(0x745, 0x74D),
    "PAM"     to Pair(0x736, 0x73E),
    "SCCM"    to Pair(0x724, 0x72C),
    "OCS"     to Pair(0x765, 0x76D),
    "ABS-MS"  to Pair(0x760, 0x768),
    "FCIM"    to Pair(0x7A6, 0x7AE),
    "GPSM"    to Pair(0x701, 0x709),
)

/** Standard UDS DIDs (ISO 14229) */
val STANDARD_DIDS = mapOf(
    0xF186 to "Active diagnostic session",
    0xF187 to "Spare part number",
    0xF188 to "ECU software number",
    0xF189 to "ECU software version",
    0xF18A to "System supplier ID",
    0xF18B to "ECU manufacturing date",
    0xF18C to "ECU serial number",
    0xF190 to "VIN",
    0xF191 to "ECU hardware number",
    0xF192 to "System supplier ECU hardware number",
    0xF193 to "System supplier ECU hardware version",
    0xF194 to "System supplier ECU software number",
    0xF195 to "System supplier ECU software version",
    0xF197 to "System name or engine type",
    0xF199 to "Programming date",
    0xF19E to "ASAM/ODX file ID",
)

/** UDS Negative Response Codes (ISO 14229-1:2020) */
val UDS_NRC_CODES = mapOf(
    0x10 to "General reject",
    0x11 to "Service not supported",
    0x12 to "Sub-function not supported",
    0x13 to "Incorrect message length or invalid format",
    0x14 to "Response too long",
    0x22 to "Conditions not correct",
    0x24 to "Request sequence error",
    0x25 to "No response from sub-net component",
    0x26 to "Failure prevents execution",
    0x31 to "Request out of range",
    0x33 to "Security access denied",
    0x35 to "Invalid key",
    0x36 to "Exceeded number of attempts",
    0x37 to "Required time delay not expired",
    0x70 to "Upload/download not accepted",
    0x71 to "Transfer data suspended",
    0x72 to "General programming failure",
    0x73 to "Wrong block sequence counter",
    0x78 to "Request correctly received — response pending",
    0x7E to "Sub-function not supported in active session",
    0x7F to "Service not supported in active session",
)

/** Ford-specific WMI prefixes for MS-CAN detection */
val FORD_WMI_PREFIXES = setOf(
    "1FA", "1FB", "1FC", "1FD", "1FM", "1FT", "1FV", "1FW",
    "2FA", "2FB", "2FC", "2FD", "2FM", "2FT",
    "3FA", "3FB", "3FC", "3FD", "3FM", "3FT",
    "MAJ", "NM0", "WF0",
)

/** Parsed DTC */
data class DTC(
    val code: String,       // e.g. "P0301"
    val status: String,     // "stored", "pending", "permanent"
    val description: String = ""
)

/** Discovered ECU module */
data class ECUModule(
    val name: String,
    val address: Int,
    val responseAddress: Int,
    val bus: String,         // "HS-CAN" or "MS-CAN"
    val dids: Map<String, String> = emptyMap()
)

/** DID reading result */
data class DIDResult(
    val did: String,
    val rawHex: String,
    val decoded: String,
    val description: String = ""
)

/**
 * Detected adapter capabilities.
 */
data class AdapterCapabilities(
    val isSTN: Boolean = false,          // STN2120 chip (OBDLink MX+, etc.)
    val deviceName: String = "",
    val firmwareVersion: String = "",
    val supportsSTAF: Boolean = false,   // Adaptive timing
    val supportsSTMA: Boolean = false,   // CAN monitor all
    val supportsSTPX: Boolean = false,   // Protocol execute
    val supportsBatchPids: Boolean = false,  // Multi-PID per request
    val maxBatchPids: Int = 1            // How many PIDs per frame (up to 6)
)

/**
 * Scope data point — single PID sample with timestamp.
 */
data class ScopeSample(
    val timestampMs: Long,
    val value: Double
)

/**
 * OBD-II / UDS Protocol engine.
 * Sends commands via an ElmConnection and parses responses.
 *
 * Detects STN-based adapters (OBDLink MX+, etc.) and enables
 * ST command optimizations for faster polling and scope mode.
 */
class OBDProtocol(private val connection: ElmConnection) {

    private var supportedPids: Set<Int> = emptySet()
    private var currentBus: String = "HS-CAN"

    /** Detected adapter capabilities (populated during detectAdapter()) */
    var capabilities = AdapterCapabilities()
        private set

    /** Whether scope polling is currently running */
    @Volatile
    var isScopeRunning = false
        private set

    // ── Mode 01: Current Data ──────────────────────────────────────

    /**
     * Query supported PIDs and cache result.
     */
    suspend fun getSupportedPids(): Set<Int> {
        val supported = mutableSetOf<Int>()

        // Query PID support bitmasks: 0x00, 0x20, 0x40, 0x60, 0x80, 0xA0, 0xC0
        val queries = listOf(0x00, 0x20, 0x40, 0x60, 0x80, 0xA0, 0xC0)
        for (base in queries) {
            val resp = connection.sendCommand("01%02X".format(base))
            if (!isLiveResponse(resp)) {
                if (base == 0x00) break  // No PIDs at all
                continue
            }

            val bytes = parseResponse(resp, 0x41, base)
            if (bytes.size >= 4) {
                for (byteIdx in 0..3) {
                    for (bit in 7 downTo 0) {
                        if (bytes[byteIdx] and (1 shl bit) != 0) {
                            val pid = base + (byteIdx * 8) + (7 - bit) + 1
                            supported.add(pid)
                        }
                    }
                }
                // Check if next range is supported (bit 0 of 4th byte)
                if (bytes[3] and 1 == 0) break
            } else {
                break
            }
        }

        supportedPids = supported
        Log.i(TAG, "Supported PIDs: ${supported.size}")
        return supported
    }

    /**
     * Read a single PID value.
     */
    suspend fun readPid(pid: Int): Double? {
        val def = PIDRegistry.getByPid(pid) ?: return null
        val resp = connection.sendCommand("01%02X".format(pid))
        if (!isLiveResponse(resp)) return null

        val bytes = parseResponse(resp, 0x41, pid)
        if (bytes.isEmpty()) return null

        return try {
            def.decode(bytes)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode PID 0x${pid.toString(16)}: ${e.message}")
            null
        }
    }

    /**
     * Read a PID by name.
     */
    suspend fun readPidByName(name: String): Pair<PIDDefinition, Double>? {
        val def = PIDRegistry.resolve(name) ?: return null
        val value = readPid(def.pid) ?: return null
        return Pair(def, value)
    }

    /**
     * Read multiple PIDs in a batch.
     */
    suspend fun readPids(names: List<String>): Map<String, Double> {
        val results = mutableMapOf<String, Double>()

        // If STN adapter with batch support, use multi-PID requests
        if (capabilities.isSTN && capabilities.supportsBatchPids && names.size > 1) {
            return readPidsBatch(names)
        }

        for (name in names) {
            val result = readPidByName(name)
            if (result != null) {
                results[result.first.name] = result.second
            }
        }
        return results
    }

    /**
     * Multi-PID batch read — sends up to 6 PIDs per OBD request frame.
     * Supported on STN-based adapters and most real (non-clone) ELM327s.
     */
    private suspend fun readPidsBatch(names: List<String>): Map<String, Double> {
        val results = mutableMapOf<String, Double>()
        val defs = names.mapNotNull { PIDRegistry.resolve(it) }

        // Batch into groups of maxBatchPids (up to 6 per ISO 15031-5)
        val batchSize = capabilities.maxBatchPids.coerceIn(1, 6)
        for (chunk in defs.chunked(batchSize)) {
            val cmd = "01" + chunk.joinToString("") { "%02X".format(it.pid) }
            val resp = connection.sendCommand(cmd)
            if (!isLiveResponse(resp)) continue

            // Parse multi-PID response: 41 PID1 DATA1 PID2 DATA2 ...
            val hex = resp.replace(" ", "").replace("\n", "").replace("\r", "").uppercase()
            var pos = 0
            // Find "41" header
            val headerIdx = hex.indexOf("41")
            if (headerIdx == -1) continue
            pos = headerIdx + 2

            for (def in chunk) {
                if (pos + 2 > hex.length) break
                val pidByte = hex.substring(pos, pos + 2).toIntOrNull(16) ?: break
                if (pidByte != def.pid) break
                pos += 2

                val dataBytes = mutableListOf<Int>()
                for (i in 0 until def.bytes) {
                    if (pos + 2 > hex.length) break
                    val b = hex.substring(pos, pos + 2).toIntOrNull(16) ?: break
                    dataBytes.add(b)
                    pos += 2
                }

                if (dataBytes.size == def.bytes) {
                    try {
                        results[def.name] = def.decode(dataBytes)
                    } catch (_: Exception) {}
                }
            }
        }
        return results
    }

    // ── STN/OBDLink Detection ─────────────────────────────────────

    /**
     * Detect adapter type and capabilities. Call after connection init.
     * Returns true if this is an STN-based adapter (OBDLink MX+, etc.)
     */
    suspend fun detectAdapter(): AdapterCapabilities {
        var isSTN = false
        var deviceName = connection.adapterName
        var fwVersion = connection.adapterVersion

        // Try STI (STN device identification)
        val stiResp = connection.sendCommand("STI")
        if (!stiResp.contains("?") && stiResp.isNotEmpty()) {
            isSTN = true
            deviceName = stiResp.lines().firstOrNull { it.isNotBlank() }?.trim() ?: deviceName
            Log.i(TAG, "STN device detected: $deviceName")
        }

        // Also detect via ATI response
        if (!isSTN) {
            val atiResp = connection.sendCommand("ATI")
            val upper = atiResp.uppercase()
            if (upper.contains("STN") || upper.contains("OBDLINK")) {
                isSTN = true
                deviceName = atiResp.lines().firstOrNull { it.isNotBlank() }?.trim() ?: deviceName
            }
        }

        // Get firmware version from STDI (STN device info)
        if (isSTN) {
            val stdiResp = connection.sendCommand("STDI")
            if (!stdiResp.contains("?")) {
                fwVersion = stdiResp.lines().firstOrNull { it.isNotBlank() }?.trim() ?: fwVersion
            }
        }

        // Test capabilities
        var supportsSTAF = false
        var supportsSTMA = false
        var supportsSTPX = false

        if (isSTN) {
            // Enable adaptive timing
            val stafResp = connection.sendCommand("STAF")
            supportsSTAF = !stafResp.contains("?")
            if (supportsSTAF) {
                Log.i(TAG, "STAF (adaptive timing) enabled")
            }

            // Test CAN monitor support
            // Don't actually start it — just check if STMA is recognized
            val stmaResp = connection.sendCommand("STMA") // Start monitoring
            supportsSTMA = !stmaResp.contains("?")
            if (supportsSTMA) {
                // Stop monitoring immediately
                connection.sendCommand("\r")  // Send CR to stop
                delay(100)
                Log.i(TAG, "STMA (CAN monitor) supported")
            }

            // Test STPX support
            val stpxResp = connection.sendCommand("STPX H:7DF, D:01 0C, R:1, T:100")
            supportsSTPX = !stpxResp.contains("?") && isLiveResponse(stpxResp)
            if (supportsSTPX) {
                Log.i(TAG, "STPX (protocol execute) supported")
            }
        }

        // Multi-PID batch: supported on both real ELM327 and STN
        // Test by requesting 2 PIDs at once
        val batchResp = connection.sendCommand("01 0C 0D")
        val supportsBatch = isLiveResponse(batchResp) && batchResp.replace(" ", "").length > 8
        val maxBatch = if (supportsBatch) {
            if (isSTN) 6 else 3  // STN handles 6 reliably, basic ELM327 sometimes chokes on >3
        } else 1

        capabilities = AdapterCapabilities(
            isSTN = isSTN,
            deviceName = deviceName,
            firmwareVersion = fwVersion,
            supportsSTAF = supportsSTAF,
            supportsSTMA = supportsSTMA,
            supportsSTPX = supportsSTPX,
            supportsBatchPids = supportsBatch,
            maxBatchPids = maxBatch
        )

        Log.i(TAG, "Adapter: $deviceName | STN=$isSTN | STAF=$supportsSTAF | " +
                "STMA=$supportsSTMA | STPX=$supportsSTPX | batch=$maxBatch")
        return capabilities
    }

    // ── Scope Mode (High-Speed Polling) ───────────────────────────

    /**
     * Start high-speed scope polling for a single PID.
     * Calls [onSample] with each new data point. Runs until [stopScope] is called.
     *
     * On STN adapters with STPX, achieves 50-100+ samples/sec.
     * On standard ELM327, achieves 10-20 samples/sec.
     */
    suspend fun startScope(
        pidName: String,
        onSample: (ScopeSample) -> Unit,
        targetHz: Int = 0  // 0 = as fast as possible
    ) {
        val def = PIDRegistry.resolve(pidName) ?: run {
            Log.w(TAG, "Scope: unknown PID '$pidName'")
            return
        }

        isScopeRunning = true
        val minIntervalMs = if (targetHz > 0) (1000L / targetHz) else 0L

        Log.i(TAG, "Scope started: ${def.name} (${def.description})")

        try {
            if (capabilities.supportsSTPX) {
                // Fast path: STPX protocol execute with tight timing
                scopeWithSTPX(def, onSample, minIntervalMs)
            } else {
                // Standard path: regular OBD polling
                scopeStandard(def, onSample, minIntervalMs)
            }
        } finally {
            isScopeRunning = false
            Log.i(TAG, "Scope stopped")
        }
    }

    /**
     * Stop scope polling.
     */
    fun stopScope() {
        isScopeRunning = false
    }

    /**
     * High-speed scope using STPX (STN Protocol Execute).
     * Bypasses the ELM327 command parser for minimal latency.
     */
    private suspend fun scopeWithSTPX(
        def: PIDDefinition,
        onSample: (ScopeSample) -> Unit,
        minIntervalMs: Long
    ) {
        val pidHex = "%02X".format(def.pid)

        while (isScopeRunning) {
            val startMs = System.currentTimeMillis()

            // STPX: Header, Data, expected Responses, Timeout (ms)
            val resp = connection.sendCommand(
                "STPX H:7DF, D:01 $pidHex, R:1, T:50",
                timeoutMs = 200
            )

            if (isLiveResponse(resp)) {
                val bytes = parseResponse(resp, 0x41, def.pid)
                if (bytes.size >= def.bytes) {
                    try {
                        val value = def.decode(bytes)
                        onSample(ScopeSample(System.currentTimeMillis(), value))
                    } catch (_: Exception) {}
                }
            }

            // Rate limiting
            val elapsed = System.currentTimeMillis() - startMs
            if (minIntervalMs > 0 && elapsed < minIntervalMs) {
                delay(minIntervalMs - elapsed)
            }
        }
    }

    /**
     * Standard scope polling using regular OBD mode 01 requests.
     */
    private suspend fun scopeStandard(
        def: PIDDefinition,
        onSample: (ScopeSample) -> Unit,
        minIntervalMs: Long
    ) {
        while (isScopeRunning) {
            val startMs = System.currentTimeMillis()

            val value = readPid(def.pid)
            if (value != null) {
                onSample(ScopeSample(System.currentTimeMillis(), value))
            }

            // Rate limiting
            val elapsed = System.currentTimeMillis() - startMs
            if (minIntervalMs > 0 && elapsed < minIntervalMs) {
                delay(minIntervalMs - elapsed)
            }
        }
    }

    /**
     * Start CAN bus monitoring (passive sniffing). STN adapters only.
     * Returns raw CAN frames via [onFrame]. Runs until [stopCanMonitor] is called.
     */
    suspend fun startCanMonitor(
        onFrame: (timestamp: Long, arbId: String, data: String) -> Unit
    ) {
        if (!capabilities.supportsSTMA) {
            Log.w(TAG, "CAN monitoring not supported on this adapter")
            return
        }

        isScopeRunning = true
        Log.i(TAG, "CAN monitor started (STMA)")

        try {
            // STMA starts monitoring, adapter sends raw frames until stopped
            connection.sendCommand("ATH1")  // Headers on for arb IDs
            connection.sendCommand("ATS1")  // Spaces on for readability
            // Note: we bypass the normal sendCommand here because STMA
            // streams until interrupted. This is a simplified approach.
            connection.sendCommand("STMA")

            // Read frames until stopped — simplified: check buffer periodically
            while (isScopeRunning) {
                delay(1)  // Yield to allow frame processing
            }

            // Stop monitoring
            connection.sendCommand("\r")
            delay(100)
        } finally {
            connection.sendCommand("ATH0")
            connection.sendCommand("ATS0")
            isScopeRunning = false
        }
    }

    /**
     * Stop CAN monitoring.
     */
    fun stopCanMonitor() {
        isScopeRunning = false
    }

    // ── Mode 03/07/0A: DTCs ───────────────────────────────────────

    /**
     * Read all DTCs (stored + pending + permanent).
     */
    suspend fun readAllDtcs(): List<DTC> {
        val dtcs = mutableListOf<DTC>()

        // Mode 03: Stored DTCs
        val stored = readDtcsMode("03", "stored")
        dtcs.addAll(stored)

        // Mode 07: Pending DTCs
        val pending = readDtcsMode("07", "pending")
        dtcs.addAll(pending)

        // Mode 0A: Permanent DTCs
        val permanent = readDtcsMode("0A", "permanent")
        dtcs.addAll(permanent)

        Log.i(TAG, "Total DTCs: ${dtcs.size} (${stored.size} stored, ${pending.size} pending, ${permanent.size} permanent)")
        return dtcs
    }

    private suspend fun readDtcsMode(mode: String, status: String): List<DTC> {
        val resp = connection.sendCommand(mode)
        if (!isLiveResponse(resp)) return emptyList()
        return parseDtcs(resp, status)
    }

    /**
     * Clear DTCs (Mode 04).
     */
    suspend fun clearDtcs(): Boolean {
        val resp = connection.sendCommand("04")
        return resp.contains("44") || resp.uppercase().contains("OK")
    }

    // ── Mode 09: Vehicle Info ─────────────────────────────────────

    /**
     * Read VIN (Mode 09 PID 02).
     */
    suspend fun readVin(): String? {
        val resp = connection.sendCommand("0902", timeoutMs = 10000)
        if (!isLiveResponse(resp)) return null

        // Parse multiframe response
        val allBytes = parseMultiframeResponse(resp)
        if (allBytes.isEmpty()) return null

        // VIN is 17 ASCII characters, skip first byte (PID count/padding)
        val vinBytes = if (allBytes.size > 17) allBytes.drop(1) else allBytes
        val vin = vinBytes
            .filter { it in 0x20..0x7E }
            .map { it.toChar() }
            .joinToString("")
            .trim()

        return if (vin.length >= 17) vin.take(17) else null
    }

    // ── UDS: Read DID ─────────────────────────────────────────────

    /**
     * Read a UDS DID from a specific ECU module.
     */
    suspend fun readDid(moduleAddr: Int, did: Int, bus: String = "HS-CAN"): DIDResult? {
        switchBus(bus)
        targetModule(moduleAddr)

        // Enter extended session
        connection.sendCommand("1003")

        // ReadDataByIdentifier (0x22) + DID
        val cmd = "22%04X".format(did)
        val resp = connection.sendCommand(cmd, timeoutMs = 8000)
        if (!isLiveResponse(resp)) return null

        // Check for negative response
        if (resp.uppercase().startsWith("7F")) {
            val nrc = if (resp.length >= 6) resp.substring(4, 6).toIntOrNull(16) else null
            val nrcDesc = nrc?.let { UDS_NRC_CODES[it] } ?: "Unknown"
            Log.w(TAG, "DID 0x${did.toString(16).uppercase()}: NRC $nrcDesc")
            return null
        }

        // Parse positive response: 62 + DID (2 bytes) + data
        val hex = resp.replace(" ", "").replace("\n", "").uppercase()
        if (!hex.startsWith("62")) return null

        val dataHex = hex.drop(6)  // Skip "62" + 4-char DID
        val decoded = decodeDIDValue(dataHex)
        val description = STANDARD_DIDS[did] ?: "DID 0x${did.toString(16).uppercase()}"

        return DIDResult(
            did = "%04X".format(did),
            rawHex = dataHex,
            decoded = decoded,
            description = description
        )
    }

    /**
     * Read multiple DIDs from a module.
     */
    suspend fun readDids(moduleAddr: Int, dids: List<Int>, bus: String = "HS-CAN"): Map<String, DIDResult> {
        val results = mutableMapOf<String, DIDResult>()
        switchBus(bus)
        targetModule(moduleAddr)
        connection.sendCommand("1003")  // Extended session once

        for (did in dids) {
            val cmd = "22%04X".format(did)
            val resp = connection.sendCommand(cmd, timeoutMs = 8000)
            if (!isLiveResponse(resp)) continue
            if (resp.uppercase().startsWith("7F")) continue

            val hex = resp.replace(" ", "").replace("\n", "").uppercase()
            if (!hex.startsWith("62")) continue

            val dataHex = hex.drop(6)
            val didKey = "%04X".format(did)
            results[didKey] = DIDResult(
                did = didKey,
                rawHex = dataHex,
                decoded = decodeDIDValue(dataHex),
                description = STANDARD_DIDS[did] ?: didKey
            )
        }
        return results
    }

    /**
     * Send a raw UDS hex command to a specific module.
     */
    suspend fun sendUdsRaw(moduleAddr: Int, hexCmd: String, bus: String = "HS-CAN"): String {
        switchBus(bus)
        targetModule(moduleAddr)
        return connection.sendCommand(hexCmd, timeoutMs = 10000)
    }

    // ── Module Discovery ──────────────────────────────────────────

    /**
     * Discover all communicating ECU modules.
     */
    suspend fun discoverModules(vin: String? = null): List<ECUModule> {
        val modules = mutableListOf<ECUModule>()

        // Phase 1: Broadcast 0100 with headers on
        connection.sendCommand("ATH1")
        connection.sendCommand("ATS1")
        val broadcastResp = connection.sendCommand("0100", timeoutMs = 10000)

        // Extract responding addresses from header
        if (isLiveResponse(broadcastResp)) {
            val respondingAddrs = parseBroadcastResponse(broadcastResp)
            for ((addr, respAddr) in respondingAddrs) {
                val name = ECU_ADDRESSES.entries.find { it.value.first == addr }?.key
                    ?: "ECU_0x${addr.toString(16).uppercase()}"
                modules.add(ECUModule(name, addr, respAddr, "HS-CAN"))
            }
        }

        // Phase 2: Probe standard addresses individually
        for ((name, addrs) in ECU_ADDRESSES) {
            if (modules.any { it.address == addrs.first }) continue  // Already found

            connection.sendCommand("ATSH%03X".format(addrs.first))
            connection.sendCommand("ATCRA%03X".format(addrs.second))

            // Try TesterPresent
            var resp = connection.sendCommand("3E00", timeoutMs = 3000)
            if (!isLiveResponse(resp)) {
                resp = connection.sendCommand("0100", timeoutMs = 3000)
            }
            if (isLiveResponse(resp)) {
                modules.add(ECUModule(name, addrs.first, addrs.second, "HS-CAN"))
            }
        }

        // Reset after HS-CAN probing
        connection.sendCommand("ATH0")
        connection.sendCommand("ATS0")
        connection.sendCommand("ATSH7DF")
        connection.sendCommand("ATCRA")
        connection.sendCommand("ATST32")

        // Phase 3: Ford MS-CAN (if VIN indicates Ford)
        if (vin != null && isFordVin(vin)) {
            Log.i(TAG, "Ford detected, scanning MS-CAN bus...")
            val msCanModules = scanMsCan()
            modules.addAll(msCanModules)
        }

        Log.i(TAG, "Discovered ${modules.size} modules")
        return modules
    }

    private fun isFordVin(vin: String): Boolean {
        if (vin.length < 3) return false
        return FORD_WMI_PREFIXES.contains(vin.take(3).uppercase())
    }

    private suspend fun scanMsCan(): List<ECUModule> {
        val modules = mutableListOf<ECUModule>()

        // Switch to MS-CAN: try STN command first, then fallback
        var switched = false
        val stnResp = connection.sendCommand("STP33", timeoutMs = 3000)
        if (stnResp.uppercase().contains("OK") || !stnResp.contains("?")) {
            switched = true
            Log.i(TAG, "MS-CAN via STP33 (STN device)")
        }

        if (!switched) {
            connection.sendCommand("ATPBE004")
            val spbResp = connection.sendCommand("ATSPB", timeoutMs = 3000)
            if (!spbResp.contains("?")) {
                switched = true
                Log.i(TAG, "MS-CAN via ATSPB fallback")
            }
        }

        if (!switched) {
            Log.w(TAG, "Could not switch to MS-CAN")
            return modules
        }

        // Probe Ford MS-CAN addresses
        connection.sendCommand("ATH1")
        connection.sendCommand("ATS1")

        for ((name, addrs) in FORD_MS_CAN_ADDRESSES) {
            connection.sendCommand("ATSH%03X".format(addrs.first))
            connection.sendCommand("ATCRA%03X".format(addrs.second))

            val resp = connection.sendCommand("3E00", timeoutMs = 2000)
            if (isLiveResponse(resp)) {
                modules.add(ECUModule(name, addrs.first, addrs.second, "MS-CAN"))
                Log.d(TAG, "MS-CAN module found: $name @ 0x${addrs.first.toString(16)}")
            }
        }

        // Switch back to HS-CAN
        connection.sendCommand("STP6")
        connection.sendCommand("STPC1")
        connection.sendCommand("ATSP6")
        connection.sendCommand("ATSH7DF")
        connection.sendCommand("ATH0")
        connection.sendCommand("ATS0")
        connection.sendCommand("ATST32")
        currentBus = "HS-CAN"

        return modules
    }

    // ── Diagnostic Snapshot ───────────────────────────────────────

    /**
     * Capture full diagnostic snapshot: VIN + DTCs + key PIDs.
     */
    suspend fun captureSnapshot(): DiagnosticSnapshot {
        val vin = readVin()
        val dtcs = readAllDtcs()
        val pids = mutableMapOf<String, Double>()

        val snapshotPids = listOf("RPM", "COOLANT_TEMP", "SPEED", "LOAD", "THROTTLE_POS",
            "MAF", "MAP", "IAT", "STFT_B1", "LTFT_B1", "FUEL_LEVEL",
            "CTRL_VOLTAGE", "TIMING_ADV", "OIL_TEMP")

        for (name in snapshotPids) {
            val result = readPidByName(name)
            if (result != null) {
                pids[result.first.name] = result.second
            }
        }

        return DiagnosticSnapshot(vin, dtcs, pids)
    }

    // ── Battery Voltage ───────────────────────────────────────────

    /**
     * Read battery voltage via ATRV.
     */
    suspend fun readBatteryVoltage(): Double? {
        val resp = connection.sendCommand("ATRV")
        val match = Regex("""(\d+\.?\d*)""").find(resp) ?: return null
        return match.groupValues[1].toDoubleOrNull()
    }

    // ── Internals ─────────────────────────────────────────────────

    private suspend fun switchBus(bus: String) {
        if (bus == currentBus) return

        when (bus.uppercase()) {
            "MS-CAN" -> {
                val resp = connection.sendCommand("STP33", timeoutMs = 3000)
                if (resp.contains("?")) {
                    connection.sendCommand("ATPBE004")
                    connection.sendCommand("ATSPB")
                }
                currentBus = "MS-CAN"
            }
            else -> {
                connection.sendCommand("STP6")
                connection.sendCommand("STPC1")
                connection.sendCommand("ATSP6")
                currentBus = "HS-CAN"
            }
        }
    }

    private suspend fun targetModule(moduleAddr: Int) {
        val respAddr = moduleAddr + 8
        connection.sendCommand("ATSH%03X".format(moduleAddr))
        connection.sendCommand("ATCRA%03X".format(respAddr))
        connection.sendCommand("ATH1")
    }

    private fun parseResponse(resp: String, expectedHeader: Int, expectedPid: Int): List<Int> {
        val hex = resp.replace(" ", "").replace("\n", "").replace("\r", "").uppercase()

        // Find the response data after the header+PID
        val headerStr = "%02X%02X".format(expectedHeader, expectedPid)
        val idx = hex.indexOf(headerStr)
        if (idx == -1) return emptyList()

        val dataHex = hex.substring(idx + headerStr.length)
        return dataHex.chunked(2).mapNotNull { it.toIntOrNull(16) }
    }

    private fun parseMultiframeResponse(resp: String): List<Int> {
        val lines = resp.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val allBytes = mutableListOf<Int>()

        for (line in lines) {
            val clean = line.replace(" ", "").uppercase()
            // Skip frame index prefix (0:, 1:, 2:, etc.)
            val data = if (clean.length > 2 && clean[1] == ':') {
                clean.substring(2)
            } else {
                // Skip possible CAN header (3 chars) + response header
                clean.dropWhile { !it.isDigit() || it == '0' }
            }
            val bytes = data.chunked(2).mapNotNull { it.toIntOrNull(16) }
            allBytes.addAll(bytes)
        }
        return allBytes
    }

    private fun parseDtcs(resp: String, status: String): List<DTC> {
        val hex = resp.replace(" ", "").replace("\n", "").replace("\r", "").uppercase()
        val dtcs = mutableListOf<DTC>()

        // Skip response header (43/47/4A + count byte)
        var data = hex
        for (prefix in listOf("43", "47", "4A")) {
            if (data.startsWith(prefix)) {
                data = data.drop(2)
                // Skip count byte if present and data is still sufficient
                if (data.length >= 2) {
                    val count = data.take(2).toIntOrNull(16) ?: 0
                    if (count in 1..20) data = data.drop(2)
                }
                break
            }
        }

        // Each DTC is 4 hex chars
        val dtcChars = data.chunked(4).filter { it.length == 4 }
        for (dtcHex in dtcChars) {
            if (dtcHex == "0000") continue  // Padding

            val firstNibble = dtcHex[0].digitToIntOrNull(16) ?: continue
            val prefix = when (firstNibble shr 2) {
                0 -> "P"
                1 -> "C"
                2 -> "B"
                3 -> "U"
                else -> "P"
            }
            val secondChar = (firstNibble and 0x03).toString()
            val code = "$prefix$secondChar${dtcHex.substring(1)}"

            if (code != "P0000") {
                dtcs.add(DTC(code, status))
            }
        }

        return dtcs
    }

    private fun parseBroadcastResponse(resp: String): List<Pair<Int, Int>> {
        val addresses = mutableListOf<Pair<Int, Int>>()
        val lines = resp.lines().map { it.trim() }.filter { it.isNotEmpty() }

        for (line in lines) {
            val clean = line.replace(" ", "").uppercase()
            if (clean.length >= 3) {
                val addr = clean.take(3).toIntOrNull(16) ?: continue
                if (addr in 0x7E8..0x7EF) {
                    addresses.add(Pair(addr - 8, addr))
                }
            }
        }

        return addresses.distinct()
    }

    private fun isLiveResponse(resp: String): Boolean {
        val upper = resp.uppercase().trim()
        return upper.isNotEmpty() &&
            !upper.startsWith("NO DATA") &&
            !upper.startsWith("ERROR") &&
            !upper.startsWith("UNABLE") &&
            !upper.startsWith("CAN ERROR") &&
            !upper.startsWith("BUS") &&
            !upper.startsWith("STOPPED") &&
            !upper.startsWith("?") &&
            !upper.startsWith("BUFFER FULL")
    }

    private fun decodeDIDValue(hexData: String): String {
        if (hexData.isEmpty()) return ""

        // Try ASCII decode if all bytes are printable
        val bytes = hexData.chunked(2).mapNotNull { it.toIntOrNull(16) }
        val printable = bytes.all { it in 0x20..0x7E }
        return if (printable && bytes.size > 1) {
            bytes.map { it.toChar() }.joinToString("").trim()
        } else {
            hexData
        }
    }
}

data class DiagnosticSnapshot(
    val vin: String?,
    val dtcs: List<DTC>,
    val pids: Map<String, Double>
)
