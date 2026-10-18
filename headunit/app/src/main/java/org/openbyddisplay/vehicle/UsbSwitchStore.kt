package org.openbyddisplay.vehicle

import android.content.Context

/** An uncertain detached command survives process death; refresh never resends it. */
internal object UsbSwitchStore {
    private fun prefs(context: Context) = context.getSharedPreferences("openbyd_usb", Context.MODE_PRIVATE)
    @Synchronized fun blocked(context: Context) = prefs(context).contains("request")
    @Synchronized fun read(context: Context): Pair<String, String>? {
        if (!blocked(context)) return null
        val request = prefs(context).getString("request", null) ?: error("USB 记录损坏")
        UsbModeEvidence.validateRequest(request)
        val cmd = prefs(context).getString("command", null).orEmpty()
        check(cmd in setOf(BydInstrumentCmd.SCMD_USBMODE_CAST, BydInstrumentCmd.SCMD_USBMODE_UDISK))
        return request to cmd
    }
    @Synchronized fun begin(context: Context, request: String, cmd: String) {
        check(!blocked(context))
        UsbModeEvidence.validateRequest(request)
        check(prefs(context).edit().putString("request", request).putString("command", cmd)
            .putLong("started", System.currentTimeMillis()).commit())
    }
    @Synchronized fun recoveryAllowed(context: Context) = System.currentTimeMillis() -
        prefs(context).getLong("started", Long.MAX_VALUE) >= 90_000
    @Synchronized fun confirmed(context: Context, request: String) {
        if (prefs(context).getString("request", null) == request)
            check(prefs(context).edit().clear().commit())
    }
}
