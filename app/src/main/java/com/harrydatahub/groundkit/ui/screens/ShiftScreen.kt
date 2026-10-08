package com.harrydatahub.groundkit.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Surface
import androidx.compose.ui.semantics.semantics
import com.harrydatahub.groundkit.domain.Fatigue
import com.harrydatahub.groundkit.domain.Finding
import com.harrydatahub.groundkit.domain.HeatStrain
import com.harrydatahub.groundkit.domain.Level
import com.harrydatahub.groundkit.domain.WorkSpan
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.harrydatahub.groundkit.data.CrewRecords
import com.harrydatahub.groundkit.data.HealthAvailability
import com.harrydatahub.groundkit.data.HealthService
import com.harrydatahub.groundkit.data.Shift
import com.harrydatahub.groundkit.domain.AirportSnapshot
import com.harrydatahub.groundkit.domain.HeatStress
import com.harrydatahub.groundkit.domain.ShiftAdvice
import com.harrydatahub.groundkit.ui.Fmt
import com.harrydatahub.groundkit.ui.HealthState
import com.harrydatahub.groundkit.ui.ShiftViewModel
import com.harrydatahub.groundkit.ui.components.EmptyState
import com.harrydatahub.groundkit.ui.components.InfoTile
import com.harrydatahub.groundkit.ui.components.SectionCard
import com.harrydatahub.groundkit.ui.theme.LocalStatusColors
import com.harrydatahub.groundkit.ui.theme.readableContent
import java.time.ZoneId
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * The worker's own shift, as the iOS app's Shift tab: time on shift, water against a
 * target that rises with the heat, activity and heart rate from Health Connect, and
 * handover notes for the next crew.
 */
@Composable
fun ShiftScreen(
    vm: ShiftViewModel,
    records: CrewRecords,
    health: HealthState,
    snap: AirportSnapshot,
    now: Long,
    age: Int,
) {
    val context = LocalContext.current
    val permissions = rememberLauncherForActivityResult(vm.health.permissionContract()) { vm.onPermissionsResult() }
    // Health Connect is read only while this screen is showing.
    LifecycleResumeEffect(vm) {
        vm.onScreenVisible()
        onPauseOrDispose { vm.onScreenHidden() }
    }
    var confirmEnd by rememberSaveable { mutableStateOf(false) }
    val shift = records.activeShift
    val c = snap.conditions
    val feelsLike = HeatStress.feelsLikeC(c?.tempC, c?.dewpointC, c?.windSpeedKt)
    val fatigue = Fatigue.assess(
        health.sleep, records.shifts.map { WorkSpan(it.startedAt, it.endedAt) }, shift?.startedAt, now,
    )
    val strain = if (shift != null) HeatStrain.assess(health.recentHeartRate, feelsLike, age.takeIf { it > 0 }, now) else null
    val lastEnded = records.shifts.firstOrNull { !it.isActive }
    val connect = { permissions.launch(HealthService.PERMISSIONS) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (shift == null) {
            // The shift just finished, until the next one starts.
            if (lastEnded?.summary != null && now - (lastEnded.endedAt ?: 0) < SUMMARY_SHOWN_MS) {
                item { SummaryCard(lastEnded, snap.zone) }
            }
            item { FatigueCard(fatigue, health, onDuty = false, onConnect = connect) }
            item {
                SectionCard("Not on shift") {
                    Text(
                        "Start a shift to track time on the ramp, water, and steps and heart rate from Health Connect.",
                        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    BigButton("Start shift at ${snap.airport.iata}", Icons.Filled.PlayArrow, LocalStatusColors.current.green) {
                        vm.startShift(snap.airport.icao)
                    }
                }
            }
        } else {
            strain?.let { item { FindingBanner(it) } }
            item { ShiftClock(shift, snap.zone, now, onBreak = vm::logBreak) }
            item { FatigueCard(fatigue, health, onDuty = true, onConnect = connect) }
            item { WaterCard(shift, now, feelsLike, health.stats.waterMl, onLog = vm::logWater) }
            item {
                HealthCard(
                    health,
                    onConnect = connect,
                    onInstall = { runCatching { context.startActivity(vm.health.providerIntent()) } },
                )
            }
            item { HearingReminder() }
            item {
                OutlinedButton(
                    onClick = { confirmEnd = true },
                    modifier = Modifier.fillMaxWidth().height(60.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = LocalStatusColors.current.red),
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("End shift", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        item { HandoverCard(records, snap, now, onAdd = vm::addNote, onResolve = vm::resolveNote) }
        val ended = records.shifts.filter { !it.isActive }.take(7)
        if (ended.isNotEmpty()) item { RecentShifts(ended) }
        item {
            Text(
                "Guidance only, not medical advice. Follow your employer's heat, cold and noise procedures. " +
                    "Shifts and notes are kept on this device.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (confirmEnd) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            title = { Text("End your shift?") },
            text = { Text("A summary of the shift is kept on this device.") },
            confirmButton = {
                TextButton(onClick = { vm.endShift(feelsLike); confirmEnd = false }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("End shift", color = LocalStatusColors.current.red)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmEnd = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Keep going") }
            },
        )
    }
}

@Composable
private fun ShiftClock(shift: Shift, zone: ZoneId, now: Long, onBreak: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val minutes = shift.durationMs(now) / 60_000
    val since = shift.workingSince()
    val amber = LocalStatusColors.current.amber
    SectionCard("On shift since ${Fmt.hm(shift.startedAt, zone)}") {
        Text(
            Fmt.duration(minutes.toInt()),
            style = MaterialTheme.typography.displaySmall.copy(fontSize = 48.sp, fontWeight = FontWeight.ExtraBold),
        )
        Text(
            (if (shift.breaks.isEmpty()) "No break yet" else "Last break ${Fmt.hm(since, zone)}") +
                " · working ${Fmt.duration(((now - since) / 60_000).toInt())}",
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (ShiftAdvice.breakDue(since, now)) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.LocalCafe, contentDescription = null, tint = amber)
                Spacer(Modifier.width(8.dp))
                Text("Time for a break and a drink?", style = MaterialTheme.typography.titleMedium, color = amber)
            }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = {
                onBreak()
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Icon(Icons.Filled.LocalCafe, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Log a break now", style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** A fatigue or heat-strain finding, coloured by level. */
@Composable
private fun FindingBanner(f: Finding) {
    val s = LocalStatusColors.current
    val tint = if (f.level == Level.WARNING) s.red else s.amber
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = tint.copy(alpha = 0.14f),
        border = BorderStroke(2.dp, tint),
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Icon(
                if (f.level == Level.WARNING) Icons.Filled.Warning else Icons.Filled.Info,
                contentDescription = if (f.level == Level.WARNING) "Warning" else "Caution",
                tint = tint, modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(f.title, style = MaterialTheme.typography.titleMedium, color = tint)
                Text(f.detail, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/**
 * Sleep before duty (from Health Connect), rest since the last shift and hours this week,
 * with any findings. Before a shift it is a fit-for-duty check.
 */
@Composable
private fun FatigueCard(f: Fatigue.Summary, health: HealthState, onDuty: Boolean, onConnect: () -> Unit) {
    val sleepAllowed = HealthService.READ_SLEEP in health.granted
    SectionCard("Fatigue", subtitle = if (onDuty) "Sleep in the 24 and 48 h before this shift" else "Before you start: sleep in the last 24 and 48 h") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InfoTile("Sleep, 24 h", f.sleep24hMs?.let(::hm) ?: "–", Modifier.weight(1f))
            InfoTile("Sleep, 48 h", f.sleep48hMs?.let(::hm) ?: "–", Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InfoTile("Awake for", f.awakeMs?.let(::hm) ?: "–", Modifier.weight(1f))
            InfoTile(if (onDuty) "Rest before shift" else "Rest since shift", f.restMs?.let(::hm) ?: "–", Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "${hm(f.weekMs)} worked in the last 7 days.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (f.findings.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { f.findings.forEach { FindingBanner(it) } }
        } else if (f.sleep24hMs != null) {
            Spacer(Modifier.height(6.dp))
            Text("Sleep and rest look fine.", style = MaterialTheme.typography.bodyMedium, color = LocalStatusColors.current.green)
        }
        if (sleepAllowed && f.sleep24hMs == null) {
            Spacer(Modifier.height(6.dp))
            Text(
                "No sleep recorded in Health Connect in the last 48 h. A watch or sleep app that saves sleep there turns on the sleep checks.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (health.availability == HealthAvailability.AVAILABLE && !sleepAllowed) {
            TextButton(onClick = onConnect, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Allow sleep from Health Connect for the sleep checks")
            }
        }
    }
}

/** How the last shift went, from the summary kept when it ended. */
@Composable
private fun SummaryCard(shift: Shift, zone: ZoneId) {
    val m = shift.summary ?: return
    val end = shift.endedAt ?: return
    SectionCard("Shift summary", subtitle = "${Fmt.hm(shift.startedAt, zone)}–${Fmt.hm(end, zone)} at ${shift.airportIcao}") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InfoTile("On shift", Fmt.duration((shift.durationMs(end) / 60_000).toInt()), Modifier.weight(1f))
            InfoTile(
                "Breaks", "${m.breaks}" + " · longest stretch ${Fmt.duration((m.longestWithoutBreakMs / 60_000).toInt())}",
                Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InfoTile(
                "Water", "${m.waterMl.roundToInt()}" + (m.waterTargetMl?.let { " of ${(ceil(it / 50) * 50).toInt()}" } ?: "") + " ml",
                Modifier.weight(1f),
            )
            InfoTile(
                "Steps",
                m.steps?.let { Fmt.thousands(it) + (m.distanceKm?.let { km -> " · %.1f km".format(java.util.Locale.ROOT, km) } ?: "") } ?: "–",
                Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InfoTile("Heart rate, avg · peak", heartRates(m.heartRateAverage, m.heartRateMax), Modifier.weight(1f))
            InfoTile("Active energy", m.activeKcal?.let { "${it.roundToInt()} kcal" } ?: "–", Modifier.weight(1f))
        }
        val target = m.waterTargetMl
        val notes = buildList {
            if (target != null && m.waterMl < target * 0.75) add("You drank under three quarters of the water target. Rehydrate before your next shift.")
            if (m.longestWithoutBreakMs > ShiftAdvice.BREAK_EVERY_MS + 30 * 60_000L) add("You worked over 2½ hours without a logged break.")
            m.sleep24hMs?.let { if (it < Fatigue.MIN_SLEEP_24H_MS) add("You started on under 5 h sleep. Aim for a full sleep before the next one.") }
        }
        notes.forEach {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = LocalStatusColors.current.amber)
        }
    }
}

private fun hm(ms: Long) = Fmt.duration((ms / 60_000).toInt())

private fun heartRates(avg: Long?, max: Long?) = when {
    avg == null && max == null -> "–"
    else -> "${avg ?: "–"} · ${max ?: "–"} bpm"
}

@Composable
private fun WaterCard(shift: Shift, now: Long, feelsLike: Double?, healthMl: Double?, onLog: (Double) -> Unit) {
    val haptics = LocalHapticFeedback.current
    val hours = shift.durationMs(now) / 3_600_000.0
    val perHour = ShiftAdvice.waterPerHourMl(feelsLike)
    val target = ShiftAdvice.waterTargetMl(feelsLike, hours)
    // Water logged here is also saved to Health Connect; count it once.
    val drunk = maxOf(shift.waterMl, healthMl ?: 0.0)
    val blue = LocalStatusColors.current.arrivalPath
    SectionCard("Water") {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${drunk.roundToInt()} ml", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.width(8.dp))
            Text(
                "of about ${(ceil(target / 50) * 50).toInt()} ml so far",
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { (drunk / target).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(10.dp),
            color = blue,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            (feelsLike?.let { "Feels like ${it.roundToInt()}°C: " } ?: "") + "aim for about ${perHour.toInt()} ml an hour.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(250.0, 500.0).forEach { ml ->
                Button(
                    onClick = {
                        onLog(ml)
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    },
                    modifier = Modifier.weight(1f).height(60.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = blue, contentColor = blue.readableContent()),
                ) {
                    Icon(Icons.Filled.WaterDrop, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("+${ml.toInt()} ml", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun HealthCard(health: HealthState, onConnect: () -> Unit, onInstall: () -> Unit) {
    val pink = Color(0xFFDB2777)
    when {
        health.availability == HealthAvailability.UNAVAILABLE -> SectionCard("Health Connect") {
            EmptyState("Health Connect isn't available on this device.")
        }
        health.availability == HealthAvailability.NEEDS_PROVIDER -> SectionCard("Health Connect") {
            Text(
                "Install or update Health Connect to see steps and heart rate here.",
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            BigButton("Get Health Connect", Icons.Filled.Favorite, pink, onInstall)
        }
        !health.connected -> SectionCard("Connect Health Connect") {
            Text(
                "Allow GroundKit to read steps, distance, active energy, heart rate, sleep and water, and save the water you log.",
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            BigButton("Connect Health Connect", Icons.Filled.Favorite, pink, onConnect)
        }
        else -> SectionCard("This shift", subtitle = "From Health Connect, updated every 2 minutes") {
            val s = health.stats
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoTile(
                    "Steps",
                    s.steps?.let { Fmt.thousands(it) + (s.distanceKm?.let { km -> " · %.1f km".format(java.util.Locale.ROOT, km) } ?: "") } ?: "–",
                    Modifier.weight(1f),
                )
                InfoTile("Active energy", s.activeKcal?.let { "${it.roundToInt()} kcal" } ?: "–", Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoTile("Heart rate", s.heartRateLatest?.let { "$it bpm" } ?: "–", Modifier.weight(1f))
                InfoTile("Average · peak", heartRates(s.heartRateAverage, s.heartRateMax), Modifier.weight(1f))
            }
            if (health.missing.isNotEmpty()) {
                TextButton(onClick = onConnect, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Some data types are off. Review access")
                }
            }
            health.error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Health Connect has no noise data, so a standing reminder replaces the iOS noise tile. */
@Composable
private fun HearingReminder() {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
        Icon(Icons.Filled.Hearing, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Text(
            "Engines and APUs run well above ${ShiftAdvice.HEARING_PROTECTION_DB} dB: keep hearing protection on near running aircraft.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HandoverCard(
    records: CrewRecords,
    snap: AirportSnapshot,
    now: Long,
    onAdd: (String, String, Boolean) -> Unit,
    onResolve: (String) -> Unit,
) {
    var draft by rememberSaveable { mutableStateOf("") }
    var important by rememberSaveable { mutableStateOf(false) }
    val open = records.notes.filter { it.airportIcao == snap.airport.icao && it.resolvedAt == null }
    val red = LocalStatusColors.current.red
    SectionCard("Handover notes", subtitle = "For the next crew at ${snap.airport.iata}") {
        if (open.isEmpty()) EmptyState("No open notes for ${snap.airport.iata}.")
        open.forEach { note ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (note.isImportant) Icons.Filled.Error else Icons.Filled.Circle,
                    contentDescription = if (note.isImportant) "Important" else null,
                    tint = if (note.isImportant) red else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(if (note.isImportant) 24.dp else 10.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(note.text, style = MaterialTheme.typography.bodyLarge)
                    Text(Fmt.age(note.createdAt, now), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(onClick = { onResolve(note.id) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Done") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = draft, onValueChange = { draft = it },
            placeholder = { Text("Equipment faults, closed stands, things to watch…") },
            minLines = 2, maxLines = 5,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = important, onCheckedChange = { important = it })
            Spacer(Modifier.width(8.dp))
            Text("Important", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Button(
                onClick = {
                    onAdd(snap.airport.icao, draft, important)
                    draft = ""
                    important = false
                },
                enabled = draft.isNotBlank(),
                modifier = Modifier.heightIn(min = 52.dp),
            ) { Text("Add note") }
        }
    }
}

@Composable
private fun RecentShifts(ended: List<Shift>) {
    val zone = ZoneId.systemDefault()
    SectionCard("Recent shifts") {
        ended.forEach { s ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text(Fmt.date(s.startedAt, zone), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.width(8.dp))
                Text(s.airportIcao, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(
                    Fmt.duration((s.durationMs(s.endedAt ?: s.startedAt) / 60_000).toInt()) +
                        (if (s.waterMl > 0) " · ${s.waterMl.roundToInt()} ml" else "") +
                        (s.summary?.steps?.let { " · ${Fmt.thousands(it)} steps" } ?: ""),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
        }
    }
}

private const val SUMMARY_SHOWN_MS = 12 * 3_600_000L

@Composable
private fun BigButton(label: String, icon: ImageVector, tint: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(60.dp),
        colors = ButtonDefaults.buttonColors(containerColor = tint, contentColor = tint.readableContent()),
    ) {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}
