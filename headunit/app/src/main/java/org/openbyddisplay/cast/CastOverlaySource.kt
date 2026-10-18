package org.openbyddisplay.cast

import android.content.*
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import androidx.core.content.ContextCompat

/** Reads only opt-in media sessions and bounded navigation broadcasts, without network lookup. */
class CastOverlaySource(private val context: Context) : AutoCloseable {
    @Volatile private var navigation = ""
    @Volatile private var expires = 0L
    private var registered = false
    private var lastLrc = ""
    private var timeline = emptyList<Pair<Long, String>>()
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action != "AUTONAVI_STANDARD_BROADCAST_SEND") return
            runCatching {
                val type = intent.getIntExtra("KEY_TYPE", -1)
                if (type in setOf(10006, 10010, 10016, 10017) || intent.getBooleanExtra("ARRIVE_STATUS", false)) { navigation = ""; expires = 0; return }
                if (type != 10001) return
                val meters = intent.getIntExtra("SEG_REMAIN_DIS", -1)
                val remaining = intent.getIntExtra("ROUTE_REMAIN_DIS", -1)
                val road = intent.getStringExtra("NEXT_ROAD_NAME").orEmpty().take(80).filter { !it.isISOControl() }
                if (meters < 0 || remaining < 0 || road.isBlank()) return
                navigation = "$meters 米 · $road · 剩余 ${remaining / 1000.0} 公里"
                expires = SystemClock.elapsedRealtime() + 15_000
            }
        }
    }
    fun start() {
        if (CastSettings.navEnabled(context)) {
            ContextCompat.registerReceiver(context, receiver, IntentFilter("AUTONAVI_STANDARD_BROADCAST_SEND"), ContextCompat.RECEIVER_EXPORTED)
            registered = true
        }
    }
    fun text(): List<String> = buildList {
        if (CastSettings.navEnabled(context) && SystemClock.elapsedRealtime() < expires) add(navigation)
        if (CastSettings.lyricsEnabled(context)) runCatching {
            val sessions = context.getSystemService(MediaSessionManager::class.java)
                .getActiveSessions(ComponentName(context, CastMediaListener::class.java))
            val active = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: return@runCatching
            val metadata = active.metadata ?: return@runCatching
            val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty().take(100)
            val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty().take(80)
            if (title.isNotBlank()) add(listOf(title, artist).filter { it.isNotBlank() }.joinToString(" · "))
            val prefs = context.getSharedPreferences("openbyd_cast", Context.MODE_PRIVATE)
            val raw = if (prefs.getString("lyric_title", "") == title) prefs.getString("lrc", "").orEmpty() else ""
            if (raw != lastLrc) { lastLrc = raw; timeline = MusicOverlayProtocol.parseLrc(raw) }
            val state = active.playbackState ?: return@runCatching
            val elapsed = (SystemClock.elapsedRealtime() - state.lastPositionUpdateTime).coerceAtLeast(0)
            val position = state.position + (elapsed * state.playbackSpeed).toLong()
            timeline.lastOrNull { it.first <= position }?.second?.take(120)?.let(::add)
        }
    }.take(3)
    override fun close() { if (registered) runCatching { context.unregisterReceiver(receiver) }; registered = false; navigation = "" }
}

/** Android grants media-session access only after the user enables notification access. */
class CastMediaListener : NotificationListenerService()
