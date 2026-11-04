package com.farewellutopia.healthdevices.ble

import java.util.UUID

/** The 16-bit Bluetooth SIG assigned numbers this app speaks, as full UUIDs. */
object Uuids {

    /** The Bluetooth base UUID: a 16-bit assigned number sits in the third and fourth hex digits. */
    fun sig(short: Int): UUID = UUID.fromString("%08x-0000-1000-8000-00805f9b34fb".format(short))

    /** Client Characteristic Configuration descriptor: where notifications and indications are switched on. */
    val CCCD: UUID = sig(0x2902)

    /** Device Information Service and the two of its characteristics the app reads. */
    val DEVICE_INFORMATION: UUID = sig(0x180A)
    val MANUFACTURER_NAME: UUID = sig(0x2A29)
    val MODEL_NUMBER: UUID = sig(0x2A24)

    /** Current Time Service: only its presence is reported for now. */
    val CURRENT_TIME: UUID = sig(0x1805)

    /** Blood Pressure Service and its measurement characteristic (indications). */
    val BLOOD_PRESSURE: UUID = sig(0x1810)
    val BLOOD_PRESSURE_MEASUREMENT: UUID = sig(0x2A35)
}

/** Lower-case hex, no separators: the form raw frames take in the reception log. */
fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

/** The inverse of [toHex]; refuses anything that is not an even run of hex digits. */
fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0 && all { it in "0123456789abcdefABCDEF" }) { "not hex: $this" }
    return ByteArray(length / 2) { i -> substring(2 * i, 2 * i + 2).toInt(16).toByte() }
}
