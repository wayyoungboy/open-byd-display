package org.openbyddisplay

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject

/** Local container preflight. Only the instrument can establish target compatibility/signature trust. */
object InstrumentUpdatePackage {
    private val required = setOf("app.sv", "os.sv", "mcu.sv", "kzbVersion", "RcAppVersion", "version_svp")
    data class Checked(val kind: InstrumentUpdateProtocol.Kind, val bytes: Long, val sha256: String)

    fun kind(name: String): InstrumentUpdateProtocol.Kind = InstrumentUpdateProtocol.Kind.entries.firstOrNull {
        name.lowercase(Locale.ROOT).endsWith(it.suffix)
    } ?: error("请选择 .zip 固件包或 .fup 服务包；RAR 不能直接上传")

    fun digest(file: File): String = file.inputStream().use { stream ->
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        while (true) { val n = stream.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) }
        hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun inspect(file: File, name: String): Checked {
        val kind = kind(name)
        require(file.isFile && file.length() in 1..kind.maxBytes) { "更新包为空或超过限制" }
        if (kind == InstrumentUpdateProtocol.Kind.FIRMWARE) inspectZip(file) else inspectFup(file)
        // FUP signatures stay with the receiving instrument; no local false claim of authorization.
        return Checked(kind, file.length(), digest(file))
    }

    private fun inspectZip(file: File) {
        ZipFile(file).use { zip ->
            val seen = mutableSetOf<String>()
            var total = 0L
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val path = entry.name.removePrefix("./")
                require(!path.startsWith('/') && '\\' !in path && path.split('/').none { it == ".." || it == "." }) { "固件包路径无效" }
                if (entry.isDirectory) { require(path == "release/"); continue }
                val name = path.removePrefix("release/")
                require((name in required || name == "patch.sv") && seen.add(name)) { "固件包文件不在允许清单内或存在重复" }
                require(entry.size in 1..InstrumentUpdateProtocol.FIRMWARE_MAX) { "固件文件大小无效" }
                total += entry.size
                require(total <= InstrumentUpdateProtocol.FIRMWARE_MAX) { "固件解压总量超出限制" }
                val crc = CRC32()
                zip.getInputStream(entry).use { input ->
                    if (name.endsWith(".sv")) checkSv(input, entry.size, name.removeSuffix(".sv"), crc)
                    else {
                        require(entry.size <= 65536) { "版本文件过大" }
                        val data = readExact(input, entry.size.toInt())
                        require(input.read() == -1) { "版本文件实际长度超出声明" }
                        crc.update(data)
                    }
                }
                require(crc.value == entry.crc) { "ZIP 文件校验失败" }
            }
            require(seen.containsAll(required)) { "固件包缺少必要文件" }
        }
    }

    private fun readExact(input: InputStream, size: Int): ByteArray {
        val result = ByteArray(size)
        var used = 0
        while (used < size) { val n = input.read(result, used, size - used); require(n > 0) { "更新包截断" }; used += n }
        return result
    }

    private fun inspectFup(file: File) = file.inputStream().use { input ->
        val header = ByteBuffer.wrap(readExact(input, 88)).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(8); header.get(magic)
        require(magic.contentEquals(byteArrayOf(70, 85, 80, 50, 13, 10, 26, 10))) { "不是 FUP2 服务包" }
        val manifestSize = header.int
        require(manifestSize in 1..65536 && header.int == 0) { "FUP2 头无效" }
        val payloadSize = header.long
        require(payloadSize in 1..268435456 && file.length() == 88L + manifestSize + payloadSize) { "FUP2 大小不符" }
        val manifest = JSONObject(String(readExact(input, manifestSize), Charsets.UTF_8))
        require(manifest.getInt("format") == 2 && manifest.length() == 4 &&
            manifest.getString("version").matches(Regex("(0|[1-9][0-9]{0,4})\\.(0|[1-9][0-9]{0,4})\\.(0|[1-9][0-9]{0,4})")) &&
            manifest.getString("version").split('.').all { it.toInt() <= 65535 } &&
            manifest.getString("package_id").matches(Regex("[0-9a-fA-F]{32}"))) { "FUP2 清单无效" }
        val files = manifest.getJSONArray("files")
        require(files.length() in 1..128)
        val paths = mutableSetOf<String>()
        var total = 0L
        var installer = false
        val buffer = ByteArray(65536)
        for (i in 0 until files.length()) {
            val row = files.getJSONObject(i)
            val path = row.getString("path")
            require(row.length() == 3 && path.length in 1..239 && !path.startsWith('/') && '\\' !in path &&
                path.split('/').size <= 16 && path.split('/').none { it.isEmpty() || it == "." || it == ".." } &&
                paths.none { it == path || it.startsWith("$path/") || path.startsWith("$it/") }) { "FUP2 文件路径冲突或越界" }
            paths.add(path)
            val size = row.getLong("bytes")
            require(size in 0..268435456 && row.getString("sha256").matches(Regex("[0-9a-fA-F]{64}")))
            total += size; require(total <= payloadSize)
            if (path == "install.sh") { require(size in 1..262144); installer = true }
            val hash = MessageDigest.getInstance("SHA-256")
            var remaining = size
            while (remaining > 0) {
                val n = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                require(n > 0); hash.update(buffer, 0, n); remaining -= n
            }
            require(hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }.equals(row.getString("sha256"), true)) { "FUP2 文件摘要不符" }
        }
        require(installer && total == payloadSize && input.read() == -1) { "FUP2 载荷不完整" }
    }

    private fun checkSv(input: InputStream, size: Long, kind: String, crc: CRC32) {
        require(size > 512) { "SV 文件过短" }
        val md5 = MessageDigest.getInstance("MD5") // OEM SV format checksum, not an authenticity proof.
        val buffer = ByteArray(65536)
        var remaining = size - 512
        while (remaining > 0) {
            val n = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
            require(n > 0) { "SV 文件截断" }; md5.update(buffer, 0, n); crc.update(buffer, 0, n); remaining -= n
        }
        val footer = ByteArray(512)
        var offset = 0
        while (offset < footer.size) { val n = input.read(footer, offset, footer.size - offset); require(n > 0); offset += n }
        require(input.read() == -1) { "SV 长度异常" }; crc.update(footer)
        require(String(footer, 0, 19, Charsets.US_ASCII) == "Desay SV Automotive" &&
            String(footer, 64, 16, Charsets.US_ASCII).trimEnd('\u0000') == kind) { "SV 类型不符" }
        val actual = md5.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        require(actual == String(footer, 288, 32, Charsets.US_ASCII)) { "SV 内容校验失败" }
    }
}
