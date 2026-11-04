package com.farewellutopia.healthdevices

import com.farewellutopia.healthdevices.time.DeviceClock
import com.farewellutopia.healthdevices.time.DeviceTime
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceTimeTest {

    private val zurich = ZoneId.of("Europe/Zurich")
    private val received = Instant.parse("2026-11-04T08:00:00Z")

    @Test
    fun localClockInWinter() {
        val t = DeviceTime.resolve(LocalDateTime.of(2026, 11, 4, 7, 30, 0), DeviceClock.LOCAL, zurich, received)
        assertEquals(Instant.parse("2026-11-04T06:30:00Z"), t.instant)
        assertEquals(ZoneOffset.ofHours(1), t.offset)
        assertTrue(t.fromDevice)
    }

    @Test
    fun localClockInSummer() {
        val t = DeviceTime.resolve(LocalDateTime.of(2026, 7, 1, 7, 30, 0), DeviceClock.LOCAL, zurich, received)
        assertEquals(Instant.parse("2026-07-01T05:30:00Z"), t.instant)
        assertEquals(ZoneOffset.ofHours(2), t.offset)
    }

    @Test
    fun utcClock() {
        val t = DeviceTime.resolve(LocalDateTime.of(2026, 7, 1, 7, 30, 0), DeviceClock.UTC, zurich, received)
        assertEquals(Instant.parse("2026-07-01T07:30:00Z"), t.instant)
        assertEquals(ZoneOffset.UTC, t.offset)
        assertTrue(t.fromDevice)
    }

    @Test
    fun absentTimeFallsBackToReceiveTime() {
        val t = DeviceTime.resolve(null, DeviceClock.LOCAL, zurich, received, sequence = 3)
        assertEquals(received, t.instant)
        assertEquals(ZoneOffset.ofHours(1), t.offset)
        assertFalse(t.fromDevice)
        assertEquals(3, t.sequence)
    }

    @Test
    fun implausibleYearFallsBackToReceiveTime() {
        val t = DeviceTime.resolve(LocalDateTime.of(2010, 1, 1, 0, 0, 0), DeviceClock.UTC, zurich, received)
        assertEquals(received, t.instant)
        assertFalse(t.fromDevice)
        // The receive time's offset follows the phone's zone, even for a UTC device.
        assertEquals(ZoneOffset.ofHours(1), t.offset)
        val latest = DeviceTime.latestPlausible(DeviceClock.UTC, zurich, received)
        assertTrue(DeviceTime.isPlausible(LocalDateTime.of(2020, 1, 1, 0, 0), latest))
        assertFalse(DeviceTime.isPlausible(LocalDateTime.of(2019, 12, 31, 23, 59), latest))
        assertFalse(DeviceTime.isPlausible(null, latest))
    }

    @Test
    fun futureStampFallsBackToReceiveTime() {
        // Received at 08:00Z: a local-clock device may be up to 09:00 local next day, a UTC one up to 08:00Z next day.
        assertEquals(LocalDateTime.of(2026, 11, 5, 9, 0, 0), DeviceTime.latestPlausible(DeviceClock.LOCAL, zurich, received))
        assertEquals(LocalDateTime.of(2026, 11, 5, 8, 0, 0), DeviceTime.latestPlausible(DeviceClock.UTC, zurich, received))
        val farFuture = DeviceTime.resolve(LocalDateTime.of(2062, 1, 1, 0, 0, 0), DeviceClock.LOCAL, zurich, received)
        assertFalse(farFuture.fromDevice)
        assertEquals(received, farFuture.instant)
        val justOver = DeviceTime.resolve(LocalDateTime.of(2026, 11, 5, 9, 0, 1), DeviceClock.LOCAL, zurich, received)
        assertFalse(justOver.fromDevice)
        val justInside = DeviceTime.resolve(LocalDateTime.of(2026, 11, 5, 9, 0, 0), DeviceClock.LOCAL, zurich, received)
        assertTrue(justInside.fromDevice)
        // A device clock a few minutes fast (the common case) is believed.
        val slightlyAhead = DeviceTime.resolve(LocalDateTime.of(2026, 11, 4, 9, 5, 0), DeviceClock.LOCAL, zurich, received)
        assertTrue(slightlyAhead.fromDevice)
    }

    @Test
    fun summerTimeGapAndOverlap() {
        // 2026-03-29 02:30 does not exist in Zurich: shifted forward to 03:30 CEST.
        val gap = DeviceTime.resolve(LocalDateTime.of(2026, 3, 29, 2, 30, 0), DeviceClock.LOCAL, zurich, received)
        assertEquals(Instant.parse("2026-03-29T01:30:00Z"), gap.instant)
        // 2026-10-25 02:30 happens twice: the earlier (CEST) one is taken.
        val overlap = DeviceTime.resolve(LocalDateTime.of(2026, 10, 25, 2, 30, 0), DeviceClock.LOCAL, zurich, received)
        assertEquals(Instant.parse("2026-10-25T00:30:00Z"), overlap.instant)
        assertEquals(ZoneOffset.ofHours(2), overlap.offset)
    }

    @Test
    fun watermarkFormIsZoneIndependent() {
        val naive = LocalDateTime.of(2026, 11, 4, 7, 30, 0)
        assertEquals(naive.toEpochSecond(ZoneOffset.UTC), DeviceTime.naiveSeconds(naive))
    }

    @Test
    fun clockKeys() {
        assertEquals(DeviceClock.UTC, DeviceClock.fromKey("utc"))
        assertEquals(DeviceClock.LOCAL, DeviceClock.fromKey("local"))
        assertEquals(DeviceClock.LOCAL, DeviceClock.fromKey(null))
        assertEquals(DeviceClock.LOCAL, DeviceClock.fromKey("garbage"))
    }
}
