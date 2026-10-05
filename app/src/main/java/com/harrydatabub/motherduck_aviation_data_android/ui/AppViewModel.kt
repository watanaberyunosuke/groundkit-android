package com.harrydatabub.motherduck_aviation_data_android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.harrydatabub.motherduck_aviation_data_android.BuildConfig
import com.harrydatabub.motherduck_aviation_data_android.data.Airport
import com.harrydatabub.motherduck_aviation_data_android.data.AviationApi
import com.harrydatabub.motherduck_aviation_data_android.data.FlightFilter
import com.harrydatabub.motherduck_aviation_data_android.data.LiveFeed
import com.harrydatabub.motherduck_aviation_data_android.data.Settings
import com.harrydatabub.motherduck_aviation_data_android.data.SettingsStore
import com.harrydatabub.motherduck_aviation_data_android.data.Warehouse
import com.harrydatabub.motherduck_aviation_data_android.data.WarehouseRepository
import com.harrydatabub.motherduck_aviation_data_android.domain.AirportSnapshot
import com.harrydatabub.motherduck_aviation_data_android.domain.BoardLive
import com.harrydatabub.motherduck_aviation_data_android.domain.BoardRow
import com.harrydatabub.motherduck_aviation_data_android.domain.Dir
import com.harrydatabub.motherduck_aviation_data_android.domain.FeedMemory
import com.harrydatabub.motherduck_aviation_data_android.domain.LiveTraffic
import com.harrydatabub.motherduck_aviation_data_android.domain.Operators
import com.harrydatabub.motherduck_aviation_data_android.domain.Overview
import com.harrydatabub.motherduck_aviation_data_android.domain.PlacedAircraft
import com.harrydatabub.motherduck_aviation_data_android.domain.RampAlert
import com.harrydatabub.motherduck_aviation_data_android.domain.RampAlerts
import com.harrydatabub.motherduck_aviation_data_android.domain.Remembered
import com.harrydatabub.motherduck_aviation_data_android.domain.WeatherOverview
import com.harrydatabub.motherduck_aviation_data_android.domain.windText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class AirportChoice(val airport: Airport, val category: String?, val wind: String?)

data class LiveStatus(
    val at: Long? = null,
    val source: String = "",
    val error: String? = null,
    val loading: Boolean = false,
)

/** Live traffic around the selected airport, placed and sorted into the boards. */
data class Traffic(
    val now: Long,
    val placed: List<PlacedAircraft>,
    val boardLive: List<BoardLive>,
    val memory: Map<String, Remembered>,
    val inboundBoard: List<BoardRow>,
    val outboundBoard: List<BoardRow>,
)

data class UiState(
    val settings: Settings,
    /** First load still running with nothing to show. */
    val loading: Boolean = true,
    /** First load failed with nothing cached. */
    val loadError: String? = null,
    val refreshing: Boolean = false,
    val refreshError: String? = null,
    val dataFetchedAt: Long? = null,
    val fromCache: Boolean = false,
    val airports: List<AirportChoice> = emptyList(),
    val snapshot: AirportSnapshot? = null,
    val alerts: List<RampAlert> = emptyList(),
    val traffic: Traffic? = null,
    val live: LiveStatus = LiveStatus(),
    val overview: List<WeatherOverview> = emptyList(),
    /** The user's airlines as ICAO designators, resolved from settings. */
    val myAirlines: Set<String> = emptySet(),
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val settingsStore = SettingsStore(app)
    private val api = AviationApi(BuildConfig.API_BASE, app.noBackupFilesDir)
    private val repo = WarehouseRepository(api)

    private val _state = MutableStateFlow(UiState(settings = settingsStore.settings.value))
    val state: StateFlow<UiState> = _state.asStateFlow()

    // Guarded by `lock`; recomputation and live updates run off the main thread.
    private val lock = Mutex()
    private var warehouse: Warehouse? = null
    private var snapshot: AirportSnapshot? = null
    private var feed: LiveFeed? = null
    private val memory = FeedMemory()

    private var foregroundJob: Job? = null
    private var refreshJob: Job? = null

    init {
        viewModelScope.launch {
            repo.loadCached()?.let { setWarehouse(it) }
            refreshTables()
        }
        viewModelScope.launch {
            settingsStore.settings.drop(1).collect { publish() }
        }
    }

    // ---- Lifecycle: poll only while the app is on screen -------------------------------

    fun onForeground() {
        if (foregroundJob?.isActive == true) return
        foregroundJob = viewModelScope.launch {
            while (isActive) {
                fetchLive()
                val fetched = _state.value.dataFetchedAt
                if (fetched == null || System.currentTimeMillis() - fetched > TABLE_REFRESH_MS) refreshTables()
                delay(LIVE_REFRESH_MS)
            }
        }
    }

    fun onBackground() {
        foregroundJob?.cancel()
        foregroundJob = null
    }

    // ---- User actions -------------------------------------------------------------------

    fun refreshAll() {
        viewModelScope.launch { fetchLive() }
        refreshTables()
    }

    fun selectAirport(iata: String) {
        if (iata == _state.value.settings.airport) return
        viewModelScope.launch {
            lock.withLock {
                memory.clear()
                feed = null
            }
            settingsStore.update { it.copy(airport = iata) }
            _state.update { it.copy(live = LiveStatus(loading = true), traffic = null) }
            publish()
            fetchLive()
        }
    }

    fun setFilter(filter: FlightFilter) = settingsStore.update { it.copy(filter = filter) }

    fun updateSettings(change: (Settings) -> Settings) = settingsStore.update(change)

    // ---- Loading ------------------------------------------------------------------------

    private fun refreshTables() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            _state.update { it.copy(refreshing = true) }
            try {
                setWarehouse(repo.refresh())
                _state.update { it.copy(refreshError = null) }
            } catch (e: Exception) {
                val message = e.message ?: e.javaClass.simpleName
                _state.update {
                    if (warehouse == null) it.copy(loading = false, loadError = message, refreshError = message)
                    else it.copy(refreshError = message)
                }
            } finally {
                _state.update { it.copy(refreshing = false) }
            }
        }
    }

    private suspend fun setWarehouse(w: Warehouse) {
        val firstLoad = lock.withLock {
            val first = warehouse == null
            warehouse = w
            snapshot = null
            first
        }
        publish()
        // The live poll needs the airport list; on a cold start it may have run without it.
        if (firstLoad && foregroundJob?.isActive == true) viewModelScope.launch { fetchLive() }
    }

    private suspend fun fetchLive() {
        val icao = lock.withLock { selectedAirport()?.icao } ?: return
        _state.update { it.copy(live = it.live.copy(loading = true)) }
        try {
            val f = api.live(icao)
            withContext(Dispatchers.Default) {
                lock.withLock {
                    if (selectedAirport()?.icao != icao) return@withContext
                    feed = f
                    // Remember what this fix showed, once per fix: the boards need it.
                    snapshotLocked()?.let { snap ->
                        val placed = place(snap, f)
                        memory.update(LiveTraffic.boardLive(placed, snap.history, f.at, snap.zone), f.at)
                    }
                }
            }
            _state.update { it.copy(live = LiveStatus(at = f.at, source = f.source)) }
        } catch (e: Exception) {
            _state.update { it.copy(live = it.live.copy(loading = false, error = e.message ?: e.javaClass.simpleName)) }
        }
        publish()
    }

    // ---- Derived state ------------------------------------------------------------------

    private suspend fun publish() = withContext(Dispatchers.Default) {
        val settings = settingsStore.settings.value
        val next = lock.withLock {
            val w = warehouse ?: return@withLock null
            val snap = snapshotLocked()
            val f = feed?.takeIf { snap != null }
            val now = System.currentTimeMillis()
            val traffic = if (snap != null && f != null) {
                val placed = place(snap, f)
                val boardLive = LiveTraffic.boardLive(placed, snap.history, f.at, snap.zone)
                val mem = memory.snapshot()
                Traffic(
                    now = f.at,
                    placed = placed,
                    boardLive = boardLive,
                    memory = mem,
                    inboundBoard = LiveTraffic.buildBoard(Dir.INBOUND, boardLive, mem, snap.history, f.at, snap.zone, snap.codes),
                    outboundBoard = LiveTraffic.buildBoard(Dir.OUTBOUND, boardLive, mem, snap.history, f.at, snap.zone, snap.codes),
                )
            } else null
            _state.value.copy(
                loading = false,
                loadError = null,
                dataFetchedAt = w.fetchedAt,
                fromCache = w.fromCache,
                airports = Overview.airportsByTraffic(w, now).map { a ->
                    val c = w.conditions[a.icao]
                    AirportChoice(a, c?.flightCategory, c?.takeIf { it.metarRaw != null }?.let(::windText))
                },
                snapshot = snap,
                alerts = RampAlerts.from(snap?.conditions, settings.gustCautionKt, settings.highWindKt, now),
                traffic = traffic,
                overview = Overview.weather7d(w, now),
                myAirlines = Operators.parseMine(settings.myAirlines, w.airlines),
            )
        }
        if (next != null) {
            // Keep live status and refresh flags that changed while computing.
            _state.update { cur ->
                next.copy(live = cur.live, refreshing = cur.refreshing, refreshError = cur.refreshError, settings = settingsStore.settings.value)
            }
        } else {
            _state.update { it.copy(settings = settingsStore.settings.value) }
        }
    }

    private fun place(snap: AirportSnapshot, f: LiveFeed): List<PlacedAircraft> = LiveTraffic.place(
        f.aircraft, snap.airport.lat, snap.airport.lon, snap.history,
        snap.terminalArrMin, snap.terminalDepMin, snap.zone, f.at, snap.codes,
    )

    /** The selected airport: the saved IATA (or ICAO) code, else HKG as in the Dive, else the first. */
    private fun selectedAirport(): Airport? {
        val airports = warehouse?.airports ?: return null
        val code = settingsStore.settings.value.airport
        return airports.firstOrNull { it.iata == code || it.icao == code }
            ?: airports.firstOrNull { it.iata == DEFAULT_AIRPORT }
            ?: airports.firstOrNull()
    }

    private fun snapshotLocked(): AirportSnapshot? {
        val w = warehouse ?: return null
        val airport = selectedAirport() ?: return null
        snapshot?.takeIf { it.airport.icao == airport.icao }?.let { return it }
        return AirportSnapshot.build(w, airport, System.currentTimeMillis()).also { snapshot = it }
    }

    private companion object {
        const val DEFAULT_AIRPORT = "HKG"
        /** The API edge-caches live positions for 2 minutes and tables for 10. */
        const val LIVE_REFRESH_MS = 120_000L
        const val TABLE_REFRESH_MS = 600_000L
    }
}
