package com.harrydatabub.motherduck_aviation_data_android.domain

import com.harrydatabub.motherduck_aviation_data_android.data.Airline
import com.harrydatabub.motherduck_aviation_data_android.data.FlightFilter

/**
 * Which callsigns a board shows. Matching is on the ICAO airline designator, the first
 * three letters of an airline callsign (CPA, FDX).
 */
object Operators {
    /**
     * All-cargo operators by ICAO designator. Combination carriers that fly freighters
     * under their passenger callsigns (Cathay, Qantas, Emirates...) cannot be told apart
     * from their passenger flights on ADS-B, so they are not listed.
     */
    val CARGO: Set<String> = setOf(
        "ABR", "ABW", "ABX", "ADB", "AER", "AHK", "AJT", "ATN", "AZG", "BCS", "BOX", "CAO",
        "CKK", "CKS", "CLX", "CSS", "DAE", "DHK", "FDX", "GEC", "GTI", "ICV", "KYE", "LCO",
        "MPH", "NCA", "NPT", "PAC", "SOO", "SQC", "SWN", "TAY", "TMN", "UPS", "VDA", "WGN",
        "YZR",
    )

    /**
     * The user's airlines as ICAO designators. Accepts ICAO (CPA) or IATA (CX) codes
     * separated by commas or spaces; an IATA code maps to every airline using it.
     */
    fun parseMine(text: String, airlines: Map<String, Airline>): Set<String> {
        val byIata = airlines.values.filter { it.iata != null }.groupBy { it.iata!!.uppercase() }
        return text.uppercase().split(',', ' ', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }
            .flatMap { code ->
                when {
                    code.length == 3 && code.all { it.isLetter() } -> listOf(code)
                    code.length == 2 -> byIata[code].orEmpty().map { it.icao }
                    else -> emptyList()
                }
            }.toSet()
    }

    fun designator(callsign: String?): String? =
        callsign?.takeIf { it.length >= 4 && it.take(3).all { c -> c.isLetter() } }?.take(3)

    fun matches(callsign: String?, filter: FlightFilter, mine: Set<String>): Boolean = when (filter) {
        FlightFilter.ALL -> true
        FlightFilter.CARGO -> designator(callsign) in CARGO
        FlightFilter.MINE -> designator(callsign) in mine
    }
}
