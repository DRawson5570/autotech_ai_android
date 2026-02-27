package net.aurorasentient.autotechgateway.elm

/**
 * VIN decoder — maps WMI prefix + model year code to year/make/model.
 * Port of vin_decoder.py.
 */

object VINDecoder {

    /** WMI → Manufacturer mapping (first 3 chars of VIN) */
    private val WMI_MAP = mapOf(
        // Ford
        "1FA" to "Ford", "1FB" to "Ford", "1FC" to "Ford", "1FD" to "Ford",
        "1FM" to "Ford", "1FT" to "Ford", "1FV" to "Ford", "1FW" to "Ford",
        "2FA" to "Ford", "2FB" to "Ford", "2FC" to "Ford", "2FD" to "Ford",
        "2FM" to "Ford", "2FT" to "Ford",
        "3FA" to "Ford", "3FB" to "Ford", "3FC" to "Ford", "3FD" to "Ford",
        "3FM" to "Ford", "3FT" to "Ford",
        "MAJ" to "Ford", "NM0" to "Ford", "WF0" to "Ford",
        // GM
        "1G1" to "Chevrolet", "1G2" to "Pontiac", "1GC" to "Chevrolet",
        "1GK" to "GMC", "1GM" to "Pontiac", "1GT" to "GMC",
        "2G1" to "Chevrolet", "2G2" to "Pontiac", "2GC" to "Chevrolet",
        "3G1" to "Chevrolet", "3GC" to "Chevrolet", "3GT" to "GMC",
        "1G6" to "Cadillac", "1GY" to "Cadillac",
        // Chrysler/Stellantis
        "1C3" to "Chrysler", "1C4" to "Chrysler/Dodge",
        "1C6" to "RAM", "1D7" to "Dodge", "1J4" to "Jeep", "1J8" to "Jeep",
        "2C3" to "Chrysler", "2D5" to "Dodge", "3C4" to "Chrysler",
        "3D7" to "Dodge",
        // Toyota
        "1NX" to "Toyota", "2T1" to "Toyota", "2T2" to "Toyota",
        "2T3" to "Toyota", "4T1" to "Toyota", "4T3" to "Toyota",
        "4T4" to "Toyota", "5TD" to "Toyota", "5TF" to "Toyota",
        "JTD" to "Toyota", "JTE" to "Toyota", "JTK" to "Toyota",
        "JTN" to "Toyota",
        // Honda
        "1HG" to "Honda", "2HG" to "Honda", "2HK" to "Honda",
        "5FN" to "Honda", "5J6" to "Honda", "JHM" to "Honda",
        "SHH" to "Honda",
        // Nissan
        "1N4" to "Nissan", "1N6" to "Nissan", "3N1" to "Nissan",
        "5N1" to "Nissan", "JN1" to "Nissan", "JN8" to "Nissan",
        // Hyundai
        "5NP" to "Hyundai", "KMH" to "Hyundai", "5NM" to "Hyundai",
        // Kia
        "KNA" to "Kia", "KND" to "Kia", "5XY" to "Kia",
        // BMW
        "WBA" to "BMW", "WBS" to "BMW M", "WBY" to "BMW i",
        "5UX" to "BMW",
        // Mercedes
        "WDB" to "Mercedes-Benz", "WDC" to "Mercedes-Benz", "WDD" to "Mercedes-Benz",
        "4JG" to "Mercedes-Benz", "55S" to "Mercedes-Benz",
        // VW/Audi
        "WVW" to "Volkswagen", "3VW" to "Volkswagen",
        "WAU" to "Audi", "WA1" to "Audi",
        // Subaru
        "JF1" to "Subaru", "JF2" to "Subaru", "4S3" to "Subaru",
        "4S4" to "Subaru",
        // Mazda
        "JM1" to "Mazda", "JM3" to "Mazda", "3MZ" to "Mazda",
        // Volvo
        "YV1" to "Volvo", "YV4" to "Volvo",
        // Tesla
        "5YJ" to "Tesla", "7SA" to "Tesla",
    )

    /** Model year code (position 10 in VIN) */
    private val YEAR_CODES = mapOf(
        'A' to 2010, 'B' to 2011, 'C' to 2012, 'D' to 2013, 'E' to 2014,
        'F' to 2015, 'G' to 2016, 'H' to 2017, 'J' to 2018, 'K' to 2019,
        'L' to 2020, 'M' to 2021, 'N' to 2022, 'P' to 2023, 'R' to 2024,
        'S' to 2025, 'T' to 2026, 'V' to 2027, 'W' to 2028, 'X' to 2029,
        'Y' to 2030,
        '1' to 2001, '2' to 2002, '3' to 2003, '4' to 2004, '5' to 2005,
        '6' to 2006, '7' to 2007, '8' to 2008, '9' to 2009,
    )

    data class VINInfo(
        val vin: String,
        val year: Int?,
        val make: String?,
        val isFord: Boolean
    )

    fun decode(vin: String): VINInfo {
        if (vin.length < 11) return VINInfo(vin, null, null, false)

        val wmi = vin.take(3).uppercase()
        val make = WMI_MAP[wmi]
        val yearChar = vin[9].uppercaseChar()
        val year = YEAR_CODES[yearChar]
        val isFord = FORD_WMI_PREFIXES.contains(wmi)

        return VINInfo(vin, year, make, isFord)
    }
}
