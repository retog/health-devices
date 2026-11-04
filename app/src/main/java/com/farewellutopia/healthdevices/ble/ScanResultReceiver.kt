package com.farewellutopia.healthdevices.ble

import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import kotlinx.coroutines.launch
import com.farewellutopia.healthdevices.HealthDevicesApp
import com.farewellutopia.healthdevices.R
import com.farewellutopia.healthdevices.data.DeviceStore
import com.farewellutopia.healthdevices.data.ScanProblem
import com.farewellutopia.healthdevices.data.ScanState
import com.farewellutopia.healthdevices.service.Notifications
import com.farewellutopia.healthdevices.service.ReadingService

/**
 * Where the system delivers the background scan's matches (and its errors).
 * Not exported: only the app's own PendingIntent reaches it. A match for a
 * paired address starts the reading service; anything else is dropped.
 */
class ScanResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BackgroundScan.ACTION_SCAN_RESULT) return
        if (intent.hasExtra(BluetoothLeScanner.EXTRA_ERROR_CODE)) {
            val code = intent.getIntExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, 0)
            Log.w(TAG, "background scan failed: $code")
            val pending = goAsync()
            HealthDevicesApp.receiverScope.launch {
                try {
                    DeviceStore(context).setScanState(ScanState(false, System.currentTimeMillis(), ScanProblem.FAILED, code))
                    Notifications.problem(context, R.string.problem_scan_failed)
                } finally {
                    pending.finish()
                }
            }
            return
        }
        val results: List<ScanResult> = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableArrayListExtra(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT, ScanResult::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT)
        } ?: return
        val addresses = results.map { it.device.address.uppercase() }.distinct()
        if (addresses.isEmpty()) return
        val pending = goAsync()
        HealthDevicesApp.receiverScope.launch {
            try {
                val paired = DeviceStore(context).devicesNow().map { it.address.uppercase() }.toSet()
                addresses.filter { it in paired }.forEach { ReadingService.start(context, it) }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "ScanResultReceiver"
    }
}
