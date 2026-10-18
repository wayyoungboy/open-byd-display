package org.openbyddisplay

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class IndependentServiceTest {
    @Test fun independentRequestsDoNotContainBusinessCredentials() {
        val body = InstrumentUpdateProtocol.declaration("a".repeat(32), InstrumentUpdateProtocol.Kind.COMPONENTS, 100)
        assertFalse(JSONObject(ClusterWireProtocol.encode(body)).has("token"))
        assertFalse(body.has("client_time"))
    }

    @Test fun anAbsentUploadCanOnlyBeClearedBeforeAnyInstallAttempt() {
        val reply = JSONObject("""{"ok":false,"error":"update_not_found"}""")
        val id = "a".repeat(32)
        assertEquals("cancelled", InstrumentUpdateProtocol.clearAbsentUnapplied(reply, id, InstrumentUpdateProtocol.Kind.COMPONENTS, false).getString("stage"))
        assertTrue(runCatching { InstrumentUpdateProtocol.clearAbsentUnapplied(reply, id, InstrumentUpdateProtocol.Kind.COMPONENTS, true) }.isFailure)
    }

    @Test fun corruptedTaskRecordsBlockReplacement() {
        assertFalse(InstrumentUpdateStore.canReplace(JSONObject().put("recovery_blocked", true)))
        assertFalse(InstrumentUpdateStore.canReplace(JSONObject().put("update_id", "a".repeat(32))))
    }

    @Test fun installRequiresExplicitOperatorIntentAndDoesNotSendKind() {
        val id = "a".repeat(32)
        assertTrue(runCatching { InstrumentUpdateProtocol.applyRequest(id, InstrumentUpdateProtocol.Kind.FIRMWARE, false) }.isFailure)
        val body = InstrumentUpdateProtocol.applyRequest(id, InstrumentUpdateProtocol.Kind.FIRMWARE, true)
        assertEquals("FLASH_FIRMWARE", body.getString("confirm"))
        assertTrue(body.getBoolean("parked")); assertFalse(body.has("kind"))
    }

    @Test fun cancelledClientCannotOpenANewMutationConnection() {
        val client = InstrumentUpdateClient("127.0.0.1", 1)
        client.close()
        assertEquals("更新请求已取消", runCatching { client.command("update.apply", "a".repeat(32)) }.exceptionOrNull()?.message)
    }

    @Test fun kotlinClientAndPythonReceiverCompleteTheSignedUpdateFlow() {
        val working = File(System.getProperty("user.dir"))
        val root = generateSequence(working) { it.parentFile }.first { File(it, "firmware").isDirectory }
        val instrument = File(root, "firmware")
        assertTrue(instrument.isDirectory)
        val temporary = Files.createTempDirectory("openbyddisplay-interop").toFile()
        val executable = System.getenv("OPENBYDDISPLAY_PYTHON") ?: "python3"
        val process = ProcessBuilder(executable, File(instrument, "tests/interop_peer.py").absolutePath, temporary.absolutePath)
            .apply { environment()["PYTHONPATH"] = instrument.absolutePath }
            .redirectErrorStream(true).redirectOutput(File(temporary, "peer.log")).start()
        try {
            val ready = File(temporary, "ready.json")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (!ready.exists() && process.isAlive && System.nanoTime() < deadline) Thread.sleep(30)
            assertTrue(File(temporary, "peer.log").readText(), ready.isFile)
            val port = JSONObject(ready.readText()).getInt("port")
            val packageFile = File(temporary, "song-plus-dmi-2021.fup")
            assertEquals(InstrumentUpdateProtocol.Kind.COMPONENTS, InstrumentUpdatePackage.inspect(packageFile, "fixture.fup").kind)
            InstrumentUpdateClient("127.0.0.1", port).use { client ->
                val info = InstrumentUpdateProtocol.capability(client.command("update.info"))
                assertTrue(info.allows(InstrumentUpdateProtocol.Kind.COMPONENTS))
                assertFalse(info.allows(InstrumentUpdateProtocol.Kind.FIRMWARE))
                assertEquals("idle", client.castStatus().getString("state"))
                val id = InstrumentUpdateProtocol.id()
                val reply = client.upload(packageFile, id, InstrumentUpdateProtocol.Kind.COMPONENTS) { _, _ -> }
                assertTrue(InstrumentUpdateProtocol.canApply(InstrumentUpdateProtocol.status(reply, id, InstrumentUpdateProtocol.Kind.COMPONENTS)))
                assertTrue(client.apply(id, InstrumentUpdateProtocol.Kind.COMPONENTS, true).getBoolean("applied"))
                assertEquals("applied", client.command("update.status", id).getString("stage"))
                val wrongId = InstrumentUpdateProtocol.id()
                client.upload(File(temporary, "tang-plus-dmi-2021.fup"), wrongId, InstrumentUpdateProtocol.Kind.COMPONENTS) { _, _ -> }
                assertEquals("failed", client.command("update.status", wrongId).getString("stage"))
            }
        } finally {
            process.destroy(); if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly()
            temporary.deleteRecursively()
        }
    }
}
