package com.farewellutopia.healthdevices.profiles.bloodpressure

import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Pressure
import com.farewellutopia.healthdevices.R
import com.farewellutopia.healthdevices.ble.Uuids
import com.farewellutopia.healthdevices.profiles.DeviceProfile
import com.farewellutopia.healthdevices.profiles.ProfileRecords
import com.farewellutopia.healthdevices.profiles.Reading
import com.farewellutopia.healthdevices.profiles.RecordKeys
import com.farewellutopia.healthdevices.profiles.Summary
import com.farewellutopia.healthdevices.time.ResolvedTime
import java.util.UUID
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The Blood Pressure Service (0x1810): a [BloodPressureRecord] for systolic
 * and diastolic, and a one-sample [HeartRateRecord] for the pulse when the
 * device sent one. Mean arterial pressure, user id and the status bits have
 * no place in Health Connect; they stay in the reception log.
 *
 * No ranges of its own: the Health Connect client validates each record in
 * its constructor (this version: systolic 20–200 mmHg, diastolic 10–180,
 * pulse 1–300), and a refused value is reported, not dropped.
 */
object BloodPressureProfile : DeviceProfile {

    override val id = "blood-pressure"
    override val serviceUuid: UUID = Uuids.BLOOD_PRESSURE
    override val measurementUuid: UUID = Uuids.BLOOD_PRESSURE_MEASUREMENT
    override val indicated = true

    override fun decode(frame: ByteArray): Reading? = BloodPressureParser.parse(frame)

    override fun records(reading: Reading, time: ResolvedTime, address: String, metadata: (clientRecordId: String) -> Metadata): ProfileRecords {
        val m = reading as BloodPressureMeasurement
        val records = mutableListOf<Record>()
        val rejected = mutableListOf<String>()
        val systolic = m.systolicMmHg
        val diastolic = m.diastolicMmHg
        if (systolic == null || diastolic == null) {
            rejected += "blood pressure without valid pressures"
        } else {
            try {
                records += BloodPressureRecord(
                    time = time.instant,
                    zoneOffset = time.offset,
                    metadata = metadata(RecordKeys.key(RecordKeys.BLOOD_PRESSURE, address, time)),
                    systolic = Pressure.millimetersOfMercury(systolic),
                    diastolic = Pressure.millimetersOfMercury(diastolic),
                )
            } catch (e: IllegalArgumentException) {
                rejected += "blood pressure ${systolic.roundToInt()}/${diastolic.roundToInt()} mmHg: ${e.message}"
            }
        }
        val pulse = m.pulseBpm?.roundToLong()
        if (pulse != null) {
            try {
                records += HeartRateRecord(
                    startTime = time.instant,
                    startZoneOffset = time.offset,
                    endTime = time.instant,
                    endZoneOffset = time.offset,
                    samples = listOf(HeartRateRecord.Sample(time.instant, pulse)),
                    metadata = metadata(RecordKeys.key(RecordKeys.HEART_RATE, address, time)),
                )
            } catch (e: IllegalArgumentException) {
                rejected += "pulse $pulse: ${e.message}"
            }
        }
        return ProfileRecords(records, rejected)
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
