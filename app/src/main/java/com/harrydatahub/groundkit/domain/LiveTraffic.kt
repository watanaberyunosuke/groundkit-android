package com.harrydatahub.groundkit.domain

import com.harrydatahub.groundkit.data.Airline
import com.harrydatahub.groundkit.data.LiveAircraft
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Delay status. Live ADS-B has no schedules, so "late" means later than the flight's usual
 * time at this airport over the last 30 days. Bands follow the 15-minute on-time convention.
 */
enum class Rag { GREEN, AMBER, RED, UNKNOWN }

fun ragOf(delayMin: Double?): Rag = when {
    delayMin == null -> Rag.UNKNOWN
    delayMin < 15 -> Rag.GREEN
    delayMin < 45 -> Rag.AMBER
    else -> Rag.RED
}

/** A live aircraft's relation to the selected airport. */
enum class Placement { INBOUND, OUTBOUND, GROUND, OTHER }

data class PlacedAircraft(
    val live: LiveAircraft,
    val placement: Placement,
    /** Usual origin (inbound) or destination (outbound), IATA where known. */
    val other: String?,
    val flightIata: String?,
    /** The IATA flight number, else the callsign, else the transponder address. */
    val label: String,
    val distNm: Double,
    /** Inbound and airborne only: minutes to touchdown. */
    val etaMin: Double?,
    val usual: String?,
    val usualMin: Double?,
    val delayMin: Double?,
    val rag: Rag,
    /** Estimated touchdown (inbound) or take-off (outbound). */
    val eventAt: Long?,
) {
    val tracked get() = placement == Placement.INBOUND || placement == Placement.OUTBOUND
}

fun ragText(delayMin: Double?): String = when {
    delayMin == null -> "No usual time"
    delayMin < -15 -> "Early ${(-delayMin).roundToInt()} min"
    delayMin < 15 -> "On time"
    else -> "Late ${delayMin.roundToInt()} min"
}

/** ICAO callsign to IATA flight number: QFA627 -> QF627, as stg_opensky_flights does. */
class FlightCodes(private val airlines: Map<String, Airline>) {
    private val pattern = Regex("^([A-Z]{3})0*(\\d{1,4})$")

    fun flightIata(callsign: String?): String? {
        val m = callsign?.let { pattern.matchEntire(it) } ?: return null
        val iata = airlines[m.groupValues[1]]?.iata ?: return null
        return iata + m.groupValues[2]
    }

    fun airlineName(callsign: String?): String? =
        callsign?.takeIf { it.length >= 3 }?.let { airlines[it.substring(0, 3)]?.name }
}

/**
 * Direction of each airborne aircraft at the last fix, by transponder address. An arrival
 * stays inbound until it lands, though downwind legs and holds point it away from the
 * airport, and a departure stays outbound. Port of the Dive's `lastDir`; the API cannot
 * do this, as it keeps nothing between calls.
 */
class DirectionMemory {
    private val dirs = HashMap<String, Placement>()

    fun clear() = dirs.clear()

    operator fun get(icao24: String): Placement? = dirs[icao24]

    fun remember(icao24: String, placement: Placement) {
        if (placement == Placement.INBOUND || placement == Placement.OUTBOUND) dirs[icao24] = placement
        else dirs.remove(icao24)
    }
}

/** A recognised flight on the live feed, direction settled (ground aircraft get one from usual times). */
data class BoardLive(
    val callsign: String,
    val dir: Dir,
    val label: String,
    val flightIata: String?,
    val other: String?,
    val usual: String?,
    val onGround: Boolean,
    val distNm: Double,
    val eventAt: Long?,
    val rag: Rag,
    val statusNote: String?,
    val aircraft: PlacedAircraft,
)

/** What the feed showed of a flight since the airport was selected. */
data class Remembered(val live: BoardLive, val lastAt: Long, val goneAt: Long?, val landedAt: Long?)

/**
 * Port of the Dive's `useFeedMemory`: remembers each recognised flight across live
 * refreshes, so the boards can show flights that landed or left range.
 */
class FeedMemory {
    private val flights = LinkedHashMap<String, Remembered>()

    fun clear() = flights.clear()

    operator fun get(callsign: String): Remembered? = flights[callsign]

    fun snapshot(): Map<String, Remembered> = LinkedHashMap(flights)

    fun update(rows: List<BoardLive>, at: Long) {
        for (r in rows) {
            val old = flights[r.callsign]
            // Airborne inbound at the last fix, on the ground here now: it landed in between.
            val landedAt = if (r.dir == Dir.INBOUND && r.onGround && old != null && !old.live.onGround) at else old?.landedAt
            flights[r.callsign] = Remembered(r, lastAt = at, goneAt = null, landedAt = landedAt)
        }
        // Not in this fix: it left the feed (landed and shut down, or out of range).
        for ((k, m) in flights) if (m.lastAt < at && m.goneAt == null) flights[k] = m.copy(goneAt = at)
    }
}

enum class Phase { PAST, NEXT }

enum class BoardStatus { LANDED, DEPARTED, PRESUMED, EXPECTED }

data class BoardRow(
    val key: String,
    val phase: Phase,
    val at: Long,
    /** True when the time is inferred from the live feed (shown with ~). */
    val estimated: Boolean,
    val flightIata: String?,
    val callsign: String,
    val other: String?,
    val status: BoardStatus,
    val statusText: String,
    val usual: String?,
)

private fun placementOf(dir: String?): Placement? = when (dir) {
    "inbound" -> Placement.INBOUND
    "outbound" -> Placement.OUTBOUND
    "ground" -> Placement.GROUND
    "other" -> Placement.OTHER
    else -> null
}

object LiveTraffic {
    const val PAST_HOURS = 3
    const val NEXT_HOURS = 6
    /** Regular flights: seen on at least this many of the last 14 days. */
    const val REGULAR_DAYS = 7
    private const val TERMINAL_NM = TERMINAL_KM / KM_PER_NM

    /**
     * Port of the Dive's `placed`: direction, ETA and delay status for each live aircraft.
     *
     * Direction comes from the API (`dir`, for this fix) or, without it, the same rules here:
     * the callsign's last 30 days at the airport; beyond 30 NM the aircraft's track must
     * agree (a reused callsign flying away is not inbound); nearer in, a clear descent or
     * climb decides for callsigns flown both ways. An airborne aircraft then keeps its
     * direction from earlier fixes ([DirectionMemory]) until it lands. ETA is the
     * time at current ground speed to the 50 NM ring plus the airport's median time inside
     * it, pro rata when already inside. Outbound flights get an estimated take-off the same
     * way, backwards.
     */
    fun place(
        aircraft: List<LiveAircraft>,
        airportLat: Double,
        airportLon: Double,
        history: Map<String, CallsignHistory>,
        terminalArrMin: Double,
        terminalDepMin: Double,
        zone: ZoneId,
        now: Long,
        codes: FlightCodes,
        directions: DirectionMemory = DirectionMemory(),
    ): List<PlacedAircraft> = aircraft.map { a ->
        val km = distKm(a.lat, a.lon, airportLat, airportLon)
        val h = a.callsign?.let { history[it] }
        // 0 = heading straight at the airport, 180 = straight away.
        val off = abs(((a.trackDeg ?: 0.0) - bearingDeg(a.lat, a.lon, airportLat, airportLon) + 540) % 360 - 180)
        val near = km < 30 * KM_PER_NM
        // Near the airport a clear descent or climb says more than the heading, which turns
        // away from the airport on downwind and in holds.
        val descending = near && a.vrateFpm != null && a.vrateFpm <= -300
        val climbing = near && a.vrateFpm != null && a.vrateFpm >= 300
        val thisFix = placementOf(a.dir) ?: when {
            a.onGround -> if (km < 8) Placement.GROUND else Placement.OTHER
            h?.inbound != null && h.outbound != null -> when {
                descending -> Placement.INBOUND
                climbing -> Placement.OUTBOUND
                off < 90 -> Placement.INBOUND
                else -> Placement.OUTBOUND
            }
            h?.inbound != null && (near || off < 110) -> Placement.INBOUND
            h?.outbound != null && (near || off > 70) -> Placement.OUTBOUND
            else -> Placement.OTHER
        }
        val prev = directions[a.icao24]
        val prevKnown = prev == Placement.INBOUND && h?.inbound != null || prev == Placement.OUTBOUND && h?.outbound != null
        val placement = when {
            a.onGround || !prevKnown -> thisFix
            // An arrival first seen level on downwind, or a departure coming back. A climb
            // never overrides inbound, so a go-around stays an arrival.
            prev == Placement.OUTBOUND && descending && h?.inbound != null -> Placement.INBOUND
            else -> prev!!
        }
        directions.remember(a.icao24, placement)
        val speed = (a.speedKt ?: 0).toDouble()
        val seen = when (placement) {
            Placement.INBOUND -> h?.inbound
            Placement.OUTBOUND -> h?.outbound
            else -> null
        }
        val terminal = if (placement == Placement.INBOUND) terminalArrMin else terminalDepMin
        val legMin = when {
            speed < 60 -> null
            km > TERMINAL_KM -> (km - TERMINAL_KM) / (speed * KM_PER_NM) * 60 + terminal
            else -> terminal * (km / TERMINAL_KM)
        }
        val inbound = placement == Placement.INBOUND
        val at = legMin?.let { now + ((if (inbound) 1 else -1) * it * MINUTE_MS).toLong() }
        val delay = if (seen != null && at != null) wrapMinutes(minuteOfDay(at, zone) - seen.usualMin) else null
        val iata = codes.flightIata(a.callsign)
        PlacedAircraft(
            live = a,
            placement = placement,
            other = seen?.other,
            flightIata = iata,
            label = iata ?: a.callsign ?: a.icao24,
            distNm = km / KM_PER_NM,
            etaMin = if (inbound) legMin else null,
            usual = seen?.let { hhmm(it.usualMin) },
            usualMin = seen?.usualMin,
            delayMin = delay,
            rag = ragOf(delay),
            eventAt = if (placement == Placement.INBOUND || placement == Placement.OUTBOUND) at else null,
        )
    }

    /**
     * Port of the Dive's `boardLive`. An aircraft on the ground here is the arrival or
     * departure whose usual time is nearest now: arrivals up to 3 h after their usual time
     * (taxiing in, parked with the transponder on), departures within 3 h of theirs.
     */
    fun boardLive(
        placed: List<PlacedAircraft>,
        history: Map<String, CallsignHistory>,
        now: Long,
        zone: ZoneId,
    ): List<BoardLive> {
        val nowMin = minuteOfDay(now, zone).toDouble()
        val rows = mutableListOf<BoardLive>()
        for (a in placed) {
            val callsign = a.live.callsign ?: continue
            if (a.placement == Placement.INBOUND || a.placement == Placement.OUTBOUND) {
                val dir = if (a.placement == Placement.INBOUND) Dir.INBOUND else Dir.OUTBOUND
                rows += BoardLive(
                    callsign, dir, a.label, a.flightIata, a.other, a.usual, a.live.onGround, a.distNm,
                    a.eventAt, a.rag,
                    statusNote = if (dir == Dir.INBOUND || a.delayMin != null) ragText(a.delayMin) else null,
                    aircraft = a,
                )
                continue
            }
            if (a.placement != Placement.GROUND) continue
            val h = history[callsign] ?: continue
            val arr = h.inbound?.let { wrapMinutes(nowMin - it.usualMin) }   // minutes since usual arrival
            val dep = h.outbound?.let { wrapMinutes(it.usualMin - nowMin) }  // minutes to usual departure
            val arrOk = arr != null && arr >= -30 && arr <= PAST_HOURS * 60
            val depOk = dep != null && dep >= -PAST_HOURS * 60 && dep <= PAST_HOURS * 60
            val dir = when {
                arrOk && (!depOk || abs(arr!!) <= abs(dep!!)) -> Dir.INBOUND
                depOk -> Dir.OUTBOUND
                else -> continue
            }
            val seen = h[dir]!!
            // A departure still on the ground after its usual time is running late.
            val late = if (dir == Dir.OUTBOUND && dep!! < 0) -dep else null
            rows += BoardLive(
                callsign, dir, a.label, a.flightIata, seen.other, hhmm(seen.usualMin), true, a.distNm,
                eventAt = null,
                rag = if (dir == Dir.OUTBOUND) ragOf(late ?: 0.0) else Rag.UNKNOWN,
                statusNote = if (late != null && late >= 15) "Late ${late.roundToInt()} min" else null,
                aircraft = a,
            )
        }
        return rows
    }

    /**
     * Port of the Dive's `buildBoard`: the flights either side of what is on the feed now.
     * Past is what the feed showed landing / leaving since the airport was picked, then
     * regular flights by their usual time; next is regular flights not yet seen.
     */
    fun buildBoard(
        dir: Dir,
        rows: List<BoardLive>,
        memory: Map<String, Remembered>,
        history: Map<String, CallsignHistory>,
        now: Long,
        zone: ZoneId,
        codes: FlightCodes,
    ): List<BoardRow> {
        val out = mutableListOf<BoardRow>()
        val placed = rows.map { it.callsign }.toMutableSet()
        val arriving = dir == Dir.INBOUND
        fun add(r: BoardRow) {
            placed += r.callsign
            out += r
        }

        // Inbound flights count as landed only if they were on the ground or inside the
        // terminal area when they went; further out it is a coverage gap.
        val cutoff = now - PAST_HOURS * HOUR_MS
        for (m in memory.values) {
            val f = m.live
            val gone = m.goneAt ?: continue
            if (f.dir != dir || f.callsign in placed) continue
            if (arriving && !f.onGround && f.distNm > TERMINAL_NM) continue
            if (!arriving && f.onGround) continue // switched off at the stand
            val at = if (arriving) m.landedAt ?: f.eventAt ?: gone else f.eventAt ?: gone
            if (at < cutoff) continue
            add(
                BoardRow(
                    key = "past|${f.callsign}", phase = Phase.PAST, at = at, estimated = true,
                    flightIata = f.flightIata, callsign = f.callsign, other = f.other,
                    status = if (arriving) BoardStatus.LANDED else BoardStatus.DEPARTED,
                    statusText = if (arriving) "Landed"
                    else "Departed, ${if (f.distNm > 400) "out of 500 NM" else "off the feed"}",
                    usual = f.usual,
                ),
            )
        }

        val nowMin = minuteOfDay(now, zone).toDouble()
        for ((callsign, h) in history) {
            val seen = h[dir] ?: continue
            if (seen.days14 < REGULAR_DAYS || callsign in placed) continue
            val delta = wrapMinutes(seen.usualMin - nowMin)
            if (delta < -PAST_HOURS * 60 || delta > NEXT_HOURS * 60) continue
            val past = delta < 0
            add(
                BoardRow(
                    key = "${if (past) "past" else "next"}|$callsign",
                    phase = if (past) Phase.PAST else Phase.NEXT,
                    at = now + (delta * MINUTE_MS).toLong(), estimated = false,
                    flightIata = codes.flightIata(callsign), callsign = callsign, other = seen.other,
                    status = if (past) BoardStatus.PRESUMED else BoardStatus.EXPECTED,
                    statusText = when {
                        past -> "Presumed ${if (arriving) "landed" else "departed"}, not seen live"
                        arriving -> "Expected, not yet within 500 NM"
                        else -> "Expected"
                    },
                    usual = hhmm(seen.usualMin),
                ),
            )
        }
        return out.sortedBy { it.at }
    }
}
