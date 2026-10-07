package com.harrydatabub.motherduck_aviation_data_android.domain

import com.harrydatabub.motherduck_aviation_data_android.data.Airport
import com.harrydatabub.motherduck_aviation_data_android.data.Conditions
import com.harrydatabub.motherduck_aviation_data_android.data.DailyMovements
import com.harrydatabub.motherduck_aviation_data_android.data.Notam
import com.harrydatabub.motherduck_aviation_data_android.data.TrackLine
import com.harrydatabub.motherduck_aviation_data_android.data.Warehouse
import com.harrydatabub.motherduck_aviation_data_android.data.WeatherHour
import java.time.ZoneId

/** One airport's share of the warehouse: what the Dive's per-airport queries return. */
data class AirportSnapshot(
    val airport: Airport,
    val zone: ZoneId,
    val conditions: Conditions?,
    val history: Map<String, CallsignHistory>,
    /** Median minutes inside 50 NM for arrivals / to leave it for departures, last 30 days. */
    val medianArrivalTerminalMin: Double?,
    val medianDepartureTerminalMin: Double?,
    val observedArrivals30d: Int,
    val observedDepartures30d: Int,
    val tracks: List<TrackLine>,
    /** NOTAMs in force for this aerodrome, newest first. */
    val notams: List<Notam>,
    val hourly: List<WeatherHour>,
    val movements: List<DailyMovements>,
    val codes: FlightCodes,
) {
    /** Fallbacks until history builds up, as in the Dive. */
    val terminalArrMin get() = medianArrivalTerminalMin ?: 15.0
    val terminalDepMin get() = medianDepartureTerminalMin ?: 10.0

    companion object {
        fun build(w: Warehouse, airport: Airport, now: Long): AirportSnapshot {
            val icao = airport.icao
            val zone = runCatching { ZoneId.of(airport.timezone) }.getOrDefault(ZoneId.of("UTC"))
            val since30 = now - 30 * DAY_MS
            return AirportSnapshot(
                airport = airport,
                zone = zone,
                conditions = w.conditions[icao],
                history = FlightHistory.build(w.arrivals, w.departures, icao, zone, now),
                medianArrivalTerminalMin = median(
                    w.impact.filter { it.arrivalIcao == icao && it.arrivedAt >= since30 }.mapNotNull { it.terminalMinutes },
                ),
                medianDepartureTerminalMin = median(
                    w.departures.filter { it.airportIcao == icao && it.at >= since30 }.mapNotNull { it.terminalMinutes },
                ),
                observedArrivals30d = w.arrivals.count { it.airportIcao == icao && it.at >= since30 },
                observedDepartures30d = w.departures.count { it.airportIcao == icao && it.at >= since30 },
                tracks = w.tracks.filter { it.airportIcao == icao },
                notams = w.notams.filter { it.location == icao }
                    .sortedWith(compareByDescending<Notam> { it.startsAt ?: 0L }.thenBy { it.number }),
                hourly = w.weatherHourly.filter { it.icao == icao && it.hourUtc >= now - 3 * DAY_MS }.sortedBy { it.hourUtc },
                movements = w.movements.filter { it.icao == icao && it.dayEpochDay >= now / DAY_MS - 30 }
                    .sortedBy { it.dayEpochDay },
                codes = FlightCodes(w.airlines, w.cargoOperators),
            )
        }
    }
}

/** The Dive's 7-day weather overview row for one airport. */
data class WeatherOverview(
    val icao: String,
    val iata: String,
    val hours: Int,
    val ifrShare: Double?,
    val gustyShare: Double?,
    val thunderstormShare: Double?,
    val maxGustKt: Int?,
)

object Overview {
    fun weather7d(w: Warehouse, now: Long): List<WeatherOverview> {
        val iataOf = w.airports.associate { it.icao to it.iata }
        return w.weatherHourly.filter { it.hourUtc >= now - 7 * DAY_MS }.groupBy { it.icao }
            .map { (icao, hours) ->
                fun share(f: (WeatherHour) -> Boolean?): Double? =
                    hours.mapNotNull(f).takeIf { it.isNotEmpty() }?.let { v -> v.count { it }.toDouble() / v.size }
                WeatherOverview(
                    icao = icao,
                    iata = iataOf[icao] ?: icao,
                    hours = hours.size,
                    ifrShare = share { it.isIfr },
                    gustyShare = share { it.isGusty },
                    thunderstormShare = share { it.hasThunderstorm },
                    maxGustKt = hours.mapNotNull { it.windGustKt }.maxOrNull(),
                )
            }.sortedBy { it.iata }
    }

    /** Airports busiest first, by arrivals over 30 days, as the Dive's picker orders them. */
    fun airportsByTraffic(w: Warehouse, now: Long): List<Airport> {
        val since = now - 30 * DAY_MS
        val counts = w.arrivals.filter { it.at >= since }.groupingBy { it.airportIcao }.eachCount()
        return w.airports.sortedWith(compareByDescending<Airport> { counts[it.icao] ?: 0 }.thenBy { it.iata })
    }
}
