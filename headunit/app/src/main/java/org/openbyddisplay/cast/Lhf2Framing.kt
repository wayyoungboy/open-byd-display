package org.openbyddisplay.cast

import org.json.JSONObject
import org.openbyddisplay.ClusterWireProtocol
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/** Independent BVL1 handshake + LHF2 frame writer. No vendor token fields. */
object Lhf2Framing {
    const val BVL1 = "BVL1"
    const val LHF2 = "LHF2"
    const val HEADER_SIZE = 24
    const val MAX_PAYLOAD = 2 * 1024 * 1024
    val EOS_SEQUENCE: Int = -1  // 0xFFFFFFFF on wire

    fun crc32(payload: ByteArray, offset: Int = 0, length: Int = payload.size): Int {
        val crc = CRC32()
        crc.update(payload, offset, length)
        return crc.value.toInt()
    }

    fun streamBody(width: Int, height: Int, fps: Int): ByteArray {
        require(width in 2..1920 && width % 2 == 0) { "invalid_width" }
        require(height in 2..1080 && height % 2 == 0) { "invalid_height" }
        require(fps in 1..60) { "invalid_fps" }
        val json = ClusterWireProtocol.encode(JSONObject().put("op", "stream"))
        require(json.toByteArray(Charsets.UTF_8).size <= 4096) { "bvl1_json_too_large" }
        val jsonBytes = json.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream(8 + jsonBytes.size + 16)
        out.write(BVL1.toByteArray(Charsets.US_ASCII))
        out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(jsonBytes.size).array())
        out.write(jsonBytes)
        out.write(
            ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
                .putInt(width).putInt(height).putInt(fps).putInt(0).array()
        )
        return out.toByteArray()
    }

    fun frame(payload: ByteArray, sequence: Int, ptsMs: Int, keyFrame: Boolean): ByteArray {
        require(payload.size <= MAX_PAYLOAD) { "payload_too_large" }
        val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
        header.put(LHF2.toByteArray(Charsets.US_ASCII))
        header.putInt(payload.size)
        header.putInt(sequence)
        header.putInt(ptsMs)
        header.putInt(if (keyFrame) 1 else 0)
        header.putInt(crc32(payload))
        return header.array() + payload
    }

    fun eos(): ByteArray = frame(ByteArray(0), EOS_SEQUENCE, 0, false)

    fun readReadyLine(input: InputStream): JSONObject {
        val line = ClusterWireProtocol.readLine(input) ?: error("stream_closed")
        val json = JSONObject(line)
        require(json.opt("ok") == true && json.opt("ready") == true) {
            json.optString("error", "not_ready")
        }
        require(json.optString("protocol") == LHF2) { "unexpected_protocol" }
        return json
    }

    fun writeExact(output: OutputStream, bytes: ByteArray) {
        output.write(bytes)
        output.flush()
    }
}
