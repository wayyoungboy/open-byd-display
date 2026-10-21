package org.openbyddisplay

import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

/** Streaming transport: no whole-package ByteArray and no automatic mutation retry. */
class InstrumentUpdateClient(
    private val host: String,
    private val port: Int = InstrumentUpdateProtocol.PORT
) : AutoCloseable {
    private val active = AtomicReference<Socket?>()
    private val closed = AtomicBoolean(false)

    private fun connect(): Socket = Socket().also { s ->
        check(!closed.get()) { "更新请求已取消" }
        check(active.compareAndSet(null, s)) { "已有更新请求正在执行" }
        try {
            check(!closed.get()) { "更新请求已取消" }
            s.tcpNoDelay = true
            s.soTimeout = 15_000
            if (host.startsWith("192.168.77.")) runCatching {
                s.bind(InetSocketAddress(InetAddress.getByName("192.168.77.2"), 0))
            }
            s.connect(InetSocketAddress(host, port), 5000)
        } catch (error: Exception) { release(s); throw error }
    }

    private fun release(socket: Socket) { runCatching { socket.close() }; active.compareAndSet(socket, null) }
    private fun reply(socket: Socket): JSONObject = JSONObject(
        ClusterWireProtocol.readLine(socket.getInputStream(), 65536) ?: error("仪表断开，结果未确认；请刷新任务状态"))

    fun command(op: String, id: String? = null): JSONObject = exchange(InstrumentUpdateProtocol.request(op, id))

    fun castStatus(): JSONObject = exchange(JSONObject().put("op", "cast.status"))
    fun apply(id: String, kind: InstrumentUpdateProtocol.Kind, operatorParked: Boolean): JSONObject =
        exchange(InstrumentUpdateProtocol.applyRequest(id, kind, operatorParked))

    private fun exchange(body: JSONObject): JSONObject {
        val socket = connect()
        try {
            val line = ClusterWireProtocol.encode(body)
            socket.getOutputStream().apply { write((line + "\n").toByteArray(Charsets.UTF_8)); flush() }
            return reply(socket)
        } finally { release(socket) }
    }

    fun upload(file: File, id: String, kind: InstrumentUpdateProtocol.Kind, progress: (Long, Long) -> Unit): JSONObject {
        val bytes = file.length()
        val native = if (kind == InstrumentUpdateProtocol.Kind.NATIVE) NativeGenerationPackage.inspect(file) else null
        val declaration = InstrumentUpdateProtocol.declaration(id, kind, bytes, native)
        val socket = connect()
        val lastWrite = AtomicLong(System.nanoTime())
        val watchdog = Executors.newSingleThreadScheduledExecutor()
        try {
            val output = socket.getOutputStream()
            output.write((ClusterWireProtocol.encode(declaration) + "\n").toByteArray(Charsets.UTF_8))
            output.flush()
            val ready = reply(socket)
            if (!InstrumentUpdateProtocol.uploadReady(ready, id)) return ready
            val guard = watchdog.scheduleAtFixedRate({
                if (System.nanoTime() - lastWrite.get() > 30_000_000_000L) runCatching { socket.close() }
            }, 1, 1, TimeUnit.SECONDS)
            try {
                file.inputStream().use { input ->
                    val buffer = ByteArray(16 * 1024)
                    var sent = 0L
                    while (sent < bytes) {
                        val n = input.read(buffer, 0, minOf(buffer.size.toLong(), bytes - sent).toInt())
                        check(n > 0) { "更新包已变化，请重新选择" }
                        output.write(buffer, 0, n)
                        lastWrite.set(System.nanoTime())
                        sent += n
                        progress(sent, bytes)
                    }
                    check(input.read() == -1) { "更新包已变化，请重新选择" }
                }
                output.flush()
            } finally { guard.cancel(false) }
            socket.soTimeout = 120_000
            return reply(socket)
        } finally { watchdog.shutdownNow(); release(socket) }
    }

    override fun close() { closed.set(true); active.getAndSet(null)?.let { runCatching { it.close() } } }
}
