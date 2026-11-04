package com.farewellutopia.healthdevices.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import java.time.LocalDateTime
import java.time.ZoneOffset
import com.farewellutopia.healthdevices.BuildConfig
import com.farewellutopia.healthdevices.R
import com.farewellutopia.healthdevices.data.PairedDevice
import com.farewellutopia.healthdevices.data.Reception
import com.farewellutopia.healthdevices.data.ScanProblem
import com.farewellutopia.healthdevices.hc.HealthConnectAvailability
import com.farewellutopia.healthdevices.time.DeviceClock

/** The main screen once a device is paired: what works, what the user has to fix, and what came in. */
@Composable
fun StatusScreen(
    viewModel: MainViewModel,
    onRequestPermissions: () -> Unit,
    onRequestHealthConnect: () -> Unit,
    onBatteryOptimisation: () -> Unit,
) {
    val devices by viewModel.devices.collectAsState()
    val receptions by viewModel.receptions.collectAsState()
    val scan by viewModel.scanState.collectAsState()
    val phone by viewModel.phone.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall) }

        item {
            Section(R.string.section_health_connect) {
                when (phone.healthConnect) {
                    HealthConnectAvailability.AVAILABLE ->
                        if (phone.healthConnectGranted) Line(stringResource(R.string.hc_available_granted), ok = true)
                        else Line(stringResource(R.string.hc_available_missing), ok = false)
                    HealthConnectAvailability.NOT_INSTALLED -> Line(stringResource(R.string.hc_not_installed), ok = false)
                    HealthConnectAvailability.UPDATE_REQUIRED -> Line(stringResource(R.string.hc_update_required), ok = false)
                }
                OutlinedButton(onClick = onRequestHealthConnect, enabled = phone.healthConnect == HealthConnectAvailability.AVAILABLE, modifier = Modifier.padding(top = 8.dp)) {
                    Text(stringResource(R.string.hc_open_permissions))
                }
            }
        }

        item {
            Section(R.string.section_bluetooth) {
                Line(stringResource(if (phone.bluetoothPermissions) R.string.bt_permissions_ok else R.string.bt_permissions_missing), ok = phone.bluetoothPermissions)
                Line(stringResource(if (phone.bluetoothOn) R.string.bt_on else R.string.bt_off), ok = phone.bluetoothOn)
                Line(stringResource(if (phone.notificationsPermission) R.string.notifications_ok else R.string.notifications_missing), ok = phone.notificationsPermission)
                if (!phone.bluetoothPermissions || !phone.notificationsPermission) {
                    OutlinedButton(onClick = onRequestPermissions, modifier = Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.grant_permissions)) }
                }
            }
        }

        item {
            Section(R.string.section_background) {
                when {
                    scan.registered && scan.allMatches -> Line(stringResource(R.string.scan_registered_all_matches, formatTime(scan.at)), ok = true)
                    scan.registered -> Line(stringResource(R.string.scan_registered, formatTime(scan.at)), ok = true)
                    scan.at == 0L -> Line(stringResource(R.string.scan_never), ok = false)
                    else -> Line(
                        when (scan.problem) {
                            ScanProblem.NO_PERMISSION -> stringResource(R.string.scan_problem_no_permission)
                            ScanProblem.BLUETOOTH_OFF -> stringResource(R.string.scan_problem_bluetooth_off)
                            ScanProblem.NO_DEVICES -> stringResource(R.string.scan_problem_no_devices)
                            ScanProblem.FAILED, null -> stringResource(R.string.scan_problem_failed, scan.code)
                        },
                        ok = false,
                    )
                }
                Hint(stringResource(R.string.scan_explanation))
                OutlinedButton(onClick = { viewModel.registerScan() }, modifier = Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.scan_reregister)) }
            }
        }

        item {
            Section(R.string.section_battery) {
                Line(stringResource(if (phone.ignoringBatteryOptimizations) R.string.battery_exempt else R.string.battery_not_exempt), ok = phone.ignoringBatteryOptimizations)
                Hint(stringResource(R.string.battery_explanation))
                OutlinedButton(onClick = onBatteryOptimisation, modifier = Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.battery_open)) }
            }
        }

        item {
            Section(R.string.section_devices) {
                devices.forEach { device -> DeviceCard(device, viewModel) }
                Button(onClick = { viewModel.startPairing() }, modifier = Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.add_device)) }
            }
        }

        item {
            Section(R.string.section_receptions) {
                if (receptions.isEmpty()) Hint(stringResource(R.string.receptions_empty))
                receptions.forEach { ReceptionRow(it) }
            }
        }

        item { Hint(stringResource(R.string.version, BuildConfig.VERSION_NAME)) }
    }
}

@Composable
private fun DeviceCard(device: PairedDevice, viewModel: MainViewModel) {
    var confirmForget by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(device.label, style = MaterialTheme.typography.titleSmall)
        Hint(stringResource(R.string.device_address, device.address))
        listOfNotNull(device.manufacturer, device.model).joinToString(" ").takeIf { it.isNotEmpty() }?.let { Hint(stringResource(R.string.device_make, it)) }
        Hint(stringResource(if (device.hasCurrentTimeService) R.string.device_cts_yes else R.string.device_cts_no))
        Hint(
            device.watermark?.let { stringResource(R.string.device_watermark, LocalDateTime.ofEpochSecond(it, 0, ZoneOffset.UTC).toString().replace('T', ' ')) }
                ?: stringResource(R.string.device_watermark_all),
        )
        Text(stringResource(R.string.device_clock_label), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
        ClockChoice(device.clock) { viewModel.setClock(device.address, it) }
        Hint(stringResource(R.string.clock_explanation))
        TextButton(onClick = { confirmForget = true }) { Text(stringResource(R.string.device_forget)) }
    }
    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text(stringResource(R.string.device_forget_title)) },
            text = { Text(stringResource(R.string.device_forget_text)) },
            confirmButton = {
                TextButton(onClick = { confirmForget = false; viewModel.forget(device.address) }) { Text(stringResource(R.string.device_forget)) }
            },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** The per-device clock setting: UTC or the phone's local time. */
@Composable
fun ClockChoice(selected: DeviceClock, onSelect: (DeviceClock) -> Unit) {
    Column {
        DeviceClock.entries.forEach { clock ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = clock == selected, onClick = { onSelect(clock) })
                Text(stringResource(if (clock == DeviceClock.UTC) R.string.clock_utc else R.string.clock_local))
            }
        }
    }
}

@Composable
private fun ReceptionRow(reception: Reception) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text("${formatTime(reception.at)} · ${reception.deviceLabel}", style = MaterialTheme.typography.labelLarge)
        Line(stringResource(resultText(reception.result)), ok = reception.result == com.farewellutopia.healthdevices.data.ReceptionResult.OK)
        if (reception.newReadings > 0) {
            Hint(pluralStringResource(R.plurals.notification_new_readings, reception.newReadings, reception.newReadings))
        }
        reception.summary?.let { Hint(it) }
        reception.detail?.let { Hint(it) }
        reception.frames.forEach { frame ->
            Text(frame, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
}
