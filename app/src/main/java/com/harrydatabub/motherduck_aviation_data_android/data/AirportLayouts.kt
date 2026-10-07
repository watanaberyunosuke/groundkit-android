package com.harrydatabub.motherduck_aviation_data_android.data

import com.harrydatabub.motherduck_aviation_data_android.domain.AirportLayout
import com.harrydatabub.motherduck_aviation_data_android.domain.DAY_MS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Airport layouts from OpenStreetMap through the Overpass API (no key), one download per
 * airport kept on the device: layouts change rarely, and the map must work without signal.
 * First the aerodrome's outline gives a box; then everything mapped in the box.
 */
class AirportLayouts(dir: File) {
    private val dir = File(dir, "layouts").apply { mkdirs() }

    private fun file(icao: String) = File(dir, "$icao.json")

    /** The kept copy, if any; parsing takes a moment, so off the main thread. */
    suspend fun cached(icao: String): AirportLayout? = withContext(Dispatchers.Default) {
        val f = file(icao)
        if (!f.exists()) return@withContext null
        runCatching { AirportLayout.parse(icao, f.readText(), f.lastModified()) }.getOrNull()
    }

    fun isStale(layout: AirportLayout, now: Long = System.currentTimeMillis()) = now - layout.fetchedAt > MAX_AGE_MS

    /** Downloads and keeps the layout around the airport (its reference point as a fallback). */
    suspend fun download(icao: String, lat: Double, lon: Double): AirportLayout = withContext(Dispatchers.IO) {
        val box = aerodromeBox(icao) ?: Box(lat - 0.03, lon - 0.04, lat + 0.03, lon + 0.04)
        val m = MARGIN_DEG
        val bbox = "${box.s - m},${box.w - m},${box.n + m},${box.e + m}"
        val query = """
            [out:json][timeout:90][bbox:$bbox];
            (
              way["aeroway"~"^(runway|stopway|taxiway|taxilane|holding_position|parking_position|jet_bridge|apron|terminal|hangar)${'$'}"];
              node["aeroway"~"^(gate|holding_position)${'$'}"];
              way["building"];
              way["highway"~"^(service|motorway|trunk|primary|secondary|tertiary|unclassified)${'$'}"];
            );
            out geom qt;
        """.trimIndent()
        val json = overpass(query)
        val layout = withContext(Dispatchers.Default) { AirportLayout.parse(icao, json, System.currentTimeMillis()) }
        if (layout.isEmpty) throw IOException("OpenStreetMap has no layout mapped for $icao")
        val tmp = File(dir, "$icao.json.tmp")
        tmp.writeText(json)
        tmp.renameTo(file(icao))
        layout
    }

    private data class Box(val s: Double, val w: Double, val n: Double, val e: Double)

    private fun aerodromeBox(icao: String): Box? {
        val json = overpass("[out:json][timeout:25];nwr[\"aeroway\"=\"aerodrome\"][\"icao\"=\"$icao\"];out bb;")
        val elements = JSONObject(json).optJSONArray("elements") ?: return null
        return (0 until elements.length()).mapNotNull { elements.getJSONObject(it).optJSONObject("bounds") }
            .map { b -> Box(b.getDouble("minlat"), b.getDouble("minlon"), b.getDouble("maxlat"), b.getDouble("maxlon")) }
            .maxByOrNull { (it.n - it.s) * (it.e - it.w) }
    }

    /** POSTs a query to the main Overpass server, then the mirrors when it is busy. */
    private fun overpass(query: String): String {
        var last: Exception? = null
        for (server in SERVERS) {
            try {
                val conn = URL(server).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 15_000
                conn.readTimeout = 120_000
                conn.useCaches = false
                conn.setRequestProperty("User-Agent", USER_AGENT)
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                try {
                    conn.outputStream.use { it.write(("data=" + URLEncoder.encode(query, "UTF-8")).toByteArray()) }
                    if (conn.responseCode != 200) throw IOException("Overpass ${conn.responseCode} from ${URL(server).host}")
                    val body = conn.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
                    // A busy server can answer 200 with an HTML error page.
                    if (!body.trimStart().startsWith("{")) throw IOException("Overpass busy at ${URL(server).host}")
                    return body
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IOException("Overpass unavailable")
    }

    private companion object {
        val SERVERS = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.private.coffee/api/interpreter",
            "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
        )
        const val USER_AGENT = "ramp-ops-android (github.com/watanaberyunosuke/motherduck-aviation-data-android)"
        const val MAX_AGE_MS = 30 * DAY_MS
        /** About 300 m round the aerodrome, for its access roads. */
        const val MARGIN_DEG = 0.003
    }
}
