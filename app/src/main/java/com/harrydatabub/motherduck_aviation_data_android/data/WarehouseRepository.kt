package com.harrydatabub.motherduck_aviation_data_android.data

import com.harrydatabub.motherduck_aviation_data_android.data.parquet.Parquet
import com.harrydatabub.motherduck_aviation_data_android.data.parquet.ParquetTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/**
 * Loads every table the app needs from [AviationApi] and maps the Parquet columns to
 * models. The tables are the same allow-listed exports the web Dive loads into
 * DuckDB-WASM; the SQL the Dive runs on them is done in Kotlin in the domain package.
 */
class WarehouseRepository(private val api: AviationApi) {
    private val versions = AtomicLong()

    /** Builds a snapshot from the disk cache only, for an instant start. Null if incomplete. */
    suspend fun loadCached(): Warehouse? = withContext(Dispatchers.Default) {
        val tables = TABLES.associateWith { api.cachedTable(it) }
        if (ESSENTIAL.any { tables[it] == null }) return@withContext null
        runCatching { build(tables) }.getOrNull()
    }

    /**
     * Fetches every table (falling back to the disk cache table by table). Fails only if an
     * essential table is unavailable from both; other tables come back empty instead.
     */
    suspend fun refresh(): Warehouse = coroutineScope {
        val fetched = TABLES.map { name ->
            async { name to runCatching { api.table(name) } }
        }.awaitAll().toMap()
        ESSENTIAL.forEach { name ->
            fetched.getValue(name).exceptionOrNull()?.let { throw it }
        }
        withContext(Dispatchers.Default) { build(fetched.mapValues { (_, r) -> r.getOrNull() }) }
    }

    private fun build(tables: Map<String, AviationApi.TableBytes?>): Warehouse {
        fun parse(name: String): ParquetTable? = tables[name]?.let { Parquet.read(it.bytes) }
        val present = tables.values.filterNotNull()
        return Warehouse(
            airports = parse(AIRPORTS)?.let(::airports).orEmpty(),
            airlines = parse(AIRLINES)?.let(::airlines).orEmpty(),
            cargoOperators = parse(CARGO_OPERATORS)?.let(::cargoOperators).orEmpty(),
            conditions = parse(CONDITIONS)?.let(::conditions).orEmpty(),
            weatherHourly = parse(WEATHER_HOURLY)?.let(::weatherHourly).orEmpty(),
            movements = parse(MOVEMENTS)?.let(::movements).orEmpty(),
            arrivals = parse(ARRIVALS)?.let { flights(it, arrival = true) }.orEmpty(),
            departures = parse(DEPARTURES)?.let { flights(it, arrival = false) }.orEmpty(),
            impact = parse(IMPACT)?.let(::impact).orEmpty(),
            tracks = parse(TRACKS)?.let(::tracks).orEmpty(),
            notams = parse(NOTAMS)?.let(::notams).orEmpty(),
            fetchedAt = present.minOfOrNull { it.fetchedAt } ?: System.currentTimeMillis(),
            fromCache = present.any { it.fromCache } || present.size < tables.size,
            version = versions.incrementAndGet(),
        )
    }

    private fun airports(t: ParquetTable): List<Airport> {
        val icao = t["icao"]; val iata = t["iata"]; val name = t["name"]; val country = t["country"]
        val lat = t["lat"]; val lon = t["lon"]; val tz = t["timezone"]; val notam = t["notam_source"]
        return (0 until t.numRows).mapNotNull { r ->
            val code = icao.string(r) ?: return@mapNotNull null
            Airport(
                icao = code,
                iata = iata.string(r) ?: code,
                name = name.string(r) ?: code,
                country = country.string(r),
                lat = lat.double(r) ?: return@mapNotNull null,
                lon = lon.double(r) ?: return@mapNotNull null,
                timezone = tz.string(r) ?: "UTC",
                notamSource = notam.string(r),
            )
        }
    }

    private fun airlines(t: ParquetTable): Map<String, Airline> {
        val icao = t["icao"]; val iata = t["iata"]; val name = t["name"]
        return (0 until t.numRows).mapNotNull { r ->
            icao.string(r)?.let { Airline(it, iata.string(r), name.string(r)) }
        }.associateBy { it.icao }
    }

    private fun cargoOperators(t: ParquetTable): Set<String> {
        val icao = t["icao"]
        return (0 until t.numRows).mapNotNull { icao.string(it) }.toSet()
    }

    private fun conditions(t: ParquetTable): Map<String, Conditions> {
        val c = { n: String -> t[n] }
        val icao = c("icao"); val iata = c("iata"); val lat = c("lat"); val lon = c("lon")
        val metarAt = c("metar_observed_at"); val metar = c("metar_raw"); val cat = c("flight_category")
        val vrb = c("wind_variable"); val dir = c("wind_dir_deg"); val spd = c("wind_speed_kt")
        val gust = c("wind_gust_kt"); val vis = c("visibility_sm"); val visLb = c("visibility_is_lower_bound")
        val ceil = c("ceiling_ft"); val wx = c("wx_string"); val temp = c("temp_c"); val dew = c("dewpoint_c")
        val qnh = c("altimeter_hpa"); val tafAt = c("taf_issued_at"); val tafFrom = c("taf_valid_from")
        val tafTo = c("taf_valid_to"); val taf = c("taf_raw"); val notams = c("notams_in_force")
        return (0 until t.numRows).mapNotNull { r ->
            val code = icao.string(r) ?: return@mapNotNull null
            Conditions(
                icao = code, iata = iata.string(r) ?: code,
                lat = lat.double(r) ?: 0.0, lon = lon.double(r) ?: 0.0,
                metarObservedAt = metarAt.epochMillis(r), metarRaw = metar.string(r),
                flightCategory = cat.string(r), windVariable = vrb.bool(r) == true,
                windDirDeg = dir.int(r), windSpeedKt = spd.int(r), windGustKt = gust.int(r),
                visibilitySm = vis.double(r), visibilityIsLowerBound = visLb.bool(r) == true,
                ceilingFt = ceil.int(r), wxString = wx.string(r),
                tempC = temp.double(r), dewpointC = dew.double(r), altimeterHpa = qnh.double(r),
                tafIssuedAt = tafAt.epochMillis(r), tafValidFrom = tafFrom.epochMillis(r),
                tafValidTo = tafTo.epochMillis(r), tafRaw = taf.string(r),
                notamsInForce = notams.long(r),
            )
        }.associateBy { it.icao }
    }

    private fun weatherHourly(t: ParquetTable): List<WeatherHour> {
        val icao = t["icao"]; val hour = t["hour_utc"]; val cat = t["flight_category"]
        val ifr = t["is_ifr"]; val spd = t["wind_speed_kt"]; val gust = t["wind_gust_kt"]
        val gusty = t["is_gusty"]; val ts = t["has_thunderstorm"]
        return (0 until t.numRows).mapNotNull { r ->
            WeatherHour(
                icao = icao.string(r) ?: return@mapNotNull null,
                hourUtc = hour.epochMillis(r) ?: return@mapNotNull null,
                flightCategory = cat.string(r), isIfr = ifr.bool(r),
                windSpeedKt = spd.int(r), windGustKt = gust.int(r),
                isGusty = gusty.bool(r), hasThunderstorm = ts.bool(r),
            )
        }
    }

    private fun movements(t: ParquetTable): List<DailyMovements> {
        val icao = t["icao"]; val day = t["day_utc"]; val arr = t["arrivals"]; val dep = t["departures"]
        return (0 until t.numRows).mapNotNull { r ->
            DailyMovements(
                icao = icao.string(r) ?: return@mapNotNull null,
                dayEpochDay = day.long(r) ?: return@mapNotNull null,
                arrivals = arr.long(r) ?: 0, departures = dep.long(r) ?: 0,
            )
        }
    }

    internal fun flights(t: ParquetTable, arrival: Boolean): List<ObservedFlight> {
        val here = t[if (arrival) "arrival_icao" else "departure_icao"]
        val otherIcao = t[if (arrival) "departure_icao" else "arrival_icao"]
        val otherIata = t[if (arrival) "departure_iata" else "arrival_iata"]
        val at = t[if (arrival) "arrived_at" else "departed_at"]
        val terminal = t[if (arrival) "terminal_minutes" else "departure_terminal_minutes"]
        val callsign = t["callsign"]; val flightIata = t["flight_number_iata"]
        val icao24 = t["icao24"]; val airline = t["airline_name"]
        return (0 until t.numRows).mapNotNull { r ->
            ObservedFlight(
                airportIcao = here.string(r) ?: return@mapNotNull null,
                callsign = callsign.string(r)?.trim()?.ifEmpty { null },
                flightNumberIata = flightIata.string(r),
                icao24 = icao24.string(r),
                airlineName = airline.string(r),
                otherIcao = otherIcao.string(r),
                otherIata = otherIata.string(r),
                at = at.epochMillis(r) ?: return@mapNotNull null,
                terminalMinutes = terminal.double(r),
            )
        }
    }

    private fun impact(t: ParquetTable): List<ArrivalImpact> {
        val icao = t["arrival_icao"]; val at = t["arrived_at"]; val term = t["terminal_minutes"]
        return (0 until t.numRows).mapNotNull { r ->
            ArrivalImpact(
                arrivalIcao = icao.string(r) ?: return@mapNotNull null,
                arrivedAt = at.epochMillis(r) ?: return@mapNotNull null,
                terminalMinutes = term.double(r),
            )
        }
    }

    private fun tracks(t: ParquetTable): List<TrackLine> {
        val airport = t["airport_icao"]; val role = t["role"]; val icao24 = t["icao24"]
        val start = t["track_start_epoch"]; val callsign = t["callsign"]; val flight = t["flight_number_iata"]
        val dep = t["departure_iata"]; val arr = t["arrival_iata"]; val at = t["point_at"]
        val lat = t["lat"]; val lon = t["lon"]
        // One line per (airport, role, aircraft, track), points in time order.
        val groups = LinkedHashMap<String, MutableList<Int>>()
        for (r in 0 until t.numRows) {
            if (lat.isNull(r) || lon.isNull(r)) continue
            val key = "${airport.string(r)}|${role.string(r)}|${icao24.string(r)}|${start.long(r)}"
            groups.getOrPut(key) { mutableListOf() } += r
        }
        return groups.values.mapNotNull { rows ->
            if (rows.size < 2) return@mapNotNull null
            rows.sortBy { at.long(it) ?: 0L }
            val r = rows.first()
            val isArrival = role.string(r) == "arrival"
            val who = flight.string(r) ?: callsign.string(r)?.trim() ?: icao24.string(r) ?: "?"
            TrackLine(
                airportIcao = airport.string(r) ?: return@mapNotNull null,
                arrival = isArrival,
                label = "$who ${dep.string(r) ?: "?"}→${arr.string(r) ?: "?"}",
                lats = DoubleArray(rows.size) { lat.double(rows[it])!! },
                lons = DoubleArray(rows.size) { lon.double(rows[it])!! },
            )
        }
    }

    private fun notams(t: ParquetTable): List<Notam> {
        val key = t["notam_key"]; val number = t["number"]; val location = t["location"]
        val category = t["category"]; val condition = t["condition"]; val starts = t["starts_at"]
        val ends = t["ends_at"]; val perm = t["is_permanent"]; val est = t["is_estimated"]
        val schedule = t["schedule"]; val hasSchedule = t["has_schedule"]; val raw = t["raw_text"]
        val rwyClosed = t["is_runway_closure"]
        val current = t.columnOrNull("is_current")
        return (0 until t.numRows).mapNotNull { r ->
            if (current != null && current.bool(r) == false) return@mapNotNull null
            Notam(
                key = key.string(r) ?: "row$r",
                number = number.string(r), location = location.string(r),
                category = category.string(r), condition = condition.string(r),
                startsAt = starts.epochMillis(r), endsAt = ends.epochMillis(r),
                isPermanent = perm.bool(r) == true, isEstimated = est.bool(r) == true,
                schedule = schedule.string(r), hasSchedule = hasSchedule.bool(r) == true,
                rawText = raw.string(r), isRunwayClosure = rwyClosed.bool(r) == true,
            )
        }
    }

    companion object {
        const val AIRPORTS = "reference.airports"
        const val AIRLINES = "reference.airlines"
        const val CARGO_OPERATORS = "reference.cargo_operators"
        const val CONDITIONS = "marts.fct_airport_conditions"
        const val WEATHER_HOURLY = "marts.fct_airport_weather_hourly"
        const val MOVEMENTS = "marts.fct_daily_airport_movements"
        const val ARRIVALS = "marts.fct_arrivals"
        const val DEPARTURES = "marts.fct_departures"
        const val IMPACT = "marts.fct_arrival_weather_impact"
        const val TRACKS = "marts.fct_terminal_tracks"
        const val NOTAMS = "marts.fct_notams"

        /** The allow-list in api/index.py. */
        val TABLES = listOf(
            AIRPORTS, AIRLINES, CARGO_OPERATORS, CONDITIONS, WEATHER_HOURLY, MOVEMENTS,
            ARRIVALS, DEPARTURES, IMPACT, TRACKS, NOTAMS,
        )
        private val ESSENTIAL = listOf(AIRPORTS, CONDITIONS)
    }
}
