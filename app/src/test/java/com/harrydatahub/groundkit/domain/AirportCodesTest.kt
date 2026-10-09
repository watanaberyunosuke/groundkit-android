package com.harrydatahub.groundkit.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AirportCodesTest {
    @Test
    fun keepsThreeLetterCodes() {
        assertEquals("SIN", AirportCodes.iata("SIN"))
    }

    @Test
    fun mapsRecodedAirports() {
        assertEquals("MCY", AirportCodes.iata("YBMC"))
        assertEquals("DAC", AirportCodes.iata("VGZR"))
    }

    @Test
    fun dropsUnknownIcaoCodes() {
        assertNull(AirportCodes.iata("YXYZ"))
        assertNull(AirportCodes.iata(null))
    }

    @Test
    fun usualExposesTheBoardCode() {
        assertEquals("MCY", Usual("YBMC", 600.0, 14, 14, emptyList()).otherIata)
    }
}
