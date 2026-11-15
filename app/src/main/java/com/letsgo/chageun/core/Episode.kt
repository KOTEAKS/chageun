package com.letsgo.chageun.core

import kotlin.math.abs

enum class Difficulty { EASY, NORMAL }

/**
 * Episode 1 stages (planning/puzzle-design.md §5). Saved by index, so new stages are only ever appended.
 * CENTER, TEMP_BACK and LIGHTS_OFF are closing instructions (put things back), not answers.
 */
enum class EpisodeStage(val label: String, val scene: Char) {
    SIGN("안내판", 'A'),
    CODE("이 차만 아는 번호", 'B'), DIAL("핸들 금고", 'B'), CENTER("핸들 가운데로", 'B'),
    BOILER("보일러 기록계", 'C'), TEMP_BACK("온도 되돌리기", 'C'), LIGHTS("거꾸로 켜는 불빛", 'C'), LIGHTS_OFF("불 끄기", 'C');

    val sceneStages: List<EpisodeStage> get() = entries.filter { it.scene == scene }
}

/** Episode 1 "지하 4층 정전". Game tasks only: nothing here is written to the learning log. */
object Episode1 {
    const val ID = "ep1"
    /** Long bar = right, short bar = left: long-short-short-long. */
    val SIGN_ANSWER = listOf(TurnDir.RIGHT, TurnDir.LEFT, TurnDir.LEFT, TurnDir.RIGHT)
    const val DEG_PER_NOTCH = 10f
    const val HOLD_TOLERANCE_DEG = 5f
    const val HOLD_MILLIS = 1_500L
    const val CENTER_TOLERANCE_DEG = 10f
    /** Pillar number "B4-37", used when the odometer cannot give two non-zero digits. */
    val PILLAR_CODE = 3 to 7
    /** Boiler log: 21 stairs and a half landing. */
    const val BOILER_TARGET = 21.5f
    /** Climate steps are 0.5 °C, so "the same temperature" is anything closer than a quarter step. */
    const val TEMP_TOLERANCE = 0.25f
    const val SCREEN_TEMP_MIN = 16f
    const val SCREEN_TEMP_MAX = 30f

    /** Last two digits of the whole-km odometer, or null when unreadable or a digit is 0 (a 0° turn is not a dial step). */
    fun codeFrom(odometerKm: Float?): Pair<Int, Int>? {
        if (odometerKm == null || odometerKm.isNaN() || odometerKm < 0f) return null
        val km = odometerKm.toLong()
        val a = ((km / 10) % 10).toInt()
        val b = (km % 10).toInt()
        return if (a == 0 || b == 0) null else a to b
    }

    /**
     * The three light clues in the order they are found.
     * Family: the child's own seat light is the third clue. Alone: the cabin light is the FIRST clue, so it is switched on
     * last; the cabin light can switch every reading light on with it (emulator: their switches too; many cars light the
     * map lamps with the room lamp), which would spoil any step after it (Codex E2E round 2).
     */
    fun clueLights(d: Difficulty): List<LightSpot> =
        if (d == Difficulty.EASY) listOf(LightSpot.PASSENGER, LightSpot.DRIVER, LightSpot.REAR_LEFT)
        else listOf(LightSpot.CABIN, LightSpot.DRIVER, LightSpot.PASSENGER)

    /** P5 answer: the clues in reverse. The clue order stays on screen, so this is a reverse-order task, not a memory test. */
    fun lightAnswer(d: Difficulty): List<LightSpot> = clueLights(d).reversed()
}

/** What the episode screen shows. */
data class EpisodeView(
    val stage: EpisodeStage?,
    val difficulty: Difficulty,
    val signProgress: Int = 0,
    val awaitingFinalOff: Boolean = false,
    val signMistake: Boolean = false,
    val code: Pair<Int, Int> = Episode1.PILLAR_CODE,
    val codeFromCar: Boolean = false,
    val codeWrong: Boolean = false,
    val dialStep: Int = 0,
    val dialTarget: Float? = null,
    val holdFraction: Float = 0f,
    val onScreen: Boolean = false,
    val screenAngle: Float = 0f,
    val dialWrong: Boolean = false,
    val clues: Int = 0,
    val boilerAlreadyMet: Boolean = false,
    val screenTemp: Float = Episode1.BOILER_TARGET,
    val tempWrong: Boolean = false,
    val originalTemp: Float? = null,
    val lightsArmed: Boolean = false,
    /** The car does not report one of the clue lights, so P5 is played on screen (review-safety M7). */
    val lightsUnreadable: Boolean = false,
    val lightProgress: Int = 0,
    val lightMistake: Boolean = false,
)

/**
 * Runs episode 1.
 *
 * - P1 sign: the turn signal must follow [Episode1.SIGN_ANSWER]. Only real changes count (a repeated report of the same
 *   state is ignored), a same-direction repeat needs OFF in between, and the scene ends only when the signal is back OFF.
 * - P2 code: the last two odometer digits, entered on screen. Falls back to the pillar number.
 * - P3 dial (optional): steering left to a×10°, then right to b×10° (absolute from centre, at most 90°), each held within
 *   ±5° for 1.5 s. Time comes from the caller so tests can drive it. Then "turn the wheel back to centre".
 * - P4 boiler: the driver set temperature must change to 21.5 °C (a value already there at the start is not counted).
 *   Then the player puts it back; the app only reads it (a value the player changed is the player's to undo).
 * - P5 lights: once the three clue lights are all off, they must be switched on in reverse clue order. A wrong light
 *   disarms the puzzle until all three are off again. Then the player switches them off.
 */
class EpisodeRun(val difficulty: Difficulty, startIndex: Int = 0) : SessionRun {
    var index: Int = startIndex.coerceIn(0, EpisodeStage.entries.size)
        private set
    override val runId: String get() = Episode1.ID
    override val savedIndex: Int get() = index
    val stage: EpisodeStage? get() = EpisodeStage.entries.getOrNull(index)
    val finished: Boolean get() = index >= EpisodeStage.entries.size

    var code: Pair<Int, Int> = Episode1.PILLAR_CODE
        private set
    var codeFromCar: Boolean = false
        private set

    private var signProgress = 0
    private var awaitingFinalOff = false
    private var signMistake = false
    private var lastTurn: TurnDir? = null

    private var codeWrong = false

    private var dialStep = 0
    private var holdStart: Long? = null
    private var holdFraction = 0f
    private var onScreen = false
    private var screenAngle = 0f
    private var dialWrong = false

    private var boilerAlreadyMet = false
    private var screenTemp = Episode1.BOILER_TARGET
    private var tempWrong = false
    /** The driver temperature before P4, kept across the P4 → TEMP_BACK step (lost if the app restarts in between). */
    private var originalTemp: Float? = null

    /** The latest wheel angle and driver temperature, so a put-back stage with nothing to put back can be passed. */
    private var angleNow: Float? = null
    private var tempNow: Float? = null

    private val answer = Episode1.lightAnswer(difficulty)
    private val lights = mutableMapOf<LightSpot, Boolean>()
    private var lightsArmed = false
    private var lightsUnreadable = false
    /** P5 was finished with the screen buttons in this run (kept in memory only; a restart forgets it on purpose). */
    private var lightsPlayedOnScreen = false
    private var lightProgress = 0
    private var lightMistake = false

    /** Light clues earned so far: the sign, the safe, the boiler. */
    val clues: Int
        get() = listOf(EpisodeStage.SIGN, EpisodeStage.DIAL, EpisodeStage.BOILER).count { index > it.ordinal }

    private val dialTarget: Float?
        get() = when (dialStep) {
            0 -> -code.first * Episode1.DEG_PER_NOTCH
            1 -> code.second * Episode1.DEG_PER_NOTCH
            else -> null
        }

    /** Call when a stage is shown (start, resume, and after each advance) with the latest signals. */
    fun begin(signals: PuzzleSignals) {
        val fromCar = Episode1.codeFrom(signals.odometerKm)
        code = fromCar ?: Episode1.PILLAR_CODE
        codeFromCar = fromCar != null
        lastTurn = signals.turnSignal
        angleNow = signals.steeringDeg
        tempNow = signals.tempDriver
        signProgress = 0; awaitingFinalOff = false; signMistake = false
        codeWrong = false
        dialStep = 0; holdStart = null; holdFraction = 0f; onScreen = false; screenAngle = 0f; dialWrong = false
        tempWrong = false
        if (stage == EpisodeStage.BOILER) {
            originalTemp = signals.tempDriver
            boilerAlreadyMet = signals.tempDriver?.let { sameTemp(it, Episode1.BOILER_TARGET) } == true
            screenTemp = signals.tempDriver ?: 22f
        }
        lights.clear(); lights.putAll(signals.lights)
        lightProgress = 0; lightMistake = false
        lightsArmed = allCluesOff()
        // review-safety M7: a clue light this car does not report (no rear reading light, say) could never be switched
        // off and on, so the puzzle waited forever at "switch all three off". It moves to the screen from the start.
        lightsUnreadable = stage == EpisodeStage.LIGHTS && answer.any { lights[it] == null }
        if (lightsUnreadable) onScreen = true
    }

    /** Returns true when the event finished the stage. */
    fun onEvent(event: VehicleEvent, nowMillis: Long): Boolean {
        if (event is VehicleEvent.Light) return onLight(event.spot, event.on)
        if (event is VehicleEvent.SteeringLost) {
            // review-safety L6: an unreadable wheel is not a wheel held still; the hold starts over at the next reading.
            angleNow = null
            holdStart = null; holdFraction = 0f
            return false
        }
        if (event is VehicleEvent.SteeringAngle) angleNow = event.degrees
        if (event is VehicleEvent.Temperature) tempNow = event.celsius
        return when (stage) {
            EpisodeStage.SIGN -> (event as? VehicleEvent.TurnSignal)?.let { onTurn(it.dir) } ?: false
            EpisodeStage.DIAL -> (event as? VehicleEvent.SteeringAngle)?.let { onAngle(it.degrees, nowMillis) } ?: false
            EpisodeStage.CENTER -> (event as? VehicleEvent.SteeringAngle)?.let {
                if (abs(it.degrees) <= Episode1.CENTER_TOLERANCE_DEG) { next(); true } else false
            } ?: false
            EpisodeStage.BOILER -> (event as? VehicleEvent.Temperature)?.let { onBoilerTemp(it.celsius) } ?: false
            EpisodeStage.TEMP_BACK -> (event as? VehicleEvent.Temperature)?.let { t ->
                val original = originalTemp
                if (original != null && sameTemp(t.celsius, original)) { next(); true } else false
            } ?: false
            else -> false
        }
    }

    /** Hold timing for the dial. Returns true when the stage finished. */
    fun tick(nowMillis: Long): Boolean {
        if (stage != EpisodeStage.DIAL || onScreen) return false
        val start = holdStart ?: return false
        holdFraction = ((nowMillis - start).toFloat() / Episode1.HOLD_MILLIS).coerceIn(0f, 1f)
        if (nowMillis - start < Episode1.HOLD_MILLIS) return false
        holdStart = null; holdFraction = 0f
        dialStep++
        if (dialStep >= 2) { next(); return true }
        return false
    }

    private fun onTurn(dir: TurnDir): Boolean {
        if (dir == lastTurn) return false // the same state reported again is not a new input
        lastTurn = dir
        if (dir == TurnDir.OFF) {
            if (awaitingFinalOff) { next(); return true }
            return false
        }
        if (awaitingFinalOff) { awaitingFinalOff = false; signProgress = 0 }
        val expected = Episode1.SIGN_ANSWER[signProgress]
        if (dir == expected) {
            signMistake = false
            signProgress++
            if (signProgress == Episode1.SIGN_ANSWER.size) awaitingFinalOff = true
        } else {
            signMistake = true
            signProgress = if (dir == Episode1.SIGN_ANSWER[0]) 1 else 0
        }
        return false
    }

    private fun onAngle(degrees: Float, nowMillis: Long): Boolean {
        if (onScreen) return false
        val target = dialTarget ?: return false
        val inside = abs(degrees - target) <= Episode1.HOLD_TOLERANCE_DEG
        if (!inside) { holdStart = null; holdFraction = 0f; return false }
        if (holdStart == null) holdStart = nowMillis
        return tick(nowMillis)
    }

    private fun onBoilerTemp(celsius: Float): Boolean {
        if (onScreen) return false
        if (!sameTemp(celsius, Episode1.BOILER_TARGET)) { boilerAlreadyMet = false; return false }
        if (boilerAlreadyMet) return false // still the value it started with
        next(); return true
    }

    private fun onLight(spot: LightSpot, on: Boolean): Boolean {
        val was = lights[spot]
        lights[spot] = on
        val s = stage
        if (s == EpisodeStage.LIGHTS_OFF) {
            if (noClueLightOn()) { next(); return true }
            return false
        }
        if (s != EpisodeStage.LIGHTS || onScreen || spot !in answer) return false
        if (!on) {
            if (!lightsArmed && allCluesOff()) { lightsArmed = true; lightMistake = false }
            return false
        }
        if (was == true || !lightsArmed) return false // not a switch-on we can count
        return pressLight(spot)
    }

    private fun pressLight(spot: LightSpot): Boolean {
        if (spot == answer[lightProgress]) {
            lightMistake = false
            lightProgress++
            if (lightProgress == answer.size) {
                if (onScreen) lightsPlayedOnScreen = true
                next(); return true
            }
        } else {
            lightMistake = true
            lightProgress = 0
            if (!onScreen) lightsArmed = false // switch everything off to start again
        }
        return false
    }

    private fun closingAlreadyDone(): Boolean = when (stage) {
        EpisodeStage.CENTER -> angleNow?.let { abs(it) <= Episode1.CENTER_TOLERANCE_DEG } == true
        EpisodeStage.TEMP_BACK -> originalTemp?.let { o -> tempNow?.let { sameTemp(it, o) } } == true
        EpisodeStage.LIGHTS_OFF -> noClueLightOn()
        else -> false
    }

    /** Arming the light puzzle needs every clue light read as off. */
    private fun allCluesOff(): Boolean = answer.all { lights[it] == false }

    /**
     * Putting the lights back needs every clue light read as off. Only when P5 was finished on screen in this run (M7)
     * may a light the car does not report be left out: the game never asked for it to be switched on. After a restart
     * that is not known, so an unread light keeps the stage open (Codex review of batch 2); "이대로 둘게요" still works.
     */
    private fun noClueLightOn(): Boolean =
        answer.all { lights[it] == false || (lights[it] == null && lightsPlayedOnScreen) }

    private fun sameTemp(a: Float, b: Float) = abs(a - b) < Episode1.TEMP_TOLERANCE

    /** P1 on screen: each tap is a lever move followed by OFF, so repeats and the final OFF are handled the same way. */
    fun screenTurn(dir: TurnDir): Boolean {
        if (stage != EpisodeStage.SIGN || dir == TurnDir.OFF) return false
        lastTurn = TurnDir.OFF
        onTurn(dir)
        return onTurn(TurnDir.OFF)
    }

    /** P2: returns true when the code was right. */
    fun enterCode(a: Int, b: Int): Boolean {
        if (stage != EpisodeStage.CODE) return false
        if ((a to b) == code) { next(); return true }
        codeWrong = true
        return false
    }

    /** P1, P3, P4 and P5 on screen instead of the car (or when the car signal cannot be read). */
    fun useScreen() {
        when (stage) {
            EpisodeStage.SIGN -> onScreen = true
            EpisodeStage.DIAL -> { onScreen = true; holdStart = null; holdFraction = 0f; screenAngle = 0f }
            EpisodeStage.BOILER, EpisodeStage.LIGHTS -> { onScreen = true; lightProgress = 0; lightMistake = false; tempWrong = false }
            else -> Unit
        }
    }

    fun nudgeScreenDial(notches: Int) {
        if (!onScreen || stage != EpisodeStage.DIAL) return
        dialWrong = false
        screenAngle = (screenAngle + notches * Episode1.DEG_PER_NOTCH).coerceIn(-90f, 90f)
    }

    /** Returns true when the stage finished. */
    fun confirmScreenDial(): Boolean {
        if (!onScreen || stage != EpisodeStage.DIAL) return false
        val target = dialTarget ?: return false
        if (abs(screenAngle - target) > 0.5f) { dialWrong = true; return false }
        dialWrong = false
        dialStep++
        if (dialStep >= 2) { next(); return true }
        return false
    }

    /** P4 on screen: half-degree steps, like the climate control. */
    fun nudgeScreenTemp(halfSteps: Int) {
        if (!onScreen || stage != EpisodeStage.BOILER) return
        tempWrong = false
        screenTemp = (screenTemp + halfSteps * 0.5f).coerceIn(Episode1.SCREEN_TEMP_MIN, Episode1.SCREEN_TEMP_MAX)
    }

    fun confirmScreenTemp(): Boolean {
        if (!onScreen || stage != EpisodeStage.BOILER) return false
        if (!sameTemp(screenTemp, Episode1.BOILER_TARGET)) { tempWrong = true; return false }
        next(); return true
    }

    /** P5 on screen: tap the lights in order. */
    fun screenLight(spot: LightSpot): Boolean {
        if (!onScreen || stage != EpisodeStage.LIGHTS) return false
        return pressLight(spot)
    }

    /** Closing steps the player confirms or leaves as they are: wheel centre, temperature back, lights off. */
    fun confirmClosing(): Boolean {
        if (stage != EpisodeStage.CENTER && stage != EpisodeStage.TEMP_BACK && stage != EpisodeStage.LIGHTS_OFF) return false
        next(); return true
    }

    /**
     * On resume after a stop: the stop screen asks to put things back, so a put-back stage the player finished while
     * parked passes instead of asking again. Call after [begin] with the current signals.
     */
    fun passIfClosingDone(): Boolean {
        if (finished || !closingAlreadyDone()) return false
        next(); return true
    }

    /** Kept for the dial screen button. */
    fun confirmCentered(): Boolean = if (stage == EpisodeStage.CENTER) confirmClosing() else false

    /** Skip the current stage. Optional tasks may be skipped; the story goes on. */
    fun skip(): Boolean {
        if (finished) return false
        next(); return true
    }

    fun view(): EpisodeView = EpisodeView(
        stage = stage, difficulty = difficulty,
        signProgress = signProgress, awaitingFinalOff = awaitingFinalOff, signMistake = signMistake,
        code = code, codeFromCar = codeFromCar, codeWrong = codeWrong,
        dialStep = dialStep, dialTarget = dialTarget, holdFraction = holdFraction,
        onScreen = onScreen, screenAngle = screenAngle, dialWrong = dialWrong,
        clues = clues,
        boilerAlreadyMet = boilerAlreadyMet, screenTemp = screenTemp, tempWrong = tempWrong, originalTemp = originalTemp,
        lightsArmed = lightsArmed, lightProgress = lightProgress, lightMistake = lightMistake,
        lightsUnreadable = lightsUnreadable,
    )

    private fun next() {
        index++
        // A put-back stage with nothing to put back (the task was done on screen or skipped, or it is already back) is
        // passed at once, so it can neither ask for a pointless step nor finish a moment later under the player's tap.
        if (closingAlreadyDone()) index++
        // The code is kept: it was read at the start and the car has not moved since (moving stops the session).
        signProgress = 0; awaitingFinalOff = false; signMistake = false
        dialStep = 0; holdStart = null; holdFraction = 0f; onScreen = false; screenAngle = 0f; dialWrong = false
        codeWrong = false; tempWrong = false
        lightProgress = 0; lightMistake = false
    }
}
