package com.farewellutopia.healthdevices.data

import kotlinx.serialization.Serializable
import com.farewellutopia.healthdevices.time.DeviceClock

/**
 * A device the user paired in the app. Android keeps the bond; this is the
 * app's own knowledge: what it is, how its clock is set, and what was
 * imported so far.
 */
@Serializable
data class PairedDevice(
    /** The Bluetooth address, upper-case as Android reports it. */
    val address: String,
    val name: String? = null,
    val manufacturer: String? = null,
    val model: String? = null,
    /** [com.farewellutopia.healthdevices.profiles.DeviceProfile.id]. */
    val profileId: String,
    val hasCurrentTimeService: Boolean = false,
    val clock: DeviceClock = DeviceClock.LOCAL,
    /**
     * Newest device time stamp imported, as [com.farewellutopia.healthdevices.time.DeviceTime.naiveSeconds];
     * null means "import everything the device has".
     */
    val watermark: Long? = null,
    /** Frames (hex) of readings without a usable time stamp that were imported, for recognising a re-send. */
    val seenFrames: List<String> = emptyList(),
    val pairedAt: Long,
) {
    /** What the UI calls the device. */
    val label: String get() = name ?: listOfNotNull(manufacturer, model).joinToString(" ").ifEmpty { address }
}

/** How one reception ended. */
@Serializable
enum class ReceptionResult { OK, NO_DATA, CONNECT_FAILED, SERVICE_MISSING, SUBSCRIBE_FAILED, TIMEOUT, HEALTH_CONNECT_UNAVAILABLE, HEALTH_CONNECT_DENIED, HEALTH_CONNECT_ERROR, BLUETOOTH_OFF, NO_PERMISSION }

/** One entry of the reception log: the raw frames stay on the phone for support. */
@Serializable
data class Reception(
    /** Epoch milliseconds. */
    val at: Long,
    val address: String,
    val deviceLabel: String,
    val result: ReceptionResult,
    val newReadings: Int = 0,
    /** The newest reading's summary, already formatted. */
    val summary: String? = null,
    val frames: List<String> = emptyList(),
    /** A GATT status or an exception message. */
    val detail: String? = null,
)

/** Why the background scan is not registered. */
@Serializable
enum class ScanProblem { NO_PERMISSION, BLUETOOTH_OFF, NO_DEVICES, FAILED }

/** The last attempt to register the background scan. */
@Serializable
data class ScanState(
    val registered: Boolean = false,
    /** Epoch milliseconds of the attempt; 0 for never. */
    val at: Long = 0,
    val problem: ScanProblem? = null,
    /** The scanner's error code when [problem] is FAILED. */
    val code: Int = 0,
    /** True when the first-match callback type was refused and all matches are reported instead. */
    val allMatches: Boolean = false,
)
