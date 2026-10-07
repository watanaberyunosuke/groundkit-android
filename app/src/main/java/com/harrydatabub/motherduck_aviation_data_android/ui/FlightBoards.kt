package com.harrydatabub.motherduck_aviation_data_android.ui

import com.harrydatabub.motherduck_aviation_data_android.data.FlightFilter
import com.harrydatabub.motherduck_aviation_data_android.domain.AirportSnapshot
import com.harrydatabub.motherduck_aviation_data_android.domain.BoardLive
import com.harrydatabub.motherduck_aviation_data_android.domain.BoardRow
import com.harrydatabub.motherduck_aviation_data_android.domain.BoardStatus
import com.harrydatabub.motherduck_aviation_data_android.domain.Dir
import com.harrydatabub.motherduck_aviation_data_android.domain.LiveTraffic
import com.harrydatabub.motherduck_aviation_data_android.domain.MINUTE_MS
import com.harrydatabub.motherduck_aviation_data_android.domain.Operators
import com.harrydatabub.motherduck_aviation_data_android.domain.Phase
import com.harrydatabub.motherduck_aviation_data_android.domain.PlacedAircraft
import com.harrydatabub.motherduck_aviation_data_android.domain.Rag
import com.harrydatabub.motherduck_aviation_data_android.domain.Usual
import com.harrydatabub.motherduck_aviation_data_android.domain.minuteOfDay
import com.harrydatabub.motherduck_aviation_data_android.domain.ragText
import com.harrydatabub.motherduck_aviation_data_android.domain.wrapMinutes
import kotlin.math.roundToInt

/** Status colour of a row; NEUTRAL is a fact (landed, expected) rather than a delay. */
enum class Tone { GREEN, AMBER, RED, UNKNOWN, NEUTRAL }

fun Rag.tone() = when (this) {
    Rag.GREEN -> Tone.GREEN
    Rag.AMBER -> Tone.AMBER
    Rag.RED -> Tone.RED
    Rag.UNKNOWN -> Tone.UNKNOWN
}

/** One row on the arrivals / departures boards, ready to draw. */
data class FlightItem(
    val key: String,
    val dir: Dir,
    /** The IATA flight number, else the ATC callsign. */
    val code: String,
    val codeIsCallsign: Boolean,
    val callsign: String?,
    val airline: String?,
    /** Origin of an arrival, destination of a departure. */
    val other: String?,
    val timeLabel: String,
    val time: Long?,
    /** Shown with ~: inferred from the live feed or from usual times. */
    val estimated: Boolean,
    val status: String,
    val tone: Tone,
    val detail: String?,
    val muted: Boolean = false,
    val aircraft: PlacedAircraft? = null,
    val usual: Usual? = null,
    /** False for live traffic not recognised as one of this airport's flights. */
    val recognised: Boolean = true,
    /** Flown by an all-cargo operator. Passenger flights may carry cargo too. */
    val freighter: Boolean = false,
)

data class FlightSection(val title: String, val note: String, val items: List<FlightItem>, val collapsed: Boolean = false)

object FlightBoards {
    fun arrivals(snap: AirportSnapshot, t: Traffic, filter: FlightFilter, mine: Set<String>): List<FlightSection> {
        val live = t.boardLive.filter { it.dir == Dir.INBOUND && keep(it.callsign, filter, mine) }
        val board = t.inboundBoard.filter { keep(it.callsign, filter, mine) }
        val airborne = live.filter { !it.onGround }.sortedBy { it.aircraft.etaMin ?: it.distNm }
        val onGround = live.filter { it.onGround }
            .sortedByDescending { t.memory[it.callsign]?.landedAt ?: 0L }
        return listOf(
            FlightSection(
                "Inbound now", "Airborne within 500 NM, nearest ETA first",
                airborne.map { liveItem(it, snap, "ETA", it.eventAt) },
            ),
            FlightSection(
                "Landed, on the ground", "On the ground here with the transponder on",
                onGround.map { b ->
                    liveItem(b, snap, "Landed", t.memory[b.callsign]?.landedAt, status = "On the ground", tone = Tone.NEUTRAL)
                },
            ),
            FlightSection(
                "Coming up, next ${LiveTraffic.NEXT_HOURS} h", "Regular flights not yet within 500 NM, by usual time",
                board.filter { it.phase == Phase.NEXT }.map { boardItem(it, snap, Dir.INBOUND) },
            ),
            FlightSection(
                "Earlier, last ${LiveTraffic.PAST_HOURS} h", "Landed while the app was open, or presumed from usual times",
                board.filter { it.phase == Phase.PAST }.sortedByDescending { it.at }.map { boardItem(it, snap, Dir.INBOUND) },
                collapsed = true,
            ),
        )
    }

    fun departures(snap: AirportSnapshot, t: Traffic, filter: FlightFilter, mine: Set<String>): List<FlightSection> {
        val live = t.boardLive.filter { it.dir == Dir.OUTBOUND && keep(it.callsign, filter, mine) }
        val board = t.outboundBoard.filter { keep(it.callsign, filter, mine) }
        val nowMin = minuteOfDay(t.now, snap.zone).toDouble()
        fun dueAt(b: BoardLive): Long? = snap.history[b.callsign]?.outbound?.let {
            t.now + (wrapMinutes(it.usualMin - nowMin) * MINUTE_MS).toLong()
        }
        val ground = live.filter { it.onGround }.sortedBy { dueAt(it) ?: Long.MAX_VALUE }
        val airborne = live.filter { !it.onGround }.sortedBy { it.distNm }
        return listOf(
            FlightSection(
                "On the ground, due out", "At the stand or taxiing, by usual departure time",
                ground.map { b ->
                    liveItem(
                        b, snap, "Due", dueAt(b), estimated = false,
                        status = b.statusNote ?: "On the ground",
                        tone = if (b.statusNote != null) b.rag.tone() else Tone.NEUTRAL,
                    )
                },
            ),
            FlightSection(
                "Departed, climbing out", "Airborne within 500 NM, nearest first",
                airborne.map { liveItem(it, snap, "Took off", it.eventAt) },
            ),
            FlightSection(
                "Coming up, next ${LiveTraffic.NEXT_HOURS} h", "Regular flights not yet seen, by usual time",
                board.filter { it.phase == Phase.NEXT }.map { boardItem(it, snap, Dir.OUTBOUND) },
            ),
            FlightSection(
                "Earlier, last ${LiveTraffic.PAST_HOURS} h", "Departed while the app was open, or presumed from usual times",
                board.filter { it.phase == Phase.PAST }.sortedByDescending { it.at }.map { boardItem(it, snap, Dir.OUTBOUND) },
                collapsed = true,
            ),
        )
    }

    /** The board item for a live aircraft (as tapped on the map), recognised or not. */
    fun itemFor(icao24: String, snap: AirportSnapshot, t: Traffic): FlightItem? {
        t.boardLive.firstOrNull { it.aircraft.live.icao24 == icao24 }?.let { b ->
            return when {
                b.dir == Dir.INBOUND && b.onGround ->
                    liveItem(b, snap, "Landed", t.memory[b.callsign]?.landedAt, status = "On the ground", tone = Tone.NEUTRAL)
                b.dir == Dir.INBOUND -> liveItem(b, snap, "ETA", b.eventAt)
                b.onGround -> liveItem(b, snap, "Due", null, status = b.statusNote ?: "On the ground", tone = Tone.NEUTRAL)
                else -> liveItem(b, snap, "Took off", b.eventAt)
            }
        }
        val a = t.placed.firstOrNull { it.live.icao24 == icao24 } ?: return null
        return FlightItem(
            key = "live|$icao24", dir = Dir.INBOUND, code = a.label, codeIsCallsign = a.flightIata == null,
            callsign = a.live.callsign, airline = snap.codes.airlineName(a.live.callsign), other = null,
            timeLabel = "", time = null, estimated = false,
            status = if (a.live.onGround) "On the ground" else "Other traffic", tone = Tone.NEUTRAL,
            detail = positionText(a), aircraft = a, recognised = false,
            freighter = snap.codes.isFreighter(a.live.callsign),
        )
    }

    private fun keep(callsign: String?, filter: FlightFilter, mine: Set<String>) =
        Operators.matches(callsign, filter, mine)

    private fun liveItem(
        b: BoardLive,
        snap: AirportSnapshot,
        timeLabel: String,
        time: Long?,
        estimated: Boolean = true,
        status: String? = null,
        tone: Tone? = null,
    ): FlightItem {
        val a = b.aircraft
        return FlightItem(
            key = "live|${a.live.icao24}",
            dir = b.dir,
            code = b.label,
            codeIsCallsign = b.flightIata == null,
            callsign = b.callsign,
            airline = snap.codes.airlineName(b.callsign),
            other = b.other,
            timeLabel = timeLabel,
            time = time,
            estimated = estimated,
            status = status ?: b.statusNote ?: ragText(a.delayMin),
            tone = tone ?: b.rag.tone(),
            detail = positionText(a),
            aircraft = a,
            usual = snap.history[b.callsign]?.get(b.dir),
            freighter = snap.codes.isFreighter(b.callsign),
        )
    }

    private fun boardItem(r: BoardRow, snap: AirportSnapshot, dir: Dir): FlightItem {
        val usual = snap.history[r.callsign]?.get(dir)
        val past = r.phase == Phase.PAST
        return FlightItem(
            key = r.key,
            dir = dir,
            code = r.flightIata ?: r.callsign,
            codeIsCallsign = r.flightIata == null,
            callsign = r.callsign,
            airline = snap.codes.airlineName(r.callsign),
            other = r.other,
            timeLabel = when (r.status) {
                BoardStatus.LANDED -> "Landed"
                BoardStatus.DEPARTED -> "Took off"
                else -> "Usual"
            },
            time = r.at,
            estimated = r.estimated,
            status = r.statusText,
            tone = Tone.NEUTRAL,
            detail = usual?.let { "Seen ${it.days14} of the last 14 days" },
            muted = past,
            usual = usual,
            freighter = snap.codes.isFreighter(r.callsign),
        )
    }

    fun positionText(a: PlacedAircraft): String {
        val parts = mutableListOf("${a.distNm.roundToInt()} NM")
        if (a.live.onGround) {
            parts += "on the ground"
            a.live.speedKt?.takeIf { it >= 3 }?.let { parts += "taxiing $it kt" }
        } else {
            val arrow = when {
                (a.live.vrateFpm ?: 0) > 300 -> " ↑"
                (a.live.vrateFpm ?: 0) < -300 -> " ↓"
                else -> ""
            }
            parts += "${Fmt.thousands(a.live.altFt)} ft$arrow"
            a.live.speedKt?.let { parts += "$it kt" }
        }
        return parts.joinToString(" · ")
    }
}
