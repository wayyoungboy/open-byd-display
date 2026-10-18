package org.openbyddisplay.cast

import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Persistent BVL1/LHF2 video sender for :47782. */
class VideoStreamClient(
    private val host: String,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
) : AutoCloseable {
    private var socket: Socket? = null
    private val sequence = AtomicInteger(0)
    private val open = AtomicBoolean(false)
    var ready: JSONObject? = null
        private set

    fun connect() {
        check(open.compareAndSet(false, true)) { "already_open" }
        val sock = Socket()
        sock.tcpNoDelay = true
        sock.soTimeout = 10_000
        sock.connect(InetSocketAddress(host, CastPorts.VIDEO), 5_000)
        val out = sock.getOutputStream()
        Lhf2Framing.writeExact(out, Lhf2Framing.streamBody(width, height, fps))
        ready = Lhf2Framing.readReadyLine(sock.getInputStream())
        sock.soTimeout = 0
        socket = sock
    }

    fun sendAccessUnit(payload: ByteArray, ptsMs: Int, keyFrame: Boolean) {
        val sock = socket ?: error("not_connected")
        val seq = sequence.getAndIncrement()
        Lhf2Framing.writeExact(sock.getOutputStream(), Lhf2Framing.frame(payload, seq, ptsMs, keyFrame))
    }

    fun sendEos() {
        val sock = socket ?: return
        runCatching { Lhf2Framing.writeExact(sock.getOutputStream(), Lhf2Framing.eos()) }
    }

    override fun close() {
        if (!open.getAndSet(false)) return
        runCatching { sendEos() }
        runCatching { socket?.close() }
        socket = null
    }
}
