package com.letsgo.chageun.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectLedgerTest {
    private val key = EffectKey.AMBIENT_BRIGHTNESS

    private fun openLedger(control: EffectControl) = EffectLedger(control).apply { open() }

    @Test fun `restores the value from before the first app change`() = runTest {
        val control = FakeControl().apply { values[key] = 40 }
        val ledger = openLedger(control)
        assertTrue(ledger.apply(key, 70))
        assertTrue(ledger.apply(key, 90))
        assertEquals(listOf(RestoreResult.Restored(key)), ledger.restoreAll())
        assertEquals(40, control.values[key])
        assertTrue(ledger.isEmpty)
    }

    @Test fun `a closed ledger changes nothing`() = runTest {
        val control = FakeControl().apply { values[key] = 40 }
        assertFalse(EffectLedger(control).apply(key, 70))
        assertEquals(40, control.values[key])
    }

    @Test fun `after a restore no effect lands until the ledger is reopened`() = runTest {
        val control = FakeControl().apply { values[key] = 40 }
        val ledger = openLedger(control)
        ledger.apply(key, 70)
        ledger.restoreAll()
        assertFalse(ledger.apply(key, 90))
        assertEquals(40, control.values[key])
        ledger.open()
        assertTrue(ledger.apply(key, 90))
    }

    @Test fun `a slow first read cannot land after the restore (Codex repro)`() = runTest {
        val control = SlowReadControl(original = 40)
        val ledger = openLedger(control)
        val applied = launch { ledger.apply(key, 70) }
        runCurrent() // apply is now waiting on the read
        val restore = launch { assertEquals(emptyList<RestoreResult>(), ledger.restoreAll()) }
        runCurrent()
        control.readGate.complete(Unit)
        applied.join(); restore.join()
        assertEquals(40, control.value)
        assertEquals(emptyList<Int>(), control.writes)
        assertTrue(ledger.isEmpty)
    }

    @Test fun `the car can report the app's write before the call returns (Codex E2E repro)`() = runTest {
        val control = EchoFirstControl(original = 80)
        val ledger = openLedger(control)
        control.onWrite = { v -> ledger.observe(key, v) }
        assertTrue(ledger.apply(key, 60))
        assertTrue(ledger.apply(key, 40)) // previously refused: 60 had been taken for a person's change
        assertTrue(ledger.apply(key, 20))
        assertEquals(listOf(RestoreResult.Restored(key)), ledger.restoreAll())
        assertEquals(80, control.value)
    }

    @Test fun `a failed write is withdrawn, so that value later means someone else`() = runTest {
        val control = EchoFirstControl(original = 80).apply { succeed = false }
        val ledger = openLedger(control)
        assertFalse(ledger.apply(key, 60))
        ledger.observe(key, 60)
        assertEquals(listOf(RestoreResult.LeftForUser(key)), ledger.restoreAll())
    }

    @Test fun `a write the car reported stays the app's even if the call then fails`() = runTest {
        val control = EchoFirstControl(original = 80).apply { succeed = false }
        val ledger = openLedger(control)
        control.onWrite = { v -> ledger.observe(key, v) }
        assertFalse(ledger.apply(key, 60))
        control.succeed = true
        assertEquals(listOf(RestoreResult.Restored(key)), ledger.restoreAll())
    }

    @Test fun `does nothing without control permission`() = runTest {
        val control = FakeControl(allowed = false).apply { values[key] = 40 }
        val ledger = openLedger(control)
        assertFalse(ledger.apply(key, 70))
        assertEquals(40, control.values[key])
        assertEquals(emptyList<RestoreResult>(), ledger.restoreAll())
    }

    @Test fun `refuses to change what it cannot read first`() = runTest {
        val ledger = openLedger(FakeControl())
        assertFalse(ledger.apply(key, 70))
        assertTrue(ledger.isEmpty)
    }

    @Test fun `a value the app never wrote hands the item to the user, even if it later matches`() = runTest {
        val control = FakeControl().apply { values[key] = 40 }
        val ledger = openLedger(control)
        ledger.apply(key, 70)
        ledger.observe(key, 70) // echo of our own write
        ledger.observe(key, 55) // someone else
        ledger.observe(key, 70) // and back to our value: still theirs (22 -> 24 -> 22 case)
        control.values[key] = 70
        assertEquals(listOf(RestoreResult.LeftForUser(key)), ledger.restoreAll())
        assertEquals(70, control.values[key])
    }

    @Test fun `echo of the app's own write is recognised`() = runTest {
        val control = FakeControl().apply { values[key] = 40 }
        val ledger = openLedger(control)
        ledger.apply(key, 70)
        assertTrue(ledger.isAppEcho(key, 70))
        assertFalse(ledger.isAppEcho(key, 40))
    }

    @Test fun `restore is reported unverified when the car keeps another value`() = runTest {
        val control = FakeControl().apply { values[key] = 40 }
        val ledger = openLedger(control)
        ledger.apply(key, 70)
        control.writesStick = false
        assertEquals(listOf(RestoreResult.NotVerified(key)), ledger.restoreAll())
    }

    @Test fun `restore reports no control when permission disappears`() = runTest {
        val control = FakeControl().apply { values[key] = 40 }
        val ledger = openLedger(control)
        ledger.apply(key, 70)
        control.allowed = false
        assertEquals(listOf(RestoreResult.NoControl(key)), ledger.restoreAll())
    }

    @Test fun `discarding forgets pending restores`() = runTest {
        val control = FakeControl().apply { values[key] = 70 }
        val ledger = EffectLedger(control)
        ledger.adopt(listOf(EffectRecord(key, 40, 0)), nowMillis = 1_000)
        ledger.discardAll()
        assertEquals(emptyList<RestoreResult>(), ledger.restoreAll())
        assertEquals(70, control.values[key])
    }

    @Test fun `saved records older than 12 hours are dropped`() {
        val ledger = EffectLedger(FakeControl())
        val now = 100L * 60 * 60 * 1000
        val fresh = EffectRecord(key, 40, now - 60_000)
        val old = EffectRecord(EffectKey("x"), 10, now - EffectLedger.MAX_RECORD_AGE_MILLIS - 1)
        assertEquals(listOf(fresh), ledger.adopt(listOf(fresh, old), now))
    }
    // ---- review-safety L3: durability and the effect setting

    @Test fun `the original is on disk before the first write`() = runTest {
        val control = FakeControl().apply { values[key] = 40 }
        var saved: List<EffectRecord> = emptyList()
        val ledger = EffectLedger(control, persist = { saved = it; true }, nowMillis = { 1_000 }).apply { open() }
        control.beforeWrite = { assertEquals(listOf(EffectRecord(key, 40, 1_000)), saved) }
        assertTrue(ledger.apply(key, 70))
    }

    @Test fun `if the original cannot be saved, nothing is changed (Codex review)`() = runTest {
        val control = FakeControl().apply { values[key] = 40 }
        val ledger = EffectLedger(control, persist = { false }).apply { open() }
        assertFalse(ledger.apply(key, 70))
        assertEquals(40, control.values[key])
        assertTrue(ledger.isEmpty)
    }

    @Test fun `a write cancelled before it answered stays the app's if it lands later`() = runTest {
        val control = CancellableWriteControl(original = 80)
        val ledger = openLedger(control)
        val dim = launch { ledger.apply(key, 20) }
        runCurrent() // the write is out, no answer yet
        dim.cancel(); dim.join() // the session stopped mid-effect
        control.value = 20; ledger.observe(key, 20) // ...and the car applied it anyway
        assertFalse(ledger.isAppEcho(key, 55))
        assertTrue(ledger.isAppEcho(key, 20))
        control.gate.complete(Unit)
        assertEquals(listOf(RestoreResult.Restored(key)), ledger.restoreAll())
        assertEquals(80, control.value)
    }

    @Test fun `an unverified restore is kept and tried again with the same original`() = runTest {
        val control = FakeControl().apply { values[key] = 40 }
        val ledger = openLedger(control)
        ledger.apply(key, 70)
        control.writesStick = false
        assertEquals(listOf(RestoreResult.NotVerified(key)), ledger.restoreAll())
        assertEquals(listOf(40), ledger.records(0).map { it.original })
        control.writesStick = true
        ledger.open()
        assertTrue(ledger.apply(key, 20)) // the next session keeps the first original, not 70
        assertEquals(listOf(RestoreResult.Restored(key)), ledger.restoreAll())
        assertEquals(40, control.values[key])
        assertTrue(ledger.isEmpty)
    }

    @Test fun `turning effects off stops new effects but not the restore`() = runTest {
        val control = FakeControl().apply { values[key] = 40 }
        val ledger = openLedger(control)
        ledger.apply(key, 70)
        control.applyAllowed = false
        assertFalse(ledger.apply(key, 20))
        assertEquals(listOf(RestoreResult.Restored(key)), ledger.restoreAll())
        assertEquals(40, control.values[key])
    }
}

/** Control that reports the new value (through [onWrite]) before the write call returns, like the emulator did. */
class EchoFirstControl(original: Int) : EffectControl {
    var value = original
    var succeed = true
    var onWrite: (Int) -> Unit = {}
    override fun canControl(key: EffectKey) = true
    override suspend fun read(key: EffectKey): Int = value
    override suspend fun write(key: EffectKey, value: Int): Boolean {
        this.value = value
        onWrite(value)
        return succeed
    }
}

/** Control whose first read waits until [readGate] completes, to reproduce a slow vehicle reply. */
class SlowReadControl(original: Int) : EffectControl {
    val readGate = CompletableDeferred<Unit>()
    var value = original
    val writes = mutableListOf<Int>()
    override fun canControl(key: EffectKey) = true
    override suspend fun read(key: EffectKey): Int { readGate.await(); return value }
    override suspend fun write(key: EffectKey, value: Int): Boolean { writes += value; this.value = value; return true }
}

/** Control whose writes wait until [gate] completes, to cancel an effect mid-write. */
class CancellableWriteControl(original: Int) : EffectControl {
    val gate = CompletableDeferred<Unit>()
    var value = original
    override fun canControl(key: EffectKey) = true
    override suspend fun read(key: EffectKey): Int = value
    override suspend fun write(key: EffectKey, value: Int): Boolean { gate.await(); this.value = value; return true }
}
