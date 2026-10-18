package org.openbyddisplay.vehicle

import android.content.Context

internal object HeadlightSettings {
    private fun prefs(c: Context) = c.getSharedPreferences("openbyd_headlight", Context.MODE_PRIVATE)
    fun enabled(c: Context) = prefs(c).getBoolean("enabled", false)
    fun enabled(c: Context, value: Boolean) { check(prefs(c).edit().putBoolean("enabled", value).commit()) }
    @Synchronized fun blocked(c: Context) = prefs(c).contains("pending")
    @Synchronized fun begin(c: Context, id: String) {
        check(!blocked(c)) { "原生自动模式恢复未确认" }
        check(prefs(c).edit().putString("pending", id).putLong("started", System.currentTimeMillis()).commit())
    }
    @Synchronized fun confirmed(c: Context, id: String) {
        if (prefs(c).getString("pending", null) == id)
            check(prefs(c).edit().remove("pending").remove("started").commit())
    }
    @Synchronized fun operatorRecovered(c: Context) {
        check(!HeadlightFollowService.running)
        check(System.currentTimeMillis() - prefs(c).getLong("started", Long.MAX_VALUE) >= 60_000) { "请先等待至少一分钟" }
        check(prefs(c).edit().remove("pending").remove("started").commit())
    }
}
