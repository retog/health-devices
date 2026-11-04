package com.farewellutopia.healthdevices

import com.farewellutopia.healthdevices.profiles.bloodpressure.Sfloat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SfloatTest {

    @Test
    fun plainIntegers() {
        assertEquals(128.0, Sfloat.decode(0x0080))
        assertEquals(0.0, Sfloat.decode(0x0000))
        // 0x07FE is +INF and 0x07FF NaN, so 2045 is the largest plain positive mantissa.
        assertEquals(2045.0, Sfloat.decode(0x07FD))
    }

    @Test
    fun negativeMantissa() {
        // 0xFFF = -1 in 12-bit two's complement
        assertEquals(-1.0, Sfloat.decode(0x0FFF))
        // 0x0800 is NRes; 0x0803 is the most negative plain mantissa.
        assertEquals(-2045.0, Sfloat.decode(0x0803))
    }

    @Test
    fun exponents() {
        // mantissa 170, exponent -1 → 17.0
        assertEquals(17.0, Sfloat.decode(0xF0AA)!!, 1e-9)
        // mantissa 12, exponent 2 → 1200
        assertEquals(1200.0, Sfloat.decode(0x200C)!!, 1e-9)
        // mantissa -5, exponent 1 → -50
        assertEquals(-50.0, Sfloat.decode(0x1FFB)!!, 1e-9)
    }

    @Test
    fun specialValuesAreNull() {
        assertNull(Sfloat.decode(0x07FF)) // NaN
        assertNull(Sfloat.decode(0x0800)) // NRes
        assertNull(Sfloat.decode(0x07FE)) // +INF
        assertNull(Sfloat.decode(0x0802)) // -INF
        assertNull(Sfloat.decode(0x0801)) // reserved
        // The exponent does not change that.
        assertNull(Sfloat.decode(0x37FF))
    }

    @Test
    fun littleEndianBytes() {
        val bytes = byteArrayOf(0x00, 0xAA.toByte(), 0xF0.toByte())
        assertEquals(17.0, Sfloat.decode(bytes, 1)!!, 1e-9)
    }
}
