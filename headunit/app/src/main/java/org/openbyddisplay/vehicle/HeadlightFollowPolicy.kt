package org.openbyddisplay.vehicle

internal object HeadlightFollowPolicy {
    // Native preference: 0 auto, 1 light, 2 dark. Actual low beam: 0 off, 1 on.
    fun targetNightMode(nativeChoice: Int, lowBeam: Int, androidPreference: Int = 0): Int? = when {
        nativeChoice != 0 || androidPreference != 0 -> null
        lowBeam == 0 -> 1
        lowBeam == 1 -> 2
        else -> null
    }

    fun mayRestoreAuto(nativeChoice: Int, currentNightMode: Int, lastOwnedMode: Int?, androidPreference: Int = 0): Boolean =
        nativeChoice == 0 && androidPreference == 0 && lastOwnedMode != null && currentNightMode == lastOwnedMode

    fun twilightMode(dump: String): Int? = Regex("mTwilightService[^\\n]*TwilightState[^\\n]*isNight=(true|false)\\b")
        .find(dump)?.groupValues?.get(1)?.let { if (it == "true") 2 else 1 }
}
