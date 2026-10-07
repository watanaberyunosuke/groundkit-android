package com.harrydatahub.groundkit.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.abs

class WellbeingTest {
    private fun at(iso: String, zone: String) = LocalDateTime.parse(iso).atZone(ZoneId.of(zone)).toInstant().toEpochMilli()

    private fun assertNear(expected: Long, actual: Long?, toleranceMin: Int) {
        assertNotNull(actual)
        assertTrue("off by ${(actual!! - expected) / 60_000.0} min", abs(actual - expected) <= toleranceMin * MINUTE_MS)
    }

    @Test
    fun sunriseAndSunsetMatchPublishedTimes() {
        // Hong Kong Observatory, 21 June 2026: sunrise 05:39, sunset 19:11.
        val hkg = 22.308 to 113.918
        val morning = at("2026-06-21T03:00", "Asia/Hong_Kong")
        assertTrue(Solar.isDark(hkg.first, hkg.second, morning))
        val sunrise = Solar.nextChange(hkg.first, hkg.second, morning)
        assertNear(at("2026-06-21T05:39", "Asia/Hong_Kong"), sunrise, 3)
        assertFalse(Solar.isDark(hkg.first, hkg.second, sunrise!! + 5 * MINUTE_MS))
        assertNear(at("2026-06-21T19:11", "Asia/Hong_Kong"), Solar.nextChange(hkg.first, hkg.second, sunrise + MINUTE_MS), 3)

        // London Heathrow at the winter solstice: sunrise about 08:04, sunset about 15:54.
        val lhr = 51.47 to -0.4543
        val dawn = Solar.nextChange(lhr.first, lhr.second, at("2026-12-21T00:00", "Europe/London"))
        assertNear(at("2026-12-21T08:05", "Europe/London"), dawn, 4)
        assertNear(at("2026-12-21T15:54", "Europe/London"), Solar.nextChange(lhr.first, lhr.second, dawn!! + MINUTE_MS), 4)
    }

    @Test
    fun polarNightHasNoSunrise() {
        // Svalbard in mid-December: the sun stays down.
        val t = at("2026-12-15T12:00", "UTC")
        assertTrue(Solar.isDark(78.246, 15.466, t))
        assertNull(Solar.nextChange(78.246, 15.466, t))
    }

    private val h = HOUR_MS

    @Test
    fun priorSleepWakeChecks() {
        val start = 100 * DAY_MS // duty starts
        // 7 h last night, 7 h the night before: fine.
        val rested = listOf(SleepSpan(start - 9 * h, start - 2 * h), SleepSpan(start - 33 * h, start - 26 * h))
        val ok = Fatigue.assess(rested, emptyList(), start, start + h)
        assertEquals(7 * h, ok.sleep24hMs)
        assertEquals(14 * h, ok.sleep48hMs)
        assertEquals(3 * h, ok.awakeMs)
        assertTrue(ok.findings.isEmpty())

        // 4 h last night and 6 h before: both sleep rules fail.
        val short = listOf(SleepSpan(start - 6 * h, start - 2 * h), SleepSpan(start - 32 * h, start - 26 * h))
        val bad = Fatigue.assess(short, emptyList(), start, start + h)
        assertEquals(listOf("Under 5 h sleep in 24 h", "Under 12 h sleep in 48 h"), bad.findings.map { it.title })
        assertTrue(bad.findings.all { it.level == Level.WARNING })

        // Late in a long duty: awake longer than the 14 h slept in 48 h.
        val late = Fatigue.assess(rested, emptyList(), start, start + 13 * h)
        assertEquals("Awake longer than you've slept", late.findings.single().title)

        // No sleep permission, or nothing recorded (no tracker): unknown, not "no sleep".
        assertTrue(Fatigue.assess(null, emptyList(), start, start + h).findings.isEmpty())
        val untracked = Fatigue.assess(emptyList(), emptyList(), start, start + h)
        assertNull(untracked.sleep24hMs)
        assertTrue(untracked.findings.isEmpty())
        // Only an old night, outside the 48 h: also unknown.
        assertNull(Fatigue.assess(listOf(SleepSpan(start - 80 * h, start - 72 * h)), emptyList(), start, start + h).sleep48hMs)
    }

    @Test
    fun sleepOverlapsAndWindowEdgesCountOnce() {
        val spans = listOf(SleepSpan(0, 5 * h), SleepSpan(3 * h, 8 * h), SleepSpan(20 * h, 30 * h))
        assertEquals(8 * h, Fatigue.sleptBetween(spans, 0, 10 * h))
        assertEquals(12 * h, Fatigue.sleptBetween(spans, 2 * h, 26 * h))
    }

    @Test
    fun restAndWeeklyHours() {
        val now = 50 * DAY_MS
        val shifts = listOf(
            WorkSpan(now - 8 * h, null), // on shift now, after
            WorkSpan(now - 26 * h, now - 16 * h), // 8 h off: short rest
        ) + (2..6).map { d -> WorkSpan(now - d * DAY_MS - 10 * h, now - d * DAY_MS) }
        val f = Fatigue.assess(null, shifts, now - 8 * h, now)
        assertEquals(8 * h, f.restMs)
        assertEquals((8 + 10 + 5 * 10) * h, f.weekMs)
        assertEquals(listOf("Short rest", "Over 48 h this week"), f.findings.map { it.title })
        // Just off shift: rest so far is shown, but isn't a finding until a shift starts on it.
        val off = Fatigue.assess(null, listOf(WorkSpan(now - 9 * h, now - h)), null, now)
        assertEquals(h, off.restMs)
        assertTrue(off.findings.isEmpty())
    }

    @Test
    fun heatStrainNeedsSustainedHeartRateInTheHeat() {
        val now = 10 * DAY_MS
        fun samples(vararg bpm: Long) = bpm.mapIndexed { i, b -> HeatStrain.Sample(now - (bpm.size - 1 - i) * MINUTE_MS, b) }
        // Age 40: limit 140. Five minutes all over it in 33 °C: warning.
        val high = samples(150, 148, 152, 145, 149, 151)
        assertEquals(Level.WARNING, HeatStrain.assess(high, 33.0, null, now)?.level)
        // The same heart rate in mild weather: nothing.
        assertNull(HeatStrain.assess(high, 22.0, null, now))
        // One spike: the lowest reading decides.
        assertNull(HeatStrain.assess(samples(100, 98, 160, 102, 99, 101), 33.0, null, now))
        // Close to the limit: caution.
        assertEquals(Level.CAUTION, HeatStrain.assess(samples(130, 132, 129, 131, 133, 130), 33.0, null, now)?.level)
        // A 25-year-old's limit is 155, so 150 is only a caution.
        assertEquals(155, HeatStrain.limitBpm(25))
        assertEquals(Level.CAUTION, HeatStrain.assess(high, 33.0, 25, now)?.level)
        // Too few minutes of data to call it sustained.
        assertNull(HeatStrain.assess(samples(150, 150), 33.0, null, now))
    }

    @Test
    fun breaksAndLongestStretch() {
        assertFalse(ShiftAdvice.breakDue(0, 2 * h - 1))
        assertTrue(ShiftAdvice.breakDue(0, 2 * h))
        assertEquals(4 * h, ShiftAdvice.longestStretchMs(0, emptyList(), 4 * h))
        assertEquals(3 * h, ShiftAdvice.longestStretchMs(0, listOf(3 * h, 5 * h), 7 * h))
    }
}
