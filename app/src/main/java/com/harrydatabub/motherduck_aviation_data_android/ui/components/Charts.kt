package com.harrydatabub.motherduck_aviation_data_android.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.harrydatabub.motherduck_aviation_data_android.data.DailyMovements
import com.harrydatabub.motherduck_aviation_data_android.data.WeatherHour
import com.harrydatabub.motherduck_aviation_data_android.domain.DAY_MS
import com.harrydatabub.motherduck_aviation_data_android.ui.Fmt
import com.harrydatabub.motherduck_aviation_data_android.ui.theme.LocalStatusColors
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.ceil

/** Wind and gust over the last 72 hours, with each hour's flight category as a strip below. */
@Composable
fun WindChart(hours: List<WeatherHour>, zone: ZoneId) {
    if (hours.isEmpty()) {
        EmptyState("No hourly weather in the last 72 hours.")
        return
    }
    val s = LocalStatusColors.current
    val windColor = MaterialTheme.colorScheme.primary
    val gustColor = s.departurePath
    val grid = MaterialTheme.colorScheme.outlineVariant
    val label = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val maxKt = maxOf(30, hours.maxOf { maxOf(it.windSpeedKt ?: 0, it.windGustKt ?: 0) })
    val top = (ceil(maxKt / 10.0) * 10).toInt()
    val first = hours.first().hourUtc
    val last = hours.last().hourUtc
    val span = (last - first).coerceAtLeast(1).toFloat()
    val catColors = hours.map {
        when (it.flightCategory) {
            "VFR" -> s.vfr; "MVFR" -> s.mvfr; "IFR" -> s.ifr; "LIFR" -> s.lifr; else -> s.unknown
        }
    }
    val peak = hours.maxBy { maxOf(it.windSpeedKt ?: 0, it.windGustKt ?: 0) }
    Column {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(200.dp)
                .semantics {
                    contentDescription = "Wind over 72 hours, peak ${maxOf(peak.windSpeedKt ?: 0, peak.windGustKt ?: 0)} knots"
                },
        ) {
            val left = 30.dp.toPx()
            val bottom = size.height - 34.dp.toPx()
            val strip = 10.dp.toPx()
            val w = size.width - left
            fun x(t: Long) = left + (t - first) / span * w
            fun y(kt: Int) = bottom - kt / top.toFloat() * (bottom - 4.dp.toPx())
            val style = TextStyle(fontSize = 11.sp, color = label)
            for (kt in 0..top step 10) {
                drawLine(grid, Offset(left, y(kt)), Offset(size.width, y(kt)), strokeWidth = 1f)
                val l = measurer.measure("$kt", style)
                drawText(l, topLeft = Offset(left - l.size.width - 6.dp.toPx(), y(kt) - l.size.height / 2f))
            }
            fun series(value: (WeatherHour) -> Int?, color: Color, dashed: Boolean) {
                val path = Path()
                var started = false
                for (h in hours) {
                    val v = value(h)
                    if (v == null) { started = false; continue }
                    if (!started) path.moveTo(x(h.hourUtc), y(v)) else path.lineTo(x(h.hourUtc), y(v))
                    started = true
                }
                drawPath(
                    path, color,
                    style = Stroke(2.5.dp.toPx(), pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null),
                )
            }
            series({ it.windSpeedKt }, windColor, dashed = false)
            series({ it.windGustKt }, gustColor, dashed = true)
            // Category strip.
            val cell = w / hours.size
            hours.forEachIndexed { i, h ->
                drawRect(catColors[i], Offset(x(h.hourUtc) - cell / 2, bottom + 6.dp.toPx()), Size(cell + 1, strip))
            }
            listOf(first, first + (last - first) / 2, last).forEachIndexed { i, t ->
                val l = measurer.measure(Fmt.dayHm(t, zone).substringAfter(' '), style)
                val px = when (i) {
                    0 -> left
                    1 -> x(t) - l.size.width / 2f
                    else -> size.width - l.size.width
                }
                drawText(l, topLeft = Offset(px, size.height - l.size.height))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            LegendSwatch(windColor, "Wind kt")
            Spacer(Modifier.width(16.dp))
            LegendSwatch(gustColor, "Gust kt")
            Spacer(Modifier.width(16.dp))
            Text("Strip: flight category", style = MaterialTheme.typography.bodySmall, color = label)
        }
    }
}

/** Observed arrivals and departures per UTC day. */
@Composable
fun MovementsChart(days: List<DailyMovements>) {
    if (days.isEmpty()) {
        EmptyState("No movements in the last 30 days.")
        return
    }
    val s = LocalStatusColors.current
    val label = MaterialTheme.colorScheme.onSurfaceVariant
    val grid = MaterialTheme.colorScheme.outlineVariant
    val measurer = rememberTextMeasurer()
    val max = days.maxOf { maxOf(it.arrivals, it.departures) }.coerceAtLeast(1)
    Column {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(180.dp)
                .semantics { contentDescription = "Daily movements, up to $max a day" },
        ) {
            val left = 34.dp.toPx()
            val bottom = size.height - 20.dp.toPx()
            val slot = (size.width - left) / days.size
            val bar = (slot * 0.4f).coerceAtLeast(1f)
            fun y(v: Long) = bottom - v / max.toFloat() * (bottom - 6.dp.toPx())
            val style = TextStyle(fontSize = 11.sp, color = label)
            listOf(0L, max / 2, max).forEach { v ->
                drawLine(grid, Offset(left, y(v)), Offset(size.width, y(v)), strokeWidth = 1f)
                val l = measurer.measure("$v", style)
                drawText(l, topLeft = Offset(left - l.size.width - 6.dp.toPx(), y(v) - l.size.height / 2f))
            }
            days.forEachIndexed { i, d ->
                val x0 = left + i * slot + slot * 0.1f
                drawRect(s.arrivalPath, Offset(x0, y(d.arrivals)), Size(bar, bottom - y(d.arrivals)))
                drawRect(s.departurePath, Offset(x0 + bar, y(d.departures)), Size(bar, bottom - y(d.departures)))
                if (i % 7 == 0 || i == days.lastIndex) {
                    val l = measurer.measure(Fmt.date(d.dayEpochDay * DAY_MS, ZoneOffset.UTC).substringAfter(' '), style)
                    drawText(l, topLeft = Offset((x0).coerceAtMost(size.width - l.size.width), size.height - l.size.height))
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            LegendSwatch(s.arrivalPath, "Arrivals")
            Spacer(Modifier.width(16.dp))
            LegendSwatch(s.departurePath, "Departures")
        }
    }
}

@Composable
private fun LegendSwatch(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Dot(color)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}
