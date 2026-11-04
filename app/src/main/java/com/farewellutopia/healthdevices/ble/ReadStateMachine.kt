package com.farewellutopia.healthdevices.ble

import java.util.UUID

/** What the GATT layer tells the state machine. */
sealed interface ReadEvent {
    /** onConnectionStateChange: [connected] is the new state, [status] the GATT status (0 = success, 133 = the generic failure). */
    data class ConnectionChanged(val connected: Boolean, val status: Int) : ReadEvent

    /** onServicesDiscovered: every characteristic found, keyed by its service. */
    data class ServicesDiscovered(val status: Int, val characteristics: Map<UUID, Set<UUID>>) : ReadEvent

    /** onCharacteristicRead for a Device Information characteristic. */
    data class CharacteristicRead(val uuid: UUID, val value: ByteArray?, val status: Int) : ReadEvent

    /** The settle delay before the CCCD write has passed. */
    data object SettleElapsed : ReadEvent

    /** onDescriptorWrite of the CCCD. */
    data class Subscribed(val status: Int) : ReadEvent

    /** onCharacteristicChanged of the measurement characteristic. */
    data class Measurement(val value: ByteArray) : ReadEvent

    /** The overall time budget is used up. */
    data object Timeout : ReadEvent
}

/** What the state machine asks the GATT layer to do. */
sealed interface ReadAction {
    data object Connect : ReadAction

    /** Close the current connection and connect again after [delayMs]. */
    data class RetryConnect(val delayMs: Long) : ReadAction
    data object DiscoverServices : ReadAction
    data class ReadCharacteristic(val uuid: UUID) : ReadAction

    /** Wait [delayMs], then send [ReadEvent.SettleElapsed]. */
    data class Settle(val delayMs: Long) : ReadAction

    /** Enable indications (or notifications) on the measurement characteristic. */
    data object Subscribe : ReadAction
    data object Disconnect : ReadAction

    /** Close the connection; the session is over. */
    data class Finish(val outcome: ReadOutcome) : ReadAction
}

/** Why a session ended without data. */
enum class ReadFailure { CONNECT_FAILED, SERVICE_MISSING, SUBSCRIBE_FAILED, TIMEOUT }

/**
 * The result of one session. [frames] are the measurement frames received,
 * in order; a session that reached the subscription and then ended (the
 * device disconnected, or the time was up) has no [failure], whatever the
 * frame count.
 */
data class ReadOutcome(
    val frames: List<ByteArray>,
    val manufacturer: String? = null,
    val model: String? = null,
    val hasCurrentTimeService: Boolean = false,
    val failure: ReadFailure? = null,
    val failureStatus: Int = 0,
)

/**
 * The read of one device as a pure state machine: connect, discover,
 * read the Device Information Service, settle, subscribe, collect until the
 * device disconnects or the time budget ends. One retry of the connection
 * ([ReadAction.RetryConnect]) after a failure before the subscription, for the
 * status-133 kind of failure the Android stack produces now and then. The
 * GATT layer ([GattSession]) feeds [on] and executes the returned actions;
 * nothing here touches Android, so the whole flow is unit-tested.
 */
class ReadStateMachine(
    private val serviceUuid: UUID,
    private val measurementUuid: UUID,
    /** The pause between service discovery and the CCCD write: the Beurer BM81 drops the connection without it. */
    private val settleMs: Long = SETTLE_MS,
    private val retryDelayMs: Long = RETRY_DELAY_MS,
) {

    enum class State { IDLE, CONNECTING, DISCOVERING, READING_INFO, SETTLING, SUBSCRIBING, COLLECTING, DONE }

    var state: State = State.IDLE
        private set

    private var retried = false
    private val frames = mutableListOf<ByteArray>()
    private var manufacturer: String? = null
    private var model: String? = null
    private var hasCurrentTime = false
    private val pendingReads = ArrayDeque<UUID>()

    fun start(): List<ReadAction> {
        check(state == State.IDLE)
        state = State.CONNECTING
        return listOf(ReadAction.Connect)
    }

    fun on(event: ReadEvent): List<ReadAction> {
        if (state == State.DONE || state == State.IDLE) return emptyList()
        return when (event) {
            is ReadEvent.ConnectionChanged -> onConnection(event)
            is ReadEvent.ServicesDiscovered -> onServices(event)
            is ReadEvent.CharacteristicRead -> onRead(event)
            ReadEvent.SettleElapsed -> if (state == State.SETTLING) {
                state = State.SUBSCRIBING
                listOf(ReadAction.Subscribe)
            } else {
                emptyList()
            }
            is ReadEvent.Subscribed -> onSubscribed(event)
            is ReadEvent.Measurement -> {
                if (state == State.COLLECTING || state == State.SUBSCRIBING) frames += event.value
                emptyList()
            }
            ReadEvent.Timeout -> onTimeout()
        }
    }

    private fun onConnection(event: ReadEvent.ConnectionChanged): List<ReadAction> {
        if (event.connected && event.status == 0) {
            if (state != State.CONNECTING) return emptyList()
            state = State.DISCOVERING
            return listOf(ReadAction.DiscoverServices)
        }
        // Disconnected, or connected with an error status.
        return when (state) {
            State.COLLECTING -> finish(ReadOutcome(frames.toList(), manufacturer, model, hasCurrentTime))
            else -> failOrRetry(ReadFailure.CONNECT_FAILED, event.status)
        }
    }

    private fun onServices(event: ReadEvent.ServicesDiscovered): List<ReadAction> {
        if (state != State.DISCOVERING) return emptyList()
        if (event.status != 0) return failOrRetry(ReadFailure.CONNECT_FAILED, event.status)
        if (event.characteristics[serviceUuid]?.contains(measurementUuid) != true) {
            return finish(ReadOutcome(emptyList(), failure = ReadFailure.SERVICE_MISSING))
        }
        hasCurrentTime = event.characteristics.containsKey(Uuids.CURRENT_TIME)
        val dis = event.characteristics[Uuids.DEVICE_INFORMATION].orEmpty()
        pendingReads.clear()
        if (Uuids.MANUFACTURER_NAME in dis) pendingReads += Uuids.MANUFACTURER_NAME
        if (Uuids.MODEL_NUMBER in dis) pendingReads += Uuids.MODEL_NUMBER
        return nextReadOrSettle()
    }

    private fun onRead(event: ReadEvent.CharacteristicRead): List<ReadAction> {
        if (state != State.READING_INFO) return emptyList()
        if (event.status == 0 && event.value != null) {
            val text = utf8(event.value)
            when (event.uuid) {
                Uuids.MANUFACTURER_NAME -> manufacturer = text
                Uuids.MODEL_NUMBER -> model = text
            }
        }
        // A failed read of optional information is no reason to give up.
        return nextReadOrSettle()
    }

    private fun nextReadOrSettle(): List<ReadAction> {
        val next = pendingReads.removeFirstOrNull()
        return if (next != null) {
            state = State.READING_INFO
            listOf(ReadAction.ReadCharacteristic(next))
        } else {
            state = State.SETTLING
            listOf(ReadAction.Settle(settleMs))
        }
    }

    private fun onSubscribed(event: ReadEvent.Subscribed): List<ReadAction> {
        if (state != State.SUBSCRIBING) return emptyList()
        if (event.status != 0) return finish(ReadOutcome(emptyList(), manufacturer, model, hasCurrentTime, ReadFailure.SUBSCRIBE_FAILED, event.status))
        state = State.COLLECTING
        return emptyList()
    }

    private fun onTimeout(): List<ReadAction> = when (state) {
        // Whatever arrived is the result; the device is asked to let go first.
        State.COLLECTING -> listOf(ReadAction.Disconnect) + finish(ReadOutcome(frames.toList(), manufacturer, model, hasCurrentTime))
        else -> listOf(ReadAction.Disconnect) + finish(ReadOutcome(emptyList(), manufacturer, model, hasCurrentTime, ReadFailure.TIMEOUT))
    }

    private fun failOrRetry(failure: ReadFailure, status: Int): List<ReadAction> {
        if (!retried) {
            retried = true
            state = State.CONNECTING
            pendingReads.clear()
            // The new connection brings the device's whole memory again; an
            // indication that slipped through before the drop would otherwise
            // be counted twice.
            frames.clear()
            return listOf(ReadAction.RetryConnect(retryDelayMs))
        }
        return finish(ReadOutcome(emptyList(), manufacturer, model, hasCurrentTime, failure, status))
    }

    private fun finish(outcome: ReadOutcome): List<ReadAction> {
        state = State.DONE
        return listOf(ReadAction.Finish(outcome))
    }

    private fun utf8(bytes: ByteArray): String? =
        bytes.toString(Charsets.UTF_8).trimEnd('\u0000').trim().takeIf { it.isNotEmpty() }

    companion object {
        const val SETTLE_MS = 100L
        const val RETRY_DELAY_MS = 2_000L
    }
}
