package com.harrydatahub.groundkit.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.harrydatahub.groundkit.data.TurnaroundStore
import com.harrydatahub.groundkit.domain.Dir
import com.harrydatahub.groundkit.domain.Turnaround
import com.harrydatahub.groundkit.domain.TurnaroundStep
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/** The Turns tab: turnaround checklists kept on the device. */
class TurnaroundViewModel(app: Application) : AndroidViewModel(app) {
    private val store = TurnaroundStore(app.filesDir)
    val turnarounds: StateFlow<List<Turnaround>> = store.turnarounds

    /** Adds a turnaround and returns its id, to open it. */
    fun create(t: Turnaround): String {
        store.add(t)
        return t.id
    }

    /** "Start turnaround" on a flight: the flight, airline and route come with it. */
    fun startFrom(item: FlightItem, airportIcao: String): String = create(
        Turnaround(
            id = UUID.randomUUID().toString(),
            airportIcao = airportIcao,
            callsign = item.callsign ?: item.code,
            flightIata = item.code.takeIf { !item.codeIsCallsign },
            airline = item.airline,
            origin = item.other.takeIf { item.dir == Dir.INBOUND },
            destination = item.other.takeIf { item.dir == Dir.OUTBOUND },
            createdAt = System.currentTimeMillis(),
        ),
    )

    fun edit(id: String, change: (Turnaround) -> Turnaround) = store.edit(id, change)

    fun markDone(id: String, step: TurnaroundStep) = store.setDone(id, step, System.currentTimeMillis())

    fun undo(id: String, step: TurnaroundStep) = store.setDone(id, step, null)

    fun close(id: String) = store.edit(id) { it.copy(closedAt = System.currentTimeMillis()) }

    fun reopen(id: String) = store.edit(id) { it.copy(closedAt = null) }

    fun delete(id: String) = store.delete(id)
}
