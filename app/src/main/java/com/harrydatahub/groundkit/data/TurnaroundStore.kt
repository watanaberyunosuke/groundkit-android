package com.harrydatahub.groundkit.data

import com.harrydatahub.groundkit.domain.Turnaround
import com.harrydatahub.groundkit.domain.TurnaroundStep
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Turnarounds, the Android counterpart of the iOS app's Turnaround model. Kept in a JSON file
 * on the device, newest first; like shifts, restored by Auto Backup but not synced live.
 */
class TurnaroundStore(dir: File) {
    private val file = File(dir, "turnarounds.json")
    private val state = MutableStateFlow(load())
    val turnarounds: StateFlow<List<Turnaround>> = state.asStateFlow()

    @Synchronized
    fun add(t: Turnaround) = update { listOf(t) + it }

    @Synchronized
    fun edit(id: String, change: (Turnaround) -> Turnaround) = update { all -> all.map { if (it.id == id) change(it) else it } }

    /** Stamps the step done now, or clears it. */
    @Synchronized
    fun setDone(id: String, step: TurnaroundStep, at: Long?) = edit(id) {
        it.copy(done = if (at == null) it.done - step.name else it.done + (step.name to at))
    }

    @Synchronized
    fun delete(id: String) = update { all -> all.filter { it.id != id } }

    private fun update(change: (List<Turnaround>) -> List<Turnaround>) {
        val next = change(state.value)
        if (next == state.value) return
        state.value = next
        save(next)
    }

    private fun load(): List<Turnaround> = runCatching { decode(JSONObject(file.readText())) }.getOrDefault(emptyList())

    private fun save(all: List<Turnaround>) {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(encode(all).toString())
        tmp.renameTo(file)
    }

    companion object {
        // Closed turnarounds kept on the device; open ones are all kept.
        private const val KEEP_CLOSED = 100

        fun encode(all: List<Turnaround>): JSONObject = JSONObject().put(
            "turnarounds",
            JSONArray((all.filter { !it.isClosed } + all.filter { it.isClosed }.take(KEEP_CLOSED))
                .sortedByDescending { it.createdAt }
                .map { t ->
                    JSONObject().put("id", t.id).put("airport", t.airportIcao).put("callsign", t.callsign)
                        .put("flightIata", t.flightIata ?: JSONObject.NULL).put("airline", t.airline ?: JSONObject.NULL)
                        .put("origin", t.origin ?: JSONObject.NULL).put("destination", t.destination ?: JSONObject.NULL)
                        .put("stand", t.stand).put("registration", t.registration).put("createdAt", t.createdAt)
                        .put("targetOffBlock", t.targetOffBlock ?: JSONObject.NULL)
                        .put("dangerousGoods", t.hasDangerousGoods)
                        .put("bagsOffloaded", t.bagsOffloaded).put("bagsLoaded", t.bagsLoaded)
                        .put("uldsOffloaded", t.uldsOffloaded).put("uldsLoaded", t.uldsLoaded)
                        .put("notes", t.notes).put("closedAt", t.closedAt ?: JSONObject.NULL)
                        .put("done", JSONObject(t.done))
                }),
        )

        fun decode(o: JSONObject): List<Turnaround> {
            fun JSONObject.str(k: String) = if (isNull(k)) null else getString(k)
            fun JSONObject.long(k: String) = if (isNull(k)) null else getLong(k)
            val arr = o.optJSONArray("turnarounds") ?: JSONArray()
            return (0 until arr.length()).map { i ->
                val t = arr.getJSONObject(i)
                val done = t.optJSONObject("done") ?: JSONObject()
                Turnaround(
                    id = t.getString("id"), airportIcao = t.getString("airport"), callsign = t.optString("callsign"),
                    flightIata = t.str("flightIata"), airline = t.str("airline"),
                    origin = t.str("origin"), destination = t.str("destination"),
                    stand = t.optString("stand"), registration = t.optString("registration"),
                    createdAt = t.getLong("createdAt"), targetOffBlock = t.long("targetOffBlock"),
                    hasDangerousGoods = t.optBoolean("dangerousGoods"),
                    bagsOffloaded = t.optInt("bagsOffloaded"), bagsLoaded = t.optInt("bagsLoaded"),
                    uldsOffloaded = t.optInt("uldsOffloaded"), uldsLoaded = t.optInt("uldsLoaded"),
                    notes = t.optString("notes"), closedAt = t.long("closedAt"),
                    done = done.keys().asSequence().associateWith { done.getLong(it) },
                )
            }
        }
    }
}
