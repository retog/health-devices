package com.farewellutopia.healthdevices.profiles

import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.Metadata
import java.time.LocalDateTime
import java.util.UUID
import com.farewellutopia.healthdevices.time.ResolvedTime

/** Things a reading can flag that the notification mentions. */
enum class ReadingNote { IRREGULAR_PULSE, BODY_MOVEMENT, CUFF_LOOSE, IMPROPER_POSITION, PULSE_OUT_OF_RANGE }

/**
 * One measurement a profile decoded from one frame. What the import needs
 * from any profile: the device's own time stamp (a naive wall clock, or none),
 * the frame for the log, and the notes worth mentioning.
 */
interface Reading {
    val frame: ByteArray
    val deviceTime: LocalDateTime?
    val notes: Set<ReadingNote>
}

/**
 * A standard Bluetooth health profile: which service and characteristic carry
 * the measurements, how one frame decodes, and which Health Connect records
 * it becomes. Adding a profile (weight scale 0x181D, thermometer 0x1809,
 * glucose 0x1808, pulse oximeter 0x1822) means one implementation of this
 * and a line in [Profiles]; the scan, the GATT session, the time handling,
 * the dedupe and the service are shared.
 */
interface DeviceProfile {

    /** Stable identifier stored with a paired device (`blood-pressure`). */
    val id: String

    /** The GATT service the device advertises and the app scans for. */
    val serviceUuid: UUID

    /** The characteristic that carries measurements. */
    val measurementUuid: UUID

    /** True when the characteristic is indicated (acknowledged), false when notified. */
    val indicated: Boolean

    /** Decodes one frame; null when it is not a well-formed measurement. */
    fun decode(frame: ByteArray): Reading?

    /**
     * The Health Connect records for one reading at its resolved instant.
     * [metadata] builds the record metadata for a client record id; the ids
     * come from [RecordKeys] so re-importing the same reading upserts.
     */
    fun records(reading: Reading, time: ResolvedTime, address: String, metadata: (clientRecordId: String) -> Metadata): List<Record>

    /** A short human line for the notification and the log, e.g. "128/79 mmHg, pulse 64", as a string resource and its arguments. */
    fun summary(reading: Reading): Summary
}

/** A string resource with its format arguments: the profile says what, the resources say how. */
data class Summary(val textRes: Int, val args: List<Any>)
