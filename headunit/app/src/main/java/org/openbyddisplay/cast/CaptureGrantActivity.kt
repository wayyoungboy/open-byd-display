package org.openbyddisplay.cast

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle

/** Each mirror session obtains a fresh user grant; never persists or reuses it. */
class CaptureGrantActivity : Activity() {
    private lateinit var options: CastOptions
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (org.openbyddisplay.vehicle.UsbSwitchStore.blocked(this)) {
            CastSessionStore.idle(this, "USB 切换结果未确认，请先刷新原请求"); finish(); return
        }
        options = runCatching { CastSettings.snapshot(this) }.getOrElse { finish(); return }
        if (options.source != "mirror") { launch(0, null); return }
        if (savedInstanceState == null) startActivityForResult(
            getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(), REQ)
    }
    private fun launch(code: Int, data: Intent?) {
        startForegroundService(Intent(this, CastForegroundService::class.java)
            .putExtra(CastForegroundService.EXTRA_OPTIONS, options.json().toString())
            .putExtra(CastForegroundService.EXTRA_RESULT_CODE, code).putExtra(CastForegroundService.EXTRA_DATA, data))
        finish()
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ) {
            if (resultCode == RESULT_OK && data != null) launch(resultCode, data)
            else { CastSessionStore.idle(this, "已取消录屏授权"); finish() }
        }
    }
    companion object {
        private const val REQ = 7101
        fun intent(context: Context, host: String): Intent {
            CastOptions.privateIpv4(host)
            CastSettings.setHost(context, host)
            return Intent(context, CaptureGrantActivity::class.java)
        }
    }
}
