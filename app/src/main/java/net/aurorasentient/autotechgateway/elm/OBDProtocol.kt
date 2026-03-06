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

/** Ford MS-CAN module addresses (secondary bus, 125 kbps)
 *  Response addr → {request addr, name}
 *  Validated from Python protocol.py (source of truth) */
val FORD_MS_CAN_ADDRESSES = mapOf(
    "PCM-MS"  to Pair(0x720, 0x728),
    "TCM"     to Pair(0x721, 0x729),
    "APIM"    to Pair(0x726, 0x72E),
    "ACM"     to Pair(0x727, 0x72F),
    "FCDIM"   to Pair(0x731, 0x739),
    "IPMA"    to Pair(0x733, 0x73B),
    "IPC"     to Pair(0x740, 0x748),
    "SCCM"    to Pair(0x746, 0x74E),
    "PAM"     to Pair(0x750, 0x758),
    "GEM"     to Pair(0x760, 0x768),
    "RCM"     to Pair(0x764, 0x76C),
    "ABS"     to Pair(0x765, 0x76D),
    "HVAC-MS" to Pair(0x7A0, 0x7A8),
    "TPMS"    to Pair(0x7B0, 0x7B8),
    "PSCM"    to Pair(0x7C0, 0x7C8),
    "DDM"     to Pair(0x7C4, 0x7CC),
    "PDM"     to Pair(0x7C5, 0x7CD),
    "AWD"     to Pair(0x7D0, 0x7D8),
    "FCIM"    to Pair(0x7A6, 0x7AE),
    "GPSM"    to Pair(0x701, 0x709),
)

/** Standard UDS DIDs (ISO 14229-1:2020 §C.1)
 *  Range 0xF180-0xF19F: Standardized identification DIDs */
val STANDARD_DIDS = mapOf(
    // --- Boot / Application Software Identification ---
    0xF180 to "Boot Software ID",
    0xF181 to "Application Software ID",
    0xF182 to "Application Data ID",
    // --- Fingerprints (who last flashed what) ---
    0xF183 to "Boot Software Fingerprint",
    0xF184 to "Application Software Fingerprint",
    0xF185 to "Application Data Fingerprint",
    // --- Active Session ---
    0xF186 to "Active Diagnostic Session",
    // --- Vehicle Manufacturer IDs ---
    0xF187 to "Part Number",
    0xF188 to "ECU Software Number",
    0xF189 to "Software Version",
    0xF18A to "System Supplier ID",
    0xF18B to "ECU Manufacturing Date",
    0xF18C to "ECU Serial Number",
    0xF18D to "Supported Functional Units",
    0xF18E to "Kit Assembly Part Number",
    0xF18F to "Regulation Software ID Numbers",
    // --- Vehicle / Hardware IDs ---
    0xF190 to "VIN",
    0xF191 to "ECU Hardware Number",
    0xF192 to "Supplier HW Number",
    0xF193 to "Supplier HW Version",
    0xF194 to "Supplier SW Number",
    0xF195 to "Supplier SW Version",
    0xF196 to "Exhaust Regulation Number",
    0xF197 to "System Name / Engine Type",
    // --- Programming / Calibration History ---
    0xF198 to "Repair Shop Code",
    0xF199 to "Programming Date",
    0xF19A to "Calibration Shop Code",
    0xF19B to "Calibration Date",
    0xF19C to "Calibration Equipment SW Number",
    0xF19D to "ECU Installation Date",
    0xF19E to "ODX File Reference",
    0xF19F to "Entity Data ID",
    // --- UDS Protocol ---
    0xFF00 to "UDS Version",
)

/** Ford-specific identification DIDs
 *  Ford ECUs (especially MS-CAN modules) often DON'T respond to F1xx standard
 *  identification DIDs. Instead, Ford uses manufacturer-specific DID ranges.
 *  These are well-known from FORScan and empirical testing. */
val FORD_IDENTIFICATION_DIDS = mapOf(
    // --- Diagnostic Data (DDxx) — most commonly supported ---
    0xDD00 to "Diagnostic Data 00",
    0xDD01 to "Calibration ID / Odometer",
    0xDD02 to "Calibration Verification Number",
    0xDD03 to "Module Software Version",
    0xDD04 to "Module Hardware Version",
    0xDD05 to "Diagnostic Data 05",
    // --- Status/Config (DExx) ---
    0xDE00 to "Module Configuration 00",
    0xDE01 to "Module Configuration 01",
    0xDE02 to "Module Configuration 02",
    0xDE03 to "Module Configuration 03",
    // --- Common Ford F1xx that sometimes work ---
    0xF110 to "ECU Part Number (Ford)",
    0xF111 to "ECU Hardware Version (Ford)",
    0xF113 to "Module Status (Ford)",
    0xF124 to "Calibration Module ID",
    0xF125 to "Ford Strategy Code",
)

/** UDS Negative Response Codes (ISO 14229-1:2020 §A.1)
 *  Service 0x7F returns: 7F <rejected-SID> <NRC> */
val UDS_NRC_CODES = mapOf(
    // --- General ---
    0x10 to "generalReject",
    0x11 to "serviceNotSupported",
    0x12 to "subFunctionNotSupported",
    0x13 to "incorrectMessageLengthOrInvalidFormat",
    0x14 to "responseTooLong",
    // --- Timing ---
    0x21 to "busyRepeatRequest",
    0x22 to "conditionsNotCorrect",
    0x23 to "routineNotComplete",
    0x24 to "requestSequenceError",
    0x25 to "noResponseFromSubnetComponent",
    0x26 to "failurePreventsExecutionOfRequestedAction",
    // --- Data / Range ---
    0x31 to "requestOutOfRange",
    // --- Security ---
    0x33 to "securityAccessDenied",
    0x34 to "authenticationRequired",
    0x35 to "invalidKey",
    0x36 to "exceededNumberOfAttempts",
    0x37 to "requiredTimeDelayNotExpired",
    0x38 to "secureDataTransmissionRequired",
    0x39 to "secureDataTransmissionNotAllowed",
    0x3A to "secureDataVerificationFailed",
    // --- Certificate Verification (ISO 14229-1:2020) ---
    0x50 to "certificateVerificationFailed_InvalidTimePeriod",
    0x51 to "certificateVerificationFailed_InvalidSignature",
    0x52 to "certificateVerificationFailed_InvalidChainOfTrust",
    0x53 to "certificateVerificationFailed_InvalidType",
    0x54 to "certificateVerificationFailed_InvalidFormat",
    0x55 to "certificateVerificationFailed_InvalidContent",
    0x56 to "certificateVerificationFailed_InvalidScope",
    0x57 to "certificateVerificationFailed_InvalidCertificate",
    0x58 to "ownershipVerificationFailed",
    0x59 to "challengeCalculationFailed",
    0x5A to "settingAccessRightsFailed",
    0x5B to "sessionKeyCreationDerivationFailed",
    0x5C to "configurationDataUsageFailed",
    0x5D to "deAuthenticationFailed",
    // --- Upload / Download ---
    0x70 to "uploadDownloadNotAccepted",
    0x71 to "transferDataSuspended",
    0x72 to "generalProgrammingFailure",
    0x73 to "wrongBlockSequenceCounter",
    // --- Response Pending ---
    0x78 to "requestCorrectlyReceivedResponsePending",
    // --- Sub-function ---
    0x7E to "subFunctionNotSupportedInActiveSession",
    0x7F to "serviceNotSupportedInActiveSession",
    // --- Vehicle Condition ---
    0x81 to "rpmTooHigh",
    0x82 to "rpmTooLow",
    0x83 to "engineIsRunning",
    0x84 to "engineIsNotRunning",
    0x85 to "engineRunTimeTooLow",
    0x86 to "temperatureTooHigh",
    0x87 to "temperatureTooLow",
    0x88 to "vehicleSpeedTooHigh",
    0x89 to "vehicleSpeedTooLow",
    0x8A to "throttlePedalTooHigh",
    0x8B to "throttlePedalTooLow",
    0x8C to "transmissionRangeNotInNeutral",
    0x8D to "transmissionRangeNotInGear",
    0x8F to "brakeSwitchNotClosed",
    0x90 to "shifterLeverNotInPark",
    0x91 to "torqueConverterClutchLocked",
    // --- Voltage ---
    0x92 to "voltageTooHigh",
    0x93 to "voltageTooLow",
    // --- Resource ---
    0x94 to "resourceTemporarilyNotAvailable",
)

/** GM Enhanced CAN addresses (HS-CAN, 500 kbps, pins 6+14)
 *  GM uses a +0x400 offset for responses (request 0x2xx → response 0x6xx) */
val GM_ENHANCED_ADDRESSES = mapOf(
    "PCM-E" to Pair(0x240, 0x640),
    "BCM-E" to Pair(0x241, 0x641),
    "TCM-E" to Pair(0x242, 0x642),
    "EBCM"  to Pair(0x243, 0x643),
    "HVAC-E" to Pair(0x244, 0x644),
    "EPS"   to Pair(0x245, 0x645),
    "Radio-E" to Pair(0x246, 0x646),
    "IPC-E" to Pair(0x247, 0x647),
    "TDM"   to Pair(0x248, 0x648),
    "SDM"   to Pair(0x249, 0x649),
    "MSM"   to Pair(0x24A, 0x64A),
    "VCIM"  to Pair(0x24B, 0x64B),
    "DIC"   to Pair(0x24C, 0x64C),
    "PDM"   to Pair(0x24D, 0x64D),
    "RIM"   to Pair(0x24E, 0x64E),
    "RCDLR" to Pair(0x24F, 0x64F),
    "TBC"   to Pair(0x250, 0x650),
    "ORC"   to Pair(0x251, 0x651),
    "PAM"   to Pair(0x252, 0x652),
    "SWM"   to Pair(0x254, 0x654),
)

/** GM SW-CAN / GMLAN addresses (Single Wire CAN, 33.3 kbps, pin 1)
 *  Body/comfort modules only accessible on SW-CAN. Same +0x400 response offset.
 *  Per OBDLink FRPM manual: STP63 = ISO 15765, 11-bit, 33.3kbps (GMLAN diagnostic) */
val GM_SW_CAN_ADDRESSES = mapOf(
    "BCM"      to Pair(0x241, 0x641),
    "HVAC"     to Pair(0x244, 0x644),
    "Radio"    to Pair(0x246, 0x646),
    "IPC"      to Pair(0x247, 0x647),
    "TDM-SW"   to Pair(0x248, 0x648),
    "MSM-SW"   to Pair(0x24A, 0x64A),
    "VCIM"     to Pair(0x24B, 0x64B),
    "DIC-SW"   to Pair(0x24C, 0x64C),
    "PDM-SW"   to Pair(0x24D, 0x64D),
    "RIM-SW"   to Pair(0x24E, 0x64E),
    "RCDLR-SW" to Pair(0x24F, 0x64F),
    "SWM-SW"   to Pair(0x254, 0x654),
)

/** Ford-specific WMI prefixes for MS-CAN detection */
val FORD_WMI_PREFIXES = setOf(
    "1FA", "1FB", "1FC", "1FD", "1FM", "1FT", "1FV", "1FW",
    "2FA", "2FB", "2FC", "2FD", "2FM", "2FT",
    "3FA", "3FB", "3FC", "3FD", "3FM", "3FT",
    "MAJ", "NM0", "WF0",
)

/** GM WMI prefixes for SW-CAN/GMLAN detection (Buick, Cadillac, Chevrolet, GMC, etc.) */
val GM_WMI_PREFIXES = setOf(
    "1G1", "1G2", "1G3", "1G4", "1G6",
    "1GC", "1GK", "1GM", "1GT", "1GY",
    "2G1", "2G2", "2G4", "2GK", "2GT",
    "3G1", "3G7", "3GK", "3GT",
    "5GA", "5GR", "5GT",
    "6G1",
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
    val bus: String,         // "HS-CAN", "MS-CAN", or "SW-CAN"
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
    val maxBatchPids: Int = 1,           // How many PIDs per frame (up to 6)
    val supportsSwCan: Boolean = false   // SW-CAN/GMLAN (STP63, pin 1)
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

    /** Set by tunnel to temporarily pause scope so tunnel requests can use the adapter */
    @Volatile
    var scopePausedForTunnel = false

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

        // SW-CAN capability: STN adapters with 3 CAN transceivers (e.g. OBDLink MX+)
        // support STP63 for GMLAN on pin 1 at 33.3 kbps
        val supportsSwCan = isSTN  // All STN2120-based adapters have the SW-CAN transceiver

        capabilities = AdapterCapabilities(
            isSTN = isSTN,
            deviceName = deviceName,
            firmwareVersion = fwVersion,
            supportsSTAF = supportsSTAF,
            supportsSTMA = supportsSTMA,
            supportsSTPX = supportsSTPX,
            supportsBatchPids = supportsBatch,
            maxBatchPids = maxBatch,
            supportsSwCan = supportsSwCan
        )

        Log.i(TAG, "Adapter: $deviceName | STN=$isSTN | STAF=$supportsSTAF | " +
                "STMA=$supportsSTMA | STPX=$supportsSTPX | batch=$maxBatch | SW-CAN=$supportsSwCan")
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
            // Yield to tunnel requests — pause polling while tunnel is using adapter
            while (scopePausedForTunnel && isScopeRunning) {
                delay(50)
            }
            if (!isScopeRunning) break

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
            // Yield to tunnel requests — pause polling while tunnel is using adapter
            while (scopePausedForTunnel && isScopeRunning) {
                delay(50)
            }
            if (!isScopeRunning) break

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

    suspend fun readDtcsMode(mode: String, status: String): List<DTC> {
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
     *
     * Resets the adapter to clean OBD-II broadcast state first,
     * because prior UDS/DID commands may have configured specific
     * CAN headers and receive filters that corrupt Mode 09.
     */
    suspend fun readVin(): String? {
        // Reset adapter to clean OBD-II broadcast state
        try {
            connection.sendCommand("ATSH7DF")   // OBD-II broadcast header
            connection.sendCommand("ATCRA")     // Clear receive filter
            connection.sendCommand("ATH0")      // Headers off
        } catch (_: Exception) {
            // Best effort — proceed with VIN request regardless
        }

        val resp = connection.sendCommand("0902", timeoutMs = 10000)
        if (!isLiveResponse(resp)) return null

        // Parse multiframe response — strips 4902XX header from first frame
        val data = parseMultiframeResponse(resp)
        if (data.size < 17) return null

        // First byte after header strip is message count (01), skip it.
        // Then take exactly 17 VIN characters.
        val vinBytes = if (data.size > 17) data.subList(1, 18) else data.take(17)
        val vin = vinBytes
            .filter { it in 0x20..0x7E }
            .map { it.toChar() }
            .joinToString("")
            .trim()

        Log.d(TAG, "VIN parsed: '$vin' (${vin.length} chars) from ${data.size} bytes")
        return if (vin.length == 17) vin else null
    }

    // ── UDS: Read DID ─────────────────────────────────────────────

    /**
     * Read a UDS DID from a specific ECU module.
     *
     * Handles bus switching, Flow Control for User protocols, and
     * ISO-TP multi-frame reassembly automatically.
     */
    suspend fun readDid(moduleAddr: Int, did: Int, bus: String = "HS-CAN"): DIDResult? {
        var switchedBus = false
        var explicitFc = false
        try {
            switchedBus = bus.uppercase() != "HS-CAN"
            switchBus(bus)
            val respAddr = resolveResponseAddr(moduleAddr)
            explicitFc = configureCanTarget(moduleAddr, respAddr, switchedBus)

            // Enter extended session
            connection.sendCommand("1003")

            // ReadDataByIdentifier (0x22) + DID
            val cmd = "22%04X".format(did)
            val resp = connection.sendCommand(cmd, timeoutMs = 8000)
            if (!isLiveResponse(resp)) return null

            // Reassemble ISO-TP and parse
            val respHex = "%03X".format(respAddr)
            val cleaned = reassembleIsoTp(resp, respHex)

            // Check for negative response
            if (cleaned.uppercase().contains("7F22")) {
                val nrcIdx = cleaned.uppercase().indexOf("7F22") + 4
                val nrc = if (nrcIdx + 2 <= cleaned.length)
                    cleaned.substring(nrcIdx, nrcIdx + 2).toIntOrNull(16) else null
                val nrcDesc = nrc?.let { UDS_NRC_CODES[it] } ?: "Unknown"
                Log.w(TAG, "DID 0x${did.toString(16).uppercase()}: NRC $nrcDesc")
                return null
            }

            // Parse positive response: 62 + DID (2 bytes) + data
            val didHex = "%04X".format(did)
            val marker = "62$didHex"
            val idx = cleaned.uppercase().indexOf(marker.uppercase())
            if (idx < 0) return null

            val dataHex = cleaned.substring(idx + marker.length)
            if (dataHex.isEmpty()) return null

            val decoded = decodeDIDValue(dataHex)
            val description = STANDARD_DIDS[did] ?: "DID 0x${did.toString(16).uppercase()}"

            return DIDResult(
                did = didHex,
                rawHex = dataHex,
                decoded = decoded,
                description = description
            )
        } catch (e: Exception) {
            Log.e(TAG, "readDid(${moduleAddr.toString(16)}, ${did.toString(16)}) failed: ${e.message}")
            return null
        } finally {
            restoreCanDefaults(switchedBus, explicitFc)
        }
    }

    /**
     * Read multiple DIDs from a module.
     *
     * Handles bus switching, Flow Control, and ISO-TP reassembly.
     * Single bus switch for all DIDs.
     */
    suspend fun readDids(moduleAddr: Int, dids: List<Int>, bus: String = "HS-CAN"): Map<String, DIDResult> {
        val results = mutableMapOf<String, DIDResult>()
        var switchedBus = false
        var explicitFc = false
        try {
            switchedBus = bus.uppercase() != "HS-CAN"
            switchBus(bus)
            val respAddr = resolveResponseAddr(moduleAddr)
            explicitFc = configureCanTarget(moduleAddr, respAddr, switchedBus)
            connection.sendCommand("1003")  // Extended session once

            val respHex = "%03X".format(respAddr)

            for (did in dids) {
                try {
                    val cmd = "22%04X".format(did)
                    val resp = connection.sendCommand(cmd, timeoutMs = 8000)
                    if (!isLiveResponse(resp)) continue

                    val cleaned = reassembleIsoTp(resp, respHex)

                    // Check for negative response
                    if (cleaned.uppercase().contains("7F22")) continue

                    val didHex = "%04X".format(did)
                    val marker = "62$didHex"
                    val idx = cleaned.uppercase().indexOf(marker.uppercase())
                    if (idx < 0) continue

                    val dataHex = cleaned.substring(idx + marker.length)
                    if (dataHex.isEmpty()) continue

                    results[didHex] = DIDResult(
                        did = didHex,
                        rawHex = dataHex,
                        decoded = decodeDIDValue(dataHex),
                        description = STANDARD_DIDS[did] ?: didHex
                    )
                } catch (e: Exception) {
                    Log.d(TAG, "DID ${did.toString(16)} failed: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "readDids failed: ${e.message}")
        } finally {
            restoreCanDefaults(switchedBus, explicitFc)
        }
        return results
    }

    /**
     * Send a raw UDS hex command to a specific module.
     *
     * Handles bus switching, Flow Control, and ISO-TP reassembly.
     */
    suspend fun sendUdsRaw(moduleAddr: Int, hexCmd: String, bus: String = "HS-CAN"): String {
        var switchedBus = false
        var explicitFc = false
        try {
            switchedBus = bus.uppercase() != "HS-CAN"
            switchBus(bus)
            val respAddr = resolveResponseAddr(moduleAddr)
            explicitFc = configureCanTarget(moduleAddr, respAddr, switchedBus)

            val resp = connection.sendCommand(hexCmd, timeoutMs = 10000)
            if (!isLiveResponse(resp)) return ""

            // Reassemble ISO-TP multi-frame response
            val respHex = "%03X".format(respAddr)
            val cleaned = reassembleIsoTp(resp, respHex)
            Log.d(TAG, "sendUdsRaw(${moduleAddr.toString(16)}, $hexCmd): $cleaned")
            return cleaned
        } catch (e: Exception) {
            Log.e(TAG, "sendUdsRaw(${moduleAddr.toString(16)}, $hexCmd) failed: ${e.message}")
            return ""
        } finally {
            restoreCanDefaults(switchedBus, explicitFc)
        }
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

        // Phase 4: GM Enhanced HS-CAN (if VIN indicates GM)
        if (vin != null && isGmVin(vin)) {
            Log.i(TAG, "GM detected, scanning enhanced HS-CAN addresses...")
            val gmHsModules = scanGmEnhancedHsCan(modules)
            modules.addAll(gmHsModules)

            // Phase 5: GM SW-CAN / GMLAN (33.3 kbps, pin 1)
            Log.i(TAG, "Scanning GM SW-CAN/GMLAN (33.3 kbps, pin 1)...")
            val swCanModules = scanSwCan(modules)
            modules.addAll(swCanModules)
        }

        Log.i(TAG, "Discovered ${modules.size} modules")
        return modules
    }

    private fun isFordVin(vin: String): Boolean {
        if (vin.length < 3) return false
        return FORD_WMI_PREFIXES.contains(vin.take(3).uppercase())
    }

    private fun isGmVin(vin: String): Boolean {
        if (vin.length < 3) return false
        return GM_WMI_PREFIXES.contains(vin.take(3).uppercase())
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

    /**
     * Phase 4: Scan GM Enhanced HS-CAN addresses (0x240-0x25F range).
     * GM uses request 0x2xx -> response 0x6xx (+0x400 offset) on HS-CAN.
     */
    private suspend fun scanGmEnhancedHsCan(existingModules: List<ECUModule>): List<ECUModule> {
        val modules = mutableListOf<ECUModule>()
        val seenAddrs = existingModules.map { it.address }.toSet()

        try {
            // Ensure we're on HS-CAN
            connection.sendCommand("STP6")
            connection.sendCommand("STPC1")
            connection.sendCommand("ATSP6")
            connection.sendCommand("ATH1")
            connection.sendCommand("ATS1")

            for ((name, addrs) in GM_ENHANCED_ADDRESSES) {
                val reqAddr = addrs.first
                val respAddr = addrs.second

                // Skip if already found via standard OBD addressing
                if (seenAddrs.contains(reqAddr)) continue
                // Skip PCM-E/TCM-E if standard PCM/TCM already found
                if (name == "PCM-E" && existingModules.any { it.name == "PCM" }) continue
                if (name == "TCM-E" && existingModules.any { it.name == "TCM" }) continue

                connection.sendCommand("ATSH%03X".format(reqAddr))
                connection.sendCommand("ATCRA%03X".format(respAddr))

                // Try TesterPresent
                var resp = connection.sendCommand("3E00", timeoutMs = 3000)
                var found = isLiveResponse(resp)

                if (!found) {
                    // Try DiagnosticSessionControl
                    resp = connection.sendCommand("1001", timeoutMs = 3000)
                    found = isLiveResponse(resp)
                }

                if (found) {
                    modules.add(ECUModule(name, reqAddr, respAddr, "HS-CAN"))
                    Log.i(TAG, "GM HS-CAN Discovered: $name @ 0x${reqAddr.toString(16).uppercase()}")
                }
            }

            // Reset CAN receive filter
            connection.sendCommand("ATCRA")
            connection.sendCommand("ATSH7DF")
            connection.sendCommand("ATH0")
            connection.sendCommand("ATS0")
            connection.sendCommand("ATST32")

        } catch (e: Exception) {
            Log.w(TAG, "GM HS-CAN probing failed: ${e.message}")
        }

        Log.i(TAG, "Phase 4 complete: found ${modules.size} GM HS-CAN module(s)")
        return modules
    }

    /**
     * Phase 5: Scan GM SW-CAN / GMLAN (33.3 kbps, pin 1).
     * Body/comfort modules accessible on Single Wire CAN.
     * Per OBDLink FRPM manual: STP63 = ISO 15765, 11-bit, 33.3kbps.
     * NOTE: STP31 is 500kbps HS-CAN raw — NOT GMLAN!
     */
    private suspend fun scanSwCan(existingModules: List<ECUModule>): List<ECUModule> {
        val modules = mutableListOf<ECUModule>()
        val seenAddrs = existingModules.map { it.address }.toSet()

        // Switch to SW-CAN: STP63 first (ISO 15765 GMLAN), STP61 fallback (raw), ATPB last resort
        var switched = false

        // STP63 = ISO 15765, 11-bit Tx, 33.3kbps, DLC=8 (SW-CAN/GMLAN diagnostic)
        val stp63Resp = connection.sendCommand("STP63", timeoutMs = 3000)
        if (!stp63Resp.contains("?")) {
            switched = true
            Log.i(TAG, "SW-CAN via STP63 (GMLAN diagnostic)")
        }

        if (!switched) {
            // STP61 = ISO 11898, 11-bit, 33.3kbps (raw SW-CAN)
            val stp61Resp = connection.sendCommand("STP61", timeoutMs = 3000)
            if (!stp61Resp.contains("?")) {
                switched = true
                Log.i(TAG, "SW-CAN via STP61 (raw)")
            }
        }

        if (!switched) {
            // Last resort: manual baud rate config for 33.3 kbps
            connection.sendCommand("ATPB 8104")
            val spbResp = connection.sendCommand("ATSPB", timeoutMs = 3000)
            if (!spbResp.contains("?")) {
                switched = true
                Log.i(TAG, "SW-CAN via ATPB 8104 fallback")
            }
        }

        if (!switched) {
            Log.w(TAG, "Could not switch to SW-CAN/GMLAN")
            return modules
        }

        currentBus = "SW-CAN"

        // Extended timeout for slow 33.3 kbps bus
        connection.sendCommand("ATSTFF")
        connection.sendCommand("ATH1")
        connection.sendCommand("ATS1")

        // Probe GM SW-CAN module addresses
        for ((name, addrs) in GM_SW_CAN_ADDRESSES) {
            val reqAddr = addrs.first
            val respAddr = addrs.second

            // Skip if already found on HS-CAN (except BCM which may be on both)
            if (seenAddrs.contains(reqAddr) && name != "BCM") continue

            connection.sendCommand("ATSH%03X".format(reqAddr))
            connection.sendCommand("ATCRA%03X".format(respAddr))

            // Try TesterPresent (longer timeout for slow bus)
            var resp = connection.sendCommand("3E00", timeoutMs = 4000)
            var found = isLiveResponse(resp)

            if (!found) {
                // Try DiagnosticSessionControl
                resp = connection.sendCommand("1001", timeoutMs = 4000)
                found = isLiveResponse(resp)
            }

            if (found) {
                // Use SW-CAN-specific name (not the enhanced HS-CAN name)
                val moduleName = if (name.endsWith("-SW")) name.removeSuffix("-SW") else name
                modules.add(ECUModule(moduleName, reqAddr, respAddr, "SW-CAN"))
                Log.i(TAG, "SW-CAN Discovered: $moduleName @ 0x${reqAddr.toString(16).uppercase()}")
            }
        }

        // Reset CAN receive filter
        connection.sendCommand("ATCRA")

        // Switch back to HS-CAN
        connection.sendCommand("STP6")
        connection.sendCommand("STPC1")
        connection.sendCommand("ATSP6")
        connection.sendCommand("ATSH7DF")
        connection.sendCommand("ATH0")
        connection.sendCommand("ATS0")
        connection.sendCommand("ATST32")
        currentBus = "HS-CAN"

        Log.i(TAG, "Phase 5 complete: found ${modules.size} GM SW-CAN module(s)")
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
            "SW-CAN" -> {
                // Per OBDLink FRPM manual:
                // STP63 = ISO 15765, 11-bit Tx, 33.3kbps, DLC=8 (SW-CAN/GMLAN diagnostic)
                // NOTE: STP31 is 500kbps HS-CAN raw — NOT GMLAN!
                var switched = false
                val stp63Resp = connection.sendCommand("STP63", timeoutMs = 3000)
                if (!stp63Resp.contains("?")) {
                    switched = true
                    Log.d(TAG, "Switched to SW-CAN via STP63")
                }
                if (!switched) {
                    val stp61Resp = connection.sendCommand("STP61", timeoutMs = 3000)
                    if (!stp61Resp.contains("?")) {
                        switched = true
                        Log.d(TAG, "Switched to SW-CAN via STP61 (raw)")
                    }
                }
                if (!switched) {
                    // Last resort: manual baud rate config for 33.3 kbps
                    connection.sendCommand("ATPB 8104")
                    connection.sendCommand("ATSPB")
                    Log.d(TAG, "Switched to SW-CAN via ATPB 8104 fallback")
                }
                currentBus = "SW-CAN"
            }
            else -> {
                connection.sendCommand("STP6")
                connection.sendCommand("STPC1")
                connection.sendCommand("ATSP6")
                currentBus = "HS-CAN"
            }
        }
    }

    /**
     * Set CAN header and receive filter for a specific module.
     * GM enhanced range (0x200-0x2FF) uses +0x400 response offset.
     * Standard OBD-II (0x7E0-0x7EF) uses +8 response offset.
     */
    private suspend fun targetModule(moduleAddr: Int) {
        // GM enhanced address range uses +0x400 offset (request 0x2xx -> response 0x6xx)
        val respAddr = if (moduleAddr in 0x200..0x2FF) {
            moduleAddr + 0x400
        } else {
            moduleAddr + 8
        }
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

    /**
     * Parse a multi-frame OBD-II response (e.g. VIN, calibration ID).
     *
     * Handles all ELM327 response formats:
     * - CAN ISO-TP indexed:  "0:490201314734\n1:48503537323536"
     * - CAN reassembled:     "4902013147344850353732353655313434343334"
     * - Multi-segment Mode 09: "490201314734485035\n490202373235365531"
     * - With "SEARCHING..." prefix (silently ignored)
     *
     * Strips the Mode 09 response header (49 02 XX) from the first
     * data frame so the caller gets pure payload bytes.
     */
    private fun parseMultiframeResponse(resp: String): List<Int> {
        val allBytes = mutableListOf<Int>()
        var isFirstFrame = true

        for (rawLine in resp.lines()) {
            var line = rawLine.trim()
            if (line.isEmpty()) continue

            // Strip CAN ISO-TP frame index prefix: "0:", "1:", ... "0A:", etc.
            if (line.length > 2 && line[1] == ':' && line[0].isDigit()) {
                line = line.substring(2)
            } else if (line.length > 3 && line[2] == ':' &&
                       line[0].isLetterOrDigit() && line[1].isLetterOrDigit()) {
                line = line.substring(3)
            }

            // Keep only hex characters (strips spaces, "SEARCHING...", etc.)
            val hexStr = line.filter { it in '0'..'9' || it in 'A'..'F' || it in 'a'..'f' }
                .uppercase()
            if (hexStr.isEmpty()) continue

            // On the first actual data frame, strip Mode 09 response header
            var data = hexStr
            if (isFirstFrame) {
                if (data.startsWith("4902") || data.startsWith("4904")) {
                    data = data.drop(6)  // Strip "4902XX" or "4904XX"
                }
                isFirstFrame = false
            } else {
                // Subsequent frames: strip per-segment Mode 09 header if present
                // (e.g. "490202..." on second segment)
                if (data.startsWith("4902") || data.startsWith("4904")) {
                    data = data.drop(6)
                }
            }

            // Convert hex pairs to byte values
            for (i in data.indices step 2) {
                if (i + 1 < data.length) {
                    data.substring(i, i + 2).toIntOrNull(16)?.let { allBytes.add(it) }
                }
            }
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

    /**
     * Compute the CAN response address for a given request address.
     *
     * Searches known address tables (Ford MS-CAN, GM Enhanced, GM SW-CAN,
     * standard ECU) in order, then falls back to heuristics.
     */
    private fun resolveResponseAddr(moduleAddr: Int): Int {
        // Search all known address tables: Pair(request, response)
        val tables = listOf(FORD_MS_CAN_ADDRESSES, GM_ENHANCED_ADDRESSES,
            GM_SW_CAN_ADDRESSES, ECU_ADDRESSES)
        for (table in tables) {
            for ((_, addrs) in table) {
                if (addrs.first == moduleAddr) return addrs.second
            }
        }
        // GM enhanced range: request 0x2XX → response 0x6XX
        if (moduleAddr in 0x200..0x2FF) return moduleAddr + 0x400
        return moduleAddr + 8
    }

    /**
     * Check if address is in the standard OBD-II range (0x7E0-0x7E7).
     *
     * The ELM327/STN auto-FC only handles these addresses automatically.
     * Any other address (GM enhanced 0x2XX, Ford MS-CAN 0x7XX, etc.)
     * needs explicit Flow Control configuration for multi-frame responses.
     */
    private fun isStandardOdbAddr(moduleAddr: Int): Boolean =
        moduleAddr in 0x7E0..0x7E7

    /**
     * Set up CAN headers, receive filter, and Flow Control for a target module.
     *
     * Configures explicit FC in two cases:
     * 1. User protocols (SW-CAN/STP63, MS-CAN/STP33) — the adapter's
     *    auto-FC doesn't know the correct header.
     * 2. Non-standard addresses on HS-CAN (e.g. GM enhanced 0x243,
     *    0x24C) — auto-FC only handles 0x7E0-0x7E7.
     *
     * @param moduleAddr CAN request address (e.g. 0x247)
     * @param respAddr   CAN response address (e.g. 0x647)
     * @param switchedBus true if we switched away from HS-CAN
     * @return true if explicit FC was configured (caller must restore)
     */
    private suspend fun configureCanTarget(moduleAddr: Int, respAddr: Int, switchedBus: Boolean): Boolean {
        connection.sendCommand("ATH1")
        connection.sendCommand("ATSH%03X".format(moduleAddr))
        connection.sendCommand("ATCRA%03X".format(respAddr))

        val needFc = switchedBus || !isStandardOdbAddr(moduleAddr)
        if (needFc) {
            // Explicit Flow Control:
            //   FC SH  = our transmit header (moduleAddr)
            //   FC SD  = 30 00 00: FlowStatus=CTS, BlockSize=0, STmin=0
            //   FC SM 1 = user-defined header + data
            connection.sendCommand("AT FC SH %03X".format(moduleAddr))
            connection.sendCommand("AT FC SD 30 00 00")
            connection.sendCommand("AT FC SM 1")
            Log.d(TAG, "Configured explicit FC: header=%03X, CTS/BS=0/STmin=0 (busSwitched=$switchedBus, nonStdAddr=${!isStandardOdbAddr(moduleAddr)})".format(moduleAddr))
        }
        return needFc
    }

    /**
     * Restore adapter to default HS-CAN state after a UDS request.
     *
     * @param switchedBus true if we switched away from HS-CAN
     * @param explicitFc  true if explicit Flow Control was configured
     *                    (non-standard addr or non-HS-CAN bus)
     */
    private suspend fun restoreCanDefaults(switchedBus: Boolean, explicitFc: Boolean = false) {
        if (explicitFc) {
            // Restore FC to auto mode — must happen before protocol switch
            try { connection.sendCommand("AT FC SM 0") } catch (_: Exception) {}
        }
        if (switchedBus) {
            try { connection.sendCommand("STP6") } catch (_: Exception) {}
            try { connection.sendCommand("STPC1") } catch (_: Exception) {}
            try { connection.sendCommand("ATSP6") } catch (_: Exception) {}
        }
        try { connection.sendCommand("ATSH7DF") } catch (_: Exception) {}
        try { connection.sendCommand("ATCRA") } catch (_: Exception) {}
        try { connection.sendCommand("ATH0") } catch (_: Exception) {}
    }

    /**
     * Reassemble ISO-TP multi-frame CAN response into clean hex data.
     *
     * Strips CAN headers from all frames and concatenates the UDS payload.
     * For single-frame responses, strips the PCI length byte.
     * For multi-frame responses, strips FF/CF PCI bytes and reassembles.
     *
     * @param rawResp       Raw response string from ELM327 (newline-separated CAN frames)
     * @param respAddrHex   Expected CAN response address as 3-char hex (e.g. "7E8")
     * @return Clean hex string containing only the UDS service response data
     */
    private fun reassembleIsoTp(rawResp: String, respAddrHex: String): String {
        val addrUpper = respAddrHex.uppercase()
        val headerLen = addrUpper.length

        // Split into lines; strip whitespace, CAN header from each
        val stripped = rawResp.trim().split('\n').mapNotNull { rawLine ->
            var line = rawLine.trim().replace(" ", "").uppercase()
            if (line.isEmpty() || line == ">" || "NODATA" in line || "ERROR" in line) {
                null
            } else {
                // Strip CAN header if present
                if (line.startsWith(addrUpper)) {
                    line = line.substring(headerLen)
                }
                line
            }
        }

        if (stripped.isEmpty()) return ""

        if (stripped.size == 1) {
            val frame = stripped[0]
            // Single frame: PCI byte is 0{len} (2 hex chars)
            // e.g. "065A90A3033C3C" → strip "06" → "5A90A3033C3C"
            return if (frame.length >= 2 && frame[0] == '0') {
                frame.substring(2)
            } else {
                frame
            }
        }

        // Multi-frame: First Frame PCI is 1{LLL} (4 hex chars)
        val first = stripped[0]
        if (first.length >= 4 && first[0] == '1') {
            val dataLen = try {
                first.substring(1, 4).toInt(16)
            } catch (_: NumberFormatException) {
                9999 // fallback: take everything
            }
            // FF data starts after 4-char PCI
            val dataParts = mutableListOf(first.substring(4))
            // Consecutive frames: PCI is 2{seq} (2 hex chars)
            for (cf in stripped.subList(1, stripped.size)) {
                if (cf.length >= 2 && cf[0] == '2') {
                    dataParts.add(cf.substring(2))
                }
            }
            var reassembled = dataParts.joinToString("")
            // Trim to declared length (length is in bytes, hex is 2 chars/byte)
            val maxChars = dataLen * 2
            if (reassembled.length > maxChars) {
                reassembled = reassembled.substring(0, maxChars)
            }
            return reassembled
        }

        // Unrecognized framing — return first frame stripped of header only
        return stripped[0]
    }
}

data class DiagnosticSnapshot(
    val vin: String?,
    val dtcs: List<DTC>,
    val pids: Map<String, Double>
)
