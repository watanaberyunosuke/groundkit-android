package com.harrydatahub.groundkit.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.harrydatahub.groundkit.BuildConfig
import com.harrydatahub.groundkit.data.Airport
import com.harrydatahub.groundkit.data.Settings
import com.harrydatahub.groundkit.data.ThemeMode
import com.harrydatahub.groundkit.domain.HeatStrain
import com.harrydatahub.groundkit.domain.Solar
import com.harrydatahub.groundkit.ui.AirportChoice
import com.harrydatahub.groundkit.ui.Fmt
import com.harrydatahub.groundkit.ui.components.CategoryBadge
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AirportPickerSheet(
    airports: List<AirportChoice>,
    selectedIcao: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text("Choose airport", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            Text(
                "Busiest first. The choice is remembered.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.size(8.dp))
            airports.forEach { choice ->
                val a = choice.airport
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 68.dp)
                        .clickable { onPick(a.iata) }
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(a.iata, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.width(72.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                        Text(
                            listOfNotNull(a.icao, choice.wind).joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    choice.category?.let { CategoryBadge(it) }
                    if (a.icao == selectedIcao) {
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.Filled.CheckCircle, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    settings: Settings,
    resolvedAirlines: Set<String>,
    airport: Airport?,
    onChange: ((Settings) -> Settings) -> Unit,
    onDismiss: () -> Unit,
) {
    var airlines by remember { mutableStateOf(settings.myAirlines) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text("Settings", style = MaterialTheme.typography.titleLarge)

            Column {
                Label("My airlines")
                Text(
                    "The airlines you handle, for the \"My airlines\" filter. IATA or ICAO codes, " +
                        "separated by commas: CX, SQ, FDX.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = airlines,
                    onValueChange = {
                        airlines = it
                        onChange { s -> s.copy(myAirlines = it) }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    placeholder = { Text("CX, SQ, FDX") },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
                if (resolvedAirlines.isNotEmpty()) {
                    Text(
                        "Matches callsigns starting ${resolvedAirlines.sorted().joinToString(", ")}",
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            Column {
                Label("Wind alerts")
                Text(
                    "Set these to your station's limits. Advisory only.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Stepper("Caution from gusts of", settings.gustCautionKt, 15..60) { v -> onChange { it.copy(gustCautionKt = v) } }
                Stepper("High wind from", settings.highWindKt, 20..80) { v -> onChange { it.copy(highWindKt = v) } }
            }

            Column {
                Label("Heat strain")
                Text(
                    "Your age sets the heart-rate limit for heat-strain warnings on the Shift tab: 180 minus your age, " +
                        "sustained for 5 minutes in the heat (NIOSH). Without it, ${HeatStrain.limitBpm(null)} bpm is used. " +
                        "Kept on this device only.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Stepper(
                    "Age", settings.age, 0..80, unit = "",
                    shown = if (settings.age == 0) "Not set" else "${settings.age}",
                    // 0 is "not set": step in and out of it from the adult range.
                    step = { v, up -> if (up) (if (v == 0) 40 else v + 1) else (if (v <= 16) 0 else v - 1) },
                ) { v -> onChange { it.copy(age = v) } }
            }

            Column {
                Label("Theme")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    ThemeMode.entries.forEachIndexed { i, mode ->
                        SegmentedButton(
                            selected = settings.theme == mode,
                            onClick = { onChange { it.copy(theme = mode) } },
                            shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text(mode.label, maxLines = 1) }
                    }
                }
                Text(
                    themeNote(settings.theme, airport),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Label("Keep screen on")
                    Text(
                        "For a tablet on a desk or in a vehicle cradle. Uses more battery.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = settings.keepScreenOn, onCheckedChange = { v -> onChange { it.copy(keepScreenOn = v) } })
            }

            HorizontalDivider()
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Label("About the data")
                About(
                    "Weather (METAR, TAF), NOTAMs and observed flights come from the aviation data " +
                        "warehouse on MotherDuck, through the same API as the web dashboard " +
                        "(${BuildConfig.API_BASE.removePrefix("https://")}). Tables refresh every 10 minutes, live positions every 2.",
                )
                About(
                    "Live positions: OpenSky Network or adsb.lol (ODbL). Map tiles: Esri, HERE, Garmin, " +
                        "© OpenStreetMap contributors.",
                )
                About(
                    "There are no airline schedules. Times, directions and delays are estimated from live " +
                        "ADS-B and each flight's usual time over the last 30 days. For situational awareness " +
                        "only: your ops control, ATC and local procedures always take precedence.",
                )
                About("Version ${BuildConfig.VERSION_NAME}")
            }
        }
    }
}

/** What the selected theme does; for Sunset, when it next switches at the airport. */
private fun themeNote(mode: ThemeMode, airport: Airport?): String {
    if (mode == ThemeMode.SYSTEM) return "Auto follows your phone's dark theme setting. Sunset goes dark at sunset at the airport instead."
    if (mode != ThemeMode.SUNSET) return "Dark is easier on the eyes on night shifts."
    airport ?: return "Dark from sunset to sunrise at the selected airport."
    val now = System.currentTimeMillis()
    val zone = runCatching { ZoneId.of(airport.timezone) }.getOrDefault(ZoneId.systemDefault())
    val dark = Solar.isDark(airport.lat, airport.lon, now)
    val next = Solar.nextChange(airport.lat, airport.lon, now)
    return "Dark from sunset to sunrise at ${airport.iata}. " + when {
        next == null && dark -> "The sun stays down there for now."
        next == null -> "The sun stays up there for now."
        dark -> "Sunrise ${Fmt.hm(next, zone)} local."
        else -> "Sunset ${Fmt.hm(next, zone)} local."
    }
}

@Composable
private fun Label(text: String) = Text(text, style = MaterialTheme.typography.titleMedium)

@Composable
private fun About(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Stepper(
    label: String,
    value: Int,
    range: IntRange,
    unit: String = " kt",
    shown: String = "$value$unit",
    step: (value: Int, up: Boolean) -> Int = { v, up -> if (up) v + 1 else v - 1 },
    onValue: (Int) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        FilledTonalIconButton(onClick = { onValue(step(value, false).coerceIn(range)) }, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.Remove, contentDescription = "Decrease $label")
        }
        Text(shown, style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(80.dp).padding(horizontal = 8.dp))
        FilledTonalIconButton(onClick = { onValue(step(value, true).coerceIn(range)) }, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.Add, contentDescription = "Increase $label")
        }
    }
}
