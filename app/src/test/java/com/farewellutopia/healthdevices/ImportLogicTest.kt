package com.farewellutopia.healthdevices

import com.farewellutopia.healthdevices.importer.ImportLogic
import com.farewellutopia.healthdevices.profiles.RecordKeys
import com.farewellutopia.healthdevices.profiles.bloodpressure.BloodPressureMeasurement
import com.farewellutopia.healthdevices.profiles.bloodpressure.BloodPressureProfile
import com.farewellutopia.healthdevices.time.DeviceClock
import com.farewellutopia.healthdevices.time.DeviceTime
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImportLogicTest {

    private val zurich = ZoneId.of("Europe/Zurich")
    private val received = Instant.parse("2026-11-04T08:00:00Z")
    private val older = Frames.bp(118, 76, time = Frames.dateTime(2026, 11, 3, 7, 0, 0), pulse = 60)
    private val newer = Frames.bp(128, 79, time = Frames.dateTime(2026, 11, 4, 7, 30, 0), pulse = 64)
    private val stampless = Frames.bp(130, 85, pulse = 70)

    private fun plan(frames: List<ByteArray>, watermark: Long? = null, clock: DeviceClock = DeviceClock.LOCAL) =
        ImportLogic.plan(frames, BloodPressureProfile, clock, zurich, received, watermark)

    @Test
    fun everythingWithoutWatermark() {
        val p = plan(listOf(older, newer))
        assertEquals(2, p.toWrite.size)
        assertEquals(0, p.skippedOld)
        assertFalse(p.anyReceiveTime)
        assertEquals(DeviceTime.naiveSeconds(LocalDateTime.of(2026, 11, 4, 7, 30, 0)), ImportLogic.watermarkAfter(null, p.toWrite))
    }

    @Test
    fun watermarkSkipsOldAndEqual() {
        val wm = DeviceTime.naiveSeconds(LocalDateTime.of(2026, 11, 3, 7, 0, 0))
        val p = plan(listOf(older, newer), watermark = wm)
        assertEquals(1, p.toWrite.size)
        assertEquals(128.0, (p.toWrite[0].reading as BloodPressureMeasurement).systolicMmHg)
        assertEquals(1, p.skippedOld)
        assertTrue(ImportLogic.watermarkAfter(wm, p.toWrite)!! > wm)
    }

    @Test
    fun nothingNewLeavesWatermark() {
        val wm = DeviceTime.naiveSeconds(LocalDateTime.of(2026, 11, 5, 0, 0, 0))
        val p = plan(listOf(older, newer), watermark = wm)
        assertTrue(p.toWrite.isEmpty())
        assertEquals(wm, ImportLogic.watermarkAfter(wm, p.toWrite))
        assertEquals(2, p.skippedOld)
    }

    @Test
    fun duplicateStampsCollapse() {
        val p = plan(listOf(newer, newer.copyOf()))
        assertEquals(1, p.toWrite.size)
    }

    @Test
    fun undecodableFramesAreCounted() {
        val p = plan(listOf(byteArrayOf(0x06, 0x01), newer))
        assertEquals(1, p.undecodable)
        assertEquals(1, p.toWrite.size)
    }

    @Test
    fun stamplessReadingsAreAllKeptWithDistinctKeys() {
        // Three readings without a time stamp, two of them byte-identical: three readings, three ids.
        val p = plan(listOf(stampless, Frames.bp(120, 80, pulse = 60), stampless.copyOf()))
        assertEquals(3, p.toWrite.size)
        assertTrue(p.anyReceiveTime)
        assertTrue(p.toWrite.all { !it.time.fromDevice && it.time.instant == received })
        assertEquals(listOf(0, 1, 2), p.toWrite.map { it.time.sequence })
        val keys = p.toWrite.map { RecordKeys.key(RecordKeys.BLOOD_PRESSURE, "X", it.time) }
        assertEquals(3, keys.toSet().size)
        assertTrue(keys.all { it.endsWith("-rx-${keys.indexOf(it)}") })
        // They carry no device stamp, so they never move the watermark.
        assertNull(ImportLogic.watermarkAfter(null, p.toWrite))
    }

    @Test
    fun stampedAndStamplessMix() {
        val p = plan(listOf(stampless, newer, stampless))
        assertEquals(3, p.toWrite.size)
        assertEquals(1, p.toWrite.count { it.time.fromDevice })
        assertEquals(listOf(0, 1), p.toWrite.filter { !it.time.fromDevice }.map { it.time.sequence })
    }

    @Test
    fun implausibleStampIsTreatedLikeAbsent() {
        val unset = Frames.bp(120, 80, time = Frames.dateTime(2000, 1, 1, 0, 0, 0))
        val p = plan(listOf(unset))
        assertFalse(p.toWrite[0].time.fromDevice)
        assertNull(ImportLogic.watermarkAfter(null, p.toWrite))
    }

    @Test
    fun futureStampDoesNotBecomeTheWatermark() {
        // A device clock set to 2062: the reading is kept (at the receive time), but it must not
        // become a watermark that hides every real reading after it.
        val future = Frames.bp(120, 80, time = Frames.dateTime(2062, 1, 1, 0, 0, 0))
        val p = plan(listOf(future, newer))
        assertEquals(2, p.toWrite.size)
        val futureReading = p.toWrite.first { !it.time.fromDevice }
        assertEquals(received, futureReading.time.instant)
        assertEquals(DeviceTime.naiveSeconds(LocalDateTime.of(2026, 11, 4, 7, 30, 0)), ImportLogic.watermarkAfter(null, p.toWrite))
        // Just inside the allowance (received 09:00 local; 23 h ahead) is still believed.
        val soon = Frames.bp(120, 80, time = Frames.dateTime(2026, 11, 5, 8, 0, 0))
        assertTrue(plan(listOf(soon)).toWrite[0].time.fromDevice)
    }

    @Test
    fun watermarkAdvancesOnlyPastDoneReadings() {
        val p = plan(listOf(older, newer))
        val onlyOlderDone = p.toWrite.filter { (it.reading as BloodPressureMeasurement).systolicMmHg == 118.0 }
        assertEquals(DeviceTime.naiveSeconds(LocalDateTime.of(2026, 11, 3, 7, 0, 0)), ImportLogic.watermarkAfter(null, onlyOlderDone))
        // An older "before" never pulls the watermark back.
        val wm = DeviceTime.naiveSeconds(LocalDateTime.of(2026, 11, 4, 7, 0, 0))
        assertEquals(wm, ImportLogic.watermarkAfter(wm, onlyOlderDone))
        assertEquals(wm, ImportLogic.watermarkAfter(wm, emptyList()))
    }

    @Test
    fun newestStampForPairing() {
        val newest = DeviceTime.naiveSeconds(LocalDateTime.of(2026, 11, 4, 7, 30, 0))
        assertEquals(newest, ImportLogic.newestStamp(listOf(older, newer, stampless), BloodPressureProfile, DeviceClock.LOCAL, zurich, received))
        // The same whatever the clock setting: the watermark is naive.
        assertEquals(newest, ImportLogic.newestStamp(listOf(older, newer), BloodPressureProfile, DeviceClock.UTC, zurich, received))
        // Implausible stamps do not count; no usable stamp means null (everything is new).
        val future = Frames.bp(120, 80, time = Frames.dateTime(2062, 1, 1, 0, 0, 0))
        assertEquals(newest, ImportLogic.newestStamp(listOf(newer, future), BloodPressureProfile, DeviceClock.LOCAL, zurich, received))
        assertNull(ImportLogic.newestStamp(listOf(stampless, future), BloodPressureProfile, DeviceClock.LOCAL, zurich, received))
        assertNull(ImportLogic.newestStamp(emptyList(), BloodPressureProfile, DeviceClock.LOCAL, zurich, received))
    }

    @Test
    fun clockSettingChangesInstantNotWatermark() {
        val local = plan(listOf(newer))
        val utc = plan(listOf(newer), clock = DeviceClock.UTC)
        assertEquals(ImportLogic.watermarkAfter(null, local.toWrite), ImportLogic.watermarkAfter(null, utc.toWrite))
        assertEquals(Instant.parse("2026-11-04T06:30:00Z"), local.toWrite[0].time.instant)
        assertEquals(Instant.parse("2026-11-04T07:30:00Z"), utc.toWrite[0].time.instant)
    }
}
