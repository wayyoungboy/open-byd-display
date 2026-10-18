package org.openbyddisplay.vehicle

internal data class HeadlightSnapshot(val native: Int, val preference: Int, val actual: Int, val beam: Int)
internal interface HeadlightIo {
    fun read(): HeadlightSnapshot
    fun activate(mode: Int): Boolean
    fun naturalMode(): Int
}
internal enum class HeadlightRestoration { RESTORED, YIELDED, UNCHANGED, UNCONFIRMED }

/** Ownership is registered before a potentially mutating call, including false/throwing calls. */
internal class HeadlightFollowController(private val io: HeadlightIo) {
    private var possibleWrite = false
    private var yielded = false
    private var ownedMode: Int? = null
    private var pending = false
    private var lastBeam = -1
    private var stable = 0
    private var result: HeadlightRestoration? = null
    private fun manual(s: HeadlightSnapshot) = s.native in 1..2 || s.preference in 1..2
    private fun readableAuto(s: HeadlightSnapshot) = s.native == 0 && s.preference == 0 && s.actual in 1..2
    private fun yield() { possibleWrite = false; pending = false; ownedMode = null; yielded = true }

    fun poll(): Pair<HeadlightSnapshot, Boolean> {
        check(result == null)
        var s = io.read()
        if (manual(s)) yield() // Unknown values never release ownership.
        stable = if (s.beam in 0..1 && s.beam == lastBeam) stable + 1 else 1
        lastBeam = s.beam
        val target = HeadlightFollowPolicy.targetNightMode(s.native, s.beam, s.preference)
        if (target != null && stable >= 2 && readableAuto(s) && s.actual != target) {
            s = io.read()
            if (manual(s)) yield()
            if (readableAuto(s) && s.beam == lastBeam) {
                possibleWrite = true
                ownedMode = target
                pending = true
                check(io.activate(target)) { "Auto 临时切换未接受" }
                s = io.read()
                check(readableAuto(s) && s.actual == target) { "深浅模式读回未确认" }
                pending = false
            }
        }
        val actualTarget = HeadlightFollowPolicy.targetNightMode(s.native, s.beam, s.preference)
        return s to (actualTarget != null && readableAuto(s) && stable >= 2 && s.beam == lastBeam && s.actual == actualTarget)
    }
    fun finish(): HeadlightRestoration {
        result?.let { return it }
        result = try {
            if (!possibleWrite) {
                if (yielded) HeadlightRestoration.YIELDED else HeadlightRestoration.UNCHANGED
            } else {
                val current = io.read()
                when {
                    manual(current) -> { yield(); HeadlightRestoration.YIELDED }
                    !readableAuto(current) -> HeadlightRestoration.UNCONFIRMED
                    !pending && current.actual != ownedMode -> { yield(); HeadlightRestoration.YIELDED }
                    else -> {
                        val natural = io.naturalMode()
                        check(natural in 1..2)
                        val latest = io.read()
                        when {
                            manual(latest) -> { yield(); HeadlightRestoration.YIELDED }
                            !readableAuto(latest) -> HeadlightRestoration.UNCONFIRMED
                            !pending && latest.actual != ownedMode -> { yield(); HeadlightRestoration.YIELDED }
                            else -> {
                                pending = true; ownedMode = natural
                                check(io.activate(natural)) { "自然 Auto 恢复未接受" }
                                val confirmed = io.read()
                                when {
                                    manual(confirmed) -> { yield(); HeadlightRestoration.YIELDED }
                                    readableAuto(confirmed) && confirmed.actual == natural -> HeadlightRestoration.RESTORED
                                    else -> HeadlightRestoration.UNCONFIRMED
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) { HeadlightRestoration.UNCONFIRMED }
        return result!!
    }
}

internal object HeadlightProtocol {
    fun terminal(line: String): HeadlightRestoration? = when {
        line == "STOPPED" || line.startsWith("ERROR RESTORED ") -> HeadlightRestoration.RESTORED
        line == "UNCHANGED" || line.startsWith("ERROR UNCHANGED ") -> HeadlightRestoration.UNCHANGED
        line == "YIELDED" -> HeadlightRestoration.YIELDED
        line.startsWith("ERROR UNCONFIRMED ") -> HeadlightRestoration.UNCONFIRMED
        else -> null
    }
    fun confirmed(line: String) = terminal(line) in setOf(HeadlightRestoration.RESTORED,
        HeadlightRestoration.UNCHANGED, HeadlightRestoration.YIELDED)
    fun describe(line: String) = when (terminal(line)) {
        HeadlightRestoration.RESTORED -> "已停止，自然 Auto 已读回确认"
        HeadlightRestoration.UNCHANGED -> "已退出，未改变系统深浅模式"
        HeadlightRestoration.YIELDED -> "已让出控制，保留原生或系统的新设置"
        else -> "原生模式恢复未确认：${line.take(160)}"
    }
}
