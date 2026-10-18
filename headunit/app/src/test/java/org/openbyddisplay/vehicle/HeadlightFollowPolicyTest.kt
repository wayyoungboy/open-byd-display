package org.openbyddisplay.vehicle

import org.junit.Assert.*
import org.junit.Test

class HeadlightFollowPolicyTest {
    @Test fun followsActualLowBeamOnlyWhileNativeAutoIsSelected() {
        assertEquals(2, HeadlightFollowPolicy.targetNightMode(0, 1))
        assertEquals(1, HeadlightFollowPolicy.targetNightMode(0, 0))
        for (choice in listOf(1, 2, -1, 65535))
            for (beam in listOf(0, 1)) assertNull(HeadlightFollowPolicy.targetNightMode(choice, beam))
    }
    @Test fun faultAndMissingSignalsDoNotChooseATheme() {
        for (beam in listOf(-10011, -1, 2, 65535)) assertNull(HeadlightFollowPolicy.targetNightMode(0, beam))
    }
    @Test fun androidManualChoicesAreAlsoRespected() {
        for (mode in listOf(-1, 1, 2)) {
            assertNull(HeadlightFollowPolicy.targetNightMode(0, 1, mode))
            assertFalse(HeadlightFollowPolicy.mayRestoreAuto(0, 2, 2, mode))
        }
    }
    @Test fun restoreUsesNaturalTwilightRatherThanTheOverriddenComputedTheme() {
        assertEquals(2, HeadlightFollowPolicy.twilightMode("mComputedNightMode=false\n  mTwilightService.getLastTwilightState()=TwilightState { isNight=true }"))
        assertEquals(1, HeadlightFollowPolicy.twilightMode("mComputedNightMode=true\n  mTwilightService.getLastTwilightState()=TwilightState { isNight=false }"))
        assertNull(HeadlightFollowPolicy.twilightMode("mComputedNightMode=true"))
        assertNull(HeadlightFollowPolicy.twilightMode("mTwilightService.getLastTwilightState()=null"))
    }
    @Test fun stoppingOnlyReturnsAnOwnedAutomaticSelection() {
        assertTrue(HeadlightFollowPolicy.mayRestoreAuto(0, 2, 2))
        assertTrue(HeadlightFollowPolicy.mayRestoreAuto(0, 1, 1))
        assertFalse(HeadlightFollowPolicy.mayRestoreAuto(1, 1, 2))
        assertFalse(HeadlightFollowPolicy.mayRestoreAuto(2, 2, 2))
        assertFalse(HeadlightFollowPolicy.mayRestoreAuto(0, 1, 2))
        assertFalse(HeadlightFollowPolicy.mayRestoreAuto(0, 2, null))
        assertFalse(HeadlightFollowPolicy.mayRestoreAuto(-1, 2, 2))
    }
    @Test fun bootRestoreRequiresOptInAndNeverRestartsAnUnconfirmedHeadlightSession() {
        val boot = "android.intent.action.BOOT_COMPLETED"
        assertEquals(VehicleRestorePolicy.Plan(false, false), VehicleRestorePolicy.plan(boot, false, false, false))
        assertEquals(VehicleRestorePolicy.Plan(true, false), VehicleRestorePolicy.plan(boot, true, true, true))
        assertEquals(VehicleRestorePolicy.Plan(false, true), VehicleRestorePolicy.plan(boot, false, true, false))
        assertEquals(VehicleRestorePolicy.Plan(false, false), VehicleRestorePolicy.plan(null, true, true, false))
        assertEquals(VehicleRestorePolicy.Plan(false, false), VehicleRestorePolicy.plan("other", true, true, false))
    }
}
