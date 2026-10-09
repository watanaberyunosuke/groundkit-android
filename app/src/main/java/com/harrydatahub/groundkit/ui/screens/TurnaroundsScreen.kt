package com.harrydatahub.groundkit.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DoorFront
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Luggage
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.PowerOff
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.UTurnLeft
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.harrydatahub.groundkit.domain.AirportSnapshot
import com.harrydatahub.groundkit.domain.Turnaround
import com.harrydatahub.groundkit.domain.TurnaroundStep
import com.harrydatahub.groundkit.domain.TurnaroundStep.Phase
import com.harrydatahub.groundkit.domain.Turnarounds
import com.harrydatahub.groundkit.ui.Fmt
import com.harrydatahub.groundkit.ui.TurnaroundViewModel
import com.harrydatahub.groundkit.ui.components.EmptyState
import com.harrydatahub.groundkit.ui.components.SectionCard
import com.harrydatahub.groundkit.ui.theme.FlightCodeStyle
import com.harrydatahub.groundkit.ui.theme.LocalStatusColors
import com.harrydatahub.groundkit.ui.theme.readableContent
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/** The Turns tab: the list of turnarounds at this airport, or the one that is open. */
@Composable
fun TurnaroundsScreen(
    vm: TurnaroundViewModel,
    all: List<Turnaround>,
    snap: AirportSnapshot,
    now: Long,
    openId: String?,
    onOpen: (String?) -> Unit,
) {
    val open = openId?.let { id -> all.firstOrNull { it.id == id } }
    if (open != null) {
        BackHandler { onOpen(null) }
        TurnaroundDetail(vm, open, snap.zone, now, onBack = { onOpen(null) })
    } else {
        TurnaroundList(vm, all.filter { it.airportIcao == snap.airport.icao }, snap, now, onOpen)
    }
}

@Composable
private fun TurnaroundList(vm: TurnaroundViewModel, here: List<Turnaround>, snap: AirportSnapshot, now: Long, onOpen: (String) -> Unit) {
    var showClosed by rememberSaveable { mutableStateOf(false) }
    var creating by rememberSaveable { mutableStateOf(false) }
    val shown = here.filter { it.isClosed == showClosed }
    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                listOf(false to "Active", true to "Completed").forEachIndexed { i, (closed, label) ->
                    SegmentedButton(
                        selected = showClosed == closed,
                        onClick = { showClosed = closed },
                        shape = SegmentedButtonDefaults.itemShape(i, 2),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(label) }
                }
            }
        }
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!showClosed) {
                item { BigButton("New turnaround", Icons.Filled.Add, MaterialTheme.colorScheme.primary) { creating = true } }
            }
            if (shown.isEmpty()) {
                item {
                    EmptyState(
                        if (showClosed) "Closed turnarounds at ${snap.airport.iata} appear here."
                        else "No active turnarounds. Start one from a flight on the Flights tab, or add one.",
                    )
                }
            }
            // Swipe a row away to delete it, as on iOS.
            items(shown, key = { it.id }) { t ->
                SwipeToDismissBox(
                    rememberSwipeToDismissBoxState(),
                    backgroundContent = { DeleteBackground() },
                    enableDismissFromStartToEnd = false,
                    onDismiss = { vm.delete(t.id) },
                ) { TurnaroundRow(t, snap.zone, now) { onOpen(t.id) } }
            }
            item {
                Text(
                    "Turnarounds are kept on this device. Local procedures and the load controller take precedence.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (creating) {
        NewTurnaroundDialog(snap, onDismiss = { creating = false }) { t ->
            creating = false
            onOpen(vm.create(t))
        }
    }
}

@Composable
private fun DeleteBackground() {
    val red = LocalStatusColors.current.red
    Row(
        Modifier.fillMaxSize().background(red, RoundedCornerShape(12.dp)).padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Delete", color = red.readableContent(), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Filled.Delete, contentDescription = null, tint = red.readableContent())
    }
}

@Composable
private fun TurnaroundRow(t: Turnaround, zone: ZoneId, now: Long, onClick: () -> Unit) {
    val s = LocalStatusColors.current
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t.label, style = FlightCodeStyle)
                if (t.stand.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    StandBadge(t.stand)
                }
                if (t.hasDangerousGoods) {
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Filled.Warning, contentDescription = "Dangerous goods", tint = s.amber)
                }
                Spacer(Modifier.weight(1f))
                if (!t.isClosed && t.doneAt(TurnaroundStep.CHOCKS_OFF) == null) {
                    t.targetOffBlock?.let { OffBlockCountdown(it, zone, now, compact = true) }
                }
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { t.progress },
                modifier = Modifier.fillMaxWidth(),
                color = if (t.progress >= 1f) s.green else MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                t.closedAt?.let { "Closed ${Fmt.hm(it, zone)}" } ?: t.nextStep?.let { "Next: ${it.title}" } ?: "All steps done",
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StandBadge(stand: String) {
    // Yellow and black, like apron stand markings.
    Text(
        "Stand $stand",
        modifier = Modifier
            .background(Color(0xFFFFD600), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        color = Color.Black, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
    )
}

/** Minutes to the target off-block time: amber in the last 10, red once it has passed. */
@Composable
private fun OffBlockCountdown(target: Long, zone: ZoneId, now: Long, compact: Boolean = false) {
    val s = LocalStatusColors.current
    val minutes = Math.floorDiv(target - now, 60_000L).toInt()
    val late = minutes < 0
    Column(
        horizontalAlignment = Alignment.End,
        modifier = Modifier.semantics(mergeDescendants = true) {
            contentDescription = if (late) "${-minutes} minutes past off-block time" else "$minutes minutes to off-block"
        },
    ) {
        Text(
            if (late) "+${-minutes} min" else "$minutes min",
            fontSize = if (compact) 22.sp else 36.sp, fontWeight = FontWeight.Bold,
            color = when {
                late -> s.red
                minutes <= 10 -> s.amber
                else -> MaterialTheme.colorScheme.onSurface
            },
        )
        Text("off-block ${Fmt.hm(target, zone)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TurnaroundDetail(vm: TurnaroundViewModel, t: Turnaround, zone: ZoneId, now: Long, onBack: () -> Unit) {
    val s = LocalStatusColors.current
    val haptics = LocalHapticFeedback.current
    // Taps in the first moment after opening are the tail of the tap that opened it (or a
    // double tap with gloves), not a step being done.
    val openedAt = remember(t.id) { System.currentTimeMillis() }
    var undoStep by remember { mutableStateOf<TurnaroundStep?>(null) }
    var confirmClose by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val next = t.nextStep

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.Top) {
                IconButton(onClick = onBack, modifier = Modifier.size(52.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "All turnarounds")
                }
                Column(Modifier.weight(1f)) {
                    Text(t.label, style = FlightCodeStyle.copy(fontSize = FlightCodeStyle.fontSize * 1.3f))
                    val route = listOfNotNull(t.origin?.let { "From $it" }, t.destination?.let { "To $it" }).joinToString(" · ")
                    if (route.isNotEmpty()) Text(route, style = MaterialTheme.typography.titleMedium)
                    t.onBlocksAt?.let {
                        Text(
                            "On blocks ${Fmt.hm(it, zone)}, ${(now - it) / 60_000} min on stand",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (!t.isClosed && t.doneAt(TurnaroundStep.CHOCKS_OFF) == null) {
                    t.targetOffBlock?.let { OffBlockCountdown(it, zone, now) }
                }
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { t.progress },
                modifier = Modifier.fillMaxWidth().height(8.dp),
                color = if (t.progress >= 1f) s.green else MaterialTheme.colorScheme.primary,
            )
        }
        Phase.entries.forEach { phase ->
            val steps = t.steps.filter { it.phase == phase }
            item(key = phase.name) {
                Text(phase.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    steps.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            pair.forEach { step ->
                                StepButton(step, t.doneAt(step), isNext = step == next, zone, Modifier.weight(1f)) {
                                    when {
                                        t.doneAt(step) != null -> undoStep = step
                                        System.currentTimeMillis() - openedAt > TAP_GUARD_MS -> {
                                            vm.markDone(t.id, step)
                                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                        }
                                    }
                                }
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        item { Counts(vm, t) }
        item { Details(vm, t) }
        item {
            if (t.isClosed) {
                OutlinedButton(onClick = { vm.reopen(t.id) }, modifier = Modifier.fillMaxWidth().height(60.dp)) {
                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Reopen turnaround", style = MaterialTheme.typography.titleMedium)
                }
            } else {
                BigButton("Close turnaround", Icons.Filled.Flag, s.green) { confirmClose = true }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Icon(Icons.Filled.Delete, contentDescription = null, tint = s.red)
                Spacer(Modifier.width(8.dp))
                Text("Delete turnaround", color = s.red)
            }
        }
    }

    undoStep?.let { step ->
        Confirm("Undo ${step.title}?", null, "Undo", s.red, onDismiss = { undoStep = null }) {
            vm.undo(t.id, step)
            undoStep = null
        }
    }
    if (confirmClose) {
        Confirm("Close this turnaround?", next?.let { "${it.title} and later steps are not marked done." }, "Close turnaround", s.green, onDismiss = { confirmClose = false }) {
            vm.close(t.id)
            confirmClose = false
        }
    }
    if (confirmDelete) {
        Confirm("Delete this turnaround?", "Its steps, counts and notes are removed from this device.", "Delete", s.red, onDismiss = { confirmDelete = false }) {
            confirmDelete = false
            onBack()
            vm.delete(t.id)
        }
    }
}

/** One step: tap to stamp it done now; tap again to undo, after a confirm. */
@Composable
private fun StepButton(step: TurnaroundStep, doneAt: Long?, isNext: Boolean, zone: ZoneId, modifier: Modifier, onClick: () -> Unit) {
    val s = LocalStatusColors.current
    val done = doneAt != null
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 84.dp).semantics {
            stateDescription = doneAt?.let { "Done at ${Fmt.hm(it, zone)}" } ?: "Not done"
        },
        shape = RoundedCornerShape(14.dp),
        color = when {
            done -> s.greenContainer
            isNext -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surfaceContainerHigh
        },
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = if (isNext) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (done) Icons.Filled.CheckCircle else step.icon, contentDescription = null,
                tint = if (done) s.green else LocalContentColor.current, modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(step.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    doneAt?.let { Fmt.hm(it, zone) } ?: if (isNext) "Next" else "",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

private val TurnaroundStep.icon: ImageVector
    get() = when (this) {
        TurnaroundStep.CHOCKS_ON, TurnaroundStep.CHOCKS_OFF -> Icons.Filled.StopCircle
        TurnaroundStep.CONES_PLACED -> Icons.Filled.Construction
        TurnaroundStep.GPU_CONNECTED -> Icons.Filled.Power
        TurnaroundStep.GPU_REMOVED -> Icons.Filled.PowerOff
        TurnaroundStep.HOLDS_OPEN -> Icons.Filled.MeetingRoom
        TurnaroundStep.HOLDS_CLOSED -> Icons.Filled.DoorFront
        TurnaroundStep.BAGS_OFFLOADED, TurnaroundStep.BAGS_LOADED -> Icons.Filled.Luggage
        TurnaroundStep.CARGO_OFFLOADED, TurnaroundStep.CARGO_LOADED -> Icons.Filled.Inventory2
        TurnaroundStep.FUELLED -> Icons.Filled.LocalGasStation
        TurnaroundStep.CATERED -> Icons.Filled.Restaurant
        TurnaroundStep.CLEANED -> Icons.Filled.CleaningServices
        TurnaroundStep.WATERED -> Icons.Filled.WaterDrop
        TurnaroundStep.NOTOC -> Icons.Filled.Warning
        TurnaroundStep.LOADSHEET -> Icons.Filled.Description
        TurnaroundStep.PUSHBACK -> Icons.Filled.UTurnLeft
    }

@Composable
private fun Counts(vm: TurnaroundViewModel, t: Turnaround) {
    SectionCard("Counts") {
        CountRow("Bags off", t.bagsOffloaded) { n -> vm.edit(t.id) { it.copy(bagsOffloaded = n) } }
        CountRow("Bags loaded", t.bagsLoaded) { n -> vm.edit(t.id) { it.copy(bagsLoaded = n) } }
        CountRow("ULDs off", t.uldsOffloaded) { n -> vm.edit(t.id) { it.copy(uldsOffloaded = n) } }
        CountRow("ULDs loaded", t.uldsLoaded) { n -> vm.edit(t.id) { it.copy(uldsLoaded = n) } }
    }
}

@Composable
private fun CountRow(title: String, value: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        OutlinedIconButton(onClick = { onChange((value - 1).coerceAtLeast(0)) }, modifier = Modifier.size(52.dp)) {
            Icon(Icons.Filled.Remove, contentDescription = "Decrease $title")
        }
        Text(
            "$value", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
            modifier = Modifier.width(64.dp), textAlign = TextAlign.Center,
        )
        FilledIconButton(onClick = { onChange(value + 1) }, modifier = Modifier.size(52.dp)) {
            Icon(Icons.Filled.Add, contentDescription = "Increase $title")
        }
    }
}

@Composable
private fun Details(vm: TurnaroundViewModel, t: Turnaround) {
    // Edited locally and saved on each change, so typing is not held up by the store.
    var stand by rememberSaveable(t.id) { mutableStateOf(t.stand) }
    var registration by rememberSaveable(t.id) { mutableStateOf(t.registration) }
    var notes by rememberSaveable(t.id) { mutableStateOf(t.notes) }
    val caps = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false)
    SectionCard("Details") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                stand, { v -> stand = v.uppercase(); vm.edit(t.id) { it.copy(stand = stand.trim()) } },
                label = { Text("Stand") }, singleLine = true, keyboardOptions = caps, modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                registration, { v -> registration = v.uppercase(); vm.edit(t.id) { it.copy(registration = registration.trim()) } },
                label = { Text("Registration") }, singleLine = true, keyboardOptions = caps, modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))
        LabeledSwitch("Dangerous goods on board", t.hasDangerousGoods) { on -> vm.edit(t.id) { it.copy(hasDangerousGoods = on) } }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            notes, { v -> notes = v; vm.edit(t.id) { it.copy(notes = v) } },
            label = { Text("Notes") }, placeholder = { Text("Damage, delays, special loads…") },
            minLines = 3, modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun LabeledSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewTurnaroundDialog(snap: AirportSnapshot, onDismiss: () -> Unit, onCreate: (Turnaround) -> Unit) {
    val zone = snap.zone
    var flight by rememberSaveable { mutableStateOf("") }
    var stand by rememberSaveable { mutableStateOf("") }
    var registration by rememberSaveable { mutableStateOf("") }
    var origin by rememberSaveable { mutableStateOf("") }
    var destination by rememberSaveable { mutableStateOf("") }
    var hasTarget by rememberSaveable { mutableStateOf(true) }
    var dangerousGoods by rememberSaveable { mutableStateOf(false) }
    // 45 minutes from now, as on iOS.
    val start = remember { Instant.now().plusSeconds(45 * 60).atZone(zone).toLocalTime() }
    val time = rememberTimePickerState(start.hour, start.minute, is24Hour = true)
    val caps = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New turnaround") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(flight, { flight = it.uppercase() }, label = { Text("Flight or callsign (QF1 / QFA1)") }, singleLine = true, keyboardOptions = caps, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(stand, { stand = it.uppercase() }, label = { Text("Stand") }, singleLine = true, keyboardOptions = caps, modifier = Modifier.weight(1f))
                    OutlinedTextField(registration, { registration = it.uppercase() }, label = { Text("Registration") }, singleLine = true, keyboardOptions = caps, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(origin, { origin = it.uppercase() }, label = { Text("From") }, singleLine = true, keyboardOptions = caps, modifier = Modifier.weight(1f))
                    OutlinedTextField(destination, { destination = it.uppercase() }, label = { Text("To") }, singleLine = true, keyboardOptions = caps, modifier = Modifier.weight(1f))
                }
                LabeledSwitch("Target off-block time", hasTarget) { hasTarget = it }
                if (hasTarget) TimeInput(time)
                LabeledSwitch("Dangerous goods on board", dangerousGoods) { dangerousGoods = it }
                Text(
                    "Times are ${snap.airport.iata} local. Dangerous goods adds the NOTOC step.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = flight.isNotBlank(),
                modifier = Modifier.heightIn(min = 48.dp),
                onClick = {
                    val now = System.currentTimeMillis()
                    val code = flight.trim()
                    onCreate(
                        Turnaround(
                            id = UUID.randomUUID().toString(),
                            airportIcao = snap.airport.icao,
                            callsign = code,
                            flightIata = code,
                            origin = origin.trim().ifEmpty { null },
                            destination = destination.trim().ifEmpty { null },
                            stand = stand.trim(),
                            registration = registration.trim(),
                            createdAt = now,
                            targetOffBlock = if (hasTarget) Turnarounds.offBlockAt(LocalTime.of(time.hour, time.minute), zone, now) else null,
                            hasDangerousGoods = dangerousGoods,
                        ),
                    )
                },
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") } },
    )
}

@Composable
private fun Confirm(title: String, text: String?, action: String, color: Color, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = text?.let { { Text(it) } },
        confirmButton = { TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = 48.dp)) { Text(action, color = color) } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") } },
    )
}

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

private const val TAP_GUARD_MS = 800L
