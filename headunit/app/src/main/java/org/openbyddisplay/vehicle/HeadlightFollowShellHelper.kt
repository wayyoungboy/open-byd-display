package org.openbyddisplay.vehicle

import android.annotation.SuppressLint
import android.content.Context
import org.openbyddisplay.ambient.AmbientProtocol
import android.os.SystemClock
import java.io.File
import java.io.PrintWriter
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.Socket
import org.json.JSONObject
import kotlin.system.exitProcess

/** Reads the actual low beam. Only changes Android night mode while OEM choice remains Auto. */
object HeadlightFollowShellHelper {
    @JvmStatic fun main(args: Array<String>) {
        var socket: Socket? = null
        var out: PrintWriter? = null
        var lockFile: RandomAccessFile? = null
        var lock: java.nio.channels.FileLock? = null
        var context: Context? = null
        var night: ShellNightModeController? = null
        var nativeMode: ShellNativeModeReader? = null
        var controller: HeadlightFollowController? = null
        var failure: String? = null
        try {
            check(android.os.Process.myUid() == 2000) { "需要已授权的本机 ADB" }
            require(args.size == 2 && args[0].toInt() in 1024..65535 && args[1].matches(Regex("[a-f0-9]{32}")))
            android.os.Looper.prepareMainLooper()
            val at = Class.forName("android.app.ActivityThread")
            context = at.getMethod("getSystemContext").invoke(at.getMethod("systemMain").invoke(null)) as Context
            night = ShellNightModeController(context)
            nativeMode = ShellNativeModeReader(context)
            @SuppressLint("WrongConstant")
            val auto = context.getSystemService("auto") ?: error("车身信号服务不可用")
            val id = Class.forName("android.hardware.bydauto.BYDAutoFeatureIds").getField("LIGHT_LOW_BEAM_LIGHT").getInt(null)
            check(id != 0) { "实际近光字段不可用" }
            val get = auto.javaClass.getMethod("getInt", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            socket = Socket(InetAddress.getByName("127.0.0.1"), args[0].toInt()).apply { soTimeout = 15000 }
            out = PrintWriter(socket.getOutputStream(), true)
            val input = socket.getInputStream()
            fun read() = AmbientProtocol.line(input, SystemClock::elapsedRealtime, if (lock == null) 15000 else 3500) { socket!!.soTimeout = it }
            out.println("HELLO ${args[1]}")
            check(read() == "AUTH") { "会话校验失败" }
            lockFile = RandomAccessFile(File("/data/local/tmp/openbyddisplay-headlight-follow.lock"), "rw")
            lock = lockFile.channel.tryLock() ?: error("已有大灯联动会话运行")
            out.println("READY")
            socket.soTimeout = 3500
            val ownedNight = checkNotNull(night)
            val ownedNative = checkNotNull(nativeMode)
            controller = HeadlightFollowController(object : HeadlightIo {
                override fun read() = HeadlightSnapshot(ownedNative.read(), ownedNight.preference,
                    ownedNight.actual(), get.invoke(auto, 1001, id) as Int)
                override fun activate(mode: Int) = ownedNight.activate(mode)
                override fun naturalMode() = ownedNight.twilightMode()
            })
            while (true) {
                val command = read() ?: break
                if (command == "STOP") break
                check(command == "POLL")
                val (state, following) = controller.poll()
                out.println(JSONObject().put("native", state.native).put("lowBeam", state.beam)
                    .put("nightMode", state.preference).put("actualMode", state.actual).put("following", following))
                check(!out.checkError()) { "联动连接断开" }
            }
        } catch (t: Throwable) { failure = (t.cause ?: t).message ?: t.javaClass.simpleName }
        finally {
            val restoration = controller?.finish() ?: HeadlightRestoration.UNCHANGED
            val reason = failure?.replace(Regex("[\\r\\n]"), " ")?.take(160)
            out?.println(when (restoration) {
                HeadlightRestoration.RESTORED -> if (reason == null) "STOPPED" else "ERROR RESTORED $reason"
                HeadlightRestoration.UNCHANGED -> if (reason == null) "UNCHANGED" else "ERROR UNCHANGED $reason"
                HeadlightRestoration.YIELDED -> "YIELDED"
                HeadlightRestoration.UNCONFIRMED -> "ERROR UNCONFIRMED ${reason ?: "状态回读不可用"}"
            })
            try { nativeMode?.close() } catch (_: Exception) { }
            try { socket?.close() } catch (_: Exception) { }
            try { lock?.release() } catch (_: Exception) { }
            try { lockFile?.close() } catch (_: Exception) { }
        }
        exitProcess(if (failure == null) 0 else 1)
    }
}
