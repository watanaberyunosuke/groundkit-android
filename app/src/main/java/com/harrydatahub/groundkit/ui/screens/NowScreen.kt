package com.harrydatahub.groundkit.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.FilterDrama
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Thunderstorm
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WbCloudy
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.harrydatahub.groundkit.data.FlightFilter
import com.harrydatahub.groundkit.data.LocalWeather
import com.harrydatahub.groundkit.domain.AlertLevel
import com.harrydatahub.groundkit.domain.HeatStress
import com.harrydatahub.groundkit.domain.WeatherWords
import com.harrydatahub.groundkit.data.Notam
import com.harrydatahub.groundkit.domain.AirportSnapshot
import com.harrydatahub.groundkit.domain.Placement
import com.harrydatahub.groundkit.ui.FlightBoards
import com.harrydatahub.groundkit.ui.FlightItem
import com.harrydatahub.groundkit.ui.Fmt
import com.harrydatahub.groundkit.ui.UiState
import com.harrydatahub.groundkit.ui.components.AlertCard
import com.harrydatahub.groundkit.ui.components.EmptyState
import com.harrydatahub.groundkit.ui.components.FlightRow
import com.harrydatahub.groundkit.ui.components.NoAlertsCard
import com.harrydatahub.groundkit.ui.components.Reading
import com.harrydatahub.groundkit.ui.components.TileGrid
import com.harrydatahub.groundkit.ui.components.SectionCard
import com.harrydatahub.groundkit.ui.map.MapScreen
import java.time.ZoneOffset
import kotlin.math.roundToInt

/** NOTAM categories that touch work on the ground. */
val AIRSIDE_CATEGORIES = setOf("apron", "taxiway", "movement_area", "runway", "aerodrome", "lighting", "obstacle")

@Composable
fun NowScreen(
    state: UiState,
    now: Long,
    onFlight: (FlightItem) -> Unit,
    onSeeArrivals: () -> Unit,
    onSeeDepartures: () -> Unit,
    onSeeWeather: () -> Unit,
    onSeeNotams: () -> Unit,
    onOpenAirportMap: () -> Unit,
    onOpenMap: () -> Unit,
    onOpenBriefing: () -> Unit,
) {
    val snap = state.snapshot ?: return
    val traffic = state.traffic
    val filter = state.settings.filter
    val arrivals = traffic?.let { FlightBoards.arrivals(snap, it, filter, state.myAirlines) }
    val departures = traffic?.let { FlightBoards.departures(snap, it, filter, state.myAirlines) }
    // Next up: live flights first, then regular flights by usual time.
    val nextArrivals = arrivals?.let { it[0].items + it[2].items }?.take(4)?.sortedBy { it.time ?: Long.MAX_VALUE }
    val nextDepartures = departures?.let { it[0].items + it[2].items }?.take(4)?.sortedBy { it.time ?: Long.MAX_VALUE }
    val filterNote = when (filter) {
        FlightFilter.ALL -> null
        FlightFilter.MINE -> "My airlines only"
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Clocks(snap, now) }
        // The all-clear whenever nothing needs care, with any notes (rain, old weather) under it.
        if (snap.conditions?.metarRaw != null && state.alerts.none { it.level >= AlertLevel.CAUTION }) {
            item { NoAlertsCard() }
        }
        items(state.alerts) { AlertCard(it) }
        item { AviationWeather(snap, now, state.metarIsLive, onSeeWeather) }
        state.localWeather?.let { w -> item { LocalWeatherCard(w, snap) } }
        item {
            NextFlights(
                "Next arrivals", filterNote, nextArrivals, snap, now, state.live.error, onFlight, onSeeArrivals,
                "See all arrivals",
            )
        }
        item {
            NextFlights(
                "Next departures", filterNote, nextDepartures, snap, now, state.live.error, onFlight, onSeeDepartures,
                "See all departures",
            )
        }
        item { MapPreview(state, onOpenMap) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PageLink(Icons.Filled.Map, "Airport map: stands, gates, routes", onOpenAirportMap)
                PageLink(Icons.Filled.Cloud, "Briefing: METAR, TAF, NOTAMs, traffic", onOpenBriefing)
            }
        }
        item { AirsideNotams(snap, onSeeNotams) }
        item { LiveFooter(state, snap) }
    }
}

@Composable
private fun Clocks(snap: AirportSnapshot, now: Long) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        Clock("${snap.airport.iata} local", Fmt.hms(now, snap.zone), "${Fmt.date(now, snap.zone)} · ${Fmt.offset(snap.zone, now)}", Modifier.weight(1.2f), primary = true)
        Clock("UTC", Fmt.hms(now, ZoneOffset.UTC), Fmt.date(now, ZoneOffset.UTC), Modifier.weight(1f))
    }
}

@Composable
private fun Clock(label: String, time: String, sub: String, modifier: Modifier, primary: Boolean = false) {
    Card(
        modifier,
        colors = CardDefaults.cardColors(
            containerColor = if (primary) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(time, style = MaterialTheme.typography.headlineMedium, maxLines = 1)
            Text(sub, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The latest METAR as plain readings: what the airport is reporting for aircraft. */
@Composable
private fun AviationWeather(snap: AirportSnapshot, now: Long, live: Boolean, onClick: () -> Unit) {
    val c = snap.conditions
    SectionCard(
        title = "Aviation weather",
        subtitle = c?.metarObservedAt?.let {
            "METAR ${Fmt.zulu(it)}, ${Fmt.age(it, now)}" + if (live) "" else " (hourly copy)"
        },
        modifier = Modifier.clickable(onClickLabel = "Open the briefing", onClick = onClick),
    ) {
        if (c?.metarRaw == null) {
            EmptyState("No METAR for ${snap.airport.iata} yet.")
            return@SectionCard
        }
        val gust = maxOf(c.windSpeedKt ?: 0, c.windGustKt ?: 0)
        val status = com.harrydatahub.groundkit.ui.theme.LocalStatusColors.current
        val feelsLike = c.tempC?.let { HeatStress.feelsLikeC(it, c.dewpointC, c.windSpeedKt) }
        TileGrid(
            listOf(
                Reading(
                    "Wind", WeatherWords.wind(c.windDirDeg, c.windVariable, c.windSpeedKt, c.windGustKt), Icons.Filled.Air,
                    if (c.windVariable) "Changing direction" else c.windDirDeg?.let { "From ${WeatherWords.compassName(it)}" },
                    when {
                        gust >= 40 -> status.red
                        gust >= 25 -> status.amber
                        else -> null
                    },
                ),
                Reading(
                    "Visibility", WeatherWords.visibility(c.visibilitySm, c.visibilityIsLowerBound), Icons.Filled.Visibility,
                    WeatherWords.category(c.flightCategory),
                ),
                Reading("Weather", WeatherWords.describe(c.wxString) ?: "Nothing significant", Icons.Filled.WbCloudy),
                Reading(
                    "Cloud ceiling", c.ceilingFt?.let { "%,d ft".format(java.util.Locale.UK, it) } ?: "None", Icons.Filled.FilterDrama,
                    if (c.ceilingFt == null) "No broken or overcast layer" else "Lowest broken or overcast layer",
                ),
                Reading(
                    "Temperature", WeatherWords.temp(c.tempC), Icons.Filled.Thermostat,
                    listOfNotNull(
                        c.dewpointC?.let { "Dew point ${WeatherWords.temp(it)}" },
                        feelsLike?.let { "feels like ${WeatherWords.temp(it)}" },
                    ).joinToString(", "),
                ),
                Reading("Pressure", WeatherWords.pressure(c.altimeterHpa), Icons.Filled.Speed, "QNH, for altimeters"),
            ),
        )
    }
}

/** Current weather at the airport's coordinates from Open-Meteo: what it feels like outside. */
@Composable
private fun LocalWeatherCard(w: LocalWeather, snap: AirportSnapshot) {
    SectionCard(
        title = "At the airport now",
        subtitle = "Open-Meteo forecast model, ${Fmt.hm(w.observedAt, snap.zone)} ${snap.airport.iata} time",
    ) {
        val sky = when (w.weatherCode ?: -1) {
            0, 1, 2 -> if (w.isDay) Icons.Filled.WbSunny else Icons.Filled.NightsStay
            in 51..67, in 80..82 -> Icons.Filled.WaterDrop
            in 95..99 -> Icons.Filled.Thunderstorm
            else -> Icons.Filled.Cloud
        }
        TileGrid(
            listOf(
                Reading("Sky", w.conditions ?: "–", sky, w.cloudCoverPct?.let { "Cloud cover $it%" }),
                Reading(
                    "Feels like", WeatherWords.temp(w.feelsLikeC ?: w.tempC), Icons.Filled.Thermostat,
                    listOfNotNull(w.tempC?.let { "Air ${WeatherWords.temp(it)}" }, w.humidityPct?.let { "humidity $it%" })
                        .joinToString(", "),
                ),
                Reading(
                    "Wind", WeatherWords.wind(w.windDirDeg, false, w.windSpeedKt, w.windGustKt), Icons.Filled.Air,
                    w.windDirDeg?.let { "From ${WeatherWords.compassName(it)}" },
                ),
                Reading(
                    "Rain",
                    w.precipitationMm?.let { if (it < 0.05) "None" else "%.1f mm".format(java.util.Locale.ROOT, it) } ?: "–",
                    Icons.Filled.WaterDrop, "Last 15 minutes",
                ),
            ),
        )
    }
}

@Composable
private fun NextFlights(
    title: String,
    filterNote: String?,
    items: List<FlightItem>?,
    snap: AirportSnapshot,
    now: Long,
    liveError: String?,
    onFlight: (FlightItem) -> Unit,
    onSeeAll: () -> Unit,
    seeAllLabel: String,
) {
    SectionCard(title = title, subtitle = filterNote) {
        when {
            items == null -> EmptyState(liveError?.let { "Live positions unavailable: $it" } ?: "Loading live traffic…")
            items.isEmpty() -> EmptyState("No regular flights expected in the next few hours.")
            else -> Column {
                items.forEachIndexed { i, item ->
                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    // The card already pads its content.
                    FlightRow(item, snap.zone, now, onClick = { onFlight(item) }, horizontalPadding = 0.dp)
                }
            }
        }
        SeeAll(seeAllLabel, onSeeAll)
    }
}

/** The airspace as a still picture, as on iOS; tap for the full map. */
@Composable
private fun MapPreview(state: UiState, onOpen: () -> Unit) {
    val placed = state.traffic?.placed.orEmpty()
    val inbound = placed.count { it.placement == Placement.INBOUND }
    val outbound = placed.count { it.placement == Placement.OUTBOUND }
    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .height(240.dp)
                .clip(RoundedCornerShape(16.dp))
                .clickable(onClickLabel = "Open the full map", onClick = onOpen),
        ) {
            MapScreen(state, selected = null, onSelect = {}, onDetails = {}, preview = true)
            Surface(
                Modifier.align(Alignment.TopEnd).padding(8.dp),
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.93f),
            ) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.OpenInFull, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Full map", style = MaterialTheme.typography.titleSmall)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                state.traffic == null -> if (state.live.error != null) "Live aircraft unavailable" else "Loading live aircraft…"
                inbound + outbound == 0 -> "No aircraft heading in or out within 500 NM"
                else -> "$inbound flying in and $outbound flying out within 500 NM"
            },
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Opens the airport map or the briefing, which are not tabs. */
@Composable
private fun PageLink(icon: ImageVector, label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().height(60.dp)) {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
    }
}

@Composable
private fun AirsideNotams(snap: AirportSnapshot, onSeeAll: () -> Unit) {
    val feed = snap.conditions?.notamsInForce != null
    val airside = snap.notams.filter { it.category in AIRSIDE_CATEGORIES }
    SectionCard(
        title = "Airside NOTAMs",
        subtitle = if (feed) "${airside.size} of ${snap.notams.size} NOTAMs in force touch the movement area" else null,
    ) {
        when {
            !feed -> EmptyState("No NOTAM feed for ${snap.airport.iata}. Check your NOTAM source.")
            airside.isEmpty() -> EmptyState("No apron, taxiway or runway NOTAMs in force.")
            else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                airside.take(3).forEach { NotamSummary(it) }
            }
        }
        if (feed) SeeAll("All NOTAMs", onSeeAll)
    }
}

@Composable
private fun NotamSummary(n: Notam) {
    Column {
        Text(
            listOfNotNull(n.number, n.category?.let(Fmt::humanize), n.condition?.let(Fmt::humanize)).joinToString(" · "),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            notamBody(n), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Item E) of a NOTAM where it can be found, else the raw text. */
fun notamBody(n: Notam): String {
    val raw = n.rawText ?: return ""
    val e = Regex("""\bE\)\s*(.*?)(?:\s+[FG]\)|$)""", RegexOption.DOT_MATCHES_ALL).find(raw)?.groupValues?.get(1)
    return (e ?: raw).replace(Regex("\\s+"), " ").trim()
}

@Composable
private fun SeeAll(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().height(48.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.width(6.dp))
        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
    }
}

@Composable
private fun LiveFooter(state: UiState, snap: AirportSnapshot) {
    val live = state.live
    val t = state.traffic
    val text = when {
        live.error != null && t == null -> "Live positions unavailable: ${live.error}"
        t == null || live.at == null -> "Loading live positions…"
        else -> {
            val inbound = t.placed.count { it.placement == Placement.INBOUND }
            val outbound = t.placed.count { it.placement == Placement.OUTBOUND }
            "${t.placed.size} aircraft within 500 NM from ${live.source.ifEmpty { "ADS-B" }}: $inbound inbound, " +
                "$outbound outbound. Updated ${Fmt.hm(live.at, snap.zone)} ${snap.airport.iata} time." +
                (live.error?.let { " Last refresh failed: $it" } ?: "")
        }
    }
    Column {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Text(
            "No airline schedules: delay is against each flight's usual time over the last 30 days. " +
                "For situational awareness only; your ops control and local procedures take precedence.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
