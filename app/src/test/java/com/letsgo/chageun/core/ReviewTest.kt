package com.letsgo.chageun.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewTest {
    private val store = MemoryLearningStore()
    private val log = LearningLog(store)
    private val day = 20_000L

    private fun review(p: Puzzle, signals: PuzzleSignals = PuzzleSignals(seatHeat = 0, frontDefroster = false)) =
        PuzzleRun(p, log).also { it.begin(signals) }

    private fun learn(id: FeatureId, progress: Progress = Progress.VEHICLE_CONFIRMED) {
        log.advance(id, progress)
        log.markLearned(id, day)
    }

    @Test fun `a review is due from the day after the puzzle, not the same day`() {
        learn(FeatureId.SEAT_HEAT)
        assertEquals(emptyList<FeatureId>(), log.dueReviews(day))
        assertEquals(listOf(FeatureId.SEAT_HEAT), log.dueReviews(day + 1))
    }

    @Test fun `nothing is due for a feature never practised, or practised only by skipping`() {
        log.markLearned(FeatureId.SEAT_HEAT, day)
        learn(FeatureId.FRONT_DEFROST, Progress.SKIPPED)
        assertEquals(emptyList<FeatureId>(), log.dueReviews(day + 1))
    }

    @Test fun `the first learned day is kept, oldest due first`() {
        learn(FeatureId.FRONT_DEFROST)
        log.markLearned(FeatureId.FRONT_DEFROST, day + 3) // replaying later does not move it
        log.advance(FeatureId.SEAT_HEAT, Progress.GUIDANCE_SEEN); log.markLearned(FeatureId.SEAT_HEAT, day + 1)
        assertEquals(listOf(FeatureId.FRONT_DEFROST, FeatureId.SEAT_HEAT), log.dueReviews(day + 2))
        assertEquals(day, LearningLog(store)[FeatureId.FRONT_DEFROST].learnedDay) // survives a restart
    }

    @Test fun `doing it in the car without help is an unaided success, and the review is no longer due`() {
        learn(FeatureId.SEAT_HEAT)
        val r = review(ReviewPuzzles.seatVent)
        assertTrue(r.onEvent(VehicleEvent.SeatHeat(-2)))
        assertTrue(r.onEvent(VehicleEvent.SeatHeat(0)))
        assertTrue(r.finished)
        assertEquals(Review.UNAIDED_SUCCESS, log[FeatureId.SEAT_HEAT].review)
        assertEquals(emptyList<FeatureId>(), log.dueReviews(day + 1))
    }

    @Test fun `turning ventilation off is judged from the ventilation side (emulator bug repro)`() {
        val r = review(ReviewPuzzles.seatVent)
        r.onEvent(VehicleEvent.SeatHeat(-2))
        r.begin(PuzzleSignals(seatHeat = -2)) // the off step starts while ventilation is on
        assertEquals(StepStatus.Waiting, r.status)
        assertTrue(r.onEvent(VehicleEvent.SeatHeat(0)))
        assertEquals(Review.UNAIDED_SUCCESS, log[FeatureId.SEAT_HEAT].review)
    }

    @Test fun `an off step that is already off does not count as help`() {
        val r = review(ReviewPuzzles.seatVent)
        r.onEvent(VehicleEvent.SeatHeat(-1))
        r.begin(PuzzleSignals(seatHeat = 0))
        assertEquals(StepStatus.AlreadyMet, r.status)
        r.continueAlreadyMet()
        assertEquals(Review.UNAIDED_SUCCESS, log[FeatureId.SEAT_HEAT].review)
    }

    @Test fun `ventilation already on at the start stays aided even after toggling (Codex round 2 repro)`() {
        val r = review(ReviewPuzzles.seatVent, PuzzleSignals(seatHeat = -2))
        assertEquals(StepStatus.AlreadyMet, r.status)
        r.onEvent(VehicleEvent.SeatHeat(0))
        assertTrue(r.onEvent(VehicleEvent.SeatHeat(-1)))
        assertTrue(r.onEvent(VehicleEvent.SeatHeat(0)))
        assertEquals(Review.AIDED, log[FeatureId.SEAT_HEAT].review)
    }

    @Test fun `a review never changes the learning progress (Codex round 2 repro)`() {
        learn(FeatureId.SEAT_HEAT, Progress.GUIDANCE_SEEN)
        val r = review(ReviewPuzzles.seatVent)
        r.onEvent(VehicleEvent.SeatHeat(-2)); r.onEvent(VehicleEvent.SeatHeat(0))
        assertEquals(Progress.GUIDANCE_SEEN, log[FeatureId.SEAT_HEAT].progress)
        assertEquals(Review.UNAIDED_SUCCESS, log[FeatureId.SEAT_HEAT].review)
        val skipped = review(ReviewPuzzles.defroster)
        skipped.skip(); skipped.skip()
        assertEquals(Progress.NOT_STARTED, log[FeatureId.FRONT_DEFROST].progress)
    }

    @Test fun `heat is not ventilation`() {
        val r = review(ReviewPuzzles.seatVent)
        r.onEvent(VehicleEvent.SeatHeat(2))
        assertEquals(0, r.index)
    }

    @Test fun `a hint makes it aided`() {
        val r = review(ReviewPuzzles.defroster)
        r.markHelpUsed()
        r.onEvent(VehicleEvent.FrontDefroster(true))
        r.confirm()
        assertEquals(Review.AIDED, log[FeatureId.FRONT_DEFROST].review)
    }

    @Test fun `doing it on screen makes it aided`() {
        val r = review(ReviewPuzzles.defroster)
        r.doOnScreen(); r.confirm()
        assertEquals(Review.AIDED, log[FeatureId.FRONT_DEFROST].review)
    }

    @Test fun `a defroster already on proves nothing, so it is aided`() {
        val r = review(ReviewPuzzles.defroster, PuzzleSignals(frontDefroster = true))
        assertEquals(StepStatus.AlreadyMet, r.status)
        r.continueAlreadyMet(); r.confirm()
        assertEquals(Review.AIDED, log[FeatureId.FRONT_DEFROST].review)
    }

    @Test fun `a wrong first answer makes it aided, a right one is unaided`() {
        val wrong = review(ReviewPuzzles.driveAssist)
        wrong.answer(1); wrong.afterAnswer()
        assertEquals(Review.AIDED, log[FeatureId.DRIVE_ASSIST].review)
        val log2 = LearningLog(MemoryLearningStore())
        val right = PuzzleRun(ReviewPuzzles.driveAssist, log2)
        right.answer(0); right.afterAnswer()
        assertEquals(Review.UNAIDED_SUCCESS, log2[FeatureId.DRIVE_ASSIST].review)
    }

    @Test fun `skipping records no review, so it stays due`() {
        learn(FeatureId.DRIVE_ASSIST)
        val r = review(ReviewPuzzles.driveAssist)
        r.skip()
        assertEquals(Review.NONE, log[FeatureId.DRIVE_ASSIST].review)
        assertEquals(listOf(FeatureId.DRIVE_ASSIST), log.dueReviews(day + 1))
    }

    @Test fun `a chapter-0 puzzle never writes a review`() {
        val r = review(CorePuzzles.driveAssist)
        r.skip(); r.skip(); r.skip(); r.skip()
        assertEquals(Review.NONE, log[FeatureId.DRIVE_ASSIST].review)
    }

    @Test fun `a car without seat ventilation reviews heat in another situation, and can succeed unaided (review-safety M7)`() {
        assertTrue(ReviewPuzzles.forFeature(FeatureId.SEAT_HEAT, Support.UNSUPPORTED) === ReviewPuzzles.seatHeatEvening)
        assertTrue(ReviewPuzzles.forFeature(FeatureId.SEAT_HEAT, Support.SUPPORTED) === ReviewPuzzles.seatVent)
        assertTrue(ReviewPuzzles.forFeature(FeatureId.SEAT_HEAT) === ReviewPuzzles.seatVent) // unknown: as before
        assertTrue(CorePuzzles.byId("r-0-2b") === ReviewPuzzles.seatHeatEvening)
        learn(FeatureId.SEAT_HEAT)
        val r = review(ReviewPuzzles.seatHeatEvening)
        assertTrue(r.onEvent(VehicleEvent.SeatHeat(2)))
        assertTrue(r.onEvent(VehicleEvent.SeatHeat(0)))
        assertTrue(r.finished)
        assertEquals(Review.UNAIDED_SUCCESS, log[FeatureId.SEAT_HEAT].review)
    }

    @Test fun `every core feature has a review`() {
        FeatureId.entries.filter { it.core }.forEach { assertTrue(ReviewPuzzles.forFeature(it) != null) }
        assertTrue(CorePuzzles.byId("r-0-4") === ReviewPuzzles.defroster)
    }
}
