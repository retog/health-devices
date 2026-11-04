package com.farewellutopia.healthdevices

import com.farewellutopia.healthdevices.profiles.RecordKeys
import com.farewellutopia.healthdevices.time.ResolvedTime
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class RecordKeysTest {

    private val instant = Instant.parse("2026-11-04T06:30:00Z")

    @Test
    fun deviceTimeKey() {
        val t = ResolvedTime(instant, ZoneOffset.ofHours(1), fromDevice = true)
        assertEquals("bp:aa:bb:cc:dd:ee:ff:${instant.epochSecond}", RecordKeys.key(RecordKeys.BLOOD_PRESSURE, "AA:BB:CC:DD:EE:FF", t))
        assertEquals("hr:aa:bb:cc:dd:ee:ff:${instant.epochSecond}", RecordKeys.key(RecordKeys.HEART_RATE, "aa:bb:cc:dd:ee:ff", t))
    }

    @Test
    fun receiveTimeKeyIsMarked() {
        val t = ResolvedTime(instant, ZoneOffset.ofHours(1), fromDevice = false)
        assertEquals("bp:aa:bb:cc:dd:ee:ff:${instant.epochSecond}-rx", RecordKeys.key(RecordKeys.BLOOD_PRESSURE, "AA:BB:CC:DD:EE:FF", t))
    }

    @Test
    fun keyIgnoresZoneOffset() {
        val a = ResolvedTime(instant, ZoneOffset.ofHours(1), fromDevice = true)
        val b = ResolvedTime(instant, ZoneOffset.UTC, fromDevice = true)
        assertEquals(RecordKeys.key("bp", "X", a), RecordKeys.key("bp", "X", b))
    }
}
