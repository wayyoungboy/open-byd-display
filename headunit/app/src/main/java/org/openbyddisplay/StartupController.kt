package org.openbyddisplay

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

object StartupController {
    private const val CHANNEL = "startup"
    private const val NOTIFICATION = 606
    private const val FROM_BOOT = "from_boot"
    private fun preferences(context: Context) = context.getSharedPreferences("startup", Context.MODE_PRIVATE)

    fun enabled(context: Context): Boolean = preferences(context).getBoolean("enabled", false)

    fun setEnabled(context: Context, enabled: Boolean) {
        check(preferences(context).edit().putBoolean("enabled", enabled).commit()) { "自启设置未能保存" }
        if (enabled) createChannel(context)
        else context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION)
    }

    fun status(context: Context): String = when {
        !enabled(context) -> "开机自启已关闭"
        Build.VERSION.SDK_INT >= 29 && !Settings.canDrawOverlays(context) -> "已开启，待配置后台打开权限；可使用下方 ADB 助手"
        else -> "已开启：开机后尝试打开 App，实际效果需在当前车机验证"
    }

    fun opened(context: Context, intent: Intent?) {
        if (intent?.getBooleanExtra(FROM_BOOT, false) == true) {
            preferences(context).edit().putString("last_boot", "上次开机已打开 App").apply()
            context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION)
        }
    }

    fun handleBoot(context: Context, action: String?) {
        when (BootLaunchPolicy.decide(action, enabled(context), Build.VERSION.SDK_INT, Settings.canDrawOverlays(context))) {
            BootLaunchPolicy.Action.IGNORE -> return
            BootLaunchPolicy.Action.NOTIFY -> notifyUser(context)
            BootLaunchPolicy.Action.OPEN_HOME -> {
                // Android/OEM policy can silently block this request; do not report it as success.
                preferences(context).edit().putString("last_boot", "上次开机已请求打开 App").apply()
                runCatching { context.startActivity(homeIntent(context)) }.onFailure { notifyUser(context) }
            }
        }
    }

    private fun homeIntent(context: Context) = Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .putExtra(FROM_BOOT, true)

    private fun createChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "开机启动", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notifyUser(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val permitted = manager.areNotificationsEnabled() && (Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        preferences(context).edit().putString("last_boot", if (permitted) "需要后台打开权限，请从通知打开 App" else "需要后台打开权限，请手动打开 App").apply()
        if (!permitted) return
        createChannel(context)
        val open = PendingIntent.getActivity(context, NOTIFICATION, homeIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        runCatching {
            manager.notify(NOTIFICATION, Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_popup_sync).setContentTitle("OpenBYDDisplay 已准备启动")
                .setContentText("点击打开 App；可在 ADB 助手配置开机打开权限")
                .setContentIntent(open).setAutoCancel(true).build())
        }
    }
}
