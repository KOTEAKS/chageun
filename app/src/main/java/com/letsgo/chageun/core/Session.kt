package com.letsgo.chageun.core

enum class StopReason { GATE, APP_HIDDEN, AUDIO_FOCUS_LOST, DISCONNECTED, USER_EXIT }

sealed interface SessionState {
    data object Idle : SessionState
    data class Playing(val puzzleId: String) : SessionState
    data class Stopped(
        val reason: StopReason,
        val blocks: Set<GateBlock>,
        val restore: List<RestoreResult>,
        val puzzleId: String,
    ) : SessionState
}

/** One saved place per run (each chapter-0 puzzle, the episode), so finishing one never erases another. */
interface ProgressStore {
    fun saveRun(runId: String, index: Int)
    fun loadRun(runId: String): Int?
    fun clearRun(runId: String)
}

interface AudioOutput {
    fun stopAll()
}

/** Anything a session can play and save: a chapter-0 puzzle or the episode. */
interface SessionRun {
    val runId: String
    /** The step or stage to come back to. */
    val savedIndex: Int
}

/**
 * Start / stop / resume rules for a puzzle session.
 *
 * - Start and resume both require [GateState.Ready] at that moment, and a user action. Nothing resumes on its own.
 * - Any gate loss while playing stops at once: the state becomes Stopped first (so no vehicle event can advance a step
 *   while the restore runs), then audio off, progress saved, then app effects restored.
 *   Saving comes before restoring so a failed restore can never lose progress.
 */
class SessionController(
    private val ledger: EffectLedger,
    private val progress: ProgressStore,
    private val audio: AudioOutput,
) {
    var state: SessionState = SessionState.Idle
        private set
    var run: SessionRun? = null
        private set
    /** True while effects are being restored after a stop; resuming waits for it. */
    var restoring: Boolean = false
        private set

    fun start(run: SessionRun, gate: GateState): Boolean {
        if (gate != GateState.Ready || state is SessionState.Playing) return false
        this.run = run
        state = SessionState.Playing(run.runId)
        ledger.open()
        return true
    }

    suspend fun onGate(gate: GateState) {
        if (state is SessionState.Playing && gate != GateState.Ready) {
            stop(StopReason.GATE, (gate as? GateState.Blocked)?.reasons.orEmpty())
        }
    }

    suspend fun stop(reason: StopReason, blocks: Set<GateBlock> = emptySet()) {
        val playing = state as? SessionState.Playing ?: return
        val stopped = SessionState.Stopped(reason, blocks, emptyList(), playing.puzzleId)
        state = stopped
        audio.stopAll()
        run?.let { progress.saveRun(it.runId, it.savedIndex) }
        restoring = true
        val restore = try { ledger.restoreAll() } finally { restoring = false }
        if (state === stopped) state = stopped.copy(restore = restore)
    }

    fun resume(gate: GateState): Boolean {
        val stopped = state as? SessionState.Stopped ?: return false
        if (gate != GateState.Ready || run == null || restoring) return false
        state = SessionState.Playing(stopped.puzzleId)
        ledger.open()
        return true
    }

    /**
     * The player finished or quit. [keepProgress] keeps the saved place (the episode saves per scene, so leaving
     * mid-episode can be continued later); otherwise the saved run is cleared.
     */
    suspend fun finish(keepProgress: Boolean = false): List<RestoreResult> {
        val current = run
        state = SessionState.Idle
        audio.stopAll()
        if (current != null) {
            if (keepProgress) progress.saveRun(current.runId, current.savedIndex) else progress.clearRun(current.runId)
        }
        val restore = ledger.restoreAll()
        run = null
        return restore
    }
}
