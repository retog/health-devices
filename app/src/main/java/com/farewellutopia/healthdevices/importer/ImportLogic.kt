package com.farewellutopia.healthdevices.importer

import com.farewellutopia.healthdevices.ble.toHex
import com.farewellutopia.healthdevices.profiles.DeviceProfile
import com.farewellutopia.healthdevices.profiles.Reading
import com.farewellutopia.healthdevices.time.DeviceClock
import com.farewellutopia.healthdevices.time.DeviceTime
import com.farewellutopia.healthdevices.time.ResolvedTime
import java.time.Instant
import java.time.ZoneId

/** One reading that is going to Health Connect, with its instant settled. */
data class PlannedReading(val reading: Reading, val time: ResolvedTime) {
    val frameHex: String get() = reading.frame.toHex()
}

/**
 * What one batch of frames turns into: the readings to write and the counts
 * for the notification. Pure, so the whole dedupe policy is unit-tested;
 * [Importer] does the writing and moves the watermark by what it managed.
 */
data class ImportPlan(
    val toWrite: List<PlannedReading>,
    /** Frames that decoded to a reading older than (or already at) the watermark. */
    val skippedOld: Int,
    /** Frames the profile could not decode. */
    val undecodable: Int,
    /** Unstamped frames already imported as often as they occur here. */
    val skippedSeen: Int,
) {
    /** True when at least one reading carries the receive time instead of the device's. */
    val anyReceiveTime: Boolean get() = toWrite.any { !it.time.fromDevice }
}

object ImportLogic {

    /** How many distinct unstamped frames are remembered per device. */
    const val UNSTAMPED_LIMIT = 200

    /**
     * Decides what to write from [frames] received at [receivedAt]:
     *
     * - a frame the profile cannot decode is counted and dropped;
     * - a reading with a plausible device time stamp at or before [watermark]
     *   was imported before and is skipped (Health Connect would upsert it
     *   anyway, but writing it again on every reception is noise);
     * - two frames with the same device stamp in one batch collapse to the
     *   last one (one instant is one reading);
     * - a reading without a usable time stamp (absent, before 2020, or more
     *   than a day ahead) gets the receive time and a sequence number. Of
     *   each such frame, only the occurrences beyond [unstampedCounts] (how
     *   often it was imported before) are new: the device re-sends its
     *   memory, and content is all there is to recognise these by.
     */
    fun plan(
        frames: List<ByteArray>,
        profile: DeviceProfile,
        clock: DeviceClock,
        phoneZone: ZoneId,
        receivedAt: Instant,
        watermark: Long?,
        unstampedCounts: Map<String, Int> = emptyMap(),
    ): ImportPlan {
        var undecodable = 0
        var skippedOld = 0
        var skippedSeen = 0
        var sequence = 0
        val latest = DeviceTime.latestPlausible(clock, phoneZone, receivedAt)
        val stamped = LinkedHashMap<Long, PlannedReading>()
        val unstamped = mutableListOf<PlannedReading>()
        val seenInBatch = HashMap<String, Int>()
        for (frame in frames) {
            val reading = profile.decode(frame)
            if (reading == null) {
                undecodable++
                continue
            }
            val deviceTime = reading.deviceTime
            if (DeviceTime.isPlausible(deviceTime, latest)) {
                val naive = DeviceTime.naiveSeconds(deviceTime!!)
                if (watermark != null && naive <= watermark) {
                    skippedOld++
                    continue
                }
                stamped[naive] = PlannedReading(reading, DeviceTime.resolve(deviceTime, clock, phoneZone, receivedAt))
            } else {
                val hex = frame.toHex()
                val occurrence = (seenInBatch[hex] ?: 0) + 1
                seenInBatch[hex] = occurrence
                if (occurrence <= (unstampedCounts[hex] ?: 0)) {
                    skippedSeen++
                    continue
                }
                unstamped += PlannedReading(reading, DeviceTime.resolve(deviceTime, clock, phoneZone, receivedAt, sequence++))
            }
        }
        return ImportPlan(stamped.values.toList() + unstamped, skippedOld, undecodable, skippedSeen)
    }

    /**
     * The watermark after a reception: the newest device stamp among the
     * [done] readings (written, or recorded as skipped), but never past a
     * reading in [failed] — those are tried again at the next reception,
     * which only happens while they are above the watermark. [before] is
     * never lowered.
     */
    fun watermarkAfter(before: Long?, done: Collection<PlannedReading>, failed: Collection<PlannedReading> = emptyList()): Long? {
        val oldestFailed = failed.mapNotNull { it.time.deviceStamp }.minOrNull()
        val newestDone = done.mapNotNull { it.time.deviceStamp }
            .filter { oldestFailed == null || it < oldestFailed }
            .maxOrNull()
        return when {
            newestDone == null -> before
            before == null -> newestDone
            else -> maxOf(before, newestDone)
        }
    }

    /**
     * The unstamped-frame counts after [done] receive-time readings were
     * written (or recorded as skipped): each done frame counts once more.
     * Capped at [UNSTAMPED_LIMIT] distinct frames, oldest entries first out.
     */
    fun unstampedCountsAfter(before: Map<String, Int>, done: Collection<PlannedReading>): Map<String, Int> {
        val counts = LinkedHashMap(before)
        done.filter { !it.time.fromDevice }.forEach { planned ->
            val hex = planned.frameHex
            // Re-inserting moves a frame to the newest position.
            val next = (counts.remove(hex) ?: 0) + 1
            counts[hex] = next
        }
        while (counts.size > UNSTAMPED_LIMIT) counts.remove(counts.keys.first())
        return counts
    }

    /**
     * The newest plausible device stamp in [frames]: what "only new readings
     * from now on" means at pairing. The device just sent everything it
     * holds, so anything it sends later with a newer stamp is new — whatever
     * the phone's clock, zone or the device's drift say. Null when no frame
     * carries a usable stamp (then everything counts as new).
     */
    fun newestStamp(frames: List<ByteArray>, profile: DeviceProfile, clock: DeviceClock, phoneZone: ZoneId, receivedAt: Instant): Long? {
        val latest = DeviceTime.latestPlausible(clock, phoneZone, receivedAt)
        return frames.mapNotNull { profile.decode(it)?.deviceTime }
            .filter { DeviceTime.isPlausible(it, latest) }
            .maxOfOrNull { DeviceTime.naiveSeconds(it) }
    }
}
