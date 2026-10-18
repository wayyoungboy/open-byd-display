package org.openbyddisplay.ambient

import android.content.Context

internal object AmbientStateStore {
    private fun prefs(context: Context) = context.getSharedPreferences("openbyd_ambient", Context.MODE_PRIVATE)
    @Synchronized fun blocked(context: Context) = prefs(context).contains("pending")
    @Synchronized fun begin(context: Context, id: String) {
        check(!blocked(context)) { "上次灯效恢复未确认，请先核对" }
        check(prefs(context).edit().putString("pending", id).putLong("started", System.currentTimeMillis()).commit())
    }
    @Synchronized fun confirmed(context: Context, id: String) {
        if (prefs(context).getString("pending", null) == id)
            check(prefs(context).edit().remove("pending").remove("started").commit())
    }
    @Synchronized fun operatorRecovered(context: Context) {
        check(!AmbientLightService.running)
        check(!AmbientLightService.audioCleanupUnknown) { "本机音频资源释放未确认，请结束并重启 App" }
        val age = System.currentTimeMillis() - prefs(context).getLong("started", Long.MAX_VALUE)
        check(age >= 60_000) { "请等待控制器租约和恢复期限结束（至少一分钟）" }
        check(prefs(context).edit().remove("pending").remove("started").commit())
    }
}
