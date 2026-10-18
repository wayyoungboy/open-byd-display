package org.openbyddisplay.ambient

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import org.openbyddisplay.R
import kotlin.concurrent.thread

class AmbientLightActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var state: TextView
    private lateinit var start: Button
    private lateinit var effect: Spinner
    private lateinit var brightness: Spinner
    private lateinit var interval: Spinner
    private lateinit var source: Spinner
    private lateinit var native: Switch
    private lateinit var multicolor: CheckBox
    private val refresh = object : Runnable { override fun run() { render(); handler.postDelayed(this, 500) } }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        setContentView(ScrollView(this).apply { addView(root) })
        fun text(value: String) = TextView(this).apply { text = value; textSize = 16f; setPadding(0, 12, 0, 12); root.addView(this) }
        fun button(label: String, action: () -> Unit) = Button(this).apply { text = label; root.addView(this); setOnClickListener { action() } }
        fun choices(label: String, values: List<String>): Spinner {
            text(label)
            return Spinner(this).apply {
                adapter = ArrayAdapter(this@AmbientLightActivity, android.R.layout.simple_spinner_dropdown_item, values)
                root.addView(this)
            }
        }
        text("氛围灯 · 车机车辆接口").textSize = 24f
        text(getString(R.string.project_risk_notice))
        text("普通接口提供 31 个色号、六档亮度。软件变化不是连续 RGB 或硬件平滑调光。停止后恢复启动前状态；手动调整时让出控制。请先关闭其他灯控应用。")
        val prefs = getSharedPreferences("openbyd_ambient_options", MODE_PRIVATE)
        effect = choices("灯效", listOf("动态多色：逐档变亮/变暗后换色", "音乐律动：16 频带，必要时 RMS 回退"))
        effect.setSelection(prefs.getInt("effect", 1).coerceIn(0, 1))
        brightness = choices("最高亮度（逻辑 0～5，对应原始 1～6）", (0..5).map { "$it（六档中的第 ${it + 1} 档）" })
        brightness.setSelection(prefs.getInt("brightness", 5).coerceIn(0, 5))
        interval = choices("动态模式：每档反馈确认后的停留时间", intervals.map { "$it 毫秒" })
        interval.setSelection(prefs.getInt("interval", 1).coerceIn(0, intervals.lastIndex))
        multicolor = CheckBox(this).apply { text = "音乐节拍切换颜色"; isChecked = prefs.getBoolean("multicolor", true); root.addView(this) }
        source = choices("音乐音源（不会自动切换到麦克风）", listOf("车机播放声音 · 16 频带 / RMS 回退", "车内麦克风 · 仅 RMS，需手动选择"))
        source.setSelection(prefs.getInt("source", 0).coerceIn(0, 1))
        native = Switch(this).apply {
            text = "使用原生音乐律动后端（当前平台未验证，保持关闭）"
            isEnabled = NativeRhythmProfiles.forFingerprint(Build.FINGERPRINT) != null
            isChecked = false
            root.addView(this)
        }
        text("不保存或上传音频，不会自动开启麦克风。原生后端仅接受播放混音的 16 频带；必须验证启停、状态回读及恢复，字段存在不等于已验证。")
        button("授权本机 ADB（传统 127.0.0.1:5555）") {
            state.text = "正在等待系统授权，请在车机的 USB 调试弹窗中点允许…"
            thread {
                val result = AmbientAdb.probeAuth(applicationContext, confirmWaitMs = 30_000)
                handler.post { if (!isFinishing && !isDestroyed) state.text = result.detail }
            }
        }
        text("需已开启传统 TCP adbd；本应用不会开启该服务，不支持无线配对/TLS。ADB 授权只表示连接权限，灯控还需本车接口与反馈验证。")
        start = button("开始灯效") {
            if (effect.selectedItemPosition == 1 && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 71)
            } else begin()
        }
        button("停止并恢复原灯光") { startService(Intent(this, AmbientLightService::class.java).setAction(AmbientLightService.STOP)) }
        button("核对上次中断后的恢复状态") {
            AlertDialog.Builder(this).setTitle("人工核对恢复")
                .setMessage("先停止灯效，等待至少一分钟，并在原车设置及灯条上确认状态已恢复。这是人工确认，不是自动读回。")
                .setPositiveButton("我已核对") { _, _ ->
                    runCatching { AmbientStateStore.operatorRecovered(this) }.onFailure { toast(it.message.orEmpty()) }
                    render()
                }.setNegativeButton("保留记录", null).show()
        }
        state = text("")
    }
    private fun begin() {
        runCatching { beginChecked() }.onFailure { toast("启动失败：${it.message}") }
    }
    private fun beginChecked() {
        val index = effect.selectedItemPosition
        val config = AmbientConfig(if (index == 0) AmbientEffect.DYNAMIC else AmbientEffect.MUSIC,
            maxBrightness = brightness.selectedItemPosition + 1, intervalMs = intervals[interval.selectedItemPosition],
            musicMulticolor = multicolor.isChecked,
            audioSource = if (source.selectedItemPosition == 0) AmbientAudioSource.PLAYBACK else AmbientAudioSource.MICROPHONE,
            backend = if (native.isChecked) AmbientBackendKind.NATIVE else AmbientBackendKind.SOFTWARE)
        getSharedPreferences("openbyd_ambient_options", MODE_PRIVATE).edit().putInt("effect", index)
            .putInt("brightness", brightness.selectedItemPosition).putInt("interval", interval.selectedItemPosition)
            .putInt("source", source.selectedItemPosition)
            .putBoolean("multicolor", multicolor.isChecked).apply()
        runCatching { AmbientLightService.start(this, config) }.onFailure { toast("启动失败：${it.message}") }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 71 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) begin()
    }
    private fun render() {
        state.text = AmbientLightService.status + if (AmbientStateStore.blocked(this) && !AmbientLightService.running)
            "\n存在未确认的恢复记录，已阻止重新控制。" else ""
        start.isEnabled = !AmbientLightService.running && !AmbientStateStore.blocked(this) && !AmbientLightService.audioCleanupUnknown
    }
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    override fun onResume() { super.onResume(); handler.post(refresh) }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }
    companion object { private val intervals = listOf(250L, 500L, 750L, 1000L, 2000L, 5000L) }
}
