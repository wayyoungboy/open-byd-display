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
    init {
        require(profileId in 1..255 && themeId in 0..4 && radius == 0 && opacity in 0..255)
        CastRect(x, y, width, height)
    }
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
            profileId = VideoStreamClient.integer(obj, "profile_id").toInt(),
            themeId = obj.optInt("theme_id", 4),
            x = obj.optInt("x", 0),
            y = obj.optInt("y", 0),
            width = VideoStreamClient.integer(obj, "width").also { require(it in 2..1920) }.toInt(),
            height = VideoStreamClient.integer(obj, "height").also { require(it in 2..1080) }.toInt(),
            opacity = obj.optInt("opacity", 255),
            radius = obj.optInt("radius", 0),
        )

        fun parseList(array: JSONArray): List<CastProfile> {
            require(array.length() in 1..16)
            val profiles = (0 until array.length()).map { parse(array.getJSONObject(it)) }
            require(profiles.map { it.profileId }.toSet().size == profiles.size)
            return profiles
        }

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
