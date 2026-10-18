package org.openbyddisplay

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class InstrumentUpdateTest {
    private val id = "a".repeat(32)
    private val kind = InstrumentUpdateProtocol.Kind.FIRMWARE
    private fun ready() = JSONObject("""{"ok":true,"update_id":"$id","kind":"firmware","stage":"ready","received_bytes":12,"total_bytes":12,"worker_active":false}""")

    @Test fun uploadDeclarationHasNoSmallUploadHashOrLimit() {
        val body = InstrumentUpdateProtocol.declaration(id, kind, 5 * 1024 * 1024)
        assertEquals("update.upload", body.getString("op"))
        assertFalse(body.has("sha256"))
        assertEquals(5 * 1024 * 1024, body.getInt("bytes"))
    }

    @Test fun onlyFullyReceivedIdleWorkerCanBeApplied() {
        assertTrue(InstrumentUpdateProtocol.canApply(ready()))
        listOf(ready().put("worker_active", true), ready().put("received_bytes", 1),
            ready().put("stage", "receiving"), ready().put("ok", false), ready().apply { remove("worker_active") })
            .forEach { assertFalse(InstrumentUpdateProtocol.canApply(it)) }
    }

    @Test(expected = IllegalStateException::class) fun statusCannotReplaceAnotherJob() {
        InstrumentUpdateProtocol.status(ready().put("update_id", "b".repeat(32)), id, kind)
    }

    @Test fun failedJobIsStillAConfirmedStatusAndHandOffIsNotSuccess() {
        assertEquals("failed", InstrumentUpdateProtocol.status(ready().put("ok", false).put("stage", "failed"), id, kind).getString("stage"))
        assertTrue(InstrumentUpdateProtocol.summary(ready().put("stage", "handed_off")).contains("尚未确认"))
        assertFalse(InstrumentUpdateProtocol.canApply(ready().put("stage", "handed_off")))
    }

    @Test fun missingComponentKeyCannotEnableUpload() {
        val info = JSONObject("""{"ok":true,"update_protocol":2,"receive_available":true,"firmware_apply_available":true,"component_apply_available":true}""")
        val capability = InstrumentUpdateProtocol.capability(info)
        assertTrue(capability.allows(kind))
        assertFalse(capability.allows(InstrumentUpdateProtocol.Kind.COMPONENTS))
    }

    @Test(expected = IllegalStateException::class) fun oldFirmwareHasNoGuessedUpdateSupport() {
        InstrumentUpdateProtocol.capability(JSONObject("""{"ok":true}"""))
    }

    @Test fun fiveMegabytesStreamOnlyAfterTheMatchingReady() {
        val file = File.createTempFile("update", ".zip")
        try {
            file.outputStream().use { out -> repeat(80) { out.write(ByteArray(65536) { (it % 251).toByte() }) } }
            ServerSocket(0).use { server ->
                val received = FutureTask(Callable {
                    server.accept().use { socket ->
                        val input = socket.getInputStream()
                        val header = JSONObject(ClusterWireProtocol.readLine(input)!!)
                        assertEquals(id, header.getString("update_id"))
                        assertFalse(header.has("sha256"))
                        socket.getOutputStream().write("{\"ok\":true,\"ready\":true,\"update_id\":\"$id\"}\n".toByteArray())
                        var remaining = header.getLong("bytes")
                        val hash = MessageDigest.getInstance("SHA-256")
                        val buffer = ByteArray(8192)
                        while (remaining > 0) {
                            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                            check(n > 0); hash.update(buffer, 0, n); remaining -= n
                        }
                        socket.getOutputStream().write((ready().toString() + "\n").toByteArray())
                        hash.digest()
                    }
                })
                Thread(received).start()
                InstrumentUpdateClient("127.0.0.1", server.localPort).use {
                    var sent = 0L
                    val reply = it.upload(file, id, kind) { n, _ -> sent = n }
                    assertEquals("ready", reply.getString("stage")); assertEquals(file.length(), sent)
                }
                assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(file.readBytes()), received.get(10, TimeUnit.SECONDS))
            }
        } finally { file.delete() }
    }

    @Test fun duplicateJobDoesNotResendAnyPayload() {
        val file = File.createTempFile("update", ".zip").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        try {
            ServerSocket(0).use { server ->
                val received = FutureTask(Callable {
                    server.accept().use { socket ->
                        socket.soTimeout = 2000
                        ClusterWireProtocol.readLine(socket.getInputStream())
                        socket.getOutputStream().write((ready().put("duplicate", true).toString() + "\n").toByteArray())
                        socket.getInputStream().read()
                    }
                })
                Thread(received).start()
                InstrumentUpdateClient("127.0.0.1", server.localPort).use {
                    assertTrue(it.upload(file, id, kind) { _, _ -> fail("duplicate must not stream") }.getBoolean("duplicate"))
                }
                assertEquals(-1, received.get(5, TimeUnit.SECONDS))
            }
        } finally { file.delete() }
    }

    private fun sv(kind: String): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write("fixture".toByteArray()) }
        val gzip = out.toByteArray()
        val footer = ByteArray(512)
        "Desay SV Automotive".toByteArray().copyInto(footer)
        kind.toByteArray().copyInto(footer, 64)
        MessageDigest.getInstance("MD5").digest(gzip).joinToString("") { "%02x".format(it.toInt() and 255) }.toByteArray().copyInto(footer, 288)
        ByteBuffer.wrap(footer).order(ByteOrder.LITTLE_ENDIAN).putInt(320, 7)
        return gzip + footer
    }

    private fun zip(extra: String? = null, broken: Boolean = false): File {
        val file = File.createTempFile("update", ".zip")
        ZipOutputStream(file.outputStream()).use { output ->
            (listOf("app.sv", "os.sv", "mcu.sv", "kzbVersion", "RcAppVersion", "version_svp") + listOfNotNull(extra)).forEach { name ->
                output.putNextEntry(ZipEntry("release/$name"))
                val bytes = if (name.endsWith(".sv")) sv(name.removeSuffix(".sv")) else "1.0.3".toByteArray()
                if (broken && name == "app.sv") bytes[5] = (bytes[5].toInt() xor 1).toByte()
                output.write(bytes); output.closeEntry()
            }
        }
        return file
    }

    @Test fun actualSvFooterAndOptionalPatchAreChecked() {
        val file = zip("patch.sv")
        try { assertEquals(kind, InstrumentUpdatePackage.inspect(file, "fixture.zip").kind) } finally { file.delete() }
    }

    @Test fun corruptedSvPayloadIsRejectedEvenWithValidOuterZipCrc() {
        val file = zip(broken = true)
        try {
            assertTrue(runCatching { InstrumentUpdatePackage.inspect(file, "fixture.zip") }.isFailure)
        } finally { file.delete() }
    }

    @Test fun parentPathsAndUnexpectedFilesAreRejected() {
        for (name in listOf("../evil", "install.sh")) {
            val file = zip(name)
            try { assertTrue(runCatching { InstrumentUpdatePackage.inspect(file, "fixture.zip") }.isFailure) } finally { file.delete() }
        }
    }

    @Test(expected = IllegalStateException::class) fun rarCannotBeAccidentallyUploadedAsFirmware() {
        InstrumentUpdatePackage.kind("new.rar")
    }
}
