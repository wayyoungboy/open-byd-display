package org.openbyddisplay.cast

import org.json.JSONArray
import org.json.JSONObject

data class CastProfile(
    val profileId: Int,
    val themeId: Int,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val opacity: Int = 255,
    val radius: Int = 0,
) {
    val area: Long get() = width.toLong() * height.toLong()

    fun toJson(): JSONObject = JSONObject()
        .put("profile_id", profileId)
        .put("theme_id", themeId)
        .put("x", x)
        .put("y", y)
        .put("width", width)
        .put("height", height)
        .put("opacity", opacity)
        .put("radius", radius)

    companion object {
        fun parse(obj: JSONObject): CastProfile = CastProfile(
            profileId = obj.getInt("profile_id"),
            themeId = obj.optInt("theme_id", 4),
            x = obj.optInt("x", 0),
            y = obj.optInt("y", 0),
            width = obj.getInt("width"),
            height = obj.getInt("height"),
            opacity = obj.optInt("opacity", 255),
            radius = obj.optInt("radius", 0),
        )

        fun parseList(array: JSONArray): List<CastProfile> =
            (0 until array.length()).map { parse(array.getJSONObject(it)) }

        /**
         * Prefer largest even-dimension area for full-width, smallest for windowed.
         * Never assume vendor profile numbers map to a fixed geometry.
         */
        fun select(profiles: List<CastProfile>, fullWidth: Boolean): CastProfile {
            val usable = profiles.filter { it.width >= 64 && it.height >= 64 && it.width % 2 == 0 && it.height % 2 == 0 }
            require(usable.isNotEmpty()) { "no_usable_cast_profile" }
            return if (fullWidth) usable.maxBy { it.area } else usable.minBy { it.area }
        }
    }
}
