package com.harrydatabub.motherduck_aviation_data_android.domain

import com.harrydatabub.motherduck_aviation_data_android.data.Conditions
import kotlin.math.roundToInt

enum class AlertLevel { INFO, CAUTION, WARNING }

enum class AlertKind { THUNDERSTORM, WIND, VISIBILITY, FREEZING, HEAT, PRECIPITATION, DUST, STALE }

data class RampAlert(val level: AlertLevel, val kind: AlertKind, val title: String, val detail: String)

/**
 * Advisories for ramp work from the latest METAR: thunderstorms, wind, low visibility,
 * freezing conditions, heat and stale data. They prompt staff to check their own
 * procedures; local SOPs and ops control always take precedence.
 */
object RampAlerts {
    const val STALE_AFTER_MIN = 90

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

        if (temp != null && temp >= 35) {
            alerts += RampAlert(
                AlertLevel.CAUTION, AlertKind.HEAT, "Heat ${temp.roundToInt()}°C",
                "Heat-stress risk on the apron: hydrate and rotate crews.",
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
            // The pipeline loads METARs twice a day, so an old report is normal, not an
            // alarm; it is still worth saying so staff check ATIS before relying on it.
            if (age > STALE_AFTER_MIN) {
                alerts += RampAlert(
                    AlertLevel.INFO, AlertKind.STALE, "Latest METAR is ${ageText(age)} old",
                    "Weather is loaded twice a day. Check ATIS or ops for current conditions.",
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
