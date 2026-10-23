package org.openbyddisplay.cast

import android.app.*
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import org.openbyddisplay.InstrumentOperation
import org.openbyddisplay.InstrumentUpdateStore
import org.openbyddisplay.MainActivity
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Serial capture/resource owner; stop only signals it and unblocks the socket. */
class CastForegroundService : Service() {
    private class Run {
        val owner = java.util.UUID.randomUUID().toString()
        val stop = AtomicBoolean(false)
        val active = AtomicReference<VideoStreamClient?>()
        var touched = false
        var stopDeadline: Runnable? = null
    }
    private val currentRun = AtomicReference<Run?>()
    private var latestStartId = 0
    private val started = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        if (intent?.action == ACTION_STOP) {
            requestStop()
            if (!started.get()) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent == null || !started.compareAndSet(false, true)) return START_NOT_STICKY
        val options = runCatching { CastOptions.parse(JSONObject(intent.getStringExtra(EXTRA_OPTIONS) ?: "{}")) }.getOrElse {
            started.set(false); CastSessionStore.idle(this, "投屏设置无效"); stopSelf(startId); return START_NOT_STICKY
        }
        val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
        val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        if ((options.source == "mirror" && (data == null || code != Activity.RESULT_OK)) ||
            org.openbyddisplay.vehicle.UsbSwitchStore.blocked(this) ||
            !InstrumentUpdateStore.canCast(InstrumentUpdateStore.read(this), options.host) || !InstrumentOperation.acquire("cast")) {
            started.set(false); CastSessionStore.idle(this, "请先结束更新/USB 请求，并取得本次录屏授权"); stopSelf(startId)
            return START_NOT_STICKY
        }
        val run = Run()
        currentRun.set(run); CastSessionStore.busy = true
        try {
            val type = if (options.source == "mirror") android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                else android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (android.os.Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, notification("正在连接自研视频服务"), type)
            else startForeground(NOTIF_ID, notification("正在连接自研视频服务"))
            worker.execute { pipeline(options, code, data, run) }
        } catch (_: Exception) {
            currentRun.compareAndSet(run, null); started.set(false); CastSessionStore.busy = false; InstrumentOperation.release("cast")
            CastSessionStore.idle(this, "系统未允许启动投屏服务"); stopSelf(startId)
        }
        return START_NOT_STICKY
    }
    private fun requestStop() {
        val run = currentRun.get() ?: return
        run.stop.set(true)
        // Give the owner a chance to send EOS and read cleanup; then bound shutdown.
        if (run.stopDeadline == null) {
            run.stopDeadline = Runnable { run.active.get()?.close() }.also { main.postDelayed(it, 5500) }
        }
    }
    private fun pipeline(options: CastOptions, resultCode: Int, data: Intent?, run: Run) {
        val stop = run.stop
        val active = run.active
        var projection: MediaProjection? = null
        var display: VirtualDisplay? = null
        var encoder: H264Encoder? = null
        var renderer: CastFrameRenderer? = null
        var pattern: CastPattern? = null
        val overlays = CastOverlaySource(this)
        var clean = false
        var message = "投屏已结束；仪表清理结果未确认"
        try {
            fun current() { check(!stop.get()) { "投屏已停止" } }
            overlays.start()
            val metrics = resources.displayMetrics
            val (width, height) = if (options.source == "mirror") options.encodedSize(metrics.widthPixels, metrics.heightPixels)
                else 1280 to 480
            current()
            encoder = H264Encoder(width, height, options.fps, options.bitrate)
            encoder.start()
            current()
            var lastEdgeRgb = VignettePalette.DARK_RGB
            renderer = CastFrameRenderer(width * options.renderScale, height * options.renderScale,
                width, height, encoder.inputSurface!!, options.fps, options.softEdge / 100f, options.horizontalFlip,
                { VignettePalette.fromUiMode(resources.configuration.uiMode, lastEdgeRgb).also { lastEdgeRgb = it } }, overlays::text) {
                stop.set(true); active.get()?.close()
                if (currentRun.get() === run) CastSessionStore.write(this, JSONObject().put("message", it).put("state", "error"))
            }
            renderer.start()
            current()
            if (options.source == "mirror") {
                projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(resultCode, data!!)
                projection.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() { stop.set(true); active.get()?.close() }
                }, main)
                current()
                display = projection.createVirtualDisplay("OpenBYDDisplay Mirror", width * options.renderScale,
                    height * options.renderScale, metrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    renderer.inputSurface, null, main)
            } else {
                display = checkNotNull(getSystemService(DisplayManager::class.java).createVirtualDisplay(
                    if (options.source == "flyscreen") options.virtualDisplayName else "OpenBYDDisplay Navigation",
                    width * options.renderScale, height * options.renderScale, 160,
                    renderer.inputSurface, DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                        (if (options.source == "flyscreen") DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC else 0)))
                val targetDisplay = display.display
                current()
                if (options.source == "pattern") pattern = CastPattern.show(this, targetDisplay, main, stop)
                else {
                    val launched = runCatching {
                        val launch = packageManager.getLaunchIntentForPackage(options.mapPackage) ?: error("未安装所选地图")
                        val bundle = ActivityOptions.makeBasic().apply { launchDisplayId = if (options.source == "flyscreen") 0 else targetDisplay.displayId }.toBundle()
                        startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), bundle)
                        if (options.source == "flyscreen") {
                            sendBroadcast(Intent("com.autonavi.amapauto.AUTO_WIDGET_MAIN_MAP").setPackage(options.mapPackage).putExtra("displayId", targetDisplay.displayId))
                        }
                    }.isSuccess
                    CastSessionStore.write(this, JSONObject().put("state", "waiting_source")
                        .put("message", if (launched) "已请求地图启动；副地图是否绑定本次副屏需核对" else "系统拒绝副屏启动。请通过 ADB 在副屏启动地图")
                        .put("display_id", targetDisplay.displayId).put("target_casting", true))
                }
            }
            var failures = 0
            var lastStatus = 0L
            while (!stop.get()) {
                val stream = VideoStreamClient(options)
                active.set(stream)
                var sent = false
                try {
                    current()
                    val bounds = stream.open(width, height) {
                        current(); CastSafetyStore.begin(this, run.owner); run.touched = true
                    }
                    current()
                    val caps = stream.capabilities()
                    if (caps != null) {
                        CastSessionStore.write(this, JSONObject()
                            .put("state", "window_calibrated")
                            .put("message", caps.fullscreenNote(options.layout == "full"))
                            .put("target_casting", true)
                            .put("hmi_control", caps.hmiControl)
                            .put("covers_physical", caps.coversPhysical)
                            .put("bounds", org.json.JSONArray(listOf(caps.bounds.x, caps.bounds.y, caps.bounds.width, caps.bounds.height)))
                            .put("visual_verified", false))
                    }
                    val rect = bounds.preset(options.layout).fit(width, height)
                    stream.configure(rect)
                    encoder.reconnect()
                    var lastTraffic = SystemClock.elapsedRealtime()
                    val firstDeadline = lastTraffic + 15_000
                    while (!stop.get()) {
                        val frame = encoder.next()
                        val now = SystemClock.elapsedRealtime()
                        if (frame != null) {
                            val receipt = stream.frame(frame.au, frame.ptsMs)
                            sent = true; failures = 0; lastTraffic = now
                            if (now - lastStatus > 1000) {
                                val confirmed = receipt.opt("property_readback") == true && VideoStreamClient.integer(receipt, "screen_posts") > 0
                                CastSessionStore.write(this, JSONObject().put("state", if (confirmed) "window_submitted" else "waiting_window")
                                    .put("message", if (confirmed) "仪表已回读视频窗口；实际画面待实车确认" else "视频帧已提交，等待窗口回读")
                                    .put("target_casting", true).put("submitted", receipt.getLong("submitted"))
                                    .put("host", options.host).put("width", width).put("height", height)
                                    .put("display_id", display?.display?.displayId ?: -1).put("visual_verified", false))
                                lastStatus = now
                            }
                        } else if (now - lastTraffic >= 1000) {
                            stream.heartbeat(); lastTraffic = now
                        }
                        check(sent || now < firstDeadline) { "15 秒内未取得带 SPS/PPS 的关键帧" }
                    }
                    if (sent) { stream.finish(); clean = CastSafetyStore.confirmed(this, run.owner) }
                } catch (e: Exception) {
                    if (run.touched) CastSafetyStore.uncertain(this)
                    if (stop.get()) break
                    failures++
                    check(failures <= options.reconnects) { "视频连接失败，已停止：${e.message}" }
                    CastSessionStore.write(this, JSONObject().put("state", "reconnecting").put("target_casting", true)
                        .put("message", "视频连接中断，正在重连 $failures/${options.reconnects}"))
                    repeat(failures * 10) { if (!stop.get()) Thread.sleep(100) }
                } finally { active.compareAndSet(stream, null); stream.close() }
            }
            if (clean) message = "投屏已停止，仪表已确认资源清理"
        } catch (e: Exception) {
            message = if (stop.get()) message else "投屏未完成：${e.message?.take(160)}"
        } finally {
            active.getAndSet(null)?.close()
            overlays.close()
            pattern?.close()
            var localClean = runCatching { display?.release() }.isSuccess
            localClean = runCatching { projection?.stop() }.isSuccess && localClean
            renderer?.close()
            localClean = (renderer?.awaitRelease() ?: true) && localClean
            encoder?.close()
            localClean = (encoder?.releaseSucceeded ?: true) && localClean
            if ((!clean && run.touched) || !localClean) runCatching { CastSafetyStore.uncertain(this, local = !localClean) }
            if (CastSafetyStore.blocked(this)) message += "；已阻止更新，请核对仪表恢复状态"
            main.post {
                run.stopDeadline?.let(main::removeCallbacks)
                if (currentRun.compareAndSet(run, null)) {
                    CastSessionStore.busy = false
                    CastSessionStore.idle(this, message)
                    started.set(false)
                    if (localClean) InstrumentOperation.release("cast")
                    stopForeground(true); stopSelfResult(latestStartId)
                }
            }
        }
    }
    override fun onDestroy() { currentRun.get()?.let { it.stop.set(true); it.active.get()?.close() }; worker.shutdown(); super.onDestroy() }
    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("obd_cast", "仪表投屏", NotificationManager.IMPORTANCE_LOW))
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return Notification.Builder(this, "obd_cast").setContentTitle("OpenBYDDisplay").setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags))
            .addAction(Notification.Action.Builder(null, "停止", PendingIntent.getService(this, 1, stopIntent(this), flags)).build())
            .setOngoing(true).build()
    }
    companion object {
        private const val NOTIF_ID = 7102
        const val ACTION_STOP = "org.openbyddisplay.cast.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        const val EXTRA_OPTIONS = "options"
        fun stopIntent(context: Context) = Intent(context, CastForegroundService::class.java).setAction(ACTION_STOP)
    }
}
