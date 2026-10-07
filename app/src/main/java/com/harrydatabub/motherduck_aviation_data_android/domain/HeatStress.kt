package com.harrydatabub.motherduck_aviation_data_android.domain

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

/** Heat index, wind chill and "feels like", as the iOS app computes them (RampAdvisor.swift). */
object HeatStress {
    /**
     * NOAA heat index (Rothfusz regression, Steadman's simple formula below 80 °F) from
     * temperature and dew point. Equals the temperature below about 27 °C.
     */
    fun heatIndexC(tempC: Double, dewpointC: Double): Double {
        val rh = relativeHumidity(tempC, dewpointC)
        val tf = tempC * 9 / 5 + 32
        var hi = 0.5 * (tf + 61 + (tf - 68) * 1.2 + rh * 0.094)
        if ((hi + tf) / 2 < 80) return tempC
        hi = -42.379 + 2.04901523 * tf + 10.14333127 * rh - 0.22475541 * tf * rh -
            0.00683783 * tf * tf - 0.05481717 * rh * rh + 0.00122874 * tf * tf * rh +
            0.00085282 * tf * rh * rh - 0.00000199 * tf * tf * rh * rh
        if (rh < 13 && tf in 80.0..112.0) {
            hi -= (13 - rh) / 4 * sqrt((17 - abs(tf - 95)) / 17)
        } else if (rh > 85 && tf in 80.0..87.0) {
            hi += (rh - 85) / 10 * ((87 - tf) / 5)
        }
        return (hi - 32) * 5 / 9
    }

    /** Environment Canada / NWS wind chill; the temperature itself above 10 °C or below 5 km/h. */
    fun windChillC(tempC: Double, windKt: Double): Double {
        val kmh = windKt * KM_PER_NM
        if (tempC > 10 || kmh <= 4.8) return tempC
        val v = kmh.pow(0.16)
        return 13.12 + 0.6215 * tempC - 11.37 * v + 0.3965 * tempC * v
    }

    fun feelsLikeC(tempC: Double?, dewpointC: Double?, windKt: Int?): Double? {
        tempC ?: return null
        if (tempC >= 27 && dewpointC != null) return heatIndexC(tempC, dewpointC)
        if (tempC <= 10 && windKt != null) return windChillC(tempC, windKt.toDouble())
        return tempC
    }

    /** Magnus formula, percent. */
    fun relativeHumidity(tempC: Double, dewpointC: Double): Double {
        val a = 17.625
        val b = 243.04
        return minOf(100.0, 100 * exp(a * dewpointC / (b + dewpointC)) / exp(a * tempC / (b + tempC)))
    }
}

/** Plain guidance for a shift from the weather. Not medical advice. */
object ShiftAdvice {
    /**
     * Water to drink per hour on the ramp: about 250 ml every 20 minutes in heat stress
     * (common occupational guidance), less otherwise. Same bands as the iOS app.
     */
    fun waterPerHourMl(feelsLikeC: Double?): Double = when {
        feelsLikeC == null -> 300.0
        feelsLikeC >= 32 -> 750.0
        feelsLikeC >= 27 -> 500.0
        else -> 300.0
    }

    /** What should have been drunk by now: at least one hour's worth, then pro rata. */
    fun waterTargetMl(feelsLikeC: Double?, hoursOnShift: Double): Double {
        val perHour = waterPerHourMl(feelsLikeC)
        return maxOf(perHour, perHour * hoursOnShift)
    }

    /** A break is due after 2 hours of work since the shift started or the last break. */
    const val BREAK_EVERY_MS = 2 * HOUR_MS

    fun breakDue(workingSince: Long, now: Long): Boolean = now - workingSince >= BREAK_EVERY_MS

    /** The longest stretch worked without a logged break. */
    fun longestStretchMs(start: Long, breaks: List<Long>, end: Long): Long {
        val marks = listOf(start) + breaks.filter { it in start..end }.sorted() + end
        return marks.zipWithNext { a, b -> b - a }.maxOrNull() ?: 0
    }

    /** Sustained exposure at or above 85 dB(A) calls for hearing protection. */
    const val HEARING_PROTECTION_DB = 85
}
