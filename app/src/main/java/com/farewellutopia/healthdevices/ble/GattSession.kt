package com.farewellutopia.healthdevices.ble

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.annotation.RequiresPermission
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.farewellutopia.healthdevices.profiles.DeviceProfile

/**
 * One GATT connection to one device, driven by [ReadStateMachine]: the
 * callbacks arrive on a handler thread of their own (so nothing of the
 * Bluetooth stack runs on the main thread, and nothing here blocks the
 * stack's binder threads), are turned into events, and are consumed by the
 * coroutine that called [run], which executes the actions. The connection is
 * closed whatever way [run] ends, including cancellation.
 *
 * Used by the foreground service for every background reception and by the
 * pairing screen for the first connection (where the CCCD write makes
 * Android show the pairing dialog; the stack retries the write once the
 * bond is in place, so the session just waits).
 */
class GattSession(
    private val context: Context,
    private val device: BluetoothDevice,
    private val profile: DeviceProfile,
    /** The whole session's time budget, from connect to close. */
    private val timeoutMs: Long,
) {

    private val events = Channel<ReadEvent>(Channel.UNLIMITED)
    private val thread = HandlerThread("gatt-${device.address}")

    @Volatile
    private var gatt: BluetoothGatt? = null

    /** Incremented before every connect; callbacks of an earlier connection are dropped. */
    @Volatile
    private var generation = 0

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun run(): ReadOutcome = coroutineScope {
        thread.start()
        val handler = Handler(thread.looper)
        val machine = ReadStateMachine(profile.serviceUuid, profile.measurementUuid)
        val timer = launch {
            delay(timeoutMs)
            events.send(ReadEvent.Timeout)
        }
        try {
            var outcome = execute(machine.start(), handler, this)
            while (outcome == null) {
                outcome = execute(machine.on(events.receive()), handler, this)
            }
            outcome
        } finally {
            timer.cancel()
            closeGatt()
            thread.quitSafely()
        }
    }

    /** Runs the actions in order; returns the outcome once [ReadAction.Finish] is among them. */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private suspend fun execute(actions: List<ReadAction>, handler: Handler, scope: CoroutineScope): ReadOutcome? {
        var finished: ReadOutcome? = null
        for (action in actions) {
            when (action) {
                ReadAction.Connect -> connect(handler)
                is ReadAction.RetryConnect -> {
                    closeGatt()
                    delay(action.delayMs)
                    connect(handler)
                }
                ReadAction.DiscoverServices -> {
                    if (gatt?.discoverServices() != true) events.send(ReadEvent.ServicesDiscovered(BluetoothGatt.GATT_FAILURE, emptyMap()))
                }
                is ReadAction.ReadCharacteristic -> {
                    val characteristic = characteristic(Uuids.DEVICE_INFORMATION, action.uuid)
                    if (characteristic == null || gatt?.readCharacteristic(characteristic) != true) {
                        events.send(ReadEvent.CharacteristicRead(action.uuid, null, BluetoothGatt.GATT_FAILURE))
                    }
                }
                is ReadAction.Settle -> scope.launch {
                    delay(action.delayMs)
                    events.send(ReadEvent.SettleElapsed)
                }
                ReadAction.Subscribe -> if (!subscribe()) events.send(ReadEvent.Subscribed(BluetoothGatt.GATT_FAILURE))
                ReadAction.Disconnect -> gatt?.disconnect()
                is ReadAction.Finish -> finished = action.outcome
            }
        }
        return finished
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun connect(handler: Handler) {
        val callback = Callback(++generation)
        // autoConnect=false: a direct connection attempt, which fails fast
        // when the device is not there (the state machine retries once).
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, handler)
        if (gatt == null) events.trySend(ReadEvent.ConnectionChanged(connected = false, status = BluetoothGatt.GATT_FAILURE))
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun subscribe(): Boolean {
        val g = gatt ?: return false
        val characteristic = characteristic(profile.serviceUuid, profile.measurementUuid) ?: return false
        if (!g.setCharacteristicNotification(characteristic, true)) return false
        val cccd = characteristic.getDescriptor(Uuids.CCCD) ?: return false
        val value = if (profile.indicated) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            cccd.value = value
            @Suppress("DEPRECATION")
            g.writeDescriptor(cccd)
        }
    }

    private fun characteristic(service: UUID, uuid: UUID): BluetoothGattCharacteristic? =
        gatt?.getService(service)?.getCharacteristic(uuid)

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun closeGatt() {
        val g = gatt ?: return
        gatt = null
        generation++
        try {
            g.disconnect()
            g.close()
        } catch (e: Exception) {
            Log.w(TAG, "close failed", e)
        }
    }

    /**
     * On Android 13+ the framework delivers the value with the callback and
     * (for compatibility) may call the old form too; the old form is ignored
     * there so nothing is counted twice.
     */
    private inner class Callback(private val gen: Int) : BluetoothGattCallback() {

        private fun live(): Boolean = gen == generation

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (!live()) return
            events.trySend(ReadEvent.ConnectionChanged(newState == BluetoothProfile.STATE_CONNECTED, status))
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (!live()) return
            val characteristics = g.services.associate { s -> s.uuid to s.characteristics.map { it.uuid }.toSet() }
            events.trySend(ReadEvent.ServicesDiscovered(status, characteristics))
        }

        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            if (!live()) return
            events.trySend(ReadEvent.CharacteristicRead(characteristic.uuid, value.copyOf(), status))
        }

        @Deprecated("Replaced on Android 13 by the form with the value")
        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT >= 33 || !live()) return
            @Suppress("DEPRECATION")
            events.trySend(ReadEvent.CharacteristicRead(characteristic.uuid, characteristic.value?.copyOf(), status))
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (!live() || descriptor.uuid != Uuids.CCCD) return
            events.trySend(ReadEvent.Subscribed(status))
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (!live() || characteristic.uuid != profile.measurementUuid) return
            events.trySend(ReadEvent.Measurement(value.copyOf()))
        }

        @Deprecated("Replaced on Android 13 by the form with the value")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= 33 || !live() || characteristic.uuid != profile.measurementUuid) return
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: return
            events.trySend(ReadEvent.Measurement(value.copyOf()))
        }
    }

    companion object {
        private const val TAG = "GattSession"

        /** A background reception: the device sends its memory within seconds and hangs up. */
        const val BACKGROUND_TIMEOUT_MS = 45_000L

        /** Pairing: the user may have to type a passkey. */
        const val PAIRING_TIMEOUT_MS = 120_000L
    }
}
