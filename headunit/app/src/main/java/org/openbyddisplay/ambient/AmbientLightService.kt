package org.openbyddisplay.ambient

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors

class AmbientLightService : Service() {
    private class Run(val config: AmbientConfig) {
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
            run?.let { stop(it) } ?: stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != START) { if (run == null) stopSelf(); return START_NOT_STICKY }
        if (run != null) return START_NOT_STICKY // No queue of obsolete modes or frames.
        try {
            check(!AmbientStateStore.blocked(this)) { "上次恢复未确认，请核对原车灯光" }
            check(!audioCleanupUnknown) { "音频资源释放未确认，请结束并重启 App" }
            val config = AmbientConfig(AmbientEffect.valueOf(intent.getStringExtra("effect") ?: "MUSIC"),
                intent.getIntExtra("brightness", 6), intervalMs = intent.getLongExtra("interval", 500),
                musicMulticolor = intent.getBooleanExtra("multicolor", true),
                audioSource = AmbientAudioSource.valueOf(intent.getStringExtra("audio") ?: "PLAYBACK"),
                backend = AmbientBackendKind.valueOf(intent.getStringExtra("backend") ?: "SOFTWARE"))
            if (config.backend == AmbientBackendKind.NATIVE)
                check(NativeRhythmProfiles.forFingerprint(Build.FINGERPRINT) != null) { "原生律动未验证，保持关闭" }
            val task = Run(config)
            if (Build.VERSION.SDK_INT >= 29) {
                var type = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                if (Build.VERSION.SDK_INT >= 30 && config.effect == AmbientEffect.MUSIC && config.audioSource == AmbientAudioSource.MICROPHONE)
                    type = type or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                startForeground(NOTIFICATION, notification("正在连接灯光控制…"), type)
            } else startForeground(NOTIFICATION, notification("正在连接灯光控制…"))
            run = task
            owner = this
            running = true
            status = "正在连接灯光控制…"
            queue.execute { execute(task) }
        } catch (t: Exception) {
            status = "灯效未启动：${t.message}"
            stopSelf()
        }
        return START_NOT_STICKY
    }
    private fun stop(task: Run) {
        task.stopping = true
        synchronized(task.monitor) { task.monitor.notifyAll() }
        if (!task.connected) runCatching { task.server?.close() }
    }
    private fun execute(task: Run) {
        fun current() = !destroyed && run === task && owner === this
        fun publish(text: String) { if (current()) status = text }
        var audio: AmbientLightAudio? = null
        var authenticated = false
        var marked = false
        var terminal: AmbientRestoration? = null
        var socket: Socket? = null
        try {
            if (task.stopping || !current()) return
            if (task.config.effect == AmbientEffect.MUSIC) audio = AmbientLightAudio(this, task.config.audioSource)
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                task.server = server
                server.soTimeout = 12000
                if (task.stopping || !current()) return
                AmbientStateStore.begin(this, task.id)
                marked = true
                val launch = AmbientAdb.exec(this, launchCommand(applicationInfo.sourceDir, server.localPort, task.id, task.config.backend))
                check(launch.ok || launch.commandMayBeRunning) { launch.detail }
                if (task.stopping || !current()) return
                socket = server.accept()
                task.connected = true
                socket!!.soTimeout = 9000
                val input = socket!!.getInputStream()
                val output = PrintWriter(socket!!.getOutputStream(), true)
                fun read() = AmbientProtocol.line(input, SystemClock::elapsedRealtime, 9000) { socket!!.soTimeout = it }
                    ?: error("灯效连接已断开")
                check(read() == "HELLO ${task.id}") { "灯效会话校验失败" }
                if (task.stopping || !current()) return
                authenticated = true // Set before sending: even a partial write must retain the pending record.
                output.println("AUTH")
                check(!output.checkError())
                val ready = read()
                if (!ready.startsWith("READY ")) {
                    terminal = AmbientProtocol.restoration(ready)
                    publish(AmbientProtocol.describe(ready))
                    return
                }
                val initial = AmbientSnapshot.parse(ready.removePrefix("READY "))
                val engine = AmbientLightEffect(task.config, initial)
                val started = SystemClock.elapsedRealtime()
                var sequence = 0L
                var previous: AmbientFrame? = null
                var previousBands: AmbientSpectrum? = null
                var stopReason: String? = null
                while (!task.stopping && current()) {
                    if (audio?.failure != null) { stopReason = "音频分析已中断：${audio.failure}"; break }
                    val tick = SystemClock.elapsedRealtime()
                    val sample = audio?.sample ?: AmbientMusicSample()
                    if (audio != null && !(if (sample.capturedAtMs == 0L) tick - started < 3000 else
                            tick - sample.capturedAtMs < 1500)) { stopReason = "音源数据已过期"; break }
                    sequence++
                    val frame = engine.frame(tick - started, sample.level, sample.beats)
                    val command = if (task.config.backend == AmbientBackendKind.NATIVE) {
                        val bands = sample.spectrum ?: error("原生后端需要有效的 16 频带音源")
                        if (bands == previousBands) "KEEP $sequence" else
                            "SPECTRUM $sequence ${bands.values().joinToString(" ")}"
                    } else if (audio != null && sample.capturedAtMs == 0L || frame == previous) "KEEP $sequence"
                    else "FRAME $sequence ${frame.color} ${frame.brightness}"
                    output.println(command)
                    check(!output.checkError())
                    val reply = read()
                    if (!reply.startsWith("ACK $sequence ")) {
                        terminal = AmbientProtocol.restoration(reply)
                        publish(AmbientProtocol.describe(reply))
                        return
                    }
                    val feedback = reply.removePrefix("ACK $sequence ")
                    if (task.config.backend == AmbientBackendKind.SOFTWARE) {
                        val expected = if (command.startsWith("KEEP") && previous == null) initial else initial.withFrame(frame)
                        check(AmbientSnapshot.parse(feedback) == expected) { "灯光读回与目标不一致" }
                        if (command.startsWith("FRAME")) { engine.onFrameConfirmed(SystemClock.elapsedRealtime() - started); previous = frame }
                    } else { check(feedback == "NATIVE"); previousBands = sample.spectrum }
                    publish(if (task.config.backend == AmbientBackendKind.SOFTWARE)
                        "${task.config.effect.label} · 色号 ${frame.color} · 六档亮度 ${frame.brightness}/6 · ${sample.analysis}；反馈已确认" +
                            if (audio != null && tick - started > 5000 && sample.level < 0.01) "\n当前未检测到有效音乐信号" else ""
                        else "原生律动接口反馈已确认；实际灯效需目视核对")
                    val wait = if (task.config.effect == AmbientEffect.DYNAMIC) task.config.intervalMs
                        else maxOf(50, 350 - (SystemClock.elapsedRealtime() - tick))
                    synchronized(task.monitor) { if (!task.stopping) task.monitor.wait(wait) }
                }
                publish("正在恢复原灯光…")
                output.println("STOP")
                val reply = read()
                terminal = AmbientProtocol.restoration(reply)
                publish(listOfNotNull(stopReason, AmbientProtocol.describe(reply)).joinToString("；"))
            }
        } catch (t: Exception) {
            publish("灯效已停止：${t.message}" + if (authenticated) "；将尝试断连恢复，结果未确认" else "")
        } finally {
            runCatching { socket?.close() }
            task.connected = false
            runCatching { task.server?.close() }
            try { audio?.close() } catch (_: Exception) {
                audioCleanupUnknown = true
                publish("音频释放未确认，请结束 App 后核对")
            }
            if (marked && (!authenticated || terminal in setOf(AmbientRestoration.RESTORED, AmbientRestoration.OVERRIDDEN)))
                runCatching { AmbientStateStore.confirmed(this, task.id) }
            Handler(mainLooper).post {
                if (run !== task || owner !== this) return@post
                run = null
                owner = null
                running = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }
    private fun notification(text: String): android.app.Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "氛围灯控制", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, AmbientLightService::class.java).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 0, Intent(this, AmbientLightActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_day)
            .setContentTitle("OpenBYDDisplay 氛围灯").setContentText(text).setContentIntent(open).setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "停止并恢复", stop).build()
    }
    override fun onDestroy() {
        destroyed = true
        run?.let { stop(it) }
        super.onDestroy()
    }
    companion object {
        private val queue = Executors.newSingleThreadExecutor { Thread(it, "OpenBYD-AmbientControl") }
        @Volatile private var owner: AmbientLightService? = null
        @Volatile var running = false
            private set
        @Volatile var audioCleanupUnknown = false
            private set
        @Volatile var status = "未启动。结束控制后恢复原灯光。"
            private set
        const val START = "org.openbyddisplay.AMBIENT_START"
        const val STOP = "org.openbyddisplay.AMBIENT_STOP"
        private const val CHANNEL = "ambient_lights"
        private const val NOTIFICATION = 4203
        fun start(context: Context, config: AmbientConfig) = ContextCompat.startForegroundService(context,
            Intent(context, AmbientLightService::class.java).setAction(START).putExtra("effect", config.effect.name)
                .putExtra("brightness", config.maxBrightness).putExtra("interval", config.intervalMs)
                .putExtra("audio", config.audioSource.name)
                .putExtra("multicolor", config.musicMulticolor).putExtra("backend", config.backend.name))
        internal fun launchCommand(apk: String, port: Int, token: String, kind: AmbientBackendKind): String {
            require(port in 1024..65535 && token.matches(Regex("[a-f0-9]{32}")))
            fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"
            val inner = "CLASSPATH=${quote(apk)} app_process /system/bin org.openbyddisplay.ambient.AmbientLightShellHelper $port $token ${kind.name}"
            return "umask 077; nohup sh -c ${quote(inner)} </dev/null >/dev/null 2>&1 &"
        }
    }
}
