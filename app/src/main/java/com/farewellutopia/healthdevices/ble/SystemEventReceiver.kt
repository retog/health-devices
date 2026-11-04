package com.farewellutopia.healthdevices.ble

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch
import com.farewellutopia.healthdevices.HealthDevicesApp
import com.farewellutopia.healthdevices.R
import com.farewellutopia.healthdevices.data.DeviceStore
import com.farewellutopia.healthdevices.data.ScanProblem
import com.farewellutopia.healthdevices.service.Notifications

/**
 * The system events after which the background scan has to be registered
 * again: boot, Bluetooth turning on, and an update of the app. Exported,
 * with the system's intent filters, because the system sends these.
 */
class SystemEventReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> Unit
            BluetoothAdapter.ACTION_STATE_CHANGED -> {
                if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) != BluetoothAdapter.STATE_ON) return
            }
            else -> return
        }
        val pending = goAsync()
        HealthDevicesApp.receiverScope.launch {
            try {
                val store = DeviceStore(context)
                if (store.devicesNow().isEmpty()) return@launch
                val state = BackgroundScan.register(context, store)
                // Bluetooth off after boot is normal (the next event handles it);
                // anything else the user has to look at.
                if (!state.registered && state.problem != ScanProblem.BLUETOOTH_OFF) {
                    Notifications.problem(context, R.string.problem_scan_not_registered)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
