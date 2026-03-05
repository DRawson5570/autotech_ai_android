package net.aurorasentient.autotechgateway.elm

/**
 * VIN decoder — maps WMI prefix + model year code to year/make/model.
 * Port of vin_decoder.py.
 */

object VINDecoder {

    /** WMI → Manufacturer mapping (first 3 chars of VIN)
     *  Ported from Python vin_decoder.py _WMI_TABLE */
    private val WMI_MAP = mapOf(
        // ── Ford ──
        "1FA" to "Ford", "1FB" to "Ford", "1FC" to "Ford", "1FD" to "Ford",
        "1FM" to "Ford", "1FT" to "Ford", "1FV" to "Ford", "1FW" to "Ford",
        "1ZV" to "Ford",
        "2FA" to "Ford", "2FB" to "Ford", "2FC" to "Ford", "2FD" to "Ford",
        "2FM" to "Ford", "2FT" to "Ford",
        "3FA" to "Ford", "3FB" to "Ford", "3FC" to "Ford", "3FD" to "Ford",
        "3FM" to "Ford", "3FT" to "Ford",
        "MAJ" to "Ford", "NM0" to "Ford", "WF0" to "Ford",
        // ── Lincoln ──
        "1LN" to "Lincoln", "2LN" to "Lincoln", "3LN" to "Lincoln",
        "5LM" to "Lincoln",
        // ── Chevrolet ──
        "1G1" to "Chevrolet", "1GC" to "Chevrolet", "2G1" to "Chevrolet",
        "3G1" to "Chevrolet", "KL7" to "Chevrolet",
        // ── GMC ──
        "1GK" to "GMC", "1GT" to "GMC", "2GT" to "GMC", "3GT" to "GMC",
        // ── Buick ──
        "1G4" to "Buick", "2G4" to "Buick",
        // ── Cadillac ──
        "1G6" to "Cadillac", "1GY" to "Cadillac",
        // ── Pontiac (legacy) ──
        "1G2" to "Pontiac", "1GM" to "Pontiac", "2G2" to "Pontiac",
        // ── Chrysler / Stellantis ──
        "1C3" to "Chrysler", "2C3" to "Chrysler", "3C4" to "Chrysler",
        "2C4" to "Chrysler",
        // ── Dodge ──
        "1B3" to "Dodge", "2B3" to "Dodge", "3B3" to "Dodge",
        "1B7" to "Dodge", "2B7" to "Dodge", "3B7" to "Dodge",
        "1D7" to "Dodge", "2D3" to "Dodge", "2D4" to "Dodge",
        "2D5" to "Dodge", "2D7" to "Dodge",
        // ── Jeep ──
        "1C4" to "Jeep", "1J4" to "Jeep", "1J8" to "Jeep",
        // ── Ram ──
        "1C6" to "Ram", "3C6" to "Ram", "3D7" to "Ram",
        // ── Toyota ──
        "JTD" to "Toyota", "JTE" to "Toyota", "JTM" to "Toyota",
        "JTN" to "Toyota", "JTK" to "Toyota", "JTL" to "Toyota",
        "1NX" to "Toyota", "2T1" to "Toyota", "2T3" to "Toyota",
        "4T1" to "Toyota", "4T3" to "Toyota", "4T4" to "Toyota",
        "5TD" to "Toyota", "5TF" to "Toyota", "5TN" to "Toyota",
        // ── Lexus ──
        "JTH" to "Lexus", "JTJ" to "Lexus", "2T2" to "Lexus", "5TJ" to "Lexus",
        // ── Honda ──
        "JHM" to "Honda", "1HG" to "Honda", "2HG" to "Honda", "2HK" to "Honda",
        "5FN" to "Honda", "5J6" to "Honda", "19X" to "Honda", "SHH" to "Honda",
        // ── Acura ──
        "JH4" to "Acura", "19U" to "Acura",
        // ── Nissan ──
        "JN1" to "Nissan", "JN8" to "Nissan",
        "1N4" to "Nissan", "1N6" to "Nissan",
        "3N1" to "Nissan", "3N6" to "Nissan", "5N1" to "Nissan",
        // ── Infiniti ──
        "JNK" to "Infiniti",
        // ── Subaru ──
        "JF1" to "Subaru", "JF2" to "Subaru", "4S3" to "Subaru", "4S4" to "Subaru",
        // ── Mazda ──
        "JM1" to "Mazda", "JM3" to "Mazda", "3MZ" to "Mazda",
        // ── Mitsubishi ──
        "JA3" to "Mitsubishi", "JA4" to "Mitsubishi", "JA7" to "Mitsubishi",
        "4A3" to "Mitsubishi", "4A4" to "Mitsubishi",
        // ── Hyundai ──
        "KMH" to "Hyundai", "5NP" to "Hyundai", "5NM" to "Hyundai",
        "KM8" to "Hyundai",
        // ── Kia ──
        "KNA" to "Kia", "KND" to "Kia", "5XY" to "Kia",
        // ── Genesis ──
        "KMT" to "Genesis",
        // ── BMW ──
        "WBA" to "BMW", "WBS" to "BMW", "WBY" to "BMW",
        "5UX" to "BMW", "5UJ" to "BMW",
        // ── Mercedes-Benz ──
        "WDB" to "Mercedes-Benz", "WDC" to "Mercedes-Benz",
        "WDD" to "Mercedes-Benz", "WDF" to "Mercedes-Benz",
        "55S" to "Mercedes-Benz", "4JG" to "Mercedes-Benz",
        // ── Audi ──
        "WAU" to "Audi", "WA1" to "Audi",
        // ── Volkswagen ──
        "WVW" to "Volkswagen", "WVG" to "Volkswagen",
        "1VW" to "Volkswagen", "3VW" to "Volkswagen",
        // ── Porsche ──
        "WP0" to "Porsche", "WP1" to "Porsche",
        // ── Volvo ──
        "YV1" to "Volvo", "YV4" to "Volvo", "7JR" to "Volvo",
        // ── Tesla ──
        "5YJ" to "Tesla", "7SA" to "Tesla",
        // ── Rivian ──
        "7PD" to "Rivian",
        // ── Lucid ──
        "7LU" to "Lucid",
        // ── Land Rover / Jaguar ──
        "SAL" to "Land Rover", "SAJ" to "Jaguar",
        // ── Mini ──
        "WMW" to "Mini",
        // ── Ferrari / Fiat ──
        "ZFF" to "Ferrari", "ZFA" to "Fiat",
        // ── Suzuki ──
        "JS1" to "Suzuki", "JS2" to "Suzuki",
        // ── Isuzu ──
        "JAA" to "Isuzu", "JAL" to "Isuzu",
    )

    /** Model year code (position 10 in VIN)
     *  Letters cycle every 30 years: A=1980 or 2010, B=1981 or 2011, etc.
     *  YEAR_CODES maps to the 2001+ cycle (most common today).
     *  YEAR_CODES_PRE_2010 maps to the 1980-2000 cycle for older vehicles. */
    private val YEAR_CODES = mapOf(
        'A' to 2010, 'B' to 2011, 'C' to 2012, 'D' to 2013, 'E' to 2014,
        'F' to 2015, 'G' to 2016, 'H' to 2017, 'J' to 2018, 'K' to 2019,
        'L' to 2020, 'M' to 2021, 'N' to 2022, 'P' to 2023, 'R' to 2024,
        'S' to 2025, 'T' to 2026, 'V' to 2027, 'W' to 2028, 'X' to 2029,
        'Y' to 2030,
        '1' to 2001, '2' to 2002, '3' to 2003, '4' to 2004, '5' to 2005,
        '6' to 2006, '7' to 2007, '8' to 2008, '9' to 2009,
    )

    /** Pre-2010 year codes (1980-2000 cycle)
     *  A=1980, B=1981, ..., H=1987, J=1988 (I/O/Q/U/Z never used), ..., Y=2000 */
    private val YEAR_CODES_PRE_2010 = mapOf(
        'A' to 1980, 'B' to 1981, 'C' to 1982, 'D' to 1983, 'E' to 1984,
        'F' to 1985, 'G' to 1986, 'H' to 1987, 'J' to 1988, 'K' to 1989,
        'L' to 1990, 'M' to 1991, 'N' to 1992, 'P' to 1993, 'R' to 1994,
        'S' to 1995, 'T' to 1996, 'V' to 1997, 'W' to 1998, 'X' to 1999,
        'Y' to 2000,
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
