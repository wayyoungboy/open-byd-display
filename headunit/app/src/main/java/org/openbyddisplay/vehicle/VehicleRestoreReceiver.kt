package org.openbyddisplay.vehicle

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Only explicitly saved vehicle preferences are restored; no casting, USB switch or installation. */
class VehicleRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_USER_PRESENT)) return
        runCatching {
            val plan = VehicleRestorePolicy.plan(intent?.action, UiModeHelper.keepUnlocked(context),
                HeadlightSettings.enabled(context), HeadlightSettings.blocked(context))
            if (plan.unlock || plan.headlight) androidx.core.content.ContextCompat.startForegroundService(context,
                Intent(context, VehicleRestoreService::class.java).setAction(intent?.action))
        }.onFailure { VehicleRestoreService.report("系统未允许开机恢复：${it.message}") }
    }
}
