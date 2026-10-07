package com.harrydatahub.groundkit.data

/** reference.airports */
data class Airport(
    val icao: String,
    val iata: String,
    val name: String,
    val country: String?,
    val lat: Double,
    val lon: Double,
    val timezone: String,
    val notamSource: String?,
)

/** marts.fct_airport_conditions: the latest METAR and TAF per airport. */
data class Conditions(
    val icao: String,
    val iata: String,
    val lat: Double,
    val lon: Double,
    val metarObservedAt: Long?,
    val metarRaw: String?,
    val flightCategory: String?,
    val windVariable: Boolean,
    val windDirDeg: Int?,
    val windSpeedKt: Int?,
    val windGustKt: Int?,
    val visibilitySm: Double?,
    val visibilityIsLowerBound: Boolean,
    val ceilingFt: Int?,
    val wxString: String?,
    val tempC: Double?,
    val dewpointC: Double?,
    val altimeterHpa: Double?,
    val tafIssuedAt: Long?,
    val tafValidFrom: Long?,
    val tafValidTo: Long?,
    val tafRaw: String?,
    /** Null when the airport has no NOTAM feed (unknown, not zero). */
    val notamsInForce: Long?,
)

/** marts.fct_airport_weather_hourly */
data class WeatherHour(
    val icao: String,
    val hourUtc: Long,
    val flightCategory: String?,
    val isIfr: Boolean?,
    val windSpeedKt: Int?,
    val windGustKt: Int?,
    val isGusty: Boolean?,
    val hasThunderstorm: Boolean?,
)

/** marts.fct_daily_airport_movements */
data class DailyMovements(val icao: String, val dayEpochDay: Long, val arrivals: Long, val departures: Long)

/**
 * One observed flight at an in-scope airport (marts.fct_arrivals or marts.fct_departures),
 * seen from that airport: `otherIcao` / `otherIata` is the origin of an arrival or the
 * destination of a departure.
 */
data class ObservedFlight(
    val airportIcao: String,
    val callsign: String?,
    val flightNumberIata: String?,
    val icao24: String?,
    val airlineName: String?,
    val otherIcao: String?,
    val otherIata: String?,
    val at: Long,
    /** Arrivals: minutes inside 50 NM. Departures: minutes to leave 50 NM. */
    val terminalMinutes: Double?,
    /** The backend's is_freighter: flown by an all-cargo operator. */
    val isFreighter: Boolean = false,
)

/** marts.fct_arrival_weather_impact, only what the app uses. */
data class ArrivalImpact(val arrivalIcao: String, val arrivedAt: Long, val terminalMinutes: Double?)

/** marts.fct_terminal_tracks, grouped into one line per tracked flight end. */
data class TrackLine(
    val airportIcao: String,
    val arrival: Boolean,
    val label: String,
    val lats: DoubleArray,
    val lons: DoubleArray,
)

/** marts.fct_notams, current rows only (the API exports `where is_current`). */
data class Notam(
    val key: String,
    val number: String?,
    val location: String?,
    val category: String?,
    val condition: String?,
    val startsAt: Long?,
    val endsAt: Long?,
    val isPermanent: Boolean,
    val isEstimated: Boolean,
    val schedule: String?,
    val hasSchedule: Boolean,
    val rawText: String?,
    val isRunwayClosure: Boolean,
)

data class Airline(val icao: String, val iata: String?, val name: String?)

/** Every warehouse table the app reads, parsed. */
data class Warehouse(
    val airports: List<Airport>,
    val airlines: Map<String, Airline>,
    val conditions: Map<String, Conditions>,
    val weatherHourly: List<WeatherHour>,
    val movements: List<DailyMovements>,
    val arrivals: List<ObservedFlight>,
    val departures: List<ObservedFlight>,
    val impact: List<ArrivalImpact>,
    val tracks: List<TrackLine>,
    val notams: List<Notam>,
    /** When the oldest table in this snapshot was fetched from the API. */
    val fetchedAt: Long,
    /** True when at least one table came from the on-device cache after a failed fetch. */
    val fromCache: Boolean,
    val version: Long,
)

/** One aircraft from /api/live/{icao}. */
data class LiveAircraft(
    val icao24: String,
    val callsign: String?,
    val lat: Double,
    val lon: Double,
    val altFt: Int,
    val onGround: Boolean,
    val speedKt: Int?,
    val trackDeg: Double?,
    val vrateFpm: Int?,
    /** The API's direction for this fix: inbound, outbound, ground or other; null if it had none. */
    val dir: String? = null,
    /** The API's is_freighter tag; null if it sent none. */
    val isFreighter: Boolean? = null,
)

data class LiveFeed(val at: Long, val source: String, val aircraft: List<LiveAircraft>)
