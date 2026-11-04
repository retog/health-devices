package com.farewellutopia.healthdevices.profiles.bloodpressure

import java.time.LocalDateTime
import com.farewellutopia.healthdevices.profiles.Reading
import com.farewellutopia.healthdevices.profiles.ReadingNote

/** What the Pulse Rate Range bits of the measurement status say. */
enum class PulseRange { WITHIN_RANGE, EXCEEDS_UPPER_LIMIT, BELOW_LOWER_LIMIT, RESERVED }

/**
 * One decoded Blood Pressure Measurement (0x2A35). Pressures are in mmHg
 * whatever unit the device sent (kPa is converted); a pressure or pulse the
 * device marked as not-a-number is null.
 */
data class BloodPressureMeasurement(
    override val frame: ByteArray,
    val systolicMmHg: Double?,
    val diastolicMmHg: Double?,
    val meanArterialMmHg: Double?,
    /** The device's own wall clock, as sent; null when the flag says there is none. */
    override val deviceTime: LocalDateTime?,
    val pulseBpm: Double?,
    val userId: Int?,
    val bodyMovement: Boolean = false,
    val cuffLoose: Boolean = false,
    val irregularPulse: Boolean = false,
    val pulseRange: PulseRange = PulseRange.WITHIN_RANGE,
    val improperPosition: Boolean = false,
    /** True when the unit flag said kPa (the values above are already converted). */
    val sentInKpa: Boolean = false,
) : Reading {

    override val notes: Set<ReadingNote>
        get() = buildSet {
            if (irregularPulse) add(ReadingNote.IRREGULAR_PULSE)
            if (bodyMovement) add(ReadingNote.BODY_MOVEMENT)
            if (cuffLoose) add(ReadingNote.CUFF_LOOSE)
            if (improperPosition) add(ReadingNote.IMPROPER_POSITION)
            if (pulseRange == PulseRange.EXCEEDS_UPPER_LIMIT || pulseRange == PulseRange.BELOW_LOWER_LIMIT) {
                add(ReadingNote.PULSE_OUT_OF_RANGE)
            }
        }

    // frame is a ByteArray, which data classes compare by identity; compare by content instead.
    override fun equals(other: Any?): Boolean =
        other is BloodPressureMeasurement && frame.contentEquals(other.frame) &&
            systolicMmHg == other.systolicMmHg && diastolicMmHg == other.diastolicMmHg &&
            meanArterialMmHg == other.meanArterialMmHg && deviceTime == other.deviceTime &&
            pulseBpm == other.pulseBpm && userId == other.userId &&
            bodyMovement == other.bodyMovement && cuffLoose == other.cuffLoose &&
            irregularPulse == other.irregularPulse && pulseRange == other.pulseRange &&
            improperPosition == other.improperPosition && sentInKpa == other.sentInKpa

    override fun hashCode(): Int = frame.contentHashCode()
}
