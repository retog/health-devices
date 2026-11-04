package com.farewellutopia.healthdevices

import com.farewellutopia.healthdevices.ble.FilterSpec
import com.farewellutopia.healthdevices.ble.ScanPlan
import com.farewellutopia.healthdevices.ble.Uuids
import com.farewellutopia.healthdevices.data.ScanProblem
import com.farewellutopia.healthdevices.data.ScanState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

class ScanRegistrationRulesTest {

    @Test
    fun fallsBackOnAnyCodeButTwo() {
        assertFalse(ScanPlan.fallsBackToAllMatches(0))
        assertFalse(ScanPlan.fallsBackToAllMatches(ScanPlan.SCAN_FAILED_ALREADY_STARTED))
        assertFalse(ScanPlan.fallsBackToAllMatches(ScanPlan.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED))
        assertTrue(ScanPlan.fallsBackToAllMatches(3)) // internal error
        assertTrue(ScanPlan.fallsBackToAllMatches(4)) // feature unsupported
        assertTrue(ScanPlan.fallsBackToAllMatches(5)) // out of hardware resources
        assertTrue(ScanPlan.fallsBackToAllMatches(6)) // scanning too frequently
    }

    @Test
    fun registrationIsDueOnlyWhenSomethingChanged() {
        val registered = ScanState(registered = true, at = 1_000, addresses = listOf("AA:BB:CC:DD:EE:FF"))
        val same = listOf("aa:bb:cc:dd:ee:ff")
        assertFalse(ScanPlan.needsRegistration(registered, same, firstInProcess = false))
        assertTrue(ScanPlan.needsRegistration(registered, same, firstInProcess = true))
        assertTrue(ScanPlan.needsRegistration(registered, same + "11:22:33:44:55:66", firstInProcess = false))
        assertTrue(ScanPlan.needsRegistration(registered.copy(registered = false, problem = ScanProblem.BLUETOOTH_OFF), same, firstInProcess = false))
        assertTrue(ScanPlan.needsRegistration(ScanState(), same, firstInProcess = false))
    }

    @Test
    fun manualReregisterIsRateLimited() {
        val registered = ScanState(registered = true, at = 100_000)
        assertFalse(ScanPlan.manualAllowed(registered, 100_000 + ScanPlan.MANUAL_INTERVAL_MS - 1))
        assertTrue(ScanPlan.manualAllowed(registered, 100_000 + ScanPlan.MANUAL_INTERVAL_MS))
        // A failed attempt counts too: the budget is per start, successful or not.
        assertFalse(ScanPlan.manualAllowed(registered.copy(registered = false), 100_001))
        assertTrue(ScanPlan.manualAllowed(registered.copy(registered = false), 100_000 + ScanPlan.MANUAL_INTERVAL_MS))
        // Never attempted: allowed.
        assertTrue(ScanPlan.manualAllowed(ScanState(), 100_001))
    }
}
