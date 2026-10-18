package org.openbyddisplay.cast

import android.content.Context
import org.json.JSONObject

internal data class CastCleanupRecord(val owner: String? = null, val pending: Boolean = false, val sticky: Boolean = false) {
    fun begin(id: String) = CastCleanupRecord(id, true, sticky || pending && owner != id)
    fun uncertain() = copy(pending = true, sticky = true)
    fun confirmed(id: String) = if (owner == id && !sticky) CastCleanupRecord() else this
    fun json() = JSONObject().put("owner", owner ?: "").put("pending", pending).put("sticky", sticky).toString()
    companion object {
        fun parse(raw: String?): CastCleanupRecord {
            if (raw == null) return CastCleanupRecord()
            return runCatching {
                val o = JSONObject(raw)
                check(o.opt("pending") is Boolean && o.opt("sticky") is Boolean && o.opt("owner") is String)
                val owner = o.getString("owner").ifEmpty { null }
                check(owner == null || owner.matches(Regex("[A-Za-z0-9_-]{1,80}")))
                check(o.getBoolean("pending") || (!o.getBoolean("sticky") && owner == null))
                CastCleanupRecord(owner, o.getBoolean("pending"), o.getBoolean("sticky"))
            }.getOrElse { CastCleanupRecord(pending = true, sticky = true) }
        }
    }
}

/** Persist uncertainty before a request can allocate remote resources. */
object CastSafetyStore {
    @Volatile var localReleaseUnknown = false
        private set
    private fun read(context: Context) = CastCleanupRecord.parse(context.getSharedPreferences("cast_safety", Context.MODE_PRIVATE).getString("record", null))
    private fun write(context: Context, record: CastCleanupRecord) {
        check(context.getSharedPreferences("cast_safety", Context.MODE_PRIVATE).edit().putString("record", record.json()).commit())
    }
    @Synchronized fun blocked(context: Context): Boolean = read(context).pending
    @Synchronized fun begin(context: Context, owner: String) { write(context, read(context).begin(owner)) }
    @Synchronized fun uncertain(context: Context, local: Boolean = false) {
        if (local) localReleaseUnknown = true
        write(context, read(context).uncertain())
    }
    @Synchronized fun confirmed(context: Context, owner: String): Boolean {
        val after = read(context).confirmed(owner)
        write(context, after); return !after.pending
    }
    /** Operator evidence is explicit; never described as an instrument readback. */
    @Synchronized fun operatorRecovered(context: Context) {
        check(!CastSessionStore.busy && !localReleaseUnknown)
        write(context, CastCleanupRecord())
    }
}
