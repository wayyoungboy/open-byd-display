package org.openbyddisplay.cast

import android.app.Activity
import android.app.AlertDialog
import android.widget.*

object CastOptionsDialog {
    fun show(activity: Activity, saved: () -> Unit) {
        val options = runCatching { CastSettings.snapshot(activity) }.getOrElse { CastOptions() }
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 16, 32, 16) }
        fun field(label: String, value: String): EditText {
            content.addView(TextView(activity).apply { text = label })
            return EditText(activity).apply { setText(value); setSingleLine(true); content.addView(this) }
        }
        fun choice(label: String, values: List<String>, index: Int): Spinner {
            content.addView(TextView(activity).apply { text = label })
            return Spinner(activity).apply {
                adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, values)
                setSelection(index); content.addView(this)
            }
        }
        val source = choice("画面来源", listOf("车机屏幕镜像", "地图独立副屏", "高德飞屏版副地图", "动态标定图"), listOf("mirror", "map", "flyscreen", "pattern").indexOf(options.source))
        val layout = choice("视频窗口（仅限仪表标定范围）", listOf("标定全区域", "居中区域", "小窗口"), listOf("full", "center", "mini").indexOf(options.layout))
        val fps = field("帧率（5～30）", options.fps.toString())
        val bitrate = field("码率 Mbps（0.5～8）", (options.bitrate / 1_000_000.0).toString())
        val edge = field("黑色柔边宽度 %（0～20）", options.softEdge.toString())
        val opacity = field("窗口不透明度（1～255）", options.opacity.toString())
        val scale = choice("副屏绘制尺寸", listOf("1 倍", "2 倍后缩小"), options.renderScale - 1)
        val flip = CheckBox(activity).apply { text = "左右翻转"; isChecked = options.horizontalFlip; content.addView(this) }
        val map = field("地图包名", options.mapPackage)
        val displayName = field("飞屏版识别的副屏名称", options.virtualDisplayName)
        val port = field("自研视频服务端口", options.port.toString())
        val dialog = AlertDialog.Builder(activity).setTitle("投屏设置 · 下次启动生效")
            .setView(ScrollView(activity).apply { addView(content) }).setPositiveButton("保存", null).setNegativeButton("取消", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            runCatching {
                val updated = options.copy(source = listOf("mirror", "map", "flyscreen", "pattern")[source.selectedItemPosition],
                    layout = listOf("full", "center", "mini")[layout.selectedItemPosition], fps = fps.text.toString().toInt(),
                    bitrate = (bitrate.text.toString().toDouble() * 1_000_000).toInt(), softEdge = edge.text.toString().toInt(),
                    opacity = opacity.text.toString().toInt(), renderScale = scale.selectedItemPosition + 1,
                    horizontalFlip = flip.isChecked, mapPackage = map.text.toString().trim(), port = port.text.toString().toInt(), virtualDisplayName = displayName.text.toString().trim())
                CastSettings.saveOptions(activity, updated); saved(); dialog.dismiss()
            }.onFailure { Toast.makeText(activity, "设置未保存，请核对数值、端口与包名", Toast.LENGTH_LONG).show() }
        } }
        dialog.show()
    }
}
