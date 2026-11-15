package com.letsgo.chageun.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PuzzleRunTest {
    private val store = MemoryLearningStore()
    private val log = LearningLog(store)

    private fun run(p: Puzzle = CorePuzzles.coldMorning, echo: (VehicleEvent) -> Boolean = { false }) =
        PuzzleRun(p, log, isAppEcho = echo).also { it.begin(PuzzleSignals(seatHeat = 0, frontDefroster = false)) }

    @Test fun `turning the seat heater on in the car is confirmed practice`() {
        val r = run()
        assertTrue(r.onEvent(VehicleEvent.SeatHeat(2)))
        assertEquals(1, r.index)
        assertEquals(Progress.VEHICLE_CONFIRMED, log[FeatureId.SEAT_HEAT].progress)
        assertEquals(Support.SUPPORTED, log[FeatureId.SEAT_HEAT].support)
    }

    @Test fun `a goal already met at the start is not counted, even if the car repeats it`() {
        val r = PuzzleRun(CorePuzzles.coldMorning, log).apply { begin(PuzzleSignals(seatHeat = 2)) }
        assertEquals(StepStatus.AlreadyMet, r.status)
        assertFalse(r.onEvent(VehicleEvent.SeatHeat(2)))
        assertEquals(Progress.NOT_STARTED, log[FeatureId.SEAT_HEAT].progress)
    }

    @Test fun `switching off and on again after an already-met start counts`() {
        val r = PuzzleRun(CorePuzzles.coldMorning, log).apply { begin(PuzzleSignals(seatHeat = 2)) }
        assertFalse(r.onEvent(VehicleEvent.SeatHeat(0)))
        assertTrue(r.onEvent(VehicleEvent.SeatHeat(1)))
        assertEquals(Progress.VEHICLE_CONFIRMED, log[FeatureId.SEAT_HEAT].progress)
    }

    @Test fun `continuing past an already-met goal is guidance only`() {
        val r = PuzzleRun(CorePuzzles.foggyWindshield, log).apply { begin(PuzzleSignals(frontDefroster = true)) }
        r.continueAlreadyMet()
        assertEquals(1, r.index)
        assertEquals(Progress.GUIDANCE_SEEN, log[FeatureId.FRONT_DEFROST].progress)
    }

    @Test fun `doing it on screen is guidance, never vehicle confirmed`() {
        val r = run()
        r.doOnScreen()
        assertEquals(Progress.GUIDANCE_SEEN, log[FeatureId.SEAT_HEAT].progress)
    }

    @Test fun `skipping is recorded as skipped, and later practice upgrades it`() {
        val r = run()
        r.skip()
        assertEquals(Progress.SKIPPED, log[FeatureId.SEAT_HEAT].progress)
        val again = run()
        again.onEvent(VehicleEvent.SeatHeat(1))
        assertEquals(Progress.VEHICLE_CONFIRMED, log[FeatureId.SEAT_HEAT].progress)
    }

    @Test fun `progress never moves backwards`() {
        val r = run()
        r.onEvent(VehicleEvent.SeatHeat(1))
        run().skip()
        assertEquals(Progress.VEHICLE_CONFIRMED, log[FeatureId.SEAT_HEAT].progress)
    }

    @Test fun `the app's own effect echo is ignored`() {
        val r = run(echo = { true })
        assertFalse(r.onEvent(VehicleEvent.SeatHeat(1)))
        assertEquals(Progress.NOT_STARTED, log[FeatureId.SEAT_HEAT].progress)
    }

    @Test fun `unrelated signals do not complete a step`() {
        val r = run()
        assertFalse(r.onEvent(VehicleEvent.FrontDefroster(true)))
        assertEquals(0, r.index)
    }

    @Test fun `cruise null is cannot-confirm, never success`() {
        val r = run(CorePuzzles.driveAssist)
        assertFalse(r.onEvent(VehicleEvent.Cruise(CruiseState.UNKNOWN)))
        assertEquals(StepStatus.CannotConfirm, r.status)
        assertTrue(r.onEvent(VehicleEvent.Cruise(CruiseState.STANDBY)))
        assertEquals(Progress.VEHICLE_CONFIRMED, log[FeatureId.DRIVE_ASSIST].progress)
    }

    @Test fun `cruise release is confirmed by the player, never by an ambiguous signal`() {
        val p = CorePuzzles.driveAssist
        val last = p.steps.lastIndex
        assertEquals(StepKind.SCREEN_CONFIRM, p.steps[last].kind)
        val r = PuzzleRun(p, log, startIndex = last).apply { begin(PuzzleSignals(cruise = CruiseState.STANDBY)) }
        assertFalse(r.onEvent(VehicleEvent.Cruise(CruiseState.UNKNOWN)))
        assertEquals(last, r.index)
        r.confirm()
        assertTrue(r.finished)
    }

    @Test fun `cruise SDK names map in one place`() {
        assertEquals(CruiseState.STANDBY, CruiseState.fromSdkName("ENABLED"))
        assertEquals(CruiseState.ENGAGED, CruiseState.fromSdkName("ACTIVATED"))
        assertEquals(CruiseState.UNKNOWN, CruiseState.fromSdkName("OTHER"))
        assertEquals(CruiseState.UNKNOWN, CruiseState.fromSdkName("FORCED_DEACTIVATION_WARNING"))
        assertEquals(CruiseState.UNKNOWN, CruiseState.fromSdkName(null))
        assertEquals(CruiseState.UNKNOWN, CruiseState.fromSdkName("SOMETHING_NEW"))
    }

    @Test fun `quiz waits for the explanation before moving on`() {
        val r = PuzzleRun(CorePuzzles.coldMorning, log, startIndex = 1)
        r.answer(0)
        assertEquals(StepStatus.Answered(0, false), r.status)
        assertEquals(1, r.index)
        r.afterAnswer()
        assertEquals(2, r.index)
    }

    @Test fun `screen confirm and info steps record guidance`() {
        val r = PuzzleRun(CorePuzzles.foggyWindshield, log, startIndex = 1)
        r.confirm()
        assertEquals(2, r.index)
        assertEquals(Progress.GUIDANCE_SEEN, log[FeatureId.FRONT_DEFROST].progress)
    }

    @Test fun `records survive a restart through the store`() {
        run().onEvent(VehicleEvent.SeatHeat(1))
        val reloaded = LearningLog(store)
        assertEquals(Progress.VEHICLE_CONFIRMED, reloaded[FeatureId.SEAT_HEAT].progress)
    }
}
