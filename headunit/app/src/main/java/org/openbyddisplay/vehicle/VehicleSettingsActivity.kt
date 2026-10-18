package org.openbyddisplay.vehicle

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Switch
import android.widget.Toast
import org.openbyddisplay.R
import org.openbyddisplay.ambient.AmbientAdb
import kotlin.concurrent.thread

class VehicleSettingsActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var state: TextView
    private lateinit var detail: TextView
    private lateinit var parked: CheckBox
    private lateinit var nightStatus: TextView
    private val switches = mutableListOf<Button>()
    private val refresh = object : Runnable { override fun run() { render(); handler.postDelayed(this, 500) } }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        setContentView(ScrollView(this).apply { addView(root) })
        fun text(value: String) = TextView(this).apply { text = value; textSize = 16f; setPadding(0, 10, 0, 10); root.addView(this) }
        fun button(label: String, action: () -> Unit) = Button(this).apply { text = label; root.addView(this); setOnClickListener { action() } }
        text("车机接口 · USB 与诊断").textSize = 24f
        text(getString(R.string.project_risk_notice))
        text("需要匹配的仪表固件和已授权的本机 ADB。NCM 配网成功、端口可达、命令受理与仪表实际模式分别核对。自研仪表 USB 后端尚待接机适配。")
        button("授权本机 ADB") { background { AmbientAdb.probeAuth(applicationContext, confirmWaitMs = 30_000).detail } }
        button("只读检测权限、USB 驱动与网络") { background {
            val usb = UsbModeDetector.detect(applicationContext)
            "${usb.label}\n${usb.detail}\n${NetworkProbe.formatReport()}\n${BydInstrumentCmd.diagnostics(applicationContext)}"
        } }
        parked = CheckBox(this).apply { text = "已停车，并已核对仪表支持此 USB 命令"; root.addView(this) }
        switches += button("请求 USB 投屏模式并配置 NCM") { request("cast") }
        switches += button("请求切回 U 盘模式") { request("udisk") }
        button("刷新原 USB 请求（不重新切换）") { request("refresh") }
        button("人工核对 USB 模式已稳定") {
            AlertDialog.Builder(this).setTitle("人工恢复确认")
                .setMessage("仅在已停车、等待至少 90 秒，并从车机和仪表确认实际模式稳定后使用。还会通过已授权 shell 核对旧 helper 已退出；这是人工证据，不是自动确认模式。")
                .setPositiveButton("我已核对") { _, _ -> request("recovered") }
                .setNegativeButton("保留记录", null).show()
        }
        text("原生深浅设置与大灯联动").textSize = 20f
        text("原生与 Android 的手动选项优先；两者均 Auto 才按实际近光灯临时切换。停止后交回原生自然 Auto。请关闭其他模式联动应用。")
        button("解锁车机原生深浅模式设置") { background { UiModeHelper.unlockNativeSettings(applicationContext).message } }
        Switch(this).apply {
            text = "开机后保持原生设置入口已解锁"
            isChecked = UiModeHelper.keepUnlocked(this@VehicleSettingsActivity)
            root.addView(this)
            setOnCheckedChangeListener { _, value -> UiModeHelper.setKeepUnlocked(this@VehicleSettingsActivity, value) }
        }
        Switch(this).apply {
            text = "大灯联动（保存选择，开机恢复；默认关闭）"
            isChecked = HeadlightSettings.enabled(this@VehicleSettingsActivity)
            root.addView(this)
            setOnCheckedChangeListener { _, value ->
                runCatching {
                    HeadlightSettings.enabled(this@VehicleSettingsActivity, value)
                    if (value) HeadlightFollowService.start(this@VehicleSettingsActivity)
                    else startService(Intent(this@VehicleSettingsActivity, HeadlightFollowService::class.java).setAction(HeadlightFollowService.STOP))
                }.onFailure { Toast.makeText(this@VehicleSettingsActivity, "联动未启动：${it.message}", Toast.LENGTH_LONG).show() }
            }
        }
        button("停止大灯联动并交回原生设置") {
            startService(Intent(this, HeadlightFollowService::class.java).setAction(HeadlightFollowService.STOP))
        }
        button("人工核对原生 Auto 已恢复") {
            AlertDialog.Builder(this).setMessage("先停止联动并等待至少一分钟，在原车设置确认自动模式与实际主题已恢复。此操作是人工核对。")
                .setPositiveButton("我已核对") { _, _ ->
                    runCatching { HeadlightSettings.operatorRecovered(this) }.onFailure {
                        Toast.makeText(this, it.message, Toast.LENGTH_LONG).show()
                    }
                }.setNegativeButton("保留记录", null).show()
        }
        nightStatus = text("")
        state = text("")
        detail = text("").apply { setTextIsSelectable(true) }
    }
    private fun background(action: () -> String) {
        detail.text = "正在检查…"
        thread {
            val result = runCatching(action).getOrElse { "未确认：${it.message}" }
            handler.post { if (!isFinishing && !isDestroyed) detail.text = result }
        }
    }
    private fun request(mode: String) {
        runCatching { startForegroundService(Intent(this, UsbSwitchService::class.java)
            .putExtra("mode", mode).putExtra("parked", parked.isChecked).putExtra("operator_confirmed", mode == "recovered")) }
            .onFailure { Toast.makeText(this, "USB 请求未启动：${it.message}", Toast.LENGTH_LONG).show() }
    }
    private fun render() {
        state.text = UsbSwitchService.status + if (UsbSwitchStore.blocked(this)) "\nUSB 请求尚未确认；已阻止投屏、更新和再次切换。" else ""
        switches.forEach { it.isEnabled = !UsbSwitchService.busy.get() && !UsbSwitchStore.blocked(this) }
        nightStatus.text = HeadlightFollowService.status + "\n" + VehicleRestoreService.status +
            if (HeadlightSettings.blocked(this) && !HeadlightFollowService.running) "\n上次原生模式恢复未确认，已阻止重启联动。" else ""
    }
    override fun onResume() { super.onResume(); handler.post(refresh) }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }
}
