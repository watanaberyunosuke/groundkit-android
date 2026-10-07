package com.harrydatahub.groundkit.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.harrydatahub.groundkit.data.Airport
import com.harrydatahub.groundkit.data.AirportLayouts
import com.harrydatahub.groundkit.data.LocationTracker
import com.harrydatahub.groundkit.data.MyFix
import com.harrydatahub.groundkit.domain.AirportLayout
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LayoutState(
    val icao: String? = null,
    val layout: AirportLayout? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

/** The airport map: the selected airport's layout from OpenStreetMap, and where you are. */
class MapViewModel(app: Application) : AndroidViewModel(app) {
    private val layouts = AirportLayouts(app.noBackupFilesDir)
    val location = LocationTracker(app)
    val fix: StateFlow<MyFix?> = location.fix

    private val _layout = MutableStateFlow(LayoutState())
    val layout: StateFlow<LayoutState> = _layout.asStateFlow()
    private var job: Job? = null

    /** Shows the kept layout for the airport at once; downloads it if missing or a month old. */
    fun show(airport: Airport) {
        if (_layout.value.icao == airport.icao && (job?.isActive == true || _layout.value.layout != null)) return
        load(airport, force = false)
    }

    fun retry(airport: Airport) = load(airport, force = true)

    private fun load(airport: Airport, force: Boolean) {
        job?.cancel()
        _layout.update { if (it.icao == airport.icao) it.copy(loading = true, error = null) else LayoutState(airport.icao, loading = true) }
        job = viewModelScope.launch {
            val kept = layouts.cached(airport.icao)
            if (kept != null) _layout.update { it.copy(layout = kept) }
            if (kept != null && !force && !layouts.isStale(kept)) {
                _layout.update { it.copy(loading = false) }
                return@launch
            }
            try {
                val fresh = layouts.download(airport.icao, airport.lat, airport.lon)
                _layout.update { it.copy(layout = fresh, loading = false, error = null) }
            } catch (e: Exception) {
                _layout.update { it.copy(loading = false, error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    fun startLocation() = location.start()

    fun stopLocation() = location.stop()

    override fun onCleared() = location.stop()
}
