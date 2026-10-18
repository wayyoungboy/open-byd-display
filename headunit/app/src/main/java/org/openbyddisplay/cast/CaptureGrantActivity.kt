package org.openbyddisplay.cast

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle

/** Requests MediaProjection consent, then starts [CastForegroundService]. */
class CaptureGrantActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mgr.createScreenCaptureIntent(), REQ)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ && resultCode == RESULT_OK && data != null) {
            val host = intent.getStringExtra("host") ?: CastSettings.host(this)
            CastSettings.setHost(this, host)
            startForegroundService(
                Intent(this, CastForegroundService::class.java)
                    .putExtra(CastForegroundService.EXTRA_RESULT_CODE, resultCode)
                    .putExtra(CastForegroundService.EXTRA_DATA, data)
                    .putExtra(CastForegroundService.EXTRA_HOST, host)
                    .putExtra(CastForegroundService.EXTRA_FULL_WIDTH, CastSettings.fullWidth(this)),
            )
        } else {
            CastSessionStore.idle(this, "用户取消了录屏授权")
        }
        finish()
    }

    companion object {
        private const val REQ = 7101
        fun intent(context: Context, host: String): Intent =
            Intent(context, CaptureGrantActivity::class.java).putExtra("host", host)
    }
}
