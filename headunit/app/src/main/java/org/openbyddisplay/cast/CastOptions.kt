package org.openbyddisplay.cast

import org.json.JSONObject
import kotlin.math.roundToInt

data class CastRect(val x: Int, val y: Int, val width: Int, val height: Int) {
    init { require(x in 0..1920 && y in 0..1080 && width in 2..1920 && height in 2..1080 && x + width <= 1920 && y + height <= 1080) }
    fun fit(encodedWidth: Int, encodedHeight: Int): CastRect {
        require(encodedWidth in 2..1920 && encodedHeight in 2..1080)
        val w: Int; val h: Int
        if (width.toLong() * encodedHeight > height.toLong() * encodedWidth) {
            h = height; w = maxOf(2, (height.toLong() * encodedWidth / encodedHeight).toInt())
        } else {
            w = width; h = maxOf(2, (width.toLong() * encodedHeight / encodedWidth).toInt())
        }
        return CastRect(x + (width-w)/2, y + (height-h)/2, w, h)
    }
    fun preset(layout: String): CastRect = when (layout) {
        "center" -> { val w = maxOf(2, width * 3 / 4); CastRect(x + (width - w) / 2, y, w, height) }
        "mini" -> { val w = maxOf(2, width / 2); val h = maxOf(2, height / 2); CastRect(x + (width-w)/2, y + (height-h)/2, w, h) }
        "full" -> this
        else -> error("未知布局")
    }
}

data class CastOptions(
    val host: String = "192.168.77.1", val port: Int = 47782,
    val source: String = "mirror", val layout: String = "full",
    val fps: Int = 15, val bitrate: Int = 3_000_000,
    val opacity: Int = 255, val softEdge: Int = 0, val horizontalFlip: Boolean = false,
    val renderScale: Int = 1, val reconnects: Int = 3,
    val mapPackage: String = "com.autonavi.amapauto",
    val virtualDisplayName: String = "fission_bg_xdjaVirtualSurface"
) {
    init {
        privateIpv4(host)
        require(port in 1024..65535 && source in setOf("mirror", "map", "flyscreen", "pattern"))
        require(layout in setOf("full", "center", "mini"))
        require(fps in 5..30 && bitrate in 500_000..8_000_000 && opacity in 1..255)
        require(softEdge in 0..20 && renderScale in 1..2 && reconnects in 0..5)
        require(virtualDisplayName.matches(Regex("[A-Za-z0-9_.-]{1,80}")))
        require(mapPackage.matches(Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+")))
    }
    fun encodedSize(width: Int, height: Int): Pair<Int, Int> {
        require(width in 2..8192 && height in 2..8192)
        val ratio = minOf(1.0, 1280.0 / width, 720.0 / height)
        return ((width * ratio).roundToInt().coerceAtLeast(2) and -2) to
            ((height * ratio).roundToInt().coerceAtLeast(2) and -2)
    }
    fun json() = JSONObject().put("host", host).put("port", port).put("source", source)
        .put("layout", layout).put("fps", fps).put("bitrate", bitrate).put("opacity", opacity)
        .put("soft_edge", softEdge).put("flip", horizontalFlip).put("render_scale", renderScale)
        .put("reconnects", reconnects).put("map_package", mapPackage).put("virtual_display_name", virtualDisplayName)
    companion object {
        fun parse(o: JSONObject) = CastOptions(o.optString("host", "192.168.77.1"), o.optInt("port", 47782),
            o.optString("source", "mirror"), o.optString("layout", "full"), o.optInt("fps", 15),
            o.optInt("bitrate", 3_000_000), o.optInt("opacity", 255), o.optInt("soft_edge", 0),
            o.optBoolean("flip"), o.optInt("render_scale", 1), o.optInt("reconnects", 3),
            o.optString("map_package", "com.autonavi.amapauto"), o.optString("virtual_display_name", "fission_bg_xdjaVirtualSurface"))
        fun privateIpv4(host: String) {
            val parts = host.split('.')
            require(parts.size == 4 && parts.all { it.matches(Regex("0|[1-9][0-9]{0,2}")) && it.toInt() <= 255 }) { "请填写私有 IPv4 地址" }
            val a = parts.map(String::toInt)
            require(a[0] == 127 || a[0] == 10 || a[0] == 192 && a[1] == 168 || a[0] == 172 && a[1] in 16..31) { "只允许本地或私有网络" }
        }
    }
}

