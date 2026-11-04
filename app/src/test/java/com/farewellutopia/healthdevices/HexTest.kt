package com.farewellutopia.healthdevices

import com.farewellutopia.healthdevices.ble.hexToBytes
import com.farewellutopia.healthdevices.ble.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HexTest {

    @Test
    fun roundTrip() {
        val bytes = byteArrayOf(0x00, 0x7F, 0x80.toByte(), 0xFF.toByte())
        assertEquals("007f80ff", bytes.toHex())
        assertTrue(bytes.contentEquals("007f80ff".hexToBytes()))
        assertTrue(bytes.contentEquals("007F80FF".hexToBytes()))
    }

    @Test
    fun rejectsNonHex() {
        assertFailsWith<IllegalArgumentException> { "abc".hexToBytes() }
        assertFailsWith<IllegalArgumentException> { "zz".hexToBytes() }
    }
}
