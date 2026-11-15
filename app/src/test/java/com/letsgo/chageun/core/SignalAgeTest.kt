package com.letsgo.chageun.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** review-safety M4: a gate signal that goes quiet must stop counting as parked. */
class SignalAgeTest {
    @Test fun `a signal that answers its question is fresh`() {
        val age = SignalAge(3_000)
        age.asked("gear", 1_000)
        age.answered("gear")
        assertEquals(emptySet<String>(), age.stale(9_000))
    }

    @Test fun `asking again every second does not reset the clock, so a dead signal goes stale`() {
        val age = SignalAge(3_000)
        for (t in 1_000L..4_000L step 1_000) {
            age.asked("gear", t)
            age.asked("door:front_left", t)
            age.answered("door:front_left")
        }
        assertEquals(emptySet<String>(), age.stale(4_000))
        assertEquals(setOf("gear"), age.stale(4_100))
        age.answered("gear")
        assertEquals(emptySet<String>(), age.stale(4_200))
    }

    @Test fun `a long gap before the first re-read is not a lost signal (start-up, measured)`() {
        val age = SignalAge(3_000)
        age.answered("gear") // the registration answer at 0 s
        age.asked("gear", 3_150) // first re-read only after binding finished
        assertEquals(emptySet<String>(), age.stale(3_160))
    }

    @Test fun `a signal never asked is not stale, and clear forgets open questions`() {
        val age = SignalAge(3_000)
        assertEquals(emptySet<String>(), age.stale(60_000))
        age.asked("gear", 0)
        age.clear()
        assertEquals(emptySet<String>(), age.stale(10_000))
    }
}
