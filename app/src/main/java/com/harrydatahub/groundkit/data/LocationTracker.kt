package com.harrydatahub.groundkit.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where the phone is. Bearing and speed only when moving. */
data class MyFix(
    val lat: Double,
    val lon: Double,
    val accuracyM: Float?,
    val bearingDeg: Float?,
    val speedMs: Float?,
    val at: Long,
)

/**
 * The phone's position for the maps, from the platform's location providers (no Play
 * services). Runs only between [start] and [stop], which the maps call while on screen.
 */
class LocationTracker(private val context: Context) {
    private val manager = context.getSystemService(LocationManager::class.java)
    private val _fix = MutableStateFlow<MyFix?>(null)
    val fix: StateFlow<MyFix?> = _fix.asStateFlow()
    private var listening = false

    private val listener = LocationListener { onLocation(it) }

    fun hasPermission(): Boolean = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    @SuppressLint("MissingPermission") // checked by hasPermission()
    fun start() {
        if (listening || manager == null || !hasPermission()) return
        listening = true
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
        }.filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        providers.forEach { p ->
            runCatching { manager.getLastKnownLocation(p) }.getOrNull()
                ?.takeIf { System.currentTimeMillis() - it.time < STALE_MS }
                ?.let(::onLocation)
            runCatching { manager.requestLocationUpdates(p, INTERVAL_MS, 0f, listener, Looper.getMainLooper()) }
        }
    }

    fun stop() {
        if (!listening) return
        listening = false
        runCatching { manager?.removeUpdates(listener) }
    }

    /** Keeps the better of the new fix and the last: newer, unless much less accurate. */
    private fun onLocation(l: Location) {
        val prev = _fix.value
        val acc = if (l.hasAccuracy()) l.accuracy else null
        if (prev != null && l.time - prev.at < BETTER_WINDOW_MS && acc != null && prev.accuracyM != null && acc > prev.accuracyM * 2) return
        _fix.value = MyFix(
            l.latitude, l.longitude, acc,
            bearingDeg = if (l.hasBearing() && l.hasSpeed() && l.speed > 1f) l.bearing else null,
            speedMs = if (l.hasSpeed()) l.speed else null,
            at = l.time,
        )
    }

    private companion object {
        const val INTERVAL_MS = 2_000L
        const val STALE_MS = 5 * 60_000L
        const val BETTER_WINDOW_MS = 15_000L
    }
}
