package com.farewellutopia.healthdevices.profiles

import java.util.UUID
import com.farewellutopia.healthdevices.profiles.bloodpressure.BloodPressureProfile

/** The profiles the app implements. Blood pressure is the first; the others are listed in the README. */
object Profiles {

    val ALL: List<DeviceProfile> = listOf(BloodPressureProfile)

    fun byId(id: String): DeviceProfile? = ALL.firstOrNull { it.id == id }

    /** The profile whose service a device advertises, or null when the app speaks none of them. */
    fun byService(services: Collection<UUID>): DeviceProfile? = ALL.firstOrNull { it.serviceUuid in services }

    /** The service UUIDs to scan for (every profile's). */
    val SERVICE_UUIDS: List<UUID> get() = ALL.map { it.serviceUuid }
}
