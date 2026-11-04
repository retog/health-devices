package com.farewellutopia.healthdevices.profiles

import com.farewellutopia.healthdevices.time.ResolvedTime

/**
 * Client record ids. Health Connect upserts a record with the same
 * `clientRecordId` from the same app, so a device that re-sends its whole
 * memory (the Beurer BM81 does, after every measurement) never duplicates
 * anything: the id is the device and the reading's own time stamp — the
 * naive wall clock the device sent, as seconds, not the resolved instant.
 * That way a corrected UTC/local setting plus "Re-import everything" rewrites
 * the same records in place instead of adding a shifted copy of each.
 *
 * A reading whose instant is the phone's receive time rather than the
 * device's clock carries the receive epoch and `-rx-<n>`, n being its place
 * among such readings in that reception — they all share the receive
 * instant, and each is a reading of its own.
 */
object RecordKeys {

    const val BLOOD_PRESSURE = "bp"
    const val HEART_RATE = "hr"
    const val RECEIVED_SUFFIX = "-rx-"

    /** Every record of the app carries version 1; bump when a record's content changes for the same key. */
    const val VERSION = 1L

    fun key(kind: String, address: String, time: ResolvedTime): String {
        val prefix = "$kind:${address.lowercase()}:"
        val stamp = time.deviceStamp
        return if (time.fromDevice && stamp != null) {
            prefix + stamp
        } else {
            prefix + time.instant.epochSecond + RECEIVED_SUFFIX + time.sequence
        }
    }
}
