package org.openbyddisplay.cast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import org.openbyddisplay.MainActivity
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MediaProjection → H.264 → BVL1/LHF2 cast service for the self-developed path.
 * Requires a matching instrument receiver; host-lab can accept video for protocol tests only.
 */
class CastForegroundService : Service() {
    private val running = AtomicBoolean(false)
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: H264Encoder? = null
    private var video: VideoStreamClient? = null
    private var worker: HandlerThread? = null
    private var handler: Handler? = null
    private var epoch: String = CastProtocol.newEpoch()
    private var revision: Int = 0
    private var profile: CastProfile? = null
    private var host: String = CastPorts.DEFAULT_HOST

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCast("用户停止投屏")
                stopSelf()
                return START_NOT_STICKY
            }
        }
        if (!running.compareAndSet(false, true)) return START_STICKY
        host = intent?.getStringExtra(EXTRA_HOST) ?: CastSettings.host(this)
        val fullWidth = intent?.getBooleanExtra(EXTRA_FULL_WIDTH, CastSettings.fullWidth(this)) ?: true
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        if (resultCode == 0 || data == null) {
            CastSessionStore.idle(this, "缺少录屏授权")
            running.set(false)
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, notification("正在连接仪表…"))
        CastSessionStore.busy = true
        worker = HandlerThread("obd-cast").also { it.start() }
        handler = Handler(worker!!.looper)
        handler!!.post { runCatching { startPipeline(resultCode, data, fullWidth) }.onFailure { fail(it) } }
        return START_STICKY
    }

    private fun startPipeline(resultCode: Int, data: Intent, fullWidth: Boolean) {
        val client = ClusterJsonClient(host)
        val profiles = loadProfiles(client)
        val selected = CastProfile.select(profiles, fullWidth)
        profile = selected
        epoch = CastProtocol.newEpoch()
        revision = 0
        val start = client.startSession(selected.profileId, epoch, revision)
        if (start.optBoolean("ok", false)) {
            epoch = start.optString("epoch", epoch)
            revision = start.optInt("revision", revision)
        }
        val options = CastProtocol.windowOptions(selected)
        runCatching { client.arm(options) }
        runCatching { client.setWindow(options) }

        val fps = CastSettings.fps(this)
        val stream = VideoStreamClient(host, selected.width, selected.height, fps)
        stream.connect()
        video = stream

        val enc = H264Encoder(selected.width, selected.height, fps) { bytes, ptsUs, key ->
            stream.sendAccessUnit(bytes, (ptsUs / 1000L).toInt(), key)
        }
        enc.start()
        encoder = enc

        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mgr.getMediaProjection(resultCode, data)
        projection = proj
        virtualDisplay = proj.createVirtualDisplay(
            "OpenBYDDisplay",
            selected.width,
            selected.height,
            resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            enc.inputSurface,
            null,
            handler,
        )
        CastSessionStore.write(
            this,
            JSONObject()
                .put("state", "playing")
                .put("message", "投屏进行中 ${selected.width}x${selected.height}@$fps")
                .put("target_casting", true)
                .put("profile_id", selected.profileId)
                .put("width", selected.width)
                .put("height", selected.height)
                .put("epoch", epoch)
                .put("revision", revision)
                .put("host", host),
        )
        nm().notify(NOTIF_ID, notification("投屏中 ${selected.width}×${selected.height}"))
        scheduleDrainAndKeepalive()
    }

    private fun loadProfiles(client: ClusterJsonClient): List<CastProfile> {
        val remote = runCatching { client.profileGet() }.getOrNull()
        val array = remote?.optJSONArray("profiles")
        if (array != null && array.length() > 0) return CastProfile.parseList(array)
        assets.open("instrument-defaults/openbyd-factory.json").use { input ->
            val factory = JSONObject(input.readBytes().toString(Charsets.UTF_8))
            return CastProfile.parseList(factory.getJSONArray("cast_profiles"))
        }
    }

    private fun scheduleDrainAndKeepalive() {
        val h = handler ?: return
        val started = SystemClock.elapsedRealtime()
        h.post(object : Runnable {
            override fun run() {
                if (!running.get()) return
                runCatching { encoder?.drain() }
                val elapsed = SystemClock.elapsedRealtime() - started
                if (elapsed % 3000L < 40L) {
                    runCatching {
                        ClusterJsonClient(host).heartbeat(epoch, revision)
                        ClusterJsonClient(host).receiverKeepalive()
                    }
                }
                // optional overlays
                maybePushOverlays()
                h.postDelayed(this, 33)
            }
        })
    }

    private fun maybePushOverlays() {
        if (!CastSettings.lyricsEnabled(this) && !CastSettings.navEnabled(this)) return
        val client = ClusterJsonClient(host)
        if (CastSettings.lyricsEnabled(this)) {
            runCatching {
                client.musicShow(
                    MusicOverlayProtocol.track(
                        title = "OpenBYDDisplay",
                        artist = "lab",
                        lyricLines = listOf("自研歌词叠加实验"),
                        playing = true,
                    ),
                )
            }
        }
        if (CastSettings.navEnabled(this)) {
            runCatching {
                client.navigationShow(
                    NavOverlayProtocol.guidance(
                        nextRoad = "实验路段",
                        distanceMeters = 200,
                        icon = "straight",
                    ),
                )
            }
        }
    }

    private fun fail(error: Throwable) {
        Log.e(TAG, "cast failed", error)
        stopCast(error.message ?: "投屏失败")
        stopSelf()
    }

    private fun stopCast(message: String) {
        if (!running.getAndSet(false) && !CastSessionStore.busy) {
            CastSessionStore.idle(this, message)
            return
        }
        handler?.removeCallbacksAndMessages(null)
        runCatching {
            ClusterJsonClient(host).stopSession(epoch, revision)
        }
        if (CastSettings.lyricsEnabled(this)) runCatching { ClusterJsonClient(host).musicHide() }
        if (CastSettings.navEnabled(this)) runCatching { ClusterJsonClient(host).navigationHide() }
        runCatching { virtualDisplay?.release() }
        runCatching { encoder?.close() }
        runCatching { video?.close() }
        runCatching { projection?.stop() }
        virtualDisplay = null
        encoder = null
        video = null
        projection = null
        worker?.quitSafely()
        worker = null
        handler = null
        CastSessionStore.busy = false
        CastSessionStore.idle(this, message)
        stopForeground(true)
    }

    override fun onDestroy() {
        stopCast("投屏服务结束")
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val channelId = "obd_cast"
        if (Build.VERSION.SDK_INT >= 26) {
            nm().createNotificationChannel(
                NotificationChannel(channelId, "投屏", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, CastForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channelId)
        else Notification.Builder(this)
        return builder.setContentTitle("OpenBYDDisplay")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(pi)
            .addAction(Notification.Action.Builder(null, "停止", stop).build())
            .setOngoing(true)
            .build()
    }

    private fun nm() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        private const val TAG = "ObdCast"
        private const val NOTIF_ID = 0x4f424443
        const val ACTION_STOP = "org.openbyddisplay.cast.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        const val EXTRA_HOST = "host"
        const val EXTRA_FULL_WIDTH = "fullWidth"

        fun stopIntent(context: Context): Intent =
            Intent(context, CastForegroundService::class.java).setAction(ACTION_STOP)
    }
}
