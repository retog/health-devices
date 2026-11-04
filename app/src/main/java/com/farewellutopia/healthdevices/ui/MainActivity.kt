package com.farewellutopia.healthdevices.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.health.connect.client.PermissionController
import com.farewellutopia.healthdevices.hc.HealthConnectAccess

/**
 * The one window: the status screen once a device is paired, the pairing
 * flow before (and from "Add device"). Every start refreshes the permission
 * state and re-registers the background scan — the cheapest way to recover
 * from whatever the system dropped since last time.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val requestPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.refresh()
        viewModel.ensureScan()
    }

    private val requestHealthConnect = registerForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        viewModel.refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HealthDevicesTheme {
                Surface {
                    val pairing by viewModel.pairing.collectAsState()
                    val devices by viewModel.devices.collectAsState()
                    if (pairing != Pairing.Idle || devices.isEmpty()) {
                        PairingScreen(
                            viewModel = viewModel,
                            canCancel = devices.isNotEmpty(),
                            onRequestPermissions = ::requestRuntimePermissions,
                            onRequestHealthConnect = ::requestHealthConnectPermissions,
                        )
                    } else {
                        StatusScreen(
                            viewModel = viewModel,
                            onRequestPermissions = ::requestRuntimePermissions,
                            onRequestHealthConnect = ::requestHealthConnectPermissions,
                            onBatteryOptimisation = ::openBatteryOptimisation,
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.refresh()
        // Registers only when due (first time in this process, nothing
        // registered, or the device set changed): Android downgrades an app
        // that starts scans more than five times in 30 s to opportunistic
        // scanning without saying so.
        viewModel.ensureScan()
    }

    override fun onStop() {
        viewModel.stopScan()
        super.onStop()
    }

    private fun requestRuntimePermissions() {
        val wanted = mutableListOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
        requestPermissions.launch(wanted.toTypedArray())
    }

    private fun requestHealthConnectPermissions() {
        requestHealthConnect.launch(HealthConnectAccess.WRITE_PERMISSIONS)
    }

    /** The system's list of apps exempt from battery optimisation; the user finds the app there and allows it. */
    private fun openBatteryOptimisation() {
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (e: android.content.ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }
}
