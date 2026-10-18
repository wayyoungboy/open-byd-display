package org.openbyddisplay.ambient

import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.io.PrintWriter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class AmbientControllerTest {
    private val baseline = AmbientSnapshot(28, 7, 6, 3, 2)
    private inner class Can : AmbientLampIo {
        var state = baseline
        var pending: AmbientSnapshot? = null
        var remainingReads = 0
        var delay = 2
        var time = 0L
        var throwAfterWrite = false
        var neverDeliver = false
        val writes = mutableListOf<Triple<Int, Int, Int>>()
        override fun read(): AmbientSnapshot {
            if (!neverDeliver && pending != null && --remainingReads <= 0) { state = pending!!; pending = null }
            return state
        }
        override fun write(color: Int, rawBrightness: Int, area: Int) {
            assertNull("a previous write was still in flight", pending)
            writes += Triple(color, rawBrightness, area)
            pending = if (color == 0) state.copy(area = area) else state.withFrame(AmbientFrame(color, rawBrightness), area)
            remainingReads = delay
            if (throwAfterWrite) { state = pending!!; pending = null; throwAfterWrite = false; error("write failed after mutation") }
        }
        override fun nowMs() = time
        override fun sleepMs(milliseconds: Long) { time += milliseconds }
    }
    @Test fun confirmationWaitsForFeedbackAndRestoresMixedAreasAndSelection() {
        val can = Can()
        val controller = AmbientLampController(can)
        assertEquals(baseline.withFrame(AmbientFrame(3, 5)), controller.frame(AmbientFrame(3, 5)))
        assertTrue(can.time >= 140)
        assertEquals(AmbientRestoration.RESTORED, controller.finish())
        assertEquals(baseline, can.state)
        assertEquals(listOf(3, 1, 2), can.writes.map { it.third })
        val count = can.writes.size
        assertEquals(AmbientRestoration.RESTORED, controller.finish())
        assertEquals(count, can.writes.size)
    }
    @Test fun originalAreaIsRestoredAfterSeparateFrontAndBackWrites() {
        for (area in 1..3) {
            val can = Can().apply { state = baseline.copy(area = area) }
            val original = can.state
            val controller = AmbientLampController(can)
            controller.frame(AmbientFrame(3, 5))
            assertEquals(AmbientRestoration.RESTORED, controller.finish())
            assertEquals(original, can.state)
            if (area != 2) assertEquals(Triple(0, 0, area), can.writes.last())
        }
    }
    @Test fun manualAdjustmentWinsEvenImmediatelyBeforeStop() {
        val can = Can()
        val controller = AmbientLampController(can)
        controller.frame(AmbientFrame(3, 5))
        can.state = can.state.copy(frontColor = 8)
        val foreign = can.state
        assertEquals(AmbientRestoration.OVERRIDDEN, controller.finish())
        assertEquals(foreign, can.state)
        assertEquals(1, can.writes.size)
    }
    @Test fun exceptionAfterARealMutationStillRestores() {
        val can = Can().apply { throwAfterWrite = true }
        val controller = AmbientLampController(can)
        try { controller.frame(AmbientFrame(3, 5)); fail() } catch (_: IllegalStateException) { }
        assertEquals(AmbientRestoration.RESTORED, controller.finish())
        assertEquals(baseline, can.state)
    }
    @Test fun missingRestoreFeedbackIsNotReportedAsRestored() {
        val can = Can()
        val controller = AmbientLampController(can)
        controller.frame(AmbientFrame(3, 5))
        can.neverDeliver = true
        assertEquals(AmbientRestoration.UNCONFIRMED, controller.finish())
    }
    @Test fun duplicateSequenceStopsAndRestoresWithoutAnotherLampWrite() {
        val can = Can()
        val replies = mutableListOf<String>()
        val requests = listOf("FRAME 1 3 5", "FRAME 1 6 2").iterator()
        AmbientControlSession(AmbientLampController(can)).run({ if (requests.hasNext()) requests.next() else null }, replies::add)
        assertFalse(can.writes.any { it.first == 6 })
        assertTrue(replies.last().startsWith("ERROR RESTORED "))
        assertEquals(baseline, can.state)
    }
    @Test fun actualTcpEofAndLeaseTimeoutBothRestoreWithoutClaimingRemoteAck() {
        for (timeout in listOf(false, true)) {
            val can = Can()
            val done = CountDownLatch(1)
            var outcome: AmbientRestoration? = null
            ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { server ->
                thread {
                    try { server.accept().use { socket ->
                        socket.soTimeout = 120
                        val output = PrintWriter(socket.getOutputStream(), true)
                        outcome = AmbientControlSession(AmbientLampController(can)).run(
                            { AmbientProtocol.line(socket.getInputStream(), { System.nanoTime() / 1_000_000 }, 500) },
                            { output.println(it) })
                    } } finally { done.countDown() }
                }
                Socket(java.net.InetAddress.getLoopbackAddress(), server.localPort).use { client ->
                    client.soTimeout = 2000
                    val read = client.getInputStream().bufferedReader()
                    assertTrue(read.readLine().startsWith("READY "))
                    PrintWriter(client.getOutputStream(), true).println("FRAME 1 3 5")
                    assertTrue(read.readLine().startsWith("ACK 1 "))
                    if (timeout) assertTrue(read.readLine().startsWith("ERROR RESTORED "))
                }
                assertTrue(done.await(2, TimeUnit.SECONDS))
            }
            assertEquals(AmbientRestoration.RESTORED, outcome)
            assertEquals(baseline, can.state)
        }
    }
    @Test fun utf8ErrorsAreBoundedAndTerminalClassificationIsExact() {
        val reply = "ERROR RESTORED 原生后端未验证"
        assertEquals(reply, AmbientProtocol.line((reply + "\n").byteInputStream(), { 0L }, 1))
        assertEquals(AmbientRestoration.RESTORED, AmbientProtocol.restoration(reply))
        assertNull(AmbientProtocol.restoration("RESTORED maybe"))
        try { AmbientProtocol.line(("x".repeat(1025) + "\n").byteInputStream(), { 0L }, 1); fail() }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun fragmentedCommandCannotRenewTheLeaseWithAnOverdueNewline() {
        var clock = 0L
        val deadlines = mutableListOf<Int>()
        val input = object : java.io.InputStream() {
            var i = 0
            override fun read(): Int {
                clock += if (i == 0) 90 else 20
                return if (i++ == 0) 'X'.code else '\n'.code
            }
        }
        try { AmbientProtocol.line(input, { clock }, 100, deadlines::add); fail() }
        catch (_: IllegalStateException) { }
        assertEquals(listOf(100, 10), deadlines)
    }
}
