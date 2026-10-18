package org.openbyddisplay.cast

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** Normalizes complete MediaCodec access units; never guesses a malformed framing. */
class AvcAccessUnits {
    enum class Framing { ANNEX_B, AVCC4 }
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var waitingKey = true
    data class Unit(val bytes: ByteArray, val key: Boolean)
    fun reconnect() { waitingKey = true }
    fun configuration(bytes: ByteArray, framing: Framing = Framing.ANNEX_B) { parse(bytes, framing).forEach(::remember) }
    fun frame(bytes: ByteArray, framing: Framing = Framing.ANNEX_B): Unit? {
        val units = parse(bytes, framing)
        units.forEach(::remember)
        val types = units.map { it[0].toInt() and 31 }
        val key = 5 in types
        if (!key && 1 !in types) return null
        if (waitingKey && !key) return null
        val out = ByteArrayOutputStream()
        if (key) {
            val a = sps ?: return null
            val b = pps ?: return null
            out.write(START); out.write(a); out.write(START); out.write(b)
        }
        units.filter { (it[0].toInt() and 31) !in setOf(7, 8) }.forEach { out.write(START); out.write(it) }
        require(out.size() in 1..MAX) { "视频帧超过 2 MiB" }
        waitingKey = false
        return Unit(out.toByteArray(), key)
    }
    private fun remember(nal: ByteArray) {
        when (nal[0].toInt() and 31) { 7 -> sps = nal; 8 -> pps = nal }
    }
    companion object {
        const val MAX = 2 * 1024 * 1024
        private val START = byteArrayOf(0, 0, 0, 1)
        fun bytes(buffer: ByteBuffer): ByteArray = buffer.duplicate().let { b -> ByteArray(b.remaining()).also(b::get) }
        fun parse(data: ByteArray, framing: Framing = Framing.ANNEX_B): List<ByteArray> {
            require(data.size in 1..MAX)
            fun start(i: Int): Int = if (i + 3 <= data.size && data[i] == 0.toByte() && data[i+1] == 0.toByte()) {
                if (data[i+2] == 1.toByte()) 3
                else if (i + 4 <= data.size && data[i+2] == 0.toByte() && data[i+3] == 1.toByte()) 4 else 0
            } else 0
            val result = mutableListOf<ByteArray>()
            if (framing == Framing.ANNEX_B) {
                require(start(0) > 0) { "编码器未提供 Annex-B；不能自动猜测 AVCC" }
                var offset = 0
                while (offset < data.size) {
                    val prefix = start(offset); require(prefix > 0)
                    val begin = offset + prefix
                    offset = begin
                    while (offset < data.size && start(offset) == 0) offset++
                    require(offset > begin)
                    result += data.copyOfRange(begin, offset)
                }
            } else {
                var offset = 0
                while (offset < data.size) {
                    require(data.size - offset >= 4)
                    val n = ByteBuffer.wrap(data, offset, 4).int
                    offset += 4
                    require(n > 0 && n <= data.size - offset)
                    result += data.copyOfRange(offset, offset + n); offset += n
                }
            }
            require(result.size <= 1024)
            result.forEach { nal ->
                val header = nal[0].toInt() and 255
                require(header and 128 == 0 && header and 31 in setOf(1, 5, 6, 7, 8, 9, 12)) { "不支持的 H.264 NAL" }
            }
            return result
        }
    }
}
