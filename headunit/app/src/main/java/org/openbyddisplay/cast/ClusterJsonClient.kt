package org.openbyddisplay.cast

import org.json.JSONObject
import org.openbyddisplay.ClusterWireProtocol
import java.net.InetSocketAddress
import java.net.Socket

/** One-shot JSON-line client for cast/control ports (no commercial auth fields). */
class ClusterJsonClient(
    private val host: String,
    private val connectTimeoutMs: Int = 3_000,
    private val soTimeoutMs: Int = 8_000,
) {
    fun exchange(port: Int, body: JSONObject): JSONObject {
        val payload = CastProtocol.encode(body) + "\n"
        Socket().use { socket ->
            socket.tcpNoDelay = true
            socket.soTimeout = soTimeoutMs
            socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            val output = socket.getOutputStream()
            output.write(payload.toByteArray(Charsets.UTF_8))
            output.flush()
            val line = ClusterWireProtocol.readLine(socket.getInputStream())
                ?: error("empty_reply")
            return JSONObject(line)
        }
    }

    fun version(): JSONObject = exchange(CastPorts.STATUS, CastProtocol.version())

    fun castStatus(): JSONObject = exchange(CastPorts.HMI, CastProtocol.castStatus())

    fun profileGet(): JSONObject = exchange(CastPorts.HMI, CastProtocol.profileGet())

    fun startSession(profileId: Int?, epoch: String, revision: Int): JSONObject =
        exchange(
            CastPorts.HMI,
            CastProtocol.castStart(CastProtocol.newRequestId(), epoch, revision, profileId),
        )

    fun stopSession(epoch: String?, revision: Int?): JSONObject =
        exchange(
            CastPorts.HMI,
            CastProtocol.castStop(
                requestId = CastProtocol.newRequestId(),
                epoch = epoch,
                expectedRevision = revision,
            ),
        )

    fun heartbeat(epoch: String, revision: Int): JSONObject =
        exchange(CastPorts.HMI, CastProtocol.castHeartbeat(epoch, revision))

    fun arm(options: JSONObject): JSONObject =
        exchange(CastPorts.RECEIVER_CONTROL, CastProtocol.castArm(options))

    fun setWindow(options: JSONObject): JSONObject =
        exchange(CastPorts.RECEIVER_CONTROL, CastProtocol.castSet(options))

    fun receiverKeepalive(): JSONObject =
        exchange(CastPorts.RECEIVER_CONTROL, CastProtocol.castKeepalive())

    fun musicShow(payload: JSONObject): JSONObject =
        exchange(CastPorts.MUSIC_OVERLAY, CastProtocol.musicShow(payload))

    fun musicHide(): JSONObject =
        exchange(CastPorts.MUSIC_OVERLAY, CastProtocol.musicHide())

    fun navigationShow(payload: JSONObject): JSONObject =
        exchange(CastPorts.NAV_OVERLAY, CastProtocol.navigationShow(payload))

    fun navigationHide(): JSONObject =
        exchange(CastPorts.NAV_OVERLAY, CastProtocol.navigationHide())
}
