package net.aurorasentient.autotechgateway.elm

/**
 * DBC File Decoder — Parse CAN database files and decode broadcast CAN frames.
 *
 * Kotlin port of dbc_decoder.py — loads DBC files from Android assets to
 * auto-decode raw CAN bus broadcast traffic into named signals with engineering units.
 *
 * DBC Format Reference:
 *     BO_ <CAN_ID> <MessageName>: <DLC> <Transmitter>
 *      SG_ <SignalName> : <StartBit>|<Length>@<ByteOrder><ValueType>
 *          (<Factor>,<Offset>) [<Min>|<Max>] "<Unit>" <Receivers>
 *
 *     ByteOrder: 1 = little-endian (Intel), 0 = big-endian (Motorola)
 *     ValueType: + = unsigned, - = signed
 */

import android.content.Context
import android.util.Log
import com.google.gson.JsonObject
import java.io.BufferedReader
import java.io.InputStreamReader

private const val TAG = "DbcDecoder"

// ─────────────────────────────────────────────────────────────
// Data classes
// ─────────────────────────────────────────────────────────────

/** A single signal within a CAN message. */
data class DBCSignal(
    val name: String,
    val startBit: Int,          // DBC start bit position
    val length: Int,            // signal length in bits
    val byteOrder: Int,         // 0 = big-endian (Motorola), 1 = little-endian (Intel)
    val isSigned: Boolean,      // True = signed value
    val factor: Double,         // physical = raw * factor + offset
    val offset: Double,
    val minimum: Double,
    val maximum: Double,
    val unit: String,
    val receivers: List<String> = emptyList(),
    var comment: String = "",
    var valueTable: Map<Int, String>? = null,  // enum-style named values
) {
    /** Decode this signal's value from raw CAN data bytes. */
    fun decode(data: ByteArray): Any {
        val raw = extractRaw(data)
        var physical = raw * factor + offset

        // Clamp to min/max if defined
        if (maximum > minimum) {
            physical = physical.coerceIn(minimum, maximum)
        }

        // Return named value if value table exists
        valueTable?.let { vt ->
            val rawInt = raw.toInt()
            vt[rawInt]?.let { return it }
        }

        // Return int if factor is 1.0 and offset is 0 and no fractional part
        if (factor == 1.0 && offset == 0.0 && physical == physical.toLong().toDouble()) {
            return physical.toLong()
        }

        return (physical * 10000).toLong() / 10000.0  // round to 4 decimals
    }

    private fun extractRaw(data: ByteArray): Double {
        return if (byteOrder == 1) {
            extractIntel(data).toDouble()
        } else {
            extractMotorola(data).toDouble()
        }
    }

    /** Extract little-endian (Intel) signal. */
    private fun extractIntel(data: ByteArray): Long {
        // Convert data to a large integer (little-endian)
        var value = 0L
        for (i in data.indices.reversed()) {
            value = (value shl 8) or (data[i].toLong() and 0xFF)
        }

        // Shift and mask
        var raw = (value ushr startBit) and ((1L shl length) - 1)

        // Handle signed
        if (isSigned && raw and (1L shl (length - 1)) != 0L) {
            raw -= (1L shl length)
        }

        return raw
    }

    /** Extract big-endian (Motorola) signal. */
    private fun extractMotorola(data: ByteArray): Long {
        // Build list of bit positions (MSB to LSB)
        val bits = mutableListOf<Int>()
        var pos = startBit
        for (i in 0 until length) {
            bits.add(pos)
            val byteNum = pos / 8
            val bitInByte = pos % 8
            pos = if (bitInByte > 0) {
                byteNum * 8 + (bitInByte - 1)
            } else {
                (byteNum + 1) * 8 + 7
            }
        }

        // Extract bits from data
        var raw = 0L
        for (bitPos in bits) {
            val byteIdx = bitPos / 8
            val bitIdx = bitPos % 8
            val bitVal = if (byteIdx < data.size) {
                (data[byteIdx].toInt() ushr bitIdx) and 1
            } else {
                0
            }
            raw = (raw shl 1) or bitVal.toLong()
        }

        // Handle signed
        if (isSigned && raw and (1L shl (length - 1)) != 0L) {
            raw -= (1L shl length)
        }

        return raw
    }
}

/** A CAN message definition from a DBC file. */
data class DBCMessage(
    val canId: Int,             // 11-bit or 29-bit CAN arbitration ID
    val name: String,           // Human-readable message name
    val dlc: Int,               // Data Length Code (bytes)
    val transmitter: String,    // Transmitting ECU node name
    val signals: MutableMap<String, DBCSignal> = mutableMapOf(),
    var comment: String = "",
    val isExtended: Boolean = false,  // True for 29-bit CAN IDs
) {
    /** Decode all signals from raw CAN data. */
    fun decode(data: ByteArray): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        for ((sigName, sig) in signals) {
            try {
                result[sigName] = sig.decode(data)
            } catch (e: Exception) {
                Log.d(TAG, "Failed to decode signal $sigName in $name: ${e.message}")
            }
        }
        return result
    }
}

/** Complete DBC database — all messages from one or more DBC files. */
class DBCDatabase(
    val messages: MutableMap<Int, DBCMessage> = mutableMapOf(),
    val sourceFiles: MutableList<String> = mutableListOf(),
) {
    /** Decode a CAN frame if its ID is known. */
    fun decodeFrame(canId: Int, data: ByteArray): Map<String, Any>? {
        val msg = messages[canId] ?: return null
        val signals = msg.decode(data)
        return mapOf(
            "message" to msg.name,
            "transmitter" to msg.transmitter,
            "signals" to signals,
        )
    }

    fun getMessage(canId: Int): DBCMessage? = messages[canId]

    val totalMessages: Int get() = messages.size
    val totalSignals: Int get() = messages.values.sumOf { it.signals.size }
}

// ─────────────────────────────────────────────────────────────
// DBC file parser
// ─────────────────────────────────────────────────────────────

private val BO_RE = Regex("""^BO_\s+(\d+)\s+(\w+)\s*:\s*(\d+)\s+(\S+)""")
private val SG_RE = Regex(
    """^\s+SG_\s+(\w+)\s*:\s*(\d+)\|(\d+)@([01])([+-])\s+\(([^,]+),([^)]+)\)\s+\[([^|]+)\|([^\]]+)\]\s+"([^"]*)"\s+(.*)"""
)
private val CM_SG_RE = Regex("""^CM_\s+SG_\s+(\d+)\s+(\w+)\s+"((?:[^"\\]|\\.)*)"\s*;""")
private val CM_BO_RE = Regex("""^CM_\s+BO_\s+(\d+)\s+"((?:[^"\\]|\\.)*)"\s*;""")
private val VAL_RE = Regex("""^VAL_\s+(\d+)\s+(\w+)\s+(.*?)\s*;""")
private val VAL_PAIR_RE = Regex("""(\d+)\s+"([^"]*)"""")

/** Parse DBC content string into a DBCDatabase. */
fun parseDbcContent(content: String, fileName: String = ""): DBCDatabase {
    val db = DBCDatabase(sourceFiles = mutableListOf(fileName))
    var currentMsg: DBCMessage? = null

    // Main parse: line by line
    for (line in content.split("\n")) {
        val trimmed = line.trim()

        // Message definition
        val boMatch = BO_RE.find(trimmed)
        if (boMatch != null) {
            val rawId = boMatch.groupValues[1].toInt()
            val name = boMatch.groupValues[2]
            val dlc = boMatch.groupValues[3].toInt()
            val transmitter = boMatch.groupValues[4]

            // In DBC format, bit 31 flags extended CAN ID
            val flaggedExtended = rawId and 0x80000000.toInt() != 0
            val canId = if (flaggedExtended) rawId and 0x1FFFFFFF else rawId
            val isExtended = flaggedExtended || canId > 0x7FF

            currentMsg = DBCMessage(
                canId = canId,
                name = name,
                dlc = dlc,
                transmitter = transmitter,
                isExtended = isExtended,
            )
            db.messages[canId] = currentMsg
            continue
        }

        // Signal definition
        val sgMatch = SG_RE.find(line)
        if (sgMatch != null && currentMsg != null) {
            val sigName = sgMatch.groupValues[1]
            val sig = DBCSignal(
                name = sigName,
                startBit = sgMatch.groupValues[2].toInt(),
                length = sgMatch.groupValues[3].toInt(),
                byteOrder = sgMatch.groupValues[4].toInt(),
                isSigned = sgMatch.groupValues[5] == "-",
                factor = sgMatch.groupValues[6].toDouble(),
                offset = sgMatch.groupValues[7].toDouble(),
                minimum = sgMatch.groupValues[8].toDouble(),
                maximum = sgMatch.groupValues[9].toDouble(),
                unit = sgMatch.groupValues[10],
                receivers = sgMatch.groupValues[11].split(",").map { it.trim() }.filter { it.isNotEmpty() },
            )
            currentMsg.signals[sigName] = sig
            continue
        }

        // Reset current message context for non-signal lines
        if (trimmed.isNotEmpty() && !trimmed.startsWith("SG_") && currentMsg != null) {
            if (!line.startsWith(" ") && !line.startsWith("\t")) {
                currentMsg = null
            }
        }
    }

    // Apply comments
    for (match in CM_SG_RE.findAll(content)) {
        val msgId = match.groupValues[1].toInt()
        val sigName = match.groupValues[2]
        val comment = match.groupValues[3].replace("\\\"", "\"")
        db.messages[msgId]?.signals?.get(sigName)?.comment = comment
    }
    for (match in CM_BO_RE.findAll(content)) {
        val msgId = match.groupValues[1].toInt()
        val comment = match.groupValues[2].replace("\\\"", "\"")
        db.messages[msgId]?.comment = comment
    }

    // Apply value definitions
    for (match in VAL_RE.findAll(content)) {
        val msgId = match.groupValues[1].toInt()
        val sigName = match.groupValues[2]
        val valsStr = match.groupValues[3]
        val vals = parseValueDefinitions(valsStr)
        if (vals.isNotEmpty()) {
            db.messages[msgId]?.signals?.get(sigName)?.valueTable = vals
        }
    }

    Log.i(TAG, "Parsed DBC $fileName: ${db.totalMessages} messages, ${db.totalSignals} signals")
    return db
}

private fun parseValueDefinitions(valsStr: String): Map<Int, String> {
    val result = mutableMapOf<Int, String>()
    for (match in VAL_PAIR_RE.findAll(valsStr)) {
        result[match.groupValues[1].toInt()] = match.groupValues[2]
    }
    return result
}

/** Merge multiple DBC databases into one. Later DBs override earlier on conflict. */
fun mergeDatabases(vararg dbs: DBCDatabase): DBCDatabase {
    val merged = DBCDatabase()
    for (db in dbs) {
        merged.messages.putAll(db.messages)
        merged.sourceFiles.addAll(db.sourceFiles)
    }
    return merged
}

// ─────────────────────────────────────────────────────────────
// OEM DBC file sets
// ─────────────────────────────────────────────────────────────

private val OEM_DBC_FILES: Map<String, List<String>> = mapOf(
    "ford" to listOf(
        "ford_lincoln_base_pt.dbc", "ford_cgea1_2_ptcan_2011.dbc",
        "ford_cgea1_2_bodycan_2011.dbc", "ford_fusion_2018_pt.dbc",
        "ford_fusion_2018_adas.dbc", "FORD_CADS.dbc", "FORD_CADS_64.dbc",
    ),
    "lincoln" to listOf(
        "ford_lincoln_base_pt.dbc", "ford_cgea1_2_ptcan_2011.dbc",
        "ford_cgea1_2_bodycan_2011.dbc",
    ),
    "gm" to listOf(
        "gm_global_a_lowspeed.dbc", "gm_global_a_lowspeed_1818125.dbc",
        "gm_global_a_chassis.dbc", "gm_global_a_powertrain_expansion.dbc",
        "gm_global_a_object.dbc", "gm_global_a_high_voltage_management.dbc",
        "cadillac_ct6_powertrain.dbc", "cadillac_ct6_chassis.dbc",
        "cadillac_ct6_object.dbc",
    ),
    "chevrolet" to listOf(
        "gm_global_a_lowspeed.dbc", "gm_global_a_lowspeed_1818125.dbc",
        "gm_global_a_chassis.dbc", "gm_global_a_powertrain_expansion.dbc",
        "gm_global_a_object.dbc", "gm_global_a_high_voltage_management.dbc",
    ),
    "gmc" to listOf(
        "gm_global_a_lowspeed.dbc", "gm_global_a_lowspeed_1818125.dbc",
        "gm_global_a_chassis.dbc", "gm_global_a_powertrain_expansion.dbc",
        "gm_global_a_object.dbc",
    ),
    "buick" to listOf(
        "gm_global_a_lowspeed.dbc", "gm_global_a_lowspeed_1818125.dbc",
        "gm_global_a_chassis.dbc", "gm_global_a_powertrain_expansion.dbc",
    ),
    "cadillac" to listOf(
        "gm_global_a_lowspeed.dbc", "gm_global_a_lowspeed_1818125.dbc",
        "gm_global_a_chassis.dbc", "gm_global_a_powertrain_expansion.dbc",
        "gm_global_a_object.dbc", "gm_global_a_high_voltage_management.dbc",
        "cadillac_ct6_powertrain.dbc", "cadillac_ct6_chassis.dbc",
        "cadillac_ct6_object.dbc",
    ),
    "chrysler" to listOf(
        "chrysler_pacifica_2017_hybrid_private_fusion.dbc",
        "chrysler_cusw.dbc", "fca_giorgio.dbc",
    ),
    "dodge" to listOf(
        "chrysler_pacifica_2017_hybrid_private_fusion.dbc",
        "chrysler_cusw.dbc", "fca_giorgio.dbc",
    ),
    "jeep" to listOf(
        "chrysler_pacifica_2017_hybrid_private_fusion.dbc",
        "chrysler_cusw.dbc", "fca_giorgio.dbc",
    ),
    "ram" to listOf(
        "chrysler_pacifica_2017_hybrid_private_fusion.dbc",
        "chrysler_cusw.dbc", "fca_giorgio.dbc",
    ),
)

// WMI (first 3 chars of VIN) to manufacturer mapping
private val WMI_TO_MAKE: Map<String, String> = mapOf(
    // Ford
    "1FA" to "ford", "1FB" to "ford", "1FC" to "ford", "1FD" to "ford",
    "1FM" to "ford", "1FT" to "ford", "3FA" to "ford", "3FE" to "ford",
    "MAJ" to "ford",
    // Lincoln
    "1LN" to "lincoln", "2LM" to "lincoln", "5LM" to "lincoln",
    // Chevrolet
    "1G1" to "chevrolet", "1GC" to "chevrolet", "1GN" to "chevrolet",
    "2G1" to "chevrolet", "3G1" to "chevrolet",
    // GMC
    "1GT" to "gmc", "2GT" to "gmc", "3GT" to "gmc",
    // Buick
    "1G4" to "buick", "2G4" to "buick",
    // Cadillac
    "1G6" to "cadillac", "1GY" to "cadillac",
    // Chrysler
    "1C3" to "chrysler", "2C3" to "chrysler", "1C4" to "chrysler",
    "2C4" to "chrysler",
    // Dodge
    "1B3" to "dodge", "2B3" to "dodge", "1C6" to "dodge", "2C7" to "dodge",
    "3C6" to "dodge", "3D7" to "dodge",
    // Jeep
    "1J4" to "jeep", "1J8" to "jeep",
    // Ram
    "3C7" to "ram",
)

// Cache loaded databases
private val dbcCache = mutableMapOf<String, DBCDatabase>()

/** Load and merge all DBC files for a given OEM/make from Android assets. */
fun loadDbcForOem(context: Context, make: String): DBCDatabase {
    val makeLower = make.lowercase().trim()

    // Check cache
    dbcCache[makeLower]?.let { return it }

    val files = OEM_DBC_FILES[makeLower]
    if (files.isNullOrEmpty()) {
        Log.w(TAG, "No DBC files configured for make: $make")
        return DBCDatabase()
    }

    val databases = mutableListOf<DBCDatabase>()
    for (fname in files) {
        try {
            val content = context.assets.open("dbc/$fname").bufferedReader().readText()
            databases.add(parseDbcContent(content, fname))
        } catch (e: Exception) {
            Log.w(TAG, "DBC file not found in assets: dbc/$fname")
        }
    }

    if (databases.isEmpty()) return DBCDatabase()

    val merged = mergeDatabases(*databases.toTypedArray())
    dbcCache[makeLower] = merged
    Log.i(TAG, "Loaded DBC for $makeLower: ${merged.totalMessages} messages, " +
            "${merged.totalSignals} signals from ${databases.size} files")
    return merged
}

/** Auto-detect manufacturer from VIN and load appropriate DBC files. */
fun loadDbcForVin(context: Context, vin: String): DBCDatabase {
    if (vin.length < 3) {
        Log.w(TAG, "VIN too short for DBC auto-detect: $vin")
        return DBCDatabase()
    }

    val wmi = vin.take(3).uppercase()
    var make = WMI_TO_MAKE[wmi]

    // Fall back to first-2-char match
    if (make == null) {
        val wmi2 = vin.take(2).uppercase()
        make = WMI_TO_MAKE.entries.firstOrNull { it.key.take(2) == wmi2 }?.value
    }

    if (make == null) {
        Log.w(TAG, "Cannot detect make from VIN $vin (WMI=$wmi)")
        return DBCDatabase()
    }

    Log.i(TAG, "VIN $vin -> make=$make (WMI=$wmi)")
    return loadDbcForOem(context, make)
}

fun clearDbcCache() {
    dbcCache.clear()
}

// ─────────────────────────────────────────────────────────────
// Live broadcast decoder
// ─────────────────────────────────────────────────────────────

/** Decoded broadcast CAN message with all signal values. */
data class DecodedBroadcast(
    val timestamp: Long,
    val canId: Int,
    val messageName: String,
    val transmitter: String,
    val signals: Map<String, Any>,
    val rawData: ByteArray,
) {
    val canIdHex: String get() = "0x%03X".format(canId)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DecodedBroadcast) return false
        return canId == other.canId && rawData.contentEquals(other.rawData)
    }

    override fun hashCode(): Int = 31 * canId + rawData.contentHashCode()
}

/**
 * Decodes broadcast CAN frames in real-time using loaded DBC database.
 *
 * Tracks the latest value of every decoded signal and provides
 * a snapshot of the current vehicle state.
 */
class LiveBroadcastDecoder(val dbc: DBCDatabase) {
    // Latest decoded value: Pair(msgName, sigName) -> value
    private val latest = mutableMapOf<Pair<String, String>, Any>()
    private val units = mutableMapOf<Pair<String, String>, String>()
    private val timestamps = mutableMapOf<Int, Long>()
    private val msgNames = mutableMapOf<Int, String>()

    var totalDecoded: Int = 0; private set
    var totalUnknown: Int = 0; private set
    private val unknownIds = mutableSetOf<Int>()

    /** Decode a single CAN frame and update internal state. */
    fun decodeFrame(canId: Int, data: ByteArray, timestamp: Long = System.currentTimeMillis()): DecodedBroadcast? {
        val msg = dbc.getMessage(canId)
        if (msg == null) {
            totalUnknown++
            unknownIds.add(canId)
            return null
        }

        val signals = msg.decode(data)
        totalDecoded++
        timestamps[canId] = timestamp
        msgNames[canId] = msg.name

        for ((sigName, value) in signals) {
            val key = msg.name to sigName
            latest[key] = value
            msg.signals[sigName]?.let { units[key] = it.unit }
        }

        return DecodedBroadcast(
            timestamp = timestamp,
            canId = canId,
            messageName = msg.name,
            transmitter = msg.transmitter,
            signals = signals,
            rawData = data,
        )
    }

    /** Get current snapshot of all decoded signal values as JSON. */
    fun getSnapshot(): JsonObject {
        val snapshot = JsonObject()
        for ((key, value) in latest) {
            val (msgName, sigName) = key
            val unit = units[key] ?: ""

            val msgObj = snapshot.getAsJsonObject(msgName) ?: JsonObject().also {
                snapshot.add(msgName, it)
            }
            val sigObj = JsonObject().apply {
                when (value) {
                    is Long -> addProperty("value", value)
                    is Double -> addProperty("value", value)
                    is String -> addProperty("value", value)
                    else -> addProperty("value", value.toString())
                }
                addProperty("unit", unit)
            }
            msgObj.add(sigName, sigObj)
        }
        return snapshot
    }

    /** Get commonly-requested diagnostic signals as flat JSON. */
    fun getKeySignals(): JsonObject {
        val result = JsonObject()

        data class Search(val friendly: String, val candidates: List<Pair<String, String>>)

        val searches = listOf(
            Search("rpm", listOf("Engine" to "EngAout_N_Dsply", "Engine" to "EngAout_N_Actl",
                "EngVehicleSpThrottle" to "EngAout3_N_Actl", "PowertrainData" to "EngSpd")),
            Search("vehicle_speed_kph", listOf("BrakeSysFeatures" to "Veh_V_ActlBrk",
                "VehicleSpeed" to "VehicleSpeed", "EngVehicleSpThrottle" to "Veh_V_ActlEng")),
            Search("throttle_pct", listOf("EngVehicleSpThrottle" to "ApedPos_Pc_ActlArb",
                "GasPedalRegenCruise" to "GasPedal")),
            Search("steering_angle_deg", listOf("SteeringPinion" to "StePinComp_An_Est",
                "SteeringWheelAngle" to "SteeringWheelAngle", "BrakeSnData" to "SteWhlComp_An_Est")),
            Search("wheel_speed_fl", listOf("WheelSpeed" to "WhlFl_W_Meas", "WheelSpeed" to "WheelSpeedFL")),
            Search("wheel_speed_fr", listOf("WheelSpeed" to "WhlFr_W_Meas", "WheelSpeed" to "WheelSpeedFR")),
            Search("wheel_speed_rl", listOf("WheelSpeed" to "WhlRl_W_Meas", "WheelSpeed" to "WheelSpeedRL")),
            Search("wheel_speed_rr", listOf("WheelSpeed" to "WhlRr_W_Meas", "WheelSpeed" to "WheelSpeedRR")),
            Search("brake_pressed", listOf("BrakePedal" to "BrakeSensor",
                "EngBrakeData" to "BpedDrvAppl_D_Actl")),
            Search("gear", listOf("PowertrainData" to "TrnRng_D_Rq",
                "GearShifter" to "GearShifter", "Gear" to "GearPos_D_Actl")),
            Search("fuel_level_pct", listOf("Engine" to "FuelLvl_Pc_DsplyEng",
                "Fuel" to "FuelLvl_Pc_Dsply")),
            Search("cruise_active", listOf("EngBrakeData" to "CcStat_D_Actl",
                "CruiseButtons" to "CruiseControlActive")),
        )

        for (search in searches) {
            var found = false
            for ((msgPrefix, sigName) in search.candidates) {
                if (found) break
                for ((key, value) in latest) {
                    val (mn, sn) = key
                    if (msgPrefix in mn && sn == sigName) {
                        val unit = units[key] ?: ""
                        val sigObj = JsonObject().apply {
                            when (value) {
                                is Long -> addProperty("value", value)
                                is Double -> addProperty("value", value)
                                is String -> addProperty("value", value)
                                else -> addProperty("value", value.toString())
                            }
                            addProperty("unit", unit)
                            addProperty("source", "$mn.$sn")
                        }
                        result.add(search.friendly, sigObj)
                        found = true
                        break
                    }
                }
            }
        }

        return result
    }

    /** Get decoder statistics as JSON. */
    fun getStats(): JsonObject {
        return JsonObject().apply {
            addProperty("dbc_loaded", true)
            addProperty("messages", dbc.totalMessages)
            addProperty("signals", dbc.totalSignals)
            addProperty("frames_decoded", totalDecoded)
            addProperty("frames_unknown", totalUnknown)
            addProperty("unique_messages", msgNames.size)
            addProperty("unique_signals", latest.size)
        }
    }
}
