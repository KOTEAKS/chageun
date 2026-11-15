package com.letsgo.chageun.core

/**
 * review-safety-v0.1 M4: a parking-gate signal that stops answering must not keep its last "parked" value.
 * Seen on the emulator: the Pleos vehicle service went inactive and getters never called back, so gear and doors
 * would have stayed at P / closed.
 *
 * A signal is stale when it was asked and has not answered for [maxAgeMillis]. The clock runs from the first
 * unanswered ask, so asking again every second does not reset it. Time since the last answer is not used: binding at
 * start-up can leave a gap of several seconds before the first re-read, which is not a lost signal (measured 2026-11-03).
 */
class SignalAge(private val maxAgeMillis: Long) {
    private val waitingSince = mutableMapOf<String, Long>()

    @Synchronized
    fun asked(name: String, nowMillis: Long) {
        waitingSince.putIfAbsent(name, nowMillis)
    }

    @Synchronized
    fun answered(name: String) {
        waitingSince.remove(name)
    }

    @Synchronized
    fun stale(nowMillis: Long): Set<String> = waitingSince.filterValues { nowMillis - it > maxAgeMillis }.keys.toSet()

    @Synchronized
    fun clear() = waitingSince.clear()
}
