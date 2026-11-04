package com.farewellutopia.healthdevices

import com.farewellutopia.healthdevices.data.ReceptionResult
import kotlin.test.Test
import kotlin.test.assertEquals

class ReceptionResultTest {

    @Test
    fun cooldownIsArmedOnlyWhenTheDeviceWasRead() {
        val armed = ReceptionResult.entries.filter { it.armsCooldown }
        assertEquals(setOf(ReceptionResult.OK, ReceptionResult.NO_DATA, ReceptionResult.HEALTH_CONNECT_REJECTED), armed.toSet())
    }
}
