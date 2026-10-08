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
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.LocalParking
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
import com.harrydatahub.groundkit.data.Notam
import com.harrydatahub.groundkit.domain.AirportSnapshot
import com.harrydatahub.groundkit.domain.Placement
import com.harrydatahub.groundkit.domain.ceilingText
import com.harrydatahub.groundkit.domain.visText
import com.harrydatahub.groundkit.domain.windText
import com.harrydatahub.groundkit.ui.FlightBoards
import com.harrydatahub.groundkit.ui.FlightItem
import com.harrydatahub.groundkit.ui.Fmt
import com.harrydatahub.groundkit.ui.UiState
import com.harrydatahub.groundkit.ui.components.AlertCard
import com.harrydatahub.groundkit.ui.components.CategoryBadge
import com.harrydatahub.groundkit.ui.components.EmptyState
import com.harrydatahub.groundkit.ui.components.FlightRow
import com.harrydatahub.groundkit.ui.components.NoAlertsCard
import com.harrydatahub.groundkit.ui.components.SectionCard
import com.harrydatahub.groundkit.ui.components.TileRow
import com.harrydatahub.groundkit.ui.components.WindCompass
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
    val nextArrivals = arrivals?.let { it[0].items + it[2].items }?.take(4)
    val nextDepartures = departures?.let { it[0].items + it[2].items }?.take(4)
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
        if (state.alerts.isEmpty()) {
            if (snap.conditions?.metarRaw != null) item { NoAlertsCard() }
        } else {
            items(state.alerts) { AlertCard(it) }
        }
        item { WeatherGlance(snap, now, onSeeWeather) }
        item { MapPreview(state, onOpenMap) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PageLink(Icons.Filled.LocalParking, "Airport map: stands, gates, routes", onOpenAirportMap)
                PageLink(Icons.Filled.Cloud, "Briefing: METAR, TAF, NOTAMs, traffic", onOpenBriefing)
            }
        }
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

@Composable
private fun WeatherGlance(snap: AirportSnapshot, now: Long, onClick: () -> Unit) {
    val c = snap.conditions
    SectionCard(
        title = "Weather now",
        subtitle = c?.metarObservedAt?.let { "METAR ${Fmt.zulu(it)}, ${Fmt.age(it, now)}" },
        modifier = Modifier.clickable(onClick = onClick),
        trailing = { c?.flightCategory?.let { CategoryBadge(it, large = true) } },
    ) {
        if (c?.metarRaw == null) {
            EmptyState("No METAR for ${snap.airport.iata} yet.")
            return@SectionCard
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            WindCompass(c, 76.dp)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(windText(c), style = MaterialTheme.typography.headlineSmall)
                Text(
                    when {
                        c.windGustKt != null -> "Gusting ${c.windGustKt} kt"
                        c.windVariable -> "Variable direction"
                        else -> "Surface wind"
                    },
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        TileRow(
            "Visibility" to visText(c),
            "Ceiling" to ceilingText(c),
            "Temp" to (c.tempC?.let { "${it.roundToInt()}°C" } ?: "–"),
            "QNH" to (c.altimeterHpa?.let { "${it.roundToInt()}" } ?: "–"),
        )
        c.wxString?.let {
            Spacer(Modifier.height(8.dp))
            Text("Weather: $it", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        }
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
            items.isEmpty() -> EmptyState("Nothing recognised in the next few hours.")
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
            "$inbound inbound · $outbound outbound within 500 NM",
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
