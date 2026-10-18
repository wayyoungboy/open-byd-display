package org.openbyddisplay.cast

import org.json.JSONObject
import org.openbyddisplay.ClusterWireProtocol
import java.util.UUID

/**
 * Self-developed cast control JSON (HMI :47785 / receiver :47783).
 * Rejects commercial token/client_time fields at encode time.
 */
object CastProtocol {
    const val OP_VERSION = "version"
    const val OP_PING = "ping"
    const val OP_ARM = "cast.arm"
    const val OP_SET = "cast.set"
    const val OP_STOP = "cast.stop"
    const val OP_KEEPALIVE = "cast.keepalive"
    const val OP_START = "cast.start"
    const val OP_SWITCH = "cast.switch"
    const val OP_STATUS = "cast.status"
    const val OP_HEARTBEAT = "cast.heartbeat"
    const val OP_PROFILE_GET = "cast.profile.get"
    const val OP_PROFILE_SET = "cast.profile.set"
    const val OP_MUSIC_SHOW = "music.show"
    const val OP_MUSIC_HIDE = "music.hide"
    const val OP_NAV_SHOW = "navigation.show"
    const val OP_NAV_HIDE = "navigation.hide"
    const val OP_MUSIC_CONFIG_SET = "music.config.set"
    const val OP_NAV_CONFIG_SET = "navigation.config.set"
    const val DEFAULT_LAYER = 1

    fun encode(body: JSONObject): String = ClusterWireProtocol.encode(body)

    fun version(): JSONObject = JSONObject().put("op", OP_VERSION)
    fun ping(): JSONObject = JSONObject().put("op", OP_PING)

    fun windowOptions(profile: CastProfile, layer: Int = DEFAULT_LAYER): JSONObject =
        JSONObject()
            .put("visible", true)
            .put("x", profile.x)
            .put("y", profile.y)
            .put("width", profile.width)
            .put("height", profile.height)
            .put("radius", profile.radius)
            .put("opacity", profile.opacity)
            .put("layer", layer)

    fun castArm(options: JSONObject): JSONObject =
        JSONObject().put("op", OP_ARM).put("options", options)

    fun castSet(options: JSONObject): JSONObject =
        JSONObject().put("op", OP_SET).put("options", options)

    fun castKeepalive(): JSONObject = JSONObject().put("op", OP_KEEPALIVE)

    fun castStatus(): JSONObject = JSONObject().put("op", OP_STATUS)

    fun castHeartbeat(epoch: String, revision: Int): JSONObject =
        JSONObject().put("op", OP_HEARTBEAT).put("epoch", epoch).put("revision", revision)

    fun castStart(requestId: String, epoch: String, expectedRevision: Int, profileId: Int?): JSONObject {
        val o = JSONObject()
            .put("op", OP_START)
            .put("request_id", requestId)
            .put("epoch", epoch)
            .put("expected_revision", expectedRevision)
        if (profileId != null && profileId > 0) o.put("profile_id", profileId)
        return o
    }

    fun castSwitch(requestId: String, epoch: String, expectedRevision: Int): JSONObject =
        JSONObject()
            .put("op", OP_SWITCH)
            .put("request_id", requestId)
            .put("epoch", epoch)
            .put("expected_revision", expectedRevision)

    fun castStop(requestId: String? = null, epoch: String? = null, expectedRevision: Int? = null): JSONObject {
        val o = JSONObject().put("op", OP_STOP)
        if (requestId != null) o.put("request_id", requestId)
        if (epoch != null) o.put("epoch", epoch)
        if (expectedRevision != null) o.put("expected_revision", expectedRevision)
        return o
    }

    fun profileGet(): JSONObject = JSONObject().put("op", OP_PROFILE_GET)

    fun musicShow(payload: JSONObject): JSONObject =
        JSONObject().put("op", OP_MUSIC_SHOW).put("music", payload)

    fun musicHide(): JSONObject = JSONObject().put("op", OP_MUSIC_HIDE)

    fun navigationShow(payload: JSONObject): JSONObject =
        JSONObject().put("op", OP_NAV_SHOW).put("navigation", payload)

    fun navigationHide(): JSONObject = JSONObject().put("op", OP_NAV_HIDE)

    fun musicConfigSet(config: JSONObject): JSONObject =
        JSONObject().put("op", OP_MUSIC_CONFIG_SET).put("config", config)

    fun navigationConfigSet(config: JSONObject): JSONObject =
        JSONObject().put("op", OP_NAV_CONFIG_SET).put("config", config)

    fun newRequestId(prefix: String = "obd"): String = "$prefix-${UUID.randomUUID()}"

    fun newEpoch(): String = UUID.randomUUID().toString()
}
