package com.letsgo.chageun.core

import kotlin.math.abs

enum class GateBlock { NOT_IN_PARK, MOVING, DOOR_OPEN, SIGNAL_UNAVAILABLE }

sealed interface GateState {
    data class Blocked(val reasons: Set<GateBlock>) : GateState
    /** Every condition holds, but not yet for [ParkingGate.settleMillis]. */
    data object Settling : GateState
    data object Ready : GateState
}

/**
 * The single "parked" rule used for start, play, and resume:
 * gear P, speed 0, every door closed, and all of those signals actually readable.
 * The platform unlocks app screens more loosely (auto hold counts as stopped in Korea); this gate is stricter on purpose.
 */
class ParkingGate(val settleMillis: Long = 2_000) {
    private var cleanSince: Long? = null

    fun evaluate(snapshot: VehicleSnapshot, nowMillis: Long): GateState {
        val reasons = blocksFor(snapshot)
        if (reasons.isNotEmpty()) {
            cleanSince = null
            return GateState.Blocked(reasons)
        }
        val since = cleanSince ?: nowMillis.also { cleanSince = it }
        return if (nowMillis - since >= settleMillis) GateState.Ready else GateState.Settling
    }

    companion object {
        /** Float noise allowance only; any real motion (forward or reverse, which reads negative) blocks. */
        const val SPEED_TOLERANCE_KMH = 0.1f

        fun blocksFor(s: VehicleSnapshot): Set<GateBlock> = buildSet {
            if (!s.connected) add(GateBlock.SIGNAL_UNAVAILABLE)
            when (val g = s.gear) {
                is Reading.Known -> if (g.value != Gear.PARK) add(GateBlock.NOT_IN_PARK)
                else -> add(GateBlock.SIGNAL_UNAVAILABLE)
            }
            when (val v = s.speedKmh) {
                is Reading.Known -> if (abs(v.value) >= SPEED_TOLERANCE_KMH) add(GateBlock.MOVING)
                else -> add(GateBlock.SIGNAL_UNAVAILABLE)
            }
            when (val d = s.openDoors) {
                is Reading.Known -> if (d.value.isNotEmpty()) add(GateBlock.DOOR_OPEN)
                else -> add(GateBlock.SIGNAL_UNAVAILABLE)
            }
        }
    }
}
