package com.harrydatabub.motherduck_aviation_data_android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.harrydatabub.motherduck_aviation_data_android.data.CrewRecords
import com.harrydatabub.motherduck_aviation_data_android.data.HealthAvailability
import com.harrydatabub.motherduck_aviation_data_android.data.HealthService
import com.harrydatabub.motherduck_aviation_data_android.data.ShiftStats
import com.harrydatabub.motherduck_aviation_data_android.data.ShiftStore
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

    fun endShift() = store.endShift()

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
        val stats = runCatching {
            if (shift != null && granted.isNotEmpty()) health.read(Instant.ofEpochMilli(shift.startedAt)) else ShiftStats()
        }
        _health.update {
            it.copy(
                availability = availability,
                granted = granted,
                stats = stats.getOrDefault(it.stats),
                error = stats.exceptionOrNull()?.let { e -> e.message ?: e.javaClass.simpleName },
            )
        }
    }

    private companion object {
        const val POLL_MS = 120_000L
    }
}
