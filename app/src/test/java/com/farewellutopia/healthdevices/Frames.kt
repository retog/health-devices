package com.farewellutopia.healthdevices

/** Synthetic 0x2A35 frames for the tests; nothing here is a real reading. */
object Frames {

    /** An SFLOAT with exponent 0 (a plain integer up to 2045), little-endian. */
    fun sfloat(value: Int): ByteArray = byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0x0F).toByte())

    /** An SFLOAT from mantissa and exponent, little-endian. */
    fun sfloat(mantissa: Int, exponent: Int): ByteArray {
        val raw = ((exponent and 0x0F) shl 12) or (mantissa and 0x0FFF)
        return byteArrayOf((raw and 0xFF).toByte(), ((raw shr 8) and 0xFF).toByte())
    }

    /** The 7-byte Date Time layout: u16 year (little-endian), month, day, hours, minutes, seconds. */
    fun dateTime(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): ByteArray =
        byteArrayOf((year and 0xFF).toByte(), ((year shr 8) and 0xFF).toByte(), month.toByte(), day.toByte(), hour.toByte(), minute.toByte(), second.toByte())

    /** A full frame: flags, three pressures, then the optional fields the flags announce. */
    fun bp(
        systolic: Int,
        diastolic: Int,
        map: Int = (systolic + 2 * diastolic) / 3,
        time: ByteArray? = null,
        pulse: Int? = null,
        userId: Int? = null,
        status: Int? = null,
        kpa: Boolean = false,
        /** Replaces the three pressures with explicit SFLOAT bytes (for kPa and special values). */
        pressures: List<ByteArray>? = null,
    ): ByteArray {
        var flags = 0
        if (kpa) flags = flags or 0x01
        if (time != null) flags = flags or 0x02
        if (pulse != null) flags = flags or 0x04
        if (userId != null) flags = flags or 0x08
        if (status != null) flags = flags or 0x10
        val out = mutableListOf(flags.toByte())
        (pressures ?: listOf(sfloat(systolic), sfloat(diastolic), sfloat(map))).forEach { out += it.toList() }
        time?.let { out += it.toList() }
        pulse?.let { out += sfloat(it).toList() }
        userId?.let { out += it.toByte() }
        status?.let {
            out += (it and 0xFF).toByte()
            out += ((it shr 8) and 0xFF).toByte()
        }
        return out.toByteArray()
    }
}
