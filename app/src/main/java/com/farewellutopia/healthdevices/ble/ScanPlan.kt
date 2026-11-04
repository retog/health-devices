package com.farewellutopia.healthdevices.ble

import com.farewellutopia.healthdevices.data.ScanState
import java.util.UUID

/** One scan filter, free of Android classes so the choice is unit-testable. */
sealed interface FilterSpec {
    data class ByAddress(val address: String) : FilterSpec
    data class ByService(val uuid: UUID) : FilterSpec
}

/**
 * Which filters the background scan registers, and when registering is due.
 * One filter per paired device address, plus one per profile service in use:
 * Android ORs the filters, and the service filter catches a bonded device
 * whose advertisements carry a private address the controller did not
 * resolve (the receiver then drops anything that is not a paired address).
 * With no paired device there is nothing to scan for: an empty plan means
 * "do not register".
 */
object ScanPlan {

    /** ScanCallback.SCAN_FAILED_ALREADY_STARTED: a scan under this PendingIntent is still running. */
    const val SCAN_FAILED_ALREADY_STARTED = 1

    /** ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED: the stack would not register the client at all. */
    const val SCAN_FAILED_APPLICATION_REGISTRATION_FAILED = 2

    /** The manual "re-register" button is honoured at most this often: Android downgrades an app that starts scans faster than five per 30 s. */
    const val MANUAL_INTERVAL_MS = 30_000L

    fun build(addresses: Collection<String>, serviceUuids: Collection<UUID>): List<FilterSpec> {
        if (addresses.isEmpty()) return emptyList()
        val byAddress = addresses.map { it.uppercase() }.distinct().map { FilterSpec.ByAddress(it) }
        val byService = serviceUuids.distinct().map { FilterSpec.ByService(it) }
        return byAddress + byService
    }

    /**
     * Whether a refused first-match scan is worth trying as an all-matches
     * scan. First-match needs the controller's on-found tracking, which is
     * missing on some chips (FEATURE_UNSUPPORTED, 4) and exhausted on others
     * once Fast Pair, Find My Device and the like hold the tracking slots
     * (OUT_OF_HARDWARE_RESOURCES, 5). Only "already started" and "could not
     * register the client" say the second attempt would fail the same way.
     */
    fun fallsBackToAllMatches(code: Int): Boolean =
        code != 0 && code != SCAN_FAILED_ALREADY_STARTED && code != SCAN_FAILED_APPLICATION_REGISTRATION_FAILED

    /**
     * Whether the scan must be (re)registered: at the first chance in a
     * process (a force-stop cancels the system's registration without a
     * trace, and only a new process follows one), when the last attempt did
     * not succeed, or when the paired addresses changed. Otherwise the
     * registration from last time is still in force, and starting another
     * scan would only count against the five-per-30-s budget.
     */
    fun needsRegistration(state: ScanState, addresses: Collection<String>, firstInProcess: Boolean): Boolean =
        firstInProcess || !state.registered || state.addresses.map { it.uppercase() }.toSet() != addresses.map { it.uppercase() }.toSet()

    /** Whether a manual re-register is honoured now (always when nothing is registered). */
    fun manualAllowed(state: ScanState, now: Long): Boolean = !state.registered || now - state.at >= MANUAL_INTERVAL_MS
}
