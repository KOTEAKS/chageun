package com.letsgo.chageun

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.letsgo.chageun.audio.Narrator
import com.letsgo.chageun.core.CorePuzzles
import com.letsgo.chageun.core.DexSummary
import com.letsgo.chageun.core.Difficulty
import com.letsgo.chageun.core.EffectKey
import com.letsgo.chageun.core.EffectLedger
import com.letsgo.chageun.core.EffectRecord
import com.letsgo.chageun.core.Episode1
import com.letsgo.chageun.core.EpisodeRun
import com.letsgo.chageun.core.EpisodeScript
import com.letsgo.chageun.core.EpisodeStage
import com.letsgo.chageun.core.EpisodeView
import com.letsgo.chageun.core.FeatureId
import com.letsgo.chageun.core.FeatureRecord
import com.letsgo.chageun.core.GateState
import com.letsgo.chageun.core.HelpOffer
import com.letsgo.chageun.core.LearningLog
import com.letsgo.chageun.core.Leftovers
import com.letsgo.chageun.core.LightSpot
import com.letsgo.chageun.core.ParkingGate
import com.letsgo.chageun.core.Puzzle
import com.letsgo.chageun.core.PuzzleRun
import com.letsgo.chageun.core.PuzzleSignals
import com.letsgo.chageun.core.RestoreResult
import com.letsgo.chageun.core.Review
import com.letsgo.chageun.core.ReviewPuzzles
import com.letsgo.chageun.core.SessionController
import com.letsgo.chageun.core.SessionState
import com.letsgo.chageun.core.Step
import com.letsgo.chageun.core.StepKind
import com.letsgo.chageun.core.StepStatus
import com.letsgo.chageun.core.StopReason
import com.letsgo.chageun.core.TurnDir
import com.letsgo.chageun.data.Effects
import com.letsgo.chageun.data.Settings
import com.letsgo.chageun.data.Stores
import com.letsgo.chageun.vehicle.PleosEffects
import com.letsgo.chageun.vehicle.PleosVehicleSource
import android.os.SystemClock
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

enum class Screen { FIRST_RUN, HOME, CANNOT_START, PUZZLE, EPISODE, STOPPED, DONE, EPISODE_DONE, DEX, WRAP_UP }

/** CAR_SPEED runtime permission. The system dialog calls it "location", so a denial is likely and must be recoverable. */
enum class SpeedPermission { UNKNOWN, GRANTED, DENIED, BLOCKED }

/** Something only the activity can do. */
enum class PlatformAction { ASK_SPEED_PERMISSION, OPEN_APP_SETTINGS }

/** A light the game had switched on that the app switched off again (only possible in P). */
data class AutoOff(val spot: LightSpot, val ok: Boolean)

data class UiState(
    val screen: Screen = Screen.HOME,
    val settings: Settings = Settings(),
    val gate: GateState = GateState.Blocked(emptySet()),
    val signals: PuzzleSignals = PuzzleSignals(),
    val puzzle: Puzzle? = null,
    val stepIndex: Int = 0,
    val status: StepStatus = StepStatus.Waiting,
    val hintLevel: Int = 0,
    val stopped: SessionState.Stopped? = null,
    val restore: List<RestoreResult> = emptyList(),
    val dex: DexSummary = DexSummary(0, 0, 0, 0, 0),
    val records: Map<FeatureId, FeatureRecord> = emptyMap(),
    /** Saved place per run id (chapter-0 puzzles and the episode each keep their own). */
    val savedRuns: Map<String, Int> = emptyMap(),
    val pendingEffectRestore: Boolean = false,
    val episode: EpisodeView? = null,
    val episodeDifficulty: Difficulty = Difficulty.EASY,
    /** Index of the sign bar the screen is showing during "소리로 듣기". */
    val signBeat: Int? = null,
    /** Core features learned on an earlier day and not reviewed yet. */
    val dueReviews: List<FeatureId> = emptyList(),
    /** Debug-only shift of "today", to show the next-day review on the emulator. Shown on screen when not 0. */
    val dayOffset: Int = 0,
    /** Result of the review that just finished, for the done screen. */
    val lastReview: Review? = null,
    /** The car when the current (or last) session started: what was already on is not the game's doing. */
    val baseline: PuzzleSignals? = null,
    /** Lights the app switched off on leaving the episode or on the "게임에서 켠 불 끄기" button. */
    val autoOff: List<AutoOff> = emptyList(),
    val speedPermission: SpeedPermission = SpeedPermission.UNKNOWN,
    /** The rest reminder replaces the caption until the next step. */
    val restNotice: Boolean = false,
    /** review-safety M11: a minute on one step without progress or a hint. */
    val helpOffer: Boolean = false,
    /** A session ended without its check (the app was closed or killed): home lists what it left on or moved. */
    val leftFromLastTime: Boolean = false,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val stores = Stores(app)
    private var settings = stores.loadSettings()
    private val log = LearningLog(stores)
    private val source = PleosVehicleSource(app) { feature, support -> log.setSupport(feature, support); refreshDex() }
    private val effects: PleosEffects = PleosEffects(
        source.vehicle,
        effectsEnabled = { settings.effects != Effects.NONE && !settings.screenOnly },
        onObserved = { key, value -> ledger.observe(key, value) },
    )
    private val narrator: Narrator = Narrator(app, voiceEnabled = { settings.voice }, onFocusLost = { permanent -> if (permanent) stopFor(StopReason.AUDIO_FOCUS_LOST) })
    // The original goes to disk before the first write (review-safety L3).
    private val ledger: EffectLedger = EffectLedger(effects, persist = { stores.saveEffectRecords(it) })
    private val session = SessionController(ledger, stores, narrator)
    private val gate = ParkingGate()
    private var run: PuzzleRun? = null
    private var episode: EpisodeRun? = null
    private var pulseJob: Job? = null
    private var beepJob: Job? = null
    private var pendingRecords: List<EffectRecord> = emptyList()
    private var dayOffset = 0
    /** Taps right after a step change are dropped: a step that ends by itself must not pass the tap on to the next one. */
    private var stepShownAt = 0L
    private var helpOffered = false
    private var lastClues = 0
    private var playMillis = 0L
    private var restShown = false
    private val _actions = MutableSharedFlow<PlatformAction>(extraBufferCapacity = 4)
    val actions: SharedFlow<PlatformAction> = _actions
    /** The day the due-review list was last computed for. */
    private var dueDay = Long.MIN_VALUE

    private val _ui = MutableStateFlow(UiState(
        screen = if (settings.firstRunDone) Screen.HOME else Screen.FIRST_RUN, settings = settings,
        episodeDifficulty = stores.loadEpisodeDifficulty(),
        baseline = stores.loadSessionBaseline()?.second,
        leftFromLastTime = stores.loadSessionBaseline() != null,
    ))
    /** Set by the first "leave" tap; later taps and the run's own events wait for that exit to finish. */
    private var finishing = false
    val ui: StateFlow<UiState> = _ui

    private val playing: Boolean get() = session.state is SessionState.Playing

    init {
        source.start()
        narrator.start()
        refreshDex()
        _ui.update { it.copy(savedRuns = loadRuns()) }
        viewModelScope.launch {
            delay(EFFECT_PROBE_DELAY_MS)
            effects.probe()
            // Effects left behind by a crash: keep them aside and ask first. They reach the ledger only if the player says so,
            // so no later session end can restore them silently.
            val now = System.currentTimeMillis()
            pendingRecords = stores.loadEffectRecords().filter { now - it.savedAtMillis <= EffectLedger.MAX_RECORD_AGE_MILLIS }
            if (pendingRecords.isNotEmpty()) _ui.update { it.copy(pendingEffectRestore = true) }
            else stores.saveEffectRecords(emptyList())
        }
        viewModelScope.launch {
            var tick = 0
            while (true) {
                if (tick++ % REFRESH_EVERY_TICKS == 0) source.refresh()
                if (tick % DAY_CHECK_TICKS == 0 && today() != dueDay) refreshDex() // midnight passed while the app was open
                checkGate()
                if (playing) {
                    countPlayTime()
                    offerHelpIfStuck()
                }
                // The "check before you drive" lists read these live, and change events were seen to go missing.
                val checking = _ui.value.screen in CHECK_SCREENS || (_ui.value.screen == Screen.HOME && _ui.value.leftFromLastTime)
                if (checking && tick % 2 == 0) {
                    source.pollEpisodeSignals(); source.pollLights(); source.pollTemperature()
                } else if (tick % LIGHT_POLL_TICKS == 0) source.pollLights() // keeps the session baseline fresh
                episode?.let { ep ->
                    if (playing) {
                        // Change events were seen to go missing on the emulator, so the episode also polls what it waits for.
                        when (ep.stage) {
                            EpisodeStage.SIGN, EpisodeStage.DIAL, EpisodeStage.CENTER -> source.pollEpisodeSignals()
                            EpisodeStage.BOILER, EpisodeStage.TEMP_BACK -> if (tick % 2 == 0) source.pollTemperature()
                            EpisodeStage.LIGHTS, EpisodeStage.LIGHTS_OFF -> if (tick % 2 == 0) source.pollLights()
                            else -> Unit
                        }
                        if (ep.tick(System.currentTimeMillis())) onEpisodeAdvanced() else publishEpisode()
                    }
                }
                delay(GATE_TICK_MS)
            }
        }
        // review-safety L2: a gear, speed or door change is judged at once, not at the next tick.
        viewModelScope.launch { source.snapshot.collect { checkGate() } }
        viewModelScope.launch { source.signals.collect { s -> _ui.update { it.copy(signals = s) } } }
        viewModelScope.launch {
            source.events.collect { e ->
                if (!playing) return@collect
                // The two collectors run in no fixed order: never take puzzle input while the latest reading is not parked.
                if (ParkingGate.blocksFor(source.snapshot.value).isNotEmpty()) { checkGate(); return@collect }
                val ep = episode
                if (ep != null) {
                    val wasWaitingOff = ep.view().awaitingFinalOff
                    if (ep.onEvent(e, System.currentTimeMillis())) onEpisodeAdvanced()
                    else {
                        publishEpisode()
                        if (!wasWaitingOff && ep.view().awaitingFinalOff) narrator.say(EpisodeScript.voice(ep.view()))
                    }
                    return@collect
                }
                val r = run ?: return@collect
                val before = r.index
                r.onEvent(e)
                if (r.index != before) onStepAdvanced() else publishRun()
            }
        }
    }

    /** The one place the parking gate is applied: on every tick and on every change of the gate signals. */
    private suspend fun checkGate() {
        val g = gate.evaluate(source.snapshot.value, System.currentTimeMillis())
        if (g != _ui.value.gate) Log.i(TAG, "gate -> $g (${source.snapshot.value})")
        _ui.update { it.copy(gate = g) }
        val before = session.state
        if (before is SessionState.Playing && g != GateState.Ready) cancelEffectJobs()
        session.onGate(g)
        if (before is SessionState.Playing && session.state is SessionState.Stopped) onStopped()
    }

    // ---- first run and settings ----
    fun finishFirstRun(s: Settings) {
        settings = s.copy(firstRunDone = true)
        stores.saveSettings(settings)
        _ui.update { it.copy(settings = settings, screen = Screen.HOME) }
    }

    /** Called on every return to the screen: the permission may have been granted in the system settings meanwhile. */
    fun onSpeedPermissionChecked(granted: Boolean) {
        val was = _ui.value.speedPermission
        if (granted) {
            if (was != SpeedPermission.GRANTED) {
                if (was != SpeedPermission.UNKNOWN) source.restartSpeed()
                _ui.update { it.copy(speedPermission = SpeedPermission.GRANTED) }
            }
        } else if (was == SpeedPermission.UNKNOWN || was == SpeedPermission.GRANTED) {
            _ui.update { it.copy(speedPermission = SpeedPermission.DENIED) }
        }
    }

    /** Result of the system dialog. [canAskAgain] is false once Android stops showing the dialog. */
    fun onSpeedPermissionResult(granted: Boolean, canAskAgain: Boolean) {
        if (granted) { source.restartSpeed(); _ui.update { it.copy(speedPermission = SpeedPermission.GRANTED) } }
        else _ui.update { it.copy(speedPermission = if (canAskAgain) SpeedPermission.DENIED else SpeedPermission.BLOCKED) }
    }

    fun fixSpeedPermission() {
        _actions.tryEmit(if (_ui.value.speedPermission == SpeedPermission.BLOCKED) PlatformAction.OPEN_APP_SETTINGS else PlatformAction.ASK_SPEED_PERMISSION)
    }

    /** Debug builds only (MainActivity): pretend it is [days] later, so the next-day review can be demonstrated. */
    fun setDayOffset(days: Int) {
        dayOffset = days
        _ui.update { it.copy(dayOffset = days) }
        refreshDex()
    }

    fun startReview(feature: FeatureId) {
        ReviewPuzzles.forFeature(feature, source.ventilation.value)?.let { startPuzzle(it.id) }
    }

    fun openSettings() = _ui.update { it.copy(screen = Screen.FIRST_RUN) }
    fun openDex() { refreshDex(); _ui.update { it.copy(screen = Screen.DEX) } }
    fun goHome() {
        _ui.update { it.copy(screen = Screen.HOME, savedRuns = loadRuns()) }
        refreshDex()
    }

    /** The app came back to the screen: a new day may have started while it was away. */
    fun onAppShown() = refreshDex()

    // ---- chapter 0 puzzles ----
    fun startPuzzle(id: String) {
        val p = CorePuzzles.byId(id) ?: return
        val g = _ui.value.gate
        if (g != GateState.Ready) { _ui.update { it.copy(screen = Screen.CANNOT_START) }; return }
        // A review always starts over: its help history lives only in this run, so resuming midway could lose it (Codex round 2).
        val saved = if (p.review) 0 else stores.loadRun(id) ?: 0
        // review-safety L4: a run saved at its very end (app killed on the done screen) starts over instead of opening blank.
        val startIndex = if (saved >= p.steps.size) { stores.clearRun(id); 0 } else saved
        val r = PuzzleRun(p, log, startIndex = startIndex)
        if (!session.start(r, g)) return
        run = r; episode = null
        _ui.update { it.copy(screen = Screen.PUZZLE, puzzle = p, restore = emptyList(), episode = null, lastReview = null,
            baseline = baselineFor(p.id), autoOff = emptyList(), leftFromLastTime = false) }
        beginStep()
    }

    fun answer(choice: Int) {
        if (!playing) return
        val r = run ?: return
        val quiz = r.step?.quiz
        val first = r.status !is StepStatus.Answered
        r.answer(choice); publishRun()
        if (quiz != null && first && r.status is StepStatus.Answered) {
            narrator.unhold()
            narrator.say(quiz.resultLine(choice, settings.detailedGuide))
        }
    }
    fun afterAnswer() = advanceWith { it.afterAnswer() }
    fun confirm() = advanceWith { it.confirm() }
    fun doOnScreen() = advanceWith { it.doOnScreen() }
    fun skip() = advanceWith { it.skip() }
    fun continueAlreadyMet() = advanceWith { it.continueAlreadyMet() }

    fun hint() {
        val ep = episode
        val hints = if (ep != null) EpisodeScript.hints(ep.view()) else run?.step?.hints.orEmpty()
        run?.markHelpUsed()
        val level = (_ui.value.hintLevel + 1).coerceAtMost(maxOf(hints.size, 1))
        _ui.update { it.copy(hintLevel = level, helpOffer = false) }
        hints.getOrNull(level - 1)?.let(narrator::say)
    }

    fun repeatVoice() {
        if (!playing) return
        narrator.unhold()
        val ep = episode
        if (ep != null) narrator.say(episodeLine(ep.view(), settings)) else run?.step?.let { narrator.say(stepLine(it, settings)) }
    }

    fun exitPuzzle() = leave()

    // ---- episode 1 ----
    fun setEpisodeDifficulty(d: Difficulty) {
        // review-safety M10: the clue lights depend on the difficulty, so it is locked while a saved run waits.
        if (Episode1.ID in _ui.value.savedRuns) return
        stores.saveEpisodeDifficulty(d)
        _ui.update { it.copy(episodeDifficulty = d) }
    }

    fun startEpisode() {
        val g = _ui.value.gate
        if (g != GateState.Ready) { _ui.update { it.copy(screen = Screen.CANNOT_START) }; return }
        var ep = EpisodeRun(_ui.value.episodeDifficulty, startIndex = stores.loadRun(Episode1.ID) ?: 0)
        // review-safety L4: a run saved at its very end starts over at once (it used to need a second tap).
        if (ep.finished) {
            stores.clearRun(Episode1.ID)
            _ui.update { it.copy(savedRuns = loadRuns()) }
            ep = EpisodeRun(_ui.value.episodeDifficulty, startIndex = 0)
        }
        if (!session.start(ep, g)) return
        episode = ep; run = null
        lastClues = ep.clues
        _ui.update { it.copy(screen = Screen.EPISODE, restore = emptyList(), puzzle = null, baseline = baselineFor(Episode1.ID), autoOff = emptyList(),
            leftFromLastTime = false) }
        beginEpisodeStage()
        dimForEpisode()
    }

    fun episodeScreenTurn(dir: TurnDir) = episodeAction { it.screenTurn(dir) }
    fun episodeEnterCode(a: Int, b: Int) = episodeAction { it.enterCode(a, b) }
    fun episodeUseScreen() = episodeAction { it.useScreen(); false }
    fun episodeNudge(notches: Int) = episodeAction { it.nudgeScreenDial(notches); false }
    fun episodeConfirmDial() = episodeAction { it.confirmScreenDial() }
    fun episodeConfirmCentered() = episodeAction { it.confirmCentered() }
    fun episodeNudgeTemp(halfSteps: Int) = episodeAction { it.nudgeScreenTemp(halfSteps); false }
    fun episodeConfirmTemp() = episodeAction { it.confirmScreenTemp() }
    fun episodeScreenLight(spot: LightSpot) = episodeAction { it.screenLight(spot) }
    fun episodeConfirmClosing() = episodeAction { it.confirmClosing() }
    fun episodeSkip() = episodeAction { it.skip() }

    /** "소리로 듣기": the sign pattern as long and short beeps, with the bar highlighted on screen. Player-initiated only. */
    fun playSignPattern() {
        if (!playing || episode == null) return
        narrator.unhold()
        beepJob?.cancel()
        beepJob = viewModelScope.launch {
            Episode1.SIGN_ANSWER.forEachIndexed { i, dir ->
                val ms = if (dir == TurnDir.RIGHT) LONG_BEEP_MS else SHORT_BEEP_MS
                _ui.update { it.copy(signBeat = i) }
                narrator.beep(ms)
                delay(ms + BEEP_GAP_MS)
            }
            _ui.update { it.copy(signBeat = null) }
        }
    }

    // ---- stop / resume ----
    fun resume() {
        if (finishing) return
        if (session.resume(_ui.value.gate)) {
            narrator.unhold()
            val ep = episode
            if (ep != null) {
                _ui.update { it.copy(screen = Screen.EPISODE, stopped = null) }
                ep.begin(source.signals.value)
                if (ep.passIfClosingDone()) onEpisodeAdvanced() else beginEpisodeStage()
                if (!ep.finished) dimForEpisode()
            } else {
                _ui.update { it.copy(screen = Screen.PUZZLE, stopped = null) }
                beginStep()
            }
        }
    }

    fun endFromStopped() = leave()

    /**
     * Leaving mid-way. The session is finished first (nothing can advance it any more), then the episode's lights are
     * switched off while still parked, then the check screen if anything is left. One exit at a time (Codex round-1 review).
     */
    private fun leave() {
        if (finishing) return
        finishing = true
        val wasEpisode = episode != null
        // review-safety L10: a mistaken tap on 나가기 must not throw away progress. Reviews always start over by design.
        val keepProgress = wasEpisode || run?.puzzle?.review == false
        viewModelScope.launch {
            try {
                finishSession(Screen.HOME, keepProgress = keepProgress, checkLeftovers = true, lightsOff = wasEpisode)
            } finally {
                finishing = false
            }
        }
    }

    /** "확인했어요" on the home list of what the last session left. */
    fun dismissLeftovers() = _ui.update { it.copy(leftFromLastTime = false) }

    /** "게임에서 켠 불 끄기" on the stop and done screens. */
    fun switchOffLightsNow() = viewModelScope.launch { switchOffSessionLights() }

    fun onAppHidden(): Job = stopFor(StopReason.APP_HIDDEN)

    private fun stopFor(reason: StopReason): Job = viewModelScope.launch {
        if (playing) {
            cancelEffectJobs()
            session.stop(reason)
            onStopped()
        }
    }

    fun restorePendingEffects() = viewModelScope.launch {
        if (playing) return@launch
        ledger.adopt(pendingRecords, System.currentTimeMillis())
        pendingRecords = emptyList()
        val results = ledger.restoreAll()
        saveEffectRecords() // only restores that could not be verified stay, for a retry (review-safety L3)
        _ui.update { it.copy(pendingEffectRestore = false, restore = results) }
    }

    fun dismissPendingEffects() = viewModelScope.launch {
        pendingRecords = emptyList()
        stores.saveEffectRecords(emptyList())
        _ui.update { it.copy(pendingEffectRestore = false) }
    }

    // ---- internals: chapter 0 ----
    private fun advanceWith(action: (PuzzleRun) -> Unit) {
        if (!playing || justChanged()) return
        val r = run ?: return
        val before = r.index
        action(r)
        if (r.index != before) onStepAdvanced() else publishRun()
    }

    private fun onStepAdvanced() {
        val r = run ?: return
        stores.saveRun(r.puzzle.id, r.index)
        if (r.finished) {
            // The review is due from the next day; a review run already wrote its own result.
            if (r.puzzle.review) _ui.update { it.copy(lastReview = log[r.puzzle.feature].review) }
            else log.markLearned(r.puzzle.feature, today())
            viewModelScope.launch { finishSession(Screen.DONE) }
        } else {
            beginStep()
            maybePulse()
        }
    }

    private fun beginStep() {
        val r = run ?: return
        r.begin(source.signals.value)
        stepShownAt = SystemClock.uptimeMillis()
        helpOffered = false
        _ui.update { it.copy(hintLevel = 0, restNotice = false, helpOffer = false) }
        publishRun()
        r.step?.let { narrator.say(stepLine(it, settings)) }
    }

    /** Optional atmosphere: a soft brightness lift after each completed step. Restored when the session ends. */
    private fun maybePulse() {
        if (!effectsOn()) return
        pulseJob = viewModelScope.launch {
            if (ledger.apply(EffectKey.AMBIENT_BRIGHTNESS, if (settings.effects == Effects.GAME) 90 else 70)) saveEffectRecords()
        }
    }

    private fun publishRun() {
        val r = run ?: return
        _ui.update { it.copy(puzzle = r.puzzle, stepIndex = r.index, status = r.status) }
    }

    // ---- internals: episode ----
    private fun episodeAction(action: (EpisodeRun) -> Boolean) {
        val ep = episode ?: return
        if (!playing || justChanged()) return
        if (action(ep)) onEpisodeAdvanced() else publishEpisode()
    }

    private fun onEpisodeAdvanced() {
        val ep = episode ?: return
        stores.saveRun(Episode1.ID, ep.index)
        if (ep.finished) {
            viewModelScope.launch {
                finishSession(Screen.EPISODE_DONE) // stops audio, so the closing line comes after
                // The restore can take a moment; if the car left P meanwhile, stay silent (Codex review C5).
                if (ParkingGate.blocksFor(source.snapshot.value).isEmpty()) narrator.say(EpisodeScript.voice(ep.view()))
            }
        } else {
            beginEpisodeStage()
            // A new light clue (sign, safe, boiler) and the escape each get one glow.
            if (ep.clues > lastClues || ep.stage == EpisodeStage.LIGHTS_OFF) clueGlow()
            lastClues = ep.clues
        }
    }

    private fun beginEpisodeStage() {
        val ep = episode ?: return
        ep.begin(source.signals.value)
        // "화면으로 대신": every car task starts on screen, so no line asks for a lever, the wheel or a light.
        if (settings.screenOnly && ep.stage in SCREEN_STAGES) ep.useScreen()
        stepShownAt = SystemClock.uptimeMillis()
        helpOffered = false
        _ui.update { it.copy(hintLevel = 0, signBeat = null, restNotice = false, helpOffer = false) }
        publishEpisode()
        narrator.say(episodeLine(ep.view(), settings))
    }

    private fun publishEpisode() {
        val ep = episode ?: return
        val v = ep.view()
        if (v != _ui.value.episode) _ui.update { it.copy(episode = v) }
    }

    /** The scene goes dark: ambient eases down to 20% (never lower), or only the screen darkens when effects are off. */
    private fun dimForEpisode() {
        if (!effectsOn()) return
        pulseJob?.cancel()
        pulseJob = viewModelScope.launch {
            val from = effects.read(EffectKey.AMBIENT_BRIGHTNESS) ?: return@launch
            if (from <= EPISODE_DIM) return@launch
            for (step in 1..3) {
                val value = from - (from - EPISODE_DIM) * step / 3
                if (!ledger.apply(EffectKey.AMBIENT_BRIGHTNESS, value)) return@launch
                saveEffectRecords()
                delay(DIM_STEP_MS)
            }
        }
    }

    /** A clue appears: a slow, single brightness swell and back (well under one change per second). */
    private fun clueGlow() {
        if (!effectsOn()) return
        pulseJob?.cancel()
        pulseJob = viewModelScope.launch {
            if (!ledger.apply(EffectKey.AMBIENT_BRIGHTNESS, CLUE_GLOW)) return@launch
            saveEffectRecords()
            delay(CLUE_GLOW_MS)
            ledger.apply(EffectKey.AMBIENT_BRIGHTNESS, EPISODE_DIM)
        }
    }

    // ---- internals: shared ----
    private fun effectsOn() = settings.effects != Effects.NONE && !settings.screenOnly

    private fun saveEffectRecords() = stores.saveEffectRecords(ledger.records(System.currentTimeMillis()))

    private fun cancelEffectJobs() {
        pulseJob?.cancel()
        beepJob?.cancel()
        _ui.update { it.copy(signBeat = null) }
    }

    /**
     * [checkLeftovers]: leaving mid-way goes through a "check before you drive" screen when something the session turned
     * on or moved is still that way, or a restore could not be confirmed. The done screens show the same list themselves.
     */
    private suspend fun finishSession(next: Screen, keepProgress: Boolean = false, checkLeftovers: Boolean = false, lightsOff: Boolean = false) {
        cancelEffectJobs()
        val restore = session.finish(keepProgress)
        Log.i(TAG, "session finished, restore=$restore")
        saveEffectRecords() // only restores that could not be verified stay, for a retry (review-safety L3)
        stores.clearSessionBaseline()
        run = null
        episode = null
        if (lightsOff) switchOffSessionLights()
        refreshDex()
        val needsCheck = checkLeftovers && (Leftovers.find(_ui.value.baseline, source.signals.value).any { it.warn } ||
            _ui.value.autoOff.isNotEmpty() || restore.any { it is RestoreResult.NotVerified || it is RestoreResult.NoControl })
        _ui.update { it.copy(screen = if (needsCheck) Screen.WRAP_UP else next, restore = restore, stopped = null, savedRuns = loadRuns()) }
    }

    /**
     * Lights switched on during the session (known off when it started) are switched off: a lit cabin reflects in the
     * windscreen at night. Only possible in P: measured 2026-11-03, the car refuses light control from gear D on, even at
     * 0 km/h, so this runs when the player leaves while parked or taps the button, never at the moment of driving off.
     */
    private suspend fun switchOffSessionLights() {
        if (ParkingGate.blocksFor(source.snapshot.value).isNotEmpty()) return
        val spots = Leftovers.lightsToSwitchOff(_ui.value.baseline, source.signals.value)
        if (spots.isEmpty()) return
        val results = spots.map { AutoOff(it, source.switchOffLight(it)) }
        _ui.update { it.copy(autoOff = it.autoOff + results) }
    }

    /** The start state for [runId]: the one saved before a restart if it belongs to this run, else the car now. */
    private fun baselineFor(runId: String): PuzzleSignals {
        val saved = stores.loadSessionBaseline()?.takeIf { it.first == runId }?.second
        val base = saved ?: source.signals.value
        stores.saveSessionBaseline(runId, base)
        return base
    }

    private fun justChanged(): Boolean = SystemClock.uptimeMillis() - stepShownAt < INPUT_GUARD_MS

    /** Play time in this app run. After [REST_AFTER_MS] one gentle reminder: a parked car with the engine on should not idle for long. */
    private fun offerHelpIfStuck() {
        val readingAnswer = run?.status is StepStatus.Answered
        if (!HelpOffer.due(SystemClock.uptimeMillis() - stepShownAt, helpOffered, _ui.value.hintLevel, readingAnswer)) return
        helpOffered = true
        _ui.update { it.copy(helpOffer = true) }
        narrator.say(HelpOffer.LINE)
    }

    private fun countPlayTime() {
        playMillis += GATE_TICK_MS
        if (!restShown && playMillis >= REST_AFTER_MS) {
            restShown = true
            _ui.update { it.copy(restNotice = true) }
            narrator.say(REST_LINE)
        }
    }

    private fun onStopped() {
        val s = session.state as? SessionState.Stopped ?: return
        saveEffectRecords() // only restores that could not be verified stay, for a retry (review-safety L3)
        _ui.update { it.copy(screen = Screen.STOPPED, stopped = s, restore = s.restore) }
    }

    private fun loadRuns(): Map<String, Int> = stores.loadRuns(CorePuzzles.all.map { it.id } + Episode1.ID)

    private fun today(): Long = LocalDate.now().toEpochDay() + dayOffset

    private fun refreshDex() {
        dueDay = today()
        _ui.update { it.copy(dex = log.summary(), records = FeatureId.entries.associateWith { id -> log[id] }, dueReviews = log.dueReviews(dueDay)) }
    }

    override fun onCleared() {
        effects.release()
        source.stop()
        narrator.release()
    }

    companion object {
        private const val TAG = "Chageun"
        private const val INPUT_GUARD_MS = 600L
        private const val LIGHT_POLL_TICKS = 8
        private val CHECK_SCREENS = setOf(Screen.STOPPED, Screen.WRAP_UP, Screen.DONE, Screen.EPISODE_DONE)
        private const val REST_AFTER_MS = 15 * 60 * 1000L
        const val REST_LINE = "15분 넘게 이용했어요. 시동을 켠 채 오래 머물렀다면 잠시 쉬어 가요. 진행은 언제든 저장돼요."
        private val SCREEN_STAGES = setOf(EpisodeStage.SIGN, EpisodeStage.DIAL, EpisodeStage.BOILER, EpisodeStage.LIGHTS)
        private const val SCREEN_ONLY_LINE = "화면으로 대신하는 중이에요. 차에서 누르지 않아도 돼요. 이 버튼이 어디 있는지만 찾아보고 '찾았어요'를 눌러 주세요."

        /** What a chapter-0 step says: the story line, only the task ("짧게"), or the find-it line ("화면으로 대신"). */
        fun stepLine(step: Step, s: Settings): String = when {
            s.screenOnly && (step.kind == StepKind.VEHICLE_ACTION || step.kind == StepKind.VEHICLE_REVERT) -> SCREEN_ONLY_LINE
            !s.detailedGuide -> step.instruction
            else -> step.voice
        }

        /** What an episode stage says. "짧게" speaks only the task; the clue places stay on screen. */
        fun episodeLine(v: EpisodeView, s: Settings): String =
            if (!s.detailedGuide && v.stage != null) EpisodeScript.instruction(v) else EpisodeScript.voice(v)
        private const val GATE_TICK_MS = 250L
        private const val REFRESH_EVERY_TICKS = 4
        private const val DAY_CHECK_TICKS = 240 // about a minute
        private const val EFFECT_PROBE_DELAY_MS = 2_500L
        private const val EPISODE_DIM = 20
        private const val CLUE_GLOW = 45
        private const val CLUE_GLOW_MS = 1_500L
        private const val DIM_STEP_MS = 600L
        private const val LONG_BEEP_MS = 700
        private const val SHORT_BEEP_MS = 250
        private const val BEEP_GAP_MS = 500L
    }
}
