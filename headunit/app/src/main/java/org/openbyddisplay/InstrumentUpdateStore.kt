package org.openbyddisplay

import android.content.Context
import org.json.JSONObject

/** A damaged record blocks replacement; an unknown install must never become a new job. */
object InstrumentUpdateStore {
    @Synchronized fun read(context: Context): JSONObject = runCatching {
        val raw = context.getSharedPreferences("instrument_update", Context.MODE_PRIVATE).getString("state", null)
            ?: return JSONObject()
        val state = JSONObject(raw)
        check(state.optInt("state_schema") == 1)
        if (state.has("update_id")) check(state.getString("update_id").matches(Regex("[0-9a-f]{32}")) &&
            state.optString("host").isNotBlank() && state.optString("kind") in setOf("firmware", "components", "native_generation"))
        state
    }.getOrElse { JSONObject().put("recovery_blocked", true).put("message", "本机任务记录损坏，已阻止新更新。请保留数据并恢复任务记录，勿重复安装。") }

    @Synchronized fun write(context: Context, state: JSONObject) {
        check(!state.optBoolean("recovery_blocked")) { "不能覆盖损坏的任务记录" }
        check(context.getSharedPreferences("instrument_update", Context.MODE_PRIVATE).edit()
            .putString("state", state.put("state_schema", 1).toString()).commit()) { "更新任务无法保存，已停止操作" }
    }

    fun canReplace(state: JSONObject): Boolean {
        if (state.optBoolean("recovery_blocked")) return false
        if (state.optString("update_id").isEmpty()) return true
        val remote = state.optJSONObject("remote") ?: return false
        return remote.optString("stage") == "cancelled" ||
            (remote.optString("stage") == "applied" && remote.optBoolean("applied"))
    }
}
