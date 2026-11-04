package com.farewellutopia.healthdevices.importer

import android.content.Context
import android.util.Log
import androidx.health.connect.client.records.Record
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
import java.time.Instant
import java.time.ZoneId

/** What the notification says after a reception. */
data class ImportResult(
    val result: ReceptionResult,
    /** Readings that produced at least one record and were written. */
    val newReadings: Int = 0,
    /** Readings Health Connect would not take as records (values outside its ranges); logged, not retried. */
    val skipped: Int = 0,
    /** Readings whose write failed for another reason; retried at the next reception. */
    val failed: Int = 0,
    /** The newest reading written, if any. */
    val latest: Summary? = null,
    val notes: Set<ReadingNote> = emptySet(),
    val anyReceiveTime: Boolean = false,
    val detail: String? = null,
)

/**
 * Takes a session's outcome to Health Connect: decode, plan ([ImportLogic]),
 * build the records, insert, then move the device's watermark past what
 * was written or recorded as skipped, and append to the reception log. A
 * reading whose write failed stays below the watermark, so the next
 * reception (the device re-sends its memory) tries it again; one such
 * reading does not hold back the others, since a failed batch insert is
 * repeated reading by reading.
 */
class Importer(private val context: Context, private val store: DeviceStore) {

    private class Entry(val planned: PlannedReading, val records: List<Record>, val rejected: List<String>)

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

        val plan = ImportLogic.plan(outcome.frames, profile, device.clock, ZoneId.systemDefault(), receivedAt, device.watermark)
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
        val entries = plan.toWrite.map { planned ->
            val built = profile.records(planned.reading, planned.time, device.address, metadata)
            Entry(planned, built.records, built.rejected)
        }

        val written = mutableListOf<Entry>()
        val skipped = mutableListOf<Entry>()
        val failed = mutableListOf<Pair<Entry, String>>()
        val (withRecords, withoutRecords) = entries.partition { it.records.isNotEmpty() }
        // A reading none of whose values became a record is skipped for good.
        skipped += withoutRecords
        if (withRecords.isNotEmpty()) {
            try {
                HealthConnectAccess.insert(context, withRecords.flatMap { it.records })
                written += withRecords
            } catch (e: Exception) {
                Log.w(TAG, "batch insert failed; retrying one reading at a time", e)
                for (entry in withRecords) {
                    try {
                        HealthConnectAccess.insert(context, entry.records)
                        written += entry
                    } catch (e: IllegalArgumentException) {
                        // Health Connect refused this reading's content: not worth retrying.
                        skipped += Entry(entry.planned, emptyList(), entry.rejected + (e.message ?: "rejected"))
                    } catch (e: Exception) {
                        failed += entry to (e.message ?: e.javaClass.simpleName)
                    }
                }
            }
        }

        val done = (written + skipped).map { it.planned }
        store.updateDevice(device.address) { d ->
            d.copy(
                watermark = ImportLogic.watermarkAfter(d.watermark, done),
                manufacturer = manufacturer,
                model = model,
                hasCurrentTimeService = outcome.hasCurrentTimeService || d.hasCurrentTimeService,
            )
        }
        val rejectedDetail = (skipped + written).flatMap { it.rejected }.distinct().take(2).joinToString("; ").ifEmpty { null }
        val result = when {
            written.isEmpty() && failed.isNotEmpty() -> ImportResult(
                ReceptionResult.HEALTH_CONNECT_ERROR,
                skipped = skipped.size,
                failed = failed.size,
                detail = failed.first().second,
            )
            written.isEmpty() -> ImportResult(ReceptionResult.HEALTH_CONNECT_REJECTED, skipped = skipped.size, detail = rejectedDetail)
            else -> {
                val newest = written.maxBy { it.planned.time.instant }.planned
                ImportResult(
                    result = ReceptionResult.OK,
                    newReadings = written.size,
                    skipped = skipped.size,
                    failed = failed.size,
                    latest = profile.summary(newest.reading),
                    notes = written.flatMap { it.planned.reading.notes }.toSet(),
                    anyReceiveTime = written.any { !it.planned.time.fromDevice },
                    detail = listOfNotNull(rejectedDetail, failed.firstOrNull()?.second, detail(plan)).joinToString("; ").ifEmpty { null },
                )
            }
        }
        return finish(device, receivedAt, result, frames, result.latest?.let(format))
    }

    private fun detail(plan: ImportPlan): String? = buildList {
        if (plan.skippedOld > 0) add("${plan.skippedOld} already imported")
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
                skipped = result.skipped,
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
