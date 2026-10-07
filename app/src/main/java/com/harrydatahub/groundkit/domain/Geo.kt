package com.harrydatahub.groundkit.domain

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** 50 NM, the terminal area used throughout the warehouse (dbt_project.yml). */
const val TERMINAL_KM = 92.6
const val KM_PER_NM = 1.852
const val MINUTE_MS = 60_000L
const val HOUR_MS = 3_600_000L
const val DAY_MS = 86_400_000L

/** Great-circle distance, km. */
fun distKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = Math.PI / 180
    val h = sin((lat2 - lat1) * r / 2).let { it * it } +
        cos(lat1 * r) * cos(lat2 * r) * sin((lon2 - lon1) * r / 2).let { it * it }
    return 2 * 6371.0088 * asin(sqrt(h))
}

/** Initial great-circle bearing from point 1 to point 2, degrees. */
fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = Math.PI / 180
    val y = sin((lon2 - lon1) * r) * cos(lat2 * r)
    val x = cos(lat1 * r) * sin(lat2 * r) - sin(lat1 * r) * cos(lat2 * r) * cos((lon2 - lon1) * r)
    return (atan2(y, x) / r + 360) % 360
}

/** Minutes after local midnight in `zone`. */
fun minuteOfDay(epochMs: Long, zone: ZoneId): Int {
    val t = Instant.ofEpochMilli(epochMs).atZone(zone)
    return t.hour * 60 + t.minute
}

/** A signed difference of two times of day wrapped to [-720, 720): 23:50 vs 00:10 is -20. */
fun wrapMinutes(d: Double): Double = (((d + 720) % 1440 + 1440) % 1440) - 720

/** Minutes after midnight as "HH:MM". */
fun hhmm(minutes: Double): String {
    val m = ((minutes.roundToInt() % 1440) + 1440) % 1440
    return String.format(Locale.ROOT, "%02d:%02d", m / 60, m % 60)
}

/** Median that interpolates between the middle two values, as DuckDB's median does. */
fun median(values: List<Double>): Double? {
    if (values.isEmpty()) return null
    val s = values.sorted()
    val mid = s.size / 2
    return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2
}
