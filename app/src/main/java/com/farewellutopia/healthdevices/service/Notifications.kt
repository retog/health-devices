package com.farewellutopia.healthdevices.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.farewellutopia.healthdevices.R
import com.farewellutopia.healthdevices.data.ReceptionResult
import com.farewellutopia.healthdevices.importer.ImportResult
import com.farewellutopia.healthdevices.profiles.ReadingNote
import com.farewellutopia.healthdevices.profiles.Summary
import com.farewellutopia.healthdevices.ui.MainActivity

/**
 * The two channels: readings (one notification per reception, replaced by the
 * next) and problems (what the user has to fix). Posting is a no-op when the
 * user has not allowed notifications (Android 13+ asks at runtime); the
 * foreground service's own notification is still required by the system and
 * is built here too.
 */
object Notifications {

    const val CHANNEL_READINGS = "readings"
    const val CHANNEL_PROBLEMS = "problems"

    const val ID_FOREGROUND = 1
    const val ID_RESULT = 2
    const val ID_PROBLEM = 3

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_READINGS, context.getString(R.string.channel_readings), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.channel_readings_description)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_PROBLEMS, context.getString(R.string.channel_problems), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.channel_problems_description)
            },
        )
    }

    /** The ongoing notification while the service talks to a device. */
    fun foreground(context: Context, deviceLabel: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_READINGS)
            .setSmallIcon(R.drawable.ic_stat_device)
            .setContentTitle(context.getString(R.string.notification_reading, deviceLabel))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openApp(context))
            .build()

    fun format(context: Context, summary: Summary): String = context.getString(summary.textRes, *summary.args.toTypedArray())

    /** What a reception came to; the "reading" channel for data, the "problems" channel for anything the user must fix. */
    fun result(context: Context, deviceLabel: String, result: ImportResult) {
        val (channel, title, text) = when (result.result) {
            ReceptionResult.OK -> {
                val count = context.resources.getQuantityString(R.plurals.notification_new_readings, result.newReadings, result.newReadings)
                val lines = mutableListOf(format(context, result.latest!!), count)
                result.notes.forEach { lines += context.getString(noteText(it)) }
                if (result.anyReceiveTime) lines += context.getString(R.string.notification_receive_time)
                if (result.skipped > 0) {
                    lines += context.resources.getQuantityString(R.plurals.notification_skipped, result.skipped, result.skipped, result.detail ?: "")
                }
                if (result.failed > 0) lines += context.resources.getQuantityString(R.plurals.notification_failed, result.failed, result.failed)
                Triple(CHANNEL_READINGS, deviceLabel, lines.joinToString(" — "))
            }
            ReceptionResult.HEALTH_CONNECT_REJECTED ->
                Triple(CHANNEL_PROBLEMS, deviceLabel, context.getString(R.string.notification_hc_rejected, result.detail ?: ""))
            ReceptionResult.NOT_BONDED -> Triple(CHANNEL_PROBLEMS, deviceLabel, context.getString(R.string.notification_not_bonded))
            ReceptionResult.NO_DATA -> Triple(CHANNEL_READINGS, deviceLabel, context.getString(R.string.notification_nothing_new))
            ReceptionResult.CONNECT_FAILED, ReceptionResult.TIMEOUT ->
                Triple(CHANNEL_PROBLEMS, deviceLabel, context.getString(R.string.notification_connect_failed))
            ReceptionResult.SERVICE_MISSING -> Triple(CHANNEL_PROBLEMS, deviceLabel, context.getString(R.string.notification_service_missing))
            ReceptionResult.SUBSCRIBE_FAILED -> Triple(CHANNEL_PROBLEMS, deviceLabel, context.getString(R.string.notification_subscribe_failed))
            ReceptionResult.HEALTH_CONNECT_UNAVAILABLE -> Triple(CHANNEL_PROBLEMS, deviceLabel, context.getString(R.string.notification_hc_unavailable))
            ReceptionResult.HEALTH_CONNECT_DENIED -> Triple(CHANNEL_PROBLEMS, deviceLabel, context.getString(R.string.notification_hc_denied))
            ReceptionResult.HEALTH_CONNECT_ERROR -> Triple(CHANNEL_PROBLEMS, deviceLabel, context.getString(R.string.notification_hc_error, result.detail ?: ""))
            ReceptionResult.BLUETOOTH_OFF -> Triple(CHANNEL_PROBLEMS, deviceLabel, context.getString(R.string.notification_bluetooth_off))
            ReceptionResult.NO_PERMISSION -> Triple(CHANNEL_PROBLEMS, deviceLabel, context.getString(R.string.notification_no_permission))
        }
        post(context, if (channel == CHANNEL_PROBLEMS) ID_PROBLEM else ID_RESULT, channel, title, text)
    }

    /** A problem outside a reception (the scan could not be registered, the service could not start). */
    fun problem(context: Context, textRes: Int) {
        post(context, ID_PROBLEM, CHANNEL_PROBLEMS, context.getString(R.string.app_name), context.getString(textRes))
    }

    private fun post(context: Context, id: Int, channel: String, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_device)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun noteText(note: ReadingNote): Int = when (note) {
        ReadingNote.IRREGULAR_PULSE -> R.string.note_irregular_pulse
        ReadingNote.BODY_MOVEMENT -> R.string.note_body_movement
        ReadingNote.CUFF_LOOSE -> R.string.note_cuff_loose
        ReadingNote.IMPROPER_POSITION -> R.string.note_improper_position
        ReadingNote.PULSE_OUT_OF_RANGE -> R.string.note_pulse_out_of_range
    }
}
