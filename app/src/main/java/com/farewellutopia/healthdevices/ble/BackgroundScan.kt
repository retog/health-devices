package com.farewellutopia.healthdevices.ble

import android.Manifest
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.farewellutopia.healthdevices.data.DeviceStore
import com.farewellutopia.healthdevices.data.ScanProblem
import com.farewellutopia.healthdevices.data.ScanState
import com.farewellutopia.healthdevices.profiles.Profiles

/**
 * The scan that wakes the app when a paired device advertises, registered
 * with the system through a PendingIntent so it outlives the process:
 * low-power, hardware-filtered on the paired addresses and the profile
 * services, first-match only (one broadcast per advertising episode) where
 * the controller can, all matches where it cannot. The registration is
 * idempotent — the previous scan under the same PendingIntent is stopped
 * before the new one starts — and is repeated on boot, when Bluetooth turns
 * on, after an update, and at the first activity start of a process, because
 * the system drops it on each of those occasions (and on a force-stop, which
 * nothing can undo but opening the app). Beyond that it is left alone:
 * Android quietly downgrades an app that starts scans more than five times
 * in 30 s ([ScanPlan.needsRegistration], [ScanPlan.manualAllowed]).
 */
object BackgroundScan {

    private const val TAG = "BackgroundScan"
    const val ACTION_SCAN_RESULT = "com.farewellutopia.healthdevices.action.SCAN_RESULT"
    private const val REQUEST_CODE = 1

    /** True once this process registered the scan (a new process after a force-stop has to). */
    @Volatile
    private var registeredInProcess = false

    fun hasPermissions(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun isBluetoothOn(context: Context): Boolean =
        context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true

    /**
     * Explicit and mutable: the system fills in the scan results, and the
     * receiver is the app's own (not exported). FLAG_UPDATE_CURRENT keeps it
     * one PendingIntent across calls, which is what makes stop/start pair up.
     */
    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, ScanResultReceiver::class.java).setAction(ACTION_SCAN_RESULT),
        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Registers only when due ([ScanPlan.needsRegistration]); the activity's start and permission results call this. */
    suspend fun ensure(context: Context, store: DeviceStore): ScanState {
        val addresses = store.devicesNow().map { it.address }
        val state = store.scanStateNow()
        if (addresses.isEmpty()) return state
        if (!ScanPlan.needsRegistration(state, addresses, firstInProcess = !registeredInProcess)) return state
        return register(context, store)
    }

    /**
     * (Re)registers the scan for the paired devices and records the outcome
     * in the store. A [manual] call (the status screen's button) is honoured
     * at most every [ScanPlan.MANUAL_INTERVAL_MS].
     */
    suspend fun register(context: Context, store: DeviceStore, manual: Boolean = false): ScanState {
        val addresses = store.devicesNow().map { it.address }
        if (manual && !ScanPlan.manualAllowed(store.scanStateNow(), System.currentTimeMillis())) return store.scanStateNow()
        val state = registerNow(context, addresses)
        if (state.registered) registeredInProcess = true
        store.setScanState(state)
        return state
    }

    private fun registerNow(context: Context, addresses: List<String>): ScanState {
        val now = System.currentTimeMillis()
        if (!hasPermissions(context)) return ScanState(false, now, ScanProblem.NO_PERMISSION)
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) return ScanState(false, now, ScanProblem.BLUETOOTH_OFF)
        val plan = ScanPlan.build(addresses, Profiles.SERVICE_UUIDS)
        val registered = plan.filterIsInstance<FilterSpec.ByAddress>().map { it.address }
        val pi = pendingIntent(context)
        try {
            val scanner = adapter.bluetoothLeScanner ?: return ScanState(false, now, ScanProblem.BLUETOOTH_OFF)
            // Idempotent: whatever was registered under this PendingIntent goes first.
            scanner.stopScan(pi)
            if (plan.isEmpty()) return ScanState(false, now, ScanProblem.NO_DEVICES)
            val filters = plan.map { spec ->
                when (spec) {
                    is FilterSpec.ByAddress -> ScanFilter.Builder().setDeviceAddress(spec.address).build()
                    is FilterSpec.ByService -> ScanFilter.Builder().setServiceUuid(ParcelUuid(spec.uuid)).build()
                }
            }
            val firstMatchCode = scanner.startScan(filters, settings(firstMatch = true), pi)
            if (firstMatchCode == 0) return ScanState(true, now, addresses = registered)
            if (ScanPlan.fallsBackToAllMatches(firstMatchCode)) {
                // No on-found tracking for this app in this controller: every
                // advertisement is reported instead; the service's cooldown
                // absorbs the repeats.
                val allMatchesCode = scanner.startScan(filters, settings(firstMatch = false), pi)
                if (allMatchesCode == 0) return ScanState(true, now, code = firstMatchCode, allMatches = true, addresses = registered)
                Log.w(TAG, "startScan failed: first-match $firstMatchCode, all-matches $allMatchesCode")
                return ScanState(false, now, ScanProblem.FAILED, allMatchesCode)
            }
            Log.w(TAG, "startScan failed: $firstMatchCode")
            return ScanState(false, now, ScanProblem.FAILED, firstMatchCode)
        } catch (e: SecurityException) {
            Log.w(TAG, "scan refused", e)
            return ScanState(false, now, ScanProblem.NO_PERMISSION)
        } catch (e: IllegalStateException) {
            // The adapter turned off between the check and the call.
            Log.w(TAG, "scan refused", e)
            return ScanState(false, now, ScanProblem.BLUETOOTH_OFF)
        }
    }

    private fun settings(firstMatch: Boolean): ScanSettings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
        .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
        .setCallbackType(if (firstMatch) ScanSettings.CALLBACK_TYPE_FIRST_MATCH else ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
        .build()

    /** Stops the scan while the pairing screen connects (the device accepts one link); [register] resumes it. */
    suspend fun pause(context: Context, store: DeviceStore) {
        stopScan(context)
        store.setScanState(ScanState(false, System.currentTimeMillis(), ScanProblem.PAUSED))
    }

    /** Stops the scan (the last device was forgotten). */
    suspend fun unregister(context: Context, store: DeviceStore) {
        stopScan(context)
        store.setScanState(ScanState(false, System.currentTimeMillis(), ScanProblem.NO_DEVICES))
    }

    private fun stopScan(context: Context) {
        if (!hasPermissions(context)) return
        try {
            context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.stopScan(pendingIntent(context))
        } catch (e: Exception) {
            Log.w(TAG, "stopScan failed", e)
        }
    }
}
