package net.aurorasentient.autotechgateway.elm

/**
 * OBD-II PID definitions and decoding.
 *
 * Port of pids.py — contains all standard Mode 01 PIDs with decode formulas.
 */

enum class PIDCategory {
    ENGINE, FUEL, AIR, TEMPERATURE, SPEED, OXYGEN, EMISSIONS, CATALYST, EVAP, MILEAGE, STATUS,
    FREEZE_FRAME, VEHICLE_INFO
}

data class PIDDefinition(
    val pid: Int,
    val name: String,
    val description: String,
    val unit: String,
    val category: PIDCategory,
    val bytes: Int = 2,
    val aliases: List<String> = emptyList(),
    val minValue: Double = 0.0,
    val maxValue: Double = 255.0,
    val decode: (data: List<Int>) -> Double
)

/**
 * Registry of all known OBD-II PIDs.
 */
object PIDRegistry {

    private val byPid = mutableMapOf<Int, PIDDefinition>()
    private val byName = mutableMapOf<String, PIDDefinition>()

    init {
        registerAll()
    }

    private fun register(def: PIDDefinition) {
        byPid[def.pid] = def
        byName[def.name.uppercase()] = def
        for (alias in def.aliases) {
            byName[alias.uppercase()] = def
        }
    }

    fun getByPid(pid: Int): PIDDefinition? = byPid[pid]
    fun getByName(name: String): PIDDefinition? = byName[name.uppercase()]
    fun allPids(): Collection<PIDDefinition> = byPid.values

    fun resolve(nameOrHex: String): PIDDefinition? {
        // Try by name first
        byName[nameOrHex.uppercase()]?.let { return it }
        // Try as hex PID number
        val hex = nameOrHex.removePrefix("0x").removePrefix("0X")
        val num = hex.toIntOrNull(16) ?: return null
        return byPid[num]
    }

    // ── PID Registration ──────────────────────────────────────────────

    private fun registerAll() {
        // Engine
        register(PIDDefinition(0x04, "LOAD", "Calculated engine load", "%", PIDCategory.ENGINE, 1,
            listOf("ENGINE_LOAD", "CALC_LOAD")) { d -> d[0] * 100.0 / 255.0 })

        register(PIDDefinition(0x05, "COOLANT_TEMP", "Engine coolant temperature", "°C", PIDCategory.TEMPERATURE, 1,
            listOf("ECT", "COOLANT")) { d -> d[0] - 40.0 })

        register(PIDDefinition(0x06, "STFT_B1", "Short term fuel trim Bank 1", "%", PIDCategory.FUEL, 1,
            listOf("SHORT_FUEL_TRIM_1", "STFT1")) { d -> (d[0] - 128) * 100.0 / 128.0 })

        register(PIDDefinition(0x07, "LTFT_B1", "Long term fuel trim Bank 1", "%", PIDCategory.FUEL, 1,
            listOf("LONG_FUEL_TRIM_1", "LTFT1")) { d -> (d[0] - 128) * 100.0 / 128.0 })

        register(PIDDefinition(0x08, "STFT_B2", "Short term fuel trim Bank 2", "%", PIDCategory.FUEL, 1,
            listOf("SHORT_FUEL_TRIM_2", "STFT2")) { d -> (d[0] - 128) * 100.0 / 128.0 })

        register(PIDDefinition(0x09, "LTFT_B2", "Long term fuel trim Bank 2", "%", PIDCategory.FUEL, 1,
            listOf("LONG_FUEL_TRIM_2", "LTFT2")) { d -> (d[0] - 128) * 100.0 / 128.0 })

        register(PIDDefinition(0x0A, "FUEL_PRESSURE", "Fuel pressure (gauge)", "kPa", PIDCategory.FUEL, 1) { d ->
            d[0] * 3.0
        })

        register(PIDDefinition(0x0B, "MAP", "Intake manifold absolute pressure", "kPa", PIDCategory.AIR, 1,
            listOf("INTAKE_PRESSURE", "MANIFOLD_PRESSURE")) { d -> d[0].toDouble() })

        register(PIDDefinition(0x0C, "RPM", "Engine RPM", "rpm", PIDCategory.ENGINE, 2,
            listOf("ENGINE_RPM", "ENGINE_SPEED")) { d -> ((d[0] * 256) + d[1]) / 4.0 })

        register(PIDDefinition(0x0D, "SPEED", "Vehicle speed", "km/h", PIDCategory.SPEED, 1,
            listOf("VEHICLE_SPEED", "VSS")) { d -> d[0].toDouble() })

        register(PIDDefinition(0x0E, "TIMING_ADV", "Timing advance", "°", PIDCategory.ENGINE, 1,
            listOf("TIMING_ADVANCE", "SPARK_ADV")) { d -> (d[0] / 2.0) - 64.0 })

        register(PIDDefinition(0x0F, "IAT", "Intake air temperature", "°C", PIDCategory.TEMPERATURE, 1,
            listOf("INTAKE_TEMP", "INTAKE_AIR_TEMP")) { d -> d[0] - 40.0 })

        register(PIDDefinition(0x10, "MAF", "MAF air flow rate", "g/s", PIDCategory.AIR, 2,
            listOf("MAF_RATE", "MAF_FLOW")) { d -> ((d[0] * 256) + d[1]) / 100.0 })

        register(PIDDefinition(0x11, "THROTTLE_POS", "Throttle position", "%", PIDCategory.ENGINE, 1,
            listOf("THROTTLE", "TPS", "TP")) { d -> d[0] * 100.0 / 255.0 })

        register(PIDDefinition(0x14, "O2_B1S1", "O2 sensor voltage Bank 1 Sensor 1", "V", PIDCategory.OXYGEN, 2,
            listOf("O2_S1")) { d -> d[0] / 200.0 })

        register(PIDDefinition(0x15, "O2_B1S2", "O2 sensor voltage Bank 1 Sensor 2", "V", PIDCategory.OXYGEN, 2,
            listOf("O2_S2")) { d -> d[0] / 200.0 })

        register(PIDDefinition(0x16, "O2_B1S3", "O2 sensor voltage Bank 1 Sensor 3", "V", PIDCategory.OXYGEN, 2) { d ->
            d[0] / 200.0
        })

        register(PIDDefinition(0x17, "O2_B1S4", "O2 sensor voltage Bank 1 Sensor 4", "V", PIDCategory.OXYGEN, 2) { d ->
            d[0] / 200.0
        })

        register(PIDDefinition(0x18, "O2_B2S1", "O2 sensor voltage Bank 2 Sensor 1", "V", PIDCategory.OXYGEN, 2) { d ->
            d[0] / 200.0
        })

        register(PIDDefinition(0x19, "O2_B2S2", "O2 sensor voltage Bank 2 Sensor 2", "V", PIDCategory.OXYGEN, 2) { d ->
            d[0] / 200.0
        })

        register(PIDDefinition(0x1F, "RUN_TIME", "Engine run time", "s", PIDCategory.ENGINE, 2,
            listOf("RUNTIME", "ENGINE_RUN_TIME")) { d -> ((d[0] * 256) + d[1]).toDouble() })

        register(PIDDefinition(0x21, "DIST_MIL", "Distance with MIL on", "km", PIDCategory.MILEAGE, 2,
            listOf("DISTANCE_W_MIL")) { d -> ((d[0] * 256) + d[1]).toDouble() })

        register(PIDDefinition(0x23, "FUEL_RAIL_PRESSURE", "Fuel rail gauge pressure", "kPa", PIDCategory.FUEL, 2,
            listOf("FRP", "FUEL_RAIL")) { d -> ((d[0] * 256) + d[1]) * 10.0 })

        register(PIDDefinition(0x24, "AFR_B1S1", "Air-fuel ratio Bank 1 Sensor 1", "λ", PIDCategory.OXYGEN, 4,
            listOf("WIDEBAND_O2_S1", "LAMBDA_B1S1")) { d -> ((d[0] * 256) + d[1]) * 2.0 / 65536.0 })

        register(PIDDefinition(0x2C, "EGR", "Commanded EGR", "%", PIDCategory.EMISSIONS, 1,
            listOf("COMMANDED_EGR")) { d -> d[0] * 100.0 / 255.0 })

        register(PIDDefinition(0x2E, "EVAP_VP", "Commanded EVAP purge", "%", PIDCategory.EVAP, 1,
            listOf("EVAP_PURGE")) { d -> d[0] * 100.0 / 255.0 })

        register(PIDDefinition(0x2F, "FUEL_LEVEL", "Fuel tank level input", "%", PIDCategory.FUEL, 1,
            listOf("FUEL_TANK_LEVEL", "FUEL")) { d -> d[0] * 100.0 / 255.0 })

        register(PIDDefinition(0x30, "WARMUPS", "Warm-ups since codes cleared", "count", PIDCategory.STATUS, 1) { d ->
            d[0].toDouble()
        })

        register(PIDDefinition(0x31, "DIST_CLR", "Distance since codes cleared", "km", PIDCategory.MILEAGE, 2,
            listOf("DISTANCE_SINCE_CLR")) { d -> ((d[0] * 256) + d[1]).toDouble() })

        register(PIDDefinition(0x33, "BARO", "Barometric pressure", "kPa", PIDCategory.AIR, 1,
            listOf("BAROMETRIC_PRESSURE")) { d -> d[0].toDouble() })

        register(PIDDefinition(0x3C, "CAT_TEMP_B1S1", "Catalyst temperature Bank 1 Sensor 1", "°C", PIDCategory.CATALYST, 2,
            listOf("CAT_TEMP_1")) { d -> ((d[0] * 256) + d[1]) / 10.0 - 40.0 })

        register(PIDDefinition(0x3D, "CAT_TEMP_B2S1", "Catalyst temperature Bank 2 Sensor 1", "°C", PIDCategory.CATALYST, 2,
            listOf("CAT_TEMP_2")) { d -> ((d[0] * 256) + d[1]) / 10.0 - 40.0 })

        register(PIDDefinition(0x3E, "CAT_TEMP_B1S2", "Catalyst temperature Bank 1 Sensor 2", "°C", PIDCategory.CATALYST, 2) { d ->
            ((d[0] * 256) + d[1]) / 10.0 - 40.0
        })

        register(PIDDefinition(0x3F, "CAT_TEMP_B2S2", "Catalyst temperature Bank 2 Sensor 2", "°C", PIDCategory.CATALYST, 2) { d ->
            ((d[0] * 256) + d[1]) / 10.0 - 40.0
        })

        register(PIDDefinition(0x42, "CTRL_VOLTAGE", "Control module voltage", "V", PIDCategory.ENGINE, 2,
            listOf("MODULE_VOLTAGE", "ECU_VOLTAGE", "BATTERY_VOLTAGE")) { d -> ((d[0] * 256) + d[1]) / 1000.0 })

        register(PIDDefinition(0x43, "ABS_LOAD", "Absolute load value", "%", PIDCategory.ENGINE, 2,
            listOf("ABSOLUTE_LOAD")) { d -> ((d[0] * 256) + d[1]) * 100.0 / 255.0 })

        register(PIDDefinition(0x44, "COMMANDED_AFR", "Commanded air-fuel ratio", "λ", PIDCategory.FUEL, 2,
            listOf("EQUIV_RATIO", "LAMBDA")) { d -> ((d[0] * 256) + d[1]) * 2.0 / 65536.0 })

        register(PIDDefinition(0x46, "AMBIENT_TEMP", "Ambient air temperature", "°C", PIDCategory.TEMPERATURE, 1,
            listOf("AMBIENT", "OUTSIDE_TEMP")) { d -> d[0] - 40.0 })

        register(PIDDefinition(0x49, "ACCEL_POS_D", "Accelerator pedal position D", "%", PIDCategory.ENGINE, 1,
            listOf("ACCEL_D", "APP_D")) { d -> d[0] * 100.0 / 255.0 })

        register(PIDDefinition(0x4A, "ACCEL_POS_E", "Accelerator pedal position E", "%", PIDCategory.ENGINE, 1,
            listOf("ACCEL_E", "APP_E")) { d -> d[0] * 100.0 / 255.0 })

        register(PIDDefinition(0x5C, "OIL_TEMP", "Engine oil temperature", "°C", PIDCategory.TEMPERATURE, 1,
            listOf("ENGINE_OIL_TEMP")) { d -> d[0] - 40.0 })

        // ── Status / Metadata PIDs ─────────────────────────────────────

        register(PIDDefinition(0x01, "MONITOR_STATUS", "Monitor status since DTCs cleared", "", PIDCategory.ENGINE, 4,
            listOf("MIL_STATUS", "DTC_STATUS")) { d -> (d[0].toLong() * 16777216 + d[1] * 65536 + d[2] * 256 + d[3]).toDouble() })

        register(PIDDefinition(0x02, "FREEZE_DTC", "Freeze DTC", "", PIDCategory.FREEZE_FRAME, 2,
            listOf("DTC_FREEZE_FRAME")) { d -> ((d[0] * 256) + d[1]).toDouble() })

        register(PIDDefinition(0x03, "FUEL_STATUS", "Fuel system status", "", PIDCategory.FUEL, 2) { d ->
            d[0].toDouble()
        })

        register(PIDDefinition(0x12, "AIR_STATUS", "Commanded secondary air status", "encoded", PIDCategory.AIR, 1,
            listOf("SEC_AIR_STATUS", "SECONDARY_AIR")) { d -> d[0].toDouble() })

        register(PIDDefinition(0x13, "O2_SENSORS_PRESENT", "Oxygen sensors present (2 banks)", "", PIDCategory.OXYGEN, 1) { d ->
            d[0].toDouble()
        })

        // ── Additional O2 Sensors ─────────────────────────────────────

        register(PIDDefinition(0x1A, "O2_B2S3", "O2 sensor voltage Bank 2 Sensor 3", "V", PIDCategory.OXYGEN, 2,
            listOf("O2_BANK2_SENSOR3")) { d -> d[0] / 200.0 })

        register(PIDDefinition(0x1B, "O2_B2S4", "O2 sensor voltage Bank 2 Sensor 4", "V", PIDCategory.OXYGEN, 2,
            listOf("O2_BANK2_SENSOR4")) { d -> d[0] / 200.0 })

        register(PIDDefinition(0x1C, "OBD_STANDARD", "OBD standards this vehicle conforms to", "", PIDCategory.VEHICLE_INFO, 1,
            listOf("OBD_COMPLIANCE")) { d -> d[0].toDouble() })

        register(PIDDefinition(0x1D, "O2_SENSORS_ALT", "O2 sensors present (alternate)", "encoded", PIDCategory.OXYGEN, 1,
            listOf("O2_SENSORS_PRESENT_ALT")) { d -> d[0].toDouble() })

        register(PIDDefinition(0x1E, "AUX_INPUT", "Auxiliary input status", "", PIDCategory.ENGINE, 1,
            listOf("AUX_INPUT_STATUS", "PTO_STATUS")) { d -> (d[0] and 0x01).toDouble() })

        // ── PID Support Bitmaps ───────────────────────────────────────

        register(PIDDefinition(0x20, "PIDS_SUPPORTED_21_40", "PIDs supported [21-40] (bitmap)", "", PIDCategory.VEHICLE_INFO, 4) { d ->
            (d[0].toLong() * 16777216 + d[1] * 65536 + d[2] * 256 + d[3]).toDouble()
        })

        // ── Fuel / Pressure PIDs ──────────────────────────────────────

        register(PIDDefinition(0x22, "FUEL_RAIL_PRESSURE_VAC", "Fuel rail pressure (relative to vacuum)", "kPa", PIDCategory.FUEL, 2,
            listOf("FRP_VAC", "FUEL_RAIL_PRESS_VAC")) { d -> ((d[0] * 256) + d[1]) * 0.079 })

        // ── Wide-band O2 / Air-Fuel Ratio ─────────────────────────────

        register(PIDDefinition(0x25, "AFR_B1S2", "Air-fuel ratio Bank 1 Sensor 2", "λ", PIDCategory.OXYGEN, 4) { d ->
            ((d[0] * 256) + d[1]) * 2.0 / 65536.0
        })

        register(PIDDefinition(0x26, "O2_S3_WR_VOLTAGE", "O2 Sensor 3 WR lambda voltage", "V", PIDCategory.OXYGEN, 4,
            listOf("O2_S3_WR_V")) { d -> ((d[2] * 256) + d[3]) * 8.0 / 65535.0 })

        register(PIDDefinition(0x27, "O2_S4_WR_VOLTAGE", "O2 Sensor 4 WR lambda voltage", "V", PIDCategory.OXYGEN, 4) { d ->
            ((d[2] * 256) + d[3]) * 8.0 / 65535.0
        })

        register(PIDDefinition(0x28, "O2_S5_WR_VOLTAGE", "O2 Sensor 5 WR lambda voltage", "V", PIDCategory.OXYGEN, 4) { d ->
            ((d[2] * 256) + d[3]) * 8.0 / 65535.0
        })

        register(PIDDefinition(0x29, "O2_S6_WR_VOLTAGE", "O2 Sensor 6 WR lambda voltage", "V", PIDCategory.OXYGEN, 4) { d ->
            ((d[2] * 256) + d[3]) * 8.0 / 65535.0
        })

        register(PIDDefinition(0x2A, "O2_S7_WR_VOLTAGE", "O2 Sensor 7 WR lambda voltage", "V", PIDCategory.OXYGEN, 4) { d ->
            ((d[2] * 256) + d[3]) * 8.0 / 65535.0
        })

        register(PIDDefinition(0x2B, "O2_S8_WR_VOLTAGE", "O2 Sensor 8 WR lambda voltage", "V", PIDCategory.OXYGEN, 4) { d ->
            ((d[2] * 256) + d[3]) * 8.0 / 65535.0
        })

        // ── EGR / Emissions ───────────────────────────────────────────

        register(PIDDefinition(0x2D, "EGR_ERROR", "EGR error", "%", PIDCategory.EMISSIONS, 1,
            listOf("EGR_ERR")) { d -> (d[0] * 100.0 / 128.0) - 100.0 })

        // ── EVAP System ───────────────────────────────────────────────

        register(PIDDefinition(0x32, "EVAP_PURGE", "Commanded evaporative purge", "%", PIDCategory.EVAP, 1,
            listOf("EVAP_PCT")) { d -> d[0] * 100.0 / 255.0 })

        // ── Wide-band O2 with current ─────────────────────────────────

        register(PIDDefinition(0x34, "O2_B1S1_WR", "O2 Sensor 1 fuel-air equiv ratio & current", "λ", PIDCategory.OXYGEN, 4,
            listOf("WIDEBAND_O2_B1S1")) { d -> ((d[0] * 256) + d[1]) * 2.0 / 65536.0 })

        register(PIDDefinition(0x35, "O2_S2_WR_CURRENT", "O2 Sensor 2 WR lambda current", "mA", PIDCategory.OXYGEN, 4) { d ->
            ((d[2] * 256) + d[3]) / 256.0 - 128.0
        })

        register(PIDDefinition(0x36, "O2_S3_WR_CURRENT", "O2 Sensor 3 WR lambda current", "mA", PIDCategory.OXYGEN, 4) { d ->
            ((d[2] * 256) + d[3]) / 256.0 - 128.0
        })

        register(PIDDefinition(0x37, "O2_S4_WR_CURRENT", "O2 Sensor 4 WR lambda current", "mA", PIDCategory.OXYGEN, 4) { d ->
            ((d[2] * 256) + d[3]) / 256.0 - 128.0
        })

        register(PIDDefinition(0x38, "O2_S5_WR_CURRENT", "O2 Sensor 5 WR lambda current", "mA", PIDCategory.OXYGEN, 4) { d ->
            ((d[2] * 256) + d[3]) / 256.0 - 128.0
        })

        register(PIDDefinition(0x39, "O2_S6_WR_CURRENT", "O2 Sensor 6 WR lambda current", "mA", PIDCategory.OXYGEN, 4) { d ->
            ((d[2] * 256) + d[3]) / 256.0 - 128.0
        })

        register(PIDDefinition(0x3A, "O2_S7_WR_CURRENT", "O2 Sensor 7 WR lambda current", "mA", PIDCategory.OXYGEN, 4) { d ->
            ((d[2] * 256) + d[3]) / 256.0 - 128.0
        })

        register(PIDDefinition(0x3B, "O2_S8_WR_CURRENT", "O2 Sensor 8 WR lambda current", "mA", PIDCategory.OXYGEN, 4) { d ->
            ((d[2] * 256) + d[3]) / 256.0 - 128.0
        })

        // ── PID Support Bitmap (41-60) ────────────────────────────────

        register(PIDDefinition(0x40, "PIDS_SUPPORTED_41_60", "PIDs supported [41-60] (bitmap)", "", PIDCategory.VEHICLE_INFO, 4) { d ->
            (d[0].toLong() * 16777216 + d[1] * 65536 + d[2] * 256 + d[3]).toDouble()
        })

        register(PIDDefinition(0x41, "MONITOR_STATUS_DRIVE", "Monitor status this drive cycle", "", PIDCategory.ENGINE, 4,
            listOf("DRIVE_MONITOR")) { d -> (d[0].toLong() * 16777216 + d[1] * 65536 + d[2] * 256 + d[3]).toDouble() })

        // ── Throttle / Pedal PIDs ─────────────────────────────────────

        register(PIDDefinition(0x45, "REL_THROTTLE_POS", "Relative throttle position", "%", PIDCategory.ENGINE, 1,
            listOf("RELATIVE_THROTTLE")) { d -> d[0] * 100.0 / 255.0 })

        register(PIDDefinition(0x47, "ABS_THROTTLE_B", "Absolute throttle position B", "%", PIDCategory.ENGINE, 1,
            listOf("THROTTLE_POS_B")) { d -> d[0] * 100.0 / 255.0 })

        register(PIDDefinition(0x48, "ABS_THROTTLE_C", "Absolute throttle position C", "%", PIDCategory.ENGINE, 1,
            listOf("THROTTLE_POS_C")) { d -> d[0] * 100.0 / 255.0 })

        register(PIDDefinition(0x4B, "ACCEL_POS_F", "Accelerator pedal position F", "%", PIDCategory.ENGINE, 1,
            listOf("ACCELERATOR_POS_F")) { d -> d[0] * 100.0 / 255.0 })

        register(PIDDefinition(0x4C, "CMD_THROTTLE", "Commanded throttle actuator", "%", PIDCategory.ENGINE, 1,
            listOf("COMMANDED_THROTTLE")) { d -> d[0] * 100.0 / 255.0 })

        // ── Time / Distance PIDs ──────────────────────────────────────

        register(PIDDefinition(0x4D, "TIME_MIL_ON", "Time run with MIL on", "minutes", PIDCategory.MILEAGE, 2,
            listOf("MIL_TIME", "TIME_WITH_MIL")) { d -> ((d[0] * 256) + d[1]).toDouble() })

        register(PIDDefinition(0x4E, "TIME_SINCE_CLR", "Time since trouble codes cleared", "minutes", PIDCategory.MILEAGE, 2,
            listOf("TIME_SINCE_CLEAR", "CLR_TIME")) { d -> ((d[0] * 256) + d[1]).toDouble() })

        // ── Max / Metadata PIDs ───────────────────────────────────────

        register(PIDDefinition(0x4F, "MAX_VALUES", "Max equiv ratio / O2 voltage / O2 current / intake pressure", "", PIDCategory.ENGINE, 4,
            listOf("MAX_RATIO_V_I_PRESSURE")) { d -> d[0].toDouble() })

        register(PIDDefinition(0x50, "MAX_MAF", "Maximum MAF rate", "g/s", PIDCategory.AIR, 1,
            listOf("MAX_AIR_FLOW_RATE")) { d -> d[0] * 10.0 })

        register(PIDDefinition(0x51, "FUEL_TYPE", "Fuel type", "", PIDCategory.FUEL, 1) { d ->
            d[0].toDouble()
        })

        register(PIDDefinition(0x52, "ETHANOL_PCT", "Ethanol fuel percentage", "%", PIDCategory.FUEL, 1,
            listOf("ETHANOL_PERCENT", "FLEX_FUEL")) { d -> d[0] * 100.0 / 255.0 })

        // ── Extended EVAP PIDs ────────────────────────────────────────

        register(PIDDefinition(0x53, "EVAP_ABS_VP", "Absolute evap system vapor pressure", "kPa", PIDCategory.EVAP, 2,
            listOf("ABS_EVAP_PRESSURE")) { d -> ((d[0] * 256) + d[1]) / 200.0 })

        register(PIDDefinition(0x54, "EVAP_VP_ALT", "Evap system vapor pressure (wide range)", "Pa", PIDCategory.EVAP, 2,
            listOf("EVAP_PRESSURE_WIDE")) { d ->
            val raw = d[0] * 256 + d[1]; if (raw > 32767) (raw - 65536).toDouble() else raw.toDouble()
        })

        // ── Secondary O2 Trims ────────────────────────────────────────

        register(PIDDefinition(0x55, "SHORT_O2_TRIM_B1", "Short term secondary O2 trim Bank 1", "%", PIDCategory.OXYGEN, 1,
            listOf("STO2_TRIM_B1")) { d -> (d[0] * 100.0 / 128.0) - 100.0 })

        register(PIDDefinition(0x56, "LONG_O2_TRIM_B1", "Long term secondary O2 trim Bank 1", "%", PIDCategory.OXYGEN, 1,
            listOf("LTO2_TRIM_B1")) { d -> (d[0] * 100.0 / 128.0) - 100.0 })

        register(PIDDefinition(0x57, "SHORT_O2_TRIM_B2", "Short term secondary O2 trim Bank 2", "%", PIDCategory.OXYGEN, 1,
            listOf("STO2_TRIM_B2")) { d -> (d[0] * 100.0 / 128.0) - 100.0 })

        register(PIDDefinition(0x58, "LONG_O2_TRIM_B2", "Long term secondary O2 trim Bank 2", "%", PIDCategory.OXYGEN, 1,
            listOf("LTO2_TRIM_B2")) { d -> (d[0] * 100.0 / 128.0) - 100.0 })

        // ── Fuel System Extended ───────────────────────────────────────

        register(PIDDefinition(0x59, "FUEL_RAIL_ABS", "Fuel rail absolute pressure", "kPa", PIDCategory.FUEL, 2) { d ->
            ((d[0] * 256) + d[1]) * 10.0
        })

        register(PIDDefinition(0x5A, "REL_ACCEL_POS", "Relative accelerator pedal position", "%", PIDCategory.ENGINE, 1,
            listOf("RELATIVE_ACCEL", "REL_PEDAL_POS")) { d -> d[0] * 100.0 / 255.0 })

        register(PIDDefinition(0x5B, "HYBRID_BATTERY_LIFE", "Hybrid battery pack remaining life", "%", PIDCategory.ENGINE, 1,
            listOf("HV_BATTERY_LIFE", "BATTERY_SOH")) { d -> d[0] * 100.0 / 255.0 })

        register(PIDDefinition(0x5D, "FUEL_INJECT_TIMING", "Fuel injection timing", "°", PIDCategory.FUEL, 2,
            listOf("INJECTION_TIMING")) { d -> ((d[0] * 256) + d[1]) / 128.0 - 210.0 })

        register(PIDDefinition(0x5E, "FUEL_RATE", "Engine fuel rate", "L/h", PIDCategory.FUEL, 2,
            listOf("ENGINE_FUEL_RATE", "FUEL_CONSUMPTION")) { d -> ((d[0] * 256) + d[1]) / 20.0 })

        register(PIDDefinition(0x5F, "EMISSION_REQ", "Emission requirements", "encoded", PIDCategory.EMISSIONS, 1,
            listOf("EMISSION_STANDARD")) { d -> d[0].toDouble() })

        // ── PID Support Bitmaps (extended) ─────────────────────────────

        register(PIDDefinition(0x60, "PIDS_SUPPORTED_61_80", "PIDs supported [61-80] (bitmap)", "", PIDCategory.VEHICLE_INFO, 4) { d ->
            (d[0].toLong() * 16777216 + d[1] * 65536 + d[2] * 256 + d[3]).toDouble()
        })

        // ── Engine Torque PIDs ─────────────────────────────────────────

        register(PIDDefinition(0x61, "DRIVER_TORQUE_DEMAND", "Driver demand engine percent torque", "%", PIDCategory.ENGINE, 1,
            listOf("DEMAND_TORQUE", "DRIVER_TORQUE")) { d -> d[0] - 125.0 })

        register(PIDDefinition(0x62, "ACTUAL_TORQUE", "Actual engine percent torque", "%", PIDCategory.ENGINE, 1,
            listOf("ENGINE_TORQUE", "TORQUE_PCT")) { d -> d[0] - 125.0 })

        register(PIDDefinition(0x63, "REF_TORQUE", "Engine reference torque", "Nm", PIDCategory.ENGINE, 2,
            listOf("REFERENCE_TORQUE", "MAX_TORQUE")) { d -> ((d[0] * 256) + d[1]).toDouble() })

        register(PIDDefinition(0x64, "PERCENT_TORQUE_IDLE", "Engine percent torque at idle", "%", PIDCategory.ENGINE, 1,
            listOf("IDLE_TORQUE_PCT")) { d -> d[0] - 125.0 })

        register(PIDDefinition(0x65, "AUX_IO_STATUS", "Auxiliary input/output supported", "encoded", PIDCategory.ENGINE, 2,
            listOf("AUXILIARY_IO")) { d -> ((d[0] * 256) + d[1]).toDouble() })

        // ── Extended Air / MAF PIDs ────────────────────────────────────

        register(PIDDefinition(0x66, "MAF_SENSOR", "Mass air flow sensor A", "g/s", PIDCategory.AIR, 3,
            listOf("MAF_A", "MASS_AIR_FLOW_A")) { d -> ((d[1] * 256) + d[2]) / 32.0 })

        // ── Extended Temperature PIDs ──────────────────────────────────

        register(PIDDefinition(0x67, "ENGINE_COOLANT_TEMP_2", "Engine coolant temperature sensor A", "°C", PIDCategory.TEMPERATURE, 2,
            listOf("COOLANT_TEMP_2")) { d -> d[1] - 40.0 })

        register(PIDDefinition(0x68, "INTAKE_AIR_TEMP_2", "Intake air temperature sensor A", "°C", PIDCategory.TEMPERATURE, 2,
            listOf("IAT_2")) { d -> d[1] - 40.0 })

        // ── Extended EGR / Diesel PIDs ─────────────────────────────────

        register(PIDDefinition(0x69, "EGR_COMMANDED_2", "Commanded EGR A duty cycle", "%", PIDCategory.EMISSIONS, 2,
            listOf("EGR_2")) { d -> d[1] * 100.0 / 255.0 })

        register(PIDDefinition(0x6A, "DIESEL_AIR_INTAKE", "Commanded diesel intake air flow", "%", PIDCategory.AIR, 2,
            listOf("DIESEL_AIR_CMD")) { d -> d[1] * 100.0 / 255.0 })

        register(PIDDefinition(0x6B, "EGR_TEMP", "Exhaust gas recirculation temperature A", "°C", PIDCategory.TEMPERATURE, 2,
            listOf("EGR_TEMPERATURE")) { d -> d[1] - 40.0 })

        register(PIDDefinition(0x6C, "THROTTLE_CMD_2", "Commanded throttle actuator A", "%", PIDCategory.ENGINE, 2,
            listOf("THROTTLE_ACTUATOR_2")) { d -> d[1] * 100.0 / 255.0 })

        register(PIDDefinition(0x6D, "FUEL_PRESS_CTRL", "Fuel pressure control system pressure", "kPa", PIDCategory.FUEL, 3,
            listOf("FUEL_PRESSURE_CTRL")) { d -> ((d[1] * 256) + d[2]) * 10.0 })

        register(PIDDefinition(0x6E, "INJ_PRESS_CTRL", "Injection pressure control system pressure", "kPa", PIDCategory.FUEL, 3,
            listOf("INJECTION_PRESSURE_CTRL")) { d -> ((d[1] * 256) + d[2]) * 10.0 })

        // ── Turbocharger PIDs ──────────────────────────────────────────

        register(PIDDefinition(0x6F, "TURBO_INLET_PRESS", "Turbocharger A compressor inlet pressure", "kPa", PIDCategory.AIR, 3,
            listOf("TURBO_INLET_PRESSURE")) { d -> ((d[1] * 256) + d[2]) / 32.0 })

        register(PIDDefinition(0x70, "BOOST_PRESS_A", "Boost pressure A", "kPa", PIDCategory.AIR, 3,
            listOf("BOOST_PRESSURE")) { d -> ((d[1] * 256) + d[2]) / 32.0 })

        register(PIDDefinition(0x71, "VGT_CONTROL_A", "Variable geometry turbo control A position", "%", PIDCategory.ENGINE, 2,
            listOf("VGT_A")) { d -> d[1] * 100.0 / 255.0 })

        register(PIDDefinition(0x72, "WASTEGATE_A", "Wastegate A position", "%", PIDCategory.ENGINE, 2,
            listOf("WASTEGATE")) { d -> d[1] * 100.0 / 255.0 })

        register(PIDDefinition(0x73, "EXHAUST_PRESS", "Exhaust pressure Bank 1 Sensor 1", "kPa", PIDCategory.EMISSIONS, 3,
            listOf("EXHAUST_PRESSURE")) { d -> ((d[1] * 256) + d[2]) / 32.0 })

        register(PIDDefinition(0x74, "TURBO_RPM_A", "Turbocharger A RPM", "rpm", PIDCategory.ENGINE, 3,
            listOf("TURBO_RPM")) { d -> ((d[1] * 256) + d[2]) * 10.0 })

        // ── Turbocharger / Exhaust Temperature PIDs ────────────────────

        register(PIDDefinition(0x75, "TURBO_A_TEMP1", "Turbocharger A inlet temperature", "°C", PIDCategory.TEMPERATURE, 2,
            listOf("TURBO_A_TEMP")) { d -> ((d[0] * 256) + d[1]) / 10.0 - 40.0 })

        register(PIDDefinition(0x76, "TURBO_B_TEMP1", "Turbocharger B inlet temperature", "°C", PIDCategory.TEMPERATURE, 2,
            listOf("TURBO_B_TEMP")) { d -> ((d[0] * 256) + d[1]) / 10.0 - 40.0 })

        register(PIDDefinition(0x77, "CACT_TEMP", "Charge air cooler temperature A", "°C", PIDCategory.TEMPERATURE, 2,
            listOf("CACT", "INTERCOOLER_TEMP")) { d -> ((d[0] * 256) + d[1]) / 10.0 - 40.0 })

        register(PIDDefinition(0x78, "EGT_B1", "Exhaust gas temperature Bank 1 Sensor 1", "°C", PIDCategory.TEMPERATURE, 3,
            listOf("EGT_BANK1", "EGT1")) { d -> ((d[1] * 256) + d[2]) / 10.0 - 40.0 })

        register(PIDDefinition(0x79, "EGT_B2", "Exhaust gas temperature Bank 2 Sensor 1", "°C", PIDCategory.TEMPERATURE, 3,
            listOf("EGT_BANK2", "EGT2")) { d -> ((d[1] * 256) + d[2]) / 10.0 - 40.0 })

        // ── Diesel Particulate Filter PIDs ─────────────────────────────

        register(PIDDefinition(0x7A, "DPF_DIFF_PRESS_B1", "DPF differential pressure Bank 1", "kPa", PIDCategory.EMISSIONS, 3,
            listOf("DPF_PRESS_B1")) { d -> ((d[1] * 256) + d[2]) / 32.0 })

        register(PIDDefinition(0x7B, "DPF_DIFF_PRESS_B2", "DPF differential pressure Bank 2", "kPa", PIDCategory.EMISSIONS, 3,
            listOf("DPF_PRESS_B2")) { d -> ((d[1] * 256) + d[2]) / 32.0 })

        register(PIDDefinition(0x7C, "DPF_TEMP_B1", "DPF temperature Bank 1 inlet", "°C", PIDCategory.TEMPERATURE, 3,
            listOf("DPF_TEMP")) { d -> ((d[1] * 256) + d[2]) / 10.0 - 40.0 })

        register(PIDDefinition(0x7D, "NOX_NTE", "NOx NTE control area status", "encoded", PIDCategory.EMISSIONS, 1,
            listOf("NOX_NTE_STATUS")) { d -> d[0].toDouble() })

        register(PIDDefinition(0x7E, "PM_NTE", "PM NTE control area status", "encoded", PIDCategory.EMISSIONS, 1,
            listOf("PM_NTE_STATUS")) { d -> d[0].toDouble() })

        register(PIDDefinition(0x7F, "ENGINE_RUN_TIME_TOTAL", "Total engine run time", "s", PIDCategory.ENGINE, 5,
            listOf("TOTAL_RUN_TIME")) { d -> (d[1].toLong() * 16777216 + d[2] * 65536 + d[3] * 256 + d[4]).toDouble() })

        // ── PID Support Bitmap (81-A0) ─────────────────────────────────

        register(PIDDefinition(0x80, "PIDS_SUPPORTED_81_A0", "PIDs supported [81-A0] (bitmap)", "", PIDCategory.VEHICLE_INFO, 4) { d ->
            (d[0].toLong() * 16777216 + d[1] * 65536 + d[2] * 256 + d[3]).toDouble()
        })

        // ── NOx / Emissions Extended ──────────────────────────────────

        register(PIDDefinition(0x83, "NOX_SENSOR_PPM", "NOx sensor concentration sensor A", "ppm", PIDCategory.EMISSIONS, 3,
            listOf("NOX_PPM", "NOX_SENSOR")) { d -> ((d[1] * 256) + d[2]).toDouble() })

        register(PIDDefinition(0x84, "MANIFOLD_SURFACE_TEMP", "Manifold surface temperature", "°C", PIDCategory.TEMPERATURE, 1,
            listOf("MANIFOLD_TEMP")) { d -> d[0] - 40.0 })

        register(PIDDefinition(0x8D, "THROTTLE_POS_G", "Throttle position G", "%", PIDCategory.ENGINE, 1,
            listOf("THROTTLE_G")) { d -> d[0] * 100.0 / 255.0 })

        register(PIDDefinition(0x8E, "ENGINE_FRICTION", "Engine friction percent torque", "%", PIDCategory.ENGINE, 1,
            listOf("FRICTION_TORQUE")) { d -> d[0] - 125.0 })

        // ── Hybrid / EV PIDs ──────────────────────────────────────────

        register(PIDDefinition(0x9A, "HYBRID_BATT_PCT", "Hybrid/EV battery pack state of charge", "%", PIDCategory.ENGINE, 2,
            listOf("HV_BATTERY_SOC", "EV_BATTERY")) { d -> d[1] * 100.0 / 255.0 })

        // ── Fuel Rate Extended ─────────────────────────────────────────

        register(PIDDefinition(0x9D, "FUEL_RATE_2", "Engine fuel rate (grams/sec)", "g/s", PIDCategory.FUEL, 2,
            listOf("FUEL_RATE_GS")) { d -> ((d[0] * 256) + d[1]) / 50.0 })

        register(PIDDefinition(0x9E, "EXHAUST_FLOW", "Engine exhaust flow rate", "kg/hr", PIDCategory.EMISSIONS, 2,
            listOf("EXHAUST_FLOW_RATE")) { d -> ((d[0] * 256) + d[1]) / 5.0 })

        // ── PID Support Bitmap (A1-C0) ─────────────────────────────────

        register(PIDDefinition(0xA0, "PIDS_SUPPORTED_A1_C0", "PIDs supported [A1-C0] (bitmap)", "", PIDCategory.VEHICLE_INFO, 4) { d ->
            (d[0].toLong() * 16777216 + d[1] * 65536 + d[2] * 256 + d[3]).toDouble()
        })

        // ── Extended Vehicle PIDs ─────────────────────────────────────

        register(PIDDefinition(0xA2, "CYL_FUEL_RATE", "Cylinder fuel rate", "mg/stroke", PIDCategory.FUEL, 2,
            listOf("CYLINDER_FUEL_RATE")) { d -> ((d[0] * 256) + d[1]) / 32.0 })

        register(PIDDefinition(0xA3, "EVAP_PRESS_2", "Evaporative system vapor pressure (wide range)", "Pa", PIDCategory.EVAP, 3,
            listOf("EVAP_VAPOR_PRESS_2")) { d -> ((d[1] * 256) + d[2]).toDouble() })

        register(PIDDefinition(0xA4, "TRANS_GEAR", "Transmission actual gear", "gear", PIDCategory.SPEED, 2,
            listOf("TRANSMISSION_GEAR", "GEAR")) { d -> (d[1] shr 4).toDouble() })

        register(PIDDefinition(0xA5, "DEF_DOSING_PCT", "Diesel exhaust fluid dosing", "%", PIDCategory.EMISSIONS, 2,
            listOf("DEF_DOSING")) { d -> d[1] / 2.0 })

        register(PIDDefinition(0xA6, "ODOMETER", "Odometer (total vehicle distance)", "km", PIDCategory.MILEAGE, 4,
            listOf("TOTAL_DISTANCE", "VEHICLE_DISTANCE", "MILEAGE")) { d ->
            (d[0].toLong() * 16777216 + d[1] * 65536 + d[2] * 256 + d[3]).toDouble() / 10.0
        })

        register(PIDDefinition(0xA7, "NOX_SENSOR_2", "NOx sensor 2 concentration", "ppm", PIDCategory.EMISSIONS, 3,
            listOf("NOX_PPM_2")) { d -> ((d[1] * 256) + d[2]).toDouble() })

        register(PIDDefinition(0xA8, "NOX_CORRECTED_2", "NOx sensor 2 corrected", "ppm", PIDCategory.EMISSIONS, 3,
            listOf("NOX_CORRECTED")) { d -> ((d[1] * 256) + d[2]).toDouble() })

        register(PIDDefinition(0xAA, "SPEED_LIMITER", "Vehicle speed limiter set speed", "km/h", PIDCategory.SPEED, 1,
            listOf("VEH_SPEED_LIMIT")) { d -> d[0].toDouble() })

        register(PIDDefinition(0xAD, "CRANKCASE_VENT", "Crankcase ventilation pressure", "kPa", PIDCategory.ENGINE, 3,
            listOf("CRANKCASE_VENTILATION")) { d -> ((d[1] * 256) + d[2]) / 32.0 })

        register(PIDDefinition(0xAE, "EVAP_PURGE_PRESS", "Evaporative purge pressure sensor", "kPa", PIDCategory.EVAP, 3,
            listOf("PURGE_PRESSURE")) { d -> ((d[1] * 256) + d[2]) / 32.0 })

        register(PIDDefinition(0xAF, "EGR_AIR_FLOW_CMD", "EGR commanded fresh air flow", "%", PIDCategory.EMISSIONS, 3,
            listOf("EGR_AIR_FLOW")) { d -> d[1] * 100.0 / 255.0 })
    }
}
