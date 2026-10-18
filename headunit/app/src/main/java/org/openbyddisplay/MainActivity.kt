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
import org.openbyddisplay.cast.ClusterJsonClient
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
        val hostField = findViewById<EditText>(R.id.updateHost)
        hostField.setText(CastSettings.host(this).ifBlank {
            getPreferences(MODE_PRIVATE).getString("host", CastPorts.DEFAULT_HOST)
        })
        bindCastSwitches()
        findViewById<Button>(R.id.castStart).setOnClickListener {
            val host = hostField.text.toString().trim()
            CastSettings.setHost(this, host)
            getPreferences(MODE_PRIVATE).edit().putString("host", host).apply()
            startActivity(CaptureGrantActivity.intent(this, host))
        }
        findViewById<Button>(R.id.castStop).setOnClickListener {
            startService(CastForegroundService.stopIntent(this))
        }
        findViewById<Button>(R.id.castProbe).setOnClickListener { probeCast() }
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
            isChecked = CastSettings.fullWidth(this@MainActivity)
            setOnCheckedChangeListener { _, v -> CastSettings.setFullWidth(this@MainActivity, v) }
        }
        findViewById<Switch>(R.id.castSoftEdge).apply {
            isChecked = CastSettings.softEdgeMask(this@MainActivity)
            setOnCheckedChangeListener { _, v -> CastSettings.setSoftEdgeMask(this@MainActivity, v) }
        }
        findViewById<Switch>(R.id.castLyrics).apply {
            isChecked = CastSettings.lyricsEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, v -> CastSettings.setLyricsEnabled(this@MainActivity, v) }
        }
        findViewById<Switch>(R.id.castNav).apply {
            isChecked = CastSettings.navEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, v -> CastSettings.setNavEnabled(this@MainActivity, v) }
        }
        findViewById<Switch>(R.id.castAutoReconnect).apply {
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
                val client = ClusterJsonClient(host)
                val version = runCatching { client.version() }.getOrElse { JSONObject().put("error", it.message) }
                val status = runCatching { client.castStatus() }.getOrElse { JSONObject().put("error", it.message) }
                val profiles = runCatching { client.profileGet() }.getOrElse { JSONObject().put("error", it.message) }
                "version=$version\ncast.status=$status\ncast.profile.get=$profiles"
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
        val signature = state.toString() + busy + startupStatus + cast.toString() +
            findViewById<CheckBox>(R.id.updateParked).isChecked
        if (lastRender == signature) return
        lastRender = signature
        findViewById<TextView>(R.id.startupStatus).text = startupStatus
        findViewById<TextView>(R.id.castStatus).text = buildString {
            append(cast.optString("message", "投屏空闲"))
            if (cast.has("profile_id")) append("\nprofile=").append(cast.optInt("profile_id"))
            if (cast.has("width")) append(" ").append(cast.optInt("width")).append('x').append(cast.optInt("height"))
            if (cast.has("host")) append("\nhost=").append(cast.optString("host"))
        }
        val casting = cast.optBoolean("target_casting", false) || CastSessionStore.busy
        findViewById<Button>(R.id.castStart).isEnabled = !casting
        findViewById<Button>(R.id.castStop).isEnabled = casting
        val remote = state.optJSONObject("remote")
        findViewById<TextView>(R.id.updateStatus).text = buildString {
            append(state.optString("message", "先选择与当前仪表匹配的更新包"))
            if (state.has("name")) append("\n\n包：").append(state.optString("name")).append("\n大小：").append(state.optLong("bytes") / 1024).append(" KiB")
            if (state.has("sha256")) append("\nSHA256：").append(state.optString("sha256"))
            if (state.has("update_id")) append("\n任务：").append(state.optString("update_id"))
            if (busy) append("\n正在处理，请保持连接")
        }
        findViewById<Button>(R.id.updatePick).isEnabled = !busy && InstrumentUpdateStore.canReplace(state)
        findViewById<Button>(R.id.updateInfo).isEnabled = !busy
        findViewById<Button>(R.id.updateUpload).isEnabled = !busy && state.has("sha256") && !state.has("update_id")
        findViewById<Button>(R.id.updateRefresh).isEnabled = !busy && state.has("update_id")
        findViewById<Button>(R.id.updateApply).isEnabled = !busy && !state.optBoolean("apply_attempted") &&
            findViewById<CheckBox>(R.id.updateParked).isChecked && remote != null && InstrumentUpdateProtocol.canApply(remote)
        findViewById<Button>(R.id.updateCancel).isEnabled = state.has("update_id") && remote?.optString("stage") !in setOf("installing", "handed_off", "applied", "recovery_required")
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
