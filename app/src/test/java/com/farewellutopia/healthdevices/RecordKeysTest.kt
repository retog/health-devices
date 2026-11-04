package com.farewellutopia.healthdevices

import com.farewellutopia.healthdevices.profiles.RecordKeys
import com.farewellutopia.healthdevices.time.ResolvedTime
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class RecordKeysTest {

    private val instant = Instant.parse("2026-11-04T06:30:00Z")

    @Test
    fun deviceTimeKey() {
        val t = ResolvedTime(instant, ZoneOffset.ofHours(1), fromDevice = true)
        assertEquals("bp:aa:bb:cc:dd:ee:ff:${instant.epochSecond}", RecordKeys.key(RecordKeys.BLOOD_PRESSURE, "AA:BB:CC:DD:EE:FF", t))
        assertEquals("hr:aa:bb:cc:dd:ee:ff:${instant.epochSecond}", RecordKeys.key(RecordKeys.HEART_RATE, "aa:bb:cc:dd:ee:ff", t))
    }

    @Test
    fun receiveTimeKeysAreMarkedAndNumbered() {
        val first = ResolvedTime(instant, ZoneOffset.ofHours(1), fromDevice = false, sequence = 0)
        val second = ResolvedTime(instant, ZoneOffset.ofHours(1), fromDevice = false, sequence = 1)
        assertEquals("bp:aa:bb:cc:dd:ee:ff:${instant.epochSecond}-rx-0", RecordKeys.key(RecordKeys.BLOOD_PRESSURE, "AA:BB:CC:DD:EE:FF", first))
        assertEquals("bp:aa:bb:cc:dd:ee:ff:${instant.epochSecond}-rx-1", RecordKeys.key(RecordKeys.BLOOD_PRESSURE, "AA:BB:CC:DD:EE:FF", second))
        assertNotEquals(RecordKeys.key("bp", "X", first), RecordKeys.key("bp", "X", second))
    }

    @Test
    fun keyIgnoresZoneOffsetAndSequenceForDeviceTime() {
        val a = ResolvedTime(instant, ZoneOffset.ofHours(1), fromDevice = true, sequence = 0)
        val b = ResolvedTime(instant, ZoneOffset.UTC, fromDevice = true, sequence = 5)
        assertEquals(RecordKeys.key("bp", "X", a), RecordKeys.key("bp", "X", b))
    }
}
