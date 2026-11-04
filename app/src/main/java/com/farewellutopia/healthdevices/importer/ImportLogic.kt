package com.farewellutopia.healthdevices.importer

import java.time.Instant
import java.time.ZoneId
import com.farewellutopia.healthdevices.ble.toHex
import com.farewellutopia.healthdevices.profiles.DeviceProfile
import com.farewellutopia.healthdevices.profiles.Reading
import com.farewellutopia.healthdevices.time.DeviceClock
import com.farewellutopia.healthdevices.time.DeviceTime
import com.farewellutopia.healthdevices.time.ResolvedTime

/** One reading that is going to Health Connect, with its instant settled. */
data class PlannedReading(val reading: Reading, val time: ResolvedTime)

/**
 * What one batch of frames turns into: the readings to write, the watermark
 * afterwards, and the counts for the notification. Pure, so the whole
 * dedupe policy is unit-tested; [Importer] does the writing.
 */
data class ImportPlan(
    val toWrite: List<PlannedReading>,
    /** The newest device time stamp seen, as [DeviceTime.naiveSeconds]; unchanged when nothing newer arrived. */
    val watermark: Long?,
    /** Frames that decoded to a reading older than (or already at) the watermark. */
    val skippedOld: Int,
    /** Frames the profile could not decode. */
    val undecodable: Int,
    /** Frames without a usable device time stamp that were imported earlier (by content). */
    val skippedSeen: Int,
) {
    /** True when at least one written reading carries the receive time instead of the device's. */
    val anyReceiveTime: Boolean get() = toWrite.any { !it.time.fromDevice }
}

object ImportLogic {

    /** How many frame hashes of time-stamp-less readings are remembered per device. */
    const val SEEN_LIMIT = 200

    /**
     * Decides what to write from [frames] received at [receivedAt]:
     *
     * - a frame the profile cannot decode is counted and dropped;
     * - a reading with a plausible device time stamp at or before [watermark]
     *   was imported before and is skipped (Health Connect would upsert it
     *   anyway, but writing it again on every reception is noise);
     * - two frames with the same instant in one batch collapse to the last one;
     * - a reading without a usable time stamp gets the receive time and is
     *   recognised on a re-send by its frame content ([seenFrames]).
     */
    fun plan(
        frames: List<ByteArray>,
        profile: DeviceProfile,
        clock: DeviceClock,
        phoneZone: ZoneId,
        receivedAt: Instant,
        watermark: Long?,
        seenFrames: Collection<String>,
    ): ImportPlan {
        var undecodable = 0
        var skippedOld = 0
        var skippedSeen = 0
        var newest = watermark
        val seen = seenFrames.toHashSet()
        // Keyed by instant and origin, so a repeated reading overwrites rather than doubles.
        val planned = LinkedHashMap<Pair<Long, Boolean>, PlannedReading>()
        for (frame in frames) {
            val reading = profile.decode(frame)
            if (reading == null) {
                undecodable++
                continue
            }
            val deviceTime = reading.deviceTime
            if (DeviceTime.isPlausible(deviceTime)) {
                val naive = DeviceTime.naiveSeconds(deviceTime!!)
                if (watermark != null && naive <= watermark) {
                    skippedOld++
                    continue
                }
                if (newest == null || naive > newest) newest = naive
            } else if (frame.toHex() in seen) {
                skippedSeen++
                continue
            }
            val time = DeviceTime.resolve(deviceTime, clock, phoneZone, receivedAt)
            planned[time.instant.epochSecond to time.fromDevice] = PlannedReading(reading, time)
        }
        return ImportPlan(planned.values.toList(), newest, skippedOld, undecodable, skippedSeen)
    }

    /** The remembered frames after [plan] was written: the old ones plus the receive-time ones, newest last, capped. */
    fun seenAfter(seenFrames: List<String>, plan: ImportPlan): List<String> {
        val added = plan.toWrite.filter { !it.time.fromDevice }.map { it.reading.frame.toHex() }
        return (seenFrames + added).distinct().takeLast(SEEN_LIMIT)
    }
}
