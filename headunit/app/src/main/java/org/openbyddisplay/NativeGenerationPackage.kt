package org.openbyddisplay

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Container/target preflight only. Ed25519 trust is enforced by the native receiver. */
object NativeGenerationPackage {
    data class Metadata(val target: String, val baseline: String, val packageId: String,
                        val version: String, val enabled: Boolean, val sha256: String)
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun u32(bytes: ByteArray, at: Int) = ByteBuffer.wrap(bytes, at, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
    private fun text(bytes: ByteArray, at: Int, size: Int): String {
        val raw = bytes.copyOfRange(at, at + size)
        val end = raw.indexOf(0)
        require(end in 1 until size && raw.drop(end).all { it == 0.toByte() }) { "原生包字符串无效" }
        return String(raw, 0, end, Charsets.US_ASCII).also {
            require(it.matches(Regex("[A-Za-z0-9_-][A-Za-z0-9_.-]*"))) { "原生包名称无效" }
        }
    }

    fun inspect(file: File): Metadata {
        val length = file.length()
        require(file.isFile && length in 208..InstrumentUpdateProtocol.Kind.NATIVE.maxBytes) { "原生包大小无效" }
        RandomAccessFile(file, "r").use { input ->
            val header = ByteArray(208).also(input::readFully)
            require(header.copyOfRange(0, 8).contentEquals("OBDGEN1\n".toByteArray())) { "原生包格式无效" }
            val payload = u32(header, 8)
            val count = u32(header, 12)
            val tableBytes = u32(header, 104)
            require(payload in 1..(16L * 1024 * 1024) && count in 2..16 && tableBytes == count * 128 &&
                length == 208 + tableBytes + payload && header.copyOfRange(102, 104).all { it == 0.toByte() } &&
                header.copyOfRange(108, 112).all { it == 0.toByte() }) { "原生包长度或保留字段错误" }
            val target = text(header, 16, 32)
            require(target in setOf("song-plus-dmi-2021", "tang-plus-dmi-2021")) { "未知仪表车型" }
            val baseline = hex(header.copyOfRange(48, 80))
            val packageId = hex(header.copyOfRange(80, 96))
            require(baseline != "0".repeat(64) && packageId != "0".repeat(32)) { "原生包身份为空" }
            val table = ByteArray(tableBytes.toInt()).also(input::readFully)
            require(MessageDigest.getInstance("SHA-256").digest(table).contentEquals(header.copyOfRange(112, 144))) { "原生包清单摘要错误" }
            var offset = 0L
            var previous = ""
            var service = false
            var profile: ByteArray? = null
            val buffer = ByteArray(65536)
            repeat(count.toInt()) { index ->
                val at = index * 128
                val name = text(table, at, 64)
                val bytes = u32(table, at + 64)
                val mode = u32(table, at + 68)
                require(name > previous && name !in setOf("bundle.obg", "state.bin", "identity.bin", "store.lock") &&
                    table.copyOfRange(at + 76, at + 96).all { it == 0.toByte() } &&
                    bytes in 1..(payload - offset) && u32(table, at + 72) == offset && mode in setOf(384L, 448L)) { "原生包文件表无效" }
                if (name == "service.elf") { require(mode == 448L); service = true }
                if (name == "profile.dat") require(mode == 384L && bytes == 64L)
                previous = name
                input.seek(208 + tableBytes + offset)
                val hash = MessageDigest.getInstance("SHA-256")
                var remaining = bytes
                while (remaining > 0) {
                    val n = minOf(buffer.size.toLong(), remaining).toInt()
                    input.readFully(buffer, 0, n)
                    hash.update(buffer, 0, n)
                    if (name == "profile.dat") profile = buffer.copyOfRange(0, n)
                    remaining -= n
                }
                require(hash.digest().contentEquals(table.copyOfRange(at + 96, at + 128))) { "原生包文件摘要错误" }
                offset += bytes
            }
            require(service && offset == payload && profile != null) { "原生包缺少服务或配置" }
            val raw = profile!!
            require(raw.copyOfRange(0, 8).contentEquals("OBDPRO1\n".toByteArray()) &&
                raw[8].toInt() == if (target.startsWith("song")) 1 else 2) { "原生包配置车型不一致" }
            require(raw[9].toInt() in 0..1) { "原生包启用状态无效" }
            val version = (0..2).joinToString(".") { index ->
                ByteBuffer.wrap(header, 96 + index * 2, 2).order(ByteOrder.LITTLE_ENDIAN).short.toUShort().toString()
            }
            require(file.length() == length) { "原生包已变化" }
            return Metadata(target, baseline, packageId, version, raw[9].toInt() == 1, InstrumentUpdatePackage.digest(file))
        }
    }
}
