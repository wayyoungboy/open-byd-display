package org.openbyddisplay

import org.json.JSONObject
import java.util.UUID

/** Protocol 2 update jobs are distinct from the small theme/mask upload API. */
object InstrumentUpdateProtocol {
    const val PORT = 47785
    const val FIRMWARE_MAX = 536870912L
    const val COMPONENT_MAX = 268435456L + 65624
    const val NATIVE_MAX = 16L * 1024 * 1024 + 208 + 16 * 128
    enum class Kind(val wire: String, val suffix: String, val maxBytes: Long) {
        FIRMWARE("firmware", ".zip", FIRMWARE_MAX), COMPONENTS("components", ".fup", COMPONENT_MAX),
        NATIVE("native_generation", ".obg", NATIVE_MAX);
        companion object {
            fun parse(value: String) = entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("未知更新类型")
        }
    }
    fun port(kind: Kind) = if (kind == Kind.NATIVE) 47793 else PORT

    data class Capability(val receive: Boolean, val firmware: Boolean, val components: Boolean,
                          val componentKey: Boolean, val firmwareMax: Long, val componentMax: Long,
                          val native: Boolean = false, val target: String = "", val baseline: String = "",
                          val keySha256: String = "", val nativeMax: Long = NATIVE_MAX) {
        fun limit(kind: Kind) = minOf(kind.maxBytes, when (kind) { Kind.FIRMWARE -> firmwareMax; Kind.COMPONENTS -> componentMax; Kind.NATIVE -> nativeMax })
        fun allows(kind: Kind) = receive && when (kind) { Kind.FIRMWARE -> firmware; Kind.COMPONENTS -> components && componentKey; Kind.NATIVE -> native }
    }

    fun capability(reply: JSONObject): Capability {
        check(reply.opt("ok") == true && reply.optInt("update_protocol") in setOf(2, 3)) { "仪表不支持此更新方式" }
        if (reply.optInt("update_protocol") == 3) {
            val target = reply.optString("target")
            val baseline = reply.optString("baseline_sha256")
            val pin = reply.optString("public_key_sha256")
            check(target in setOf("song-plus-dmi-2021", "tang-plus-dmi-2021") &&
                listOf(baseline, pin).all { it.matches(Regex("[0-9a-f]{64}")) && it != "0".repeat(64) }) { "仪表更新身份不完整" }
            return Capability(reply.optBoolean("receive_available"), false, false, false, 0, 0,
                reply.optBoolean("native_generation_available"), target, baseline, pin, reply.optLong("native_max_bytes", NATIVE_MAX))
        }
        return Capability(reply.optBoolean("receive_available"), reply.optBoolean("firmware_apply_available"),
            reply.optBoolean("component_apply_available"), reply.optBoolean("component_key_configured"),
            reply.optLong("firmware_max_bytes", FIRMWARE_MAX), reply.optLong("component_payload_max_bytes", 268435456) + 65624)
    }

    fun id(): String = UUID.randomUUID().toString().replace("-", "")
    fun request(op: String, id: String? = null): JSONObject {
        require(op in setOf("update.info", "update.status", "update.upload", "update.apply", "update.cancel"))
        return JSONObject().put("op", op).apply {
            if (id != null) { require(id.matches(Regex("[0-9a-f]{32}"))); put("update_id", id) }
        }
    }

    fun declaration(id: String, kind: Kind, bytes: Long, native: NativeGenerationPackage.Metadata? = null): JSONObject {
        require(bytes in 1..kind.maxBytes) { "更新包大小超出限制" }
        // Original updater intentionally omits sha256 here; it validates the package itself.
        return request("update.upload", id).put("kind", kind.wire).put("bytes", bytes).apply {
            if (kind == Kind.NATIVE) {
                require(native != null) { "缺少原生包身份" }
                put("sha256", native.sha256).put("target", native.target).put("baseline_sha256", native.baseline)
            }
        }
    }

    fun applyRequest(id: String, kind: Kind, operatorParked: Boolean): JSONObject {
        require(operatorParked) { "请先确认已停车并核对车型" }
        return request("update.apply", id).put("parked", true)
            .put("confirm", when (kind) { Kind.FIRMWARE -> "FLASH_FIRMWARE"; Kind.COMPONENTS -> "INSTALL_COMPONENTS"; Kind.NATIVE -> "INSTALL_NATIVE_GENERATION" })
    }

    fun uploadReady(reply: JSONObject, id: String): Boolean {
        check(reply.optString("update_id") == id) { "更新任务不一致" }
        if (reply.optBoolean("duplicate")) return false
        check(reply.opt("ok") == true && reply.opt("ready") == true) { "仪表未同意接收更新包" }
        return true
    }

    fun status(reply: JSONObject, id: String, kind: Kind): JSONObject {
        check(reply.optString("update_id") == id && reply.optString("kind") == kind.wire && reply.has("stage")) {
            "更新结果不属于本次任务，保持原记录"
        }
        check(reply.opt("ok") == true || reply.optString("stage") in setOf("failed", "recovery_required")) {
            "仪表未确认更新结果"
        }
        return reply
    }

    fun clearAbsentUnapplied(reply: JSONObject, id: String, kind: Kind, applyAttempted: Boolean): JSONObject {
        check(reply.opt("ok") == false && reply.optString("error") == "update_not_found" && !applyAttempted) {
            "安装结果未确认，不能清除本机记录"
        }
        return JSONObject().put("ok", true).put("update_id", id).put("kind", kind.wire)
            .put("stage", "cancelled").put("local_only", true).put("applied", false)
    }

    fun canApply(reply: JSONObject): Boolean = reply.opt("ok") == true && reply.optString("stage") == "ready" &&
        reply.opt("worker_active") == false && reply.optLong("received_bytes", -1) > 0 &&
        reply.optLong("received_bytes") == reply.optLong("total_bytes", -2) &&
        (reply.optString("kind") != Kind.NATIVE.wire || reply.opt("can_install") == true)

    fun finished(reply: JSONObject): Boolean = reply.optString("stage") in setOf("cancelled", "failed", "recovery_required", "handed_off") ||
        (reply.optString("stage") == "applied" && reply.optBoolean("applied"))

    fun summary(reply: JSONObject): String = when (reply.optString("stage")) {
        "ready" -> "更新包已由仪表校验，等待安装"
        "receiving" -> "仪表正在接收更新包"
        "validating" -> "仪表正在校验更新包"
        "installing", "applying" -> "仪表正在安装，请保持供电"
        "awaiting_confirmation" -> "已安装自研服务包，等待仪表试启动与健康验证；尚未确认完成"
        "handed_off" -> "已交给仪表启动更新程序，尚未确认升级完成"
        "applied" -> if (reply.optBoolean("applied")) "仪表已确认安装完成" else "尚未确认安装完成"
        "cancelled" -> "更新包已清除"
        "recovery_required" -> "仪表要求恢复处理，请保留任务记录"
        "failed" -> "更新失败：${reply.optString("error").take(120)}"
        else -> "更新阶段：${reply.optString("stage", "未知").take(80)}"
    }
}
