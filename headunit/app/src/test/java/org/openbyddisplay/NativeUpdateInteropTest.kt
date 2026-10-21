package org.openbyddisplay

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** Actual Kotlin transport to actual C installer; synthetic health is explicitly host-only. */
class NativeUpdateInteropTest {
    @Test fun bothModelPackagesReachNativeInstallationAndRequireHealthConfirmation() {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "firmware").isDirectory }
        val localPython = File(root, ".venv/bin/python")
        val python = System.getenv("OPENBYDDISPLAY_PYTHON") ?: if (localPython.isFile) localPython.absolutePath else "python3"
        listOf("song-plus-dmi-2021", "tang-plus-dmi-2021").forEach { target ->
            val home = Files.createTempDirectory("openbyd-update-interop").toFile()
            val log = File(home, "peer.log")
            val process = ProcessBuilder(python, File(root, "firmware/native/tests/update_interop_peer.py").absolutePath,
                home.absolutePath, target).redirectErrorStream(true).redirectOutput(log).start()
            fun await(file: File, seconds: Long) {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
                while (!file.exists() && process.isAlive && System.nanoTime() < deadline) Thread.sleep(25)
                assertTrue(log.readText(), file.exists())
            }
            try {
                val readyFile = File(home, "ready.json")
                await(readyFile, 60)
                val ready = JSONObject(readyFile.readText())
                val file = File(home, "$target.obg")
                val checked = InstrumentUpdatePackage.inspect(file, file.name)
                val metadata = checked.native!!
                assertEquals(target, metadata.target)
                assertTrue(metadata.enabled)
                val native = InstrumentUpdateProtocol.Kind.NATIVE
                val id = "a".repeat(32)
                InstrumentUpdateClient("127.0.0.1", ready.getInt("port")).use { client ->
                    val info = client.command("update.info")
                    val state = JSONObject().put("target", target).put("baseline_sha256", ready.getString("baseline"))
                    InstrumentUpdateProtocol.nativeIdentity(state, info)
                    assertTrue(InstrumentUpdateProtocol.capability(info).allows(native))
                    assertFalse(info.getBoolean("production_flashable"))
                    val other = if (target.startsWith("song")) "tang-plus-dmi-2021" else "song-plus-dmi-2021"
                    assertTrue(runCatching { client.upload(File(home, "$other.obg"), "b".repeat(32), native) { _, _ -> } }.isFailure)
                    var sent = 0L
                    val uploaded = client.upload(file, id, native) { bytes, total ->
                        assertTrue(bytes <= total); sent = bytes
                    }
                    assertEquals(file.length(), sent)
                    assertEquals(metadata.packageId, uploaded.getString("package_id"))
                    assertTrue(InstrumentUpdateProtocol.canApply(InstrumentUpdateProtocol.status(uploaded, id, native)))
                    assertTrue(client.upload(file, id, native) { _, _ -> fail("duplicate must not resend payload") }.getBoolean("duplicate"))
                    assertTrue(runCatching { client.apply(id, native, false) }.isFailure)
                    val pending = InstrumentUpdateProtocol.status(client.apply(id, native, true), id, native)
                    assertEquals("awaiting_confirmation", pending.getString("stage"))
                    assertFalse(pending.getBoolean("applied"))
                    val trial = JSONObject().put("update_id", id).put("remote", pending).put("host", "127.0.0.1")
                        .put("kind", native.wire).put("apply_attempted", true)
                    assertFalse(InstrumentUpdateStore.canReplace(trial))
                    assertTrue(InstrumentUpdateStore.canCast(trial, "127.0.0.1"))
                    process.outputStream.apply { write((JSONObject().put("op", "confirm").put("id", id).toString() + "\n").toByteArray()); flush() }
                    await(File(home, "confirmed.json"), 5)
                    val installed = InstrumentUpdateProtocol.status(client.command("update.status", id), id, native)
                    assertTrue(installed.getBoolean("applied"))
                    assertTrue(InstrumentUpdateProtocol.finished(installed))
                    assertTrue(InstrumentUpdateStore.canReplace(JSONObject().put("update_id", id).put("remote", installed)))
                }
                val damaged = File(home, "damaged.obg").apply { writeBytes(file.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }) }
                assertTrue(runCatching { NativeGenerationPackage.inspect(damaged) }.isFailure)
                process.outputStream.apply { write("{\"op\":\"stop\"}\n".toByteArray()); flush(); close() }
                assertTrue(process.waitFor(8, TimeUnit.SECONDS))
                assertEquals(log.readText(), 0, process.exitValue())
            } finally {
                process.destroy()
                if (!process.waitFor(8, TimeUnit.SECONDS)) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
                // Native generations are intentionally read-only after publishing.
                home.walkBottomUp().filter { it.isDirectory }.forEach { it.setWritable(true, true) }
                home.deleteRecursively()
            }
        }
    }
}
