package com.harrydatahub.groundkit.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
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
import com.harrydatahub.groundkit.data.FlightFilter
import com.harrydatahub.groundkit.domain.Dir
import com.harrydatahub.groundkit.domain.Solar
import com.harrydatahub.groundkit.ui.components.FreshnessBanner
import com.harrydatahub.groundkit.ui.components.rememberNow
import com.harrydatahub.groundkit.ui.map.MapMode
import com.harrydatahub.groundkit.ui.map.MapTab
import com.harrydatahub.groundkit.ui.screens.AirportPickerSheet
import com.harrydatahub.groundkit.ui.screens.BriefingScreen
import com.harrydatahub.groundkit.ui.screens.BriefingTab
import com.harrydatahub.groundkit.ui.screens.FlightDetailSheet
import com.harrydatahub.groundkit.ui.screens.FlightsScreen
import com.harrydatahub.groundkit.ui.screens.NowScreen
import com.harrydatahub.groundkit.ui.screens.SettingsSheet
import com.harrydatahub.groundkit.ui.screens.ShiftScreen
import com.harrydatahub.groundkit.ui.screens.TurnaroundsScreen
import com.harrydatahub.groundkit.ui.theme.RampTheme

/** Bottom-bar destinations, the same four as the iOS app. */
enum class Screen(val label: String, val icon: ImageVector) {
    NOW("Now", Icons.Filled.Dashboard),
    FLIGHTS("Flights", Icons.Filled.Flight),
    TURNAROUNDS("Turnarounds", Icons.Filled.Checklist),
    SHIFT("Shift", Icons.Filled.HealthAndSafety),
}

/** Full-screen pages opened from Now (and the map from a flight), over the tabs. */
enum class Page(val title: String) { MAP("Map"), BRIEFING("Briefing") }

@Composable
fun AppRoot(vm: AppViewModel, shiftVm: ShiftViewModel, mapVm: MapViewModel, turnVm: TurnaroundViewModel) {
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
        Content(state, vm, shiftVm, mapVm, turnVm)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Content(state: UiState, vm: AppViewModel, shiftVm: ShiftViewModel, mapVm: MapViewModel, turnVm: TurnaroundViewModel) {
    val records by shiftVm.records.collectAsStateWithLifecycle()
    val turnarounds by turnVm.turnarounds.collectAsStateWithLifecycle()
    var openTurnaround by rememberSaveable { mutableStateOf<String?>(null) }
    val health by shiftVm.healthState.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf(Screen.NOW) }
    var page by rememberSaveable { mutableStateOf<Page?>(null) }
    var flightsDir by rememberSaveable { mutableStateOf(Dir.INBOUND) }
    var briefingTab by rememberSaveable { mutableStateOf(BriefingTab.WEATHER) }
    var mapSelection by rememberSaveable { mutableStateOf<String?>(null) }
    var mapMode by rememberSaveable { mutableStateOf(MapMode.AIRPORT) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var detail by remember { mutableStateOf<FlightItem?>(null) }
    val now by rememberNow()

    // Back closes a page, then goes to Now before leaving the app.
    BackHandler(enabled = page != null || screen != Screen.NOW) {
        if (page != null) page = null else screen = Screen.NOW
    }
    // Airborne arrivals on the Flights tab, as on iOS.
    val inbound = state.traffic?.boardLive?.count { it.dir == Dir.INBOUND && !it.onGround } ?: 0

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
                        Text("GroundKit")
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
                    // The map from any tab, like the map button in the iOS app's toolbar.
                    IconButton(onClick = { page = Page.MAP }, modifier = Modifier.size(52.dp)) {
                        Icon(Icons.Filled.Map, contentDescription = "Map")
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
                        onClick = { screen = s; page = null },
                        icon = {
                            if (s == Screen.FLIGHTS && inbound > 0) {
                                BadgedBox(badge = { Badge { Text("$inbound") } }) { Icon(s.icon, contentDescription = null) }
                            } else {
                                Icon(s.icon, contentDescription = null)
                            }
                        },
                        label = { Text(s.label, maxLines = 1, softWrap = false) },
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
                    page != null -> Column(Modifier.fillMaxSize()) {
                        PageBar(page!!.title, onBack = { page = null })
                        when (page!!) {
                            Page.MAP -> MapTab(
                                state, mapVm, mapMode, onMode = { mapMode = it },
                                selectedAircraft = mapSelection, onSelectAircraft = { mapSelection = it }, onDetails = { detail = it },
                            )
                            Page.BRIEFING -> BriefingScreen(
                                state, now, briefingTab, onTab = { briefingTab = it },
                                onSelectAirport = vm::selectAirport,
                            )
                        }
                    }
                    else -> when (screen) {
                        Screen.NOW -> NowScreen(
                            state, now,
                            onFlight = { detail = it },
                            onSeeArrivals = { flightsDir = Dir.INBOUND; screen = Screen.FLIGHTS },
                            onSeeDepartures = { flightsDir = Dir.OUTBOUND; screen = Screen.FLIGHTS },
                            onSeeWeather = { briefingTab = BriefingTab.WEATHER; page = Page.BRIEFING },
                            onSeeNotams = { briefingTab = BriefingTab.NOTAMS; page = Page.BRIEFING },
                            onOpenAirportMap = { mapMode = MapMode.AIRPORT; page = Page.MAP },
                            onOpenMap = { mapMode = MapMode.AIRSPACE; page = Page.MAP },
                            onOpenBriefing = { page = Page.BRIEFING },
                        )
                        Screen.FLIGHTS -> Column(Modifier.fillMaxSize()) {
                            DirToggle(flightsDir, onDir = { flightsDir = it })
                            FlightsScreen(
                                flightsDir, state, now,
                                onFilter = vm::setFilter,
                                onConfigureMine = { showSettings = true },
                                onFlight = { detail = it },
                                onRefresh = vm::refreshAll,
                            )
                        }
                        Screen.TURNAROUNDS -> TurnaroundsScreen(
                            turnVm, turnarounds, snap, now, openTurnaround, onOpen = { openTurnaround = it },
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
            if (state.settings.filter != FlightFilter.MINE && state.myAirlines.isNotEmpty() && screen == Screen.FLIGHTS) {
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
                page = Page.MAP
                detail = null
            },
            onStartTurnaround = {
                openTurnaround = turnVm.startFrom(d, snap.airport.icao)
                screen = Screen.TURNAROUNDS
                page = null
                detail = null
            },
            onDismiss = { detail = null },
        )
    }
}

/** Arrivals or departures, at the top of the Flights tab. */
@Composable
private fun DirToggle(dir: Dir, onDir: (Dir) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            listOf(Dir.INBOUND to "Arrivals", Dir.OUTBOUND to "Departures").forEachIndexed { i, (d, label) ->
                SegmentedButton(
                    selected = dir == d,
                    onClick = { onDir(d) },
                    shape = SegmentedButtonDefaults.itemShape(i, 2),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(label) }
            }
        }
    }
}

@Composable
private fun PageBar(title: String, onBack: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(52.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(title, style = MaterialTheme.typography.titleLarge)
        }
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
