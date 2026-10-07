package com.harrydatahub.groundkit.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.harrydatahub.groundkit.domain.AirportSnapshot
import com.harrydatahub.groundkit.domain.Dir
import com.harrydatahub.groundkit.domain.hhmm
import com.harrydatahub.groundkit.ui.FlightItem
import com.harrydatahub.groundkit.ui.Fmt
import com.harrydatahub.groundkit.ui.components.StatusPill
import com.harrydatahub.groundkit.ui.theme.FlightCodeStyle
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlightDetailSheet(
    item: FlightItem,
    snap: AirportSnapshot,
    now: Long,
    onShowOnMap: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val zone = snap.zone
    val here = snap.airport.iata
    val a = item.aircraft
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 20.dp),
        ) {
            Text(item.code, style = FlightCodeStyle.copy(fontSize = FlightCodeStyle.fontSize * 1.3f))
            val sub = listOfNotNull(item.callsign?.takeIf { it != item.code }, item.airline).joinToString(" · ")
            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Text(
                when {
                    !item.recognised -> "Not recognised as a $here flight"
                    item.dir == Dir.INBOUND -> "${item.other ?: "?"} → $here"
                    else -> "$here → ${item.other ?: "?"}"
                },
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(8.dp))
            StatusPill(item.status, item.tone)
            Spacer(Modifier.height(12.dp))

            Facts(
                buildList {
                    item.time?.let { t ->
                        add(item.timeLabel to "${if (item.estimated) "~" else ""}${Fmt.hm(t, zone)} $here (${Fmt.relative(t, now)})")
                    }
                    item.usual?.let { u ->
                        add((if (item.dir == Dir.INBOUND) "Usual arrival" else "Usual departure") to "${hhmm(u.usualMin)} $here")
                        add("Regularity" to "Seen on ${u.days14} of the last 14 days")
                    }
                    if (a != null) {
                        add("Distance" to "${a.distNm.roundToInt()} NM from $here")
                        add("Altitude" to if (a.live.onGround) "On the ground" else "${Fmt.thousands(a.live.altFt)} ft")
                        a.live.speedKt?.let { add("Ground speed" to "$it kt") }
                        a.live.vrateFpm?.takeIf { it != 0 }?.let { add("Vertical" to "${if (it > 0) "+" else ""}$it ft/min") }
                        a.live.trackDeg?.let { add("Track" to "%03d°".format(java.util.Locale.ROOT, it.roundToInt() % 360)) }
                        add("Transponder" to a.live.icao24.uppercase())
                    }
                },
            )

            item.usual?.recent?.takeIf { it.isNotEmpty() }?.let { recent ->
                Spacer(Modifier.height(16.dp))
                Text(
                    if (item.dir == Dir.INBOUND) "Recent arrivals at $here" else "Recent departures from $here",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(4.dp))
                recent.forEach { t ->
                    Text("${Fmt.dayHm(t, zone)} $here", style = MaterialTheme.typography.bodyLarge)
                }
                Text(
                    "Observed by ADS-B (OpenSky); the latest complete day is loaded each morning.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (a != null) {
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { onShowOnMap(a.live.icao24) },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Icon(Icons.Filled.Map, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Show on map")
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Times are estimates from live position and this flight's usual time; there is no airline schedule.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Facts(rows: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        rows.forEachIndexed { i, (label, value) ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.42f))
                Text(value, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(0.58f))
            }
        }
    }
}
