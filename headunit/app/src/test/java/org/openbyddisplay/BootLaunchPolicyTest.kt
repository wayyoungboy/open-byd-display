package org.openbyddisplay

import org.junit.Assert.assertEquals
import org.junit.Test

class BootLaunchPolicyTest {
    private val boot = "android.intent.action.BOOT_COMPLETED"

    @Test fun defaultOffIgnoresBootOnAllPlatforms() {
        for (sdk in listOf(28, 29, 35)) assertEquals(BootLaunchPolicy.Action.IGNORE,
            BootLaunchPolicy.decide(boot, false, sdk, true))
    }
    @Test fun unrelatedOrMissingBroadcastCannotLaunch() {
        for (action in listOf(null, "android.intent.action.MY_PACKAGE_REPLACED", "custom.BOOT"))
            assertEquals(BootLaunchPolicy.Action.IGNORE, BootLaunchPolicy.decide(action, true, 28, true))
    }
    @Test fun androidNineCanOpenAnOptedInHome() {
        assertEquals(BootLaunchPolicy.Action.OPEN_HOME, BootLaunchPolicy.decide(boot, true, 28, false))
    }
    @Test fun backgroundRestrictionUsesNotificationWithoutOverlayGrant() {
        for (sdk in listOf(29, 35)) assertEquals(BootLaunchPolicy.Action.NOTIFY,
            BootLaunchPolicy.decide(boot, true, sdk, false))
    }
    @Test fun explicitlyGrantedBackgroundLaunchCanOpenHome() {
        assertEquals(BootLaunchPolicy.Action.OPEN_HOME, BootLaunchPolicy.decide(boot, true, 35, true))
    }
}
