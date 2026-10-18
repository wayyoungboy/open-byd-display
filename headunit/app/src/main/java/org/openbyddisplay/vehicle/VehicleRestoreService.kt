package org.openbyddisplay.vehicle

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import kotlin.concurrent.thread

class VehicleRestoreService : Service() {
    private var active = false
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (active) return START_NOT_STICKY
        active = true
        try {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel("vehicle_restore", "恢复车机设置", NotificationManager.IMPORTANCE_LOW))
            startForeground(4206, Notification.Builder(this, "vehicle_restore").setSmallIcon(android.R.drawable.ic_menu_day)
                .setContentTitle("恢复已开启的车机设置").setContentText("不启动投屏、USB 切换或安装").build())
            thread(name = "OpenBYD-VehicleRestore") {
                try {
                    val plan = VehicleRestorePolicy.plan(intent?.action, UiModeHelper.keepUnlocked(this),
                        HeadlightSettings.enabled(this), HeadlightSettings.blocked(this))
                    val messages = mutableListOf<String>()
                    if (plan.unlock) messages += runCatching {
                        check(UiModeHelper.ensureUnlockedWithin(this))
                        "原生入口解锁已读回"
                    }.getOrElse { "原生入口解锁未确认" }
                    if (plan.headlight) messages += runCatching {
                        if (!HeadlightFollowService.running) HeadlightFollowService.start(this)
                        "已请求恢复大灯联动，查看独立状态"
                    }.getOrElse { "大灯联动恢复请求未接受" }
                    status = messages.ifEmpty { listOf("没有开启可恢复的选项") }.joinToString("；")
                } catch (t: Exception) { status = "车机设置恢复未确认：${t.message}" }
                finally { Handler(mainLooper).post { active = false; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() } }
            }
        } catch (t: Exception) { status = "恢复服务未启动：${t.message}"; active = false; stopSelf(startId) }
        return START_NOT_STICKY
    }
    companion object {
        @Volatile var status = "未执行开机设置恢复"; private set
        internal fun report(value: String) { status = value }
    }
}
