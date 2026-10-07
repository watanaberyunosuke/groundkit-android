package com.harrydatahub.groundkit.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.harrydatahub.groundkit.data.CrewRecords
import com.harrydatahub.groundkit.data.HealthAvailability
import com.harrydatahub.groundkit.data.HealthService
import com.harrydatahub.groundkit.data.ShiftStats
import com.harrydatahub.groundkit.data.ShiftStore
import com.harrydatahub.groundkit.data.ShiftSummary
import com.harrydatahub.groundkit.domain.DAY_MS
import com.harrydatahub.groundkit.domain.Fatigue
import com.harrydatahub.groundkit.domain.HOUR_MS
import com.harrydatahub.groundkit.domain.HeatStrain
import com.harrydatahub.groundkit.domain.MINUTE_MS
import com.harrydatahub.groundkit.domain.ShiftAdvice
import com.harrydatahub.groundkit.domain.SleepSpan
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant

data class HealthState(
    val availability: HealthAvailability = HealthAvailability.UNAVAILABLE,
    val granted: Set<String> = emptySet(),
    val stats: ShiftStats = ShiftStats(),
    /** Sleep in the 48 h before the shift (or now); null when sleep is not allowed. */
    val sleep: List<SleepSpan>? = null,
    /** Heart rate over the last few minutes, for the heat-strain check. */
    val recentHeartRate: List<HeatStrain.Sample> = emptyList(),
    val error: String? = null,
) {
    val connected get() = granted.isNotEmpty()
    val missing get() = HealthService.PERMISSIONS - granted
}

/** The Shift tab: the crew's records on the device, and Health Connect. */
class ShiftViewModel(app: Application) : AndroidViewModel(app) {
    private val store = ShiftStore(app.filesDir)
    val health = HealthService(app)

    val records: StateFlow<CrewRecords> = store.records
    private val _health = MutableStateFlow(HealthState())
    val healthState: StateFlow<HealthState> = _health.asStateFlow()

    private var pollJob: Job? = null

    init {
        viewModelScope.launch { refreshHealth() }
    }

    fun startShift(airportIcao: String) {
        store.startShift(airportIcao)
        viewModelScope.launch { refreshHealth() }
    }

    /** Ends the shift and keeps a summary of it, with Health Connect's latest totals. */
    fun endShift(feelsLikeC: Double?) {
        val shift = records.value.activeShift ?: return
        val now = System.currentTimeMillis()
        viewModelScope.launch {
            refreshHealth()
            val h = _health.value
            val stats = h.stats
            val water = maxOf(shift.waterMl, stats.waterMl ?: 0.0)
            val summary = ShiftSummary(
                steps = stats.steps, distanceKm = stats.distanceKm, activeKcal = stats.activeKcal,
                heartRateAverage = stats.heartRateAverage, heartRateMax = stats.heartRateMax,
                waterMl = water,
                waterTargetMl = ShiftAdvice.waterTargetMl(feelsLikeC, shift.durationMs(now) / HOUR_MS.toDouble()),
                breaks = shift.breaks.size,
                longestWithoutBreakMs = ShiftAdvice.longestStretchMs(shift.startedAt, shift.breaks, now),
                // Through assess, so no sleep recorded stays unknown rather than none.
                sleep24hMs = Fatigue.assess(h.sleep, emptyList(), shift.startedAt, now).sleep24hMs,
            )
            store.endShift(now, summary)
            refreshHealth()
        }
    }

    fun logBreak() = store.logBreak()

    /** Logs a drink here, and in Health Connect when allowed. */
    fun logWater(ml: Double) {
        store.addWater(ml)
        viewModelScope.launch {
            runCatching { health.logWater(ml) }
                .onSuccess { saved -> if (saved) refreshHealth() }
                .onFailure { e -> _health.update { it.copy(error = e.message) } }
        }
    }

    fun addNote(airportIcao: String, text: String, important: Boolean) = store.addNote(airportIcao, text, important)

    fun resolveNote(id: String) = store.resolveNote(id)

    fun onPermissionsResult() {
        viewModelScope.launch { refreshHealth() }
    }

    /** Polls Health Connect every 2 minutes while the Shift screen is showing. */
    fun onScreenVisible() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (isActive) {
                refreshHealth()
                delay(POLL_MS)
            }
        }
    }

    fun onScreenHidden() {
        pollJob?.cancel()
        pollJob = null
    }

    suspend fun refreshHealth() {
        val availability = health.availability
        val granted = if (availability == HealthAvailability.AVAILABLE) health.grantedPermissions() else emptySet()
        val shift = records.value.activeShift
        val now = Instant.now()
        val stats = runCatching {
            if (shift != null && granted.isNotEmpty()) health.read(Instant.ofEpochMilli(shift.startedAt)) else ShiftStats()
        }
        // Sleep is read off shift too, as a check before starting one.
        val dutyStart = shift?.let { Instant.ofEpochMilli(it.startedAt) } ?: now
        val sleep = runCatching { if (granted.isNotEmpty()) health.sleep(dutyStart.minusMillis(2 * DAY_MS)) else null }
        val heart = runCatching {
            if (shift != null && granted.isNotEmpty()) health.heartRateSamples(now.minusMillis(HEART_WINDOW_MS)) else emptyList()
        }
        val error = listOf(stats, sleep, heart).firstNotNullOfOrNull { it.exceptionOrNull() }
        _health.update {
            it.copy(
                availability = availability,
                granted = granted,
                stats = stats.getOrDefault(it.stats),
                sleep = sleep.getOrDefault(it.sleep),
                recentHeartRate = heart.getOrDefault(it.recentHeartRate),
                error = error?.let { e -> e.message ?: e.javaClass.simpleName },
            )
        }
    }

    private companion object {
        const val POLL_MS = 120_000L
        /** Heart rate kept for the heat-strain check: its 5 minutes plus a margin. */
        const val HEART_WINDOW_MS = 10 * MINUTE_MS
    }
}
