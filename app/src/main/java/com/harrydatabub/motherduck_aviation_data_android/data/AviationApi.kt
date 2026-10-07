package com.harrydatabub.motherduck_aviation_data_android.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class ApiException(message: String) : IOException(message)

/**
 * Client for the aviation project's Vercel API (api/index.py), unchanged from what the
 * web Dive uses:
 *  - GET /api/tables/<schema>.<table>: an allow-listed warehouse table as Parquet,
 *    edge-cached for 10 minutes;
 *  - GET /api/live/<icao>: live aircraft within 500 NM as JSON, edge-cached for 2 minutes.
 *
 * Each table download is also kept on disk, so the app still opens with the last data
 * when the apron has no signal.
 */
class AviationApi(private val baseUrl: String, cacheRoot: File) {
    private val cacheDir = File(cacheRoot, "tables").apply { mkdirs() }

    class TableBytes(val bytes: ByteArray, val fetchedAt: Long, val fromCache: Boolean)

    /** The latest copy of a table: the network if it answers, else the disk cache. */
    suspend fun table(name: String): TableBytes = withContext(Dispatchers.IO) {
        try {
            val bytes = get("/api/tables/$name")
            val now = System.currentTimeMillis()
            cacheFile(name).let { f ->
                val tmp = File(f.path + ".tmp")
                tmp.writeBytes(bytes)
                tmp.renameTo(f)
                f.setLastModified(now)
            }
            TableBytes(bytes, now, fromCache = false)
        } catch (e: IOException) {
            cached(name) ?: throw e
        }
    }

    /** The disk copy of a table, if one was ever downloaded. */
    suspend fun cachedTable(name: String): TableBytes? = withContext(Dispatchers.IO) { cached(name) }

    suspend fun live(icao: String): LiveFeed = withContext(Dispatchers.IO) {
        parseLive(get("/api/live/$icao").toString(Charsets.UTF_8))
    }

    private fun cached(name: String): TableBytes? {
        val f = cacheFile(name)
        return if (f.isFile) TableBytes(f.readBytes(), f.lastModified(), fromCache = true) else null
    }

    private fun cacheFile(name: String) = File(cacheDir, "$name.parquet")

    private fun get(path: String): ByteArray {
        val conn = URL(baseUrl + path).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 45_000
        // Tables are cached on disk by this class; the HTTP cache is for map tiles.
        conn.useCaches = false
        conn.setRequestProperty("User-Agent", "ramp-ops-android")
        try {
            val code = conn.responseCode
            if (code != 200) {
                // FastAPI explains itself in a {"detail": ...} body.
                val body = conn.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                val detail = runCatching { JSONObject(body).optString("detail") }.getOrNull()
                throw ApiException(detail?.takeIf { it.isNotBlank() } ?: "HTTP $code for $path")
            }
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        fun parseLive(json: String): LiveFeed {
            val o = JSONObject(json)
            val list = o.optJSONArray("aircraft")
            val aircraft = buildList {
                if (list != null) for (i in 0 until list.length()) {
                    val a = list.getJSONObject(i)
                    if (a.isNull("lat") || a.isNull("lon")) continue
                    add(
                        LiveAircraft(
                            icao24 = a.optString("icao24", "").ifEmpty { "?" },
                            callsign = a.optStringOrNull("callsign")?.trim()?.ifEmpty { null },
                            lat = a.getDouble("lat"),
                            lon = a.getDouble("lon"),
                            altFt = if (a.isNull("alt_ft")) 0 else a.optDouble("alt_ft", 0.0).toInt(),
                            onGround = a.optBoolean("on_ground", false),
                            speedKt = a.optNumber("speed_kt")?.toInt(),
                            trackDeg = a.optNumber("track_deg")?.toDouble(),
                            vrateFpm = a.optNumber("vrate_fpm")?.toInt(),
                            dir = a.optStringOrNull("dir"),
                            isFreighter = if (a.isNull("is_freighter")) null else a.optBoolean("is_freighter"),
                        ),
                    )
                }
            }
            val at = if (o.has("time")) o.getLong("time") * 1000 else System.currentTimeMillis()
            return LiveFeed(at = at, source = o.optString("source", ""), aircraft = aircraft)
        }

        private fun JSONObject.optStringOrNull(key: String): String? = if (isNull(key)) null else optString(key)

        private fun JSONObject.optNumber(key: String): Number? =
            if (isNull(key)) null else (opt(key) as? Number)
    }
}
