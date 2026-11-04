package com.farewellutopia.healthdevices.profiles.bloodpressure

/**
 * The IEEE 11073-20601 16-bit SFLOAT the health profiles encode numbers in:
 * a 4-bit signed base-10 exponent in the high nibble and a 12-bit signed
 * mantissa below it, little-endian on the wire. Five mantissa values are not
 * numbers (NaN, NRes, +INF, -INF and a reserved one) and decode to null.
 */
object Sfloat {

    private const val NAN = 0x07FF
    private const val NRES = 0x0800
    private const val POSITIVE_INFINITY = 0x07FE
    private const val NEGATIVE_INFINITY = 0x0802
    private const val RESERVED = 0x0801

    /** Decodes the two bytes at [offset] (little-endian); null for a special value. */
    fun decode(bytes: ByteArray, offset: Int): Double? {
        val raw = (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
        return decode(raw)
    }

    /** Decodes the 16-bit value [raw]; null for a special value. */
    fun decode(raw: Int): Double? {
        val mantissaBits = raw and 0x0FFF
        if (mantissaBits == NAN || mantissaBits == NRES || mantissaBits == POSITIVE_INFINITY ||
            mantissaBits == NEGATIVE_INFINITY || mantissaBits == RESERVED
        ) {
            return null
        }
        // Sign-extend the 12-bit mantissa and the 4-bit exponent.
        val mantissa = if (mantissaBits >= 0x0800) mantissaBits - 0x1000 else mantissaBits
        val exponentBits = (raw shr 12) and 0x0F
        val exponent = if (exponentBits >= 0x08) exponentBits - 0x10 else exponentBits
        return mantissa * Math.pow(10.0, exponent.toDouble())
    }
}
