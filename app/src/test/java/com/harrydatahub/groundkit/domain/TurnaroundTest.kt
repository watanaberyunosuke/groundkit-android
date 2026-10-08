package com.harrydatahub.groundkit.domain

import com.harrydatahub.groundkit.data.TurnaroundStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class TurnaroundTest {
    private fun turnaround(dg: Boolean = false, done: Map<String, Long> = emptyMap()) =
        Turnaround(id = "t", airportIcao = "VHHH", callsign = "CPA101", createdAt = 0, hasDangerousGoods = dg, done = done)

    @Test
    fun notocOnlyWithDangerousGoods() {
        assertEquals(17, turnaround().steps.size)
        assertFalse(TurnaroundStep.NOTOC in turnaround().steps)
        assertEquals(18, turnaround(dg = true).steps.size)
        assertTrue(TurnaroundStep.NOTOC in turnaround(dg = true).steps)
    }

    @Test
    fun nextStepAndProgressFollowTheOrder() {
        val t = turnaround(done = mapOf("CHOCKS_ON" to 1_000L, "GPU_CONNECTED" to 2_000L))
        assertEquals(TurnaroundStep.CONES_PLACED, t.nextStep)
        assertEquals(2f / 17, t.progress, 1e-6f)
        assertEquals(1_000L, t.onBlocksAt)
        // A step from a newer app is kept but does not count.
        val newer = turnaround(done = mapOf("DE_ICED" to 5L))
        assertEquals(0f, newer.progress, 0f)
        assertEquals(TurnaroundStep.CHOCKS_ON, newer.nextStep)
        val all = turnaround(done = TurnaroundStep.entries.associate { it.name to 1L })
        assertNull(all.nextStep)
        assertEquals(1f, all.progress, 0f)
    }

    @Test
    fun labelPrefersTheFlightNumber() {
        assertEquals("CPA101", turnaround().label)
        assertEquals("CX101", turnaround().copy(flightIata = "CX101").label)
        assertEquals("Turnaround", turnaround().copy(callsign = "").label)
    }

    @Test
    fun offBlockTimeIsTodayOrTomorrowAtTheAirport() {
        val hkg = ZoneId.of("Asia/Hong_Kong")
        fun at(h: Int, m: Int, day: Int = 8) = ZonedDateTime.of(2026, 10, day, h, m, 0, 0, hkg).toInstant().toEpochMilli()
        val now = at(14, 0)
        assertEquals(at(14, 45), Turnarounds.offBlockAt(LocalTime.of(14, 45), hkg, now))
        // Slightly in the past stays today (a late departure), not tomorrow.
        assertEquals(at(13, 30), Turnarounds.offBlockAt(LocalTime.of(13, 30), hkg, now))
        // 23:30 picking 00:15 means after midnight.
        assertEquals(at(0, 15, day = 9), Turnarounds.offBlockAt(LocalTime.of(0, 15), hkg, at(23, 30)))
    }

    @Test
    fun turnaroundsSurviveARestart() {
        val dir = Files.createTempDirectory("turnarounds").toFile()
        val store = TurnaroundStore(dir)
        store.add(turnaround().copy(id = "a", origin = "SIN", stand = "N5", targetOffBlock = 9_000, createdAt = 1))
        store.add(turnaround(dg = true).copy(id = "b", createdAt = 2))
        store.setDone("a", TurnaroundStep.CHOCKS_ON, 1_000)
        store.setDone("a", TurnaroundStep.CONES_PLACED, 1_100)
        store.setDone("a", TurnaroundStep.CONES_PLACED, null) // undone
        store.edit("a") { it.copy(bagsOffloaded = 112, uldsLoaded = 4, notes = "Dent by door 5L", closedAt = 8_000) }
        store.delete("b")

        val a = TurnaroundStore(dir).turnarounds.value.single()
        assertEquals("SIN", a.origin)
        assertNull(a.destination)
        assertEquals("N5", a.stand)
        assertEquals(9_000L, a.targetOffBlock)
        assertEquals(mapOf("CHOCKS_ON" to 1_000L), a.done)
        assertEquals(112, a.bagsOffloaded)
        assertEquals(4, a.uldsLoaded)
        assertEquals("Dent by door 5L", a.notes)
        assertTrue(a.isClosed)
    }
}
