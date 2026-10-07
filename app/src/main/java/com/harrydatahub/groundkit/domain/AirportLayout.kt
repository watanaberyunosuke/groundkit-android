package com.harrydatahub.groundkit.domain

import org.json.JSONObject
import java.util.PriorityQueue
import kotlin.math.cos

enum class AreaKind { RUNWAY, APRON, TERMINAL, HANGAR, CARGO, BUILDING }

enum class LineKind { RUNWAY, STOPWAY, TAXIWAY, TAXILANE, HOLDING, STAND, JET_BRIDGE, SERVICE_ROAD, ROAD }

enum class PlaceKind(val label: String) {
    GATE("Gate"), STAND("Stand"), TAXIWAY("Taxiway"), CARGO("Cargo"), TERMINAL("Terminal"),
    HANGAR("Hangar"), BUILDING("Building"),
}

/** A way's outline or centreline. */
class Shape(val lats: DoubleArray, val lons: DoubleArray, val label: String?) {
    val minLat = lats.min()
    val maxLat = lats.max()
    val minLon = lons.min()
    val maxLon = lons.max()
}

class Area(val kind: AreaKind, val shape: Shape)

class Line(val kind: LineKind, val shape: Shape, val widthM: Double?)

data class Place(val kind: PlaceKind, val label: String, val lat: Double, val lon: Double) {
    val title get() = if (kind == PlaceKind.GATE || kind == PlaceKind.STAND || kind == PlaceKind.TAXIWAY) "${kind.label} $label" else label
}

/** A route along the roads, from where you are to a place. */
data class Route(
    val lats: DoubleArray,
    val lons: DoubleArray,
    /** Along the roads, plus the straight bits to and from them. */
    val distanceM: Double,
    /** No route kept to one-way rules; this one ignores them. */
    val ignoresOneWay: Boolean,
)

/**
 * An airport's layout from OpenStreetMap: runways, taxiways and stands, aprons, terminals,
 * cargo sheds and other buildings, gates, and the roads, which also give routes between
 * them. OSM is mapped by volunteers, so it can be out of date or incomplete; the airport's
 * own charts and markings take precedence.
 */
class AirportLayout(
    val icao: String,
    val fetchedAt: Long,
    val areas: List<Area>,
    val lines: List<Line>,
    val places: List<Place>,
    /** Runway holding points mapped as single points, lat/lon pairs. */
    val holdingPoints: List<Pair<Double, Double>>,
    private val roads: RoadGraph,
) {
    // The airfield's extent, for fitting the map: roads run on for miles, so they don't count.
    private val airfield = areas.filter { it.kind != AreaKind.BUILDING }.map { it.shape } +
        lines.filter { it.kind in AIRFIELD_LINES }.map { it.shape }
    val minLat = airfield.minOfOrNull { it.minLat }
    val maxLat = airfield.maxOfOrNull { it.maxLat }
    val minLon = airfield.minOfOrNull { it.minLon }
    val maxLon = airfield.maxOfOrNull { it.maxLon }

    val isEmpty get() = areas.isEmpty() && lines.isEmpty()

    /** Places whose label or kind matches `query`, best matches first. */
    fun search(query: String, limit: Int = 40): List<Place> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return places.sortedWith(placeOrder).take(limit)
        return places
            .mapNotNull { p ->
                val label = p.label.lowercase()
                val title = p.title.lowercase()
                val rank = when {
                    label == q || title == q -> 0
                    label.startsWith(q) || title.startsWith(q) -> 1
                    q in title || q in p.kind.label.lowercase() -> 2
                    else -> return@mapNotNull null
                }
                rank to p
            }
            .sortedWith(compareBy<Pair<Int, Place>> { it.first }.thenComparator { a, b -> placeOrder.compare(a.second, b.second) })
            .take(limit)
            .map { it.second }
    }

    fun route(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Route? =
        roads.route(fromLat, fromLon, toLat, toLon)

    companion object {
        private val AIRFIELD_LINES = setOf(LineKind.RUNWAY, LineKind.STOPWAY, LineKind.TAXIWAY, LineKind.TAXILANE)
        private val placeOrder = compareBy<Place>({ it.kind.ordinal }, { naturalKey(it.label) })

        /** "A12" before "A100". */
        private fun naturalKey(s: String) = s.replace(Regex("\\d+")) { it.value.padStart(6, '0') }

        private val CARGO_WORDS = Regex(
            "cargo|freight|logistic|express|air ?mail|courier|dhl|fedex|ups|hactl|tnt|forwarder|貨|货|物流",
            RegexOption.IGNORE_CASE,
        )
        private val ONEWAY_YES = setOf("yes", "true", "1")
        private val SKIP_SERVICE = setOf("parking_aisle", "emergency_access")

        /** From an Overpass API response (`out geom`) for the airport's area. */
        fun parse(icao: String, json: String, fetchedAt: Long): AirportLayout {
            val elements = JSONObject(json).optJSONArray("elements") ?: return AirportLayout(icao, fetchedAt, emptyList(), emptyList(), emptyList(), emptyList(), RoadGraph.EMPTY)
            val areas = mutableListOf<Area>()
            val lines = mutableListOf<Line>()
            val places = LinkedHashMap<Pair<PlaceKind, String>, Place>()
            val roads = RoadGraph.Builder()
            val taxiwayLength = HashMap<String, Pair<Double, Place>>()
            val holds = mutableListOf<Pair<Double, Double>>()

            fun addPlace(kind: PlaceKind, label: String?, lat: Double, lon: Double) {
                val l = label?.trim()?.takeIf { it.isNotEmpty() } ?: return
                places.putIfAbsent(kind to l, Place(kind, l, lat, lon))
            }

            for (i in 0 until elements.length()) {
                val e = elements.getJSONObject(i)
                val tags = e.optJSONObject("tags") ?: continue
                fun tag(k: String): String? = tags.optString(k).takeIf { it.isNotEmpty() }
                val aeroway = tag("aeroway")
                val label = tag("ref") ?: tag("name:en") ?: tag("name")

                if (e.getString("type") == "node") {
                    if (aeroway == "gate") addPlace(PlaceKind.GATE, tag("ref") ?: tag("name"), e.getDouble("lat"), e.getDouble("lon"))
                    if (aeroway == "holding_position") holds += e.getDouble("lat") to e.getDouble("lon")
                    continue
                }
                val geom = e.optJSONArray("geometry") ?: continue
                if (geom.length() < 2) continue
                val lats = DoubleArray(geom.length()) { geom.getJSONObject(it).getDouble("lat") }
                val lons = DoubleArray(geom.length()) { geom.getJSONObject(it).getDouble("lon") }
                val closed = lats.first() == lats.last() && lons.first() == lons.last() && lats.size > 3
                val shape = Shape(lats, lons, label)
                val mid = lats.size / 2
                val width = tag("width")?.substringBefore(' ')?.toDoubleOrNull()

                when (aeroway) {
                    "runway" -> if (closed && tag("area") == "yes") areas += Area(AreaKind.RUNWAY, shape) else lines += Line(LineKind.RUNWAY, shape, width)
                    "stopway" -> lines += Line(LineKind.STOPWAY, shape, width)
                    "taxiway", "taxilane" -> {
                        lines += Line(if (aeroway == "taxiway") LineKind.TAXIWAY else LineKind.TAXILANE, shape, width)
                        // One search result per taxiway, at its longest piece.
                        tag("ref")?.let { ref ->
                            val len = length(lats, lons)
                            if (len > (taxiwayLength[ref]?.first ?: 0.0)) {
                                taxiwayLength[ref] = len to Place(PlaceKind.TAXIWAY, ref, lats[mid], lons[mid])
                            }
                        }
                    }
                    "holding_position" -> lines += Line(LineKind.HOLDING, shape, null)
                    "parking_position" -> {
                        lines += Line(LineKind.STAND, shape, null)
                        addPlace(PlaceKind.STAND, tag("ref") ?: tag("name"), lats[mid], lons[mid])
                    }
                    "jet_bridge" -> lines += Line(LineKind.JET_BRIDGE, shape, null)
                    "apron" -> if (closed) areas += Area(AreaKind.APRON, shape)
                    "terminal" -> if (closed) {
                        val cargo = CARGO_WORDS.containsMatchIn(tags.toString())
                        areas += Area(if (cargo) AreaKind.CARGO else AreaKind.TERMINAL, shape)
                        addPlace(if (cargo) PlaceKind.CARGO else PlaceKind.TERMINAL, tag("name:en") ?: tag("name"), centreLat(lats), centreLon(lons))
                    }
                    "hangar" -> if (closed) {
                        areas += Area(AreaKind.HANGAR, shape)
                        addPlace(PlaceKind.HANGAR, tag("name:en") ?: tag("name") ?: tag("ref"), centreLat(lats), centreLon(lons))
                    }
                    else -> when {
                        tag("building") != null && closed -> {
                            val name = tag("name:en") ?: tag("name")
                            val cargo = tag("building") == "warehouse" || (name != null && CARGO_WORDS.containsMatchIn(name)) ||
                                CARGO_WORDS.containsMatchIn(tag("operator").orEmpty())
                            areas += Area(if (cargo) AreaKind.CARGO else AreaKind.BUILDING, shape)
                            addPlace(if (cargo) PlaceKind.CARGO else PlaceKind.BUILDING, name, centreLat(lats), centreLon(lons))
                        }
                        tag("highway") != null -> {
                            val highway = tag("highway")!!
                            if (tag("service") in SKIP_SERVICE || tag("access") == "no") {
                                lines += Line(LineKind.SERVICE_ROAD, shape, null)
                                continue
                            }
                            lines += Line(if (highway == "service") LineKind.SERVICE_ROAD else LineKind.ROAD, shape, null)
                            val nodes = e.optJSONArray("nodes")
                            if (nodes != null && nodes.length() == lats.size) {
                                val oneway = tag("oneway")
                                roads.addWay(
                                    LongArray(nodes.length()) { nodes.getLong(it) }, lats, lons,
                                    forward = oneway != "-1",
                                    backward = oneway !in ONEWAY_YES && oneway != "-1" && tag("junction") != "roundabout" || oneway == "-1",
                                )
                            }
                        }
                    }
                }
            }
            taxiwayLength.values.forEach { (_, p) -> places.putIfAbsent(p.kind to p.label, p) }
            // Small buildings under the big ones, so names and taxiways stay readable.
            areas.sortBy { it.kind.ordinal }
            return AirportLayout(icao, fetchedAt, areas, lines, places.values.toList(), holds, roads.build())
        }

        private fun centreLat(lats: DoubleArray) = (lats.min() + lats.max()) / 2
        private fun centreLon(lons: DoubleArray) = (lons.min() + lons.max()) / 2

        private fun length(lats: DoubleArray, lons: DoubleArray): Double =
            (1 until lats.size).sumOf { metres(lats[it - 1], lons[it - 1], lats[it], lons[it]) }
    }
}

/** Metres between two nearby points (equirectangular: fine across an airport). */
fun metres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val k = Math.PI / 180 * 6_371_008.8
    val x = (lon2 - lon1) * k * cos((lat1 + lat2) / 2 * Math.PI / 180)
    val y = (lat2 - lat1) * k
    return kotlin.math.sqrt(x * x + y * y)
}

/** The road network as a graph of OSM nodes, for routes. */
class RoadGraph private constructor(
    private val lat: DoubleArray,
    private val lon: DoubleArray,
    /** Per node: the nodes reachable in one step, and which of those steps are one-way against. */
    private val next: Array<IntArray>,
    private val againstOneWay: Array<BooleanArray>,
) {
    class Builder {
        private val index = HashMap<Long, Int>()
        private val lat = ArrayList<Double>()
        private val lon = ArrayList<Double>()
        private val edges = ArrayList<MutableList<Pair<Int, Boolean>>>()

        private fun node(id: Long, la: Double, lo: Double): Int = index.getOrPut(id) {
            lat.add(la)
            lon.add(lo)
            edges.add(mutableListOf())
            lat.size - 1
        }

        fun addWay(ids: LongArray, lats: DoubleArray, lons: DoubleArray, forward: Boolean, backward: Boolean) {
            for (i in 1 until ids.size) {
                val a = node(ids[i - 1], lats[i - 1], lons[i - 1])
                val b = node(ids[i], lats[i], lons[i])
                if (a == b) continue
                edges[a].add(b to !forward)
                edges[b].add(a to !backward)
            }
        }

        fun build() = RoadGraph(
            lat.toDoubleArray(), lon.toDoubleArray(),
            Array(edges.size) { n -> IntArray(edges[n].size) { edges[n][it].first } },
            Array(edges.size) { n -> BooleanArray(edges[n].size) { edges[n][it].second } },
        )
    }

    val size get() = lat.size

    /**
     * The shortest route by road, keeping to one-way rules if possible. It starts from
     * the road nodes near `from` and ends at the one near `to` that gives the shortest
     * total, so a gap in the map near either end doesn't stop it.
     */
    fun route(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Route? {
        if (size == 0) return null
        return search(fromLat, fromLon, toLat, toLon, oneWay = true)
            ?: search(fromLat, fromLon, toLat, toLon, oneWay = false)
    }

    private fun search(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, oneWay: Boolean): Route? {
        val dist = DoubleArray(size) { Double.POSITIVE_INFINITY }
        val prev = IntArray(size) { -1 }
        val queue = PriorityQueue<Pair<Double, Int>>(compareBy { it.first })
        // Off-road legs cost double, so routes keep to the roads where they can.
        nearest(fromLat, fromLon).forEach { (n, d) ->
            dist[n] = d * OFF_ROAD
            queue += dist[n] to n
        }
        if (queue.isEmpty()) return null
        while (queue.isNotEmpty()) {
            val (d, n) = queue.poll()!!
            if (d > dist[n]) continue
            val out = next[n]
            for (j in out.indices) {
                if (oneWay && againstOneWay[n][j]) continue
                val m = out[j]
                val nd = d + metres(lat[n], lon[n], lat[m], lon[m])
                if (nd < dist[m]) {
                    dist[m] = nd
                    prev[m] = n
                    queue += nd to m
                }
            }
        }
        val end = nearest(toLat, toLon)
            .filter { dist[it.first].isFinite() }
            .minByOrNull { (n, d) -> dist[n] + d * OFF_ROAD } ?: return null
        val path = generateSequence(end.first) { prev[it].takeIf { p -> p >= 0 } }.toList().reversed()
        val lats = doubleArrayOf(fromLat) + DoubleArray(path.size) { lat[path[it]] } + toLat
        val lons = doubleArrayOf(fromLon) + DoubleArray(path.size) { lon[path[it]] } + toLon
        val metresAlong = (1 until lats.size).sumOf { metres(lats[it - 1], lons[it - 1], lats[it], lons[it]) }
        return Route(lats, lons, metresAlong, ignoresOneWay = !oneWay)
    }

    /** Road nodes within [SNAP_M] of a point (or the nearest one), with their distances. */
    private fun nearest(la: Double, lo: Double): List<Pair<Int, Double>> {
        val all = (0 until size).map { it to metres(la, lo, lat[it], lon[it]) }
        val near = all.filter { it.second <= SNAP_M }
        return near.ifEmpty { listOfNotNull(all.minByOrNull { it.second }) }
    }

    companion object {
        /** How far from a road a route may start or end. */
        const val SNAP_M = 400.0
        private const val OFF_ROAD = 2.0
        val EMPTY = Builder().build()
    }
}
