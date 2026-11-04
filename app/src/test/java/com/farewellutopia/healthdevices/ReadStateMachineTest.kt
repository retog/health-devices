package com.farewellutopia.healthdevices

import com.farewellutopia.healthdevices.ble.ReadAction
import com.farewellutopia.healthdevices.ble.ReadEvent
import com.farewellutopia.healthdevices.ble.ReadFailure
import com.farewellutopia.healthdevices.ble.ReadStateMachine
import com.farewellutopia.healthdevices.ble.ReadStateMachine.State
import com.farewellutopia.healthdevices.ble.Uuids
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReadStateMachineTest {

    private fun machine() = ReadStateMachine(Uuids.BLOOD_PRESSURE, Uuids.BLOOD_PRESSURE_MEASUREMENT)

    private val fullDevice: Map<UUID, Set<UUID>> = mapOf(
        Uuids.BLOOD_PRESSURE to setOf(Uuids.BLOOD_PRESSURE_MEASUREMENT),
        Uuids.DEVICE_INFORMATION to setOf(Uuids.MANUFACTURER_NAME, Uuids.MODEL_NUMBER),
        Uuids.CURRENT_TIME to setOf(Uuids.sig(0x2A2B)),
    )
    private val bareDevice: Map<UUID, Set<UUID>> = mapOf(Uuids.BLOOD_PRESSURE to setOf(Uuids.BLOOD_PRESSURE_MEASUREMENT))

    private fun connected(m: ReadStateMachine): List<ReadAction> {
        assertEquals(listOf(ReadAction.Connect), m.start())
        assertEquals(State.CONNECTING, m.state)
        return m.on(ReadEvent.ConnectionChanged(connected = true, status = 0))
    }

    @Test
    fun happyPathWithDeviceInformation() {
        val m = machine()
        assertEquals(listOf(ReadAction.DiscoverServices), connected(m))
        assertEquals(listOf(ReadAction.ReadCharacteristic(Uuids.MANUFACTURER_NAME)), m.on(ReadEvent.ServicesDiscovered(0, fullDevice)))
        assertEquals(State.READING_INFO, m.state)
        assertEquals(
            listOf(ReadAction.ReadCharacteristic(Uuids.MODEL_NUMBER)),
            m.on(ReadEvent.CharacteristicRead(Uuids.MANUFACTURER_NAME, "Beurer\u0000".toByteArray(), 0)),
        )
        assertEquals(listOf(ReadAction.Settle(ReadStateMachine.SETTLE_MS)), m.on(ReadEvent.CharacteristicRead(Uuids.MODEL_NUMBER, "BM81".toByteArray(), 0)))
        assertEquals(State.SETTLING, m.state)
        assertEquals(listOf(ReadAction.Subscribe), m.on(ReadEvent.SettleElapsed))
        assertEquals(State.SUBSCRIBING, m.state)
        assertTrue(m.on(ReadEvent.Subscribed(0)).isEmpty())
        assertEquals(State.COLLECTING, m.state)
        assertTrue(m.on(ReadEvent.Measurement(byteArrayOf(1))).isEmpty())
        assertTrue(m.on(ReadEvent.Measurement(byteArrayOf(2))).isEmpty())
        val actions = m.on(ReadEvent.ConnectionChanged(connected = false, status = 0))
        val finish = assertIs<ReadAction.Finish>(actions.single())
        assertNull(finish.outcome.failure)
        assertEquals(2, finish.outcome.frames.size)
        assertEquals("Beurer", finish.outcome.manufacturer)
        assertEquals("BM81", finish.outcome.model)
        assertTrue(finish.outcome.hasCurrentTimeService)
        assertEquals(State.DONE, m.state)
        // Nothing after DONE.
        assertTrue(m.on(ReadEvent.Measurement(byteArrayOf(3))).isEmpty())
    }

    @Test
    fun deviceWithoutInformationServiceSettlesAtOnce() {
        val m = machine()
        connected(m)
        assertEquals(listOf(ReadAction.Settle(ReadStateMachine.SETTLE_MS)), m.on(ReadEvent.ServicesDiscovered(0, bareDevice)))
        assertEquals(State.SETTLING, m.state)
    }

    @Test
    fun failedInformationReadIsNotFatal() {
        val m = machine()
        connected(m)
        m.on(ReadEvent.ServicesDiscovered(0, fullDevice))
        m.on(ReadEvent.CharacteristicRead(Uuids.MANUFACTURER_NAME, null, 133))
        assertEquals(listOf(ReadAction.Settle(ReadStateMachine.SETTLE_MS)), m.on(ReadEvent.CharacteristicRead(Uuids.MODEL_NUMBER, null, 133)))
        m.on(ReadEvent.SettleElapsed)
        m.on(ReadEvent.Subscribed(0))
        val finish = assertIs<ReadAction.Finish>(m.on(ReadEvent.ConnectionChanged(false, 0)).single())
        assertNull(finish.outcome.manufacturer)
        assertNull(finish.outcome.failure)
    }

    @Test
    fun missingServiceFails() {
        val m = machine()
        connected(m)
        val finish = assertIs<ReadAction.Finish>(m.on(ReadEvent.ServicesDiscovered(0, mapOf(Uuids.DEVICE_INFORMATION to setOf(Uuids.MODEL_NUMBER)))).single())
        assertEquals(ReadFailure.SERVICE_MISSING, finish.outcome.failure)
    }

    @Test
    fun status133RetriesOnceThenFails() {
        val m = machine()
        m.start()
        assertEquals(listOf(ReadAction.RetryConnect(ReadStateMachine.RETRY_DELAY_MS)), m.on(ReadEvent.ConnectionChanged(false, 133)))
        assertEquals(State.CONNECTING, m.state)
        val finish = assertIs<ReadAction.Finish>(m.on(ReadEvent.ConnectionChanged(false, 133)).single())
        assertEquals(ReadFailure.CONNECT_FAILED, finish.outcome.failure)
        assertEquals(133, finish.outcome.failureStatus)
    }

    @Test
    fun retrySucceeds() {
        val m = machine()
        m.start()
        m.on(ReadEvent.ConnectionChanged(false, 133))
        assertEquals(listOf(ReadAction.DiscoverServices), m.on(ReadEvent.ConnectionChanged(true, 0)))
    }

    @Test
    fun disconnectDuringDiscoveryRetries() {
        val m = machine()
        connected(m)
        assertEquals(State.DISCOVERING, m.state)
        assertEquals(listOf(ReadAction.RetryConnect(ReadStateMachine.RETRY_DELAY_MS)), m.on(ReadEvent.ConnectionChanged(false, 8)))
    }

    @Test
    fun subscriptionFailureIsFinal() {
        val m = machine()
        connected(m)
        m.on(ReadEvent.ServicesDiscovered(0, bareDevice))
        m.on(ReadEvent.SettleElapsed)
        val finish = assertIs<ReadAction.Finish>(m.on(ReadEvent.Subscribed(137)).single())
        assertEquals(ReadFailure.SUBSCRIBE_FAILED, finish.outcome.failure)
        assertEquals(137, finish.outcome.failureStatus)
    }

    @Test
    fun timeoutWhileCollectingKeepsFrames() {
        val m = machine()
        connected(m)
        m.on(ReadEvent.ServicesDiscovered(0, bareDevice))
        m.on(ReadEvent.SettleElapsed)
        m.on(ReadEvent.Subscribed(0))
        m.on(ReadEvent.Measurement(byteArrayOf(9)))
        val actions = m.on(ReadEvent.Timeout)
        assertEquals(ReadAction.Disconnect, actions[0])
        val finish = assertIs<ReadAction.Finish>(actions[1])
        assertNull(finish.outcome.failure)
        assertEquals(1, finish.outcome.frames.size)
    }

    @Test
    fun timeoutBeforeSubscriptionFails() {
        val m = machine()
        connected(m)
        val actions = m.on(ReadEvent.Timeout)
        assertEquals(ReadAction.Disconnect, actions[0])
        assertEquals(ReadFailure.TIMEOUT, assertIs<ReadAction.Finish>(actions[1]).outcome.failure)
    }

    @Test
    fun measurementBeforeSubscribedAckIsKept() {
        val m = machine()
        connected(m)
        m.on(ReadEvent.ServicesDiscovered(0, bareDevice))
        m.on(ReadEvent.SettleElapsed)
        m.on(ReadEvent.Measurement(byteArrayOf(1)))
        m.on(ReadEvent.Subscribed(0))
        val finish = assertIs<ReadAction.Finish>(m.on(ReadEvent.ConnectionChanged(false, 0)).single())
        assertEquals(1, finish.outcome.frames.size)
    }

    @Test
    fun staleSettleIsIgnored() {
        val m = machine()
        connected(m)
        assertTrue(m.on(ReadEvent.SettleElapsed).isEmpty())
        assertEquals(State.DISCOVERING, m.state)
    }
}
