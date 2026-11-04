package com.farewellutopia.healthdevices

import com.farewellutopia.healthdevices.profiles.ReadingNote
import com.farewellutopia.healthdevices.profiles.bloodpressure.BloodPressureParser
import com.farewellutopia.healthdevices.profiles.bloodpressure.PulseRange
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BloodPressureParserTest {

    @Test
    fun minimalFrame() {
        val m = BloodPressureParser.parse(Frames.bp(120, 80, 93))!!
        assertEquals(120.0, m.systolicMmHg)
        assertEquals(80.0, m.diastolicMmHg)
        assertEquals(93.0, m.meanArterialMmHg)
        assertNull(m.deviceTime)
        assertNull(m.pulseBpm)
        assertNull(m.userId)
        assertFalse(m.irregularPulse)
        assertTrue(m.notes.isEmpty())
        assertFalse(m.sentInKpa)
    }

    @Test
    fun fullFrame() {
        val frame = Frames.bp(128, 79, 95, time = Frames.dateTime(2026, 11, 4, 7, 30, 15), pulse = 64, userId = 1, status = 0x0004)
        assertEquals(1 + 6 + 7 + 2 + 1 + 2, frame.size)
        val m = BloodPressureParser.parse(frame)!!
        assertEquals(128.0, m.systolicMmHg)
        assertEquals(79.0, m.diastolicMmHg)
        assertEquals(LocalDateTime.of(2026, 11, 4, 7, 30, 15), m.deviceTime)
        assertEquals(64.0, m.pulseBpm)
        assertEquals(1, m.userId)
        assertTrue(m.irregularPulse)
        assertEquals(setOf(ReadingNote.IRREGULAR_PULSE), m.notes)
        assertTrue(frame.contentEquals(m.frame))
    }

    @Test
    fun statusBits() {
        val m = BloodPressureParser.parse(Frames.bp(120, 80, status = 0x0001 or 0x0002 or 0x0008 or 0x0020))!!
        assertTrue(m.bodyMovement)
        assertTrue(m.cuffLoose)
        assertFalse(m.irregularPulse)
        assertEquals(PulseRange.EXCEEDS_UPPER_LIMIT, m.pulseRange)
        assertTrue(m.improperPosition)
        assertEquals(
            setOf(ReadingNote.BODY_MOVEMENT, ReadingNote.CUFF_LOOSE, ReadingNote.PULSE_OUT_OF_RANGE, ReadingNote.IMPROPER_POSITION),
            m.notes,
        )
        assertEquals(PulseRange.BELOW_LOWER_LIMIT, BloodPressureParser.parse(Frames.bp(120, 80, status = 0x0010))!!.pulseRange)
        assertEquals(PulseRange.RESERVED, BloodPressureParser.parse(Frames.bp(120, 80, status = 0x0018))!!.pulseRange)
    }

    @Test
    fun kpaIsConvertedToMmHg() {
        // 17.0 kPa / 10.6 kPa / 12.7 kPa as mantissa×10^-1
        val frame = Frames.bp(0, 0, kpa = true, pressures = listOf(Frames.sfloat(170, -1), Frames.sfloat(106, -1), Frames.sfloat(127, -1)))
        val m = BloodPressureParser.parse(frame)!!
        assertTrue(m.sentInKpa)
        assertEquals(17.0 * BloodPressureParser.MMHG_PER_KPA, m.systolicMmHg!!, 1e-6)
        assertEquals(127.5, m.systolicMmHg!!, 0.2)
        assertEquals(10.6 * BloodPressureParser.MMHG_PER_KPA, m.diastolicMmHg!!, 1e-6)
    }

    @Test
    fun nanPressureIsNull() {
        val frame = Frames.bp(0, 0, pressures = listOf(Frames.sfloat(0x7FF, 0), Frames.sfloat(80), Frames.sfloat(90)))
        val m = BloodPressureParser.parse(frame)!!
        assertNull(m.systolicMmHg)
        assertEquals(80.0, m.diastolicMmHg)
    }

    @Test
    fun timestampWithUnknownPartsIsAbsent() {
        assertNull(BloodPressureParser.parse(Frames.bp(120, 80, time = Frames.dateTime(0, 1, 1, 0, 0, 0)))!!.deviceTime)
        assertNull(BloodPressureParser.parse(Frames.bp(120, 80, time = Frames.dateTime(2026, 0, 1, 0, 0, 0)))!!.deviceTime)
        assertNull(BloodPressureParser.parse(Frames.bp(120, 80, time = Frames.dateTime(2026, 2, 31, 0, 0, 0)))!!.deviceTime)
        assertNull(BloodPressureParser.parse(Frames.bp(120, 80, time = Frames.dateTime(2026, 11, 4, 25, 0, 0)))!!.deviceTime)
    }

    @Test
    fun yearIsLittleEndian() {
        // 2026 = 0x07EA → bytes EA 07
        val frame = Frames.bp(120, 80, time = Frames.dateTime(2026, 1, 2, 3, 4, 5))
        assertEquals(0xEA.toByte(), frame[7])
        assertEquals(0x07.toByte(), frame[8])
        assertEquals(LocalDateTime.of(2026, 1, 2, 3, 4, 5), BloodPressureParser.parse(frame)!!.deviceTime)
    }

    @Test
    fun truncatedFramesAreRejected() {
        assertNull(BloodPressureParser.parse(byteArrayOf()))
        assertNull(BloodPressureParser.parse(byteArrayOf(0x00, 0x78, 0x00, 0x50)))
        // Flags announce a time stamp and a pulse, but only the pressures follow.
        assertNull(BloodPressureParser.parse(byteArrayOf(0x06, 0x78, 0x00, 0x50, 0x00, 0x5D, 0x00)))
        // One byte short of the announced pulse.
        val full = Frames.bp(120, 80, pulse = 60)
        assertNull(BloodPressureParser.parse(full.copyOf(full.size - 1)))
    }

    @Test
    fun trailingBytesAreIgnored() {
        val frame = Frames.bp(120, 80) + byteArrayOf(0x01, 0x02)
        assertEquals(120.0, BloodPressureParser.parse(frame)!!.systolicMmHg)
    }
}
