package com.harrydatabub.motherduck_aviation_data_android.domain

import com.harrydatabub.motherduck_aviation_data_android.data.Airline
import com.harrydatabub.motherduck_aviation_data_android.data.LiveAircraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class LiveTrafficTest {
    private val zone = ZoneId.of("Asia/Hong_Kong")
    private val hkgLat = 22.308
    private val hkgLon = 113.918
    private val codes = FlightCodes(mapOf("CPA" to Airline("CPA", "CX", "Cathay Pacific"), "QFA" to Airline("QFA", "QF", "Qantas")))

    /** 12:00 local. */
    private val now = LocalDateTime.of(2026, 10, 5, 12, 0).atZone(zone).toInstant().toEpochMilli()

    private fun usual(hhmm: String, other: String = "SIN", days: Int = 14): Usual {
        val (h, m) = hhmm.split(":").map { it.toInt() }
        return Usual(other, (h * 60 + m).toDouble(), days, days, emptyList())
    }

    /** A point `nm` nautical miles due south of HKG. */
    private fun southOf(nm: Double) = hkgLat - nm * KM_PER_NM / 111.195

    private fun aircraft(
        callsign: String?, lat: Double, lon: Double = hkgLon, track: Double = 0.0, speed: Int? = 300,
        onGround: Boolean = false, icao24: String = callsign ?: "abc123",
    ) = LiveAircraft(icao24, callsign, lat, lon, if (onGround) 0 else 20_000, onGround, speed, track, 0)

    private fun place(list: List<LiveAircraft>, history: Map<String, CallsignHistory>) =
        LiveTraffic.place(list, hkgLat, hkgLon, history, 15.0, 10.0, zone, now, codes)

    @Test
    fun wrapsTimesOfDayAcrossMidnight() {
        assertEquals(-20.0, wrapMinutes(1430.0 - 10.0), 1e-9) // 23:50 is 20 min before 00:10
        assertEquals(20.0, wrapMinutes(10.0 - 1430.0), 1e-9)  // 00:10 is 20 min after 23:50
        assertEquals(-720.0, wrapMinutes(720.0), 1e-9)
        assertEquals("23:59", hhmm(1439.4))
        assertEquals("00:00", hhmm(1439.6))
    }

    @Test
    fun mapsCallsignsToIataFlightNumbers() {
        assertEquals("CX101", codes.flightIata("CPA101"))
        assertEquals("QF1", codes.flightIata("QFA001"))
        assertNull(codes.flightIata("QLK10D"))
        assertNull(codes.flightIata("XYZ123"))
    }

    @Test
    fun inboundEtaIsTimeToTheRingPlusMedianTerminalTime() {
        // 100 NM south heading north at 300 kt: 50 NM to the ring = 10 min, plus 15 min inside.
        val history = mapOf("CPA710" to CallsignHistory(inbound = usual("12:25")))
        val p = place(listOf(aircraft("CPA710", southOf(100.0))), history).single()
        assertEquals(Placement.INBOUND, p.placement)
        assertEquals(25.0, p.etaMin!!, 0.3)
        assertEquals("CX710", p.label)
        assertEquals("SIN", p.other)
        // ETA 12:25 against a usual 12:25: on time.
        assertEquals(0.0, p.delayMin!!, 1.0)
        assertEquals(Rag.GREEN, p.rag)
    }

    @Test
    fun lateInboundIsAmberThenRed() {
        val amber = place(listOf(aircraft("CPA710", southOf(100.0))), mapOf("CPA710" to CallsignHistory(inbound = usual("11:55")))).single()
        assertEquals(Rag.AMBER, amber.rag) // ~30 min late
        val red = place(listOf(aircraft("CPA710", southOf(100.0))), mapOf("CPA710" to CallsignHistory(inbound = usual("11:30")))).single()
        assertEquals(Rag.RED, red.rag) // ~55 min late
    }

    @Test
    fun callsignFlyingAwayIsNotInboundBeyond30Nm() {
        val history = mapOf("CPA710" to CallsignHistory(inbound = usual("12:25")))
        val away = place(listOf(aircraft("CPA710", southOf(100.0), track = 180.0)), history).single()
        assertEquals(Placement.OTHER, away.placement)
        // Inside 30 NM history is trusted whatever the track (approach manoeuvring).
        val near = place(listOf(aircraft("CPA710", southOf(20.0), track = 180.0)), history).single()
        assertEquals(Placement.INBOUND, near.placement)
    }

    @Test
    fun groundAircraftBecomeDeparturesWithLateness() {
        val history = mapOf(
            "CPA100" to CallsignHistory(outbound = usual("12:20", "LHR")),
            "CPA200" to CallsignHistory(outbound = usual("11:20", "NRT")),
        )
        val placed = place(
            listOf(
                aircraft("CPA100", hkgLat + 0.01, onGround = true, speed = 0),
                aircraft("CPA200", hkgLat - 0.01, onGround = true, speed = 0),
            ),
            history,
        )
        assertTrue(placed.all { it.placement == Placement.GROUND })
        val board = LiveTraffic.boardLive(placed, history, now, zone).associateBy { it.callsign }
        assertEquals(Dir.OUTBOUND, board.getValue("CPA100").dir)
        assertEquals(Rag.GREEN, board.getValue("CPA100").rag)
        assertNull(board.getValue("CPA100").statusNote)
        assertEquals(Rag.AMBER, board.getValue("CPA200").rag)
        assertEquals("Late 40 min", board.getValue("CPA200").statusNote)
    }

    @Test
    fun feedMemoryDetectsLandingAndDepartureFromTheFeed() {
        val history = mapOf("CPA710" to CallsignHistory(inbound = usual("12:05", days = 3)))
        val memory = FeedMemory()
        fun fix(at: Long, vararg a: LiveAircraft) {
            val placed = LiveTraffic.place(a.toList(), hkgLat, hkgLon, history, 15.0, 10.0, zone, at, codes)
            memory.update(LiveTraffic.boardLive(placed, history, at, zone), at)
        }
        val t1 = now
        val t2 = now + 2 * MINUTE_MS
        val t3 = now + 4 * MINUTE_MS
        fix(t1, aircraft("CPA710", southOf(5.0), speed = 140))
        fix(t2, aircraft("CPA710", hkgLat, onGround = true, speed = 20))
        assertEquals(t2, memory["CPA710"]!!.landedAt)
        fix(t3)
        assertEquals(t3, memory["CPA710"]!!.goneAt)

        val board = LiveTraffic.buildBoard(Dir.INBOUND, emptyList(), memory.snapshot(), history, t3, zone, codes)
        val row = board.single()
        assertEquals(Phase.PAST, row.phase)
        assertEquals(BoardStatus.LANDED, row.status)
        assertEquals(t2, row.at)
        assertEquals("CX710", row.flightIata)
    }

    @Test
    fun boardListsRegularFlightsInTheWindowOnly() {
        val history = mapOf(
            "CPA1" to CallsignHistory(inbound = usual("13:00", days = 10)), // next, regular
            "CPA2" to CallsignHistory(inbound = usual("13:30", days = 3)),  // not regular
            "CPA3" to CallsignHistory(inbound = usual("19:00", days = 14)), // beyond 6 h
            "CPA4" to CallsignHistory(inbound = usual("10:00", days = 14)), // past 3 h
        )
        val board = LiveTraffic.buildBoard(Dir.INBOUND, emptyList(), emptyMap(), history, now, zone, codes)
        assertEquals(listOf("CPA4", "CPA1"), board.map { it.callsign })
        assertEquals(Phase.PAST, board[0].phase)
        assertEquals(BoardStatus.PRESUMED, board[0].status)
        assertEquals(Phase.NEXT, board[1].phase)
        assertEquals(now + 60 * MINUTE_MS, board[1].at)
    }
}
