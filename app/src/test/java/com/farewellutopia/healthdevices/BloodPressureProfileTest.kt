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
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BloodPressureProfileTest {

    private val instant = Instant.parse("2026-11-04T06:30:00Z")
    private val time = ResolvedTime(instant, ZoneOffset.ofHours(1), fromDevice = true)
    private val device = Device(type = Device.TYPE_UNKNOWN, manufacturer = "Beurer", model = "BM81")
    private val metadata: (String) -> Metadata = { id -> Metadata.autoRecorded(device, id, RecordKeys.VERSION) }

    @Test
    fun bloodPressureAndHeartRateRecords() {
        val reading = BloodPressureProfile.decode(Frames.bp(128, 79, time = Frames.dateTime(2026, 11, 4, 7, 30, 0), pulse = 64))!!
        val records = BloodPressureProfile.records(reading, time, "AA:BB:CC:DD:EE:FF", metadata)
        assertEquals(2, records.size)
        val bp = assertIs<BloodPressureRecord>(records[0])
        assertEquals(128.0, bp.systolic.inMillimetersOfMercury)
        assertEquals(79.0, bp.diastolic.inMillimetersOfMercury)
        assertEquals(instant, bp.time)
        assertEquals(ZoneOffset.ofHours(1), bp.zoneOffset)
        assertEquals("bp:aa:bb:cc:dd:ee:ff:${instant.epochSecond}", bp.metadata.clientRecordId)
        assertEquals(1L, bp.metadata.clientRecordVersion)
        assertEquals(Metadata.RECORDING_METHOD_AUTOMATICALLY_RECORDED, bp.metadata.recordingMethod)
        assertEquals("Beurer", bp.metadata.device?.manufacturer)
        val hr = assertIs<HeartRateRecord>(records[1])
        assertEquals(instant, hr.startTime)
        assertEquals(instant, hr.endTime)
        assertEquals(1, hr.samples.size)
        assertEquals(64L, hr.samples[0].beatsPerMinute)
        assertEquals(instant, hr.samples[0].time)
        assertEquals("hr:aa:bb:cc:dd:ee:ff:${instant.epochSecond}", hr.metadata.clientRecordId)
    }

    @Test
    fun noPulseMeansNoHeartRateRecord() {
        val reading = BloodPressureProfile.decode(Frames.bp(120, 80))!!
        val records = BloodPressureProfile.records(reading, time, "X", metadata)
        assertEquals(1, records.size)
        assertIs<BloodPressureRecord>(records[0])
    }

    @Test
    fun outOfRangeValuesAreSkippedNotThrown() {
        // Systolic 250 is outside Health Connect's range; the pulse is still written.
        val reading = BloodPressureProfile.decode(Frames.bp(250, 80, pulse = 70))!!
        val records = BloodPressureProfile.records(reading, time, "X", metadata)
        assertEquals(1, records.size)
        assertIs<HeartRateRecord>(records[0])
        // A NaN systolic likewise.
        val nan = BloodPressureProfile.decode(Frames.bp(0, 0, pressures = listOf(Frames.sfloat(0x7FF, 0), Frames.sfloat(80), Frames.sfloat(90))))!!
        assertTrue(BloodPressureProfile.records(nan, time, "X", metadata).isEmpty())
    }

    @Test
    fun receiveTimeKeysCarrySuffix() {
        val rx = ResolvedTime(instant, ZoneOffset.ofHours(1), fromDevice = false)
        val reading = BloodPressureProfile.decode(Frames.bp(120, 80, pulse = 60))!!
        val records = BloodPressureProfile.records(reading, rx, "X", metadata)
        assertTrue(records.all { it.metadata.clientRecordId!!.endsWith("-rx") })
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
