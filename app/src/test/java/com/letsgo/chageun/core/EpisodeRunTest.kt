package com.letsgo.chageun.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeRunTest {
    private val parkedOff = PuzzleSignals(turnSignal = TurnDir.OFF, steeringDeg = 0f, odometerKm = 12437.8f)

    private fun run(stage: EpisodeStage = EpisodeStage.SIGN, signals: PuzzleSignals = parkedOff) =
        EpisodeRun(Difficulty.EASY, startIndex = stage.ordinal).also { it.begin(signals) }

    private fun EpisodeRun.turn(vararg dirs: TurnDir): Boolean = dirs.map { onEvent(VehicleEvent.TurnSignal(it), 0) }.last()
    private fun EpisodeRun.angle(deg: Float, at: Long): Boolean = onEvent(VehicleEvent.SteeringAngle(deg), at)

    @Test fun `the code is the last two whole-km digits, and needs two non-zero digits`() {
        assertEquals(3 to 7, Episode1.codeFrom(12437.8f))
        assertNull(Episode1.codeFrom(12430f))
        assertNull(Episode1.codeFrom(12407f))
        assertNull(Episode1.codeFrom(null))
        assertNull(Episode1.codeFrom(-1f))
        assertNull(Episode1.codeFrom(Float.NaN))
    }

    @Test fun `the sign is solved by right-left-left-right and ends only when the signal is off again`() {
        val r = run()
        assertFalse(r.turn(TurnDir.RIGHT, TurnDir.OFF, TurnDir.LEFT, TurnDir.OFF, TurnDir.LEFT, TurnDir.OFF, TurnDir.RIGHT))
        assertTrue(r.view().awaitingFinalOff)
        assertEquals(EpisodeStage.SIGN, r.stage)
        assertTrue(r.turn(TurnDir.OFF))
        assertEquals(EpisodeStage.CODE, r.stage)
        assertEquals(1, r.clues)
    }

    @Test fun `the same state reported twice is one input, so a repeat needs off in between`() {
        val r = run()
        r.turn(TurnDir.RIGHT, TurnDir.RIGHT) // duplicate report
        assertEquals(1, r.view().signProgress)
        r.turn(TurnDir.LEFT, TurnDir.LEFT) // lever never left LEFT: still one input
        assertEquals(2, r.view().signProgress)
        r.turn(TurnDir.RIGHT) // expected LEFT here
        assertTrue(r.view().signMistake)
        assertEquals(1, r.view().signProgress) // a wrong RIGHT is also a fresh first step
    }

    @Test fun `a wrong first move starts over`() {
        val r = run()
        r.turn(TurnDir.LEFT)
        assertTrue(r.view().signMistake)
        assertEquals(0, r.view().signProgress)
    }

    @Test fun `a lever already on at the start is not counted`() {
        val r = run(signals = parkedOff.copy(turnSignal = TurnDir.RIGHT))
        r.turn(TurnDir.RIGHT)
        assertEquals(0, r.view().signProgress)
        r.turn(TurnDir.OFF, TurnDir.RIGHT)
        assertEquals(1, r.view().signProgress)
    }

    @Test fun `the sign can be done on screen`() {
        val r = run()
        r.screenTurn(TurnDir.RIGHT); r.screenTurn(TurnDir.LEFT); r.screenTurn(TurnDir.LEFT)
        assertTrue(r.screenTurn(TurnDir.RIGHT))
        assertEquals(EpisodeStage.CODE, r.stage)
    }

    @Test fun `the code comes from this car's odometer`() {
        val r = run(EpisodeStage.CODE)
        assertTrue(r.codeFromCar)
        assertFalse(r.enterCode(7, 3))
        assertTrue(r.view().codeWrong)
        assertTrue(r.enterCode(3, 7))
        assertEquals(EpisodeStage.DIAL, r.stage)
    }

    @Test fun `without a usable odometer the pillar number is the code`() {
        val r = run(EpisodeStage.CODE, parkedOff.copy(odometerKm = 12400f))
        assertFalse(r.codeFromCar)
        assertEquals(Episode1.PILLAR_CODE, r.code)
    }

    @Test fun `the dial is left a notches then right b notches, each held for 1_5 s`() {
        val r = run(EpisodeStage.DIAL) // code 3,7
        assertEquals(-30f, r.view().dialTarget)
        assertFalse(r.angle(-30f, 0))
        assertFalse(r.tick(1_000))
        assertFalse(r.tick(1_500)) // first step done, stage not yet
        assertEquals(1, r.view().dialStep)
        assertEquals(70f, r.view().dialTarget)
        r.angle(68f, 2_000)
        r.angle(74f, 2_500) // still inside ±5°
        assertTrue(r.tick(3_500))
        assertEquals(EpisodeStage.CENTER, r.stage)
        assertEquals(2, r.clues)
    }

    @Test fun `leaving the window restarts the hold, and right is not left`() {
        val r = run(EpisodeStage.DIAL)
        r.angle(30f, 0) // wrong side
        assertFalse(r.tick(2_000))
        r.angle(-30f, 2_000)
        r.angle(-40f, 3_000) // out of the window
        r.angle(-31f, 3_200)
        assertFalse(r.tick(4_600))
        r.tick(4_700)
        assertEquals(1, r.view().dialStep)
    }

    @Test fun `a lost wheel reading restarts the dial hold (review-safety L6)`() {
        val r = run(EpisodeStage.DIAL)
        r.angle(-30f, 0)
        assertFalse(r.onEvent(VehicleEvent.SteeringLost, 1_000))
        assertFalse(r.tick(1_600)) // the hold did not survive the lost reading
        r.angle(-30f, 1_700)
        r.angle(-30f, 3_300) // held again for 1.6 s: the first number is in
        r.angle(70f, 3_400)
        assertTrue(r.angle(70f, 5_000))
    }

    @Test fun `the dial can be done on screen`() {
        val r = run(EpisodeStage.DIAL)
        r.useScreen()
        r.nudgeScreenDial(-2)
        assertFalse(r.confirmScreenDial())
        assertTrue(r.view().dialWrong)
        r.nudgeScreenDial(-1)
        assertFalse(r.confirmScreenDial())
        r.nudgeScreenDial(10)
        assertTrue(r.confirmScreenDial())
        // The wheel never moved, so "turn it back to centre" is passed at once.
        assertEquals(EpisodeStage.BOILER, r.stage)
    }

    @Test fun `the screen dial never goes past 90 degrees`() {
        val r = run(EpisodeStage.DIAL)
        r.useScreen()
        r.nudgeScreenDial(-20)
        assertEquals(-90f, r.view().screenAngle)
    }

    @Test fun `centre is reached by turning back, or confirmed by the player`() {
        val r = run(EpisodeStage.CENTER)
        assertFalse(r.angle(40f, 0))
        assertTrue(r.angle(4f, 100))
        assertEquals(EpisodeStage.BOILER, r.stage)
        val r2 = run(EpisodeStage.CENTER)
        assertTrue(r2.confirmCentered())
    }

    // ---- P4 boiler

    private val scene = PuzzleSignals(tempDriver = 23f,
        lights = LightSpot.entries.associateWith { false })

    private fun EpisodeRun.temp(c: Float) = onEvent(VehicleEvent.Temperature(c), 0)
    private fun EpisodeRun.light(spot: LightSpot, on: Boolean) = onEvent(VehicleEvent.Light(spot, on), 0)

    @Test fun `the boiler wants 21_5 degrees, then the player puts the temperature back`() {
        val r = run(EpisodeStage.BOILER, scene)
        assertFalse(r.temp(21f))
        assertTrue(r.temp(21.5f))
        assertEquals(EpisodeStage.TEMP_BACK, r.stage)
        assertEquals(3, r.clues)
        r.begin(scene.copy(tempDriver = 21.5f)) // the stage begins again with the new reading
        assertEquals(23f, r.view().originalTemp) // the value from before P4 is kept
        assertFalse(r.temp(22f))
        assertTrue(r.temp(23f))
        assertEquals(EpisodeStage.LIGHTS, r.stage)
    }

    @Test fun `a temperature already at 21_5 at the start is not counted`() {
        val r = run(EpisodeStage.BOILER, scene.copy(tempDriver = 21.5f))
        assertTrue(r.view().boilerAlreadyMet)
        assertFalse(r.temp(21.5f))
        r.temp(22f)
        assertTrue(r.temp(21.5f))
    }

    @Test fun `the boiler can be done on screen in half-degree steps`() {
        val r = run(EpisodeStage.BOILER, scene)
        r.useScreen()
        r.nudgeScreenTemp(-2) // 22.0
        assertFalse(r.confirmScreenTemp())
        assertTrue(r.view().tempWrong)
        r.nudgeScreenTemp(-1)
        assertTrue(r.confirmScreenTemp())
    }

    @Test fun `leaving the temperature as it is is the player's choice`() {
        val r = run(EpisodeStage.TEMP_BACK, scene)
        assertTrue(r.confirmClosing())
        assertEquals(EpisodeStage.LIGHTS, r.stage)
    }

    // ---- P5 lights

    @Test fun `lights go on in reverse clue order once all are off (family uses the rear-left light)`() {
        val r = run(EpisodeStage.LIGHTS, scene) // EASY: clues passenger, driver, rear-left
        assertTrue(r.view().lightsArmed)
        r.light(LightSpot.REAR_LEFT, true)
        r.light(LightSpot.DRIVER, true)
        assertEquals(2, r.view().lightProgress)
        assertTrue(r.light(LightSpot.PASSENGER, true))
        assertEquals(EpisodeStage.LIGHTS_OFF, r.stage)
    }

    @Test fun `playing alone, the cabin light is the first clue so it goes on last (Codex round 2 repro)`() {
        val r = EpisodeRun(Difficulty.NORMAL, EpisodeStage.LIGHTS.ordinal).also { it.begin(scene) }
        assertEquals(listOf(LightSpot.PASSENGER, LightSpot.DRIVER, LightSpot.CABIN), Episode1.lightAnswer(Difficulty.NORMAL))
        r.light(LightSpot.PASSENGER, true); r.light(LightSpot.DRIVER, true)
        // The cabin light switches the rear reading lights on with it; those are not clues here and must not count.
        r.light(LightSpot.REAR_LEFT, true)
        assertFalse(r.view().lightMistake)
        assertTrue(r.light(LightSpot.CABIN, true))
    }

    @Test fun `a light already on at the start must be switched off first`() {
        val r = run(EpisodeStage.LIGHTS, scene.copy(lights = scene.lights + (LightSpot.DRIVER to true)))
        assertFalse(r.view().lightsArmed)
        r.light(LightSpot.REAR_LEFT, true) // not armed: ignored
        assertEquals(0, r.view().lightProgress)
        r.light(LightSpot.REAR_LEFT, false); r.light(LightSpot.DRIVER, false)
        assertTrue(r.view().lightsArmed)
        r.light(LightSpot.REAR_LEFT, true)
        assertEquals(1, r.view().lightProgress)
    }

    @Test fun `a wrong light disarms until all are off again, and the same state twice is one press`() {
        val r = run(EpisodeStage.LIGHTS, scene)
        r.light(LightSpot.REAR_LEFT, true)
        r.light(LightSpot.REAR_LEFT, true) // repeated report
        assertEquals(1, r.view().lightProgress)
        r.light(LightSpot.PASSENGER, true) // wrong: driver was next
        assertTrue(r.view().lightMistake)
        assertFalse(r.view().lightsArmed)
        r.light(LightSpot.DRIVER, true) // ignored while disarmed
        assertEquals(0, r.view().lightProgress)
        r.light(LightSpot.REAR_LEFT, false); r.light(LightSpot.PASSENGER, false); r.light(LightSpot.DRIVER, false)
        assertTrue(r.view().lightsArmed)
    }

    @Test fun `other lights do not count`() {
        val r = run(EpisodeStage.LIGHTS, scene)
        r.light(LightSpot.CABIN, true) // not a clue in family mode
        assertEquals(0, r.view().lightProgress)
        assertFalse(r.view().lightMistake)
    }

    @Test fun `the lights can be done on screen`() {
        val r = run(EpisodeStage.LIGHTS, scene)
        r.useScreen()
        r.screenLight(LightSpot.REAR_LEFT); r.screenLight(LightSpot.DRIVER)
        assertTrue(r.screenLight(LightSpot.PASSENGER))
    }

    @Test fun `the episode ends when the clue lights are off again, or when the player leaves them`() {
        val r = run(EpisodeStage.LIGHTS_OFF, scene.copy(lights = scene.lights + (LightSpot.DRIVER to true) + (LightSpot.PASSENGER to true)))
        assertFalse(r.light(LightSpot.DRIVER, false))
        assertTrue(r.light(LightSpot.PASSENGER, false))
        assertTrue(r.finished)
        val r2 = run(EpisodeStage.LIGHTS_OFF, scene)
        assertTrue(r2.confirmClosing())
        assertTrue(r2.finished)
    }

    @Test fun `the optional dial can be skipped and the story goes on`() {
        val r = run(EpisodeStage.DIAL)
        assertTrue(r.skip())
        assertEquals(EpisodeStage.BOILER, r.stage) // wheel already centred: nothing to put back
        val turned = run(EpisodeStage.DIAL, parkedOff.copy(steeringDeg = -40f))
        assertTrue(turned.skip())
        assertEquals(EpisodeStage.CENTER, turned.stage)
    }

    // ---- put-back stages with nothing to put back (review-safety M3: a stage that ended by itself took the next tap)

    @Test fun `the centre stage is passed at once only when the wheel is already centred`() {
        val r = run(EpisodeStage.DIAL)
        r.angle(-30f, 0); r.angle(-30f, 1_600)
        r.angle(70f, 2_000); assertTrue(r.angle(70f, 3_600))
        assertEquals(EpisodeStage.CENTER, r.stage) // the wheel is at 70°
        val unknown = EpisodeRun(Difficulty.EASY, EpisodeStage.DIAL.ordinal).also { it.begin(parkedOff.copy(steeringDeg = null)) }
        unknown.skip()
        assertEquals(EpisodeStage.CENTER, unknown.stage) // angle unknown: ask
    }

    @Test fun `a boiler done on screen leaves the temperature alone, so its put-back stage is passed`() {
        val r = run(EpisodeStage.BOILER, scene)
        r.useScreen()
        r.nudgeScreenTemp(-3)
        assertTrue(r.confirmScreenTemp())
        assertEquals(EpisodeStage.LIGHTS, r.stage)
        val inCar = run(EpisodeStage.BOILER, scene)
        assertTrue(inCar.temp(21.5f))
        assertEquals(EpisodeStage.TEMP_BACK, inCar.stage) // the car is at 21.5 now: ask to put it back
    }

    @Test fun `a put-back stage done while stopped passes on resume`() {
        val lit = scene.lights + (LightSpot.DRIVER to true) + (LightSpot.PASSENGER to true) + (LightSpot.REAR_LEFT to true)
        val r = run(EpisodeStage.LIGHTS_OFF, scene.copy(lights = lit))
        assertFalse(r.passIfClosingDone()) // the lights are still on
        r.begin(scene) // drove off, parked, switched them off on the stop screen, resumed
        assertTrue(r.passIfClosingDone())
        assertTrue(r.finished)
        assertFalse(r.passIfClosingDone())

        val wheel = run(EpisodeStage.CENTER, parkedOff.copy(steeringDeg = 70f))
        wheel.begin(parkedOff)
        assertTrue(wheel.passIfClosingDone())
        assertEquals(EpisodeStage.BOILER, wheel.stage)
        val sign = run()
        assertFalse(sign.passIfClosingDone()) // answers never pass by themselves
    }

    @Test fun `a clue light the car does not report moves the light puzzle to the screen (review-safety M7)`() {
        val noRearLight = scene.copy(lights = scene.lights - LightSpot.REAR_LEFT)
        val r = run(EpisodeStage.LIGHTS, noRearLight)
        assertTrue(r.view().onScreen)
        assertTrue(r.view().lightsUnreadable)
        r.screenLight(LightSpot.REAR_LEFT); r.screenLight(LightSpot.DRIVER)
        assertTrue(r.screenLight(LightSpot.PASSENGER))
        assertTrue(r.finished)
        val all = run(EpisodeStage.LIGHTS, scene)
        assertFalse(all.view().onScreen) // every clue light readable: the car as usual
    }

    @Test fun `after a restart an unread clue light keeps the lights-off stage open (Codex review repro)`() {
        // Saved at LIGHTS_OFF, app restarted, the clue lights not read yet; another light reports off.
        val unread = scene.copy(lights = mapOf(LightSpot.CABIN to false))
        val r = run(EpisodeStage.LIGHTS_OFF, unread)
        assertFalse(r.passIfClosingDone())
        assertFalse(r.light(LightSpot.CABIN, false))
        assertEquals(EpisodeStage.LIGHTS_OFF, r.stage)
        r.light(LightSpot.PASSENGER, false); r.light(LightSpot.DRIVER, false)
        assertTrue(r.light(LightSpot.REAR_LEFT, false)) // every clue light read as off
        assertTrue(r.finished)
    }

    @Test fun `lights done on screen end the episode without a lights-off stage`() {
        val r = run(EpisodeStage.LIGHTS, scene)
        r.useScreen()
        r.screenLight(LightSpot.REAR_LEFT); r.screenLight(LightSpot.DRIVER)
        assertTrue(r.screenLight(LightSpot.PASSENGER))
        assertTrue(r.finished)
    }

    @Test fun `the sign can switch to screen mode, which only changes what the screen and voice say`() {
        val r = run()
        r.useScreen()
        assertTrue(r.view().onScreen)
        assertTrue(EpisodeScript.voice(r.view()).contains("버튼"))
        assertEquals("", EpisodeScript.role(r.view()))
        val car = run()
        assertTrue(EpisodeScript.voice(car.view()).contains("운전석의 어른"))
        assertEquals(" · 운전석 어른", EpisodeScript.role(car.view()))
    }

    @Test fun `in the family mode the voice says what the dial and boiler pictures mean (review-safety M9)`() {
        val dial = run(EpisodeStage.DIAL).view() // code 3·7 from the odometer
        assertTrue(EpisodeScript.voice(dial).contains("한 칸이 핸들 10도"))
        assertTrue(EpisodeScript.voice(dial).contains("왼쪽으로 3칸"))
        val normal = EpisodeRun(Difficulty.NORMAL, startIndex = EpisodeStage.DIAL.ordinal).also { it.begin(parkedOff) }
        assertFalse(EpisodeScript.voice(normal.view()).contains("10도")) // the normal mode keeps the puzzle
        val boiler = EpisodeScript.voice(run(EpisodeStage.BOILER, scene).view())
        assertTrue(boiler.contains("한 칸이 1도") && boiler.contains("0.5도"))
        assertFalse(boiler.contains("21.5")) // the counting stays with the players
    }

    @Test fun `the episode saves per stage and can be left and continued`() = runTest {
        val progress = RecordingProgressStore(mutableListOf())
        val session = SessionController(EffectLedger(FakeControl()), progress, RecordingAudio(mutableListOf()))
        val r = run(EpisodeStage.CODE)
        assertTrue(session.start(r, GateState.Ready))
        assertEquals(SessionState.Playing(Episode1.ID), session.state)
        session.onGate(GateState.Blocked(setOf(GateBlock.DOOR_OPEN)))
        assertEquals(Episode1.ID to EpisodeStage.CODE.ordinal, progress.run)
        session.finish(keepProgress = true)
        assertEquals(Episode1.ID to EpisodeStage.CODE.ordinal, progress.run)
    }
}
