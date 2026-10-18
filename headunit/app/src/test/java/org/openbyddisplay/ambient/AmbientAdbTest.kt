package org.openbyddisplay.ambient

import org.junit.Assert.*
import org.junit.Test
import org.openbyddisplay.vehicle.BydInstrumentCmd
import org.openbyddisplay.vehicle.UsbModeEvidence
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.RSAPrivateKey
import java.nio.file.Files
import java.io.File
import java.net.ServerSocket
import java.net.InetAddress
import java.security.KeyPair
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Byte streams, local shells and loopback fake adbd only; no Android device or adb executable. */
class AmbientAdbTest {
    private fun frame(): ByteArray = ByteArrayOutputStream().also {
        AmbientAdb.writeMsg(DataOutputStream(it), 0x45545257, 3, 8, byteArrayOf(0, 1, -1))
    }.toByteArray()
    private fun read(bytes: ByteArray) = AmbientAdb.readMsg(DataInputStream(ByteArrayInputStream(bytes)))!!

    @Test fun classicFrameRoundTripIncludesUnsignedChecksum() {
        val msg = read(frame())
        assertEquals(3, msg.arg0)
        assertEquals(8, msg.arg1)
        assertArrayEquals(byteArrayOf(0, 1, -1), msg.payload)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsBadMagic() {
        read(frame().also { it[20] = (it[20].toInt() xor 1).toByte() })
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsBadChecksum() {
        read(frame().also { it[24] = 1 })
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsOversizeBeforeAllocation() {
        read(frame().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(12, 1_048_577) })
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsNegativeSize() {
        read(frame().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(12, -1) })
    }
    @Test(expected = EOFException::class) fun rejectsTruncatedPayload() {
        read(frame().dropLast(1).toByteArray())
    }
    @Test fun classicRsaSignatureVerifiesWithIndependentJcaVerifier() {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val content = "offline-auth-fixture".toByteArray()
        val token = MessageDigest.getInstance("SHA-1").digest(content)
        val signature = AmbientAdb.signToken(keys.private as RSAPrivateKey, token)
        val verifier = Signature.getInstance("SHA1withRSA")
        verifier.initVerify(keys.public)
        verifier.update(content)
        assertTrue(verifier.verify(signature))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsMalformedAuthChallenge() {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        AmbientAdb.signToken(keys.private as RSAPrivateKey, ByteArray(19))
    }
    @Test fun shellIdentityIsObservedRatherThanHardCoded() {
        val result = AmbientAdb.parseShellResult("__OPENBYDDISPLAY_UID__=10042\nhello\n__OPENBYDDISPLAY_EXIT__=0\n", true)
        assertFalse(result.ok)
        assertEquals("hello", result.output)
        assertTrue(result.detail.contains("uid=10042"))
    }
    @Test fun successLookingTextCannotHideNonzeroExit() {
        val result = AmbientAdb.parseShellResult("__OPENBYDDISPLAY_UID__=2000\nOK cmd=SCMD_USBMODE1\n__OPENBYDDISPLAY_EXIT__=1", true)
        assertFalse(result.ok)
    }
    @Test fun timeoutOrMissingCompletionCannotSucceed() {
        assertFalse(AmbientAdb.parseShellResult("__OPENBYDDISPLAY_UID__=2000\nOK cmd=x", true).ok)
        assertFalse(AmbientAdb.parseShellResult("__OPENBYDDISPLAY_UID__=2000\n__OPENBYDDISPLAY_EXIT__=0", false).ok)
        assertFalse(AmbientAdb.parseShellResult("2000\n__OPENBYDDISPLAY_EXIT__=0", true).ok)
    }
    @Test fun emptySuccessfulCommandIsAccepted() {
        assertTrue(AmbientAdb.parseShellResult("__OPENBYDDISPLAY_UID__=2000\n\n__OPENBYDDISPLAY_EXIT__=0", true).ok)
    }
    @Test fun wrapperPreservesActualExitAndOutputWithoutAnyDevice() {
        val process = ProcessBuilder("/bin/sh", "-c", AmbientAdb.wrapShellCommand("printf 'hello'; exit 7")).start()
        val raw = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor())
        val result = AmbientAdb.parseShellResult(raw, true)
        assertFalse(result.ok)
        assertEquals("hello", result.output)
        assertTrue(result.detail.contains("exit=7"))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsCommandInjectionInUsbMode() {
        AmbientAdb.buildUsbModeCommand("/data/app/base.apk", "SCMD_USBMODE2; id", "a".repeat(32))
    }

    @Test fun detachedHelperPreservesQuotedApkPathAndExitCode() {
        val dir = Files.createTempDirectory("openbyddisplay-shell-test").toFile()
        try {
            val nohup = File(dir, "nohup")
            nohup.writeText("#!/bin/sh\nsleep 0.15\nexec \"\$@\"\n")
            assertTrue(nohup.setExecutable(true))
            val fake = File(dir, "app_process")
            fake.writeText("#!/bin/sh\nprintf '%s\\n' \"\$CLASSPATH\" \"\$@\"\nexit 7\n")
            assertTrue(fake.setExecutable(true))
            val marker = File(dir, "unexpected")
            val apk = "/data/app/My App's \$(touch ${marker.absolutePath})/base.apk"
            val request = "b".repeat(32)
            val command = AmbientAdb.buildUsbModeCommand(apk, BydInstrumentCmd.SCMD_USBMODE_CAST, request)
                .replace("/data/local/tmp/", dir.absolutePath + "/")
            val process = ProcessBuilder("/bin/sh", "-c", command).apply {
                environment()["PATH"] = dir.absolutePath + ":" + System.getenv("PATH")
            }.start()
            assertEquals(0, process.waitFor())
            assertEquals("STARTED", process.inputStream.bufferedReader().readText().trim())
            val output = File(dir, "openbyddisplay-usb-$request.out")
            assertTrue("Must acknowledge child startup before closing ADB", output.exists())
            val deadline = System.nanoTime() + 3_000_000_000L
            while ((!output.exists() || !output.readText().contains(UsbModeEvidence.EXIT_MARKER)) &&
                System.nanoTime() < deadline) Thread.sleep(10)
            val text = output.readText()
            assertEquals(apk, text.lines().first())
            assertTrue(text.contains(UsbModeEvidence.EXIT_MARKER + "7"))
            assertFalse(marker.exists())
        } finally { dir.deleteRecursively() }
    }

    private fun fakeAdb(keys: KeyPair, conversation: (DataInputStream, DataOutputStream) -> Unit): AmbientAdb.ExecResult {
        return ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 3000
            val failure = AtomicReference<Throwable?>()
            val worker = thread(isDaemon = true) {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 3000
                        val input = DataInputStream(socket.getInputStream())
                        val output = DataOutputStream(socket.getOutputStream())
                        assertEquals(0x4e584e43, AmbientAdb.readMsg(input)!!.command)
                        conversation(input, output)
                    }
                } catch (t: Throwable) { failure.set(t) }
            }
            val result = AmbientAdb.execWithKeys(keys, "true", "127.0.0.1", server.localPort, 300)
            worker.join(4000)
            assertFalse("Fake adbd must finish", worker.isAlive)
            failure.get()?.let { throw AssertionError("Fake adbd failed", it) }
            result
        }
    }

    @Test fun transportTimeoutAfterOpenPreservesPartialOutputAndUncertainExecution() {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val result = fakeAdb(keys) { input, output ->
            AmbientAdb.writeMsg(output, 0x4e584e43, 0x01000000, 4096, "device::\u0000".toByteArray())
            val open = AmbientAdb.readMsg(input)!!
            assertEquals(0x4e45504f, open.command)
            AmbientAdb.writeMsg(output, 0x45545257, 1, open.arg0,
                "__OPENBYDDISPLAY_UID__=2000\nSTARTED\n".toByteArray())
            assertEquals(0x59414b4f, AmbientAdb.readMsg(input)!!.command)
            // No CLSE/exit marker: the client must time out rather than invent success.
            assertEquals(-1, input.read())
        }
        assertFalse(result.ok)
        assertTrue(result.commandMayBeRunning)
        assertEquals("STARTED", result.output)
        assertTrue(result.detail.contains("SocketTimeoutException"))
    }

    @Test fun authRejectionCannotBeMistakenForDispatchedCommand() {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val result = fakeAdb(keys) { input, output ->
            AmbientAdb.writeMsg(output, 0x48545541, 1, 0, ByteArray(20) { 1 })
            val signature = AmbientAdb.readMsg(input)!!
            assertEquals(0x48545541, signature.command)
            assertEquals(2, signature.arg0)
            AmbientAdb.writeMsg(output, 0x48545541, 1, 0, ByteArray(20) { 2 })
            assertEquals(-1, input.read())
        }
        assertFalse(result.ok)
        assertFalse(result.commandMayBeRunning)
        assertTrue(result.detail.contains("未获授权"))
    }
}
