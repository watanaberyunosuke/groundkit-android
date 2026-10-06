package com.harrydatabub.motherduck_aviation_data_android.domain

import com.harrydatabub.motherduck_aviation_data_android.data.Conditions
import kotlin.math.roundToInt

enum class AlertLevel { INFO, CAUTION, WARNING }

enum class AlertKind { THUNDERSTORM, WIND, VISIBILITY, FREEZING, HEAT, COLD, PRECIPITATION, DUST, STALE }

data class RampAlert(val level: AlertLevel, val kind: AlertKind, val title: String, val detail: String)

/**
 * Advisories for ramp work from the latest METAR: thunderstorms, wind, low visibility,
 * freezing conditions, heat and stale data. They prompt staff to check their own
 * procedures; local SOPs and ops control always take precedence.
 */
object RampAlerts {
    const val STALE_AFTER_MIN = 90
    const val HEAT_CAUTION_C = 32.0
    const val HEAT_WARNING_C = 41.0
    const val WIND_CHILL_CAUTION_C = -10.0
    /** Exposed skin can freeze in 30 minutes or less below about -27 °C wind chill. */
    const val WIND_CHILL_WARNING_C = -27.0

    fun from(c: Conditions?, gustCautionKt: Int, highWindKt: Int, now: Long): List<RampAlert> {
        if (c?.metarRaw == null) return emptyList()
        val wx = c.wxString.orEmpty().uppercase()
        val codes = wx.split(' ').filter { it.isNotBlank() }
        fun has(code: String) = codes.any { code in it }
        val alerts = mutableListOf<RampAlert>()

        if (has("TS")) {
            val vicinity = codes.all { !it.contains("TS") || it.startsWith("VC") }
            alerts += RampAlert(
                AlertLevel.WARNING, AlertKind.THUNDERSTORM,
                if (vicinity) "Thunderstorm in the vicinity" else "Thunderstorm at the airport",
                "METAR reports $wx. Follow your lightning and ramp-closure procedure.",
            )
        }
        if (has("GR") || has("GS")) {
            alerts += RampAlert(AlertLevel.WARNING, AlertKind.THUNDERSTORM, "Hail reported", "METAR reports $wx. Shelter staff and protect equipment.")
        }
        if (has("SQ") || has("FC")) {
            alerts += RampAlert(AlertLevel.WARNING, AlertKind.WIND, "Squall reported", "METAR reports $wx. Expect sudden strong winds.")
        }

        val speed = c.windSpeedKt ?: 0
        val gust = c.windGustKt
        val peak = maxOf(speed, gust ?: 0)
        val windDesc = windText(c)
        when {
            peak >= highWindKt -> alerts += RampAlert(
                AlertLevel.WARNING, AlertKind.WIND, "High wind $peak kt",
                "$windDesc. Check limits for cargo doors, stairs, loaders and ULD handling.",
            )
            gust != null && gust >= gustCautionKt -> alerts += RampAlert(
                AlertLevel.CAUTION, AlertKind.WIND, "Gusts $gust kt",
                "$windDesc. Secure loose equipment, ULDs and FOD; take care with doors and stairs.",
            )
        }

        val temp = c.tempC
        val precip = listOf("RA", "DZ", "SN", "SG", "PL", "UP").any { has(it) }
        when {
            has("FZ") -> alerts += RampAlert(
                AlertLevel.WARNING, AlertKind.FREEZING, "Freezing ${if (has("FG")) "fog" else "precipitation"}",
                "METAR reports $wx. Icing likely: slippery surfaces, de-icing may be needed.",
            )
            has("SN") || has("SG") || has("PL") -> alerts += RampAlert(
                AlertLevel.CAUTION, AlertKind.FREEZING, "Snow or ice pellets",
                "METAR reports $wx. Slippery surfaces; check de-icing.",
            )
            temp != null && temp <= 0 -> alerts += RampAlert(
                AlertLevel.CAUTION, AlertKind.FREEZING, "Below freezing (${temp.roundToInt()}°C)",
                "Watch for ice on the apron and on aircraft surfaces.",
            )
            temp != null && temp <= 3 && precip -> alerts += RampAlert(
                AlertLevel.CAUTION, AlertKind.FREEZING, "Near freezing (${temp.roundToInt()}°C) with precipitation",
                "Ice may form on surfaces.",
            )
        }

        when (c.flightCategory) {
            "LIFR" -> alerts += RampAlert(
                AlertLevel.WARNING, AlertKind.VISIBILITY, "Low visibility (LIFR)",
                "${visText(c)} visibility, ceiling ${ceilingText(c)}. Low-visibility procedures may be in force: extra care airside.",
            )
            "IFR" -> alerts += RampAlert(
                AlertLevel.CAUTION, AlertKind.VISIBILITY, "Reduced visibility (IFR)",
                "${visText(c)} visibility, ceiling ${ceilingText(c)}.",
            )
        }

        // Heat and cold stress from what it feels like, with the iOS app's thresholds.
        val heat = if (temp != null && c.dewpointC != null) HeatStress.heatIndexC(temp, c.dewpointC) else null
        val chill = if (temp != null && c.windSpeedKt != null) HeatStress.windChillC(temp, c.windSpeedKt.toDouble()) else null
        when {
            heat != null && heat >= HEAT_WARNING_C -> alerts += RampAlert(
                AlertLevel.WARNING, AlertKind.HEAT, "Extreme heat, feels like ${heat.roundToInt()}°C",
                "Rotate crews, take shade breaks and drink water every 15 to 20 minutes. Watch each other for heat illness.",
            )
            heat != null && heat >= HEAT_CAUTION_C -> alerts += RampAlert(
                AlertLevel.CAUTION, AlertKind.HEAT, "Heat stress, feels like ${heat.roundToInt()}°C",
                "Drink about 250 ml every 20 minutes, even if not thirsty. Take breaks in the shade.",
            )
        }
        when {
            chill != null && chill <= WIND_CHILL_WARNING_C -> alerts += RampAlert(
                AlertLevel.WARNING, AlertKind.COLD, "Frostbite risk, wind chill ${chill.roundToInt()}°C",
                "Exposed skin can freeze in 30 minutes or less. Cover up fully and limit time outside.",
            )
            chill != null && chill <= WIND_CHILL_CAUTION_C -> alerts += RampAlert(
                AlertLevel.CAUTION, AlertKind.COLD, "Very cold, wind chill ${chill.roundToInt()}°C",
                "Wear insulated gloves and cover exposed skin. Warm up between tasks.",
            )
        }
        if (has("DS") || has("SS") || has("SA") || has("DU")) {
            alerts += RampAlert(AlertLevel.CAUTION, AlertKind.DUST, "Dust or sand", "METAR reports $wx. Eye protection; cover open holds and ULDs.")
        }
        if (precip && alerts.none { it.kind == AlertKind.FREEZING }) {
            alerts += RampAlert(
                AlertLevel.INFO, AlertKind.PRECIPITATION, if (has("+")) "Heavy rain" else "Rain",
                "METAR reports $wx. Wet surfaces; protect baggage and cargo.",
            )
        }

        c.metarObservedAt?.let { observed ->
            val age = ((now - observed) / MINUTE_MS).toInt()
            // The pipeline loads METARs every hour, but its runs can be hours late, so an
            // old report is normal, not an alarm; it is still worth saying so staff check
            // ATIS before relying on it.
            if (age > STALE_AFTER_MIN) {
                alerts += RampAlert(
                    AlertLevel.INFO, AlertKind.STALE, "Latest METAR is ${ageText(age)} old",
                    "Weather is loaded hourly and can run late. Check ATIS or ops for current conditions.",
                )
            }
        }
        return alerts.sortedByDescending { it.level }
    }

    private fun ageText(min: Int) = if (min < 120) "$min min" else "${min / 60} h"
}

// ---- Shared METAR text, as the web Dive formats it --------------------------------------

fun windText(c: Conditions): String {
    val speed = c.windSpeedKt ?: return "Wind not reported"
    if (speed == 0) return "Calm"
    val dir = if (c.windVariable) "VRB" else c.windDirDeg?.let { "%03d°".format(java.util.Locale.ROOT, it) } ?: "VRB"
    return "$dir $speed kt" + (c.windGustKt?.let { " G$it" } ?: "")
}

/** AWC reports statute miles; "6+" (a lower bound) is 10 km or more in ICAO terms. */
fun visText(c: Conditions): String = when {
    c.visibilitySm == null -> "–"
    c.visibilityIsLowerBound -> "10 km+"
    else -> {
        val km = c.visibilitySm * 1.609
        if (km < 5) "${(km * 1000 / 50).roundToInt() * 50} m" else "%.1f km".format(java.util.Locale.ROOT, km)
    }
}

fun ceilingText(c: Conditions): String = c.ceilingFt?.let { "%,d ft".format(java.util.Locale.ROOT, it) } ?: "none"
