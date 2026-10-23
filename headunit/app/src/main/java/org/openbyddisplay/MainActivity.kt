package org.openbyddisplay

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.widget.EditText
import android.widget.CheckBox
import android.net.Uri
import android.widget.Button
import android.widget.TextView
import android.widget.Switch
import android.widget.Toast
import android.os.Build
import android.provider.Settings
import org.json.JSONObject
import org.openbyddisplay.cast.CaptureGrantActivity
import org.openbyddisplay.cast.CastForegroundService
import org.openbyddisplay.cast.CastPorts
import org.openbyddisplay.cast.CastSessionStore
import org.openbyddisplay.cast.CastSettings
import org.openbyddisplay.cast.VideoStreamClient
import org.openbyddisplay.cast.CastOptionsDialog
import org.openbyddisplay.cast.CastSafetyStore
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var lastRender: String? = null
    private val refresh = object : Runnable {
        override fun run() { render(); handler.postDelayed(this, 800) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_instrument_update)
        StartupController.opened(this, intent)
        findViewById<Switch>(R.id.startupEnabled).apply {
            isChecked = StartupController.enabled(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                runCatching { StartupController.setEnabled(this@MainActivity, checked) }
                    .onFailure { isChecked = StartupController.enabled(this@MainActivity) }
                render()
            }
        }
        findViewById<Button>(R.id.startupAdb).setOnClickListener { showAdbHelp() }
        findViewById<Button>(R.id.ambientOpen).setOnClickListener {
            startActivity(Intent(this, org.openbyddisplay.ambient.AmbientLightActivity::class.java))
        }
        findViewById<Button>(R.id.vehicleOpen).setOnClickListener {
            startActivity(Intent(this, org.openbyddisplay.vehicle.VehicleSettingsActivity::class.java))
        }
        findViewById<Button>(R.id.customSupport).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/wayyoungboy")))
        }
        val hostField = findViewById<EditText>(R.id.updateHost)
        hostField.setText(CastSettings.host(this).ifBlank {
            getPreferences(MODE_PRIVATE).getString("host", CastPorts.DEFAULT_HOST)
        })
        bindCastSwitches()
        findViewById<Button>(R.id.castStart).setOnClickListener {
            val host = hostField.text.toString().trim()
            CastSettings.setHost(this, host)
            getPreferences(MODE_PRIVATE).edit().putString("host", host).apply()
            runCatching { startActivity(CaptureGrantActivity.intent(this, host)) }
                .onFailure { Toast.makeText(this, "请填写有效的私有 IPv4 仪表地址", Toast.LENGTH_LONG).show() }
        }
        findViewById<Button>(R.id.castStop).setOnClickListener {
            startService(CastForegroundService.stopIntent(this))
        }
        findViewById<Button>(R.id.castProbe).setOnClickListener { probeCast() }
        findViewById<Button>(R.id.castOptions).setOnClickListener { CastOptionsDialog.show(this) { bindCastSwitches() } }
        findViewById<Button>(R.id.castLrc).setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), 607)
        }
        findViewById<Button>(R.id.castRecovery).setOnClickListener {
            if (CastSafetyStore.localReleaseUnknown) {
                Toast.makeText(this, "本机图像资源清理未确认，请先结束并重启 App，再核对仪表", Toast.LENGTH_LONG).show()
            } else AlertDialog.Builder(this).setTitle("人工核对恢复")
                .setMessage("仅在已停止投屏、重启仪表服务或仪表，并确认原车显示恢复后清除本机阻塞记录。这是人工确认，不是仪表自动回读。")
                .setPositiveButton("我已核对恢复") { _, _ ->
                    runCatching { CastSafetyStore.operatorRecovered(this) }.onFailure {
                        Toast.makeText(this, "仍有投屏任务或记录无法保存", Toast.LENGTH_LONG).show()
                    }
                    lastRender = null; render()
                }.setNegativeButton("保留记录", null).show()
        }
        findViewById<Button>(R.id.castMediaAccess).setOnClickListener {
            runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                .onFailure { Toast.makeText(this, "车机未提供通知使用权设置", Toast.LENGTH_LONG).show() }
        }
        findViewById<Button>(R.id.castMapAdb).setOnClickListener {
            val id = CastSessionStore.read(this).optInt("display_id", -1)
            if (id > 0) {
                val options = CastSettings.snapshot(this)
                val pkg = options.mapPackage
                val launchId = if (options.source == "flyscreen") 0 else id
                val command = "adb shell am start --display $launchId -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p $pkg"
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("地图副屏", command))
                AlertDialog.Builder(this).setMessage("已复制副屏启动命令。需在已授权电脑上执行：\n\n$command").setPositiveButton("关闭", null).show()
            } else Toast.makeText(this, "先启动地图独立副屏模式", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.updateGitHub).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/wayyoungboy/open-byd-display/releases")))
        }
        findViewById<Button>(R.id.updatePick).setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), 605)
        }
        listOf(R.id.updateInfo to "info", R.id.updateUpload to "upload", R.id.updateRefresh to "refresh",
            R.id.updateApply to "apply", R.id.updateCancel to "cancel").forEach { (id, operation) ->
            findViewById<Button>(id).setOnClickListener { run(operation) }
        }
    }

    private fun bindCastSwitches() {
        findViewById<Switch>(R.id.castFullWidth).apply {
            setOnCheckedChangeListener(null)
            isChecked = CastSettings.fullWidth(this@MainActivity)
            setOnCheckedChangeListener { _, v -> CastSettings.setFullWidth(this@MainActivity, v) }
        }
        findViewById<Switch>(R.id.castSoftEdge).apply {
            setOnCheckedChangeListener(null)
            isChecked = CastSettings.softEdgeMask(this@MainActivity)
            setOnCheckedChangeListener { _, v -> CastSettings.setSoftEdgeMask(this@MainActivity, v) }
        }
        findViewById<Switch>(R.id.castLyrics).apply {
            setOnCheckedChangeListener(null)
            isChecked = CastSettings.lyricsEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, v -> CastSettings.setLyricsEnabled(this@MainActivity, v) }
        }
        findViewById<Switch>(R.id.castNav).apply {
            setOnCheckedChangeListener(null)
            isChecked = CastSettings.navEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, v -> CastSettings.setNavEnabled(this@MainActivity, v) }
        }
        findViewById<Switch>(R.id.castAutoReconnect).apply {
            setOnCheckedChangeListener(null)
            isChecked = CastSettings.autoReconnect(this@MainActivity)
            setOnCheckedChangeListener { _, v -> CastSettings.setAutoReconnect(this@MainActivity, v) }
        }
    }

    private fun probeCast() {
        val host = findViewById<EditText>(R.id.updateHost).text.toString().trim()
        CastSettings.setHost(this, host)
        findViewById<TextView>(R.id.castStatus).text = "探测中…"
        thread {
            val text = runCatching {
                val options = CastSettings.snapshot(this).copy(host = host)
                VideoStreamClient(options).use { client ->
                    client.probe()
                    val caps = checkNotNull(client.capabilities())
                    "自研视频服务已响应\n" + caps.summarize() +
                        "\n持续推流、心跳和窗口配置可用。"
                }
            }.getOrElse { "探测失败：${it.message}" }
            handler.post { findViewById<TextView>(R.id.castStatus).text = text }
        }
    }

    private fun run(operation: String, uri: String? = null, name: String? = null) {
        val host = findViewById<EditText>(R.id.updateHost).text.toString().trim()
        getPreferences(MODE_PRIVATE).edit().putString("host", host).apply()
        CastSettings.setHost(this, host)
        val command = Intent(this, InstrumentUpdateService::class.java).putExtra("operation", operation)
            .putExtra("host", host)
            .putExtra("uri", uri).putExtra("name", name)
            .putExtra("operator_parked", findViewById<CheckBox>(R.id.updateParked).isChecked)
        startForegroundService(command)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 607 && resultCode == RESULT_OK) {
            val selected = data?.data ?: return
            thread {
                val result = runCatching {
                    var name = ""
                    contentResolver.query(selected, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) name = it.getString(0).orEmpty()
                    }
                    require(name.endsWith(".lrc", true))
                    val bytes = contentResolver.openInputStream(selected)!!.use { input -> java.io.ByteArrayOutputStream().also { out ->
                        val buffer = ByteArray(4096)
                        while (out.size() <= 262144) {
                            val count = input.read(buffer, 0, minOf(buffer.size, 262145 - out.size()))
                            if (count < 0) break
                            if (count == 0) error("文件未返回数据")
                            out.write(buffer, 0, count)
                        }
                    }.toByteArray() }
                    require(bytes.size <= 262144)
                    val lrc = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
                    require(org.openbyddisplay.cast.MusicOverlayProtocol.parseLrc(lrc).isNotEmpty())
                    check(getSharedPreferences("openbyd_cast", MODE_PRIVATE).edit()
                        .putString("lyric_title", name.dropLast(4)).putString("lrc", lrc).commit())
                    "已导入歌词，仅在歌曲标题与 LRC 文件名一致时显示"
                }.getOrElse { "歌词导入失败，请选择不超过 256 KiB 的有效 LRC 文件" }
                handler.post { Toast.makeText(this, result, Toast.LENGTH_LONG).show() }
            }
            return
        }
        if (requestCode != 605 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        var name = ""
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) name = it.getString(0).orEmpty()
        }
        run("prepare", uri.toString(), name)
    }

    private fun render() {
        val state = InstrumentUpdateStore.read(this)
        val cast = CastSessionStore.read(this)
        val busy = InstrumentUpdateService.busy.get()
        val startupStatus = StartupController.status(this)
        val cleanupBlocked = CastSafetyStore.blocked(this)
        val usbBlocked = org.openbyddisplay.vehicle.UsbSwitchStore.blocked(this) || org.openbyddisplay.vehicle.UsbSwitchService.busy.get()
        val signature = state.toString() + busy + startupStatus + cast.toString() + cleanupBlocked + usbBlocked +
            findViewById<CheckBox>(R.id.updateParked).isChecked
        if (lastRender == signature) return
        lastRender = signature
        findViewById<TextView>(R.id.startupStatus).text = startupStatus
        findViewById<TextView>(R.id.castStatus).text = buildString {
            append(cast.optString("message", "投屏空闲"))
            if (cast.has("profile_id")) append("\nprofile=").append(cast.optInt("profile_id"))
            if (cast.has("width")) append(" ").append(cast.optInt("width")).append('x').append(cast.optInt("height"))
            if (cast.has("display_id")) append("\n副屏 ID：").append(cast.optInt("display_id"))
            if (cast.has("host")) append("\nhost=").append(cast.optString("host"))
        }
        val casting = CastSessionStore.busy
        findViewById<Button>(R.id.castStart).isEnabled = !casting && !busy && !usbBlocked && InstrumentUpdateStore.canCast(state, findViewById<EditText>(R.id.updateHost).text.toString().trim())
        listOf(R.id.castOptions, R.id.castProbe).forEach { findViewById<Button>(it).isEnabled = !casting && !busy }
        listOf(R.id.castFullWidth, R.id.castSoftEdge, R.id.castLyrics, R.id.castNav, R.id.castAutoReconnect).forEach { findViewById<Switch>(it).isEnabled = !casting }
        findViewById<Button>(R.id.castStop).isEnabled = casting
        findViewById<Button>(R.id.castRecovery).apply { visibility = if (cleanupBlocked) android.view.View.VISIBLE else android.view.View.GONE; isEnabled = !casting && !busy }
        val remote = state.optJSONObject("remote")
        findViewById<TextView>(R.id.updateStatus).text = buildString {
            append(state.optString("message", "先选择与当前仪表匹配的更新包"))
            if (state.has("name")) append("\n\n包：").append(state.optString("name")).append("\n大小：").append(state.optLong("bytes") / 1024).append(" KiB")
            if (state.has("target")) append("\n车型：").append(if (state.optString("target").startsWith("song-")) "21 款宋 PLUS DM-i" else "21 款唐 PLUS DM-i")
            if (state.has("native_version")) append("\n自研服务版本：").append(state.optString("native_version"))
            if (state.has("sha256")) append("\nSHA256：").append(state.optString("sha256"))
            if (state.has("update_id")) append("\n任务：").append(state.optString("update_id"))
            if (busy) append("\n正在处理，请保持连接")
        }
        findViewById<Button>(R.id.updatePick).isEnabled = !busy && !casting && InstrumentUpdateStore.canReplace(state)
        findViewById<Button>(R.id.updateInfo).isEnabled = !busy && !casting
        findViewById<Button>(R.id.updateUpload).isEnabled = !busy && !casting && !usbBlocked && !cleanupBlocked && state.has("sha256") && !state.has("update_id")
        findViewById<Button>(R.id.updateRefresh).isEnabled = !busy && state.has("update_id")
        findViewById<Button>(R.id.updateApply).isEnabled = !busy && !casting && !usbBlocked && !cleanupBlocked && !state.optBoolean("apply_attempted") &&
            findViewById<CheckBox>(R.id.updateParked).isChecked && remote != null && InstrumentUpdateProtocol.canApply(remote)
        findViewById<Button>(R.id.updateCancel).isEnabled = !casting && state.has("update_id") && remote?.optString("stage") !in setOf("installing", "applying", "awaiting_confirmation", "handed_off", "applied", "recovery_required")
    }

    private fun showAdbHelp() {
        val command = "adb shell appops set $packageName SYSTEM_ALERT_WINDOW allow"
        val message = "Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}\n" +
            "包名：$packageName\n后台打开权限：${if (Settings.canDrawOverlays(this)) "已允许" else "未允许"}\n\n" +
            "Android 10及以上开机打开页面需要系统允许后台启动。可打开权限设置，或在已连接的电脑执行：\n\n$command\n\n" +
            "也可从项目 headunit/tools/adb_setup.py 读取权限和网络诊断。配置完成后开启开机自启，并重启车机验证。自启只打开 App，更新包仍需手动上传和安装。"
        val dialog = AlertDialog.Builder(this).setTitle("ADB 辅助配置").setMessage(message)
            .setPositiveButton("打开权限设置") { _, _ ->
                runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
                    .onFailure { Toast.makeText(this, "当前车机未提供此设置，请使用电脑 ADB 配置", Toast.LENGTH_LONG).show() }
            }.setNeutralButton("复制命令") { _, _ ->
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("开机自启权限", command))
                Toast.makeText(this, "命令已复制", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("关闭", null).create()
        dialog.show()
        dialog.findViewById<TextView>(android.R.id.message)?.setTextIsSelectable(true)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); StartupController.opened(this, intent) }

    override fun onResume() { super.onResume(); handler.post(refresh) }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }
}
