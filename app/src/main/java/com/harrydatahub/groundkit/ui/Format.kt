package com.harrydatahub.groundkit.ui

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** 24-hour times everywhere: ramp and ops work in 24-hour local time and UTC. */
object Fmt {
    private val hm = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)
    private val hms = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.UK)
    private val dayDate = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)
    private val dayHm = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.UK)
    private val zulu = DateTimeFormatter.ofPattern("dd HH:mm'Z'", Locale.UK)
    private val zuluLong = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm'Z'", Locale.UK)

    fun hm(epochMs: Long, zone: ZoneId): String = hm.format(Instant.ofEpochMilli(epochMs).atZone(zone))
    fun hms(epochMs: Long, zone: ZoneId): String = hms.format(Instant.ofEpochMilli(epochMs).atZone(zone))
    fun date(epochMs: Long, zone: ZoneId): String = dayDate.format(Instant.ofEpochMilli(epochMs).atZone(zone))
    fun dayHm(epochMs: Long, zone: ZoneId): String = dayHm.format(Instant.ofEpochMilli(epochMs).atZone(zone))

    /** METAR-style UTC: "05 14:30Z". */
    fun zulu(epochMs: Long): String = zulu.format(Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC))
    fun zuluLong(epochMs: Long): String = zuluLong.format(Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC))

    /** "UTC+8" for the zone's current offset. */
    fun offset(zone: ZoneId, at: Long): String {
        val secs = zone.rules.getOffset(Instant.ofEpochMilli(at)).totalSeconds
        if (secs == 0) return "UTC"
        val h = abs(secs) / 3600
        val m = abs(secs) % 3600 / 60
        return "UTC${if (secs < 0) "−" else "+"}$h${if (m != 0) ":%02d".format(Locale.ROOT, m) else ""}"
    }

    /** "in 12 min", "now", "8 min ago", "in 1 h 05". */
    fun relative(target: Long, now: Long): String {
        val min = ((target - now) / 60_000.0).roundToInt()
        return when {
            min == 0 -> "now"
            min > 0 -> "in ${duration(min)}"
            else -> "${duration(-min)} ago"
        }
    }

    fun duration(minutes: Int): String =
        if (minutes < 60) "$minutes min" else "${minutes / 60} h ${"%02d".format(Locale.ROOT, minutes % 60)}"

    fun age(epochMs: Long, now: Long): String {
        val min = ((now - epochMs) / 60_000).toInt()
        return when {
            min < 1 -> "just now"
            min < 120 -> "$min min ago"
            min < 48 * 60 -> "${min / 60} h ago"
            else -> "${min / 1440} days ago"
        }
    }

    fun thousands(n: Number): String = "%,d".format(Locale.UK, n.toLong())

    fun percent(share: Double?): String = share?.let { "${(it * 100).roundToInt()}%" } ?: "–"

    /** "movement_area" -> "Movement area" */
    fun humanize(v: String): String = v.replaceFirstChar { it.uppercase() }.replace('_', ' ')
}
