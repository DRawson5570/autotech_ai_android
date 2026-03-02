package net.aurorasentient.autotechgateway.elm

/**
 * CAN Bus Sniffer — Passive traffic capture and UDS exchange parser.
 *
 * Kotlin port of can_sniffer.py — parses raw CAN frames from STN2120 STMA
 * (Monitor All) mode into structured UDS request/response exchanges.
 *
 * Used for reverse-engineering professional scan tool DID reads via Y-splitter.
 */

import android.util.Log
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentLinkedQueue

private const val TAG = "CanSniffer"

/** A single parsed CAN frame. */
data class CANFrame(
    val arbId: Int,
    val data: ByteArray,
    val timestamp: Long = System.currentTimeMillis(),
) {
    /** True if this is a request (arb ID in standard request range). */
    val isRequest: Boolean
        get() = arbId and 0x008 == 0 && arbId in 0x600..0x7FF

    /** True if this is a response (arb ID = request + 8). */
    val isResponse: Boolean
        get() = arbId and 0x008 != 0 && arbId in 0x608..0x7FF

    /** Hex string of arb ID. */
    val arbIdHex: String
        get() = "%03X".format(arbId)

    /** Hex string of data bytes. */
    val dataHex: String
        get() = data.joinToString(" ") { "%02X".format(it) }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CANFrame) return false
        return arbId == other.arbId && data.contentEquals(other.data)
    }

    override fun hashCode(): Int = 31 * arbId + data.contentHashCode()
}

/** A matched UDS request/response pair. */
data class UDSExchange(
    val requestArbId: Int,
    val responseArbId: Int,
    val service: Int,          // UDS service ID (e.g. 0x22)
    val did: Int = 0,          // DID number (for Service 0x22)
    val responseData: ByteArray = byteArrayOf(),
    val isPositive: Boolean = true,
    val nrc: Int = 0,          // Negative response code
    val timestamp: Long = System.currentTimeMillis(),
) {
    val moduleHex: String
        get() = "0x%03X".format(requestArbId)

    val didHex: String
        get() = "%04X".format(did)

    val responseHex: String
        get() = responseData.joinToString("") { "%02X".format(it) }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UDSExchange) return false
        return requestArbId == other.requestArbId && did == other.did
    }

    override fun hashCode(): Int = 31 * requestArbId + did
}

/** Module name lookup by arb ID. */
fun moduleNameForAddr(arbId: Int): String {
    // Check HS-CAN first
    for ((name, pair) in ECU_ADDRESSES) {
        if (pair.first == arbId || pair.second == arbId) return name
    }
    // Check Ford MS-CAN
    for ((name, pair) in FORD_MS_CAN_ADDRESSES) {
        if (pair.first == arbId || pair.second == arbId) return name
    }
    return "0x%03X".format(arbId)
}

/**
 * Parse a raw STMA output line into a CANFrame.
 *
 * STMA output format (with ATH1 ATS1):
 *     7E0 03 22 D0 02
 *     7E8 06 62 D0 02 1F FE
 */
fun parseStmaLine(line: String): CANFrame? {
    val trimmed = line.trim()
    if (trimmed.isEmpty() || trimmed == ">" || trimmed.startsWith("STMA")) return null

    // Regex: 3-char hex arb ID followed by space-separated hex bytes
    val regex = Regex("^([0-9A-Fa-f]{3})\\s+((?:[0-9A-Fa-f]{2}\\s*)+)$")
    val match = regex.matchEntire(trimmed) ?: return null

    val arbId = match.groupValues[1].toIntOrNull(16) ?: return null
    val dataStr = match.groupValues[2].trim()
    val bytes = dataStr.split("\\s+".toRegex()).mapNotNull {
        it.toIntOrNull(16)?.toByte()
    }.toByteArray()

    if (bytes.isEmpty()) return null

    return CANFrame(arbId = arbId, data = bytes)
}

/**
 * CAN sniffer session — accumulates frames and extracts UDS exchanges.
 *
 * Thread-safe: uses ConcurrentLinkedQueue for frame accumulation.
 */
class CanSnifferSession(
    val bus: String = "HS-CAN",
) {
    val frames = ConcurrentLinkedQueue<CANFrame>()
    private val exchanges = mutableListOf<UDSExchange>()
    private val labels = mutableMapOf<String, String>()  // "module:did" → label
    val startedAt: Long = System.currentTimeMillis()
    var running: Boolean = false
        private set

    val frameCount: Int get() = frames.size
    val exchangeCount: Int get() = exchanges.size

    fun addFrame(frame: CANFrame) {
        frames.add(frame)
    }

    fun setRunning(value: Boolean) {
        running = value
    }

    /**
     * Parse all accumulated frames into UDS exchanges.
     * Returns the exchanges found.
     */
    fun extractExchanges(): List<UDSExchange> {
        val frameList = frames.toList()
        val newExchanges = extractUdsExchanges(frameList)
        exchanges.clear()
        exchanges.addAll(newExchanges)
        return newExchanges
    }

    /**
     * Label a captured DID.
     */
    fun addLabel(module: String, did: String, label: String) {
        labels["$module:$did"] = label
    }

    fun getLabels(): Map<String, String> = labels.toMap()

    /**
     * Get unique DIDs grouped by module.
     * Returns Map<moduleHex, Map<didHex, responseDataHex>>
     */
    fun getDidData(): Map<String, Map<String, String>> {
        val result = mutableMapOf<String, MutableMap<String, String>>()
        for (ex in exchanges) {
            if (!ex.isPositive || ex.service != 0x22) continue
            val moduleKey = ex.moduleHex
            val dids = result.getOrPut(moduleKey) { mutableMapOf() }
            dids[ex.didHex] = ex.responseHex
        }
        return result
    }

    /**
     * Build a summary for the API response.
     */
    fun toSummary(): Map<String, Any> {
        val uniqueDids = mutableMapOf<String, MutableSet<String>>()
        for (ex in exchanges) {
            if (ex.service == 0x22 && ex.isPositive) {
                uniqueDids.getOrPut(ex.moduleHex) { mutableSetOf() }.add(ex.didHex)
            }
        }
        return mapOf(
            "frame_count" to frameCount,
            "exchange_count" to exchangeCount,
            "unique_modules" to uniqueDids.size,
            "unique_dids" to uniqueDids.mapValues { it.value.size },
            "bus" to bus,
            "duration_ms" to (System.currentTimeMillis() - startedAt),
        )
    }

    fun clear() {
        frames.clear()
        exchanges.clear()
        labels.clear()
    }
}

/**
 * Extract UDS request/response exchanges from a list of CAN frames.
 *
 * Matches requests (arb ID) with responses (arb ID + 8).
 * Extracts Service 0x22 (ReadDataByIdentifier) exchanges.
 * Handles ISO-TP multi-frame responses.
 */
fun extractUdsExchanges(frames: List<CANFrame>): List<UDSExchange> {
    val exchanges = mutableListOf<UDSExchange>()
    var i = 0

    while (i < frames.size) {
        val frame = frames[i]

        // Only process request frames (even arb IDs in range)
        if (!frame.isRequest || frame.data.size < 3) {
            i++
            continue
        }

        // PCI byte (first byte) gives data length for single-frame
        val pci = frame.data[0].toInt() and 0xFF
        if (pci > 7 || pci < 2) {
            // Not a valid single-frame UDS request
            i++
            continue
        }

        val serviceId = frame.data[1].toInt() and 0xFF

        // Only handle Service 0x22 (ReadDataByIdentifier) for now
        if (serviceId != 0x22 || pci < 3 || frame.data.size < 4) {
            i++
            continue
        }

        val didHigh = frame.data[2].toInt() and 0xFF
        val didLow = frame.data[3].toInt() and 0xFF
        val did = (didHigh shl 8) or didLow

        val expectedRespId = frame.arbId + 8

        // Look for response in next frames (within 20 frames window)
        val searchEnd = minOf(i + 20, frames.size)
        var foundResp = false

        for (j in i + 1 until searchEnd) {
            val resp = frames[j]
            if (resp.arbId != expectedRespId || resp.data.size < 2) continue

            val respPci = resp.data[0].toInt() and 0xFF
            val respServiceId = resp.data[1].toInt() and 0xFF

            when {
                // Positive response: 0x62 (0x22 + 0x40)
                respServiceId == 0x62 && respPci >= 5 && resp.data.size >= 4 -> {
                    val respDidH = resp.data[2].toInt() and 0xFF
                    val respDidL = resp.data[3].toInt() and 0xFF
                    val respDid = (respDidH shl 8) or respDidL

                    if (respDid == did) {
                        // Extract response data (bytes after DID)
                        val dataLen = respPci - 3  // PCI minus service(1) + DID(2)
                        val responseData = if (resp.data.size >= 4 + dataLen) {
                            resp.data.sliceArray(4 until 4 + dataLen)
                        } else {
                            resp.data.sliceArray(4 until resp.data.size)
                        }

                        exchanges.add(
                            UDSExchange(
                                requestArbId = frame.arbId,
                                responseArbId = resp.arbId,
                                service = serviceId,
                                did = did,
                                responseData = responseData,
                                isPositive = true,
                                timestamp = frame.timestamp,
                            )
                        )
                        foundResp = true
                        break
                    }
                }

                // First frame (ISO-TP multi-frame) — 0x1X prefix
                (respPci and 0xF0) == 0x10 && resp.data.size >= 5 -> {
                    val mfServiceId = resp.data[2].toInt() and 0xFF
                    if (mfServiceId == 0x62 && resp.data.size >= 6) {
                        val mfDidH = resp.data[3].toInt() and 0xFF
                        val mfDidL = resp.data[4].toInt() and 0xFF
                        val mfDid = (mfDidH shl 8) or mfDidL

                        if (mfDid == did) {
                            // Reassemble multi-frame
                            val totalLen = ((respPci and 0x0F) shl 8) or (resp.data[1].toInt() and 0xFF)
                            val firstData = resp.data.sliceArray(5 until resp.data.size)
                            val assembled = mutableListOf<Byte>()
                            assembled.addAll(firstData.toList())

                            // Collect consecutive frames
                            for (k in j + 1 until minOf(j + 30, frames.size)) {
                                val cf = frames[k]
                                if (cf.arbId != expectedRespId || cf.data.isEmpty()) continue
                                val cfPci = cf.data[0].toInt() and 0xFF
                                if ((cfPci and 0xF0) != 0x20) continue // Not a consecutive frame

                                assembled.addAll(cf.data.drop(1))
                                // Check if we have enough data (totalLen minus service+DID = totalLen-3)
                                if (assembled.size >= totalLen - 3) break
                            }

                            exchanges.add(
                                UDSExchange(
                                    requestArbId = frame.arbId,
                                    responseArbId = resp.arbId,
                                    service = serviceId,
                                    did = did,
                                    responseData = assembled.toByteArray(),
                                    isPositive = true,
                                    timestamp = frame.timestamp,
                                )
                            )
                            foundResp = true
                            break
                        }
                    }
                }

                // Negative response (0x7F)
                respServiceId == 0x7F && resp.data.size >= 4 -> {
                    val rejectedService = resp.data[2].toInt() and 0xFF
                    val nrc = resp.data[3].toInt() and 0xFF

                    if (rejectedService == 0x22) {
                        exchanges.add(
                            UDSExchange(
                                requestArbId = frame.arbId,
                                responseArbId = resp.arbId,
                                service = serviceId,
                                did = did,
                                isPositive = false,
                                nrc = nrc,
                                timestamp = frame.timestamp,
                            )
                        )
                        foundResp = true
                        break
                    }
                }
            }
        }

        i++
    }

    return exchanges
}
