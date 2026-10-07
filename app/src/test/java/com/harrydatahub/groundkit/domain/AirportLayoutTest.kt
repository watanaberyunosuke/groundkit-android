package com.harrydatahub.groundkit.domain

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A small airport in Overpass's `out geom` shape: a runway, taxiway K in two pieces, a
 * stand, a gate, a cargo shed, a terminal, and a square of service roads with a one-way
 * side, about 1 km across, near 22.3° N.
 */
class AirportLayoutTest {
    private var nextId = 1L
    private val nodeIds = HashMap<Pair<Double, Double>, Long>()

    private fun node(lat: Double, lon: Double) = nodeIds.getOrPut(lat to lon) { nextId++ }

    private fun way(tags: Map<String, String>, vararg pts: Pair<Double, Double>) = JSONObject()
        .put("type", "way").put("id", nextId++)
        .put("nodes", JSONArray(pts.map { node(it.first, it.second) }))
        .put("geometry", JSONArray(pts.map { JSONObject().put("lat", it.first).put("lon", it.second) }))
        .put("tags", JSONObject(tags))

    private fun gate(ref: String, lat: Double, lon: Double) = JSONObject()
        .put("type", "node").put("id", nextId++).put("lat", lat).put("lon", lon)
        .put("tags", JSONObject(mapOf("aeroway" to "gate", "ref" to ref)))

    // Corners of the road square.
    private val sw = 22.300 to 113.900
    private val se = 22.300 to 113.910
    private val ne = 22.309 to 113.910
    private val nw = 22.309 to 113.900

    private fun layout(): AirportLayout {
        val elements = JSONArray()
            .put(way(mapOf("aeroway" to "runway", "ref" to "07L/25R", "width" to "60"), 22.310 to 113.890, 22.312 to 113.930))
            .put(way(mapOf("aeroway" to "taxiway", "ref" to "K"), 22.305 to 113.890, 22.305 to 113.895))
            .put(way(mapOf("aeroway" to "taxiway", "ref" to "K"), 22.305 to 113.895, 22.305 to 113.920))
            .put(way(mapOf("aeroway" to "parking_position", "ref" to "N12"), 22.304 to 113.905, 22.303 to 113.905))
            .put(gate("23", 22.3045, 113.906))
            .put(
                way(
                    mapOf("building" to "yes", "name" to "國泰航空貨運站 Cathay Pacific Cargo Terminal"),
                    22.301 to 113.901, 22.301 to 113.903, 22.302 to 113.903, 22.302 to 113.901, 22.301 to 113.901,
                ),
            )
            .put(
                way(
                    mapOf("aeroway" to "terminal", "name" to "Terminal 1"),
                    22.306 to 113.906, 22.306 to 113.908, 22.307 to 113.908, 22.307 to 113.906, 22.306 to 113.906,
                ),
            )
            // South and west sides both ways; the east side one-way northwards; the north side both ways.
            .put(way(mapOf("highway" to "service", "access" to "private"), sw, se))
            .put(way(mapOf("highway" to "service", "oneway" to "yes"), se, ne))
            .put(way(mapOf("highway" to "service"), ne, nw))
            .put(way(mapOf("highway" to "service"), nw, sw))
        return AirportLayout.parse("VTST", JSONObject().put("elements", elements).toString(), 0)
    }

    @Test
    fun parsesShapesAndPlaces() {
        val l = layout()
        assertFalse(l.isEmpty)
        assertEquals(setOf(AreaKind.CARGO, AreaKind.TERMINAL), l.areas.map { it.kind }.toSet())
        assertEquals(60.0, l.lines.single { it.kind == LineKind.RUNWAY }.widthM!!, 0.0)
        assertEquals(4, l.lines.count { it.kind == LineKind.SERVICE_ROAD })
        // One taxiway K, at its longer piece.
        val k = l.places.single { it.kind == PlaceKind.TAXIWAY }
        assertEquals("Taxiway K", k.title)
        assertTrue(k.lon > 113.895)
        assertEquals("Stand N12", l.places.single { it.kind == PlaceKind.STAND }.title)
        assertEquals("Gate 23", l.places.single { it.kind == PlaceKind.GATE }.title)
        assertEquals("國泰航空貨運站 Cathay Pacific Cargo Terminal", l.places.single { it.kind == PlaceKind.CARGO }.label)
        assertEquals(PlaceKind.TERMINAL, l.places.single { it.label == "Terminal 1" }.kind)
    }

    @Test
    fun searchRanksExactMatchesFirst() {
        val l = layout()
        assertEquals("Gate 23", l.search("23").first().title)
        assertEquals("Stand N12", l.search("n1").first().title)
        assertEquals(PlaceKind.CARGO, l.search("cargo").first().kind)
        assertEquals(PlaceKind.TAXIWAY, l.search("taxiway").single().kind)
        assertTrue(l.search("zzz").isEmpty())
        // Empty query: everything, gates first.
        assertEquals(PlaceKind.GATE, l.search("").first().kind)
    }

    @Test
    fun routesKeepToOneWayRoads() {
        val l = layout()
        // North-east to south-east: the east side is one-way northwards, so the route goes
        // round the other three sides (about 1 + 1.03 + 1 km).
        val down = l.route(ne.first, ne.second, se.first, se.second)
        assertNotNull(down)
        assertFalse(down!!.ignoresOneWay)
        assertEquals(1028.0 + 1003.0 + 1028.0, down.distanceM, 30.0)
        // South-east to north-east may use it directly.
        val up = l.route(se.first, se.second, ne.first, ne.second)!!
        assertEquals(1003.0, up.distanceM, 15.0)
        // Starting off the road: the straight leg to it counts.
        val fromStand = l.route(22.3005, 113.905, nw.first, nw.second)!!
        assertTrue(fromStand.distanceM > 1000)
        assertEquals(22.3005, fromStand.lats.first(), 0.0)
    }

    @Test
    fun noRoadsNoRoute() {
        val empty = AirportLayout.parse("VTST", """{"elements":[]}""", 0)
        assertTrue(empty.isEmpty)
        assertNull(empty.route(22.3, 113.9, 22.31, 113.91))
    }
}
