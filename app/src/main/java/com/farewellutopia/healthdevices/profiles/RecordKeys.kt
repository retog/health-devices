package com.farewellutopia.healthdevices.profiles

import com.farewellutopia.healthdevices.time.ResolvedTime

/**
 * Client record ids. Health Connect upserts a record with the same
 * `clientRecordId` from the same app, so a device that re-sends its whole
 * memory (the Beurer BM81 does, after every measurement) never duplicates
 * anything: the id is the device and the reading's instant. A reading whose
 * instant is the phone's receive time rather than the device's clock carries
 * `-rx-<n>`, n being its place among such readings in that reception — they
 * all share the receive instant, and each is a reading of its own.
 */
object RecordKeys {

    const val BLOOD_PRESSURE = "bp"
    const val HEART_RATE = "hr"
    const val RECEIVED_SUFFIX = "-rx-"

    /** Every record of the app carries version 1; bump when a record's content changes for the same key. */
    const val VERSION = 1L

    fun key(kind: String, address: String, time: ResolvedTime): String {
        val base = "$kind:${address.lowercase()}:${time.instant.epochSecond}"
        return if (time.fromDevice) base else base + RECEIVED_SUFFIX + time.sequence
    }
}
