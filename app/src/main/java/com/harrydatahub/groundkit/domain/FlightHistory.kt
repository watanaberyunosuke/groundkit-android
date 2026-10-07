package com.harrydatahub.groundkit.domain

import com.harrydatahub.groundkit.data.ObservedFlight
import java.time.Instant
import java.time.ZoneId

enum class Dir { INBOUND, OUTBOUND }

/**
 * What 30 days of observations say about one callsign in one direction at one airport.
 *
 * @property other usual origin (inbound) or destination (outbound), IATA where known
 * @property usualMin usual local time of day, minutes after midnight
 * @property days14 local days it was seen on out of the last 14
 * @property recent latest observed times (epoch ms), newest first
 */
data class Usual(
    val other: String?,
    val usualMin: Double,
    val days14: Int,
    val count: Int,
    val recent: List<Long>,
    /** Tagged by the backend as flown by an all-cargo operator. */
    val freighter: Boolean = false,
)

data class CallsignHistory(val inbound: Usual? = null, val outbound: Usual? = null) {
    operator fun get(dir: Dir) = if (dir == Dir.INBOUND) inbound else outbound
}

/**
 * Port of the Dive's `historyQ`: callsigns seen arriving at / departing from the airport in
 * the last 30 days, with their usual origin / destination and usual local time. Flight
 * numbers repeat daily, so a live aircraft with one of these callsigns is very likely
 * inbound / outbound now.
 *
 * The usual time is the median offset from the flight's first observed time, wrapped, so
 * flights either side of midnight average correctly.
 */
object FlightHistory {
    fun build(
        arrivals: List<ObservedFlight>,
        departures: List<ObservedFlight>,
        icao: String,
        zone: ZoneId,
        now: Long,
    ): Map<String, CallsignHistory> {
        val since30 = now - 30 * DAY_MS
        val since14 = now - 14 * DAY_MS
        fun summarise(flights: List<ObservedFlight>): Map<String, Usual> =
            flights.asSequence()
                .filter { it.airportIcao == icao && it.at >= since30 && it.callsign != null }
                .groupBy { it.callsign!! }
                .mapValues { (_, seen) ->
                    val first = seen.minBy { it.at }
                    val ref = minuteOfDay(first.at, zone).toDouble()
                    val offsets = seen.map { wrapMinutes(minuteOfDay(it.at, zone) - ref) }
                    val usual = ((ref + median(offsets)!!) % 1440 + 1440) % 1440
                    val days = seen.filter { it.at >= since14 }
                        .map { Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() }
                        .toSet().size
                    Usual(
                        other = mode(seen.map { it.otherIata ?: it.otherIcao }),
                        usualMin = usual,
                        days14 = days,
                        count = seen.size,
                        recent = seen.map { it.at }.sortedDescending().take(7),
                        freighter = seen.any { it.isFreighter },
                    )
                }

        val inbound = summarise(arrivals)
        val outbound = summarise(departures)
        return (inbound.keys + outbound.keys).associateWith {
            CallsignHistory(inbound = inbound[it], outbound = outbound[it])
        }
    }

    /** Most frequent non-null value; ties go to the alphabetically first. */
    private fun mode(values: List<String?>): String? =
        values.filterNotNull().groupingBy { it }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .firstOrNull()?.key
}
