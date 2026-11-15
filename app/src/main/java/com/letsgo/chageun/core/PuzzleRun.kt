package com.letsgo.chageun.core

/** What the current step is waiting for, shown by the UI. */
sealed interface StepStatus {
    data object Waiting : StepStatus
    /** The goal was already met when the step began. The player may toggle it (normal features) or just continue. */
    data object AlreadyMet : StepStatus
    /** The car gave a value we cannot interpret (e.g. cruise state null). Offer the screen alternative. */
    data object CannotConfirm : StepStatus
    data class Answered(val choice: Int, val correct: Boolean) : StepStatus
}

/**
 * Runs one puzzle step by step and writes honest learning records.
 *
 * - A VEHICLE_ACTION step done through the car records VEHICLE_CONFIRMED; done on screen records GUIDANCE_SEEN.
 * - A goal that was already met at the start is not counted as practice.
 * - Safety features are never asked to be switched off and on again.
 * - Changes that are only the car echoing the app's own effects are ignored via [isAppEcho].
 * - In a review ([Puzzle.review]) any help (hint, doing it on screen, a wrong answer, an already-met start) makes the
 *   result "aided"; finishing without help is "unaided success"; skipping records no review at all. A review writes only
 *   the review axis (and support), never the learning progress, so the two measures stay apart.
 */
class PuzzleRun(
    val puzzle: Puzzle,
    private val log: LearningLog,
    startIndex: Int = 0,
    private val isAppEcho: (VehicleEvent) -> Boolean = { false },
) : SessionRun {
    var index: Int = startIndex.coerceIn(0, puzzle.steps.size)
        private set
    override val runId: String get() = puzzle.id
    override val savedIndex: Int get() = index
    var status: StepStatus = StepStatus.Waiting
        private set
    var usedHelp: Boolean = false
        private set
    private var skippedAny = false

    val finished: Boolean get() = index >= puzzle.steps.size
    val step: Step? get() = puzzle.steps.getOrNull(index)

    /** Call when a step is shown, with the latest known signals. */
    fun begin(signals: PuzzleSignals) {
        status = StepStatus.Waiting
        val s = step ?: return
        val goal = s.goal ?: return
        when (goal.current(signals)) {
            GoalCheck.MET -> {
                status = StepStatus.AlreadyMet
                // In a review the answer was already showing; toggling it afterwards proves less than finding it (Codex round 2).
                if (puzzle.review && s.kind == StepKind.VEHICLE_ACTION) usedHelp = true
            }
            GoalCheck.UNKNOWN -> status = StepStatus.CannotConfirm
            else -> Unit
        }
    }

    /** Returns true when the event completed the step. */
    fun onEvent(event: VehicleEvent): Boolean {
        val s = step ?: return false
        val goal = s.goal ?: return false
        if (s.kind != StepKind.VEHICLE_ACTION && s.kind != StepKind.VEHICLE_REVERT) return false
        if (isAppEcho(event)) return false
        return when (goal.check(event)) {
            GoalCheck.MET -> {
                // Still in the state it started in (e.g. a repeated callback): not a change the player made.
                if (status == StepStatus.AlreadyMet) return false
                if (s.kind == StepKind.VEHICLE_ACTION) progress(Progress.VEHICLE_CONFIRMED)
                log.setSupport(puzzle.feature, Support.SUPPORTED)
                next(); true
            }
            GoalCheck.UNKNOWN -> { status = StepStatus.CannotConfirm; false }
            GoalCheck.NOT_MET -> {
                // A real change away from the goal clears "already met", so the next MET counts as practice.
                if (status == StepStatus.AlreadyMet) status = StepStatus.Waiting
                false
            }
            null -> false
        }
    }

    /** Continue past a goal that was already met. Counts as guidance, not as practice. */
    fun continueAlreadyMet() {
        if (status != StepStatus.AlreadyMet) return
        // Nothing was shown to change, so it cannot count as an unaided success. A revert step that is already off is fine.
        if (step?.kind == StepKind.VEHICLE_ACTION) usedHelp = true
        log.setSupport(puzzle.feature, Support.SUPPORTED)
        progress(Progress.GUIDANCE_SEEN)
        next()
    }

    /** "화면으로 하기": the player follows along on screen instead of in the car. */
    fun doOnScreen() {
        if (step == null) return
        usedHelp = true
        progress(Progress.GUIDANCE_SEEN)
        next()
    }

    /** SCREEN_CONFIRM / INFO steps. */
    fun confirm() {
        val s = step ?: return
        if (s.kind != StepKind.SCREEN_CONFIRM && s.kind != StepKind.INFO) return
        progress(Progress.GUIDANCE_SEEN)
        next()
    }

    fun answer(choice: Int) {
        val q = step?.quiz ?: return
        if (status is StepStatus.Answered) return
        if (choice != q.answer) usedHelp = true
        status = StepStatus.Answered(choice, choice == q.answer)
    }

    /** After the explanation of an answered quiz. */
    fun afterAnswer() {
        if (status is StepStatus.Answered) next()
    }

    fun skip() {
        if (step == null) return
        skippedAny = true
        progress(Progress.SKIPPED)
        next()
    }

    fun markHelpUsed() {
        usedHelp = true
    }

    private fun progress(p: Progress) {
        if (!puzzle.review) log.advance(puzzle.feature, p)
    }

    private fun next() {
        index++
        status = StepStatus.Waiting
        if (finished && puzzle.review && !skippedAny) log.review(puzzle.feature, if (usedHelp) Review.AIDED else Review.UNAIDED_SUCCESS)
    }
}
