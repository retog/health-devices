package com.farewellutopia.healthdevices.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import com.farewellutopia.healthdevices.R

/**
 * Why the app writes health data, as Health Connect requires every app that
 * uses it to say (its permission screen links here on Android 13 and lower
 * through the Health Connect action, on 14+ through the manifest alias).
 */
class RationaleActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HealthDevicesTheme {
                AlertDialog(
                    onDismissRequest = ::finish,
                    title = { Text(stringResource(R.string.rationale_title)) },
                    text = { Text(stringResource(R.string.rationale_text)) },
                    confirmButton = {
                        TextButton(onClick = ::finish) { Text(stringResource(R.string.ok)) }
                    },
                )
            }
        }
    }
}
