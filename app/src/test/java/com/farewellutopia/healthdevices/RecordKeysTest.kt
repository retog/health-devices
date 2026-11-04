package com.farewellutopia.healthdevices

import com.farewellutopia.healthdevices.profiles.RecordKeys
import com.farewellutopia.healthdevices.time.DeviceClock
import com.farewellutopia.healthdevices.time.DeviceTime
import com.farewellutopia.healthdevices.time.ResolvedTime
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class RecordKeysTest {

    private val instant = Instant.parse("2026-11-04T06:30:00Z")
    private val naive = LocalDateTime.of(2026, 11, 4, 7, 30, 0)
    private val stamp = DeviceTime.naiveSeconds(naive)

    @Test
    fun deviceTimeKeyIsTheNaiveStamp() {
        val t = ResolvedTime(instant, ZoneOffset.ofHours(1), fromDevice = true, deviceStamp = stamp)
        assertEquals("bp:aa:bb:cc:dd:ee:ff:$stamp", RecordKeys.key(RecordKeys.BLOOD_PRESSURE, "AA:BB:CC:DD:EE:FF", t))
        assertEquals("hr:aa:bb:cc:dd:ee:ff:$stamp", RecordKeys.key(RecordKeys.HEART_RATE, "aa:bb:cc:dd:ee:ff", t))
        assertNotEquals(instant.epochSecond, stamp)
    }

    @Test
    fun clockSettingDoesNotChangeTheKey() {
        // The same device stamp read as local time or as UTC: different instants, one record.
        val received = Instant.parse("2026-11-04T08:00:00Z")
        val local = DeviceTime.resolve(naive, DeviceClock.LOCAL, ZoneId.of("Europe/Zurich"), received)
        val utc = DeviceTime.resolve(naive, DeviceClock.UTC, ZoneId.of("Europe/Zurich"), received)
        assertNotEquals(local.instant, utc.instant)
        assertEquals(RecordKeys.key("bp", "X", local), RecordKeys.key("bp", "X", utc))
        assertEquals("bp:x:$stamp", RecordKeys.key("bp", "X", local))
    }

    @Test
    fun receiveTimeKeysAreMarkedAndNumbered() {
        val first = ResolvedTime(instant, ZoneOffset.ofHours(1), fromDevice = false, sequence = 0)
        val second = ResolvedTime(instant, ZoneOffset.ofHours(1), fromDevice = false, sequence = 1)
        assertEquals("bp:aa:bb:cc:dd:ee:ff:${instant.epochSecond}-rx-0", RecordKeys.key(RecordKeys.BLOOD_PRESSURE, "AA:BB:CC:DD:EE:FF", first))
        assertEquals("bp:aa:bb:cc:dd:ee:ff:${instant.epochSecond}-rx-1", RecordKeys.key(RecordKeys.BLOOD_PRESSURE, "AA:BB:CC:DD:EE:FF", second))
        assertNotEquals(RecordKeys.key("bp", "X", first), RecordKeys.key("bp", "X", second))
    }
}
