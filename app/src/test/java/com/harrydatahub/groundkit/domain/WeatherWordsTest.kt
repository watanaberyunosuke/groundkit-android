package com.harrydatahub.groundkit.domain

import com.harrydatahub.groundkit.data.Airport
import com.harrydatahub.groundkit.data.AviationApi
import com.harrydatahub.groundkit.data.Conditions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.OffsetDateTime

/** Live weather from /api/weather and its plain wording; the same cases as iOS's LiveWeatherTests. */
class WeatherWordsTest {
    private val json = """
    {"time": 1791872569, "sources": {"aviation": "aviationweather.gov", "local": "Open-Meteo"},
     "metar": {"metar_observed_at": "2026-10-13T06:00:00+00:00",
       "metar_raw": "METAR VHHH 130600Z 28005KT 7000 FEW020 SCT030 31/23 Q1015 NOSIG", "flight_category": "MVFR",
       "wind_variable": false, "wind_dir_deg": 280, "wind_speed_kt": 5, "wind_gust_kt": null, "visibility_sm": 4.35,
       "visibility_is_lower_bound": false, "ceiling_ft": null, "wx_string": null, "temp_c": 31.0, "dewpoint_c": 23.0,
       "altimeter_hpa": 1015.0},
     "taf": {"taf_issued_at": "2026-10-13T05:00:00+00:00", "taf_valid_from": "2026-10-13T06:00:00+00:00",
       "taf_valid_to": "2026-10-14T12:00:00+00:00", "taf_raw": "TAF VHHH 130500Z 1306/1412 27010KT 7000 FEW030"},
     "local": {"observed_at": "2026-10-13T06:15:00+00:00", "conditions": "Clear", "weather_code": 0, "is_day": true,
       "temp_c": 29.6, "feels_like_c": 34.4, "humidity_pct": 65, "dewpoint_c": 22.4, "precipitation_mm": 0.0,
       "cloud_cover_pct": 6, "pressure_hpa": 1015.7, "wind_dir_deg": 268, "wind_speed_kt": 3, "wind_gust_kt": 6},
     "failed": []}
    """

    private val hkg = Airport("VHHH", "HKG", "Hong Kong International", "HK", 22.309, 113.915, "Asia/Hong_Kong", null)

    private fun at(iso: String) = OffsetDateTime.parse(iso).toInstant().toEpochMilli()

    private fun hourly(observed: String, windDir: Int = 90) = Conditions(
        icao = "VHHH", iata = "HKG", lat = 22.3, lon = 113.9,
        metarObservedAt = at(observed), metarRaw = "METAR VHHH 130030Z VRB03KT 9999 FEW025 28/22 Q1018 NOSIG",
        flightCategory = "VFR", windVariable = false, windDirDeg = windDir, windSpeedKt = 3, windGustKt = null,
        visibilitySm = 6.0, visibilityIsLowerBound = true, ceilingFt = null, wxString = "-RA",
        tempC = 28.0, dewpointC = 22.0, altimeterHpa = 1018.0,
        tafIssuedAt = null, tafValidFrom = null, tafValidTo = null, tafRaw = null, notamsInForce = 5,
    )

    @Test
    fun parsesTheWeatherResponse() {
        val w = AviationApi.parseWeather(json)
        assertEquals(1_791_872_569_000L, w.at)
        assertEquals(280, w.metar?.windDirDeg)
        assertEquals("MVFR", w.metar?.flightCategory)
        assertEquals(at("2026-10-13T05:00:00+00:00"), w.taf?.issuedAt)
        assertEquals(34.4, w.local?.feelsLikeC!!, 0.0)
        assertEquals(65, w.local?.humidityPct)
        assertNull(w.metar?.ceilingFt)
    }

    @Test
    fun newerLiveMetarReplacesTheHourlyCopy() {
        val c = WeatherWords.merged(hourly("2026-10-13T00:30:00Z"), AviationApi.parseWeather(json), hkg)!!
        assertEquals(280, c.windDirDeg)
        assertNull(c.wxString)
        assertEquals(31.0, c.tempC!!, 0.0)
        assertEquals(at("2026-10-13T05:00:00Z"), c.tafIssuedAt)
        assertEquals(5L, c.notamsInForce) // not part of the live weather
    }

    @Test
    fun olderLiveMetarKeepsTheHourlyCopy() {
        val c = WeatherWords.merged(hourly("2026-10-13T06:30:00Z", windDir = 90), AviationApi.parseWeather(json), hkg)!!
        assertEquals(90, c.windDirDeg)
    }

    @Test
    fun liveWeatherAloneWithoutTheHourlyCopy() {
        val c = WeatherWords.merged(null, AviationApi.parseWeather(json), hkg)
        assertNotNull(c)
        assertEquals("HKG", c!!.iata)
        assertEquals(1015.0, c.altimeterHpa!!, 0.0)
        assertNull(WeatherWords.merged(null, null, hkg))
    }

    @Test
    fun plainWords() {
        assertEquals("Light rain showers, mist", WeatherWords.describe("-SHRA BR"))
        assertEquals("Thunderstorm with rain nearby", WeatherWords.describe("VCTSRA"))
        assertNull(WeatherWords.describe(" "))
        assertEquals("280° 5 kt", WeatherWords.wind(280, false, 5, null))
        assertEquals("Variable 3 kt, gusts 15", WeatherWords.wind(null, true, 3, 15))
        assertEquals("the west", WeatherWords.compassName(280))
        assertEquals("the north-east", WeatherWords.compassName(44))
        assertEquals("7 km", WeatherWords.visibility(4.35, false))
        assertEquals("1015 hPa", WeatherWords.pressure(1015.4))
        assertEquals("3 h 5 min early", ragText(-185.0))
        assertEquals("20 min late", ragText(20.0))
        assertEquals("On time", ragText(5.0))
    }
}
