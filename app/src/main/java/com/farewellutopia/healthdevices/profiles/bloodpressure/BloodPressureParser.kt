package com.farewellutopia.healthdevices.profiles.bloodpressure

import java.time.LocalDateTime

/**
 * Parses the Blood Pressure Measurement characteristic (0x2A35) as the
 * Bluetooth Blood Pressure Service specification lays it out:
 *
 * ```
 * flags            u8    bit0 unit (0 mmHg, 1 kPa) · bit1 time stamp · bit2 pulse rate
 *                        bit3 user id · bit4 measurement status
 * systolic         SFLOAT
 * diastolic        SFLOAT
 * mean arterial    SFLOAT
 * [time stamp      u16 year, u8 month, u8 day, u8 hours, u8 minutes, u8 seconds]
 * [pulse rate      SFLOAT]
 * [user id         u8]
 * [status          u16   bit0 body movement · bit1 cuff too loose · bit2 irregular pulse
 *                        bits3–4 pulse rate range · bit5 improper position]
 * ```
 *
 * A frame that is too short for what its flags announce is rejected (null)
 * rather than read half-way.
 */
object BloodPressureParser {

    private const val FLAG_KPA = 0x01
    private const val FLAG_TIMESTAMP = 0x02
    private const val FLAG_PULSE = 0x04
    private const val FLAG_USER_ID = 0x08
    private const val FLAG_STATUS = 0x10

    private const val STATUS_BODY_MOVEMENT = 0x0001
    private const val STATUS_CUFF_LOOSE = 0x0002
    private const val STATUS_IRREGULAR_PULSE = 0x0004
    private const val STATUS_PULSE_RANGE_SHIFT = 3
    private const val STATUS_IMPROPER_POSITION = 0x0020

    /** 1 kPa in mmHg (exactly 1000 / 133.322387415). */
    const val MMHG_PER_KPA = 7.50061683

    fun parse(frame: ByteArray): BloodPressureMeasurement? {
        if (frame.size < 7) return null
        val flags = frame[0].toInt() and 0xFF
        var length = 7
        if (flags and FLAG_TIMESTAMP != 0) length += 7
        if (flags and FLAG_PULSE != 0) length += 2
        if (flags and FLAG_USER_ID != 0) length += 1
        if (flags and FLAG_STATUS != 0) length += 2
        if (frame.size < length) return null

        val kpa = flags and FLAG_KPA != 0
        fun pressure(offset: Int): Double? = Sfloat.decode(frame, offset)?.let { if (kpa) it * MMHG_PER_KPA else it }
        val systolic = pressure(1)
        val diastolic = pressure(3)
        val meanArterial = pressure(5)
        var p = 7

        var time: LocalDateTime? = null
        if (flags and FLAG_TIMESTAMP != 0) {
            time = dateTime(frame, p)
            p += 7
        }
        var pulse: Double? = null
        if (flags and FLAG_PULSE != 0) {
            pulse = Sfloat.decode(frame, p)
            p += 2
        }
        var userId: Int? = null
        if (flags and FLAG_USER_ID != 0) {
            userId = frame[p].toInt() and 0xFF
            p += 1
        }
        var status = 0
        if (flags and FLAG_STATUS != 0) {
            status = (frame[p].toInt() and 0xFF) or ((frame[p + 1].toInt() and 0xFF) shl 8)
        }
        val pulseRange = when ((status shr STATUS_PULSE_RANGE_SHIFT) and 0x03) {
            0 -> PulseRange.WITHIN_RANGE
            1 -> PulseRange.EXCEEDS_UPPER_LIMIT
            2 -> PulseRange.BELOW_LOWER_LIMIT
            else -> PulseRange.RESERVED
        }
        return BloodPressureMeasurement(
            frame = frame.copyOf(),
            systolicMmHg = systolic,
            diastolicMmHg = diastolic,
            meanArterialMmHg = meanArterial,
            deviceTime = time,
            pulseBpm = pulse,
            userId = userId,
            bodyMovement = status and STATUS_BODY_MOVEMENT != 0,
            cuffLoose = status and STATUS_CUFF_LOOSE != 0,
            irregularPulse = status and STATUS_IRREGULAR_PULSE != 0,
            pulseRange = pulseRange,
            improperPosition = status and STATUS_IMPROPER_POSITION != 0,
            sentInKpa = kpa,
        )
    }

    /**
     * The 7-byte Date Time characteristic layout (0x2A08). The specification
     * lets a device send 0 for a year, month or day it does not know; such a
     * time stamp, like one that is not a calendar date at all, is treated as
     * absent (null), and the receive time is used instead.
     */
    fun dateTime(bytes: ByteArray, offset: Int): LocalDateTime? {
        val year = (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
        val month = bytes[offset + 2].toInt() and 0xFF
        val day = bytes[offset + 3].toInt() and 0xFF
        val hours = bytes[offset + 4].toInt() and 0xFF
        val minutes = bytes[offset + 5].toInt() and 0xFF
        val seconds = bytes[offset + 6].toInt() and 0xFF
        if (year == 0 || month == 0 || day == 0) return null
        return try {
            LocalDateTime.of(year, month, day, hours, minutes, seconds)
        } catch (e: java.time.DateTimeException) {
            null
        }
    }
}
