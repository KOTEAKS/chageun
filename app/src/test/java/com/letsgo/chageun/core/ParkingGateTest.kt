package com.letsgo.chageun.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ParkingGateTest {

    @Test fun `parked with doors closed is clean`() {
        assertEquals(emptySet<GateBlock>(), ParkingGate.blocksFor(parked()))
    }

    @Test fun `any gear other than P blocks`() {
        for (g in listOf(Gear.DRIVE, Gear.REVERSE, Gear.NEUTRAL, Gear.UNKNOWN)) {
            assertEquals(setOf(GateBlock.NOT_IN_PARK), ParkingGate.blocksFor(parked(gear = g)))
        }
    }

    @Test fun `forward and reverse motion both block`() {
        assertEquals(setOf(GateBlock.MOVING), ParkingGate.blocksFor(parked(speed = 3f)))
        assertEquals(setOf(GateBlock.MOVING), ParkingGate.blocksFor(parked(speed = -2f)))
    }

    @Test fun `tiny float noise does not block`() {
        assertEquals(emptySet<GateBlock>(), ParkingGate.blocksFor(parked(speed = 0.05f)))
    }

    @Test fun `an open door blocks`() {
        assertEquals(setOf(GateBlock.DOOR_OPEN), ParkingGate.blocksFor(parked(doorsOpen = setOf("rear_left"))))
    }

    @Test fun `missing, failed or disconnected signals block`() {
        assertEquals(setOf(GateBlock.SIGNAL_UNAVAILABLE), ParkingGate.blocksFor(parked().copy(speedKmh = Reading.Pending)))
        assertEquals(setOf(GateBlock.SIGNAL_UNAVAILABLE), ParkingGate.blocksFor(parked().copy(gear = Reading.Failed)))
        assertEquals(setOf(GateBlock.SIGNAL_UNAVAILABLE), ParkingGate.blocksFor(parked().copy(connected = false)))
        assertEquals(setOf(GateBlock.SIGNAL_UNAVAILABLE), ParkingGate.blocksFor(VehicleSnapshot()))
    }

    @Test fun `ready only after conditions hold for the settle time`() {
        val gate = ParkingGate(settleMillis = 2_000)
        assertEquals(GateState.Settling, gate.evaluate(parked(), 0))
        assertEquals(GateState.Settling, gate.evaluate(parked(), 1_999))
        assertEquals(GateState.Ready, gate.evaluate(parked(), 2_000))
    }

    @Test fun `a block resets the settle timer`() {
        val gate = ParkingGate(settleMillis = 2_000)
        gate.evaluate(parked(), 0)
        assertEquals(GateState.Blocked(setOf(GateBlock.DOOR_OPEN)), gate.evaluate(parked(doorsOpen = setOf("front_left")), 1_000))
        assertEquals(GateState.Settling, gate.evaluate(parked(), 1_500))
        assertEquals(GateState.Settling, gate.evaluate(parked(), 3_000))
        assertEquals(GateState.Ready, gate.evaluate(parked(), 3_500))
    }
}
