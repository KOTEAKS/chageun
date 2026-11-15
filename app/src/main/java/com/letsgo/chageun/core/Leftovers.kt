package com.letsgo.chageun.core

import kotlin.math.abs

/**
 * Something still on or moved in the car when a session stops or ends, compared with the car when the session started.
 * The driver should check these before moving off (planning/review-safety-v0.1.md H1).
 */
sealed interface Leftover {
    /** True when it matters on the road (other drivers, glare, steering); false for comfort items. */
    val warn: Boolean

    data class TurnSignalOn(val dir: TurnDir) : Leftover { override val warn = true }
    data class WheelTurned(val degrees: Float) : Leftover { override val warn = true }
    data class LightOn(val spot: LightSpot) : Leftover { override val warn = true }
    data class SeatHeatOn(val level: Int) : Leftover { override val warn = true }
    data class SeatVentOn(val level: Int) : Leftover { override val warn = false }
    data class TempChanged(val from: Float, val now: Float) : Leftover { override val warn = false }
}

object Leftovers {
    private const val TEMP_STEP = 0.5f

    /**
     * What differs from [baseline] in a way worth checking before driving. A turn signal that is on always counts.
     * Without a baseline (the app restarted mid-episode) anything switched on counts.
     */
    fun find(baseline: PuzzleSignals?, now: PuzzleSignals): List<Leftover> = buildList {
        now.turnSignal?.let { if (it != TurnDir.OFF) add(Leftover.TurnSignalOn(it)) }
        now.steeringDeg?.let { deg ->
            val before = baseline?.steeringDeg ?: 0f
            if (abs(deg) > Episode1.CENTER_TOLERANCE_DEG && abs(deg - before) > Episode1.CENTER_TOLERANCE_DEG) add(Leftover.WheelTurned(deg))
        }
        for (spot in LightSpot.entries) {
            if (now.lights[spot] == true && baseline?.lights?.get(spot) != true) add(Leftover.LightOn(spot))
        }
        now.seatHeat?.let { level ->
            val before = baseline?.seatHeat ?: 0
            if (level > 0 && before <= 0) add(Leftover.SeatHeatOn(level))
            if (level < 0 && before >= 0) add(Leftover.SeatVentOn(level))
        }
        val from = baseline?.tempDriver
        val temp = now.tempDriver
        if (from != null && temp != null && abs(temp - from) >= TEMP_STEP - 0.01f) add(Leftover.TempChanged(from, temp))
    }

    /**
     * Lights to switch off when driving is detected mid-episode: on now, known to be off when the session started.
     * The cabin light goes first because it can carry the reading lights with it. Without a baseline nothing is switched,
     * since the light may have been someone's own before the game.
     */
    fun lightsToSwitchOff(baseline: PuzzleSignals?, now: PuzzleSignals): List<LightSpot> {
        if (baseline == null) return emptyList()
        return LightSpot.entries
            .filter { now.lights[it] == true && baseline.lights[it] == false }
            .sortedBy { if (it == LightSpot.CABIN) 0 else 1 }
    }
}
