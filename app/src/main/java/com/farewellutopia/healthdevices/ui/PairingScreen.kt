package com.farewellutopia.healthdevices.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.farewellutopia.healthdevices.R
import com.farewellutopia.healthdevices.ble.ReadFailure
import com.farewellutopia.healthdevices.hc.HealthConnectAvailability
import com.farewellutopia.healthdevices.time.DeviceClock

/**
 * The only time the user opens the app on purpose: grant what is needed,
 * take a measurement so the monitor advertises, pick it from the list,
 * let Android pair, then say how the device's clock is set and whether
 * the readings it already holds should be imported.
 */
@Composable
fun PairingScreen(
    viewModel: MainViewModel,
    canCancel: Boolean,
    onRequestPermissions: () -> Unit,
    onRequestHealthConnect: () -> Unit,
) {
    val pairing by viewModel.pairing.collectAsState()
    val phone by viewModel.phone.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.pairing_title), style = MaterialTheme.typography.headlineSmall)

        when (val p = pairing) {
            Pairing.Idle, is Pairing.Scanning -> {
                val ready = phone.bluetoothPermissions && phone.healthConnect == HealthConnectAvailability.AVAILABLE && phone.healthConnectGranted
                if (!phone.bluetoothPermissions) {
                    Line(stringResource(R.string.pairing_prereq_bt), ok = false)
                    OutlinedButton(onClick = onRequestPermissions) { Text(stringResource(R.string.grant_permissions)) }
                }
                when (phone.healthConnect) {
                    HealthConnectAvailability.NOT_INSTALLED -> Line(stringResource(R.string.hc_not_installed), ok = false)
                    HealthConnectAvailability.UPDATE_REQUIRED -> Line(stringResource(R.string.hc_update_required), ok = false)
                    HealthConnectAvailability.AVAILABLE -> if (!phone.healthConnectGranted) {
                        Line(stringResource(R.string.pairing_prereq_hc), ok = false)
                        OutlinedButton(onClick = onRequestHealthConnect) { Text(stringResource(R.string.hc_open_permissions)) }
                    }
                }
                Text(stringResource(R.string.pairing_intro))
                if (p is Pairing.Scanning) {
                    when (p.error) {
                        null -> Unit
                        MainViewModel.ERROR_NO_PERMISSION -> Line(stringResource(R.string.pairing_error_no_permission), ok = false)
                        MainViewModel.ERROR_BLUETOOTH_OFF -> Line(stringResource(R.string.pairing_error_bluetooth_off), ok = false)
                        else -> Line(stringResource(R.string.pairing_error_scan, p.error), ok = false)
                    }
                    if (p.error == null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.padding(end = 12.dp))
                            Text(stringResource(R.string.pairing_scanning))
                        }
                        if (p.found.isEmpty()) Hint(stringResource(R.string.pairing_no_devices_yet))
                    }
                    p.found.forEach { found ->
                        Card(Modifier.fillMaxWidth().clickable { viewModel.connect(found) }) {
                            Column(Modifier.padding(12.dp)) {
                                Text(found.name ?: stringResource(R.string.pairing_unknown_device), style = MaterialTheme.typography.titleSmall)
                                Hint(found.address)
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (p is Pairing.Scanning && p.error == null) {
                        OutlinedButton(onClick = { viewModel.cancelPairing() }) { Text(stringResource(R.string.pairing_stop)) }
                    } else {
                        Button(onClick = { viewModel.startPairing() }, enabled = ready) { Text(stringResource(R.string.pairing_scan)) }
                    }
                    if (canCancel) OutlinedButton(onClick = { viewModel.cancelPairing() }) { Text(stringResource(R.string.back)) }
                }
            }

            is Pairing.Connecting -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.padding(end = 12.dp))
                    Text(stringResource(R.string.pairing_connecting, p.device.name ?: p.device.address))
                }
                Hint(stringResource(R.string.pairing_connecting_hint))
                OutlinedButton(onClick = { viewModel.cancelPairing() }) { Text(stringResource(R.string.cancel)) }
            }

            is Pairing.Connected -> {
                var clock by rememberSaveable { mutableStateOf(DeviceClock.LOCAL) }
                var importExisting by rememberSaveable { mutableStateOf(true) }
                Line(stringResource(R.string.pairing_connected, p.device.name ?: p.device.address), ok = true)
                listOfNotNull(p.outcome.manufacturer, p.outcome.model).joinToString(" ").takeIf { it.isNotEmpty() }?.let { Hint(stringResource(R.string.device_make, it)) }
                Hint(pluralStringResource(R.plurals.pairing_readings_received, p.outcome.frames.size, p.outcome.frames.size))
                Hint(stringResource(if (p.outcome.hasCurrentTimeService) R.string.device_cts_yes else R.string.device_cts_no))

                Text(stringResource(R.string.device_clock_label), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                ClockChoice(clock) { clock = it }
                Hint(stringResource(R.string.clock_explanation))

                Text(stringResource(R.string.pairing_import_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = importExisting, onClick = { importExisting = true })
                    Text(stringResource(R.string.pairing_import_existing))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = !importExisting, onClick = { importExisting = false })
                    Text(stringResource(R.string.pairing_import_new_only))
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = { viewModel.finishPairing(clock, importExisting) }) { Text(stringResource(R.string.pairing_finish)) }
            }

            is Pairing.Failed -> {
                Line(stringResource(R.string.pairing_failed, p.device.name ?: p.device.address), ok = false)
                Hint(
                    when (p.outcome.failure) {
                        ReadFailure.SERVICE_MISSING -> stringResource(R.string.pairing_failed_service)
                        ReadFailure.SUBSCRIBE_FAILED -> stringResource(R.string.pairing_failed_subscribe, p.outcome.failureStatus)
                        ReadFailure.TIMEOUT -> stringResource(R.string.pairing_failed_timeout)
                        ReadFailure.CONNECT_FAILED, null -> stringResource(R.string.pairing_failed_connect, p.outcome.failureStatus)
                    },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { viewModel.connect(p.device) }) { Text(stringResource(R.string.retry)) }
                    OutlinedButton(onClick = { viewModel.cancelPairing() }) { Text(stringResource(R.string.back)) }
                }
            }

            is Pairing.Finished -> {
                Line(stringResource(R.string.pairing_finished, p.device.label), ok = true)
                val imported = p.imported
                if (imported == null) {
                    Hint(stringResource(R.string.pairing_not_imported))
                } else {
                    Line(stringResource(resultText(imported.result)), ok = imported.result == com.farewellutopia.healthdevices.data.ReceptionResult.OK)
                    if (imported.newReadings > 0) Hint(pluralStringResource(R.plurals.notification_new_readings, imported.newReadings, imported.newReadings))
                }
                Button(onClick = { viewModel.cancelPairing() }) { Text(stringResource(R.string.done)) }
            }
        }
    }
}
