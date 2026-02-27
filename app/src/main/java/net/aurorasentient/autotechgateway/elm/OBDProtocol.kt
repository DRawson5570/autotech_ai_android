package net.aurorasentient.autotechgateway.elm

/**
 * OBD-II / UDS protocol engine.
 *
 * Port of protocol.py — handles OBD modes 01-0A and UDS services (0x10, 0x22, 0x3E).
 */

import android.util.Log

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
 * OBD-II / UDS Protocol engine.
 * Sends commands via an ElmConnection and parses responses.
 */
class OBDProtocol(private val connection: ElmConnection) {

    private var supportedPids: Set<Int> = emptySet()
    private var currentBus: String = "HS-CAN"

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
        for (name in names) {
            val result = readPidByName(name)
            if (result != null) {
                results[result.first.name] = result.second
            }
        }
        return results
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
