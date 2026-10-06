package com.harrydatabub.motherduck_aviation_data_android.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Shift(
    val id: String,
    val airportIcao: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    /** Water logged in the app this shift. */
    val waterMl: Double = 0.0,
) {
    val isActive get() = endedAt == null
    fun durationMs(now: Long) = (endedAt ?: now) - startedAt
}

data class HandoverNote(
    val id: String,
    val airportIcao: String,
    val createdAt: Long,
    val text: String,
    val isImportant: Boolean,
    val resolvedAt: Long? = null,
)

data class CrewRecords(val shifts: List<Shift> = emptyList(), val notes: List<HandoverNote> = emptyList()) {
    val activeShift: Shift? get() = shifts.firstOrNull { it.isActive }
}

/**
 * The crew's own records, the Android counterpart of the iOS app's Shift and HandoverNote
 * models. Kept in one small JSON file on the device. iOS syncs them through iCloud; here
 * Android's Auto Backup restores them to a new phone, but they do not sync live.
 */
class ShiftStore(dir: File) {
    private val file = File(dir, "crew_records.json")
    private val state = MutableStateFlow(load())
    val records: StateFlow<CrewRecords> = state.asStateFlow()

    @Synchronized
    fun startShift(airportIcao: String, now: Long = System.currentTimeMillis()) = update { r ->
        if (r.activeShift != null) r
        else r.copy(shifts = listOf(Shift(UUID.randomUUID().toString(), airportIcao, now)) + r.shifts)
    }

    @Synchronized
    fun endShift(now: Long = System.currentTimeMillis()) = update { r ->
        r.copy(shifts = r.shifts.map { if (it.isActive) it.copy(endedAt = now) else it })
    }

    @Synchronized
    fun addWater(ml: Double) = update { r ->
        r.copy(shifts = r.shifts.map { if (it.isActive) it.copy(waterMl = it.waterMl + ml) else it })
    }

    @Synchronized
    fun addNote(airportIcao: String, text: String, important: Boolean, now: Long = System.currentTimeMillis()) = update { r ->
        val note = HandoverNote(UUID.randomUUID().toString(), airportIcao, now, text.trim(), important)
        if (note.text.isEmpty()) r else r.copy(notes = listOf(note) + r.notes)
    }

    @Synchronized
    fun resolveNote(id: String, now: Long = System.currentTimeMillis()) = update { r ->
        r.copy(notes = r.notes.map { if (it.id == id) it.copy(resolvedAt = now) else it })
    }

    private fun update(change: (CrewRecords) -> CrewRecords) {
        val next = change(state.value)
        if (next == state.value) return
        state.value = next
        save(next)
    }

    private fun load(): CrewRecords = runCatching { decode(JSONObject(file.readText())) }.getOrDefault(CrewRecords())

    private fun save(r: CrewRecords) {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(encode(r).toString())
        tmp.renameTo(file)
    }

    companion object {
        // Ended shifts and resolved notes kept on the device.
        private const val KEEP_SHIFTS = 60
        private const val KEEP_RESOLVED_NOTES = 50

        fun encode(r: CrewRecords): JSONObject = JSONObject()
            .put("shifts", JSONArray(r.shifts.take(KEEP_SHIFTS).map { s ->
                JSONObject().put("id", s.id).put("airport", s.airportIcao).put("startedAt", s.startedAt)
                    .put("endedAt", s.endedAt ?: JSONObject.NULL).put("waterMl", s.waterMl)
            }))
            .put("notes", JSONArray((r.notes.filter { it.resolvedAt == null } +
                r.notes.filter { it.resolvedAt != null }.take(KEEP_RESOLVED_NOTES)).map { n ->
                JSONObject().put("id", n.id).put("airport", n.airportIcao).put("createdAt", n.createdAt)
                    .put("text", n.text).put("important", n.isImportant)
                    .put("resolvedAt", n.resolvedAt ?: JSONObject.NULL)
            }))

        fun decode(o: JSONObject): CrewRecords {
            fun JSONObject.optLongOrNull(k: String) = if (isNull(k)) null else getLong(k)
            val shifts = o.optJSONArray("shifts") ?: JSONArray()
            val notes = o.optJSONArray("notes") ?: JSONArray()
            return CrewRecords(
                shifts = (0 until shifts.length()).map { i ->
                    val s = shifts.getJSONObject(i)
                    Shift(s.getString("id"), s.getString("airport"), s.getLong("startedAt"), s.optLongOrNull("endedAt"), s.optDouble("waterMl", 0.0))
                },
                notes = (0 until notes.length()).map { i ->
                    val n = notes.getJSONObject(i)
                    HandoverNote(
                        n.getString("id"), n.getString("airport"), n.getLong("createdAt"), n.getString("text"),
                        n.optBoolean("important"), n.optLongOrNull("resolvedAt"),
                    )
                },
            )
        }
    }
}
