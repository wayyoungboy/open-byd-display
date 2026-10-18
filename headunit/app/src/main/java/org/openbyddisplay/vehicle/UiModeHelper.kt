package org.openbyddisplay.vehicle

import org.openbyddisplay.ambient.AmbientAdb as LocalAdbShell

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.util.Log

/** Unlock the OEM settings page; selection and automatic mode remain owned by the OEM. */
object UiModeHelper {
    private const val TAG = "OpenBYDUiMode"
    private const val PREF_KEEP_UNLOCKED = "nativeUiModeKeepUnlocked"
    val URI_GLOBAL: Uri = Uri.parse("content://carsettings/global")
    const val KEY_SUPPORT = "support_change_system_uimode"
    const val KEY_SCREEN_MODE = "sys_screen_mode"

    data class ApplyResult(val ok: Boolean, val via: String, val message: String,
                           val support: Int = -1, val screenMode: Int = -1)

    private fun prefs(context: Context) = context.getSharedPreferences("openbyd_vehicle", Context.MODE_PRIVATE)

    fun keepUnlocked(context: Context): Boolean = prefs(context).getBoolean(PREF_KEEP_UNLOCKED, false)
    fun setKeepUnlocked(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(PREF_KEEP_UNLOCKED, enabled).apply()
    }

    fun getString(context: Context, key: String): String? = try {
        context.contentResolver.query(URI_GLOBAL, arrayOf("value"), "key = ?", arrayOf(key), null)?.use { cursor ->
            if (cursor.count != 1 || !cursor.moveToFirst()) null
            else cursor.getColumnIndex("value").takeIf { it >= 0 }?.let { cursor.getString(it) }
        }
    } catch (e: Exception) {
        Log.w(TAG, "read $key: ${e.message}")
        null
    }

    fun getInt(context: Context, key: String, def: Int = -1): Int = getString(context, key)?.trim()?.toIntOrNull() ?: def

    private fun unlockDirect(context: Context): Boolean = try {
        val resolver = context.contentResolver
        val values = ContentValues().apply { put("value", 1) }
        val updated = resolver.update(URI_GLOBAL, values, "key = ?", arrayOf(KEY_SUPPORT))
        if (updated == 0) resolver.insert(URI_GLOBAL, ContentValues(values).apply { put("key", KEY_SUPPORT) })
        getInt(context, KEY_SUPPORT) == 1
    } catch (e: Exception) {
        Log.w(TAG, "unlock: ${e.message}")
        false
    }

    fun unlockNativeSettings(context: Context): ApplyResult {
        val via = if (unlockDirect(context)) "ContentResolver" else {
            val result = LocalAdbShell.exec(context, UiModeWriteEvidence.buildWriteCommand(KEY_SUPPORT, 1))
            if (UiModeWriteEvidence.adbConfirmed(result.ok, result.output,
                    getString(context, KEY_SUPPORT)?.trim()?.toIntOrNull(), 1)) "ADB" else {
                return ApplyResult(false, "fail", "原生设置解锁未确认。请先在 USB 页授权本机 ADB，再重试。${result.detail}")
            }
        }
        // The OEM provider derives notification URIs from selectionArgs. The `content`
        // CLI has no selectionArgs, so explicitly notify the key URI after ADB fallback.
        try { context.contentResolver.notifyChange(Uri.withAppendedPath(URI_GLOBAL, KEY_SUPPORT), null) }
        catch (e: Exception) { Log.w(TAG, "unlock notify: ${e.message}") }
        return ApplyResult(true, via,
            "原生深浅模式已解锁。请关闭后重新进入车机原生显示设置，选择自动、浅色或深色。OpenBYDDisplay不再接管或反复改写模式。",
            1, getInt(context, KEY_SCREEN_MODE))
    }

    fun ensureUnlockedWithin(context: Context, timeoutMs: Long = 8000L): Boolean {
        val deadline = System.nanoTime() + timeoutMs.coerceAtLeast(0) * 1_000_000L
        do {
            if (!keepUnlocked(context)) return false
            if (getInt(context, KEY_SUPPORT) == 1 || unlockDirect(context)) return true
            Thread.sleep(500)
        } while (System.nanoTime() < deadline)
        return keepUnlocked(context) && unlockNativeSettings(context).ok
    }

    fun statusLine(context: Context): String {
        val support = getInt(context, KEY_SUPPORT)
        val mode = when (getInt(context, KEY_SCREEN_MODE)) {
            0 -> "自动"; 1 -> "浅色"; 2 -> "深色"; else -> "无法读取"
        }
        return "原生深浅模式：${if (support == 1) "已解锁" else "未确认解锁"} · 当前=$mode\n" +
            "原生选项由车机设置页控制；跟随大灯仅在 Auto 时生效。"
    }
}
