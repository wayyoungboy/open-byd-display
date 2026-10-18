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
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("full_width", value).putString("layout", if (value) "full" else "mini").apply()
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
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("fps", 15).coerceIn(5, 30)

    fun setFps(context: Context, value: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("fps", value.coerceIn(5, 30)).apply()
    }
    fun snapshot(context: Context): CastOptions {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return CastOptions(host = host(context), port = p.getInt("video_port", 47782),
            source = p.getString("source", "mirror") ?: "mirror",
            layout = if (p.contains("layout")) p.getString("layout", "full")!! else if (fullWidth(context)) "full" else "mini",
            fps = fps(context), bitrate = p.getInt("bitrate", 3_000_000),
            opacity = p.getInt("opacity", 255), softEdge = if (softEdgeMask(context)) p.getInt("edge", 10) else 0,
            horizontalFlip = p.getBoolean("flip", false), renderScale = p.getInt("scale", 1),
            reconnects = if (autoReconnect(context)) 3 else 0,
            mapPackage = p.getString("map_package", "com.autonavi.amapauto")!!,
            virtualDisplayName = p.getString("virtual_display_name", "fission_bg_xdjaVirtualSurface")!!)
    }
    fun saveOptions(context: Context, o: CastOptions) {
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("host", o.host).putInt("video_port", o.port).putString("source", o.source)
            .putString("layout", o.layout).putBoolean("full_width", o.layout == "full")
            .putInt("fps", o.fps).putInt("bitrate", o.bitrate).putInt("opacity", o.opacity)
            .putInt("edge", maxOf(1, o.softEdge)).putBoolean("soft_edge_mask", o.softEdge > 0)
            .putBoolean("flip", o.horizontalFlip).putInt("scale", o.renderScale)
            .putBoolean("auto_reconnect", o.reconnects > 0).putString("map_package", o.mapPackage)
            .putString("virtual_display_name", o.virtualDisplayName).commit()) { "设置保存失败" }
    }
}
