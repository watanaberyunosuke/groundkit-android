package com.harrydatabub.motherduck_aviation_data_android.domain

import com.harrydatabub.motherduck_aviation_data_android.data.Airline
import com.harrydatabub.motherduck_aviation_data_android.data.FlightFilter

/**
 * Which callsigns a board shows. Matching is on the ICAO airline designator, the first
 * three letters of an airline callsign (CPA, FDX).
 */
object Operators {
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
        FlightFilter.MINE -> designator(callsign) in mine
    }
}
