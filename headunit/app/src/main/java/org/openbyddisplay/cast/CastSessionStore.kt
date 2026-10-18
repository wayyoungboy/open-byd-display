package org.openbyddisplay.cast

import android.content.Context
import org.json.JSONObject

object CastSessionStore {
    private const val PREFS = "openbyd_cast_session"

    @Volatile
    var busy: Boolean = false

    fun read(context: Context): JSONObject {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("state", null)
        return runCatching {
            val state = if (raw.isNullOrBlank()) JSONObject().put("state", "idle") else JSONObject(raw)
            if (!busy && state.optBoolean("target_casting")) state.put("state", "interrupted")
                .put("target_casting", false).put("message", "上次投屏服务已中断；仪表清理结果未确认")
            state
        }.getOrElse { JSONObject().put("state", "unknown").put("message", "本机投屏状态记录不可读") }
    }

    fun write(context: Context, state: JSONObject) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("state", state.toString()).apply()
    }

    fun idle(context: Context, message: String = "投屏空闲") {
        write(
            context,
            JSONObject()
                .put("state", "idle")
                .put("message", message)
                .put("target_casting", false),
        )
    }
}
