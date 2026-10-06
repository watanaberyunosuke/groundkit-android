package com.harrydatabub.motherduck_aviation_data_android.domain

import com.harrydatabub.motherduck_aviation_data_android.data.ShiftStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ShiftTest {
    @Test
    fun heatIndexAndWindChillMatchTheIosApp() {
        // RampAdvisorTests.swift: 35 °C with a 25 °C dew point feels like about 43 °C.
        assertEquals(43.3, HeatStress.heatIndexC(35.0, 25.0), 0.5)
        // Anchorage in winter: -20 °C in 20 kt is about -33 °C.
        assertEquals(-33.0, HeatStress.windChillC(-20.0, 20.0), 2.0)
        // Mild weather is just the temperature.
        assertEquals(20.0, HeatStress.feelsLikeC(20.0, 10.0, 10)!!, 1e-9)
        assertEquals(15.0, HeatStress.windChillC(15.0, 30.0), 1e-9)
        assertNull(HeatStress.feelsLikeC(null, 10.0, 10))
    }

    @Test
    fun waterTargetRisesWithTheHeat() {
        assertEquals(300.0, ShiftAdvice.waterPerHourMl(null), 0.0)
        assertEquals(300.0, ShiftAdvice.waterPerHourMl(20.0), 0.0)
        assertEquals(500.0, ShiftAdvice.waterPerHourMl(28.0), 0.0)
        assertEquals(750.0, ShiftAdvice.waterPerHourMl(36.0), 0.0)
        // At least an hour's worth, then pro rata.
        assertEquals(500.0, ShiftAdvice.waterTargetMl(28.0, 0.25), 0.0)
        assertEquals(1500.0, ShiftAdvice.waterTargetMl(36.0, 2.0), 0.0)
    }

    @Test
    fun breakPromptEveryTwoHours() {
        assertFalse(ShiftAdvice.breakDue(119))
        assertTrue(ShiftAdvice.breakDue(120))
        assertTrue(ShiftAdvice.breakDue(134))
        assertFalse(ShiftAdvice.breakDue(135))
        assertTrue(ShiftAdvice.breakDue(245))
    }

    @Test
    fun recordsSurviveARestart() {
        val dir = Files.createTempDirectory("records").toFile()
        val store = ShiftStore(dir)
        store.startShift("VHHH", now = 1_000)
        store.startShift("YSSY", now = 2_000) // already on shift: ignored
        store.addWater(250.0)
        store.addWater(500.0)
        store.addNote("VHHH", "  GPU 3 faulty  ", important = true, now = 1_500)
        store.addNote("VHHH", "   ", important = false) // blank: ignored
        val noteId = store.records.value.notes.single().id

        val reopened = ShiftStore(dir).records.value
        val shift = reopened.activeShift!!
        assertEquals("VHHH", shift.airportIcao)
        assertEquals(750.0, shift.waterMl, 0.0)
        assertEquals("GPU 3 faulty", reopened.notes.single().text)
        assertTrue(reopened.notes.single().isImportant)

        store.resolveNote(noteId, now = 3_000)
        store.endShift(now = 4_000)
        val after = ShiftStore(dir).records.value
        assertNull(after.activeShift)
        assertEquals(3_000L, after.shifts.single().durationMs(0))
        assertEquals(3_000L, after.notes.single().resolvedAt)
    }
}
