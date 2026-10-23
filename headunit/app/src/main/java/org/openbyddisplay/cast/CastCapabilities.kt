package org.openbyddisplay.cast

import org.json.JSONObject

/**
 * Receiver capabilities from INFO/ready. Fullscreen that hides OEM gauges needs
 * HMI page/mask control on the instrument; this App only fills the calibrated video rect.
 */
data class CastCapabilities(
    val bounds: CastRect,
    val physicalWidth: Int? = null,
    val physicalHeight: Int? = null,
    val displayId: Int? = null,
    val hmiControl: Boolean = false,
    val nativeOverlay: Boolean = false,
) {
    /** True when the calibrated rect covers the entire reported physical display. */
    val coversPhysical: Boolean
        get() {
            val pw = physicalWidth ?: return false
            val ph = physicalHeight ?: return false
            return bounds.x == 0 && bounds.y == 0 && bounds.width == pw && bounds.height == ph
        }

    fun summarize(): String {
        val physical = when {
            physicalWidth != null && physicalHeight != null -> "${physicalWidth}×${physicalHeight}"
            else -> "未报告"
        }
        val cover = when {
            physicalWidth == null || physicalHeight == null -> "无法对比"
            coversPhysical -> "是"
            else -> "否（周围原厂 HMI 会露出来）"
        }
        return buildString {
            append("标定范围：").append(bounds.x).append(',').append(bounds.y).append(' ')
            append(bounds.width).append('×').append(bounds.height)
            append("\n物理显示：").append(physical)
            append("\n标定是否铺满物理屏：").append(cover)
            append("\nHMI 页面/遮罩控制：").append(if (hmiControl) "已接入" else "未接入")
            append("\n独立透明叠加：").append(if (nativeOverlay) "已接入" else "未接入")
            if (displayId != null) append("\ndisplay_id：").append(displayId)
            append('\n').append(fullscreenNote())
        }
    }

    fun fullscreenNote(wantFullLayout: Boolean = true): String {
        if (!wantFullLayout) {
            return "当前为小窗/居中布局，只会使用标定范围的一部分。"
        }
        return when {
            !hmiControl && !coversPhysical ->
                "「填满标定区域」不会隐藏原厂车速/电量等控件。当前标定小于物理屏，且仪表未接入 HMI 页面/遮罩，所以中间出图、四周仍是原厂界面。唐版熊猫全屏需要最大面积 profile、info 页切换、fullscreen3 遮罩与页面锁；本项目尚未实现这些仪表端能力。"
            !hmiControl ->
                "「填满标定区域」只铺满视频窗口，不会切换原厂 HMI 页面或下发全屏遮罩。车速/档位/电量等控件仍可能叠在画面上。隐藏这些控件需要仪表端 HMI 控制（本接收器报告 hmi_control=false）。"
            !coversPhysical ->
                "HMI 控制已宣告，但当前标定未铺满物理屏；请在仪表侧改用覆盖全屏的 profile/标定后再试。"
            else ->
                "接收器宣告已具备 HMI 控制且标定铺满物理屏；实际是否隐藏原厂控件仍需实车确认。"
        }
    }

    companion object {
        fun parse(obj: JSONObject): CastCapabilities {
            val a = obj.getJSONArray("bounds")
            require(a.length() == 4)
            val values = (0..3).map { i ->
                VideoStreamClient.integerValue(a.get(i)).also { require(it in 0..1920) }.toInt()
            }
            val bounds = CastRect(values[0], values[1], values[2], values[3])
            fun optionalSize(field: String, max: Int): Int? {
                if (!obj.has(field) || obj.isNull(field)) return null
                val v = VideoStreamClient.integer(obj, field)
                require(v in 2..max)
                return v.toInt()
            }
            val display = if (!obj.has("display_id") || obj.isNull("display_id")) null
            else VideoStreamClient.integer(obj, "display_id").also { require(it in 0..16) }.toInt()
            return CastCapabilities(
                bounds = bounds,
                physicalWidth = optionalSize("physical_width", 1920),
                physicalHeight = optionalSize("physical_height", 1080),
                displayId = display,
                hmiControl = obj.optBoolean("hmi_control", false),
                nativeOverlay = obj.optBoolean("native_overlay", false),
            )
        }
    }
}
