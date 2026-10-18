package org.openbyddisplay.cast

import android.content.Context

/** Persistent cast preferences for the self-developed App path. */
object CastSettings {
    private const val PREFS = "openbyd_cast"

    fun host(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("host", CastPorts.DEFAULT_HOST) ?: CastPorts.DEFAULT_HOST

    fun setHost(context: Context, host: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("host", host.trim()).apply()
    }

    fun fullWidth(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("full_width", true)

    fun setFullWidth(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("full_width", value).apply()
    }

    fun autoReconnect(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("auto_reconnect", true)

    fun setAutoReconnect(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("auto_reconnect", value).apply()
    }

    fun softEdgeMask(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("soft_edge_mask", true)

    fun setSoftEdgeMask(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("soft_edge_mask", value).apply()
    }

    fun lyricsEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("lyrics_enabled", false)

    fun setLyricsEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("lyrics_enabled", value).apply()
    }

    fun navEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("nav_enabled", false)

    fun setNavEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("nav_enabled", value).apply()
    }

    fun fps(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("fps", 30).coerceIn(5, 30)

    fun setFps(context: Context, value: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("fps", value.coerceIn(5, 30)).apply()
    }
}
