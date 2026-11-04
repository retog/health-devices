package com.farewellutopia.healthdevices

import com.farewellutopia.healthdevices.ble.FilterSpec
import com.farewellutopia.healthdevices.ble.ScanPlan
import com.farewellutopia.healthdevices.ble.Uuids
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScanPlanTest {

    @Test
    fun noDevicesMeansNoScan() {
        assertTrue(ScanPlan.build(emptyList(), listOf(Uuids.BLOOD_PRESSURE)).isEmpty())
    }

    @Test
    fun addressesFirstThenServices() {
        val plan = ScanPlan.build(listOf("aa:bb:cc:dd:ee:ff", "11:22:33:44:55:66"), listOf(Uuids.BLOOD_PRESSURE))
        assertEquals(
            listOf(
                FilterSpec.ByAddress("AA:BB:CC:DD:EE:FF"),
                FilterSpec.ByAddress("11:22:33:44:55:66"),
                FilterSpec.ByService(Uuids.BLOOD_PRESSURE),
            ),
            plan,
        )
    }

    @Test
    fun duplicatesCollapse() {
        val plan = ScanPlan.build(listOf("AA:BB:CC:DD:EE:FF", "aa:bb:cc:dd:ee:ff"), listOf(Uuids.BLOOD_PRESSURE, Uuids.BLOOD_PRESSURE))
        assertEquals(2, plan.size)
    }

    @Test
    fun sigUuids() {
        assertEquals("00001810-0000-1000-8000-00805f9b34fb", Uuids.BLOOD_PRESSURE.toString())
        assertEquals("00002a35-0000-1000-8000-00805f9b34fb", Uuids.BLOOD_PRESSURE_MEASUREMENT.toString())
        assertEquals("00002902-0000-1000-8000-00805f9b34fb", Uuids.CCCD.toString())
    }
}
