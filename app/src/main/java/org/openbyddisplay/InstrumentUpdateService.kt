package org.openbyddisplay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Never automatically uploads, applies, or retries a mutation. A lost result stays unknown. */
class InstrumentUpdateService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var client: InstrumentUpdateClient? = null
    private val cancelRequested = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("instrument_update", "仪表更新", NotificationManager.IMPORTANCE_LOW))
        startForeground(604, Notification.Builder(this, "instrument_update").setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("仪表更新").setContentText("正在处理更新任务，请保持连接").build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.getStringExtra("operation") ?: run { stopSelf(startId); return START_NOT_STICKY }
        if (!busy.compareAndSet(false, true)) {
            if (action == "cancel") { cancelRequested.set(true); client?.close() }
            return START_NOT_STICKY
        }
        cancelRequested.set(false)
        val host = intent.getStringExtra("host").orEmpty()
        val uri = intent.getStringExtra("uri")
        val name = intent.getStringExtra("name").orEmpty()
        val operatorParked = intent.getBooleanExtra("operator_parked", false)
        worker.execute {
            try {
                require(host.isNotBlank()) { "请先填写仪表地址" }
                val state = InstrumentUpdateStore.read(this)
                check(InstrumentUpdateStore.canReplace(state) || state.optString("host") == host) {
                    "已有另一仪表的未结束任务，请先核对原仪表结果"
                }
                when (action) {
                    "prepare" -> prepare(state, uri ?: error("未选择文件"), name, host)
                    "info" -> {
                        val reply = remote(host) { it.command("update.info") }
                        InstrumentUpdateProtocol.capability(reply)
                        save(state.put("capability", reply).put("message", "已读取仪表更新能力"))
                    }
                    "upload" -> upload(state, host)
                    "refresh" -> status(state, host)
                    "apply" -> apply(state, host, operatorParked)
                    "cancel" -> cancel(state, host)
                    else -> error("未知更新操作")
                }
            } catch (_: Exception) {
                val state = InstrumentUpdateStore.read(this)
                runCatching { save(state.put("message", "操作未确认或预检未通过。保留当前任务；请检查包类型、仪表连接并刷新状态，勿重复提交安装。")) }
            } finally {
                if (cancelRequested.getAndSet(false)) runCatching {
                    cancel(InstrumentUpdateStore.read(this), host)
                }
                Handler(Looper.getMainLooper()).post {
                    busy.set(false)
                    stopForeground(true)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun save(state: JSONObject) = InstrumentUpdateStore.write(this, state)
    private fun <T> remote(host: String, block: (InstrumentUpdateClient) -> T): T {
        check(!cancelRequested.get()) { "操作已取消" }
        val connection = InstrumentUpdateClient(host)
        client = connection
        return try {
            check(!cancelRequested.get()) { "操作已取消" }
            block(connection)
        } finally { connection.close(); client = null }
    }

    private fun prepare(old: JSONObject, uri: String, name: String, host: String) {
        check(InstrumentUpdateStore.canReplace(old)) { "请先核对或清除已有更新任务" }
        val kind = InstrumentUpdatePackage.kind(name)
        val part = File(filesDir, "instrument-update.part")
        try {
            contentResolver.openInputStream(Uri.parse(uri))!!.use { input ->
                part.outputStream().use { output ->
                    var total = 0L
                    val buffer = ByteArray(65536)
                    while (true) {
                        val n = input.read(buffer); if (n < 0) break
                        total += n; require(total <= kind.maxBytes) { "文件过大" }; output.write(buffer, 0, n)
                    }
                }
            }
            val checked = InstrumentUpdatePackage.inspect(part, name)
            Files.move(part.toPath(), File(filesDir, "instrument-update.package").toPath(), StandardCopyOption.REPLACE_EXISTING)
            save(JSONObject().put("host", host).put("name", name.take(256)).put("kind", checked.kind.wire)
                .put("bytes", checked.bytes).put("sha256", checked.sha256).put("message", "本机容器检查通过；签名和车型适配仍由仪表核验"))
        } finally { part.delete() }
    }

    private fun upload(state: JSONObject, host: String) {
        check(state.optString("update_id").isEmpty()) { "任务已创建，请刷新状态，不能重复上传" }
        val kind = InstrumentUpdateProtocol.Kind.parse(state.getString("kind"))
        val file = File(filesDir, "instrument-update.package")
        check(file.length() == state.getLong("bytes") && InstrumentUpdatePackage.digest(file) == state.getString("sha256")) { "更新包已变化" }
        val info = remote(host) { it.command("update.info") }
        val capability = InstrumentUpdateProtocol.capability(info)
        check(capability.allows(kind) && file.length() <= capability.limit(kind)) { "仪表不接受此类更新" }
        val cast = remote(host) { it.castStatus() }
        check(cast.opt("ok") == true && !cast.optBoolean("target_casting", true) &&
            cast.optString("state") == "idle") { "请先停止投屏，并确认仪表空闲" }
        val id = InstrumentUpdateProtocol.id()
        save(state.put("host", host).put("update_id", id).put("capability", info).put("message", "正在传输更新包"))
        var lastSaved = 0L
        val reply = remote(host) { connection -> connection.upload(file, id, kind) { sent, total ->
            if (sent == total || sent - lastSaved >= 1024 * 1024) {
                lastSaved = sent
                save(state.put("sent", sent).put("message", "正在传输：${sent * 100 / total}%"))
            }
        } }
        val confirmed = InstrumentUpdateProtocol.status(reply, id, kind)
        save(state.put("remote", confirmed).put("message", InstrumentUpdateProtocol.summary(confirmed)))
    }

    private fun status(state: JSONObject, host: String) {
        val id = state.getString("update_id")
        val reply = remote(host) { it.command("update.status", id) }
        if (reply.opt("ok") == false && reply.optString("error") == "update_not_found" && !state.optBoolean("apply_attempted")) {
            save(state.put("message", "仪表未创建或未保留本次上传任务。可点击清除暂存包，解除本机记录后重新选择。"))
            return
        }
        val checked = InstrumentUpdateProtocol.status(reply, id, InstrumentUpdateProtocol.Kind.parse(state.getString("kind")))
        save(state.put("remote", checked).put("message", InstrumentUpdateProtocol.summary(checked)))
    }

    private fun apply(state: JSONObject, host: String, operatorParked: Boolean) {
        check(operatorParked) { "请先确认已停车并核对车型" }
        check(!state.optBoolean("apply_attempted")) { "安装已提交，先刷新结果" }
        val id = state.getString("update_id")
        val kind = InstrumentUpdateProtocol.Kind.parse(state.getString("kind"))
        val info = remote(host) { it.command("update.info") }
        check(InstrumentUpdateProtocol.capability(info).allows(kind)) { "仪表不能执行此更新" }
        val checked = InstrumentUpdateProtocol.status(remote(host) { it.command("update.status", id) }, id, kind)
        check(InstrumentUpdateProtocol.canApply(checked)) { "更新包尚未就绪" }
        // Persist intent before sending; after any uncertain reply never auto-submit again.
        save(state.put("apply_attempted", true).put("message", "已提交安装请求；结果未确认前请保持供电"))
        val reply = remote(host) { it.apply(id, kind, operatorParked) }
        val confirmed = InstrumentUpdateProtocol.status(reply, id, kind)
        save(state.put("remote", confirmed).put("message", InstrumentUpdateProtocol.summary(confirmed)))
    }

    private fun cancel(state: JSONObject, host: String) {
        val id = state.getString("update_id")
        val reply = remote(host) { it.command("update.cancel", id) }
        val kind = InstrumentUpdateProtocol.Kind.parse(state.getString("kind"))
        val confirmed = if (reply.optString("error") == "update_not_found")
            InstrumentUpdateProtocol.clearAbsentUnapplied(reply, id, kind, state.optBoolean("apply_attempted"))
            else InstrumentUpdateProtocol.status(reply, id, kind)
        save(state.put("remote", confirmed).put("message", InstrumentUpdateProtocol.summary(confirmed)))
    }

    override fun onDestroy() { client?.close(); worker.shutdown(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object { val busy = AtomicBoolean(false) }
}
