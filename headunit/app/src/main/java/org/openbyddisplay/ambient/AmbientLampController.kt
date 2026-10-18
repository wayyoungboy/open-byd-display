package org.openbyddisplay.ambient

interface AmbientLampIo {
    fun read(): AmbientSnapshot
    /** Raw brightness 1..6; (0,0) is reserved for restoring area selection. */
    fun write(color: Int, rawBrightness: Int, area: Int)
    fun nowMs(): Long
    fun sleepMs(milliseconds: Long)
}

class AmbientManualOverride : Exception("原生灯光设置已变化，已让出控制")
enum class AmbientRestoration { RESTORED, OVERRIDDEN, UNCONFIRMED }

/** One synchronous write at a time; acceptance is never confused with vehicle feedback. */
class AmbientLampController(private val io: AmbientLampIo) {
    val initial = io.read()
    private var current = initial
    private var pending: AmbientSnapshot? = null
    private var changed = false
    private var overridden = false
    private var closed = false
    private var result: AmbientRestoration? = null

    fun keep(): AmbientSnapshot {
        check(!closed && pending == null)
        if (io.read() != current) manual()
        return current
    }

    fun frame(frame: AmbientFrame): AmbientSnapshot {
        keep()
        current = apply(current, frame.color, frame.brightness, 3)
        return current
    }

    private fun manual(): Nothing {
        overridden = true
        throw AmbientManualOverride()
    }

    private fun apply(old: AmbientSnapshot, color: Int, brightness: Int, area: Int): AmbientSnapshot {
        val expected = if (color == 0 && brightness == 0) old.copy(area = area)
            else old.withFrame(AmbientFrame(color, brightness), area)
        if (old == expected) return old
        if (io.read() != old) manual()
        pending = expected
        changed = true // A negative/throwing write may already have partially reached the vehicle.
        io.write(color, brightness, area)
        val deadline = io.nowMs() + 1600
        do {
            io.sleepMs(70)
            val actual = io.read()
            if (actual == expected) { pending = null; return actual }
            if (!actual.isTransitionBetween(old, expected)) manual()
        } while (io.nowMs() < deadline)
        error("氛围灯状态读回未确认")
    }

    /** Idempotent. Foreign adjustments take precedence over restoring an old snapshot. */
    fun finish(): AmbientRestoration {
        result?.let { return it }
        closed = true
        result = try {
            if (overridden) AmbientRestoration.OVERRIDDEN else {
                val actual = io.read()
                if (actual != current && (pending == null || !actual.isTransitionBetween(current, pending!!))) manual()
                current = actual
                pending = null
                if (changed) {
                    if (initial.frontColor == initial.backColor && initial.frontBrightness == initial.backBrightness) {
                        current = apply(current, initial.frontColor, initial.frontBrightness, 3)
                    } else {
                        current = apply(current, initial.frontColor, initial.frontBrightness, 1)
                        current = apply(current, initial.backColor, initial.backBrightness, 2)
                    }
                    current = apply(current, 0, 0, initial.area)
                }
                check(io.read() == initial)
                AmbientRestoration.RESTORED
            }
        } catch (_: AmbientManualOverride) { AmbientRestoration.OVERRIDDEN }
        catch (_: Exception) { AmbientRestoration.UNCONFIRMED }
        return result!!
    }
}
