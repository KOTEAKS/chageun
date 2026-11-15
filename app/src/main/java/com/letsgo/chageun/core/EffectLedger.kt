package com.letsgo.chageun.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Something the app changes only for atmosphere, such as ambient brightness. Never a safety function. */
data class EffectKey(val id: String) {
    companion object {
        val AMBIENT_BRIGHTNESS = EffectKey("ambient.brightness")
    }
}

/** Vehicle control for effects. On a release build without control permission, [canControl] is false. */
interface EffectControl {
    /** Permission to write the key at all. Restoring depends only on this (review-safety L3). */
    fun canControl(key: EffectKey): Boolean
    /** Whether a new effect may be applied now: permission plus the player's effect setting. */
    fun mayApply(key: EffectKey): Boolean = canControl(key)
    suspend fun read(key: EffectKey): Int?
    suspend fun write(key: EffectKey, value: Int): Boolean
}

sealed interface RestoreResult {
    val key: EffectKey

    data class Restored(override val key: EffectKey) : RestoreResult
    /** The write went out but reading back did not show the original value. */
    data class NotVerified(override val key: EffectKey) : RestoreResult
    /** Someone else changed it after the app did, so the app leaves it alone. */
    data class LeftForUser(override val key: EffectKey) : RestoreResult
    data class NoControl(override val key: EffectKey) : RestoreResult
}

/** A pending restore kept on disk in case the app is killed mid-effect. */
data class EffectRecord(val key: EffectKey, val original: Int, val savedAtMillis: Long)

/**
 * Remembers the original value of every effect the app changes, and puts them back afterwards.
 *
 * Ownership rule: once a value the app never wrote is observed, the item counts as taken over by a person
 * and is not restored. This lowers the chance of fighting the user; it cannot prove what the user wanted.
 *
 * Ordering rule: [apply] and [restoreAll] never interleave, and after [restoreAll] the ledger stays closed until
 * [open] is called for a new session, so a slow effect can never land after the restore.
 *
 * Echo rule (Codex E2E, 2026-11-01): the car's change callback can arrive before the write call returns. A value is
 * therefore registered as "requested" before it is written; a report of a requested value confirms it as the app's own.
 * A write the car refused is withdrawn unless the car already reported it. A write cancelled before it answered (the
 * session stopped mid-effect) may still land, so its value stays the app's if the car reports it later (review-safety L3).
 *
 * Durability (review-safety L3): the original is handed to [persist] before the first write, and an entry whose restore
 * could not be verified is kept, so it can be retried by the next session or after a restart.
 * [observe] is called from the SDK callback thread, so entry state is guarded by [state].
 */
class EffectLedger(
    private val control: EffectControl,
    /** Must return only once the records are durable; false means they are not, and nothing is changed. */
    private val persist: (List<EffectRecord>) -> Boolean = { true },
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private class Entry(val original: Int) {
        /** Writes in flight. */
        val requested = mutableSetOf<Int>()
        /** Values known to be the app's: successful writes, and in-flight values the car already reported. */
        val confirmed = mutableSetOf<Int>()
        /** Writes cancelled before they answered: if the car reports one of these, it was the app's. */
        val uncertain = mutableSetOf<Int>()
        var takenOver = false
    }

    private val entries = linkedMapOf<EffectKey, Entry>()
    private val lock = Mutex()
    private val state = Any()
    @Volatile private var closed = true

    /** Allows effects for a session that has just started or resumed. */
    fun open() {
        closed = false
    }

    suspend fun apply(key: EffectKey, value: Int): Boolean = lock.withLock {
        if (closed || !control.mayApply(key)) return@withLock false
        val entry = synchronized(state) { entries[key] } ?: run {
            // Without the original we could not promise a restore, so we do not change anything.
            val original = control.read(key) ?: return@withLock false
            if (closed) return@withLock false
            val created = Entry(original)
            synchronized(state) { entries[key] = created }
            // On disk before anything changes, in case the app is killed mid-write. If that fails, change nothing.
            if (!persist(records(nowMillis()))) {
                synchronized(state) { entries.remove(key) }
                return@withLock false
            }
            created
        }
        synchronized(state) {
            if (entry.takenOver || closed) return@withLock false
            entry.requested += value
        }
        var ok = false
        var answered = false
        try {
            ok = control.write(key, value)
            answered = true
        } finally {
            synchronized(state) {
                entry.requested -= value
                if (ok) entry.confirmed += value
                else if (!answered) entry.uncertain += value // cancelled: it may still land
            }
        }
        ok
    }

    /** Feed every value the car reports for an effect key. */
    fun observe(key: EffectKey, value: Int) = synchronized(state) {
        val e = entries[key] ?: return@synchronized
        when {
            value == e.original || value in e.confirmed -> Unit
            value in e.requested -> e.confirmed += value // the car reported our write before the call returned
            value in e.uncertain -> e.confirmed += value // a cancelled write that landed after all
            else -> e.takenOver = true
        }
    }

    /** True when the reported value is just the car echoing the app's own write; puzzles must not count it. */
    fun isAppEcho(key: EffectKey, value: Int): Boolean = synchronized(state) {
        val e = entries[key] ?: return@synchronized false
        !e.takenOver && (value in e.confirmed || value in e.requested || value in e.uncertain)
    }

    fun records(nowMillis: Long): List<EffectRecord> = synchronized(state) { entries.map { (k, e) -> EffectRecord(k, e.original, nowMillis) } }

    /** Reloads records saved before a crash. Records older than [maxAgeMillis] are dropped (storage limit, not a right to restore). */
    fun adopt(records: List<EffectRecord>, nowMillis: Long, maxAgeMillis: Long = MAX_RECORD_AGE_MILLIS): List<EffectRecord> {
        val fresh = records.filter { nowMillis - it.savedAtMillis <= maxAgeMillis }
        synchronized(state) { for (r in fresh) entries.getOrPut(r.key) { Entry(r.original) } }
        return fresh
    }

    /** The player chose to keep things as they are: forget every pending restore. */
    suspend fun discardAll() = lock.withLock { synchronized(state) { entries.clear() } }

    /** Closes the ledger first, then restores. Effects requested afterwards are refused until [open]. */
    suspend fun restoreAll(): List<RestoreResult> {
        closed = true
        return lock.withLock {
            val snapshot = synchronized(state) { entries.map { (key, e) -> Triple(key, e.original, e.takenOver) } }
            val results = snapshot.map { (key, original, takenOver) ->
                when {
                    takenOver -> RestoreResult.LeftForUser(key)
                    !control.canControl(key) -> RestoreResult.NoControl(key)
                    else -> {
                        val wrote = control.write(key, original)
                        if (wrote && control.read(key) == original) RestoreResult.Restored(key) else RestoreResult.NotVerified(key)
                    }
                }
            }
            // Keep only what could not be verified: its original is still the one to go back to (review-safety L3).
            val retry = results.filterIsInstance<RestoreResult.NotVerified>().map { it.key }.toSet()
            synchronized(state) { entries.keys.retainAll(retry) }
            results
        }
    }

    val isEmpty: Boolean get() = synchronized(state) { entries.isEmpty() }

    companion object {
        const val MAX_RECORD_AGE_MILLIS = 12L * 60 * 60 * 1000
    }
}
