package com.harrydatabub.motherduck_aviation_data_android.ui.map

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Esri's grey canvas basemap, the same tiles the web Dive draws (no API key). Tiles load
 * in the background; [version] changes whenever one arrives so the map redraws. The
 * HTTP response cache (installed in MainActivity) keeps them on disk between visits.
 */
class TileCache(private val scope: CoroutineScope) {
    private val memory = LruCache<String, ImageBitmap>(160)
    private val inFlight = HashSet<String>()
    private val failedAt = HashMap<String, Long>()
    private val permits = Semaphore(6)

    /** Read in the draw phase, so a newly loaded tile triggers a redraw. */
    val version = mutableIntStateOf(0)

    fun url(dark: Boolean, z: Int, x: Int, y: Int): String =
        "https://services.arcgisonline.com/ArcGIS/rest/services/Canvas/World_${if (dark) "Dark" else "Light"}" +
            "_Gray_Base/MapServer/tile/$z/$y/$x"

    /** The tile if loaded; otherwise starts loading it and returns null. */
    fun get(url: String): ImageBitmap? {
        memory.get(url)?.let { return it }
        synchronized(inFlight) {
            if (url in inFlight) return null
            failedAt[url]?.let { if (System.currentTimeMillis() - it < RETRY_MS) return null }
            inFlight += url
        }
        scope.launch(Dispatchers.IO) {
            try {
                permits.withPermit {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 15_000
                    conn.useCaches = true
                    conn.setRequestProperty("User-Agent", "ramp-ops-android")
                    try {
                        val bytes = conn.inputStream.use { it.readBytes() }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()?.let { memory.put(url, it) }
                    } finally {
                        conn.disconnect()
                    }
                }
            } catch (_: Exception) {
                synchronized(inFlight) { failedAt[url] = System.currentTimeMillis() }
            } finally {
                synchronized(inFlight) { inFlight -= url }
                withContext(Dispatchers.Main) { version.intValue++ }
            }
        }
        return null
    }

    companion object {
        private const val RETRY_MS = 30_000L
        /** Esri's grey canvas has tiles to level 16. */
        const val MAX_LEVEL = 16
    }
}
