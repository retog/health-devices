package com.farewellutopia.healthdevices

import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import com.farewellutopia.healthdevices.R
import com.farewellutopia.healthdevices.profiles.Profiles
import com.farewellutopia.healthdevices.profiles.RecordKeys
import com.farewellutopia.healthdevices.profiles.bloodpressure.BloodPressureProfile
import com.farewellutopia.healthdevices.time.ResolvedTime
import com.farewellutopia.healthdevices.time.DeviceTime
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BloodPressureProfileTest {

    private val instant = Instant.parse("2026-11-04T06:30:00Z")
    private val stamp = DeviceTime.naiveSeconds(LocalDateTime.of(2026, 11, 4, 7, 30, 0))
    private val time = ResolvedTime(instant, ZoneOffset.ofHours(1), fromDevice = true, deviceStamp = stamp)
    private val device = Device(type = Device.TYPE_UNKNOWN, manufacturer = "Beurer", model = "BM81")
    private val metadata: (String) -> Metadata = { id -> Metadata.autoRecorded(device, id, RecordKeys.VERSION) }

    @Test
    fun bloodPressureAndHeartRateRecords() {
        val reading = BloodPressureProfile.decode(Frames.bp(128, 79, time = Frames.dateTime(2026, 11, 4, 7, 30, 0), pulse = 64))!!
        val built = BloodPressureProfile.records(reading, time, "AA:BB:CC:DD:EE:FF", metadata)
        assertTrue(built.rejected.isEmpty())
        val records = built.records
        assertEquals(2, records.size)
        val bp = assertIs<BloodPressureRecord>(records[0])
        assertEquals(128.0, bp.systolic.inMillimetersOfMercury)
        assertEquals(79.0, bp.diastolic.inMillimetersOfMercury)
        assertEquals(instant, bp.time)
        assertEquals(ZoneOffset.ofHours(1), bp.zoneOffset)
        assertEquals("bp:aa:bb:cc:dd:ee:ff:$stamp", bp.metadata.clientRecordId)
        assertEquals(1L, bp.metadata.clientRecordVersion)
        assertEquals(Metadata.RECORDING_METHOD_AUTOMATICALLY_RECORDED, bp.metadata.recordingMethod)
        assertEquals("Beurer", bp.metadata.device?.manufacturer)
        val hr = assertIs<HeartRateRecord>(records[1])
        assertEquals(instant, hr.startTime)
        assertEquals(instant, hr.endTime)
        assertEquals(1, hr.samples.size)
        assertEquals(64L, hr.samples[0].beatsPerMinute)
        assertEquals(instant, hr.samples[0].time)
        assertEquals("hr:aa:bb:cc:dd:ee:ff:$stamp", hr.metadata.clientRecordId)
    }

    @Test
    fun noPulseMeansNoHeartRateRecord() {
        val reading = BloodPressureProfile.decode(Frames.bp(120, 80))!!
        val built = BloodPressureProfile.records(reading, time, "X", metadata)
        assertEquals(1, built.records.size)
        assertIs<BloodPressureRecord>(built.records[0])
        assertTrue(built.rejected.isEmpty())
    }

    @Test
    fun highReadingsWithinHealthConnectRangesAreWritten() {
        // A hypertensive crisis must not vanish: 195/115 is inside the client's ranges and becomes a record.
        val reading = BloodPressureProfile.decode(Frames.bp(195, 115, pulse = 140))!!
        val built = BloodPressureProfile.records(reading, time, "X", metadata)
        assertEquals(2, built.records.size)
        assertTrue(built.rejected.isEmpty())
    }

    @Test
    fun valuesTheClientRefusesAreReportedNotDropped() {
        // The Health Connect client validates systolic 20–200 mmHg in the record constructor;
        // 250 cannot become a record, and the import has to say so. The pulse is still written.
        val reading = BloodPressureProfile.decode(Frames.bp(250, 80, pulse = 70))!!
        val built = BloodPressureProfile.records(reading, time, "X", metadata)
        assertEquals(1, built.records.size)
        assertIs<HeartRateRecord>(built.records[0])
        assertEquals(1, built.rejected.size)
        assertTrue(built.rejected[0].startsWith("blood pressure 250/80 mmHg"), built.rejected[0])
        // A pulse of 0 likewise (the client wants 1–300).
        val zeroPulse = BloodPressureProfile.decode(Frames.bp(120, 80, pulse = 0))!!
        val built2 = BloodPressureProfile.records(zeroPulse, time, "X", metadata)
        assertEquals(1, built2.records.size)
        assertIs<BloodPressureRecord>(built2.records[0])
        assertEquals(1, built2.rejected.size)
        assertTrue(built2.rejected[0].startsWith("pulse 0"), built2.rejected[0])
    }

    @Test
    fun nanPressuresAreRejectedWithReason() {
        val nan = BloodPressureProfile.decode(Frames.bp(0, 0, pressures = listOf(Frames.sfloat(0x7FF, 0), Frames.sfloat(80), Frames.sfloat(90))))!!
        val built = BloodPressureProfile.records(nan, time, "X", metadata)
        assertTrue(built.records.isEmpty())
        assertEquals(listOf("blood pressure without valid pressures"), built.rejected)
    }

    @Test
    fun receiveTimeKeysCarrySuffix() {
        val rx = ResolvedTime(instant, ZoneOffset.ofHours(1), fromDevice = false, sequence = 2)
        val reading = BloodPressureProfile.decode(Frames.bp(120, 80, pulse = 60))!!
        val built = BloodPressureProfile.records(reading, rx, "X", metadata)
        assertTrue(built.records.all { it.metadata.clientRecordId!!.endsWith("-rx-2") })
    }

    @Test
    fun summaries() {
        val full = BloodPressureProfile.summary(BloodPressureProfile.decode(Frames.bp(128, 79, pulse = 64))!!)
        assertEquals(R.string.bp_summary, full.textRes)
        assertEquals(listOf(128, 79, 64), full.args)
        val noPulse = BloodPressureProfile.summary(BloodPressureProfile.decode(Frames.bp(128, 79))!!)
        assertEquals(R.string.bp_summary_no_pulse, noPulse.textRes)
        assertEquals(listOf(128, 79), noPulse.args)
        val invalid = BloodPressureProfile.summary(
            BloodPressureProfile.decode(Frames.bp(0, 0, pressures = listOf(Frames.sfloat(0x7FF, 0), Frames.sfloat(80), Frames.sfloat(90))))!!,
        )
        assertEquals(R.string.bp_summary_invalid, invalid.textRes)
    }

    @Test
    fun registry() {
        assertEquals(BloodPressureProfile, Profiles.byId("blood-pressure"))
        assertEquals(BloodPressureProfile, Profiles.byService(listOf(BloodPressureProfile.serviceUuid)))
        assertEquals(null, Profiles.byService(emptyList()))
        assertEquals(listOf(BloodPressureProfile.serviceUuid), Profiles.SERVICE_UUIDS)
    }
}
