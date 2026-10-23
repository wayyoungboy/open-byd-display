package org.openbyddisplay.cast

import org.openbyddisplay.ClusterWireProtocol

import org.json.JSONObject
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.CRC32

/** Serialized socket owner. A receipt means decoder submission, never proof of pixels. */
class VideoStreamClient(private val settings: CastOptions) : AutoCloseable {
    private val socket = Socket()
    private val closed = AtomicBoolean(false)
    private val watchdog = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "cast-deadline").apply { isDaemon = true } }
    private var submitted = 0L
    private var cookie = 0L
    private var lastPts = -1L
    private var bounds: CastRect? = null
    private var capabilities: CastCapabilities? = null
    fun capabilities(): CastCapabilities? = capabilities
    private fun <T> transaction(block: (DataOutputStream) -> T): T {
        check(!closed.get())
        val guard = watchdog.schedule({ runCatching { socket.close() } }, 5, TimeUnit.SECONDS)
        return try { block(DataOutputStream(socket.getOutputStream())) } finally { guard.cancel(false) }
    }
    private fun reply(): JSONObject {
        val line = ClusterWireProtocol.readLine(socket.getInputStream(), 4096) ?: error("仪表视频连接已断开")
        val o = JSONObject(line)
        check(o.opt("ok") == true) { "仪表拒绝视频请求：${o.optString("error").take(80)}" }
        check(o.optString("service") == "openbyddisplay.video" && integer(o, "wire_version") == 1L) { "需要自研仪表视频服务" }
        return o
    }
    fun open(width: Int, height: Int, beforeHandshake: () -> Unit = {}): CastRect {
        require(width in 2..1920 && height in 2..1080 && width % 2 == 0 && height % 2 == 0)
        check(bounds == null)
        connect()
        return transaction { out ->
            val json = "{\"op\":\"stream\"}".toByteArray(Charsets.US_ASCII)
            beforeHandshake()
            out.writeBytes("BVL1"); out.writeInt(json.size); out.write(json)
            out.writeInt(width); out.writeInt(height); out.writeInt(settings.fps); out.writeInt(0); out.flush()
            val ready = reply()
            check(ready.opt("ready") == true && ready.optString("protocol") == "LHF2" && ready.opt("continuous") == true)
            check(integer(ready, "heartbeat_protocol") == 1L && integer(ready, "window_protocol") == 1L)
            check(integer(ready, "submitted") == 0L)
            val caps = CastCapabilities.parse(ready)
            capabilities = caps
            bounds = caps.bounds
            caps.bounds
        }
    }
    private fun connect() {
        socket.tcpNoDelay = true; socket.soTimeout = 5000
        // Never silently route the instrument address over another interface.
        if (settings.host.startsWith("192.168.77.")) socket.bind(InetSocketAddress(InetAddress.getByName("192.168.77.2"), 0))
        socket.connect(InetSocketAddress(settings.host, settings.port), 5000)
    }
    fun probe(): JSONObject {
        connect()
        return transaction { out ->
            out.writeBytes("INFO"); out.flush()
            reply().also {
                check(it.optString("stage") == "capabilities")
                capabilities = CastCapabilities.parse(it)
                bounds = capabilities!!.bounds
            }
        }
    }
    fun configure(rect: CastRect): JSONObject = transaction { out ->
        val allowed = bounds ?: error("尚未握手")
        require(rect.x >= allowed.x && rect.y >= allowed.y && rect.x + rect.width <= allowed.x + allowed.width && rect.y + rect.height <= allowed.y + allowed.height)
        val id = ++cookie
        out.writeBytes("CONF"); out.writeInt(id.toInt())
        listOf(rect.x, rect.y, rect.width, rect.height, settings.opacity).forEach(out::writeInt); out.flush()
        reply().also { check(integer(it, "configured") == id && it.optString("stage") == "configured") }
    }
    fun heartbeat(): JSONObject = transaction { out ->
        val id = ++cookie
        out.writeBytes("PING"); out.writeInt(id.toInt()); out.flush()
        reply().also { check(integer(it, "heartbeat") == id && it.optString("stage") == "heartbeat") }
    }
    fun frame(au: AvcAccessUnits.Unit, ptsMs: Long): JSONObject = transaction { out ->
        check(bounds != null && submitted < 0xffffffffL)
        require(ptsMs in 0..0xffffffffL && ptsMs >= lastPts && au.bytes.size in 1..AvcAccessUnits.MAX)
        out.writeBytes("LHF2"); out.writeInt(au.bytes.size); out.writeInt(submitted.toInt())
        out.writeInt(ptsMs.toInt()); out.writeInt(if (au.key) 1 else 0)
        out.writeInt(CRC32().apply { update(au.bytes) }.value.toInt()); out.write(au.bytes); out.flush()
        reply().also {
            check(integer(it, "ack") == submitted && integer(it, "accepted_sequence") == submitted && integer(it, "submitted") == submitted + 1)
            check(it.optString("stage") == "decoder_submitted")
            submitted++; lastPts = ptsMs
        }
    }
    fun finish(): JSONObject = transaction { out ->
        check(submitted > 0)
        out.writeBytes("LHF2"); listOf(0, -1, 0, 0, 0).forEach(out::writeInt); out.flush()
        reply().also { check(it.opt("done") == true && it.opt("cleanup") == true && integer(it, "submitted") == submitted) }
    }
    override fun close() {
        closed.set(true); runCatching { socket.close() }; watchdog.shutdownNow()
    }
    companion object {
        internal fun integerValue(value: Any?): Long {
            check(value is Int || value is Long) { "仪表数字字段类型错误" }
            return (value as Number).toLong()
        }
        internal fun integer(o: JSONObject, field: String): Long = integerValue(o.opt(field))
    }
}
