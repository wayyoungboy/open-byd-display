package org.openbyddisplay.vehicle

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import org.openbyddisplay.InstrumentOperation
import org.openbyddisplay.InstrumentUpdateStore
import org.openbyddisplay.cast.CastSafetyStore
import org.openbyddisplay.ambient.AmbientAdb
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** One explicit request, no mode-change queue. A later refresh only reads that request's file. */
class UsbSwitchService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val mode = intent?.getStringExtra("mode")
        if (mode !in setOf("cast", "udisk", "refresh", "recovered")) { if (!busy.get()) stopSelf(startId); return START_NOT_STICKY }
        if (!busy.compareAndSet(false, true)) { if (owner !== this) stopSelf(startId); return START_NOT_STICKY }
        if (!InstrumentOperation.acquire("usb")) {
            busy.set(false); status = "请先停止投屏或等待更新操作结束"; stopSelf(startId); return START_NOT_STICKY
        }
        try {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel("usb_switch", "USB 模式切换", NotificationManager.IMPORTANCE_LOW))
            startForeground(4204, Notification.Builder(this, "usb_switch").setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("仪表 USB 请求").setContentText("核对请求及 NCM 反馈，不自动重发").build())
            owner = this
        } catch (t: Exception) {
            InstrumentOperation.release("usb"); busy.set(false); status = "USB 请求未启动：${t.message}"; stopSelf(startId)
            return START_NOT_STICKY
        }
        queue.execute {
            try {
                check(!CastSafetyStore.blocked(this) && InstrumentUpdateStore.canReplace(InstrumentUpdateStore.read(this))) {
                    "投屏恢复或更新任务未确认，暂不切换 USB"
                }
                if (mode == "recovered") {
                    check(intent?.getBooleanExtra("operator_confirmed", false) == true && intent.getBooleanExtra("parked", false))
                    check(UsbSwitchStore.recoveryAllowed(this)) { "请先等待原请求至少 90 秒" }
                    val pending = UsbSwitchStore.read(this) ?: error("没有待确认请求")
                    fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"
                    val command = "CLASSPATH=${quote(applicationInfo.sourceDir)} app_process /system/bin " +
                        "org.openbyddisplay.vehicle.UsbModeShellHelper CHECK_USB_IDLE ${pending.first}"
                    val check = AmbientAdb.exec(this, command)
                    check(check.ok && check.output.trim() == "QUIESCENT request=${pending.first} uid=2000") {
                        "原 helper 尚未确认退出，保留记录"
                    }
                    UsbSwitchStore.confirmed(this, pending.first)
                    status = "已按人工核对清除不明记录；未重新切换 USB，也未宣称自动确认仪表模式。"
                    return@execute
                }
                val result = if (mode == "refresh") {
                    val pending = UsbSwitchStore.read(this) ?: error("没有待确认的 USB 请求")
                    val poll = AmbientAdb.exec(this, AmbientAdb.buildUsbModePollCommand(pending.first))
                    check(poll.ok) { poll.detail }
                    val result = UsbModeEvidence.parse(poll.output, pending.first, pending.second)
                        ?: error("请求尚无完整结果，继续保留记录；未重新切换 USB")
                    if (result.completionKnown) {
                        UsbSwitchStore.confirmed(this, pending.first)
                        AmbientAdb.exec(this, AmbientAdb.buildUsbModeCleanupCommand(pending.first))
                    }
                    result
                } else {
                    check(intent?.getBooleanExtra("parked", false) == true) { "请先确认已停车及固件支持此命令" }
                    val request = UUID.randomUUID().toString().replace("-", "")
                    val cmd = if (mode == "cast") BydInstrumentCmd.SCMD_USBMODE_CAST else BydInstrumentCmd.SCMD_USBMODE_UDISK
                    UsbSwitchStore.begin(this, request, cmd)
                    status = "等待 USB 请求和网络反馈，最长约 90 秒…"
                    BydInstrumentCmd.sendUsbMode(this, cmd, request).also {
                        if (it.completionKnown) {
                            UsbSwitchStore.confirmed(this, request)
                            AmbientAdb.exec(this, AmbientAdb.buildUsbModeCleanupCommand(request))
                        }
                    }
                }
                status = result.detail // COMMAND_ACCEPTED never becomes "U-disk verified".
            } catch (t: Exception) { status = "USB 结果未确认：${t.message}" }
            finally { Handler(mainLooper).post {
                busy.set(false); if (owner === this) owner = null
                InstrumentOperation.release("usb"); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            } }
        }
        return START_NOT_STICKY
    }
    companion object {
        private val queue = Executors.newSingleThreadExecutor { Thread(it, "OpenBYD-USB") }
        @Volatile private var owner: UsbSwitchService? = null
        val busy = AtomicBoolean(false)
        @Volatile var status = "尚未切换 USB。命令受理、NCM 配网和实际仪表模式分别核对。"
            private set
    }
}
