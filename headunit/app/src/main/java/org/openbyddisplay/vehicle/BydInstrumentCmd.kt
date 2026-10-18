package org.openbyddisplay.vehicle

import org.openbyddisplay.ambient.AmbientAdb as LocalAdbShell

import android.content.Context
import android.content.pm.PackageManager
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Authorized local ADB runs our helper; the helper calls system BYD and network services. */
object BydInstrumentCmd {
    const val SCMD_USBMODE_CAST = "SCMD_USBMODE2"
    const val SCMD_USBMODE_UDISK = "SCMD_USBMODE1"
    const val PERM_INSTRUMENT_COMMON = "android.permission.BYDAUTO_INSTRUMENT_COMMON"
    private val inFlight = AtomicBoolean(false)
    enum class Status { NETWORK_CONFIGURED, NCM_DISCONNECTED, COMMAND_ACCEPTED, FAILED }

    data class Result(val ok: Boolean, val channel: String, val cmd: String, val detail: String,
                      val status: Status = if (ok) Status.COMMAND_ACCEPTED else Status.FAILED,
                      val completionKnown: Boolean = false)

    fun sendUsbModeCast(context: Context): Result = sendUsbMode(context, SCMD_USBMODE_CAST)
    fun sendUsbModeUdisk(context: Context): Result = sendUsbMode(context, SCMD_USBMODE_UDISK)

    fun sendUsbMode(context: Context, cmd: String, request: String = UUID.randomUUID().toString().replace("-", "")): Result {
        val channel = "adb-shell/BYDAutoManager"
        if (cmd !in setOf(SCMD_USBMODE_CAST, SCMD_USBMODE_UDISK))
            return Result(false, "validation", cmd, "不支持的 USB 命令", completionKnown = true)
        UsbModeEvidence.validateRequest(request)
        if (!inFlight.compareAndSet(false, true))
            return Result(false, channel, cmd, "已有 USB 模式请求正在执行，请稍候", completionKnown = true)
        try {
            val app = context.applicationContext ?: context
            val deadline = System.nanoTime() + UsbModeEvidence.REQUEST_TIMEOUT_MS * 1_000_000L
            val start = LocalAdbShell.exec(app,
                LocalAdbShell.buildUsbModeCommand(app.applicationInfo.sourceDir, cmd, request))
            if (!UsbModeEvidence.mayPollResult(start)) {
                return Result(false, channel, cmd, "无法启动 shell helper：${start.detail} ${start.output.take(200)}",
                    completionKnown = !start.commandMayBeRunning)
            }
            var last = ""
            while (System.nanoTime() < deadline) {
                Thread.sleep(400)
                val poll = LocalAdbShell.exec(app, LocalAdbShell.buildUsbModePollCommand(request))
                last = if (poll.ok) poll.output.takeLast(1000) else poll.detail
                if (!poll.ok) continue
                val completed = UsbModeEvidence.parse(poll.output, request, cmd) ?: continue
                // The caller persists the terminal result before deleting its evidence file.
                return completed
            }
            return Result(false, channel, cmd, "${UsbModeEvidence.REQUEST_TIMEOUT_MS / 1000} 秒内未获得完整结果：$last；请检测网络后重试，未据此判定切换成功")
        } catch (e: Exception) {
            return Result(false, channel, cmd, "USB 请求异常 ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            inFlight.set(false)
        }
    }

    fun hasCommonPermission(context: Context): Boolean =
        context.checkSelfPermission(PERM_INSTRUMENT_COMMON) == PackageManager.PERMISSION_GRANTED

    fun probeSdk(): String = try {
        val manager = Class.forName("android.hardware.BYDAutoManager")
        val method = manager.getMethod("setBuffer", Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, ByteArray::class.java)
        val feature = Class.forName("android.hardware.bydauto.BYDAutoFeatureIds")
            .getField("INSTRUMENT_TARGET_NEXT_PATHNAME_INFO_SET").getInt(null)
        check(feature != 0) { "仪表 USB 命令字段不可用" }
        "SDK PRESENT ${manager.name}.${method.name} feature=0x${feature.toString(16)}；切换和配网使用已授权的 shell"
    } catch (e: Exception) {
        "SDK ERROR ${e.javaClass.simpleName}: ${e.message}"
    }

    fun diagnostics(context: Context): String {
        val permissions = listOf(PERM_INSTRUMENT_COMMON,
            "android.permission.BYDAUTO_INSTRUMENT_GET", "android.permission.BYDAUTO_INSTRUMENT_SET",
            "com.byd.ditrainer.permission.CORE")
        val state = permissions.joinToString("\n") { permission ->
            val granted = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
            val protection = try {
                context.packageManager.getPermissionInfo(permission, 0).let { (it.protection or it.protectionFlags).toString() }
            } catch (_: PackageManager.NameNotFoundException) { "未定义" }
            "$permission: granted=$granted protectionLevel=$protection"
        }
        return "app uid=${android.os.Process.myUid()}\n$state\n${probeSdk()}\n" +
            "切换通道=LocalAdbShell→自有 shell helper→系统 BYD/网络管理服务；不调用第三方应用"
    }
}
