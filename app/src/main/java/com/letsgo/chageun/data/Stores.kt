package com.letsgo.chageun.data

import android.content.Context
import com.letsgo.chageun.core.Difficulty
import com.letsgo.chageun.core.EffectKey
import com.letsgo.chageun.core.EffectRecord
import com.letsgo.chageun.core.FeatureId
import com.letsgo.chageun.core.FeatureRecord
import com.letsgo.chageun.core.LightSpot
import com.letsgo.chageun.core.LearningStore
import com.letsgo.chageun.core.Progress
import com.letsgo.chageun.core.ProgressStore
import com.letsgo.chageun.core.PuzzleSignals
import com.letsgo.chageun.core.Review
import com.letsgo.chageun.core.Support
import org.json.JSONArray
import org.json.JSONObject

enum class Effects { NONE, SOFT, GAME }

data class Settings(
    val firstRunDone: Boolean = false,
    val detailedGuide: Boolean = true,
    val voice: Boolean = true,
    val largeText: Boolean = false,
    val screenOnly: Boolean = false,
    val effects: Effects = Effects.NONE,
)

/** Everything is stored on the device only. Nothing about the car or the driver leaves it. */
class Stores(context: Context) : LearningStore, ProgressStore {
    private val prefs = context.getSharedPreferences("chageun", Context.MODE_PRIVATE)

    override fun load(): Map<FeatureId, FeatureRecord> {
        val json = prefs.getString(KEY_LEARNING, null) ?: return emptyMap()
        return runCatching {
            val o = JSONObject(json)
            FeatureId.entries.filter { o.has(it.name) }.associateWith { id ->
                val r = o.getJSONObject(id.name)
                FeatureRecord(
                    Support.valueOf(r.getString("s")), Progress.valueOf(r.getString("p")), Review.valueOf(r.getString("r")),
                    learnedDay = if (r.has("d")) r.getLong("d") else null,
                )
            }
        }.getOrDefault(emptyMap())
    }

    override fun save(records: Map<FeatureId, FeatureRecord>) {
        val o = JSONObject()
        for ((id, r) in records) {
            val j = JSONObject().put("s", r.support.name).put("p", r.progress.name).put("r", r.review.name)
            r.learnedDay?.let { j.put("d", it) }
            o.put(id.name, j)
        }
        prefs.edit().putString(KEY_LEARNING, o.toString()).apply()
    }

    override fun saveRun(runId: String, index: Int) {
        prefs.edit().putInt(KEY_RUN_PREFIX + runId, index).apply()
    }

    override fun loadRun(runId: String): Int? =
        if (prefs.contains(KEY_RUN_PREFIX + runId)) prefs.getInt(KEY_RUN_PREFIX + runId, 0) else null

    override fun clearRun(runId: String) {
        prefs.edit().remove(KEY_RUN_PREFIX + runId).apply()
    }

    fun loadRuns(runIds: List<String>): Map<String, Int> = runIds.mapNotNull { id -> loadRun(id)?.let { id to it } }.toMap()

    fun loadSettings(): Settings = Settings(
        firstRunDone = prefs.getBoolean("firstRunDone", false),
        detailedGuide = prefs.getBoolean("detailedGuide", true),
        voice = prefs.getBoolean("voice", true),
        largeText = prefs.getBoolean("largeText", false),
        screenOnly = prefs.getBoolean("screenOnly", false),
        effects = runCatching { Effects.valueOf(prefs.getString("effects", null) ?: "NONE") }.getOrDefault(Effects.NONE),
    )

    fun saveSettings(s: Settings) {
        prefs.edit()
            .putBoolean("firstRunDone", s.firstRunDone).putBoolean("detailedGuide", s.detailedGuide)
            .putBoolean("voice", s.voice).putBoolean("largeText", s.largeText)
            .putBoolean("screenOnly", s.screenOnly).putString("effects", s.effects.name)
            .apply()
    }

    /** Episode difficulty is kept so a continued episode keeps the same clue lights after a restart. */
    fun loadEpisodeDifficulty(): Difficulty =
        runCatching { Difficulty.valueOf(prefs.getString(KEY_EP_DIFFICULTY, null) ?: "EASY") }.getOrDefault(Difficulty.EASY)

    fun saveEpisodeDifficulty(d: Difficulty) {
        prefs.edit().putString(KEY_EP_DIFFICULTY, d.name).apply()
    }

    /**
     * The car when the current session started, kept until the session ends, so a restart (or a crash) cannot make
     * what the game switched on look like it was already on (Codex review of round 1). Only the fields Leftovers uses.
     */
    fun saveSessionBaseline(runId: String, s: PuzzleSignals) {
        val o = JSONObject().put("run", runId)
        s.steeringDeg?.let { o.put("steer", it.toDouble()) }
        s.tempDriver?.let { o.put("temp", it.toDouble()) }
        s.seatHeat?.let { o.put("seat", it) }
        val lights = JSONObject()
        s.lights.forEach { (spot, on) -> lights.put(spot.name, on) }
        o.put("lights", lights)
        prefs.edit().putString(KEY_BASELINE, o.toString()).apply()
    }

    fun loadSessionBaseline(): Pair<String, PuzzleSignals>? = runCatching {
        val o = JSONObject(prefs.getString(KEY_BASELINE, null) ?: return null)
        val lights = o.getJSONObject("lights")
        o.getString("run") to PuzzleSignals(
            steeringDeg = if (o.has("steer")) o.getDouble("steer").toFloat() else null,
            tempDriver = if (o.has("temp")) o.getDouble("temp").toFloat() else null,
            seatHeat = if (o.has("seat")) o.getInt("seat") else null,
            lights = LightSpot.entries.filter { lights.has(it.name) }.associateWith { lights.getBoolean(it.name) },
        )
    }.getOrNull()

    fun clearSessionBaseline() {
        prefs.edit().remove(KEY_BASELINE).apply()
    }

    /** Effect originals kept in case the app is killed mid-effect (12-hour limit applied by the ledger). */
    /**
     * Written synchronously and reports success: the original must be on disk before the car is changed, or a crash
     * right after the write would leave nothing to restore from (Codex review of review-safety L3). The record is tiny.
     */
    fun saveEffectRecords(records: List<EffectRecord>): Boolean {
        val a = JSONArray()
        records.forEach { a.put(JSONObject().put("k", it.key.id).put("o", it.original).put("t", it.savedAtMillis)) }
        return prefs.edit().putString(KEY_EFFECTS, a.toString()).commit()
    }

    fun loadEffectRecords(): List<EffectRecord> = runCatching {
        val a = JSONArray(prefs.getString(KEY_EFFECTS, "[]"))
        (0 until a.length()).map { i -> a.getJSONObject(i).let { EffectRecord(EffectKey(it.getString("k")), it.getInt("o"), it.getLong("t")) } }
    }.getOrDefault(emptyList())

    companion object {
        private const val KEY_LEARNING = "learning"
        private const val KEY_RUN_PREFIX = "run."
        private const val KEY_EP_DIFFICULTY = "ep1.difficulty"
        private const val KEY_EFFECTS = "effects_pending"
        private const val KEY_BASELINE = "session_baseline"
    }
}
