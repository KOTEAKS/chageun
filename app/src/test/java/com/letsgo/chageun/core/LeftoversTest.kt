package com.letsgo.chageun.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** review-safety-v0.1 H1: what a session left on or moved must be listed on every way out, not only on the done screens. */
class LeftoversTest {
    private val allOff = LightSpot.entries.associateWith { false }
    private val start = PuzzleSignals(seatHeat = 0, turnSignal = TurnDir.OFF, steeringDeg = 0f, tempDriver = 23f, lights = allOff)

    @Test fun `nothing changed means nothing to check`() {
        assertEquals(emptyList<Leftover>(), Leftovers.find(start, start))
    }

    @Test fun `a turn signal that is on always counts, even if it was on at the start`() {
        val on = start.copy(turnSignal = TurnDir.LEFT)
        assertEquals(listOf(Leftover.TurnSignalOn(TurnDir.LEFT)), Leftovers.find(on, on))
    }

    @Test fun `a wheel turned during the session counts, a wheel parked turned does not`() {
        assertEquals(listOf(Leftover.WheelTurned(70f)), Leftovers.find(start, start.copy(steeringDeg = 70f)))
        assertEquals(emptyList<Leftover>(), Leftovers.find(start, start.copy(steeringDeg = 8f)))
        val parkedTurned = start.copy(steeringDeg = -200f)
        assertEquals(emptyList<Leftover>(), Leftovers.find(parkedTurned, parkedTurned.copy(steeringDeg = -195f)))
    }

    @Test fun `lights switched on during the session count, lights already on do not`() {
        val now = start.copy(lights = allOff + (LightSpot.DRIVER to true) + (LightSpot.REAR_LEFT to true))
        assertEquals(listOf(Leftover.LightOn(LightSpot.DRIVER), Leftover.LightOn(LightSpot.REAR_LEFT)), Leftovers.find(start, now))
        val reading = start.copy(lights = allOff + (LightSpot.PASSENGER to true))
        assertEquals(emptyList<Leftover>(), Leftovers.find(reading, reading))
    }

    @Test fun `seat heat warns, ventilation and temperature are only noted`() {
        val heat = Leftovers.find(start, start.copy(seatHeat = 2))
        assertEquals(listOf(Leftover.SeatHeatOn(2)), heat)
        assertTrue(heat.single().warn)
        val calm = Leftovers.find(start, start.copy(seatHeat = -1, tempDriver = 21.5f))
        assertEquals(listOf(Leftover.SeatVentOn(-1), Leftover.TempChanged(23f, 21.5f)), calm)
        assertTrue(calm.none { it.warn })
    }

    @Test fun `without a baseline anything switched on counts`() {
        val now = start.copy(lights = allOff + (LightSpot.CABIN to true), seatHeat = 1)
        assertEquals(listOf(Leftover.LightOn(LightSpot.CABIN), Leftover.SeatHeatOn(1)), Leftovers.find(null, now))
    }

    @Test fun `on driving, only lights known to be off at the start are switched off, cabin light first`() {
        val now = start.copy(lights = allOff + (LightSpot.DRIVER to true) + (LightSpot.CABIN to true))
        assertEquals(listOf(LightSpot.CABIN, LightSpot.DRIVER), Leftovers.lightsToSwitchOff(start, now))
        val passengerReading = start.copy(lights = allOff + (LightSpot.PASSENGER to true))
        assertEquals(emptyList<LightSpot>(), Leftovers.lightsToSwitchOff(passengerReading, passengerReading))
        assertEquals(emptyList<LightSpot>(), Leftovers.lightsToSwitchOff(null, now))
        val unread = PuzzleSignals(lights = emptyMap())
        assertEquals(emptyList<LightSpot>(), Leftovers.lightsToSwitchOff(unread, now))
    }
}
