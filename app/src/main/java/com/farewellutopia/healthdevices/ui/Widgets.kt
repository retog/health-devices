package com.farewellutopia.healthdevices.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import com.farewellutopia.healthdevices.R
import com.farewellutopia.healthdevices.data.ReceptionResult

/** A titled card; every section of both screens is one. */
@Composable
fun Section(titleRes: Int, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(titleRes), style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
fun Line(text: String, ok: Boolean? = null) {
    val color = when (ok) {
        null -> MaterialTheme.colorScheme.onSurface
        true -> MaterialTheme.colorScheme.primary
        false -> MaterialTheme.colorScheme.error
    }
    Text(text, color = color, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
}

@Composable
fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
}

/** Epoch milliseconds in the user's short date-time format. */
fun formatTime(epochMillis: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(epochMillis))

fun resultText(result: ReceptionResult): Int = when (result) {
    ReceptionResult.OK -> R.string.result_ok
    ReceptionResult.NO_DATA -> R.string.result_no_data
    ReceptionResult.CONNECT_FAILED -> R.string.result_connect_failed
    ReceptionResult.SERVICE_MISSING -> R.string.result_service_missing
    ReceptionResult.SUBSCRIBE_FAILED -> R.string.result_subscribe_failed
    ReceptionResult.TIMEOUT -> R.string.result_timeout
    ReceptionResult.HEALTH_CONNECT_UNAVAILABLE -> R.string.result_hc_unavailable
    ReceptionResult.HEALTH_CONNECT_DENIED -> R.string.result_hc_denied
    ReceptionResult.HEALTH_CONNECT_ERROR -> R.string.result_hc_error
    ReceptionResult.HEALTH_CONNECT_REJECTED -> R.string.result_hc_rejected
    ReceptionResult.NOT_BONDED -> R.string.result_not_bonded
    ReceptionResult.BLUETOOTH_OFF -> R.string.result_bluetooth_off
    ReceptionResult.NO_PERMISSION -> R.string.result_no_permission
}
