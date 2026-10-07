package com.harrydatahub.groundkit.data.parquet

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * Every fixture in resources/parquet is read and compared cell by cell with DuckDB's own
 * reading of it (<file>.expected.json, written by make_fixtures.py). The fixtures are real
 * exports from the API plus synthetic files covering each codec, page version and encoding.
 */
class ParquetReaderTest {
    private val dir = File(javaClass.classLoader!!.getResource("parquet")!!.toURI())
    private val fixtures = dir.listFiles()!!.filter { it.name.endsWith(".parquet") }.sortedBy { it.name }

    @Test
    fun fixturesExist() {
        assertTrue("expected API and synthetic fixtures", fixtures.size >= 14)
    }

    @Test
    fun readsEveryFixtureCellLikeDuckDb() {
        for (file in fixtures) {
            val table = Parquet.read(file.readBytes())
            val expected = JSONObject(File(file.path + ".expected.json").readText())
            val columns = expected.getJSONArray("columns")
            val rows = expected.getJSONArray("rows")
            assertEquals("${file.name} row count", rows.length(), table.numRows)
            for (c in 0 until columns.length()) {
                val name = columns.getJSONArray(c).getString(0)
                val type = columns.getJSONArray(c).getString(1)
                val col = table[name]
                for (r in 0 until rows.length()) {
                    val actual: Any? = when {
                        type.startsWith("TIMESTAMP") -> col.epochMillis(r)
                        type == "DATE" -> col.long(r)
                        type.startsWith("DECIMAL") || type == "DOUBLE" || type == "FLOAT" -> col.double(r)
                        type == "BOOLEAN" -> col.bool(r)
                        type == "VARCHAR" -> col.string(r)
                        else -> col.long(r)
                    }
                    check(rows.getJSONArray(r).get(c), actual, "${file.name} $name ($type) row $r")
                }
            }
        }
    }

    @Test
    fun readsTheApiAirportsTable() {
        val table = Parquet.read(File(dir, "reference.airports.parquet").readBytes())
        val icao = table["icao"]
        val tz = table["timezone"]
        val hkg = (0 until table.numRows).single { icao.string(it) == "VHHH" }
        assertEquals("Asia/Hong_Kong", tz.string(hkg))
        assertEquals("HKG", table["iata"].string(hkg))
        assertEquals(22.3, table["lat"].double(hkg)!!, 0.1)
    }

    @Test
    fun rejectsNonParquet() {
        try {
            Parquet.read("{\"detail\": \"not available\"}".toByteArray())
            fail("expected ParquetException")
        } catch (_: ParquetException) {
        }
    }

    private fun check(expected: Any, actual: Any?, where: String) {
        when {
            expected == JSONObject.NULL -> assertEquals(where, null, actual)
            expected is Number && actual is Double -> {
                val e = expected.toDouble()
                assertTrue("$where: expected $e, got $actual", abs(e - actual) <= 1e-9 * maxOf(1.0, abs(e)))
            }
            expected is Number -> assertEquals(where, expected.toLong(), (actual as Number?)?.toLong())
            else -> assertEquals(where, expected, actual)
        }
    }
}
