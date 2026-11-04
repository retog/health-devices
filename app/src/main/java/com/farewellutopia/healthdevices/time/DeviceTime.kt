package com.farewellutopia.healthdevices.time

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** How a device's clock is set, chosen per device at pairing and changeable later. */
enum class DeviceClock(val key: String) {
    /** The device shows UTC. */
    UTC("utc"),

    /** The device shows the phone's local time (its zone, with summer time). */
    LOCAL("local");

    companion object {
        fun fromKey(key: String?): DeviceClock = entries.firstOrNull { it.key == key } ?: LOCAL
    }
}

/**
 * A reading's instant as Health Connect wants it: the instant, the zone offset
 * that was in force (so the record shows the wall-clock time the user saw),
 * and whether it came from the device's clock or is the phone's receive time.
 * Receive-time readings of one reception share the instant, so each carries
 * its [sequence] within the reception to keep their record ids apart.
 */
data class ResolvedTime(
    val instant: Instant,
    val offset: ZoneOffset,
    val fromDevice: Boolean,
    val sequence: Int = 0,
    /** The device's own stamp as [DeviceTime.naiveSeconds] when [fromDevice]; what the record id is made of. */
    val deviceStamp: Long? = null,
)

/**
 * Turns the naive wall clock a device sends into an instant. The Blood
 * Pressure profile (like the other health profiles) carries a date-time with
 * no zone, so the user says how the device's clock is set ([DeviceClock]);
 * the app interprets the stamp in that zone. A stamp that is absent or
 * implausible ([isPlausible]) falls back to the receive time, marked as such.
 */
object DeviceTime {

    /** A device clock that was never set shows a year like 2000 or 2010; nothing before this is believed. */
    const val EARLIEST_PLAUSIBLE_YEAR = 2020

    /**
     * A stamp more than this far ahead of the receive time (in the device's
     * zone) is a mis-set clock: believed, it would become the watermark and
     * hide every real reading after it.
     */
    const val MAX_FUTURE_DAYS = 1L

    /** The latest stamp believed for a reading received at [receivedAt]. */
    fun latestPlausible(clock: DeviceClock, phoneZone: ZoneId, receivedAt: Instant): LocalDateTime =
        LocalDateTime.ofInstant(receivedAt, zoneFor(clock, phoneZone)).plusDays(MAX_FUTURE_DAYS)

    fun isPlausible(deviceTime: LocalDateTime?, latest: LocalDateTime): Boolean =
        deviceTime != null && deviceTime.year >= EARLIEST_PLAUSIBLE_YEAR && !deviceTime.isAfter(latest)

    /** The zone a [DeviceClock] setting means, given the phone's zone. */
    fun zoneFor(clock: DeviceClock, phoneZone: ZoneId): ZoneId = when (clock) {
        DeviceClock.UTC -> ZoneOffset.UTC
        DeviceClock.LOCAL -> phoneZone
    }

    fun resolve(deviceTime: LocalDateTime?, clock: DeviceClock, phoneZone: ZoneId, receivedAt: Instant, sequence: Int = 0): ResolvedTime {
        val zone = zoneFor(clock, phoneZone)
        if (!isPlausible(deviceTime, latestPlausible(clock, phoneZone, receivedAt))) {
            return ResolvedTime(receivedAt, phoneZone.rules.getOffset(receivedAt), fromDevice = false, sequence = sequence)
        }
        // In a summer-time overlap the earlier offset is taken; in a gap the time
        // is shifted forward by the gap, as ZonedDateTime.ofLocal does.
        val zoned = ZonedDateTime.ofLocal(deviceTime!!, zone, null)
        return ResolvedTime(zoned.toInstant(), zoned.offset, fromDevice = true, deviceStamp = naiveSeconds(deviceTime))
    }

    /**
     * The watermark form of a naive wall clock: its seconds as if it were UTC.
     * Comparing naive stamps this way does not depend on the clock setting, so
     * changing UTC/local later leaves "which readings are new" intact.
     */
    fun naiveSeconds(deviceTime: LocalDateTime): Long = deviceTime.toEpochSecond(ZoneOffset.UTC)
}
