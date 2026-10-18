package org.openbyddisplay.cast

import android.content.Context
import org.json.JSONObject

object CastSessionStore {
    private const val PREFS = "openbyd_cast_session"

    @Volatile
    var busy: Boolean = false

    fun read(context: Context): JSONObject {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("state", null)
        return if (raw.isNullOrBlank()) JSONObject().put("state", "idle") else JSONObject(raw)
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
