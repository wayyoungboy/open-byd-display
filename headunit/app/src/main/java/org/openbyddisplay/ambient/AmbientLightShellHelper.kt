package org.openbyddisplay.ambient

import android.os.SystemClock
import java.io.File
import java.io.PrintWriter
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.Socket
import kotlin.system.exitProcess

/** This entry point is launched by our own authorized ADB key, never by an impersonated Binder. */
object AmbientLightShellHelper {
    @JvmStatic fun main(args: Array<String>) {
        var socket: Socket? = null
        var output: PrintWriter? = null
        var lamp: AmbientLampController? = null
        var native: NativeRhythmController? = null
        var lockFile: RandomAccessFile? = null
        var lock: java.nio.channels.FileLock? = null
        var failure: String? = null
        var override = false
        var sessionFinished = false
        try {
            require(args.size == 3 && args[0].toInt() in 1024..65535 && args[1].matches(Regex("[a-f0-9]{32}")))
            check(android.os.Process.myUid() == 2000) { "需要已授权的 ADB shell" }
            val kind = AmbientBackendKind.valueOf(args[2])
            socket = Socket(InetAddress.getByName("127.0.0.1"), args[0].toInt()).apply { soTimeout = 8000 }
            output = PrintWriter(socket.getOutputStream(), true)
            val input = socket.getInputStream()
            output.println("HELLO ${args[1]}")
            check(AmbientProtocol.line(input, SystemClock::elapsedRealtime, 8000) { socket.soTimeout = it } == "AUTH")
            lockFile = RandomAccessFile(File("/data/local/tmp/openbyddisplay-ambient.lock"), "rw")
            lock = lockFile.channel.tryLock() ?: error("已有灯效控制器运行")
            val vehicle = AmbientVehicle()
            if (kind == AmbientBackendKind.SOFTWARE) lamp = AmbientLampController(vehicle)
            else native = NativeRhythmController(vehicle.native())
            socket.soTimeout = 3500
            AmbientControlSession(lamp, native).run(
                { AmbientProtocol.line(input, SystemClock::elapsedRealtime, 3500) { socket.soTimeout = it } },
                { output.println(it); check(!output.checkError()) { "灯效连接断开" } })
            sessionFinished = true
        } catch (t: Throwable) {
            override = t is AmbientManualOverride
            failure = (t.cause ?: t).message ?: t.javaClass.simpleName
        } finally {
            val restored = native?.finish() ?: lamp?.finish() ?: AmbientRestoration.RESTORED
            val reply = when {
                override || restored == AmbientRestoration.OVERRIDDEN -> "OVERRIDDEN"
                restored == AmbientRestoration.UNCONFIRMED -> "ERROR UNCONFIRMED 恢复读回失败"
                failure != null -> "ERROR RESTORED ${failure.replace(Regex("[\\r\\n]"), " ").take(160)}"
                else -> "RESTORED"
            }
            if (!sessionFinished) output?.println(reply)
            try { socket?.close() } catch (_: Exception) { }
            try { lock?.release() } catch (_: Exception) { }
            try { lockFile?.close() } catch (_: Exception) { }
        }
        exitProcess(if (failure == null) 0 else 1)
    }
}
