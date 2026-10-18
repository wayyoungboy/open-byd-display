package org.openbyddisplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        StartupController.handleBoot(context, intent?.action)
    }
}
