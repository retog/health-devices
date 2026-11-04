package com.farewellutopia.healthdevices.profiles.bloodpressure

import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Pressure
import java.util.UUID
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import com.farewellutopia.healthdevices.R
import com.farewellutopia.healthdevices.ble.Uuids
import com.farewellutopia.healthdevices.profiles.DeviceProfile
import com.farewellutopia.healthdevices.profiles.Reading
import com.farewellutopia.healthdevices.profiles.RecordKeys
import com.farewellutopia.healthdevices.profiles.Summary
import com.farewellutopia.healthdevices.time.ResolvedTime

/**
 * The Blood Pressure Service (0x1810): a [BloodPressureRecord] for systolic
 * and diastolic, and a one-sample [HeartRateRecord] for the pulse when the
 * device sent one. Mean arterial pressure, user id and the status bits have
 * no place in Health Connect; they stay in the reception log.
 */
object BloodPressureProfile : DeviceProfile {

    override val id = "blood-pressure"
    override val serviceUuid: UUID = Uuids.BLOOD_PRESSURE
    override val measurementUuid: UUID = Uuids.BLOOD_PRESSURE_MEASUREMENT
    override val indicated = true

    // Health Connect's own validation ranges; a reading outside them would be
    // rejected on insert, so it is skipped (and logged) up front.
    private val SYSTOLIC_MMHG = 20.0..200.0
    private val DIASTOLIC_MMHG = 10.0..180.0
    private val PULSE_BPM = 1L..300L

    override fun decode(frame: ByteArray): Reading? = BloodPressureParser.parse(frame)

    override fun records(reading: Reading, time: ResolvedTime, address: String, metadata: (clientRecordId: String) -> Metadata): List<Record> {
        val m = reading as BloodPressureMeasurement
        val records = mutableListOf<Record>()
        val systolic = m.systolicMmHg
        val diastolic = m.diastolicMmHg
        if (systolic != null && diastolic != null && systolic in SYSTOLIC_MMHG && diastolic in DIASTOLIC_MMHG) {
            records += BloodPressureRecord(
                time = time.instant,
                zoneOffset = time.offset,
                metadata = metadata(RecordKeys.key(RecordKeys.BLOOD_PRESSURE, address, time)),
                systolic = Pressure.millimetersOfMercury(systolic),
                diastolic = Pressure.millimetersOfMercury(diastolic),
            )
        }
        val pulse = m.pulseBpm?.roundToLong()
        if (pulse != null && pulse in PULSE_BPM) {
            records += HeartRateRecord(
                startTime = time.instant,
                startZoneOffset = time.offset,
                endTime = time.instant,
                endZoneOffset = time.offset,
                samples = listOf(HeartRateRecord.Sample(time.instant, pulse)),
                metadata = metadata(RecordKeys.key(RecordKeys.HEART_RATE, address, time)),
            )
        }
        return records
    }

    override fun summary(reading: Reading): Summary {
        val m = reading as BloodPressureMeasurement
        val sys = m.systolicMmHg?.roundToInt()
        val dia = m.diastolicMmHg?.roundToInt()
        val pulse = m.pulseBpm?.roundToInt()
        return when {
            sys == null || dia == null -> Summary(R.string.bp_summary_invalid, emptyList())
            pulse == null -> Summary(R.string.bp_summary_no_pulse, listOf(sys, dia))
            else -> Summary(R.string.bp_summary, listOf(sys, dia, pulse))
        }
    }
}
