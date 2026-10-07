package com.harrydatabub.motherduck_aviation_data_android.domain

import com.harrydatabub.motherduck_aviation_data_android.data.Airline
import com.harrydatabub.motherduck_aviation_data_android.data.AviationApi
import com.harrydatabub.motherduck_aviation_data_android.data.Conditions
import com.harrydatabub.motherduck_aviation_data_android.data.FlightFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RampAlertsAndOperatorsTest {
    private val now = 1_790_000_000_000L

    private fun metar(
        wind: Int? = 10, gust: Int? = null, wx: String? = null, cat: String? = "VFR", temp: Double? = 20.0,
        ageMin: Int = 20,
    ) = Conditions(
        icao = "VHHH", iata = "HKG", lat = 22.3, lon = 113.9,
        metarObservedAt = now - ageMin * MINUTE_MS, metarRaw = "METAR VHHH ...", flightCategory = cat,
        windVariable = false, windDirDeg = 240, windSpeedKt = wind, windGustKt = gust,
        visibilitySm = 6.0, visibilityIsLowerBound = true, ceilingFt = null, wxString = wx,
        tempC = temp, dewpointC = 15.0, altimeterHpa = 1012.0,
        tafIssuedAt = null, tafValidFrom = null, tafValidTo = null, tafRaw = null, notamsInForce = 3,
    )

    private fun alerts(c: Conditions) = RampAlerts.from(c, gustCautionKt = 25, highWindKt = 35, now = now)

    @Test
    fun benignWeatherHasNoAlerts() {
        assertTrue(alerts(metar()).isEmpty())
    }

    @Test
    fun thunderstormIsAWarningAndVicinityIsNamed() {
        val at = alerts(metar(wx = "+TSRA")).first()
        assertEquals(AlertLevel.WARNING, at.level)
        assertEquals("Thunderstorm at the airport", at.title)
        assertEquals("Thunderstorm in the vicinity", alerts(metar(wx = "VCTS")).first().title)
    }

    @Test
    fun windThresholdsFollowSettings() {
        assertEquals(AlertLevel.CAUTION, alerts(metar(wind = 18, gust = 28)).single().level)
        assertEquals(AlertLevel.WARNING, alerts(metar(wind = 36)).single().level)
        assertEquals(AlertLevel.WARNING, alerts(metar(wind = 20, gust = 38)).single().level)
        assertTrue(RampAlerts.from(metar(wind = 18, gust = 28), 30, 40, now).isEmpty())
    }

    @Test
    fun visibilityFreezingHeatAndStaleData() {
        assertEquals(AlertKind.VISIBILITY, alerts(metar(cat = "LIFR")).single().kind)
        assertEquals(AlertLevel.WARNING, alerts(metar(cat = "LIFR")).single().level)
        assertEquals(AlertLevel.CAUTION, alerts(metar(cat = "IFR")).single().level)
        assertEquals(AlertKind.FREEZING, alerts(metar(temp = -2.0)).single().kind)
        assertEquals(AlertLevel.WARNING, alerts(metar(wx = "FZFG", temp = -1.0)).first().level)
        // Heat goes by heat index: 35 °C with a 25 °C dew point feels like 43 °C.
        assertEquals(AlertLevel.WARNING, alerts(metar(temp = 35.0).copy(dewpointC = 25.0)).single().level)
        assertEquals(AlertKind.HEAT, alerts(metar(temp = 32.0).copy(dewpointC = 18.0)).single().kind)
        assertEquals(AlertKind.COLD, alerts(metar(wind = 20, temp = -20.0).copy(dewpointC = -25.0)).first { it.kind != AlertKind.FREEZING }.kind)
        assertEquals(AlertKind.STALE, alerts(metar(ageMin = 180)).single().kind)
        assertEquals(AlertKind.PRECIPITATION, alerts(metar(wx = "-RA")).single().kind)
    }

    @Test
    fun alertsAreOrderedMostSevereFirst() {
        val levels = alerts(metar(wx = "-RA", gust = 28, cat = "LIFR")).map { it.level }
        assertEquals(levels.sortedDescending(), levels)
    }

    @Test
    fun windAndVisibilityText() {
        assertEquals("240° 18 kt G28", windText(metar(wind = 18, gust = 28)))
        assertEquals("Calm", windText(metar(wind = 0)))
        assertEquals("10 km+", visText(metar()))
    }

    @Test
    fun myAirlinesAcceptIataAndIcaoCodes() {
        val airlines = mapOf(
            "CPA" to Airline("CPA", "CX", "Cathay Pacific"),
            "SIA" to Airline("SIA", "SQ", "Singapore Airlines"),
            "FDX" to Airline("FDX", "FX", "FedEx"),
        )
        assertEquals(setOf("CPA", "SIA", "FDX"), Operators.parseMine("cx, SQ fdx", airlines))
        assertEquals(emptySet<String>(), Operators.parseMine("  ", airlines))
        val mine = setOf("CPA")
        assertTrue(Operators.matches("CPA101", FlightFilter.MINE, mine))
        assertFalse(Operators.matches("SIA1", FlightFilter.MINE, mine))
        val codes = FlightCodes(airlines, cargoOperators = setOf("FDX"))
        assertTrue(codes.isFreighter("FDX5150"))
        assertFalse(codes.isFreighter("CPA101"))
        assertFalse(codes.isFreighter("B1234")) // registration, not an airline
        // Passenger flights carry belly cargo, so no filter hides them for being passenger.
        assertTrue(Operators.matches("CPA101", FlightFilter.ALL, mine))
        assertTrue(Operators.matches("FDX5150", FlightFilter.ALL, mine))
        assertTrue(Operators.matches(null, FlightFilter.ALL, mine))
    }

    @Test
    fun parsesTheLiveEndpoint() {
        val feed = AviationApi.parseLive(
            """{"time":1791196902,"source":"adsb.lol","failed":["OpenSky: HTTP 429"],"aircraft":[
              {"icao24":"76b455","callsign":"SIA194 ","lon":105.6,"lat":21.2,"alt_ft":3975,"on_ground":false,"speed_kt":180,"track_deg":21.15,"vrate_fpm":-960},
              {"icao24":"780a1b","callsign":null,"lon":113.9,"lat":22.3,"alt_ft":0,"on_ground":true,"speed_kt":null,"track_deg":null,"vrate_fpm":null}]}""",
        )
        assertEquals(1_791_196_902_000L, feed.at)
        assertEquals("adsb.lol", feed.source)
        assertEquals(2, feed.aircraft.size)
        assertEquals("SIA194", feed.aircraft[0].callsign)
        assertEquals(-960, feed.aircraft[0].vrateFpm)
        assertNull(feed.aircraft[1].callsign)
        assertNull(feed.aircraft[1].speedKt)
        assertTrue(feed.aircraft[1].onGround)
    }
}
