package org.openbyddisplay

import android.app.Activity
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

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() { render(); handler.postDelayed(this, 800) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_instrument_update)
        findViewById<EditText>(R.id.updateHost).setText(getPreferences(MODE_PRIVATE).getString("host", "192.168.77.1"))
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

    private fun run(operation: String, uri: String? = null, name: String? = null) {
        val host = findViewById<EditText>(R.id.updateHost).text.toString().trim()
        getPreferences(MODE_PRIVATE).edit().putString("host", host).apply()
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
        val busy = InstrumentUpdateService.busy.get()
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

    override fun onResume() { super.onResume(); handler.post(refresh) }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }
}
