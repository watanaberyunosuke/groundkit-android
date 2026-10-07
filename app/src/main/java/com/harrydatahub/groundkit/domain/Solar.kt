package com.harrydatahub.groundkit.domain

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Where the sun is at an airport, for the Auto theme: dark from sunset to sunrise there,
 * whatever the phone's own setting. The Astronomical Almanac's low-precision formulae,
 * good to about 0.01° (a minute or so of sunrise time) for decades either side of 2000.
 */
object Solar {
    /** Sunrise and sunset are when the sun's centre is 0.833° below the horizon (refraction plus its radius). */
    const val SUNSET_DEG = -0.833

    /** The sun's altitude above the horizon in degrees, without refraction. */
    fun elevationDeg(lat: Double, lon: Double, epochMs: Long): Double {
        val r = Math.PI / 180
        val n = epochMs / DAY_MS.toDouble() + 2440587.5 - 2451545.0 // days since J2000.0
        val meanLon = norm(280.460 + 0.9856474 * n)
        val g = norm(357.528 + 0.9856003 * n) * r
        val eclLon = (meanLon + 1.915 * sin(g) + 0.020 * sin(2 * g)) * r
        val obliquity = (23.439 - 0.0000004 * n) * r
        val ra = atan2(cos(obliquity) * sin(eclLon), cos(eclLon))
        val dec = asin(sin(obliquity) * sin(eclLon))
        val gmstDeg = norm((18.697374558 + 24.06570982441908 * n) * 15)
        val hourAngle = (gmstDeg + lon) * r - ra
        return asin(sin(lat * r) * sin(dec) + cos(lat * r) * cos(dec) * cos(hourAngle)) / r
    }

    fun isDark(lat: Double, lon: Double, epochMs: Long): Boolean = elevationDeg(lat, lon, epochMs) < SUNSET_DEG

    /**
     * The next sunrise or sunset after `epochMs`, to the minute, or null if there is none
     * in the next two days (polar day or night).
     */
    fun nextChange(lat: Double, lon: Double, epochMs: Long): Long? {
        val darkNow = isDark(lat, lon, epochMs)
        var t = epochMs
        while (t < epochMs + 2 * DAY_MS) {
            val next = t + STEP_MS
            if (isDark(lat, lon, next) != darkNow) {
                var lo = t
                var hi = next
                while (hi - lo > MINUTE_MS) {
                    val mid = (lo + hi) / 2
                    if (isDark(lat, lon, mid) == darkNow) lo = mid else hi = mid
                }
                return hi
            }
            t = next
        }
        return null
    }

    private fun norm(deg: Double) = ((deg % 360) + 360) % 360

    private const val STEP_MS = 10 * MINUTE_MS
}
