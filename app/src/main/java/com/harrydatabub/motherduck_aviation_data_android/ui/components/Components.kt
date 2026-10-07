package com.harrydatabub.motherduck_aviation_data_android.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Thunderstorm
import androidx.compose.material.icons.filled.Umbrella
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.harrydatabub.motherduck_aviation_data_android.data.Conditions
import com.harrydatabub.motherduck_aviation_data_android.data.FlightFilter
import com.harrydatabub.motherduck_aviation_data_android.domain.AlertKind
import com.harrydatabub.motherduck_aviation_data_android.domain.AlertLevel
import com.harrydatabub.motherduck_aviation_data_android.domain.Dir
import com.harrydatabub.motherduck_aviation_data_android.domain.RampAlert
import com.harrydatabub.motherduck_aviation_data_android.ui.FlightItem
import com.harrydatabub.motherduck_aviation_data_android.ui.Fmt
import com.harrydatabub.motherduck_aviation_data_android.ui.Tone
import com.harrydatabub.motherduck_aviation_data_android.ui.theme.BoardTimeStyle
import com.harrydatabub.motherduck_aviation_data_android.ui.theme.FlightCodeStyle
import com.harrydatabub.motherduck_aviation_data_android.ui.theme.LocalStatusColors
import com.harrydatabub.motherduck_aviation_data_android.ui.theme.MonoStyle
import kotlinx.coroutines.delay
import java.time.ZoneId
import kotlin.math.cos
import kotlin.math.sin

/** The current time, ticking every `periodMs`. */
@Composable
fun rememberNow(periodMs: Long = 1_000): State<Long> = produceState(System.currentTimeMillis()) {
    while (true) {
        delay(periodMs - System.currentTimeMillis() % periodMs)
        value = System.currentTimeMillis()
    }
}

@Composable
fun toneColor(tone: Tone): Color {
    val s = LocalStatusColors.current
    return when (tone) {
        Tone.GREEN -> s.green
        Tone.AMBER -> s.amber
        Tone.RED -> s.red
        Tone.UNKNOWN -> s.unknown
        Tone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

private fun toneIcon(tone: Tone): ImageVector? = when (tone) {
    Tone.GREEN -> Icons.Filled.CheckCircle
    Tone.AMBER -> Icons.Filled.Schedule
    Tone.RED -> Icons.Filled.Warning
    Tone.UNKNOWN -> Icons.AutoMirrored.Filled.HelpOutline
    Tone.NEUTRAL -> null
}

@Composable
fun categoryColor(category: String?): Color {
    val s = LocalStatusColors.current
    return when (category) {
        "VFR" -> s.vfr
        "MVFR" -> s.mvfr
        "IFR" -> s.ifr
        "LIFR" -> s.lifr
        else -> s.unknown
    }
}

/** Status as an icon, a word and a colour, so it reads in sunlight and without colour vision. */
@Composable
fun StatusPill(text: String, tone: Tone, modifier: Modifier = Modifier) {
    val color = toneColor(tone)
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        toneIcon(tone)?.let {
            Icon(it, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(5.dp))
        }
        Text(text, color = color, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun CategoryBadge(category: String?, modifier: Modifier = Modifier, large: Boolean = false) {
    val color = categoryColor(category)
    Text(
        category ?: "–",
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color)
            .padding(horizontal = if (large) 10.dp else 6.dp, vertical = if (large) 3.dp else 1.dp),
        color = MaterialTheme.colorScheme.background,
        style = if (large) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    subtitle?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                trailing?.invoke(this)
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
fun InfoTile(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color? = null) {
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value, style = MaterialTheme.typography.titleMedium, color = valueColor ?: MaterialTheme.colorScheme.onSurface,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A row of equal-width tiles. */
@Composable
fun TileRow(vararg tiles: Pair<String, String>, colors: List<Color?> = emptyList()) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.forEachIndexed { i, (label, value) ->
            InfoTile(label, value, Modifier.weight(1f), colors.getOrNull(i))
        }
    }
}

/** Raw METAR / TAF / NOTAM text: monospace, selectable so it can be copied. */
@Composable
fun MonoBlock(text: String, modifier: Modifier = Modifier) {
    SelectionContainer {
        Text(
            text,
            style = MonoStyle,
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(10.dp),
        )
    }
}

@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Text(
        text, modifier = modifier.padding(vertical = 12.dp),
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// ---- Ramp alerts ---------------------------------------------------------------------------

@Composable
fun AlertCard(alert: RampAlert, modifier: Modifier = Modifier) {
    val s = LocalStatusColors.current
    val color = when (alert.level) {
        AlertLevel.WARNING -> s.red
        AlertLevel.CAUTION -> s.amber
        // Neutral, so information never reads as a caution.
        AlertLevel.INFO -> MaterialTheme.colorScheme.secondary
    }
    val icon = when (alert.kind) {
        AlertKind.THUNDERSTORM -> Icons.Filled.Thunderstorm
        AlertKind.WIND -> Icons.Filled.Air
        AlertKind.VISIBILITY -> Icons.Filled.VisibilityOff
        AlertKind.FREEZING -> Icons.Filled.AcUnit
        AlertKind.HEAT -> Icons.Filled.WbSunny
        AlertKind.COLD -> Icons.Filled.AcUnit
        AlertKind.PRECIPITATION -> Icons.Filled.Umbrella
        AlertKind.DUST -> Icons.Filled.Grain
        AlertKind.STALE -> Icons.Filled.Schedule
    }
    val level = when (alert.level) {
        AlertLevel.WARNING -> "Warning"
        AlertLevel.CAUTION -> "Caution"
        AlertLevel.INFO -> "Info"
    }
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.13f))
            .border(2.dp, color, RoundedCornerShape(12.dp))
            .padding(12.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, contentDescription = level, tint = color, modifier = Modifier.size(32.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(alert.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(alert.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
fun NoAlertsCard() {
    val green = LocalStatusColors.current.green
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(green.copy(alpha = 0.10f))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = green, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(12.dp))
        Text("No weather alerts from the latest METAR", style = MaterialTheme.typography.bodyLarge)
    }
}

// ---- Wind compass ----------------------------------------------------------------------------

/** Compass with the wind blowing across it: tail on the "from" bearing, head downwind. */
@Composable
fun WindCompass(c: Conditions, size: Dp = 72.dp) {
    val ink = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val rule = MaterialTheme.colorScheme.outline
    val measurer = rememberTextMeasurer()
    val calm = c.windSpeedKt == null || c.windSpeedKt == 0
    val from = if (!calm && !c.windVariable) c.windDirDeg else null
    val description = when {
        calm -> "Wind calm"
        from == null -> "Wind variable"
        else -> "Wind from $from degrees"
    }
    Canvas(Modifier.size(size).semantics { contentDescription = description }) {
        val r = this.size.minDimension / 2
        val center = Offset(r, r)
        drawCircle(rule, radius = r - 8.dp.toPx(), center = center, style = Stroke(1.5.dp.toPx()))
        val labelStyle = TextStyle(fontSize = 10.sp, color = muted, fontWeight = FontWeight.Bold)
        listOf("N", "E", "S", "W").forEachIndexed { i, label ->
            val a = i * Math.PI / 2
            val layout = measurer.measure(label, labelStyle)
            val p = Offset((r + (r - 5.dp.toPx()) * sin(a)).toFloat(), (r - (r - 5.dp.toPx()) * cos(a)).toFloat())
            drawText(layout, topLeft = p - Offset(layout.size.width / 2f, layout.size.height / 2f))
        }
        if (from != null) {
            val a = Math.toRadians(from.toDouble())
            fun at(angle: Double, d: Float) = Offset((r + d * sin(angle)).toFloat(), (r - d * cos(angle)).toFloat())
            val tail = at(a, r - 14.dp.toPx())
            val head = at(a + Math.PI, r - 16.dp.toPx())
            drawLine(ink, tail, head, strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
            // Arrow head.
            val back = 9.dp.toPx()
            val side = Math.toRadians(25.0)
            val headPath = Path().apply {
                moveTo(head.x, head.y)
                val l = Offset((head.x + back * sin(a + side)).toFloat(), (head.y - back * cos(a + side)).toFloat())
                val rr = Offset((head.x + back * sin(a - side)).toFloat(), (head.y - back * cos(a - side)).toFloat())
                lineTo(l.x, l.y)
                lineTo(rr.x, rr.y)
                close()
            }
            drawPath(headPath, ink)
        } else {
            val layout = measurer.measure(if (calm) "Calm" else "VRB", TextStyle(fontSize = 12.sp, color = muted, fontWeight = FontWeight.Bold))
            drawText(layout, topLeft = center - Offset(layout.size.width / 2f, layout.size.height / 2f))
        }
    }
}

// ---- Flights ---------------------------------------------------------------------------------

/**
 * A board row: time on the left at a glance, flight and route large, status as a pill.
 * The whole row is one touch target.
 */
@Composable
fun FlightRow(
    item: FlightItem,
    zone: ZoneId,
    now: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 16.dp,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val alpha = if (item.muted) 0.6f else 1f
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = horizontalPadding, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.widthIn(min = 78.dp)) {
            Text(
                item.time?.let { (if (item.estimated) "~" else "") + Fmt.hm(it, zone) } ?: "––:––",
                style = BoardTimeStyle, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
            )
            Text(item.timeLabel, style = MaterialTheme.typography.labelMedium, color = muted)
            if (!item.muted && item.time != null) {
                Text(Fmt.relative(item.time, now), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.code,
                    style = FlightCodeStyle,
                    color = (if (item.codeIsCallsign) muted else MaterialTheme.colorScheme.onSurface).copy(alpha = alpha),
                    maxLines = 1,
                )
                if (item.freighter) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Freighter",
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = alpha))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    (if (item.dir == Dir.INBOUND) "from " else "to ") + (item.other ?: "–"),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                )
            }
            Spacer(Modifier.height(4.dp))
            StatusPill(item.status, if (item.muted) Tone.NEUTRAL else item.tone)
            item.detail?.let {
                Spacer(Modifier.height(2.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun FilterRow(
    filter: FlightFilter,
    mineCount: Int,
    onFilter: (FlightFilter) -> Unit,
    onConfigureMine: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        @Composable
        fun chip(f: FlightFilter, label: String, onClick: () -> Unit = { onFilter(f) }) = FilterChip(
            selected = filter == f,
            onClick = onClick,
            label = { Text(label, style = MaterialTheme.typography.labelLarge) },
            modifier = Modifier.height(44.dp),
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.primary,
                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
            ),
        )
        chip(FlightFilter.ALL, "All flights")
        if (mineCount > 0) chip(FlightFilter.MINE, "My airlines ($mineCount)")
        else chip(FlightFilter.MINE, "Set my airlines…", onClick = onConfigureMine)
    }
}

/** Shown when the warehouse snapshot came from the device cache or is old. */
@Composable
fun FreshnessBanner(fromCache: Boolean, fetchedAt: Long?, error: String?, now: Long, onRetry: () -> Unit) {
    if (fetchedAt == null) return
    val ageMin = (now - fetchedAt) / 60_000
    if (!fromCache && ageMin < 30) return
    val amber = LocalStatusColors.current.amber
    Surface(
        color = amber.copy(alpha = 0.16f),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onRetry),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.CloudOff, contentDescription = null, tint = amber)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (fromCache) "Offline: showing saved data from ${Fmt.age(fetchedAt, now)}"
                    else "Data last updated ${Fmt.age(fetchedAt, now)}",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    error?.let { "$it · Tap to retry" } ?: "Tap to retry",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun Dot(color: Color, size: Dp = 10.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}
