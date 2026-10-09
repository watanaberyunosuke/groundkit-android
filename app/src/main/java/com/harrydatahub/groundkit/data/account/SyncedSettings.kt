package com.harrydatahub.groundkit.data.account

import org.json.JSONObject
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * The settings synced through a GroundKit account, shared with the iOS app and the web
 * dashboard (contract: the aviation repo's docs/accounts.md section 3).
 *
 * The app keeps the values, when each key last changed (its stamp) and the keys changed
 * since the last successful sync (pending). A sync sends the pending keys to the
 * merge_settings function, which keeps the newer stamp per key, and adopts what it returns.
 * Values are String, Int or Boolean.
 */
enum class SettingKey(val id: String) {
    AIRPORT("airport"), // home airport, ICAO
    GLOVE_MODE("gloveMode"), // iOS only; kept for it
    KEEP_AWAKE("keepAwake"),
    APPEARANCE("appearance"), // auto | sunset | light | dark
    WIND_CAUTION_KT("windCautionKt"),
    WIND_WARNING_KT("windWarningKt"),
    DASHBOARD_THEME("dashboardTheme"),
    DASHBOARD_HOME_TIME_ZONE("dashboardHomeTimeZone"),
    DASHBOARD_LAYOUT("dashboardLayout");

    fun isValid(v: Any?): Boolean = when (this) {
        AIRPORT -> v is String && Regex("^[A-Z0-9]{3,4}$").matches(v)
        GLOVE_MODE, KEEP_AWAKE -> v is Boolean
        APPEARANCE -> v in setOf("auto", "sunset", "light", "dark")
        WIND_CAUTION_KT, WIND_WARNING_KT -> v is Int && v in 5..100
        DASHBOARD_THEME -> v in setOf("auto", "light", "dark")
        DASHBOARD_HOME_TIME_ZONE -> v is String && v.length <= 64
        DASHBOARD_LAYOUT -> v is String && v.length <= 1000
    }

    companion object {
        fun of(id: String): SettingKey? = entries.firstOrNull { it.id == id }
    }
}

data class SyncedSettings(
    val values: Map<String, Any> = emptyMap(),
    val stamps: Map<String, String> = emptyMap(),
    val pending: List<String> = emptyList(),
) {
    operator fun get(key: SettingKey): Any? = values[key.id]

    /** A change made on this device: the new value (null removes it), stamped [now]. */
    fun change(key: SettingKey, value: Any?, now: Instant = Instant.now()): SyncedSettings = copy(
        values = if (value == null) values - key.id else values + (key.id to value),
        stamps = stamps + (key.id to stamp(now)),
        pending = if (key.id in pending) pending else pending + key.id,
    )

    /** The body for merge_settings: pending keys, null for removed ones. */
    fun patch(): JSONObject {
        val patch = JSONObject()
        val patchStamps = JSONObject()
        for (k in pending) {
            patch.put(k, values[k] ?: JSONObject.NULL)
            stamps[k]?.let { patchStamps.put(k, it) }
        }
        return JSONObject().put("patch", patch).put("patch_stamps", patchStamps)
    }

    /** The stamps sent in [patch], to tell which keys changed again while it was out. */
    fun sentStamps(): Map<String, String> = pending.mapNotNull { k -> stamps[k]?.let { k to it } }.toMap()

    /**
     * Adopts the server's merged settings. Keys changed again while the request was in
     * flight (their stamp differs from what was sent) stay local and pending.
     */
    fun adopting(server: JSONObject, sent: Map<String, String>): SyncedSettings {
        val values = clean(server.optJSONObject("settings")).toMutableMap()
        val stamps = mutableMapOf<String, String>()
        server.optJSONObject("stamps")?.let { s ->
            for (k in s.keys()) if (SettingKey.of(k) != null) s.optString(k).takeIf { it.isNotEmpty() }?.let { stamps[k] = it }
        }
        val stillPending = pending.filter { this.stamps[it] != sent[it] }
        for (k in stillPending) {
            val v = this.values[k]
            if (v == null) values.remove(k) else values[k] = v
            this.stamps[k]?.let { stamps[k] = it }
        }
        return SyncedSettings(values, stamps, stillPending)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("values", JSONObject(values))
        .put("stamps", JSONObject(stamps))
        .put("pending", org.json.JSONArray(pending))

    companion object {
        fun stamp(at: Instant): String = at.truncatedTo(ChronoUnit.MILLIS).toString() // 2026-10-09T10:00:00.123Z

        /** Known keys with valid values; anything else (a newer client's keys) is left out. */
        fun clean(raw: JSONObject?): Map<String, Any> {
            raw ?: return emptyMap()
            val out = mutableMapOf<String, Any>()
            for (k in raw.keys()) {
                val key = SettingKey.of(k) ?: continue
                val v = when (val x = raw.opt(k)) {
                    is Number -> if (x.toDouble() % 1.0 == 0.0) x.toInt() else null
                    is String, is Boolean -> x
                    else -> null
                }
                if (v != null && key.isValid(v)) out[k] = v
            }
            return out
        }

        fun fromJson(o: JSONObject?): SyncedSettings {
            o ?: return SyncedSettings()
            val stamps = mutableMapOf<String, String>()
            o.optJSONObject("stamps")?.let { s -> for (k in s.keys()) stamps[k] = s.optString(k) }
            val pending = o.optJSONArray("pending")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
            return SyncedSettings(clean(o.optJSONObject("values")), stamps, pending.filter { SettingKey.of(it) != null })
        }
    }
}
