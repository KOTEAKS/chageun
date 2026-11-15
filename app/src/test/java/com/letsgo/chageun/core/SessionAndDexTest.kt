package com.letsgo.chageun.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionAndDexTest {
    private val calls = mutableListOf<String>()
    private val control = FakeControl().apply { values[EffectKey.AMBIENT_BRIGHTNESS] = 40 }
    private val ledger = EffectLedger(control)
    private val progress = RecordingProgressStore(calls)
    private val session = SessionController(ledger, progress, RecordingAudio(calls))
    private val log = LearningLog(MemoryLearningStore())

    private fun newRun() = PuzzleRun(CorePuzzles.coldMorning, log)

    @Test fun `cannot start unless the gate is ready`() {
        assertFalse(session.start(newRun(), GateState.Settling))
        assertFalse(session.start(newRun(), GateState.Blocked(setOf(GateBlock.NOT_IN_PARK))))
        assertTrue(session.start(newRun(), GateState.Ready))
    }

    @Test fun `losing the gate stops audio, saves, then restores`() = runTest {
        session.start(newRun(), GateState.Ready)
        ledger.apply(EffectKey.AMBIENT_BRIGHTNESS, 80)
        session.onGate(GateState.Blocked(setOf(GateBlock.NOT_IN_PARK)))
        val s = session.state as SessionState.Stopped
        assertEquals(StopReason.GATE, s.reason)
        assertEquals(setOf(GateBlock.NOT_IN_PARK), s.blocks)
        assertEquals(listOf(RestoreResult.Restored(EffectKey.AMBIENT_BRIGHTNESS)), s.restore)
        assertEquals(listOf("audio-stop", "save:0-2:0"), calls)
        assertEquals(40, control.values[EffectKey.AMBIENT_BRIGHTNESS])
    }

    @Test fun `a failed restore never loses progress`() = runTest {
        session.start(newRun(), GateState.Ready)
        ledger.apply(EffectKey.AMBIENT_BRIGHTNESS, 80)
        control.writeSucceeds = false
        session.onGate(GateState.Blocked(setOf(GateBlock.MOVING)))
        assertEquals("0-2" to 0, progress.run)
        assertEquals(listOf(RestoreResult.NotVerified(EffectKey.AMBIENT_BRIGHTNESS)), (session.state as SessionState.Stopped).restore)
    }

    @Test fun `stopped before restoring, and no resume until the restore is done`() = runTest {
        val slow = SlowWriteControl(original = 40)
        val ledger2 = EffectLedger(slow)
        val s2 = SessionController(ledger2, RecordingProgressStore(mutableListOf()), RecordingAudio(mutableListOf()))
        s2.start(newRun(), GateState.Ready)
        ledger2.apply(EffectKey.AMBIENT_BRIGHTNESS, 80) // first write passes the gate below
        val stopping = launch { s2.stop(StopReason.GATE, setOf(GateBlock.NOT_IN_PARK)) }
        runCurrent() // the restore write is now waiting
        assertTrue(s2.state is SessionState.Stopped)
        assertTrue(s2.restoring)
        assertFalse(s2.resume(GateState.Ready))
        slow.writeGate.complete(Unit)
        stopping.join()
        assertFalse(s2.restoring)
        assertTrue(s2.resume(GateState.Ready))
    }

    @Test fun `gate coming back never resumes by itself`() = runTest {
        session.start(newRun(), GateState.Ready)
        session.onGate(GateState.Blocked(setOf(GateBlock.DOOR_OPEN)))
        session.onGate(GateState.Ready)
        assertTrue(session.state is SessionState.Stopped)
    }

    @Test fun `resume needs the user and a ready gate`() = runTest {
        session.start(newRun(), GateState.Ready)
        session.stop(StopReason.APP_HIDDEN)
        assertFalse(session.resume(GateState.Settling))
        assertTrue(session.resume(GateState.Ready))
        assertEquals(SessionState.Playing("0-2"), session.state)
    }

    @Test fun `finish restores effects and clears the saved run`() = runTest {
        session.start(newRun(), GateState.Ready)
        ledger.apply(EffectKey.AMBIENT_BRIGHTNESS, 80)
        val restored = session.finish()
        assertEquals(listOf(RestoreResult.Restored(EffectKey.AMBIENT_BRIGHTNESS)), restored)
        assertEquals(SessionState.Idle, session.state)
        assertEquals(null, progress.run)
    }

    @Test fun `finishing a chapter-0 puzzle keeps the episode's saved place (Codex E2E repro)`() = runTest {
        progress.saveRun(Episode1.ID, 1)
        session.start(newRun(), GateState.Ready)
        session.stop(StopReason.APP_HIDDEN) // saves 0-2 in its own slot
        session.finish()
        assertEquals(1, progress.loadRun(Episode1.ID))
        assertEquals(null, progress.loadRun("0-2"))
    }

    @Test fun `dex counts only what this car supports, and keeps confirmed apart from guidance`() {
        log.setSupport(FeatureId.SEAT_HEAT, Support.SUPPORTED); log.advance(FeatureId.SEAT_HEAT, Progress.VEHICLE_CONFIRMED)
        log.setSupport(FeatureId.FRONT_DEFROST, Support.SUPPORTED); log.advance(FeatureId.FRONT_DEFROST, Progress.GUIDANCE_SEEN)
        log.setSupport(FeatureId.WHEEL_HEAT, Support.UNSUPPORTED)
        log.advance(FeatureId.HUD, Progress.GUIDANCE_SEEN) // explain-only features always count as available
        val s = log.summary()
        assertEquals(1, s.vehicleConfirmed)
        assertEquals(2, s.guidanceSeen)
        assertEquals(1, s.unsupported)
        // 8 detectable: 2 supported, 1 unsupported, 5 not checked yet. Plus 6 explain-only, always available.
        assertEquals(8, s.available)
        assertEquals(5, s.unknown)
    }
}

/** Control whose writes after the first one wait for [writeGate], to hold a restore in progress. */
class SlowWriteControl(original: Int) : EffectControl {
    val writeGate = CompletableDeferred<Unit>()
    private var value = original
    private var writes = 0
    override fun canControl(key: EffectKey) = true
    override suspend fun read(key: EffectKey): Int = value
    override suspend fun write(key: EffectKey, value: Int): Boolean {
        if (writes++ > 0) writeGate.await()
        this.value = value
        return true
    }
}
