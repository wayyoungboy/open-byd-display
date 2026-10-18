package org.openbyddisplay.ambient

import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

internal sealed class AmbientCommand(val sequence: Long) {
    class Keep(sequence: Long) : AmbientCommand(sequence)
    class Frame(sequence: Long, val frame: AmbientFrame) : AmbientCommand(sequence)
    class Spectrum(sequence: Long, val bands: AmbientSpectrum) : AmbientCommand(sequence)
}

internal object AmbientProtocol {
    fun line(input: InputStream, timeMs: () -> Long, limitMs: Long, timeout: (Int) -> Unit = {}): String? {
        val deadline = timeMs() + limitMs
        val out = ByteArrayOutputStream()
        while (true) {
            val remaining = deadline - timeMs()
            check(remaining > 0) { "灯效会话超时" }
            timeout(remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            val byte = input.read()
            check(timeMs() < deadline) { "灯效会话超时" }
            if (byte < 0) { check(out.size() == 0) { "命令不完整" }; return null }
            if (byte == 10) return Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(out.toByteArray())).toString()
            require(byte >= 32 && out.size() < 1024) { "无效灯效命令" }
            out.write(byte)
        }
    }
    fun parse(line: String, previous: Long, kind: AmbientBackendKind): AmbientCommand {
        val fields = line.split(' ')
        require(fields.size >= 2)
        val sequence = fields[1].toLong()
        require(sequence > previous && sequence > 0)
        return when (fields[0]) {
            "KEEP" -> { require(fields.size == 2); AmbientCommand.Keep(sequence) }
            "FRAME" -> {
                require(fields.size == 4 && kind == AmbientBackendKind.SOFTWARE)
                AmbientCommand.Frame(sequence, AmbientFrame(fields[2].toInt(), fields[3].toInt()))
            }
            "SPECTRUM" -> {
                require(fields.size == 18 && kind == AmbientBackendKind.NATIVE)
                AmbientCommand.Spectrum(sequence, AmbientSpectrum(fields.drop(2).map { it.toInt() }.toIntArray()))
            }
            else -> error("不支持的灯效命令")
        }
    }
    fun restoration(reply: String): AmbientRestoration? = when {
        reply == "RESTORED" || reply.startsWith("ERROR RESTORED ") -> AmbientRestoration.RESTORED
        reply == "OVERRIDDEN" -> AmbientRestoration.OVERRIDDEN
        reply.startsWith("ERROR UNCONFIRMED ") -> AmbientRestoration.UNCONFIRMED
        else -> null
    }
    fun describe(reply: String): String = when (restoration(reply)) {
        AmbientRestoration.RESTORED -> if (reply == "RESTORED") "已停止，原灯光已恢复并读回确认。"
            else "灯效已停止，原灯光读回已确认：${reply.removePrefix("ERROR RESTORED ")}"
        AmbientRestoration.OVERRIDDEN -> "检测到手动调整，已让出控制并保留当前设置。"
        AmbientRestoration.UNCONFIRMED -> "恢复未确认，请核对原车灯光：${reply.removePrefix("ERROR UNCONFIRMED ")}"
        null -> "灯效响应异常：${reply.take(160)}"
    }
}
