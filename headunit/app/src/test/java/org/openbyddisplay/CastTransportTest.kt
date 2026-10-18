package org.openbyddisplay

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.openbyddisplay.cast.*
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class CastTransportTest {
    private val key = byteArrayOf(0,0,0,1,0x67,1,0,0,0,1,0x68,1,0,0,0,1,0x65,1)
    @Test fun normalizationAndReconnectRequireParameterSetsAndNewKeyframe() {
        val units = AvcAccessUnits()
        assertNull(units.frame(byteArrayOf(0,0,1,0x41,1)))
        val first = units.frame(key)!!
        assertTrue(first.key)
        assertEquals(listOf(7,8,5), AvcAccessUnits.parse(first.bytes).map { it[0].toInt() and 31 })
        units.reconnect()
        assertNull(units.frame(byteArrayOf(0,0,1,0x41,1)))
        assertTrue(units.frame(byteArrayOf(0,0,0,2,0x65,1), AvcAccessUnits.Framing.AVCC4)!!.key)
        listOf(byteArrayOf(0,0,0,9,0x65), byteArrayOf(0,0,1,0xe5.toByte()), byteArrayOf(0,0,1,0x65,0,0,1)).forEach {
            assertTrue(runCatching { AvcAccessUnits.parse(it) }.isFailure)
        }
    }
    @Test fun avccLengthsThatResembleStartCodesAreParsedOnlyWithExplicitFraming() {
        listOf(256,257,511).forEach { length ->
            val nal=ByteArray(length) { 0x55.toByte() }.also { it[0]=0x65 }
            val framed=java.nio.ByteBuffer.allocate(4+length).putInt(length).put(nal).array()
            val decoded=AvcAccessUnits.parse(framed,AvcAccessUnits.Framing.AVCC4)
            assertEquals(1,decoded.size); assertArrayEquals(nal,decoded[0])
        }
    }
    @Test fun uncertainCleanupSurvivesReconnectAndRestartAndOldOwnerCannotClearIt() {
        val first=CastCleanupRecord().begin("first")
        assertTrue(first.confirmed("old").pending)
        assertFalse(first.confirmed("first").pending)
        val interrupted=CastCleanupRecord.parse(first.uncertain().json())
        val reconnected=interrupted.begin("second").confirmed("second")
        assertTrue(reconnected.pending); assertTrue(reconnected.sticky)
        assertTrue(CastCleanupRecord.parse("corrupt").pending)
    }
    @Test fun readyRequiresBothExplicitSuccessAndProtocol() {
        listOf("""{"ok":false,"ready":true,"protocol":"LHF2"}""",
            """{"ok":true,"ready":false,"protocol":"LHF2"}""", """{"ok":true,"ready":true}""").forEach {
            assertTrue(runCatching { Lhf2Framing.readReadyLine(ByteArrayInputStream((it+"\n").toByteArray())) }.isFailure)
        }
    }
    @Test fun receiptNumbersRejectBooleanAndFloatAndSettingsStayInsideCalibratedBounds() {
        listOf(true, 1.0, "1").forEach { assertTrue(runCatching { VideoStreamClient.integerValue(it) }.isFailure) }
        val bounds = CastRect(100,100,600,400)
        val fitted = bounds.preset("mini").fit(1280,480)
        assertTrue(fitted.x >= bounds.x && fitted.y >= bounds.y && fitted.x+fitted.width <= bounds.x+bounds.width && fitted.y+fitted.height <= bounds.y+bounds.height)
        assertTrue(runCatching { CastOptions(host = "example.com") }.isFailure)
        assertTrue(runCatching { CastOptions(host = "8.8.8.8") }.isFailure)
        assertEquals(CastOptions(), CastOptions.parse(CastOptions().json()))
        assertEquals(1280 to 720, CastOptions().encodedSize(1920,1080))
    }
    @Test fun wrongAckCannotBeCountedAsSubmitted() {
        ServerSocket(0).use { server ->
            var peerError: Throwable? = null
            val peer = thread {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val input = DataInputStream(socket.getInputStream())
                        val hello = ByteArray(39); input.readFully(hello)
                        val base = JSONObject().put("ok",true).put("service","openbyddisplay.video").put("wire_version",1)
                        val ready = JSONObject(base.toString()).put("ready",true).put("protocol","LHF2").put("continuous",true)
                            .put("heartbeat_protocol",1).put("window_protocol",1).put("submitted",0).put("bounds",org.json.JSONArray(listOf(0,0,64,48)))
                        socket.getOutputStream().write((ready.toString()+"\n").toByteArray())
                        val header = ByteArray(24); input.readFully(header)
                        input.readFully(ByteArray(key.size))
                        val bad = JSONObject(base.toString()).put("stage","decoder_submitted").put("ack",99).put("accepted_sequence",99).put("submitted",1)
                        socket.getOutputStream().write((bad.toString()+"\n").toByteArray())
                    }
                } catch (e: Throwable) { peerError = e }
            }
            VideoStreamClient(CastOptions(host="127.0.0.1", port=server.localPort, fps=5)).use {
                it.open(64,48)
                assertTrue(runCatching { it.frame(AvcAccessUnits.Unit(key,true),0) }.isFailure)
            }
            peer.join(7000); assertFalse(peer.isAlive); assertNull(peerError)
        }
    }
    @Test fun kotlinSenderAndNativeCReceiverExchangeSeventyRealFramesAndCleanup() {
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }.first { File(it,"firmware").isDirectory }
        val temporary = Files.createTempDirectory("openbyd-native-cast").toFile()
        val process = ProcessBuilder(System.getenv("OPENBYDDISPLAY_PYTHON") ?: "python3",
            File(root,"firmware/native/tests/cast_interop_peer.py").absolutePath,temporary.absolutePath)
            .redirectErrorStream(true).redirectOutput(File(temporary,"peer.log")).start()
        try {
            val ready = File(temporary,"ready.json")
            val deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(40)
            while (!ready.exists() && process.isAlive && System.nanoTime()<deadline) Thread.sleep(30)
            assertTrue(File(temporary,"peer.log").readText(), ready.exists())
            val options = CastOptions(host="127.0.0.1",port=JSONObject(ready.readText()).getInt("port"),fps=5)
            VideoStreamClient(options).use { assertFalse(it.probe().getBoolean("production_flashable")) }
            VideoStreamClient(options).use { client ->
                val bounds=client.open(64,48)
                client.configure(bounds)
                val parser=AvcAccessUnits()
                repeat(70) { sequence ->
                    val au=parser.frame(File(temporary,"%03d.h264".format(sequence)).readBytes())!!
                    val receipt=client.frame(au,sequence*200L)
                    assertEquals(sequence+1L,receipt.getLong("submitted"))
                    if(sequence==35) assertTrue(client.heartbeat().getBoolean("ok"))
                }
                assertTrue(client.finish().getBoolean("cleanup"))
            }
            assertTrue(process.waitFor(15,TimeUnit.SECONDS))
            assertEquals(File(temporary,"peer.log").readText(),0,process.exitValue())
            val evidence=JSONObject(File(temporary,"evidence.json").readText())
            assertEquals(70,evidence.getInt("decoded")); assertTrue(evidence.getBoolean("matching_pixels"))
            assertFalse(evidence.getBoolean("qnx_execution_verified"))
        } finally { process.destroy(); if(!process.waitFor(3,TimeUnit.SECONDS)) process.destroyForcibly(); temporary.deleteRecursively() }
    }
}
