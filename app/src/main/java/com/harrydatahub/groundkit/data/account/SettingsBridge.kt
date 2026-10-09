package com.harrydatahub.groundkit.data.account

import com.harrydatahub.groundkit.data.Settings
import com.harrydatahub.groundkit.data.ThemeMode

/**
 * The app's own settings as synced values, and back. Airlines, the flight filter and age
 * stay on this device; glove mode and the dashboard keys belong to the other clients.
 */
object SettingsBridge {
    val KEYS = listOf(SettingKey.AIRPORT, SettingKey.KEEP_AWAKE, SettingKey.APPEARANCE, SettingKey.WIND_CAUTION_KT, SettingKey.WIND_WARNING_KT)

    /** IATA to ICAO for the warehouse's airports; AppViewModel adds the loaded list. */
    private val codes = mutableMapOf(
        "SYD" to "YSSY", "MEL" to "YMML", "BNE" to "YBBN", "SIN" to "WSSS", "HKG" to "VHHH", "AMS" to "EHAM", "ANC" to "PANC",
    )

    @Synchronized
    fun registerAirports(pairs: List<Pair<String, String>>) {
        pairs.forEach { (iata, icao) -> if (iata.isNotBlank() && icao.isNotBlank()) codes[iata] = icao }
    }

    @Synchronized
    private fun icaoOf(code: String): String? = if (code.length == 4) code else codes[code]

    @Synchronized
    private fun iataOf(icao: String): String = codes.entries.firstOrNull { it.value == icao }?.key ?: icao

    private val THEMES = mapOf(ThemeMode.SYSTEM to "auto", ThemeMode.SUNSET to "sunset", ThemeMode.LIGHT to "light", ThemeMode.DARK to "dark")

    fun read(s: Settings): Map<SettingKey, Any> = buildMap {
        icaoOf(s.airport)?.let { put(SettingKey.AIRPORT, it) }
        put(SettingKey.KEEP_AWAKE, s.keepScreenOn)
        put(SettingKey.APPEARANCE, THEMES.getValue(s.theme))
        put(SettingKey.WIND_CAUTION_KT, s.gustCautionKt)
        put(SettingKey.WIND_WARNING_KT, s.highWindKt)
    }

    /** This app's defaults, so first sign-in offers only what the person changed. */
    fun defaults(): Map<SettingKey, Any> = read(Settings())

    /** The synced values applied to [s]; keys the account does not have keep this device's value. */
    fun apply(s: Settings, synced: SyncedSettings): Settings {
        var next = s
        (synced[SettingKey.AIRPORT] as? String)?.let { icao -> if (icaoOf(s.airport) != icao) next = next.copy(airport = iataOf(icao)) }
        (synced[SettingKey.KEEP_AWAKE] as? Boolean)?.let { next = next.copy(keepScreenOn = it) }
        (synced[SettingKey.APPEARANCE] as? String)?.let { a -> THEMES.entries.firstOrNull { it.value == a }?.let { next = next.copy(theme = it.key) } }
        // The steppers' ranges: caution 15 to 60 kt, high wind 20 to 80 kt and not below caution.
        (synced[SettingKey.WIND_CAUTION_KT] as? Int)?.let { next = next.copy(gustCautionKt = it.coerceIn(15, 60)) }
        (synced[SettingKey.WIND_WARNING_KT] as? Int)?.let { next = next.copy(highWindKt = it.coerceIn(20, 80)) }
        if (next.highWindKt < next.gustCautionKt) next = next.copy(highWindKt = next.gustCautionKt.coerceAtLeast(20))
        return next
    }
}
