package com.harrydatabub.motherduck_aviation_data_android.domain

import com.harrydatabub.motherduck_aviation_data_android.data.AviationApi
import com.harrydatabub.motherduck_aviation_data_android.data.ObservedFlight
import com.harrydatabub.motherduck_aviation_data_android.data.WarehouseRepository
import com.harrydatabub.motherduck_aviation_data_android.data.parquet.Parquet
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.ZoneId

/**
 * The Kotlin port of the Dive's `historyQ` must give the same usual times, day counts and
 * usual origins / destinations as the original SQL. history_vhhh.expected.json is DuckDB
 * running that SQL on HKG's real flights (make_history_fixture.py).
 */
class FlightHistoryGoldenTest {
    private val dir = File(javaClass.classLoader!!.getResource("history")!!.toURI())

    @Test
    fun matchesTheDiveSqlOnRealHongKongFlights() {
        val repo = WarehouseRepository(AviationApi("http://unused", Files.createTempDirectory("t").toFile()))
        val arrivals = repo.flights(Parquet.read(File(dir, "arrivals_vhhh.parquet").readBytes()), arrival = true)
        val departures = repo.flights(Parquet.read(File(dir, "departures_vhhh.parquet").readBytes()), arrival = false)
        val expected = JSONObject(File(dir, "history_vhhh.expected.json").readText())
        val history = FlightHistory.build(
            arrivals, departures, expected.getString("icao"), ZoneId.of(expected.getString("timezone")), expected.getLong("now_ms"),
        )

        val rows = expected.getJSONArray("rows")
        assertTrue("fixture has rows", rows.length() > 1000)
        var checkedOther = 0
        for (i in 0 until rows.length()) {
            val r = rows.getJSONObject(i)
            val callsign = r.getString("callsign").trim()
            val dir = if (r.getString("dir") == "inbound") Dir.INBOUND else Dir.OUTBOUND
            val usual = history[callsign]?.get(dir)
            assertNotNull("$callsign $dir missing", usual)
            usual!!
            val where = "$callsign $dir"
            assertEquals("$where count", r.getInt("n"), usual.count)
            assertEquals("$where days_14", r.getInt("days_14"), usual.days14)
            assertEquals("$where usual_min", r.getDouble("usual_min"), usual.usualMin, 1e-6)
            if (r.getBoolean("mode_is_unique") && !r.isNull("other")) {
                assertEquals("$where other", r.getString("other"), usual.other)
                checkedOther++
            }
        }
        assertTrue("checked usual origins/destinations", checkedOther > 1000)
        assertEquals("no extra callsigns", rows.length(), history.values.sumOf { listOfNotNull(it.inbound, it.outbound).size })
    }

    @Test
    fun keepsTheBackendFreighterTagPerCallsign() {
        val now = 1_791_200_000_000L
        fun seen(callsign: String, freighter: Boolean, daysAgo: Int) = ObservedFlight(
            "VHHH", callsign, null, null, null, "RJAA", "NRT", now - daysAgo * DAY_MS, null, freighter,
        )
        val history = FlightHistory.build(
            arrivals = listOf(seen("FDX5150", true, 1), seen("FDX5150", true, 2), seen("CPA101", false, 1)),
            departures = emptyList(), icao = "VHHH", zone = ZoneId.of("Asia/Hong_Kong"), now = now,
        )
        assertTrue(history.getValue("FDX5150").inbound!!.freighter)
        assertFalse(history.getValue("CPA101").inbound!!.freighter)
    }
}
