package com.harrydatabub.motherduck_aviation_data_android.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.harrydatabub.motherduck_aviation_data_android.data.MyFix
import com.harrydatabub.motherduck_aviation_data_android.data.TrackLine
import com.harrydatabub.motherduck_aviation_data_android.domain.Dir
import com.harrydatabub.motherduck_aviation_data_android.domain.Placement
import com.harrydatabub.motherduck_aviation_data_android.domain.PlacedAircraft
import com.harrydatabub.motherduck_aviation_data_android.domain.TERMINAL_KM
import com.harrydatabub.motherduck_aviation_data_android.domain.windText
import com.harrydatabub.motherduck_aviation_data_android.ui.FlightBoards
import com.harrydatabub.motherduck_aviation_data_android.ui.FlightItem
import com.harrydatabub.motherduck_aviation_data_android.ui.Fmt
import com.harrydatabub.motherduck_aviation_data_android.ui.Tone
import com.harrydatabub.motherduck_aviation_data_android.ui.UiState
import com.harrydatabub.motherduck_aviation_data_android.ui.components.CategoryBadge
import com.harrydatabub.motherduck_aviation_data_android.ui.components.Dot
import com.harrydatabub.motherduck_aviation_data_android.ui.components.StatusPill
import com.harrydatabub.motherduck_aviation_data_android.ui.theme.LocalStatusColors
import com.harrydatabub.motherduck_aviation_data_android.ui.theme.StatusColors
import com.harrydatabub.motherduck_aviation_data_android.ui.theme.isDarkTheme
import com.harrydatabub.motherduck_aviation_data_android.ui.tone
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin

private const val MIN_ZOOM = 5f
private const val MAX_ZOOM = 12f
private const val DEFAULT_ZOOM = 9f

/** Web Mercator, normalised to 0..1 on both axes. */
internal fun mercatorX(lon: Double): Double = (lon + 180) / 360

internal fun mercatorY(lat: Double): Double {
    val s = sin(lat * PI / 180)
    return 0.5 - ln((1 + s) / (1 - s)) / (4 * PI)
}

private fun mercator(lat: Double, lon: Double): Offset = Offset(mercatorX(lon).toFloat(), mercatorY(lat).toFloat())

/**
 * Live airspace around the airport, as the Dive's map: live aircraft within 500 NM
 * (this airport's flights coloured by delay status), observed arrival (blue) and departure
 * (orange) paths from the last 3 days, and the 50 NM terminal area. Pinch or use the
 * buttons to zoom, drag to pan, tap an aircraft for its details.
 */
@Composable
fun MapScreen(
    state: UiState,
    selected: String?,
    onSelect: (String?) -> Unit,
    onDetails: (FlightItem) -> Unit,
    me: MyFix? = null,
) {
    val snap = state.snapshot ?: return
    val traffic = state.traffic
    val dark = isDarkTheme
    val status = LocalStatusColors.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val tiles = remember { TileCache(scope) }
    val measurer = rememberTextMeasurer()
    val ink = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    val home = remember(snap.airport.icao) { mercator(snap.airport.lat, snap.airport.lon) }
    var center by remember(snap.airport.icao) { mutableStateOf(home) }
    var zoom by remember(snap.airport.icao) { mutableFloatStateOf(DEFAULT_ZOOM) }
    var canvasSize by remember { mutableStateOf(Size.Zero) }
    val tilePx = with(density) { 128.dp.toPx() }

    // Paths and aircraft projected once per data change, not per frame.
    val paths = remember(snap.tracks) { snap.tracks.map { it to project(it) } }
    val aircraft = traffic?.placed.orEmpty()
    val projected = remember(aircraft) { aircraft.map { it to mercator(it.live.lat, it.live.lon) } }

    // "Show on map" from a flight: centre on it.
    LaunchedEffect(selected) {
        projected.firstOrNull { it.first.live.icao24 == selected }?.let { center = it.second }
    }

    fun worldSize() = tilePx * 2f.pow(zoom)
    fun toScreen(n: Offset): Offset = (n - center) * worldSize() + Offset(canvasSize.width / 2, canvasSize.height / 2)
    fun zoomAround(focus: Offset, factor: Float) {
        val before = center + (focus - Offset(canvasSize.width / 2, canvasSize.height / 2)) / worldSize()
        zoom = (zoom + ln(factor) / ln(2f)).coerceIn(MIN_ZOOM, MAX_ZOOM)
        center = before - (focus - Offset(canvasSize.width / 2, canvasSize.height / 2)) / worldSize()
    }

    Box(Modifier.fillMaxSize()) {
        Canvas(
            Modifier
                .fillMaxSize()
                .semantics { contentDescription = "Map of live aircraft around ${snap.airport.iata}" }
                .pointerInput(snap.airport.icao) {
                    detectTransformGestures { centroid, pan, gestureZoom, _ ->
                        center -= pan / worldSize()
                        if (gestureZoom != 1f) zoomAround(centroid, gestureZoom)
                    }
                }
                .pointerInput(snap.airport.icao, projected) {
                    detectTapGestures(
                        onDoubleTap = { zoomAround(it, 2f) },
                        onTap = { tap ->
                            val hit = projected
                                .map { (a, n) -> a to (toScreen(n) - tap).getDistance() }
                                .filter { it.second < 28.dp.toPx() }
                                .minByOrNull { it.second }
                            onSelect(hit?.first?.live?.icao24)
                        },
                    )
                },
        ) {
            canvasSize = size
            drawRect(status.mapBackground)
            drawTiles(tiles, dark, zoom, center.x.toDouble(), center.y.toDouble(), tilePx)

            val ws = worldSize()
            val origin = Offset(size.width / 2, size.height / 2) - center * ws
            // 50 NM terminal area.
            val pxPerKm = ws / (40075.017 * cos(snap.airport.lat * PI / 180)).toFloat()
            val homePx = origin + home * ws
            drawCircle(
                ink.copy(alpha = 0.4f), radius = (TERMINAL_KM * pxPerKm).toFloat(), center = homePx,
                style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))),
            )
            // Observed paths.
            for ((line, pts) in paths) {
                val path = Path()
                pts.forEachIndexed { i, n ->
                    val p = origin + n * ws
                    if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
                drawPath(
                    path, (if (line.arrival) status.arrivalPath else status.departurePath).copy(alpha = 0.45f),
                    style = Stroke(1.6.dp.toPx(), join = StrokeJoin.Round),
                )
            }
            // Live aircraft: others first, so this airport's flights draw on top.
            val onScreen = projected.map { (a, n) -> a to origin + n * ws }
                .filter { (_, p) -> p.x > -40 && p.y > -40 && p.x < size.width + 40 && p.y < size.height + 40 }
            for ((a, p) in onScreen.sortedBy { it.first.tracked }) {
                drawAircraft(p, a.live.trackDeg ?: 0.0, markerColor(a, status), status.halo, if (a.tracked) 1.5f else 1.1f)
                if (a.live.icao24 == selected) {
                    drawCircle(ink, radius = 18.dp.toPx(), center = p, style = Stroke(2.5.dp.toPx()))
                }
            }
            // Labels: the selected aircraft, then this airport's flights nearest first, then
            // others when zoomed in. One that would overlap a label already drawn is skipped.
            val labelStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            val taken = mutableListOf<Rect>()
            val order = onScreen.sortedWith(
                compareByDescending<Pair<PlacedAircraft, Offset>> { it.first.live.icao24 == selected }
                    .thenByDescending { it.first.tracked }
                    .thenBy { it.first.distNm },
            )
            for ((a, p) in order) {
                val isSelected = a.live.icao24 == selected
                if (!isSelected && !(a.tracked && zoom >= 7.5f) && zoom < 9.5f) continue
                val layout = measurer.measure(a.label, labelStyle.copy(color = if (a.tracked) markerColor(a, status) else muted))
                val topLeft = p + Offset(14.dp.toPx(), -layout.size.height / 2f)
                val box = Rect(topLeft, Size(layout.size.width.toFloat(), layout.size.height.toFloat()))
                if (!isSelected && taken.any { it.overlaps(box) }) continue
                taken += box
                drawText(layout, topLeft = topLeft)
            }
            // The airport, coloured by flight category.
            drawCircle(status.halo, radius = 12.dp.toPx(), center = homePx)
            drawCircle(categoryColorRaw(snap.conditions?.flightCategory, status), radius = 9.dp.toPx(), center = homePx)
            // Me, on top.
            me?.let { drawMe(origin + mercator(it.lat, it.lon) * ws, 0f, it.bearingDeg, status.arrivalPath, status.halo) }
        }

        // Zoom controls: large, thumb-sized.
        Column(
            Modifier
                .align(Alignment.TopStart)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MapButton(Icons.Filled.Add, "Zoom in") { zoomAround(Offset(canvasSize.width / 2, canvasSize.height / 2), 2f) }
            MapButton(Icons.Filled.Remove, "Zoom out") { zoomAround(Offset(canvasSize.width / 2, canvasSize.height / 2), 0.5f) }
            MapButton(Icons.Filled.MyLocation, "Centre on ${snap.airport.iata}") {
                center = home
                zoom = DEFAULT_ZOOM
            }
        }

        // Weather and legend, top right.
        Surface(
            Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp),
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.93f),
            shadowElevation = 2.dp,
        ) {
            Column(Modifier.padding(10.dp)) {
                snap.conditions?.takeIf { it.metarRaw != null }?.let { c ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(snap.airport.iata, style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.width(6.dp))
                        c.flightCategory?.let { CategoryBadge(it) }
                    }
                    Text(windText(c), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                }
                Legend(status.green, "On time")
                Legend(status.amber, "15–44 min late")
                Legend(status.red, "45+ min late")
                Legend(status.unknown, "No usual time")
                Legend(status.otherTraffic, "Other traffic")
            }
        }

        // Selected aircraft, bottom.
        val sel = traffic?.let { t -> selected?.let { FlightBoards.itemFor(it, snap, t) } }
        if (sel != null) {
            SelectedCard(sel, snap.airport.iata, onClose = { onSelect(null) }, onDetails = { onDetails(sel) }, modifier = Modifier.align(Alignment.BottomCenter))
        } else {
            val note = when {
                state.live.error != null && traffic == null -> "Live positions unavailable: ${state.live.error}"
                traffic == null -> "Loading live positions…"
                else -> "${traffic.placed.size} aircraft · ${snap.tracks.size} observed paths · updated ${state.live.at?.let { Fmt.hm(it, snap.zone) } ?: "–"}"
            }
            Surface(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
            ) {
                Text(note, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            }
        }
        Text(
            "Esri, HERE, Garmin, © OpenStreetMap contributors",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
            color = muted,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 4.dp, bottom = 2.dp),
        )
    }
}

private fun project(line: TrackLine): List<Offset> = line.lats.indices.map { mercator(line.lats[it], line.lons[it]) }

private fun markerColor(a: PlacedAircraft, s: StatusColors): Color = when (a.placement) {
    Placement.INBOUND, Placement.OUTBOUND -> when (a.rag.tone()) {
        Tone.GREEN -> s.green
        Tone.AMBER -> s.amber
        Tone.RED -> s.red
        else -> s.unknown
    }
    Placement.GROUND -> s.ground
    Placement.OTHER -> s.otherTraffic
}

private fun categoryColorRaw(category: String?, s: StatusColors) = when (category) {
    "VFR" -> s.vfr
    "MVFR" -> s.mvfr
    "IFR" -> s.ifr
    "LIFR" -> s.lifr
    else -> s.unknown
}

/**
 * The basemap around normalised point (cx, cy). Positions are worked out in doubles: at
 * street zooms a float loses whole pixels. Above [TileCache.MAX_LEVEL] the deepest tiles
 * are scaled up.
 */
internal fun DrawScope.drawTiles(tiles: TileCache, dark: Boolean, zoom: Float, cx: Double, cy: Double, tilePx: Float) {
    tiles.version.intValue // redraw when a tile arrives
    val z = floor(zoom).toInt().coerceAtMost(TileCache.MAX_LEVEL)
    val n = 1 shl z
    val ws = tilePx * 2.0.pow(zoom.toDouble())
    val size = ws / n // on-screen size of one tile at level z
    val ox = this.size.width / 2 - cx * ws
    val oy = this.size.height / 2 - cy * ws
    val x0 = floor(-ox / size).toInt()
    val y0 = floor(-oy / size).toInt()
    val x1 = floor((this.size.width - ox) / size).toInt()
    val y1 = floor((this.size.height - oy) / size).toInt()
    for (tx in x0..x1) for (ty in y0..y1) {
        if (ty < 0 || ty >= n) continue
        val wrapped = ((tx % n) + n) % n
        val img = tiles.get(tiles.url(dark, z, wrapped, ty)) ?: continue
        val left = ox + tx * size
        val top = oy + ty * size
        // Half a pixel of overlap hides seams between tiles.
        drawImage(
            img,
            dstOffset = IntOffset(left.toInt(), top.toInt()),
            dstSize = IntSize((size + 1).toInt(), (size + 1).toInt()),
        )
    }
}

/** The Dive's aircraft glyph, pointing along the track. */
private fun DrawScope.drawAircraft(p: Offset, trackDeg: Double, fill: Color, halo: Color, scale: Float) {
    val k = 1.dp.toPx() * scale
    val glyph = Path().apply {
        moveTo(0f, -8f * k)
        lineTo(5.5f * k, 6f * k)
        lineTo(0f, 3f * k)
        lineTo(-5.5f * k, 6f * k)
        close()
    }
    translate(p.x, p.y) {
        rotate(trackDeg.toFloat(), pivot = Offset.Zero) {
            drawPath(glyph, fill)
            drawPath(glyph, halo, style = Stroke(1.dp.toPx()))
        }
    }
}

@Composable
internal fun MapButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    FilledTonalIconButton(onClick = onClick, modifier = Modifier.size(52.dp)) {
        Icon(icon, contentDescription = label)
    }
}

@Composable
internal fun Legend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 1.dp)) {
        Dot(color)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SelectedCard(item: FlightItem, here: String, onClose: () -> Unit, onDetails: () -> Unit, modifier: Modifier) {
    Surface(
        modifier
            .fillMaxWidth()
            .padding(12.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.code, style = MaterialTheme.typography.titleLarge)
                    Text(
                        when {
                            !item.recognised -> item.callsign ?: item.aircraft?.live?.icao24 ?: ""
                            item.dir == Dir.INBOUND -> "${item.other ?: "?"} → $here"
                            else -> "$here → ${item.other ?: "?"}"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Close")
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusPill(item.status, item.tone)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDetails, modifier = Modifier.height(48.dp)) { Text("Details") }
            }
            item.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
