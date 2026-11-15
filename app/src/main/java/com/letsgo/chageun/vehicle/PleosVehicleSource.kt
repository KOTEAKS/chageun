package com.letsgo.chageun.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.CruiseControl
import ai.pleos.playground.vehicle.constant.control.LightState
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.CruiseControlStateListener
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.HvacDefrosterStateListener
import ai.pleos.playground.vehicle.listener.HvacSeatTemperatureListener
import ai.pleos.playground.vehicle.listener.HvacTemperatureListener
import ai.pleos.playground.vehicle.listener.LightStateListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.letsgo.chageun.core.CruiseState
import com.letsgo.chageun.core.FeatureId
import com.letsgo.chageun.core.Gear
import com.letsgo.chageun.core.LightSpot
import com.letsgo.chageun.core.PuzzleSignals
import com.letsgo.chageun.core.Reading
import com.letsgo.chageun.core.SignalAge
import com.letsgo.chageun.core.Support
import com.letsgo.chageun.core.TurnDir
import com.letsgo.chageun.core.VehicleEvent
import com.letsgo.chageun.core.VehicleSnapshot
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Reads every signal the app relies on. All of these are read APIs ("사용 가능" on the console),
 * plus AAOS PERF_VEHICLE_SPEED, which the Pleos FAQ names as the way to read vehicle speed.
 */
class PleosVehicleSource(context: Context, private val onSupport: (FeatureId, Support) -> Unit) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    val vehicle = Vehicle(app)
    private var car: Car? = null
    private var propertyManager: CarPropertyManager? = null

    private val _snapshot = MutableStateFlow(VehicleSnapshot())
    val snapshot: StateFlow<VehicleSnapshot> = _snapshot
    private val _signals = MutableStateFlow(PuzzleSignals())
    val signals: StateFlow<PuzzleSignals> = _signals
    private val _events = MutableSharedFlow<VehicleEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<VehicleEvent> = _events
    /** Seat ventilation, read as negative seat-climate levels; UNSUPPORTED when the driver seat range has none (M7). */
    private val _ventilation = MutableStateFlow(Support.UNKNOWN)
    val ventilation: StateFlow<Support> = _ventilation

    private val doors = mapOf(
        "front_left" to DoorValue.ROW_1_LEFT, "front_right" to DoorValue.ROW_1_RIGHT,
        "rear_left" to DoorValue.ROW_2_LEFT, "rear_right" to DoorValue.ROW_2_RIGHT,
    )
    private val doorReading = mutableMapOf<String, Reading<Boolean>>()
    /** Gear and door re-reads still waiting for an answer (review-safety M4). */
    private val ages = SignalAge(SIGNAL_MAX_AGE_MS)
    private var vehicleBound = false
    private var speedBound = false

    private val driverSeat = SeatArea(SeatValue.ROW_1_LEFT)
    private val windshield = WindowArea(WindowValue.FRONT_WINDSHIELD)
    /** Reading lights by seat; the cabin light has its own calls. */
    private val readingAreas = mapOf(
        LightSpot.DRIVER to SeatArea(SeatValue.ROW_1_LEFT),
        LightSpot.PASSENGER to SeatArea(SeatValue.ROW_1_RIGHT),
        LightSpot.REAR_LEFT to SeatArea(SeatValue.ROW_2_LEFT),
    )
    /**
     * Light input is the switch the person pressed. The lit state can follow other lights (on the emulator the cabin light
     * turns every seat's reading-light state on), so it is used only when the switch cannot be read (Codex E2E round 2).
     */
    private val lightState = mutableMapOf<LightSpot, Boolean>()
    private val lightSwitch = mutableMapOf<LightSpot, Boolean>()

    private val gearListener = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) = setGear(gear)
        override fun onFailed(e: Exception) = fail("gear", e) { it.copy(gear = Reading.Failed) }
    }
    private val doorListeners = doors.keys.associateWith { name ->
        object : DoorPositionListener {
            override fun onPositionUpdated(position: Int?) = setDoor(name, position)
            override fun onFailed(e: Exception) { Log.w(TAG, "door $name listener", e); answeredDoor(name, Reading.Failed) }
        }
    }
    private val seatListener = object : HvacSeatTemperatureListener {
        override fun onHvacSeatTemperatureUpdated(level: Int?) { level?.let { emit(VehicleEvent.SeatHeat(it)) } }
        override fun onFailed(e: Exception) { Log.w(TAG, "seat heat listener", e) }
    }
    private val defrostListener = object : HvacDefrosterStateListener {
        override fun onHvacDefrosterStateUpdated(on: Boolean?) { on?.let { emit(VehicleEvent.FrontDefroster(it)) } }
        override fun onFailed(e: Exception) { Log.w(TAG, "defroster listener", e) }
    }
    private val cruiseListener = object : CruiseControlStateListener {
        override fun onCruiseControlStateUpdated(state: CruiseControl?) = emit(VehicleEvent.Cruise(CruiseState.fromSdkName(state?.name)))
        override fun onFailed(e: Exception) { Log.w(TAG, "cruise listener", e) }
    }
    private val turnListener = object : TurnSignalStateListener {
        override fun onTurnSignalStateUpdated(state: TurnSignalValue?) { turnDir(state)?.let { emit(VehicleEvent.TurnSignal(it)) } }
        override fun onFailed(e: Exception) { Log.w(TAG, "turn signal listener", e) }
    }
    private val tempListener = object : HvacTemperatureListener {
        override fun onHvacTemperatureUpdated(value: Float?) { value?.let { emit(VehicleEvent.Temperature(it)) } }
        override fun onFailed(e: Exception) { Log.w(TAG, "temperature listener", e) }
    }
    private val lightListeners = LightSpot.entries.associateWith { spot ->
        object : LightStateListener {
            override fun onLightStateUpdated(state: LightState?) {
                state?.let { setLight(spot, state = it == LightState.ON) }
                // The input is the switch, which has no listener: read it now so a change is not missed outside the light puzzle.
                readSwitch(spot)
            }
            override fun onFailed(e: Exception) { Log.w(TAG, "light listener $spot", e) }
        }
    }
    private val steeringListener = object : SteeringWheelAngleListener {
        override fun onSteeringWheelAngleUpdated(angle: Float?) { angle?.let { emit(VehicleEvent.SteeringAngle(it)) } }
        override fun onFailed(e: Exception) { Log.w(TAG, "steering listener", e); steeringLost() }
    }
    private val speedCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) = setSpeed(value)
        override fun onErrorEvent(propertyId: Int, areaId: Int) = fail("speed", null) { it.copy(speedKmh = Reading.Failed) }
    }

    fun start() {
        try {
            vehicle.initialize()
        } catch (e: Exception) {
            fail("vehicle.initialize", e) { it.copy(connected = false) }
            return
        }
        // The vehicle service needs a moment to bind before calls succeed (seen on the emulator).
        main.postDelayed({ registerVehicle() }, BIND_DELAY_MS)
        startSpeed()
    }

    fun stop() {
        main.removeCallbacksAndMessages(null)
        runCatching {
            vehicle.getGear().unregisterGearSelection(gearListener)
            doors.forEach { (name, value) -> vehicle.getDoor().unregisterDoorPosition(DoorArea(value), doorListeners.getValue(name)) }
            vehicle.getSeat().unregisterHvacSeatTemperature(driverSeat, seatListener)
            vehicle.getHVAC().unregisterHvacDefrosterState(windshield, defrostListener)
            vehicle.getDrivingMode().unregisterCruiseControlState(cruiseListener)
            vehicle.getTurnSignal().unregisterTurnSignalState(turnListener)
            vehicle.getSteeringWheel().unregisterSteeringWheelAngle(steeringListener)
            vehicle.getHVAC().unregisterHvacTemperature(driverSeat, tempListener)
            readingAreas.forEach { (spot, area) -> vehicle.getLight().unregisterReadingLightState(area, lightListeners.getValue(spot)) }
            vehicle.getLight().unregisterCabinLightState(lightListeners.getValue(LightSpot.CABIN))
        }
        runCatching { propertyManager?.unregisterCallback(speedCallback) }
        runCatching { vehicle.release() }
        runCatching { car?.disconnect() }
        vehicleBound = false; speedBound = false
        ages.clear()
        _snapshot.value = VehicleSnapshot()
    }

    private fun registerVehicle() {
        try {
            val gear = vehicle.getGear()
            gear.getCurrentGearSelectionState({ setGear(it) }, { e -> fail("gear.get", e) { s -> s.copy(gear = Reading.Failed) } })
            gear.registerGearSelection(gearListener)

            val door = vehicle.getDoor()
            doors.forEach { (name, value) ->
                door.getCurrentDoorPosition(DoorArea(value), { setDoor(name, it) }, { e -> Log.w(TAG, "door.get $name", e); answeredDoor(name, Reading.Failed) })
                door.registerDoorPosition(DoorArea(value), doorListeners.getValue(name))
            }

            vehicle.getSeat().getHvacSeatTemperature(driverSeat, { level ->
                onSupport(FeatureId.SEAT_HEAT, Support.SUPPORTED)
                level?.let { setSignal(VehicleEvent.SeatHeat(it)) }
            }, { e -> Log.w(TAG, "seat heat read", e) })
            vehicle.getSeat().registerHvacSeatTemperature(driverSeat, seatListener)
            runCatching {
                vehicle.getSeat().checkSeatTemperatureCapabilities({ bounds: Map<SeatArea, AreaBounds<Int>>? ->
                    val range = bounds?.entries?.firstOrNull { SeatValue.ROW_1_LEFT in it.key.values }?.value ?: bounds?.values?.firstOrNull()
                    val min = range?.minValue
                    _ventilation.value = when {
                        min == null -> Support.UNKNOWN
                        min < 0 -> Support.SUPPORTED
                        else -> Support.UNSUPPORTED
                    }
                    Log.i(TAG, "seat climate range ${range?.minValue}..${range?.maxValue} -> ventilation ${_ventilation.value}")
                }, { e -> Log.w(TAG, "seat climate capability", e) })
            }.onFailure { Log.w(TAG, "seat climate capability threw", it) }

            val hvac = vehicle.getHVAC()
            hvac.checkHvacDefrosterCapabilities({ areas ->
                val has = areas?.any { a -> a.values.contains(WindowValue.FRONT_WINDSHIELD) } == true
                onSupport(FeatureId.FRONT_DEFROST, if (has) Support.SUPPORTED else Support.UNSUPPORTED)
            }, { e -> Log.w(TAG, "defroster capability", e) })
            hvac.getHvacDefrosterState(windshield, { on -> on?.let { setSignal(VehicleEvent.FrontDefroster(it)) } }, { e -> Log.w(TAG, "defroster read", e) })
            hvac.registerHvacDefrosterState(windshield, defrostListener)

            val drive = vehicle.getDrivingMode()
            drive.checkCruiseControlStateCapabilities({ config ->
                val readable = config?.accessType == AccessType.READ || config?.accessType == AccessType.READ_WRITE
                onSupport(FeatureId.DRIVE_ASSIST, if (readable) Support.SUPPORTED else Support.UNSUPPORTED)
            }, { e -> Log.w(TAG, "cruise capability", e) })
            drive.getCruiseControlState({ setSignal(VehicleEvent.Cruise(CruiseState.fromSdkName(it?.name))) }, { e -> Log.w(TAG, "cruise read", e) })
            drive.registerCruiseControlState(cruiseListener)

            // Episode 1 inputs. Turn signal also feeds the dex (a lever every car has, but checked like the rest).
            val turn = vehicle.getTurnSignal()
            turn.checkTurnSignalStateCapabilities({ config ->
                val readable = config?.accessType == AccessType.READ || config?.accessType == AccessType.READ_WRITE
                onSupport(FeatureId.TURN_SIGNAL, if (readable) Support.SUPPORTED else Support.UNSUPPORTED)
            }, { e -> Log.w(TAG, "turn signal capability", e) })
            turn.registerTurnSignalState(turnListener)
            vehicle.getSteeringWheel().registerSteeringWheelAngle(steeringListener)
            readEpisodeSignals()
            readOdometer()
            // Episode scene C: driver set temperature and the clue lights.
            runCatching { hvac.registerHvacTemperature(driverSeat, tempListener) }.onFailure { Log.w(TAG, "temperature register", it) }
            runCatching {
                readingAreas.forEach { (spot, area) -> vehicle.getLight().registerReadingLightState(area, lightListeners.getValue(spot)) }
                vehicle.getLight().registerCabinLightState(lightListeners.getValue(LightSpot.CABIN))
            }.onFailure { Log.w(TAG, "light register", it) }
            readTemperature()
            readLights()

            vehicleBound = true
            refreshConnected()
        } catch (e: Exception) {
            fail("vehicle.register", e) { it.copy(connected = false) }
        }
    }

    /**
     * Re-reads the gate signals with getters. Change events were seen to go missing on the emulator
     * (turn signal, gear), so the gate never relies on events alone: a missed P or D is corrected within a second.
     */
    fun refresh() {
        if (!vehicleBound) return
        val now = SystemClock.elapsedRealtime()
        runCatching {
            ages.asked("gear", now)
            vehicle.getGear().getCurrentGearSelectionState({ setGear(it) }, { e ->
                ages.answered("gear")
                fail("gear.refresh", e) { s -> s.copy(gear = Reading.Failed) }
            })
            val door = vehicle.getDoor()
            doors.forEach { (name, value) ->
                ages.asked("door:$name", now)
                door.getCurrentDoorPosition(DoorArea(value), { setDoor(name, it) }, { e -> Log.w(TAG, "door.refresh $name", e); answeredDoor(name, Reading.Failed) })
            }
        }.onFailure { Log.w(TAG, "refresh", it) }
        markStale()
        readOdometer()
        val pm = propertyManager
        if (speedBound && pm != null) {
            runCatching { setSpeed(pm.getProperty<Any>(VehiclePropertyIds.PERF_VEHICLE_SPEED, 0)) }
                .onFailure { e -> fail("speed.refresh", e as? Exception) { s -> s.copy(speedKmh = Reading.Failed) } }
        }
    }

    /**
     * Re-reads the episode inputs with getters and emits them only when they changed. Turn signal change events were seen
     * to go missing on the emulator, so the episode polls these while it waits for a lever or wheel move.
     */
    fun pollEpisodeSignals() {
        if (vehicleBound) readEpisodeSignals()
    }

    private fun readEpisodeSignals() {
        runCatching {
            vehicle.getTurnSignal().getCurrentTurnSignalState({ s ->
                turnDir(s)?.let { if (it != _signals.value.turnSignal) emit(VehicleEvent.TurnSignal(it)) }
            }, { e -> Log.w(TAG, "turn signal read", e) })
            vehicle.getSteeringWheel().getCurrentSteeringWheelAngle({ a ->
                a?.let { if (it != _signals.value.steeringDeg) emit(VehicleEvent.SteeringAngle(it)) }
            }, { e -> Log.w(TAG, "steering read", e); steeringLost() })
        }.onFailure { Log.w(TAG, "episode poll", it) }
    }

    /** Re-reads the driver set temperature (episode P4). */
    fun pollTemperature() {
        if (vehicleBound) readTemperature()
    }

    /** Re-reads the clue lights, state and switch (episode P5). */
    fun pollLights() {
        if (vehicleBound) readLights()
    }

    private fun readTemperature() {
        runCatching {
            vehicle.getHVAC().getHvacTemperature(driverSeat, TemperatureUnit.CELSIUS, { t ->
                t?.let { if (it != _signals.value.tempDriver) emit(VehicleEvent.Temperature(it)) }
            }, { e -> Log.w(TAG, "temperature read", e) })
        }.onFailure { Log.w(TAG, "temperature", it) }
    }

    private fun readLights() {
        runCatching {
            val light = vehicle.getLight()
            readingAreas.forEach { (spot, area) ->
                light.getCurrentReadingLightState(area, { st -> st?.let { setLight(spot, state = it == LightState.ON) } }, { e -> Log.w(TAG, "light state $spot", e) })
                light.getReadingLightSwitch(area, { sw -> sw?.let { setLight(spot, switch = it == LightSwitch.ON) } }, { e -> Log.w(TAG, "light switch $spot", e) })
            }
            light.getCurrentCabinLightState({ st -> st?.let { setLight(LightSpot.CABIN, state = it == LightState.ON) } }, { e -> Log.w(TAG, "cabin state", e) })
            light.getCabinLightSwitch({ sw -> sw?.let { setLight(LightSpot.CABIN, switch = it == LightSwitch.ON) } }, { e -> Log.w(TAG, "cabin switch", e) })
        }.onFailure { Log.w(TAG, "lights", it) }
    }

    private fun readSwitch(spot: LightSpot) {
        runCatching {
            val light = vehicle.getLight()
            val area = readingAreas[spot]
            if (area != null) light.getReadingLightSwitch(area, { sw -> sw?.let { setLight(spot, switch = it == LightSwitch.ON) } }, { e -> Log.w(TAG, "light switch $spot", e) })
            else light.getCabinLightSwitch({ sw -> sw?.let { setLight(LightSpot.CABIN, switch = it == LightSwitch.ON) } }, { e -> Log.w(TAG, "cabin switch", e) })
        }.onFailure { Log.w(TAG, "light switch read $spot", it) }
    }

    @Synchronized
    private fun setLight(spot: LightSpot, state: Boolean? = null, switch: Boolean? = null) {
        state?.let { lightState[spot] = it }
        switch?.let { lightSwitch[spot] = it }
        val on = lightSwitch[spot] ?: (lightState[spot] == true)
        if (_signals.value.lights[spot] != on) {
            Log.i(TAG, "light $spot -> $on (state=${lightState[spot]}, switch=${lightSwitch[spot]})")
            emit(VehicleEvent.Light(spot, on))
        }
    }

    /**
     * Switches one light the game had switched on back off. Works only in P: the car answers 50001 from gear D on, even at
     * 0 km/h (measured 2026-11-03). Returns true when the car accepted it; the light listener reports the result.
     */
    suspend fun switchOffLight(spot: LightSpot): Boolean {
        if (!vehicleBound) return false
        val ok = withTimeoutOrNull(CONTROL_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val done: (Boolean) -> Unit = { if (cont.isActive) cont.resume(it) }
                runCatching {
                    val light = vehicle.getLight()
                    val area = readingAreas[spot]
                    if (area != null) light.setReadingLightSwitch(area, LightSwitch.OFF, { done(true) }, { e -> Log.w(TAG, "light off $spot", e); done(false) })
                    else light.setCabinLightSwitch(LightSwitch.OFF, { done(true) }, { e -> Log.w(TAG, "cabin off", e); done(false) })
                }.onFailure { Log.w(TAG, "light off $spot threw", it); done(false) }
            }
        } ?: false
        Log.i(TAG, "light $spot switched off by app -> $ok")
        if (ok) readLights()
        return ok
    }

    /** Only the episode code uses this. The value stays in memory and is never stored or sent. */
    private fun readOdometer() {
        runCatching {
            vehicle.getOdometer().getCurrentDistanceDriven({ km -> km?.let { v -> _signals.update { it.copy(odometerKm = v) } } },
                { e -> Log.w(TAG, "odometer read", e) })
        }.onFailure { Log.w(TAG, "odometer", it) }
    }

    private fun turnDir(v: TurnSignalValue?): TurnDir? = when (v) {
        TurnSignalValue.LEFT -> TurnDir.LEFT
        TurnSignalValue.RIGHT -> TurnDir.RIGHT
        TurnSignalValue.OFF -> TurnDir.OFF
        null -> null
    }

    /** Call again after the CAR_SPEED runtime permission is granted. Until then speed reads as Failed and the gate stays closed. */
    fun restartSpeed() {
        runCatching { propertyManager?.unregisterCallback(speedCallback) }
        runCatching { car?.disconnect() }
        speedBound = false
        startSpeed()
    }

    private fun startSpeed() {
        car = Car.createCar(app, main, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { c, ready ->
            if (!ready) {
                speedBound = false
                _snapshot.update { it.copy(speedKmh = Reading.Failed) }
                refreshConnected()
                return@createCar
            }
            try {
                val pm = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
                propertyManager = pm
                setSpeed(pm.getProperty<Any>(VehiclePropertyIds.PERF_VEHICLE_SPEED, 0))
                pm.registerCallback(speedCallback, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_ONCHANGE)
                speedBound = true
                refreshConnected()
            } catch (e: Exception) {
                fail("speed", e) { it.copy(speedKmh = Reading.Failed) }
            }
        }
    }

    /**
     * review-safety M4: gear or a door that stopped answering (getters never calling back, as when the emulator's vehicle
     * service went inactive) becomes unreadable instead of keeping its last "parked" value, so the gate stops the session.
     */
    private fun markStale() {
        val stale = ages.stale(SystemClock.elapsedRealtime())
        if (stale.isEmpty()) return
        if ("gear" in stale && _snapshot.value.gear != Reading.Failed) fail("gear stale", null) { it.copy(gear = Reading.Failed) }
        doors.keys.filter { "door:$it" in stale && doorReading[it] != Reading.Failed }.forEach {
            Log.w(TAG, "signal failed: door $it stale")
            setDoorReading(it, Reading.Failed)
        }
    }

    /** review-safety L6: an unreadable wheel must not count as held still. */
    private fun steeringLost() {
        if (_signals.value.steeringDeg != null) emit(VehicleEvent.SteeringLost)
    }

    private fun setGear(gear: VehicleGear?) {
        ages.answered("gear")
        val g = when (gear?.name) {
            "GEAR_PARK" -> Gear.PARK
            "GEAR_REVERSE" -> Gear.REVERSE
            "GEAR_NEUTRAL" -> Gear.NEUTRAL
            null, "GEAR_UNKNOWN" -> Gear.UNKNOWN
            else -> Gear.DRIVE // GEAR_DRIVE and numbered forward gears
        }
        _snapshot.update { it.copy(gear = Reading.Known(g)) }
    }

    /** A null position is treated as a failed read, never as "closed". */
    private fun setDoor(name: String, position: Int?) =
        answeredDoor(name, position?.let { Reading.Known(it > 0) } ?: Reading.Failed)

    private fun answeredDoor(name: String, reading: Reading<Boolean>) {
        ages.answered("door:$name")
        setDoorReading(name, reading)
    }

    /**
     * Any door that failed makes the whole door signal unavailable; doors not read yet keep it pending.
     * SDK callbacks arrive on binder threads while refresh runs on the main thread, so the map is guarded (review-safety L5).
     */
    @Synchronized
    private fun setDoorReading(name: String, reading: Reading<Boolean>) {
        doorReading[name] = reading
        val all = doors.keys.map { doorReading[it] }
        val combined: Reading<Set<String>> = when {
            all.any { it == Reading.Failed } -> Reading.Failed
            all.all { it is Reading.Known } -> Reading.Known(doors.keys.filter { k -> (doorReading[k] as? Reading.Known)?.value == true }.toSet())
            else -> Reading.Pending
        }
        _snapshot.update { it.copy(openDoors = combined) }
    }

    private fun setSpeed(value: CarPropertyValue<*>) {
        val ms = (value.value as? Float)
        _snapshot.update {
            if (value.status != CarPropertyValue.STATUS_AVAILABLE || ms == null) it.copy(speedKmh = Reading.Failed)
            else it.copy(speedKmh = Reading.Known(ms * 3.6f))
        }
    }

    private fun setSignal(event: VehicleEvent) = _signals.update { it.with(event) }

    private fun emit(event: VehicleEvent) {
        setSignal(event)
        _events.tryEmit(event)
    }

    private fun refreshConnected() = _snapshot.update { it.copy(connected = vehicleBound && speedBound) }

    private inline fun fail(what: String, e: Exception?, change: (VehicleSnapshot) -> VehicleSnapshot) {
        Log.w(TAG, "signal failed: $what", e)
        _snapshot.update(change)
    }

    companion object {
        private const val TAG = "Chageun"
        private const val BIND_DELAY_MS = 1_500L
        private const val CONTROL_TIMEOUT_MS = 3_000L
        /** Gear and doors are re-read every second; no answer for 3 s after asking means the signal is gone. */
        private const val SIGNAL_MAX_AGE_MS = 3_000L
    }
}
