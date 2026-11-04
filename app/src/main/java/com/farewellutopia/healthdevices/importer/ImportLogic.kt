package com.farewellutopia.healthdevices.importer

import com.farewellutopia.healthdevices.profiles.DeviceProfile
import com.farewellutopia.healthdevices.profiles.Reading
import com.farewellutopia.healthdevices.time.DeviceClock
import com.farewellutopia.healthdevices.time.DeviceTime
import com.farewellutopia.healthdevices.time.ResolvedTime
import java.time.Instant
import java.time.ZoneId

/** One reading that is going to Health Connect, with its instant settled. */
data class PlannedReading(val reading: Reading, val time: ResolvedTime)

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
) {
    /** True when at least one reading carries the receive time instead of the device's. */
    val anyReceiveTime: Boolean get() = toWrite.any { !it.time.fromDevice }
}

object ImportLogic {

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
     *   than a day ahead) gets the receive time and a sequence number; such
     *   readings are all kept, since nothing tells two of them apart.
     */
    fun plan(
        frames: List<ByteArray>,
        profile: DeviceProfile,
        clock: DeviceClock,
        phoneZone: ZoneId,
        receivedAt: Instant,
        watermark: Long?,
    ): ImportPlan {
        var undecodable = 0
        var skippedOld = 0
        var sequence = 0
        val latest = DeviceTime.latestPlausible(clock, phoneZone, receivedAt)
        val stamped = LinkedHashMap<Long, PlannedReading>()
        val unstamped = mutableListOf<PlannedReading>()
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
                unstamped += PlannedReading(reading, DeviceTime.resolve(deviceTime, clock, phoneZone, receivedAt, sequence++))
            }
        }
        return ImportPlan(stamped.values.toList() + unstamped, skippedOld, undecodable)
    }

    /**
     * The watermark after [done] readings were written (or recorded as
     * skipped): the newest device stamp among them, or [before] when none
     * of them carried one. A reading that failed to write is not in [done],
     * so the next reception tries it again.
     */
    fun watermarkAfter(before: Long?, done: Collection<PlannedReading>): Long? {
        val newest = done.filter { it.time.fromDevice }.mapNotNull { it.reading.deviceTime }.maxOfOrNull { DeviceTime.naiveSeconds(it) }
        return when {
            newest == null -> before
            before == null -> newest
            else -> maxOf(before, newest)
        }
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
