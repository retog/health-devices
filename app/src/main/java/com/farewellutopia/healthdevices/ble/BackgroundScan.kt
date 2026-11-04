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
 * services, first-match only (one broadcast per advertising episode). The
 * registration is idempotent — the previous scan under the same
 * PendingIntent is stopped before the new one starts — and is repeated on
 * boot, when Bluetooth turns on, after an update, and whenever the activity
 * starts, because the system drops it on each of those occasions (and on a
 * force-stop, which nothing can undo but opening the app).
 */
object BackgroundScan {

    private const val TAG = "BackgroundScan"
    const val ACTION_SCAN_RESULT = "com.farewellutopia.healthdevices.action.SCAN_RESULT"
    private const val REQUEST_CODE = 1

    /** The scanner's code for a callback type the controller does not support. */
    private const val SCAN_FAILED_FEATURE_UNSUPPORTED = 4

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

    /** (Re)registers the scan for the paired devices and records the outcome in the store. */
    suspend fun register(context: Context, store: DeviceStore): ScanState {
        val state = registerNow(context, store.devicesNow().map { it.address })
        store.setScanState(state)
        return state
    }

    private fun registerNow(context: Context, addresses: List<String>): ScanState {
        val now = System.currentTimeMillis()
        if (!hasPermissions(context)) return ScanState(false, now, ScanProblem.NO_PERMISSION)
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) return ScanState(false, now, ScanProblem.BLUETOOTH_OFF)
        val plan = ScanPlan.build(addresses, Profiles.SERVICE_UUIDS)
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
            var code = scanner.startScan(filters, settings(firstMatch = true), pi)
            if (code == 0) return ScanState(true, now)
            if (code == SCAN_FAILED_FEATURE_UNSUPPORTED) {
                // No on-found tracking in this controller: every advertisement
                // is reported instead; the service's cooldown absorbs the repeats.
                code = scanner.startScan(filters, settings(firstMatch = false), pi)
                if (code == 0) return ScanState(true, now, allMatches = true)
            }
            Log.w(TAG, "startScan failed: $code")
            return ScanState(false, now, ScanProblem.FAILED, code)
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

    /** Stops the scan (the last device was forgotten). */
    suspend fun unregister(context: Context, store: DeviceStore) {
        if (hasPermissions(context)) {
            try {
                context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.stopScan(pendingIntent(context))
            } catch (e: Exception) {
                Log.w(TAG, "stopScan failed", e)
            }
        }
        store.setScanState(ScanState(false, System.currentTimeMillis(), ScanProblem.NO_DEVICES))
    }
}
