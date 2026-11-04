package com.farewellutopia.healthdevices

import com.farewellutopia.healthdevices.ble.toHex
import com.farewellutopia.healthdevices.importer.ImportLogic
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

    private fun plan(frames: List<ByteArray>, watermark: Long? = null, seen: List<String> = emptyList()) =
        ImportLogic.plan(frames, BloodPressureProfile, DeviceClock.LOCAL, zurich, received, watermark, seen)

    @Test
    fun everythingWithoutWatermark() {
        val p = plan(listOf(older, newer))
        assertEquals(2, p.toWrite.size)
        assertEquals(DeviceTime.naiveSeconds(LocalDateTime.of(2026, 11, 4, 7, 30, 0)), p.watermark)
        assertEquals(0, p.skippedOld)
        assertFalse(p.anyReceiveTime)
    }

    @Test
    fun watermarkSkipsOldAndEqual() {
        val wm = DeviceTime.naiveSeconds(LocalDateTime.of(2026, 11, 3, 7, 0, 0))
        val p = plan(listOf(older, newer), watermark = wm)
        assertEquals(1, p.toWrite.size)
        assertEquals(128.0, (p.toWrite[0].reading as com.farewellutopia.healthdevices.profiles.bloodpressure.BloodPressureMeasurement).systolicMmHg)
        assertEquals(1, p.skippedOld)
        assertTrue(p.watermark!! > wm)
    }

    @Test
    fun nothingNewLeavesWatermark() {
        val wm = DeviceTime.naiveSeconds(LocalDateTime.of(2026, 11, 5, 0, 0, 0))
        val p = plan(listOf(older, newer), watermark = wm)
        assertTrue(p.toWrite.isEmpty())
        assertEquals(wm, p.watermark)
        assertEquals(2, p.skippedOld)
    }

    @Test
    fun duplicateInstantsCollapse() {
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
    fun stamplessReadingUsesReceiveTimeAndIsRememberedByContent() {
        val first = plan(listOf(stampless))
        assertEquals(1, first.toWrite.size)
        assertFalse(first.toWrite[0].time.fromDevice)
        assertEquals(received, first.toWrite[0].time.instant)
        assertTrue(first.anyReceiveTime)
        assertNull(first.watermark)
        val seen = ImportLogic.seenAfter(emptyList(), first)
        assertEquals(listOf(stampless.toHex()), seen)
        // The same frame again, later: recognised and skipped.
        val second = ImportLogic.plan(listOf(stampless), BloodPressureProfile, DeviceClock.LOCAL, zurich, received.plusSeconds(3600), null, seen)
        assertTrue(second.toWrite.isEmpty())
        assertEquals(1, second.skippedSeen)
    }

    @Test
    fun implausibleStampIsTreatedLikeAbsent() {
        val unset = Frames.bp(120, 80, time = Frames.dateTime(2000, 1, 1, 0, 0, 0))
        val p = plan(listOf(unset))
        assertFalse(p.toWrite[0].time.fromDevice)
        assertNull(p.watermark)
    }

    @Test
    fun seenListIsCapped() {
        val seen = (1..ImportLogic.SEEN_LIMIT).map { "%04x".format(it) }
        val p = plan(listOf(stampless))
        val after = ImportLogic.seenAfter(seen, p)
        assertEquals(ImportLogic.SEEN_LIMIT, after.size)
        assertEquals(stampless.toHex(), after.last())
        assertFalse("0001" in after)
    }

    @Test
    fun clockSettingChangesInstantNotWatermark() {
        val local = plan(listOf(newer))
        val utc = ImportLogic.plan(listOf(newer), BloodPressureProfile, DeviceClock.UTC, zurich, received, null, emptyList())
        assertEquals(local.watermark, utc.watermark)
        assertEquals(Instant.parse("2026-11-04T06:30:00Z"), local.toWrite[0].time.instant)
        assertEquals(Instant.parse("2026-11-04T07:30:00Z"), utc.toWrite[0].time.instant)
    }
}
