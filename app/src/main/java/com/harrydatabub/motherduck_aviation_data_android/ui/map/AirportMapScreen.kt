package com.harrydatabub.motherduck_aviation_data_android.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.harrydatabub.motherduck_aviation_data_android.data.Airport
import com.harrydatabub.motherduck_aviation_data_android.data.MyFix
import com.harrydatabub.motherduck_aviation_data_android.domain.AirportLayout
import com.harrydatabub.motherduck_aviation_data_android.domain.AreaKind
import com.harrydatabub.motherduck_aviation_data_android.domain.LineKind
import com.harrydatabub.motherduck_aviation_data_android.domain.Place
import com.harrydatabub.motherduck_aviation_data_android.domain.PlaceKind
import com.harrydatabub.motherduck_aviation_data_android.domain.Route
import com.harrydatabub.motherduck_aviation_data_android.domain.Shape
import com.harrydatabub.motherduck_aviation_data_android.domain.bearingDeg
import com.harrydatabub.motherduck_aviation_data_android.domain.metres
import com.harrydatabub.motherduck_aviation_data_android.ui.Fmt
import com.harrydatabub.motherduck_aviation_data_android.ui.LayoutState
import com.harrydatabub.motherduck_aviation_data_android.ui.theme.LocalStatusColors
import com.harrydatabub.motherduck_aviation_data_android.ui.theme.isDarkTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

private const val MIN_ZOOM = 11f
private const val MAX_ZOOM = 19.5f
private const val FALLBACK_ZOOM = 14.5f

/** Airside driving speed for the time estimate; local limits vary (often 15–30 km/h). */
private const val DRIVE_KMH = 25

/**
 * The airport's layout, as a ramp worker's navigation map: runways, taxiways and stands,
 * aprons, terminals, cargo sheds and other buildings, gates, and the service roads tugs
 * and cargo dollies use, from OpenStreetMap. Shows where you are, finds a gate, stand,
 * taxiway or building, and routes to it by road.
 */
@Composable
fun AirportMapScreen(
    airport: Airport,
    layoutState: LayoutState,
    me: MyFix?,
    locationAllowed: Boolean,
    onRequestLocation: () -> Unit,
    onRetry: () -> Unit,
) {
    val layout = layoutState.layout?.takeIf { it.icao == airport.icao }
    val dark = isDarkTheme
    val status = LocalStatusColors.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val tiles = remember { TileCache(scope) }
    val measurer = rememberTextMeasurer()
    val style = remember(dark) { LayoutStyle.of(dark) }

    // Home: the airport reference point. Shapes are kept relative to it as floats.
    val hx = remember(airport.icao) { mercatorX(airport.lon) }
    val hy = remember(airport.icao) { mercatorY(airport.lat) }
    var cx by remember(airport.icao) { mutableDoubleStateOf(hx) }
    var cy by remember(airport.icao) { mutableDoubleStateOf(hy) }
    var zoom by remember(airport.icao) { mutableFloatStateOf(FALLBACK_ZOOM) }
    var fitted by remember(airport.icao) { mutableStateOf(false) }
    var follow by remember(airport.icao) { mutableStateOf(false) }
    var canvasSize by remember { mutableStateOf(Size.Zero) }
    val tilePx = with(density) { 128.dp.toPx() }

    var selected by remember(airport.icao) { mutableStateOf<Place?>(null) }
    var routeTo by remember(airport.icao) { mutableStateOf<Place?>(null) }
    var route by remember(airport.icao) { mutableStateOf<Route?>(null) }
    var routing by remember { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var showLegend by rememberSaveable { mutableStateOf(false) }

    val drawn = remember(layout, hx, hy) { layout?.let { Projected.of(it, hx, hy) } }

    fun worldSize() = tilePx * 2.0.pow(zoom.toDouble())
    fun fit() {
        val l = layout ?: return
        if (canvasSize == Size.Zero || l.minLat == null) return
        val x0 = mercatorX(l.minLon!!)
        val x1 = mercatorX(l.maxLon!!)
        val y0 = mercatorY(l.maxLat!!)
        val y1 = mercatorY(l.minLat)
        cx = (x0 + x1) / 2
        cy = (y0 + y1) / 2
        val fx = canvasSize.width * 0.9 / ((x1 - x0) * tilePx)
        val fy = canvasSize.height * 0.8 / ((y1 - y0) * tilePx)
        zoom = log2(minOf(fx, fy)).toFloat().coerceIn(MIN_ZOOM, MAX_ZOOM)
    }
    fun centreOn(lat: Double, lon: Double, atLeast: Float? = null) {
        cx = mercatorX(lon)
        cy = mercatorY(lat)
        atLeast?.let { if (zoom < it) zoom = it }
    }
    fun zoomAround(focus: Offset, factor: Float) {
        val ws = worldSize()
        val fx = cx + (focus.x - canvasSize.width / 2) / ws
        val fy = cy + (focus.y - canvasSize.height / 2) / ws
        zoom = (zoom + ln(factor) / ln(2f)).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val ws2 = worldSize()
        cx = fx - (focus.x - canvasSize.width / 2) / ws2
        cy = fy - (focus.y - canvasSize.height / 2) / ws2
    }
    fun toScreen(lat: Double, lon: Double): Offset {
        val ws = worldSize()
        return Offset(((mercatorX(lon) - cx) * ws + canvasSize.width / 2).toFloat(), ((mercatorY(lat) - cy) * ws + canvasSize.height / 2).toFloat())
    }

    // Fit the airport once its layout and the canvas are known.
    LaunchedEffect(layout, canvasSize) {
        if (!fitted && layout != null && canvasSize != Size.Zero) {
            fit()
            fitted = true
        }
    }
    // Follow me: keep the map on my position as it moves.
    LaunchedEffect(me, follow) {
        if (follow && me != null) centreOn(me.lat, me.lon)
    }
    // The route, again as I move (about every 20 m).
    val moved = me?.let { (it.lat * 5_000).roundToInt() to (it.lon * 5_000).roundToInt() }
    LaunchedEffect(routeTo, moved, layout) {
        val target = routeTo
        if (target == null || me == null || layout == null) {
            route = null
            return@LaunchedEffect
        }
        routing = route == null
        route = withContext(Dispatchers.Default) { layout.route(me.lat, me.lon, target.lat, target.lon) }
        routing = false
    }

    Box(Modifier.fillMaxSize()) {
        Canvas(
            Modifier
                .fillMaxSize()
                .semantics { contentDescription = "Layout map of ${airport.iata}" }
                .pointerInput(airport.icao) {
                    detectTransformGestures { centroid, pan, gestureZoom, _ ->
                        if (pan != Offset.Zero) follow = false
                        val ws = worldSize()
                        cx -= pan.x / ws
                        cy -= pan.y / ws
                        if (gestureZoom != 1f) zoomAround(centroid, gestureZoom)
                    }
                }
                .pointerInput(airport.icao, drawn) {
                    detectTapGestures(
                        onDoubleTap = { zoomAround(it, 2f) },
                        onTap = { tap ->
                            val hit = drawn?.labelled(zoom)
                                ?.map { p -> p to (toScreen(p.lat, p.lon) - tap).getDistance() }
                                ?.filter { it.second < 28.dp.toPx() }
                                ?.minByOrNull { it.second }?.first
                            if (hit != null) selected = hit else if (routeTo == null) selected = null
                        },
                    )
                },
        ) {
            canvasSize = size
            drawRect(status.mapBackground)
            drawTiles(tiles, dark, zoom, cx, cy, tilePx)
            val ws = worldSize()
            val view = View(((hx - cx) * ws + size.width / 2).toFloat(), ((hy - cy) * ws + size.height / 2).toFloat(), ws.toFloat(), size)
            val pxPerM = (ws / (40_075_016.7 * cos(airport.lat * PI / 180))).toFloat()
            drawn?.let { drawLayout(it, view, zoom, pxPerM, style) }

            route?.let { r ->
                val path = Path()
                for (i in r.lats.indices) {
                    val p = toScreen(r.lats[i], r.lons[i])
                    if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
                drawPath(path, status.halo, style = Stroke(9.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(path, style.route, style = Stroke(5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }

            // The selected place under the labels, so its own label stays readable.
            selected?.let { p ->
                val s = toScreen(p.lat, p.lon)
                drawCircle(status.halo, radius = 13.dp.toPx(), center = s)
                drawCircle(style.selected, radius = 10.dp.toPx(), center = s)
                drawCircle(status.halo, radius = 4.dp.toPx(), center = s)
            }
            drawn?.let { drawLabels(it, view, zoom, style, measurer, selected) }
            me?.let { drawMe(toScreen(it.lat, it.lon), (it.accuracyM ?: 0f) * pxPerM, it.bearingDeg, status.arrivalPath, status.halo) }
        }

        // Find, top.
        Surface(
            onClick = { searching = true },
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 3.dp,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(start = 76.dp, end = 76.dp, top = 12.dp)
                .fillMaxWidth()
                .height(52.dp),
        ) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Search, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Text(
                    "Find gate, stand, building…", style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Zoom, fit and my position, left.
        Column(
            Modifier
                .align(Alignment.TopStart)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MapButton(Icons.Filled.Add, "Zoom in") { zoomAround(Offset(canvasSize.width / 2, canvasSize.height / 2), 2f) }
            MapButton(Icons.Filled.Remove, "Zoom out") { zoomAround(Offset(canvasSize.width / 2, canvasSize.height / 2), 0.5f) }
            MapButton(Icons.Filled.FitScreen, "Show all of ${airport.iata}") {
                follow = false
                fit()
            }
            MapButton(Icons.Filled.MyLocation, if (follow) "Following your position" else "Show my position") {
                when {
                    !locationAllowed -> onRequestLocation()
                    me != null -> {
                        follow = true
                        centreOn(me.lat, me.lon, atLeast = 16.5f)
                    }
                    else -> follow = true
                }
            }
        }
        MapButtonAt(Modifier.align(Alignment.TopEnd).padding(12.dp), Icons.Filled.Layers, "Map key") { showLegend = !showLegend }
        if (showLegend) LayoutLegend(style, Modifier.align(Alignment.TopEnd).padding(top = 72.dp, end = 12.dp))

        // Status and the selected place, bottom.
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                layout == null && layoutState.loading -> Note {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Downloading the ${airport.iata} layout from OpenStreetMap. Once only; it can take a minute.", style = MaterialTheme.typography.bodyMedium)
                }
                layout == null && layoutState.error != null -> Note {
                    Text("Couldn't load the layout: ${layoutState.error}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text("Retry") }
                }
                me != null && metres(me.lat, me.lon, airport.lat, airport.lon) > FAR_M && selected == null -> Note {
                    Text(
                        "You're ${Fmt.thousands((metres(me.lat, me.lon, airport.lat, airport.lon) / 1000).roundToInt().toLong())} km from ${airport.iata}.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            selected?.let { p ->
                PlaceCard(
                    p, me, route.takeIf { routeTo == p }, routing && routeTo == p,
                    onRoute = {
                        if (!locationAllowed) onRequestLocation()
                        routeTo = p
                    },
                    onClose = {
                        selected = null
                        routeTo = null
                    },
                )
            }
            if (selected == null) {
                Text(
                    "Layout © OpenStreetMap contributors (ODbL)" + (layout?.let { " · ${Fmt.date(it.fetchedAt, java.time.ZoneId.systemDefault())}" } ?: "") +
                        ". Check against airport charts and markings.",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.End)
                        .clickable(enabled = layout != null, onClick = onRetry)
                        .padding(horizontal = 4.dp),
                )
            }
        }
    }

    if (searching && layout != null) {
        SearchSheet(
            layout, me,
            onPick = { p ->
                searching = false
                selected = p
                follow = false
                centreOn(p.lat, p.lon, atLeast = if (p.kind == PlaceKind.TAXIWAY) 16f else 17f)
            },
            onDismiss = { searching = false },
        )
    }
}

private const val FAR_M = 20_000.0

/** The layout's shapes as floats relative to home, normalised Mercator, with bounds for culling. */
private class Projected(
    val areas: List<Pair<AreaKind, Pts>>,
    val lines: List<Triple<LineKind, Pts, Float?>>,
    val places: List<Place>,
    val placePts: List<Pair<Place, Pair<Float, Float>>>,
    val holds: List<Pair<Float, Float>>,
) {
    fun labelled(zoom: Float): List<Place> = places.filter { zoom >= labelZoom(it.kind) }

    companion object {
        fun of(l: AirportLayout, hx: Double, hy: Double): Projected {
            fun pts(s: Shape) = Pts(
                FloatArray(s.lats.size) { (mercatorX(s.lons[it]) - hx).toFloat() },
                FloatArray(s.lats.size) { (mercatorY(s.lats[it]) - hy).toFloat() },
            )
            return Projected(
                l.areas.map { it.kind to pts(it.shape) },
                l.lines.map { Triple(it.kind, pts(it.shape), it.widthM?.toFloat()) },
                l.places,
                l.places.map { it to ((mercatorX(it.lon) - hx).toFloat() to (mercatorY(it.lat) - hy).toFloat()) },
                l.holdingPoints.map { (la, lo) -> (mercatorX(lo) - hx).toFloat() to (mercatorY(la) - hy).toFloat() },
            )
        }
    }
}

private class Pts(val x: FloatArray, val y: FloatArray) {
    val minX = x.min()
    val maxX = x.max()
    val minY = y.min()
    val maxY = y.max()
}

/** Screen transform: home at (ox, oy), `ws` pixels per normalised unit. */
private class View(val ox: Float, val oy: Float, val ws: Float, val size: Size) {
    fun visible(p: Pts): Boolean =
        p.maxX * ws + ox > -50 && p.minX * ws + ox < size.width + 50 && p.maxY * ws + oy > -50 && p.minY * ws + oy < size.height + 50

    fun x(v: Float) = v * ws + ox
    fun y(v: Float) = v * ws + oy

    /** The on-screen size of a shape, to skip ones too small to see. */
    fun extent(p: Pts) = maxOf(p.maxX - p.minX, p.maxY - p.minY) * ws

    fun path(p: Pts, into: Path, close: Boolean) {
        into.moveTo(x(p.x[0]), y(p.y[0]))
        for (i in 1 until p.x.size) into.lineTo(x(p.x[i]), y(p.y[i]))
        if (close) into.close()
    }
}

private fun labelZoom(kind: PlaceKind) = when (kind) {
    PlaceKind.CARGO, PlaceKind.TERMINAL -> 14.5f
    PlaceKind.TAXIWAY -> 15f
    PlaceKind.HANGAR -> 15.5f
    PlaceKind.GATE -> 16f
    PlaceKind.STAND -> 16.5f
    PlaceKind.BUILDING -> 16.5f
}

private fun DrawScope.drawLayout(d: Projected, v: View, zoom: Float, pxPerM: Float, s: LayoutStyle) {
    // Areas, batched into one path per kind.
    for (kind in AreaKind.entries) {
        val fill = Path()
        var any = false
        for ((k, p) in d.areas) {
            if (k != kind || !v.visible(p) || v.extent(p) < 2f) continue
            v.path(p, fill, close = true)
            any = true
        }
        if (!any) continue
        drawPath(fill, s.areaFill(kind), style = Fill)
        drawPath(fill, s.areaStroke(kind), style = Stroke(1.dp.toPx()))
    }
    fun lines(kind: LineKind, color: Color, widthPx: (Float?) -> Float, effect: PathEffect? = null, cap: StrokeCap = StrokeCap.Butt) {
        // Lines of one kind share a width unless they carry their own (runways).
        val groups = d.lines.filter { it.first == kind && v.visible(it.second) }.groupBy { widthPx(it.third) }
        for ((w, group) in groups) {
            val path = Path()
            group.forEach { v.path(it.second, path, close = false) }
            drawPath(path, color, style = Stroke(w, cap = cap, join = StrokeJoin.Round, pathEffect = effect))
        }
    }
    // Thinner minimum widths when zoomed out, so the airfield doesn't turn to spaghetti.
    val dp = 1.dp.toPx() * ((zoom - 12f) / 3f).coerceIn(0.35f, 1f)
    // Pavement under the markings: roads, then runways and taxiways at their real widths.
    lines(LineKind.ROAD, s.road, { maxOf(3 * dp, 9 * pxPerM) }, cap = StrokeCap.Round)
    lines(LineKind.SERVICE_ROAD, s.serviceRoad, { maxOf(2.5f * dp, 6 * pxPerM) }, cap = StrokeCap.Round)
    lines(LineKind.RUNWAY, s.runway, { w -> maxOf(6 * dp, (w ?: 45f) * pxPerM) })
    lines(LineKind.STOPWAY, s.runway.copy(alpha = 0.6f), { w -> maxOf(6 * dp, (w ?: 45f) * pxPerM) })
    lines(LineKind.TAXIWAY, s.taxiPavement, { w -> maxOf(3 * dp, (w ?: 23f) * pxPerM) }, cap = StrokeCap.Round)
    lines(LineKind.TAXILANE, s.taxiPavement, { w -> maxOf(2 * dp, (w ?: 15f) * pxPerM) }, cap = StrokeCap.Round)
    // Markings.
    if (zoom >= 14.5f) {
        lines(LineKind.RUNWAY, s.runwayMark, { 1.5f * dp }, PathEffect.dashPathEffect(floatArrayOf(30 * pxPerM, 20 * pxPerM)))
    }
    lines(LineKind.TAXIWAY, s.taxiLine, { 1.6f * dp })
    lines(LineKind.TAXILANE, s.taxiLine, { 1.2f * dp })
    if (zoom >= 15.5f) {
        lines(LineKind.STAND, s.taxiLine, { 1f * dp }, PathEffect.dashPathEffect(floatArrayOf(6 * dp, 4 * dp)))
        lines(LineKind.JET_BRIDGE, s.jetBridge, { maxOf(2 * dp, 3 * pxPerM) }, cap = StrokeCap.Round)
    }
    lines(LineKind.HOLDING, s.holding, { 3 * dp })
    if (zoom >= 14.5f) {
        for ((x, y) in d.holds) drawCircle(s.holding, radius = 3f * dp, center = Offset(v.x(x), v.y(y)))
    }
    // Gates as dots once there is room.
    if (zoom >= 15.5f) {
        for ((p, xy) in d.placePts) {
            if (p.kind != PlaceKind.GATE) continue
            drawCircle(s.gate, radius = 3.5f * dp, center = Offset(v.x(xy.first), v.y(xy.second)))
        }
    }
}

private fun DrawScope.drawLabels(
    d: Projected,
    v: View,
    zoom: Float,
    s: LayoutStyle,
    measurer: androidx.compose.ui.text.TextMeasurer,
    selected: Place?,
) {
    val taken = mutableListOf<Rect>()
    val order = d.placePts
        .filter { (p, _) -> zoom >= labelZoom(p.kind) || p == selected }
        .sortedWith(compareByDescending<Pair<Place, Pair<Float, Float>>> { it.first == selected }.thenBy { labelRank(it.first.kind) })
    for ((p, xy) in order) {
        val at = Offset(v.x(xy.first), v.y(xy.second))
        if (at.x < -60 || at.y < -30 || at.x > size.width + 60 || at.y > size.height + 30) continue
        val text = if (p.kind == PlaceKind.GATE) p.label else p.label.take(28)
        val big = p.kind == PlaceKind.CARGO || p.kind == PlaceKind.TERMINAL || p.kind == PlaceKind.TAXIWAY
        val layout = measurer.measure(
            text,
            TextStyle(
                fontSize = if (big) 12.sp else 11.sp,
                fontWeight = if (big) FontWeight.Bold else FontWeight.Medium,
                color = s.labelColor(p.kind),
            ),
        )
        val w = layout.size.width.toFloat()
        val h = layout.size.height.toFloat()
        val topLeft = when {
            p == selected -> at + Offset(15.dp.toPx(), -h / 2)
            p.kind == PlaceKind.GATE -> at + Offset(6.dp.toPx(), -h / 2)
            else -> at - Offset(w / 2, h / 2)
        }
        val box = Rect(topLeft, Size(w, h)).inflate(2f)
        if (p != selected && taken.any { it.overlaps(box) }) continue
        taken += box
        // A pill behind taxiway letters, as on the signs.
        if (p.kind == PlaceKind.TAXIWAY) {
            drawRoundRect(s.taxiSign, topLeft - Offset(4f, 1f), Size(w + 8f, h + 2f), androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
        } else {
            drawRoundRect(s.labelHalo, topLeft - Offset(3f, 0f), Size(w + 6f, h), androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
        }
        drawText(layout, topLeft = topLeft)
    }
}

private fun labelRank(kind: PlaceKind) = when (kind) {
    PlaceKind.CARGO -> 0
    PlaceKind.TERMINAL -> 1
    PlaceKind.TAXIWAY -> 2
    PlaceKind.HANGAR -> 3
    PlaceKind.GATE -> 4
    PlaceKind.STAND -> 5
    PlaceKind.BUILDING -> 6
}

/** My position: a blue dot, its accuracy, and a wedge for the direction when moving. */
internal fun DrawScope.drawMe(p: Offset, accuracyPx: Float, bearing: Float?, blue: Color, halo: Color) {
    if (accuracyPx > 12.dp.toPx()) {
        drawCircle(blue.copy(alpha = 0.14f), radius = accuracyPx, center = p)
        drawCircle(blue.copy(alpha = 0.4f), radius = accuracyPx, center = p, style = Stroke(1.dp.toPx()))
    }
    if (bearing != null) {
        val r = 22.dp.toPx()
        val a = (bearing - 90) * PI.toFloat() / 180
        val spread = 0.45f
        val wedge = Path().apply {
            moveTo(p.x, p.y)
            lineTo(p.x + r * kotlin.math.cos(a - spread), p.y + r * kotlin.math.sin(a - spread))
            lineTo(p.x + r * kotlin.math.cos(a + spread), p.y + r * kotlin.math.sin(a + spread))
            close()
        }
        drawPath(wedge, blue.copy(alpha = 0.35f))
    }
    drawCircle(halo, radius = 10.dp.toPx(), center = p)
    drawCircle(blue, radius = 7.dp.toPx(), center = p)
}

/** Colours for the layout, in the app's light and hi-vis dark themes. */
private class LayoutStyle(
    val apron: Color, val apronEdge: Color,
    val terminal: Color, val terminalEdge: Color,
    val cargo: Color, val cargoEdge: Color,
    val hangar: Color, val building: Color, val buildingEdge: Color,
    val runway: Color, val runwayMark: Color,
    val taxiPavement: Color, val taxiLine: Color, val taxiSign: Color,
    val holding: Color, val jetBridge: Color, val gate: Color,
    val serviceRoad: Color, val road: Color,
    val route: Color, val selected: Color,
    val label: Color, val labelHalo: Color,
) {
    fun areaFill(k: AreaKind) = when (k) {
        AreaKind.RUNWAY -> runway
        AreaKind.APRON -> apron
        AreaKind.TERMINAL -> terminal
        AreaKind.CARGO -> cargo
        AreaKind.HANGAR -> hangar
        AreaKind.BUILDING -> building
    }

    fun areaStroke(k: AreaKind) = when (k) {
        AreaKind.RUNWAY -> runway
        AreaKind.APRON -> apronEdge
        AreaKind.TERMINAL -> terminalEdge
        AreaKind.CARGO -> cargoEdge
        AreaKind.HANGAR, AreaKind.BUILDING -> buildingEdge
    }

    fun labelColor(k: PlaceKind) = when (k) {
        PlaceKind.TAXIWAY -> Color(0xFF111111)
        PlaceKind.CARGO -> cargoEdge
        PlaceKind.TERMINAL -> terminalEdge
        else -> label
    }

    companion object {
        fun of(dark: Boolean) = if (dark) LayoutStyle(
            apron = Color(0xFF2B2F36), apronEdge = Color(0xFF3B414A),
            terminal = Color(0xFF1E3A5F), terminalEdge = Color(0xFF93C5FD),
            cargo = Color(0xFF3B2763), cargoEdge = Color(0xFFC4B5FD),
            hangar = Color(0xFF334155), building = Color(0xFF30353D), buildingEdge = Color(0xFF4B525C),
            runway = Color(0xFF4B5058), runwayMark = Color(0xFFE5E7EB),
            taxiPavement = Color(0xFF383D45), taxiLine = Color(0xFFFFC72C), taxiSign = Color(0xFFFFC72C),
            holding = Color(0xFFF87171), jetBridge = Color(0xFF6B7280), gate = Color(0xFF93C5FD),
            serviceRoad = Color(0xFFFB923C), road = Color(0xFF6B7280),
            route = Color(0xFF60A5FA), selected = Color(0xFFF87171),
            label = Color(0xFFE5E7EB), labelHalo = Color(0xCC0E1013),
        ) else LayoutStyle(
            apron = Color(0xFFDDE1E6), apronEdge = Color(0xFFC3C8CF),
            terminal = Color(0xFFBFDBFE), terminalEdge = Color(0xFF1D4ED8),
            cargo = Color(0xFFDDD6FE), cargoEdge = Color(0xFF6D28D9),
            hangar = Color(0xFFCBD5E1), building = Color(0xFFE7E9EC), buildingEdge = Color(0xFFB8BEC6),
            runway = Color(0xFF5B6270), runwayMark = Color.White,
            taxiPavement = Color(0xFFC9CED5), taxiLine = Color(0xFFD69E00), taxiSign = Color(0xFFFFC72C),
            holding = Color(0xFFC81E1E), jetBridge = Color(0xFF7B8491), gate = Color(0xFF1D4ED8),
            serviceRoad = Color(0xFFEA7A1E), road = Color(0xFFA3AAB4),
            route = Color(0xFF2563EB), selected = Color(0xFFC81E1E),
            label = Color(0xFF1F2933), labelHalo = Color(0xCCFFFFFF),
        )
    }
}

@Composable
private fun LayoutLegend(s: LayoutStyle, modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f), shadowElevation = 2.dp) {
        Column(Modifier.padding(10.dp)) {
            Legend(s.runway, "Runway")
            Legend(s.taxiLine, "Taxiway")
            Legend(s.holding, "Holding point")
            Legend(s.serviceRoad, "Service road (GSE, cargo)")
            Legend(s.road, "Public road")
            Legend(s.terminalEdge, "Terminal")
            Legend(s.cargoEdge, "Cargo building")
            Legend(s.gate, "Gate")
            Legend(s.route, "Your route")
        }
    }
}

@Composable
private fun MapButtonAt(modifier: Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(modifier) { MapButton(icon, label, onClick) }
}

@Composable
private fun Note(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f), shadowElevation = 2.dp) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

/** Distance and compass direction from me, as "1.2 km NE". */
private fun fromMe(me: MyFix?, p: Place): String? {
    me ?: return null
    val d = metres(me.lat, me.lon, p.lat, p.lon)
    val dirs = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    val dir = dirs[((bearingDeg(me.lat, me.lon, p.lat, p.lon) + 22.5) / 45).toInt() % 8]
    return "${distance(d)} $dir"
}

private fun distance(m: Double) = if (m < 1000) "${(m / 10).roundToInt() * 10} m" else String.format(Locale.ROOT, "%.1f km", m / 1000)

@Composable
private fun PlaceCard(
    p: Place,
    me: MyFix?,
    route: Route?,
    routing: Boolean,
    onRoute: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 6.dp) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(p.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(p.kind.label.takeIf { p.title != "${p.kind.label} ${p.label}" }, fromMe(me, p)?.let { "$it from you" })
                            .joinToString(" · ").ifEmpty { p.kind.label },
                        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.Close, contentDescription = "Close") }
            }
            when {
                route != null -> {
                    val minutes = maxOf(1, (route.distanceM / 1000 / DRIVE_KMH.toDouble() * 60).roundToInt())
                    Text(
                        "${distance(route.distanceM)} by road · about $minutes min at $DRIVE_KMH km/h",
                        style = MaterialTheme.typography.titleMedium, color = LocalStatusColors.current.arrivalPath,
                    )
                    Text(
                        (if (route.ignoresOneWay) "No route keeps to the one-way roads mapped; this one doesn't. " else "") +
                            "From OpenStreetMap: follow airside driving rules, markings and marshallers, and never cross a runway or taxiway without clearance.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 12.dp, top = 2.dp),
                    )
                }
                routing -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Finding a route…", style = MaterialTheme.typography.bodyMedium)
                }
                else -> Row(Modifier.padding(top = 6.dp, end = 12.dp)) {
                    Button(onClick = onRoute, modifier = Modifier.heightIn(min = 52.dp)) {
                        Icon(Icons.Filled.Directions, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (me == null) "Route from my position" else "Route by road")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchSheet(layout: AirportLayout, me: MyFix?, onPick: (Place) -> Unit, onDismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val results = remember(query, layout) { layout.search(query, limit = 60) }
    // Straight to typing.
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp).imePadding().navigationBarsPadding()) {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                placeholder = { Text("Gate 23, stand N5, taxiway K, cargo…") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            Spacer(Modifier.height(8.dp))
            if (results.isEmpty()) {
                Text(
                    if (layout.places.isEmpty()) "No gates, stands or buildings are named in the OpenStreetMap layout here." else "Nothing matches \"$query\".",
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
            LazyColumn(Modifier.heightIn(max = 520.dp)) {
                items(results, key = { "${it.kind}:${it.label}" }) { p ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(p) }
                            .heightIn(min = 56.dp)
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(p.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(p.kind.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        fromMe(me, p)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
