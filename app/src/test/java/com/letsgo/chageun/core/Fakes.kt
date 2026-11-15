package com.letsgo.chageun.core

class FakeControl(var allowed: Boolean = true) : EffectControl {
    val values = mutableMapOf<EffectKey, Int>()
    var writeSucceeds = true
    /** When false, writes report success but the car keeps the old value (restore cannot be verified). */
    var writesStick = true
    /** The player's effect setting: off refuses new effects but never the restore. */
    var applyAllowed = true
    var beforeWrite: () -> Unit = {}

    override fun canControl(key: EffectKey) = allowed
    override fun mayApply(key: EffectKey) = allowed && applyAllowed
    override suspend fun read(key: EffectKey): Int? = values[key]
    override suspend fun write(key: EffectKey, value: Int): Boolean {
        beforeWrite()
        if (!writeSucceeds) return false
        if (writesStick) values[key] = value
        return true
    }
}

class MemoryLearningStore : LearningStore {
    var saved: Map<FeatureId, FeatureRecord> = emptyMap()
    override fun load() = saved
    override fun save(records: Map<FeatureId, FeatureRecord>) { saved = records }
}

class RecordingProgressStore(private val calls: MutableList<String>) : ProgressStore {
    val runs = linkedMapOf<String, Int>()
    /** The most recently saved run, for tests that play one run. */
    val run: Pair<String, Int>? get() = runs.entries.lastOrNull()?.toPair()
    override fun saveRun(runId: String, index: Int) { calls += "save:$runId:$index"; runs.remove(runId); runs[runId] = index }
    override fun loadRun(runId: String) = runs[runId]
    override fun clearRun(runId: String) { calls += "clear:$runId"; runs.remove(runId) }
}

class RecordingAudio(private val calls: MutableList<String>) : AudioOutput {
    override fun stopAll() { calls += "audio-stop" }
}

fun parked(doorsOpen: Set<String> = emptySet(), speed: Float = 0f, gear: Gear = Gear.PARK) = VehicleSnapshot(
    gear = Reading.Known(gear),
    speedKmh = Reading.Known(speed),
    openDoors = Reading.Known(doorsOpen),
    connected = true,
)
