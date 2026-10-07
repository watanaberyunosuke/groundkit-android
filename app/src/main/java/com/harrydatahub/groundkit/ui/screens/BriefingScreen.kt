package com.harrydatahub.groundkit.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.harrydatahub.groundkit.data.Notam
import com.harrydatahub.groundkit.domain.AirportSnapshot
import com.harrydatahub.groundkit.domain.ceilingText
import com.harrydatahub.groundkit.domain.visText
import com.harrydatahub.groundkit.domain.windText
import com.harrydatahub.groundkit.ui.Fmt
import com.harrydatahub.groundkit.ui.UiState
import com.harrydatahub.groundkit.ui.components.CategoryBadge
import com.harrydatahub.groundkit.ui.components.EmptyState
import com.harrydatahub.groundkit.ui.components.MonoBlock
import com.harrydatahub.groundkit.ui.components.MovementsChart
import com.harrydatahub.groundkit.ui.components.SectionCard
import com.harrydatahub.groundkit.ui.components.TileRow
import com.harrydatahub.groundkit.ui.components.WindChart
import com.harrydatahub.groundkit.ui.components.WindCompass
import com.harrydatahub.groundkit.ui.theme.LocalStatusColors
import kotlin.math.roundToInt

enum class BriefingTab(val title: String) { WEATHER("Weather"), NOTAMS("NOTAMs"), TRAFFIC("Traffic") }

private val NOTAM_SOURCES = mapOf(
    "hk_cad" to "Hong Kong CAD",
    "faa_search" to "FAA NOTAM Search",
    "faa" to "FAA NOTAM API",
    "rapidapi" to "SkyLink on RapidAPI",
)

@Composable
fun BriefingScreen(
    state: UiState,
    now: Long,
    tab: BriefingTab,
    onTab: (BriefingTab) -> Unit,
    onSelectAirport: (String) -> Unit,
) {
    val snap = state.snapshot ?: return
    Column(Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = tab.ordinal) {
            BriefingTab.entries.forEach { t ->
                Tab(
                    selected = t == tab, onClick = { onTab(t) },
                    modifier = Modifier.heightIn(min = 52.dp),
                    text = { Text(t.title, style = MaterialTheme.typography.labelLarge) },
                )
            }
        }
        when (tab) {
            BriefingTab.WEATHER -> WeatherTab(state, snap, now, onSelectAirport)
            BriefingTab.NOTAMS -> NotamsTab(snap)
            BriefingTab.TRAFFIC -> TrafficTab(snap)
        }
    }
}

@Composable
private fun WeatherTab(state: UiState, snap: AirportSnapshot, now: Long, onSelectAirport: (String) -> Unit) {
    val c = snap.conditions
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionCard(
                "Current conditions",
                subtitle = c?.metarObservedAt?.let { "METAR ${Fmt.zulu(it)} (${Fmt.age(it, now)}). Loaded twice a day." },
                trailing = { c?.flightCategory?.let { CategoryBadge(it, large = true) } },
            ) {
                if (c?.metarRaw == null) {
                    EmptyState("No METAR for ${snap.airport.iata} yet.")
                    return@SectionCard
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    WindCompass(c, 84.dp)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(windText(c), style = MaterialTheme.typography.headlineSmall)
                        Text(
                            if (c.windVariable) "Variable direction" else c.windDirDeg?.let { "From %03d°".format(java.util.Locale.ROOT, it) } ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                TileRow("Visibility" to visText(c), "Ceiling" to ceilingText(c))
                Spacer(Modifier.height(8.dp))
                TileRow(
                    "Temp / dew" to (c.tempC?.let { "${it.roundToInt()}° / ${c.dewpointC?.roundToInt() ?: "–"}°C" } ?: "–"),
                    "QNH" to (c.altimeterHpa?.let { "${it.roundToInt()} hPa" } ?: "–"),
                )
                Spacer(Modifier.height(8.dp))
                TileRow(
                    "Weather" to (c.wxString ?: "Nil"),
                    "NOTAMs in force" to (c.notamsInForce?.toString() ?: "No feed"),
                )
                Spacer(Modifier.height(12.dp))
                Text("METAR", style = MaterialTheme.typography.labelLarge)
                MonoBlock(c.metarRaw)
                Spacer(Modifier.height(10.dp))
                Text(
                    if (c.tafRaw != null && c.tafIssuedAt != null) {
                        "TAF issued ${Fmt.zulu(c.tafIssuedAt)}, valid " +
                            "${c.tafValidFrom?.let(Fmt::zulu) ?: "?"} to ${c.tafValidTo?.let(Fmt::zulu) ?: "?"}"
                    } else "TAF",
                    style = MaterialTheme.typography.labelLarge,
                )
                MonoBlock(c.tafRaw ?: "No TAF for this airport yet.")
            }
        }
        item {
            SectionCard("Wind and flight category, last 72 hours", subtitle = "${snap.airport.iata} local time") {
                WindChart(snap.hourly, snap.zone)
            }
        }
        item {
            SectionCard("Last 7 days, all airports", subtitle = "Share of observed hours. Tap an airport to switch.") {
                OverviewTable(state, snap, onSelectAirport)
            }
        }
    }
}

@Composable
private fun OverviewTable(state: UiState, snap: AirportSnapshot, onSelectAirport: (String) -> Unit) {
    if (state.overview.isEmpty()) {
        EmptyState("No METARs in the last 7 days.")
        return
    }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column {
        Row(Modifier.padding(vertical = 6.dp)) {
            listOf("Airport", "IFR", "Gusts", "TS", "Max gust").forEachIndexed { i, h ->
                Text(
                    h, color = muted, style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(if (i == 0) 1.1f else 1f),
                    textAlign = if (i == 0) TextAlign.Start else TextAlign.End,
                )
            }
        }
        state.overview.forEach { r ->
            val current = r.icao == snap.airport.icao
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable { onSelectAirport(r.iata) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val weight = if (current) FontWeight.Bold else FontWeight.Normal
                Text(r.iata, fontWeight = weight, modifier = Modifier.weight(1.1f), style = MaterialTheme.typography.bodyLarge)
                listOf(Fmt.percent(r.ifrShare), Fmt.percent(r.gustyShare), Fmt.percent(r.thunderstormShare), r.maxGustKt?.let { "$it kt" } ?: "–")
                    .forEach {
                        Text(it, fontWeight = weight, modifier = Modifier.weight(1f), textAlign = TextAlign.End, style = MaterialTheme.typography.bodyLarge)
                    }
            }
        }
    }
}

@Composable
private fun NotamsTab(snap: AirportSnapshot) {
    val source = NOTAM_SOURCES[snap.airport.notamSource]
    val feed = snap.conditions?.notamsInForce != null
    val airsideCount = snap.notams.count { it.category in AIRSIDE_CATEGORIES }
    // Airside first, as that is what ramp work needs; all of them when none are airside.
    var category by rememberSaveable(snap.airport.icao) { mutableStateOf(if (airsideCount > 0) AIRSIDE else ALL) }
    var query by rememberSaveable(snap.airport.icao) { mutableStateOf("") }
    val counts = remember(snap.notams) {
        snap.notams.groupingBy { it.category ?: UNCATEGORISED }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
    }
    val shown = snap.notams.filter { n ->
        when (category) {
            ALL -> true
            AIRSIDE -> n.category in AIRSIDE_CATEGORIES
            else -> (n.category ?: UNCATEGORISED) == category
        } && (query.isBlank() || n.rawText?.contains(query.trim(), ignoreCase = true) == true)
    }

    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Text(
                "Every NOTAM for ${snap.airport.icao} in force now, newest first" +
                    (source?.let { ", from the $it" } ?: "") +
                    ". Times UTC; refreshed every 3 hours. For awareness, not flight planning.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
        if (!feed) {
            item {
                EmptyState(
                    if (source != null) "No NOTAMs loaded for ${snap.airport.iata} yet from the $source."
                    else "No NOTAM feed for ${snap.airport.iata}. Check your usual NOTAM source.",
                    Modifier.padding(horizontal = 16.dp),
                )
            }
            return@LazyColumn
        }
        item {
            Row(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CategoryChip("Airside ($airsideCount)", category == AIRSIDE) { category = AIRSIDE }
                CategoryChip("All (${snap.notams.size})", category == ALL) { category = ALL }
                counts.forEach { (c, n) -> CategoryChip("${Fmt.humanize(c)} ($n)", category == c) { category = c } }
            }
        }
        item {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                placeholder = { Text("Search text, e.g. TWY B or stand") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (shown.isEmpty()) {
            item { EmptyState("No NOTAMs match.", Modifier.padding(horizontal = 16.dp)) }
        }
        items(shown, key = { it.key }) { n ->
            NotamCard(n)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun CategoryChip(label: String, selected: Boolean, onClick: () -> Unit) = FilterChip(
    selected = selected, onClick = onClick,
    label = { Text(label, style = MaterialTheme.typography.labelLarge) },
    modifier = Modifier.height(44.dp),
    colors = FilterChipDefaults.filterChipColors(
        selectedContainerColor = MaterialTheme.colorScheme.primary,
        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
    ),
)

@Composable
private fun NotamCard(n: Notam) {
    val amber = LocalStatusColors.current.amber
    val red = LocalStatusColors.current.red
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(n.number ?: "NOTAM", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(10.dp))
            Text(
                listOfNotNull(n.category?.let(Fmt::humanize), n.condition?.let(Fmt::humanize)).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = if (n.isRunwayClosure) red else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (n.isRunwayClosure) FontWeight.Bold else FontWeight.Normal,
            )
        }
        Text(
            "${n.startsAt?.let(Fmt::zuluLong) ?: "?"} to " +
                (if (n.isPermanent) "PERM" else n.endsAt?.let(Fmt::zuluLong) ?: "?") +
                if (n.isEstimated) " (estimated)" else "",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (n.hasSchedule && n.schedule != null) {
            Text("Active only: ${n.schedule}", style = MaterialTheme.typography.bodyMedium, color = amber, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(6.dp))
        MonoBlock(n.rawText ?: "")
    }
}

@Composable
private fun TrafficTab(snap: AirportSnapshot) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionCard("Last 30 days at ${snap.airport.iata}", subtitle = "Observed by ADS-B (OpenSky)") {
                TileRow(
                    "Arrivals seen" to Fmt.thousands(snap.observedArrivals30d),
                    "Departures seen" to Fmt.thousands(snap.observedDepartures30d),
                )
                Spacer(Modifier.height(8.dp))
                TileRow(
                    "50 NM to landing" to (snap.medianArrivalTerminalMin?.let { "${it.roundToInt()} min" } ?: "–"),
                    "Take-off to 50 NM" to (snap.medianDepartureTerminalMin?.let { "${it.roundToInt()} min" } ?: "–"),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Median times inside the 50 NM terminal area. The boards use them to estimate ETAs: " +
                        "ground speed to the 50 NM ring, then this typical time to the runway.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            SectionCard("Daily movements", subtitle = "UTC days. Undercounts where ADS-B coverage is thin.") {
                MovementsChart(snap.movements)
            }
        }
    }
}

private const val ALL = "__all"
private const val AIRSIDE = "__airside"
private const val UNCATEGORISED = "uncategorised"
