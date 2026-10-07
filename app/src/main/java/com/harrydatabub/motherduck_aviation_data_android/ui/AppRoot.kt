package com.harrydatabub.motherduck_aviation_data_android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.FlightLand
import androidx.compose.material.icons.filled.FlightTakeoff
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.harrydatabub.motherduck_aviation_data_android.data.FlightFilter
import com.harrydatabub.motherduck_aviation_data_android.domain.Dir
import com.harrydatabub.motherduck_aviation_data_android.domain.Solar
import com.harrydatabub.motherduck_aviation_data_android.ui.components.FreshnessBanner
import com.harrydatabub.motherduck_aviation_data_android.ui.components.rememberNow
import com.harrydatabub.motherduck_aviation_data_android.ui.map.MapMode
import com.harrydatabub.motherduck_aviation_data_android.ui.map.MapTab
import com.harrydatabub.motherduck_aviation_data_android.ui.screens.AirportPickerSheet
import com.harrydatabub.motherduck_aviation_data_android.ui.screens.BriefingScreen
import com.harrydatabub.motherduck_aviation_data_android.ui.screens.BriefingTab
import com.harrydatabub.motherduck_aviation_data_android.ui.screens.FlightDetailSheet
import com.harrydatabub.motherduck_aviation_data_android.ui.screens.FlightsScreen
import com.harrydatabub.motherduck_aviation_data_android.ui.screens.NowScreen
import com.harrydatabub.motherduck_aviation_data_android.ui.screens.SettingsSheet
import com.harrydatabub.motherduck_aviation_data_android.ui.screens.ShiftScreen
import com.harrydatabub.motherduck_aviation_data_android.ui.theme.RampTheme

/** Bottom-bar destinations. The map also opens from Now and from a flight, on the airspace. */
enum class Screen(val label: String, val icon: ImageVector) {
    NOW("Now", Icons.Filled.Dashboard),
    ARRIVALS("Arrivals", Icons.Filled.FlightLand),
    DEPARTURES("Departures", Icons.Filled.FlightTakeoff),
    MAP("Map", Icons.Filled.Map),
    BRIEFING("Briefing", Icons.Filled.Cloud),
    SHIFT("Shift", Icons.Filled.HealthAndSafety),
}

@Composable
fun AppRoot(vm: AppViewModel, shiftVm: ShiftViewModel, mapVm: MapViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val minute by rememberNow(60_000)
    val airport = state.snapshot?.airport
    val sunDark = airport?.let { Solar.isDark(it.lat, it.lon, minute) }
    RampTheme(state.settings.theme, sunDark) {
        // Poll the API only while the app is visible.
        LifecycleStartEffect(vm) {
            vm.onForeground()
            onStopOrDispose { vm.onBackground() }
        }
        val view = LocalView.current
        DisposableEffect(state.settings.keepScreenOn) {
            view.keepScreenOn = state.settings.keepScreenOn
            onDispose { view.keepScreenOn = false }
        }
        Content(state, vm, shiftVm, mapVm)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Content(state: UiState, vm: AppViewModel, shiftVm: ShiftViewModel, mapVm: MapViewModel) {
    val records by shiftVm.records.collectAsStateWithLifecycle()
    val health by shiftVm.healthState.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf(Screen.NOW) }
    var briefingTab by rememberSaveable { mutableStateOf(BriefingTab.WEATHER) }
    var mapSelection by rememberSaveable { mutableStateOf<String?>(null) }
    var mapMode by rememberSaveable { mutableStateOf(MapMode.AIRPORT) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var detail by remember { mutableStateOf<FlightItem?>(null) }
    val now by rememberNow()

    // Back goes to Now before leaving the app.
    BackHandler(enabled = screen != Screen.NOW) { screen = Screen.NOW }

    val snap = state.snapshot
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (snap != null) {
                        TextButton(onClick = { picking = true }, modifier = Modifier.height(52.dp)) {
                            Text(snap.airport.iata, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
                            Icon(Icons.Filled.ArrowDropDown, contentDescription = "Change airport", tint = MaterialTheme.colorScheme.onSurface)
                            Spacer(Modifier.width(4.dp))
                            Text(
                                snap.airport.name, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    } else {
                        Text("Ramp Ops")
                    }
                },
                actions = {
                    if (state.refreshing || state.live.loading) {
                        CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.5.dp)
                    } else {
                        IconButton(onClick = vm::refreshAll, modifier = Modifier.size(52.dp)) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                        }
                    }
                    IconButton(onClick = { showSettings = true }, modifier = Modifier.size(52.dp)) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            )
        },
        bottomBar = {
            NavigationBar {
                Screen.entries.forEach { s ->
                    NavigationBarItem(
                        selected = screen == s,
                        onClick = { screen = s },
                        icon = { Icon(s.icon, contentDescription = null) },
                        // Six tabs: the smaller label keeps "Departures" whole on a phone.
                        label = { Text(s.label, maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            FreshnessBanner(state.fromCache, state.dataFetchedAt, state.refreshError, now, onRetry = vm::refreshAll)
            Box(Modifier.weight(1f)) {
                when {
                    snap == null && state.loadError != null -> LoadFailed(state.loadError, vm::refreshAll)
                    snap == null -> Loading()
                    else -> when (screen) {
                        Screen.NOW -> NowScreen(
                            state, now,
                            onFlight = { detail = it },
                            onSeeArrivals = { screen = Screen.ARRIVALS },
                            onSeeDepartures = { screen = Screen.DEPARTURES },
                            onSeeWeather = { briefingTab = BriefingTab.WEATHER; screen = Screen.BRIEFING },
                            onSeeNotams = { briefingTab = BriefingTab.NOTAMS; screen = Screen.BRIEFING },
                            onOpenMap = { mapMode = MapMode.AIRSPACE; screen = Screen.MAP },
                        )
                        Screen.ARRIVALS, Screen.DEPARTURES -> FlightsScreen(
                            if (screen == Screen.ARRIVALS) Dir.INBOUND else Dir.OUTBOUND,
                            state, now,
                            onFilter = vm::setFilter,
                            onConfigureMine = { showSettings = true },
                            onFlight = { detail = it },
                            onRefresh = vm::refreshAll,
                        )
                        Screen.MAP -> MapTab(
                            state, mapVm, mapMode, onMode = { mapMode = it },
                            selectedAircraft = mapSelection, onSelectAircraft = { mapSelection = it }, onDetails = { detail = it },
                        )
                        Screen.BRIEFING -> BriefingScreen(
                            state, now, briefingTab, onTab = { briefingTab = it },
                            onSelectAirport = vm::selectAirport,
                        )
                        Screen.SHIFT -> ShiftScreen(shiftVm, records, health, snap, now, state.settings.age)
                    }
                }
            }
        }
    }

    if (picking) {
        AirportPickerSheet(
            state.airports, snap?.airport?.icao,
            onPick = { vm.selectAirport(it); mapSelection = null; picking = false },
            onDismiss = { picking = false },
        )
    }
    if (showSettings) {
        SettingsSheet(state.settings, state.myAirlines, snap?.airport, onChange = vm::updateSettings, onDismiss = {
            showSettings = false
            // Configuring "my airlines" from the filter chip: switch to it once set.
            if (state.settings.filter != FlightFilter.MINE && state.myAirlines.isNotEmpty() && screen in setOf(Screen.ARRIVALS, Screen.DEPARTURES)) {
                vm.setFilter(FlightFilter.MINE)
            }
        })
    }
    val d = detail
    if (d != null && snap != null) {
        FlightDetailSheet(
            d, snap, now,
            onShowOnMap = { icao24 ->
                mapSelection = icao24
                mapMode = MapMode.AIRSPACE
                screen = Screen.MAP
                detail = null
            },
            onDismiss = { detail = null },
        )
    }
}

@Composable
private fun Loading() {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text("Loading weather, NOTAMs and flights…", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun LoadFailed(error: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Could not load data", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(error, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Check the device has a data connection. Data loaded once is kept for offline use.",
            style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Row {
            Button(onClick = onRetry, modifier = Modifier.height(52.dp)) { Text("Try again") }
        }
    }
}
