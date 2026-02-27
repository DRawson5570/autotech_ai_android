package net.aurorasentient.autotechgateway.elm

/**
 * OBD-II PID definitions and decoding.
 *
 * Port of pids.py — contains all standard Mode 01 PIDs with decode formulas.
 */

enum class PIDCategory {
    ENGINE, FUEL, AIR, TEMPERATURE, SPEED, OXYGEN, EMISSIONS, CATALYST, EVAP, MILEAGE, STATUS
}

data class PIDDefinition(
    val pid: Int,
    val name: String,
    val description: String,
    val unit: String,
    val category: PIDCategory,
    val bytes: Int = 2,
    val aliases: List<String> = emptyList(),
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
    }
}
