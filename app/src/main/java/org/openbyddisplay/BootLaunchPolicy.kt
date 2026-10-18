package org.openbyddisplay

/** Boot restores only the application window, never an instrument operation. */
object BootLaunchPolicy {
    enum class Action { IGNORE, OPEN_HOME, NOTIFY }

    fun decide(action: String?, enabled: Boolean, sdk: Int, overlayAllowed: Boolean): Action = when {
        action != "android.intent.action.BOOT_COMPLETED" || !enabled -> Action.IGNORE
        sdk >= 29 && !overlayAllowed -> Action.NOTIFY
        else -> Action.OPEN_HOME
    }
}
