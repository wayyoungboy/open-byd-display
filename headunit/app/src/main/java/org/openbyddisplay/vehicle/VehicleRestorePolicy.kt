package org.openbyddisplay.vehicle

internal object VehicleRestorePolicy {
    data class Plan(val unlock: Boolean, val headlight: Boolean)
    fun plan(action: String?, unlock: Boolean, headlight: Boolean, recoveryBlocked: Boolean): Plan =
        if (action in setOf("android.intent.action.BOOT_COMPLETED", "android.intent.action.USER_PRESENT"))
            Plan(unlock, headlight && !recoveryBlocked) else Plan(false, false)
}
