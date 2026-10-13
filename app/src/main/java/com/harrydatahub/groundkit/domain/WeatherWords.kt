package com.harrydatahub.groundkit.domain

import com.harrydatahub.groundkit.data.Airport
import com.harrydatahub.groundkit.data.Conditions
import com.harrydatahub.groundkit.data.LiveWeather
import java.util.Locale
import kotlin.math.roundToInt

/** Plain words for METAR codes and readings, for people who don't read METARs every day. The same wording as iOS. */
object WeatherWords {
    private val descriptors = mapOf(
        "MI" to "shallow", "PR" to "partial", "BC" to "patches of", "DR" to "low drifting",
        "BL" to "blowing", "FZ" to "freezing",
    )
    private val phenomena = mapOf(
        "DZ" to "drizzle", "RA" to "rain", "SN" to "snow", "SG" to "snow grains", "IC" to "ice crystals",
        "PL" to "ice pellets", "GR" to "hail", "GS" to "small hail", "UP" to "precipitation",
        "BR" to "mist", "FG" to "fog", "FU" to "smoke", "VA" to "volcanic ash", "DU" to "dust",
        "SA" to "sand", "HZ" to "haze", "PY" to "spray", "PO" to "dust whirls", "SQ" to "squalls",
        "FC" to "funnel cloud", "SS" to "sandstorm", "DS" to "duststorm",
    )

    /** "-SHRA BR" -> "Light rain showers, mist". Null for no weather. */
    fun describe(wx: String?): String? {
        val groups = wx?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }.orEmpty()
        if (groups.isEmpty()) return null
        val parts = groups.map(::describeGroup)
        return (listOf(parts.first().replaceFirstChar { it.uppercase() }) + parts.drop(1)).joinToString(", ")
    }

    fun describeGroup(group: String): String {
        var g = group
        var intensity: String? = null
        var nearby = false
        if (g.startsWith("-")) { intensity = "light"; g = g.drop(1) }
        else if (g.startsWith("+")) { intensity = "heavy"; g = g.drop(1) }
        if (g.startsWith("VC")) { nearby = true; g = g.drop(2) }
        var showers = false
        var thunder = false
        val words = mutableListOf<String>()
        val things = mutableListOf<String>()
        while (g.length >= 2) {
            val code = g.take(2)
            g = g.drop(2)
            when {
                code == "SH" -> showers = true
                code == "TS" -> thunder = true
                code in descriptors -> words += descriptors.getValue(code)
                code in phenomena -> things += phenomena.getValue(code)
                else -> return group // not a weather group we know: show it as is
            }
        }
        var text = if (thunder) {
            "thunderstorm" + if (things.isEmpty()) "" else " with " + things.joinToString(" and ")
        } else {
            (words + things.joinToString(" and ")).filter { it.isNotEmpty() }.joinToString(" ")
                .let { if (showers) (if (it.isEmpty()) "showers" else "$it showers") else it }
        }
        if (intensity != null) text = "$intensity $text"
        if (nearby) text += " nearby"
        return text
    }

    /** "270° 15 kt, gusts 28", "Variable 3 kt" or "Calm". */
    fun wind(dir: Int?, variable: Boolean, speed: Int?, gust: Int?): String {
        speed ?: return "–"
        if (speed == 0 && gust == null) return "Calm"
        val from = if (variable || dir == null) "Variable" else "%03d°".format(Locale.ROOT, dir)
        return "$from $speed kt" + (gust?.let { ", gusts $it" } ?: "")
    }

    /** "the west", "the north-east": where the wind comes from, in words. */
    fun compassName(deg: Int): String {
        val points = listOf("north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west")
        return "the " + points[(deg / 45.0).roundToInt() % 8]
    }

    /** Statute miles to metres, rounded as a METAR would report them. */
    fun visibility(sm: Double?, isLowerBound: Boolean): String {
        sm ?: return "–"
        val metres = sm * 1609.34
        return when {
            isLowerBound || metres >= 9999 -> "10 km or more"
            metres >= 5000 -> "${(metres / 1000).roundToInt()} km"
            else -> "${(metres / 100).roundToInt() * 100} m"
        }
    }

    /** "31°C". */
    fun temp(c: Double?): String = c?.let { "${it.roundToInt()}°C" } ?: "–"

    /** "1015 hPa". */
    fun pressure(hpa: Double?): String = hpa?.let { "${it.roundToInt()} hPa" } ?: "–"

    /** Plain words for a flight category, for people who don't fly. */
    fun category(code: String?): String? = when (code) {
        "VFR" -> "Good for flying (VFR)"
        "MVFR" -> "Marginal for flying (MVFR)"
        "IFR" -> "Poor, instruments only (IFR)"
        "LIFR" -> "Very poor (LIFR)"
        else -> code
    }

    /**
     * The hourly mart's conditions with the live METAR and TAF laid over them where the live
     * ones are at least as new. Without a mart row, the live weather alone.
     */
    fun merged(base: Conditions?, live: LiveWeather?, airport: Airport): Conditions? {
        var c = base
        val m = live?.metar
        if (m != null && m.observedAt >= (base?.metarObservedAt ?: Long.MIN_VALUE)) {
            val start = c ?: Conditions(
                icao = airport.icao, iata = airport.iata, lat = airport.lat, lon = airport.lon,
                metarObservedAt = null, metarRaw = null, flightCategory = null, windVariable = false,
                windDirDeg = null, windSpeedKt = null, windGustKt = null, visibilitySm = null,
                visibilityIsLowerBound = false, ceilingFt = null, wxString = null, tempC = null,
                dewpointC = null, altimeterHpa = null, tafIssuedAt = null, tafValidFrom = null,
                tafValidTo = null, tafRaw = null, notamsInForce = null,
            )
            c = start.copy(
                metarObservedAt = m.observedAt, metarRaw = m.raw, flightCategory = m.flightCategory,
                windVariable = m.windVariable, windDirDeg = m.windDirDeg, windSpeedKt = m.windSpeedKt,
                windGustKt = m.windGustKt, visibilitySm = m.visibilitySm,
                visibilityIsLowerBound = m.visibilityIsLowerBound, ceilingFt = m.ceilingFt,
                wxString = m.wxString, tempC = m.tempC, dewpointC = m.dewpointC, altimeterHpa = m.altimeterHpa,
            )
        }
        val t = live?.taf
        if (c != null && t != null && t.issuedAt >= (c.tafIssuedAt ?: Long.MIN_VALUE)) {
            c = c.copy(tafIssuedAt = t.issuedAt, tafValidFrom = t.validFrom, tafValidTo = t.validTo, tafRaw = t.raw)
        }
        return c
    }
}
