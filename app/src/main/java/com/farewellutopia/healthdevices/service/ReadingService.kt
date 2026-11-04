package com.farewellutopia.healthdevices.service

import android.Manifest
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.farewellutopia.healthdevices.R
import com.farewellutopia.healthdevices.ble.BackgroundScan
import com.farewellutopia.healthdevices.ble.GattSession
import com.farewellutopia.healthdevices.ble.ReadFailure
import com.farewellutopia.healthdevices.ble.ReadOutcome
import com.farewellutopia.healthdevices.data.DeviceStore
import com.farewellutopia.healthdevices.data.PairedDevice
import com.farewellutopia.healthdevices.data.Reception
import com.farewellutopia.healthdevices.data.ReceptionResult
import com.farewellutopia.healthdevices.importer.ImportResult
import com.farewellutopia.healthdevices.importer.Importer
import com.farewellutopia.healthdevices.profiles.Profiles

/**
 * The short-lived foreground service (type connectedDevice) that reads one
 * device after the scan saw it: connect, collect, write to Health Connect,
 * notify, stop. Started by [com.farewellutopia.healthdevices.ble.ScanResultReceiver].
 *
 * One worker drains a queue of addresses, so two matches at once (or the
 * same device reported twice) never run two GATT sessions side by side; a
 * device read within [COOLDOWN_MS] is not read again. The queue and the
 * worker are touched on the main thread only (onStartCommand and the
 * worker's own bookkeeping); the session and the import run on Default/IO
 * under a scope that is cancelled when the service is destroyed, which
 * closes any open connection.
 */
class ReadingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val queue = ArrayDeque<String>()
    private var worker: Job? = null
    private var lastStartId = 0
    private val store by lazy { DeviceStore(this) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        // Promoted before anything else: the system allows a few seconds
        // between startForegroundService and startForeground. The label is
        // refined once the device record is loaded.
        try {
            ServiceCompat.startForeground(
                this,
                Notifications.ID_FOREGROUND,
                Notifications.foreground(this, intent?.getStringExtra(EXTRA_ADDRESS) ?: getString(R.string.app_name)),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException (Android 12+) when the
            // start from the background was not allowed, or a SecurityException
            // when a prerequisite permission of the type is missing.
            Log.w(TAG, "cannot start in the foreground", e)
            Notifications.problem(this, R.string.problem_service_not_allowed)
            stopSelf()
            return START_NOT_STICKY
        }
        val address = intent?.getStringExtra(EXTRA_ADDRESS)?.uppercase()
        if (address != null && address !in queue) queue += address
        if (worker?.isActive != true) {
            worker = scope.launch { drain() }
        }
        return START_NOT_STICKY
    }

    private suspend fun drain() {
        while (true) {
            val address = queue.removeFirstOrNull()
            if (address == null) {
                // A newer start may be on its way; then this returns false and
                // its onStartCommand launches the next worker.
                stopSelfResult(lastStartId)
                return
            }
            try {
                withContext(Dispatchers.Default) { readOne(address) }
            } catch (e: Exception) {
                Log.w(TAG, "reception of $address failed", e)
            }
        }
    }

    private suspend fun readOne(address: String) {
        val device = store.device(address) ?: return
        val last = lastCompleted[address] ?: 0L
        if (System.currentTimeMillis() - last < COOLDOWN_MS) {
            Log.i(TAG, "$address read ${(System.currentTimeMillis() - last) / 1000}s ago; skipping")
            return
        }
        val profile = Profiles.byId(device.profileId) ?: return
        NotificationManagerCompat.from(this).notify(Notifications.ID_FOREGROUND, Notifications.foreground(this, device.label))

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            report(device, ImportResult(ReceptionResult.NO_PERMISSION))
            return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            report(device, ImportResult(ReceptionResult.BLUETOOTH_OFF))
            return
        }
        val remote = adapter.getRemoteDevice(address)
        val outcome = withTimeoutOrNull(GattSession.BACKGROUND_TIMEOUT_MS + GRACE_MS) {
            GattSession(this@ReadingService, remote, profile, GattSession.BACKGROUND_TIMEOUT_MS).run()
        } ?: ReadOutcome(emptyList(), failure = ReadFailure.TIMEOUT)
        val result = withContext(Dispatchers.IO) {
            Importer(this@ReadingService, store).import(device, outcome, Instant.now()) { Notifications.format(this@ReadingService, it) }
        }
        lastCompleted[address] = System.currentTimeMillis()
        Notifications.result(this, device.label, result)
    }

    /** A reception that never reached the device: logged and notified like any other. */
    private suspend fun report(device: PairedDevice, result: ImportResult) {
        store.addReception(Reception(System.currentTimeMillis(), device.address, device.label, result.result))
        Notifications.result(this, device.label, result)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ReadingService"
        const val EXTRA_ADDRESS = "address"

        /** The same device is not read twice within this; the Beurer advertises for about 30 s after a measurement. */
        const val COOLDOWN_MS = 60_000L

        /** Beyond the session's own timeout, so a stuck stack cannot pin the service. */
        private const val GRACE_MS = 10_000L

        /** Process-wide, so the cooldown survives the service being stopped between receptions. */
        private val lastCompleted = java.util.concurrent.ConcurrentHashMap<String, Long>()

        fun start(context: Context, address: String) {
            if (!BackgroundScan.hasPermissions(context)) {
                Notifications.problem(context, R.string.problem_no_permission)
                return
            }
            val intent = Intent(context, ReadingService::class.java).putExtra(EXTRA_ADDRESS, address)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
