package org.openbyddisplay.vehicle

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import java.util.concurrent.TimeUnit

/** Uses the system's temporary Auto override, retaining the native and Android Auto choices. */
internal class ShellNightModeController(context: Context) {
    private val ui = context.getSystemService(UiModeManager::class.java)
    private val manager = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)
    private val configuration = Class.forName("android.app.IActivityManager").getMethod("getConfiguration")
    private val activate = UiModeManager::class.java.getMethod("setNightModeActivated", Boolean::class.javaPrimitiveType)

    val preference: Int get() = ui.nightMode
    fun actual(): Int = when ((configuration.invoke(manager) as Configuration).uiMode and Configuration.UI_MODE_NIGHT_MASK) {
        Configuration.UI_MODE_NIGHT_NO -> 1
        Configuration.UI_MODE_NIGHT_YES -> 2
        else -> -1
    }

    fun activate(mode: Int): Boolean {
        require(mode in 1..2)
        check(preference == UiModeManager.MODE_NIGHT_AUTO) { "系统手动模式优先" }
        return activate.invoke(ui, mode == 2) == true
    }

    fun twilightMode(): Int {
        // Read the current natural Auto decision, not the overridden computed theme.
        val process = ProcessBuilder("dumpsys", "uimode").redirectErrorStream(true).start()
        try {
            check(process.waitFor(3, TimeUnit.SECONDS)) { "原生自动状态读取超时" }
            val dump = process.inputStream.bufferedReader().readText()
            check(process.exitValue() == 0) { "原生自动状态读取失败" }
            return HeadlightFollowPolicy.twilightMode(dump) ?: error("原生自动状态暂不可用")
        } finally { process.destroy() }
    }
}
