package com.farewellutopia.healthdevices.ble

import java.util.UUID

/** One scan filter, free of Android classes so the choice is unit-testable. */
sealed interface FilterSpec {
    data class ByAddress(val address: String) : FilterSpec
    data class ByService(val uuid: UUID) : FilterSpec
}

/**
 * Which filters the background scan registers. One filter per paired device
 * address, plus one per profile service in use: Android ORs the filters, and
 * the service filter catches a bonded device whose advertisements carry a
 * private address the controller did not resolve (the receiver then drops
 * anything that is not a paired address). With no paired device there is
 * nothing to scan for: an empty plan means "do not register".
 */
object ScanPlan {

    fun build(addresses: Collection<String>, serviceUuids: Collection<UUID>): List<FilterSpec> {
        if (addresses.isEmpty()) return emptyList()
        val byAddress = addresses.map { it.uppercase() }.distinct().map { FilterSpec.ByAddress(it) }
        val byService = serviceUuids.distinct().map { FilterSpec.ByService(it) }
        return byAddress + byService
    }
}
