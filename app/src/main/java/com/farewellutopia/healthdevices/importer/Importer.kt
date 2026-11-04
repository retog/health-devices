package com.farewellutopia.healthdevices.importer

import android.content.Context
import android.util.Log
import androidx.health.connect.client.records.Record
import java.time.Instant
import java.time.ZoneId
import com.farewellutopia.healthdevices.ble.ReadFailure
import com.farewellutopia.healthdevices.ble.ReadOutcome
import com.farewellutopia.healthdevices.ble.toHex
import com.farewellutopia.healthdevices.data.DeviceStore
import com.farewellutopia.healthdevices.data.PairedDevice
import com.farewellutopia.healthdevices.data.Reception
import com.farewellutopia.healthdevices.data.ReceptionResult
import com.farewellutopia.healthdevices.hc.HealthConnectAccess
import com.farewellutopia.healthdevices.hc.HealthConnectAvailability
import com.farewellutopia.healthdevices.profiles.Profiles
import com.farewellutopia.healthdevices.profiles.ReadingNote
import com.farewellutopia.healthdevices.profiles.Summary

/** What the notification says after a reception. */
data class ImportResult(
    val result: ReceptionResult,
    val newReadings: Int = 0,
    /** The newest reading written, if any. */
    val latest: Summary? = null,
    val notes: Set<ReadingNote> = emptySet(),
    val anyReceiveTime: Boolean = false,
    val detail: String? = null,
)

/**
 * Takes a session's outcome to Health Connect: decode, plan ([ImportLogic]),
 * insert, then advance the device's watermark and append to the reception
 * log. The watermark moves only after the insert succeeded, so a failed
 * write is retried by the next reception.
 */
class Importer(private val context: Context, private val store: DeviceStore) {

    suspend fun import(device: PairedDevice, outcome: ReadOutcome, receivedAt: Instant = Instant.now(), format: (Summary) -> String): ImportResult {
        val frames = outcome.frames.map { it.toHex() }
        val failure = outcome.failure
        if (failure != null) {
            val result = when (failure) {
                ReadFailure.CONNECT_FAILED -> ReceptionResult.CONNECT_FAILED
                ReadFailure.SERVICE_MISSING -> ReceptionResult.SERVICE_MISSING
                ReadFailure.SUBSCRIBE_FAILED -> ReceptionResult.SUBSCRIBE_FAILED
                ReadFailure.TIMEOUT -> ReceptionResult.TIMEOUT
            }
            return finish(device, receivedAt, ImportResult(result, detail = "status ${outcome.failureStatus}"), frames)
        }
        val profile = Profiles.byId(device.profileId)
            ?: return finish(device, receivedAt, ImportResult(ReceptionResult.SERVICE_MISSING, detail = device.profileId), frames)
        // The Device Information read at pairing wins over an absent one now.
        val manufacturer = outcome.manufacturer ?: device.manufacturer
        val model = outcome.model ?: device.model

        val plan = ImportLogic.plan(outcome.frames, profile, device.clock, ZoneId.systemDefault(), receivedAt, device.watermark, device.seenFrames)
        if (plan.toWrite.isEmpty()) {
            // Nothing new: an old reading re-sent, or nothing at all. The watermark cannot have moved.
            return finish(device, receivedAt, ImportResult(ReceptionResult.NO_DATA, detail = detail(plan)), frames)
        }

        if (HealthConnectAccess.availability(context) != HealthConnectAvailability.AVAILABLE) {
            return finish(device, receivedAt, ImportResult(ReceptionResult.HEALTH_CONNECT_UNAVAILABLE), frames)
        }
        if (!HealthConnectAccess.allGranted(context)) {
            return finish(device, receivedAt, ImportResult(ReceptionResult.HEALTH_CONNECT_DENIED), frames)
        }
        val metadata = HealthConnectAccess.metadata(manufacturer, model)
        val records = mutableListOf<Record>()
        for (planned in plan.toWrite) {
            try {
                records += profile.records(planned.reading, planned.time, device.address, metadata)
            } catch (e: IllegalArgumentException) {
                // Health Connect refused the values (out of its ranges); the frame stays in the log.
                Log.w(TAG, "reading skipped: ${e.message}")
            }
        }
        try {
            HealthConnectAccess.insert(context, records)
        } catch (e: Exception) {
            Log.w(TAG, "Health Connect insert failed", e)
            return finish(device, receivedAt, ImportResult(ReceptionResult.HEALTH_CONNECT_ERROR, detail = e.message), frames)
        }

        store.updateDevice(device.address) { d ->
            d.copy(
                watermark = plan.watermark ?: d.watermark,
                seenFrames = ImportLogic.seenAfter(d.seenFrames, plan),
                manufacturer = manufacturer,
                model = model,
                hasCurrentTimeService = outcome.hasCurrentTimeService || d.hasCurrentTimeService,
            )
        }
        val newest = plan.toWrite.maxBy { it.time.instant }
        val result = ImportResult(
            result = ReceptionResult.OK,
            newReadings = plan.toWrite.size,
            latest = profile.summary(newest.reading),
            notes = plan.toWrite.flatMap { it.reading.notes }.toSet(),
            anyReceiveTime = plan.anyReceiveTime,
            detail = detail(plan),
        )
        return finish(device, receivedAt, result, frames, format(result.latest!!))
    }

    private fun detail(plan: ImportPlan): String? = buildList {
        if (plan.skippedOld > 0) add("${plan.skippedOld} already imported")
        if (plan.skippedSeen > 0) add("${plan.skippedSeen} seen before")
        if (plan.undecodable > 0) add("${plan.undecodable} undecodable")
    }.joinToString(", ").ifEmpty { null }

    private suspend fun finish(device: PairedDevice, at: Instant, result: ImportResult, frames: List<String>, summary: String? = null): ImportResult {
        store.addReception(
            Reception(
                at = at.toEpochMilli(),
                address = device.address,
                deviceLabel = device.label,
                result = result.result,
                newReadings = result.newReadings,
                summary = summary,
                frames = frames,
                detail = result.detail,
            ),
        )
        return result
    }

    companion object {
        private const val TAG = "Importer"
    }
}
