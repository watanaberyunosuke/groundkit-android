package com.harrydatahub.groundkit.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.harrydatahub.groundkit.data.FlightFilter
import com.harrydatahub.groundkit.domain.Dir
import com.harrydatahub.groundkit.ui.FlightBoards
import com.harrydatahub.groundkit.ui.FlightItem
import com.harrydatahub.groundkit.ui.FlightSection
import com.harrydatahub.groundkit.ui.UiState
import com.harrydatahub.groundkit.ui.components.EmptyState
import com.harrydatahub.groundkit.ui.components.FilterRow
import com.harrydatahub.groundkit.ui.components.FlightRow

/**
 * Arrivals or departures: what is on the live feed now, then the regular flights coming
 * up, then (collapsed) the last few hours. Pull down to refresh.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlightsScreen(
    dir: Dir,
    state: UiState,
    now: Long,
    onFilter: (FlightFilter) -> Unit,
    onConfigureMine: () -> Unit,
    onFlight: (FlightItem) -> Unit,
    onRefresh: () -> Unit,
) {
    val snap = state.snapshot ?: return
    val traffic = state.traffic
    val sections = traffic?.let {
        if (dir == Dir.INBOUND) FlightBoards.arrivals(snap, it, state.settings.filter, state.myAirlines)
        else FlightBoards.departures(snap, it, state.settings.filter, state.myAirlines)
    }
    val expanded = remember(dir) { mutableStateMapOf<String, Boolean>() }

    PullToRefreshBox(
        isRefreshing = state.live.loading || state.refreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                FilterRow(state.settings.filter, state.myAirlines.size, onFilter, onConfigureMine)
            }
            if (sections == null) {
                item {
                    EmptyState(
                        state.live.error?.let { "Live positions unavailable: $it. Pull down to retry." }
                            ?: "Loading live traffic…",
                        Modifier.padding(horizontal = 16.dp),
                    )
                }
                return@LazyColumn
            }
            for (section in sections) {
                val open = !section.collapsed || expanded[section.title] == true
                item(key = "h|${section.title}") { SectionHeader(section) }
                when {
                    section.items.isEmpty() -> item(key = "e|${section.title}") {
                        EmptyState("None right now.", Modifier.padding(horizontal = 16.dp))
                    }
                    !open -> item(key = "x|${section.title}") {
                        OutlinedButton(
                            onClick = { expanded[section.title] = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .height(48.dp),
                        ) { Text("Show ${section.items.size} earlier flights") }
                    }
                    else -> items(section.items, key = { "${section.title}|${it.key}" }) { item ->
                        FlightRow(item, snap.zone, now, onClick = { onFlight(item) })
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
            item { androidx.compose.foundation.layout.Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun SectionHeader(section: FlightSection) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics { heading() },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(section.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text("${section.items.size}", style = MaterialTheme.typography.titleMedium)
        }
        Text(section.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
