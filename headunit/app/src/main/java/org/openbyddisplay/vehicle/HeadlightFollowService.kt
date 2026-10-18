package org.openbyddisplay.vehicle

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.SystemClock
import org.json.JSONObject
import org.openbyddisplay.ambient.AmbientAdb
import org.openbyddisplay.ambient.AmbientProtocol
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors

class HeadlightFollowService : Service() {
    private class Run {
        val id = UUID.randomUUID().toString().replace("-", "")
        @Volatile var stopping = false
        @Volatile var server: ServerSocket? = null
        @Volatile var connected = false
        val monitor = Object()
    }
    @Volatile private var run: Run? = null
    @Volatile private var destroyed = false
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            HeadlightSettings.enabled(this, false)
            run?.let(::stop) ?: stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != START) { if (run == null) stopSelf(); return START_NOT_STICKY }
        if (run != null) return START_NOT_STICKY
        try {
            check(HeadlightSettings.enabled(this) && !HeadlightSettings.blocked(this)) { "联动关闭或上次恢复未确认" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel("headlight_follow", "大灯与深浅模式", NotificationManager.IMPORTANCE_LOW))
            val stop = PendingIntent.getService(this, 0, Intent(this, HeadlightFollowService::class.java).setAction(STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            startForeground(4205, Notification.Builder(this, "headlight_follow").setSmallIcon(android.R.drawable.ic_menu_day)
                .setContentTitle("大灯与深浅模式联动").setContentText("原生手动选择优先")
                .addAction(Notification.Action.Builder(null, "停止并交回 Auto", stop).build()).build())
            val task = Run()
            run = task; owner = this; running = true; status = "正在读取原生模式与近光灯…"
            queue.execute { execute(task) }
        } catch (t: Exception) { status = "联动未启动：${t.message}"; stopSelf(startId) }
        return START_NOT_STICKY
    }
    private fun stop(task: Run) {
        task.stopping = true
        synchronized(task.monitor) { task.monitor.notifyAll() }
        if (!task.connected) runCatching { task.server?.close() }
    }
    private fun execute(task: Run) {
        fun current() = !destroyed && owner === this && run === task
        fun publish(value: String) { if (current()) status = value }
        var authenticated = false
        var marked = false
        var restored = false
        var socket: Socket? = null
        try {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                task.server = server; server.soTimeout = 12000
                if (task.stopping || !current()) return
                HeadlightSettings.begin(this, task.id); marked = true
                fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"
                val command = "CLASSPATH=${quote(applicationInfo.sourceDir)} app_process /system/bin " +
                    "org.openbyddisplay.vehicle.HeadlightFollowShellHelper ${server.localPort} ${task.id}"
                val launch = AmbientAdb.exec(this, "umask 077; nohup sh -c ${quote(command)} </dev/null >/dev/null 2>&1 &")
                check(launch.ok || launch.commandMayBeRunning) { launch.detail }
                if (task.stopping || !current()) return
                socket = server.accept(); task.connected = true
                val input = socket!!.getInputStream()
                fun read() = AmbientProtocol.line(input, SystemClock::elapsedRealtime, 9000) { socket!!.soTimeout = it }
                    ?: error("联动连接已断开")
                val output = PrintWriter(socket!!.getOutputStream(), true)
                check(read() == "HELLO ${task.id}")
                if (task.stopping || !current()) return
                authenticated = true; output.println("AUTH"); check(!output.checkError())
                check(read() == "READY")
                while (!task.stopping && current()) {
                    output.println("POLL"); check(!output.checkError())
                    val reply = read()
                    if (HeadlightProtocol.terminal(reply) != null) {
                        restored = HeadlightProtocol.confirmed(reply)
                        publish(HeadlightProtocol.describe(reply))
                        return
                    }
                    val data = JSONObject(reply)
                    publish(when {
                        data.getInt("native") in 1..2 || data.getInt("nightMode") != 0 -> "原生或系统手动模式优先，联动已暂停"
                        data.getInt("native") != 0 || data.getInt("lowBeam") !in 0..1 -> "车辆反馈不可用，未切换模式"
                        !data.getBoolean("following") -> "等待近光灯状态稳定…"
                        data.getInt("lowBeam") == 1 -> "近光灯已开，深色读回已确认"
                        else -> "近光灯已关，浅色读回已确认"
                    })
                    synchronized(task.monitor) { if (!task.stopping) task.monitor.wait(750) }
                }
                output.println("STOP")
                val reply = read()
                restored = HeadlightProtocol.confirmed(reply)
                publish(HeadlightProtocol.describe(reply))
            }
        } catch (t: Exception) { publish("联动未运行：${t.message}；恢复结果请核对") }
        finally {
            runCatching { socket?.close() }; runCatching { task.server?.close() }
            if (marked && (!authenticated || restored)) runCatching { HeadlightSettings.confirmed(this, task.id) }
            Handler(mainLooper).post {
                if (owner !== this || run !== task) return@post
                run = null; owner = null; running = false
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            }
        }
    }
    override fun onDestroy() { destroyed = true; run?.let(::stop); super.onDestroy() }
    companion object {
        private val queue = Executors.newSingleThreadExecutor { Thread(it, "OpenBYD-Headlight") }
        @Volatile private var owner: HeadlightFollowService? = null
        @Volatile var running = false
            private set
        @Volatile var status = "大灯联动关闭；原生手动模式优先"
            private set
        const val START = "org.openbyddisplay.HEADLIGHT_START"
        const val STOP = "org.openbyddisplay.HEADLIGHT_STOP"
        fun start(context: Context) = androidx.core.content.ContextCompat.startForegroundService(context,
            Intent(context, HeadlightFollowService::class.java).setAction(START))
    }
}
