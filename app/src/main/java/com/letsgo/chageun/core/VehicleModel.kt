package com.letsgo.chageun.core

enum class Gear { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }

/** A vehicle value that may not have arrived yet, or could not be read at all. */
sealed interface Reading<out T> {
    data class Known<T>(val value: T) : Reading<T>
    data object Pending : Reading<Nothing>
    data object Failed : Reading<Nothing>
}

/** What the parking gate needs. [connected] is false until the vehicle services are bound, and after they drop. */
data class VehicleSnapshot(
    val gear: Reading<Gear> = Reading.Pending,
    val speedKmh: Reading<Float> = Reading.Pending,
    val openDoors: Reading<Set<String>> = Reading.Pending,
    val connected: Boolean = false,
)

/**
 * Driving-assist (cruise) state as the puzzles understand it.
 * The SDK value is mapped in one place ([fromSdkName]) because the release notes warn the mapping may change.
 */
enum class CruiseState {
    STANDBY, ENGAGED, UNKNOWN;

    companion object {
        /**
         * Only ENABLED (standby) is trusted as a positive signal. OTHER, FORCED_DEACTIVATION_WARNING, null and anything new
         * cannot be shown to mean "off", so they map to UNKNOWN and are never counted as a successful release.
         */
        fun fromSdkName(name: String?): CruiseState = when (name) {
            "ENABLED" -> STANDBY
            "ACTIVATED", "USER_OVERRIDE", "SUSPENDED" -> ENGAGED
            else -> UNKNOWN
        }
    }
}

/** Turn signal lever state. One-touch flashes are reported as plain LEFT / RIGHT (measured on the emulator). */
enum class TurnDir { LEFT, RIGHT, OFF }

/** Cabin lights the episode reads. Reading lights are per seat; the cabin (dome) light is one switch. */
enum class LightSpot(val label: String, val where: String) {
    PASSENGER("조수석 독서등", "조수석 쪽"), DRIVER("운전석 독서등", "운전석 쪽"), REAR_LEFT("뒷좌석 왼쪽 독서등", "뒷좌석 왼쪽"), CABIN("실내등", "천장"),
}

/** Vehicle changes the puzzles react to. Values are what the car reports, not what the app requested. */
sealed interface VehicleEvent {
    /** Driver seat climate level: above 0 heats, below 0 ventilates. */
    data class SeatHeat(val level: Int) : VehicleEvent
    data class FrontDefroster(val on: Boolean) : VehicleEvent
    data class Cruise(val state: CruiseState) : VehicleEvent
    data class TurnSignal(val dir: TurnDir) : VehicleEvent
    /** Degrees from centre. Left is negative (AAOS PERF_STEERING_ANGLE convention). */
    data class SteeringAngle(val degrees: Float) : VehicleEvent
    /** Driver-side climate set temperature in °C. */
    data class Temperature(val celsius: Float) : VehicleEvent
    data class Light(val spot: LightSpot, val on: Boolean) : VehicleEvent
    /** The wheel angle could not be read (review-safety L6): its value is unknown until the next reading. */
    data object SteeringLost : VehicleEvent
}

/** The latest value of each puzzle signal, used to check whether a goal is already met when a step starts. */
data class PuzzleSignals(
    val seatHeat: Int? = null,
    val frontDefroster: Boolean? = null,
    val cruise: CruiseState? = null,
    val turnSignal: TurnDir? = null,
    val steeringDeg: Float? = null,
    /** Read only to build the episode 1 code. Never stored or sent anywhere. */
    val odometerKm: Float? = null,
    val tempDriver: Float? = null,
    /** Lights read so far; a spot missing from the map has not been read. */
    val lights: Map<LightSpot, Boolean> = emptyMap(),
) {
    fun with(event: VehicleEvent): PuzzleSignals = when (event) {
        is VehicleEvent.SeatHeat -> copy(seatHeat = event.level)
        is VehicleEvent.FrontDefroster -> copy(frontDefroster = event.on)
        is VehicleEvent.Cruise -> copy(cruise = event.state)
        is VehicleEvent.TurnSignal -> copy(turnSignal = event.dir)
        is VehicleEvent.SteeringAngle -> copy(steeringDeg = event.degrees)
        is VehicleEvent.Temperature -> copy(tempDriver = event.celsius)
        is VehicleEvent.Light -> copy(lights = lights + (event.spot to event.on))
        VehicleEvent.SteeringLost -> copy(steeringDeg = null)
    }
}
