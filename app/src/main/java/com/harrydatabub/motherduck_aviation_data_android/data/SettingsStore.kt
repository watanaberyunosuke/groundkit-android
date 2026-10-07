package com.harrydatabub.motherduck_aviation_data_android.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Which flights the boards show. There is no cargo-only filter: passenger flights carry
 * belly cargo, so hiding them hides cargo work. Flights by the all-cargo operators the
 * backend lists (reference.cargo_operators) are tagged instead.
 */
enum class FlightFilter { ALL, MINE }

data class Settings(
    /** Selected airport, by IATA code (as the web Dive's ?airport= parameter). */
    val airport: String = "HKG",
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val keepScreenOn: Boolean = false,
    /** Airlines this user handles, as typed: IATA or ICAO codes, comma separated. */
    val myAirlines: String = "",
    val filter: FlightFilter = FlightFilter.ALL,
    /** Ramp wind alerts: caution from this gust, high-wind from this wind or gust. */
    val gustCautionKt: Int = 25,
    val highWindKt: Int = 35,
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(load())
    val settings: StateFlow<Settings> = state.asStateFlow()

    fun update(change: (Settings) -> Settings) {
        val next = change(state.value)
        state.value = next
        prefs.edit()
            .putString("airport", next.airport)
            .putString("theme", next.theme.name)
            .putBoolean("keepScreenOn", next.keepScreenOn)
            .putString("myAirlines", next.myAirlines)
            .putString("filter", next.filter.name)
            .putInt("gustCautionKt", next.gustCautionKt)
            .putInt("highWindKt", next.highWindKt)
            .apply()
    }

    private fun load(): Settings {
        val d = Settings()
        return Settings(
            airport = prefs.getString("airport", d.airport) ?: d.airport,
            theme = enumOr(prefs.getString("theme", null), d.theme),
            keepScreenOn = prefs.getBoolean("keepScreenOn", d.keepScreenOn),
            myAirlines = prefs.getString("myAirlines", d.myAirlines) ?: d.myAirlines,
            filter = enumOr(prefs.getString("filter", null), d.filter),
            gustCautionKt = prefs.getInt("gustCautionKt", d.gustCautionKt),
            highWindKt = prefs.getInt("highWindKt", d.highWindKt),
        )
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default
}
