package com.harrydatahub.groundkit.domain

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** A ground-handling turnaround step, in the usual order: ramp, baggage and cargo. As on iOS. */
enum class TurnaroundStep(val phase: Phase, val title: String) {
    CHOCKS_ON(Phase.ARRIVAL, "Chocks on"),
    CONES_PLACED(Phase.ARRIVAL, "Cones placed"),
    GPU_CONNECTED(Phase.ARRIVAL, "GPU on"),
    HOLDS_OPEN(Phase.ARRIVAL, "Holds open"),
    BAGS_OFFLOADED(Phase.OFFLOAD, "Bags off"),
    CARGO_OFFLOADED(Phase.OFFLOAD, "Cargo / ULDs off"),
    FUELLED(Phase.SERVICING, "Fuelling done"),
    CATERED(Phase.SERVICING, "Catering done"),
    CLEANED(Phase.SERVICING, "Cleaning done"),
    WATERED(Phase.SERVICING, "Water / toilets"),
    BAGS_LOADED(Phase.LOAD, "Bags loaded"),
    CARGO_LOADED(Phase.LOAD, "Cargo / ULDs loaded"),
    NOTOC(Phase.LOAD, "NOTOC to captain"),
    LOADSHEET(Phase.LOAD, "Loadsheet"),
    HOLDS_CLOSED(Phase.DEPARTURE, "Holds closed"),
    GPU_REMOVED(Phase.DEPARTURE, "GPU off"),
    CHOCKS_OFF(Phase.DEPARTURE, "Chocks off"),
    PUSHBACK(Phase.DEPARTURE, "Pushback");

    enum class Phase(val title: String) { ARRIVAL("Arrival"), OFFLOAD("Offload"), SERVICING("Servicing"), LOAD("Load"), DEPARTURE("Departure") }

    /** The NOTOC (notification to captain) is only needed with dangerous goods on board. */
    val dangerousGoodsOnly get() = this == NOTOC
}

data class Turnaround(
    val id: String,
    val airportIcao: String,
    val callsign: String,
    val flightIata: String? = null,
    val airline: String? = null,
    /** Inbound origin and outbound destination. */
    val origin: String? = null,
    val destination: String? = null,
    val stand: String = "",
    val registration: String = "",
    val createdAt: Long,
    val targetOffBlock: Long? = null,
    val hasDangerousGoods: Boolean = false,
    val bagsOffloaded: Int = 0,
    val bagsLoaded: Int = 0,
    val uldsOffloaded: Int = 0,
    val uldsLoaded: Int = 0,
    val notes: String = "",
    val closedAt: Long? = null,
    /** When each step was done, by step name; names kept as text so a newer app's steps survive. */
    val done: Map<String, Long> = emptyMap(),
) {
    val label get() = flightIata ?: callsign.ifEmpty { "Turnaround" }
    val isClosed get() = closedAt != null
    val steps get() = TurnaroundStep.entries.filter { !it.dangerousGoodsOnly || hasDangerousGoods }
    fun doneAt(step: TurnaroundStep): Long? = done[step.name]
    val nextStep get() = steps.firstOrNull { doneAt(it) == null }
    val progress get() = steps.count { doneAt(it) != null }.toFloat() / steps.size.coerceAtLeast(1)
    val onBlocksAt get() = doneAt(TurnaroundStep.CHOCKS_ON)
}

object Turnarounds {
    /**
     * The target off-block time picked as a local time at the airport: today, or tomorrow when
     * that is more than 12 h ago (a turnaround picked before midnight that leaves after it).
     */
    fun offBlockAt(time: LocalTime, zone: ZoneId, now: Long): Long {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val at = today.atTime(time).atZone(zone).toInstant().toEpochMilli()
        return if (now - at > 12 * HOUR_MS) today.plusDays(1).atTime(time).atZone(zone).toInstant().toEpochMilli() else at
    }
}
