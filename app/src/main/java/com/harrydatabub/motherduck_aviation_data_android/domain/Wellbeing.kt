package com.harrydatabub.motherduck_aviation_data_android.domain

/** A stretch of sleep from Health Connect, epoch ms. */
data class SleepSpan(val start: Long, val end: Long) {
    val durationMs get() = end - start
}

/** A finished or running shift, epoch ms; end null while on shift. */
data class WorkSpan(val start: Long, val end: Long?)

enum class Level { OK, CAUTION, WARNING }

/** One fatigue or heat-strain finding, worded for the crew member. */
data class Finding(val level: Level, val title: String, val detail: String)

/**
 * Fatigue checks for one person, from their sleep and shifts. The sleep checks are the
 * prior sleep/wake model of Dawson and McCulloch (2005), used in ICAO's FRMS manual: at
 * least 5 h sleep in the 24 h before duty, at least 12 h in the 48 h before, and no longer
 * awake than the sleep in those 48 h. Rest and weekly hours follow the EU Working Time
 * Directive (11 h rest a day, 48 h a week). Guidance only: rosters and the employer's
 * fatigue procedures decide.
 */
object Fatigue {
    const val MIN_SLEEP_24H_MS = 5 * HOUR_MS
    const val MIN_SLEEP_48H_MS = 12 * HOUR_MS
    const val MIN_REST_MS = 11 * HOUR_MS
    const val WEEK_HOURS_MS = 48 * HOUR_MS

    data class Summary(
        /**
         * Sleep in the 24 and 48 h before duty starts (or before now, off shift). Null without
         * sleep data: not allowed, or nothing recorded in those 48 h (no tracker worn).
         */
        val sleep24hMs: Long?,
        val sleep48hMs: Long?,
        /** Time awake since the last sleep ended, now. */
        val awakeMs: Long?,
        /** Rest between the previous shift's end and this one's start (or now, off shift). */
        val restMs: Long?,
        /** Time worked in the 7 days to now, the running shift included. */
        val weekMs: Long,
        val findings: List<Finding>,
    )

    /**
     * `dutyStart` is the running shift's start, or null off shift, when the checks are for
     * a shift starting now.
     */
    fun assess(allSleep: List<SleepSpan>?, shifts: List<WorkSpan>, dutyStart: Long?, now: Long): Summary {
        val ref = dutyStart ?: now
        // Nothing recorded at all means no data, not no sleep.
        val sleep = allSleep?.takeIf { spans -> spans.any { it.end > ref - 2 * DAY_MS && it.start < now } }
        val sleep24 = sleep?.let { sleptBetween(it, ref - DAY_MS, ref) }
        val sleep48 = sleep?.let { sleptBetween(it, ref - 2 * DAY_MS, ref) }
        val lastWake = sleep?.filter { it.start < now }?.maxOfOrNull { minOf(it.end, now) }
        val awake = lastWake?.let { now - it }
        val previousEnd = shifts.mapNotNull { it.end }.filter { it <= ref }.maxOrNull()
        val rest = previousEnd?.let { ref - it }
        val week = shifts.sumOf { s -> overlap(s.start, s.end ?: now, now - 7 * DAY_MS, now) }

        val findings = buildList {
            if (sleep24 != null && sleep48 != null) {
                if (sleep24 < MIN_SLEEP_24H_MS) add(
                    Finding(Level.WARNING, "Under 5 h sleep in 24 h", "${Fmt0.hm(sleep24)} slept in the 24 h before duty. Fatigue risk is high: tell your supervisor and avoid safety-critical tasks."),
                )
                if (sleep48 < MIN_SLEEP_48H_MS) add(
                    Finding(Level.WARNING, "Under 12 h sleep in 48 h", "${Fmt0.hm(sleep48)} slept in the 48 h before duty."),
                )
                if (awake != null && awake > sleep48) add(
                    Finding(Level.CAUTION, "Awake longer than you've slept", "Awake ${Fmt0.hm(awake)}, more than the ${Fmt0.hm(sleep48)} slept in 48 h. Take a break before tasks that need full attention."),
                )
            }
            // Off shift, rest is still building up: only a shift starting on it is short of rest.
            if (dutyStart != null && rest != null && rest < MIN_REST_MS) add(
                Finding(Level.CAUTION, "Short rest", "${Fmt0.hm(rest)} off between shifts, under the usual 11 h."),
            )
            if (week > WEEK_HOURS_MS) add(
                Finding(Level.CAUTION, "Over 48 h this week", "${Fmt0.hm(week)} worked in the last 7 days."),
            )
        }
        return Summary(sleep24, sleep48, awake, rest, week, findings)
    }

    /** Sleep inside [from, to), counting overlapping spans once. */
    fun sleptBetween(spans: List<SleepSpan>, from: Long, to: Long): Long {
        var total = 0L
        var covered = from
        for (s in spans.sortedBy { it.start }) {
            val a = maxOf(s.start, covered)
            val b = minOf(s.end, to)
            if (b > a) {
                total += b - a
                covered = b
            }
        }
        return total
    }

    private fun overlap(a0: Long, a1: Long, b0: Long, b1: Long) = maxOf(0L, minOf(a1, b1) - maxOf(a0, b0))
}

/**
 * Heat strain from heart rate in the heat. NIOSH's criteria (2016) treat a heart rate
 * sustained for several minutes above 180 minus age as excessive heat strain; with no age
 * set, 40 is assumed (140 bpm). Checked only when it feels like 27 °C or more, where the
 * water target also rises. Not medical advice.
 */
object HeatStrain {
    const val DEFAULT_AGE = 40
    const val HEAT_FROM_C = 27.0
    /** "Several minutes": the samples must cover at least this long. */
    const val SUSTAINED_MS = 5 * MINUTE_MS
    /** How far below the limit counts as getting close. */
    const val CAUTION_MARGIN_BPM = 15

    data class Sample(val at: Long, val bpm: Long)

    fun limitBpm(age: Int?): Int = 180 - (age?.takeIf { it in 16..80 } ?: DEFAULT_AGE)

    /**
     * Uses the samples from the last [SUSTAINED_MS]: their lowest value must be over the
     * limit, so one spike (lifting a bag) does not count. Null when there's nothing to say.
     */
    fun assess(samples: List<Sample>, feelsLikeC: Double?, age: Int?, now: Long): Finding? {
        if (feelsLikeC == null || feelsLikeC < HEAT_FROM_C) return null
        val recent = samples.filter { it.at in (now - SUSTAINED_MS)..now }.sortedBy { it.at }
        if (recent.size < 3 || recent.last().at - recent.first().at < SUSTAINED_MS * 3 / 5) return null
        val floor = recent.minOf { it.bpm }
        val limit = limitBpm(age)
        val feels = "%.0f".format(java.util.Locale.ROOT, feelsLikeC)
        return when {
            floor > limit -> Finding(
                Level.WARNING, "Heat strain: heart rate over $limit bpm",
                "Your heart rate has stayed over $limit bpm for 5 minutes, and it feels like $feels °C. " +
                    "Stop, get into shade or air conditioning, drink water, and tell your supervisor. " +
                    "Confusion, headache, nausea or no sweating are an emergency.",
            )
            floor > limit - CAUTION_MARGIN_BPM -> Finding(
                Level.CAUTION, "Heart rate high in the heat",
                "Over ${limit - CAUTION_MARGIN_BPM} bpm for 5 minutes, feels like $feels °C. Slow down and drink water.",
            )
            else -> null
        }
    }
}

/** Durations as "7 h 05 min" for the findings; the UI's Fmt is not reachable from domain. */
internal object Fmt0 {
    fun hm(ms: Long): String {
        val m = (ms / MINUTE_MS).toInt()
        return if (m >= 60) "%d h %02d min".format(java.util.Locale.ROOT, m / 60, m % 60) else "$m min"
    }
}
