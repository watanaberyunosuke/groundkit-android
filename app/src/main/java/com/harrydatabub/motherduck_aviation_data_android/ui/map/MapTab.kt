package com.harrydatabub.motherduck_aviation_data_android.ui.map

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.harrydatabub.motherduck_aviation_data_android.ui.FlightItem
import com.harrydatabub.motherduck_aviation_data_android.ui.MapViewModel
import com.harrydatabub.motherduck_aviation_data_android.ui.UiState

enum class MapMode(val label: String) { AIRPORT("Airport"), AIRSPACE("Airspace") }

/**
 * The Map tab: the airport's layout for finding your way on the ground, or the airspace
 * with live traffic. Both show where you are once location is allowed.
 */
@Composable
fun MapTab(
    state: UiState,
    vm: MapViewModel,
    mode: MapMode,
    onMode: (MapMode) -> Unit,
    selectedAircraft: String?,
    onSelectAircraft: (String?) -> Unit,
    onDetails: (FlightItem) -> Unit,
) {
    val snap = state.snapshot ?: return
    val layout by vm.layout.collectAsStateWithLifecycle()
    val fix by vm.fix.collectAsStateWithLifecycle()
    var allowed by remember { mutableStateOf(vm.location.hasPermission()) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        allowed = vm.location.hasPermission()
        if (allowed) vm.startLocation()
    }
    // Location runs only while a map is on screen.
    LifecycleResumeEffect(vm, allowed) {
        allowed = vm.location.hasPermission()
        if (allowed) vm.startLocation()
        onPauseOrDispose { vm.stopLocation() }
    }
    LaunchedEffect(snap.airport.icao, mode) {
        if (mode == MapMode.AIRPORT) vm.show(snap.airport)
    }
    val request = { ask.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }

    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            SingleChoiceSegmentedButtonRow(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                MapMode.entries.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = mode == m,
                        onClick = { onMode(m) },
                        shape = SegmentedButtonDefaults.itemShape(i, MapMode.entries.size),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(m.label) }
                }
            }
        }
        // Clipped: the maps draw past their edges, over the toggle above.
        Box(Modifier.weight(1f).clipToBounds()) {
            when (mode) {
                MapMode.AIRPORT -> AirportMapScreen(
                    snap.airport, layout, fix, allowed,
                    onRequestLocation = request,
                    onRetry = { vm.retry(snap.airport) },
                )
                MapMode.AIRSPACE -> MapScreen(state, selectedAircraft, onSelectAircraft, onDetails, me = fix)
            }
        }
    }
}
