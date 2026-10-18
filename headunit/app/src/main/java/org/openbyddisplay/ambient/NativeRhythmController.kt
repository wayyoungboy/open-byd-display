package org.openbyddisplay.ambient

/** A runtime mapping can be added only after stop, feedback, manual override and recovery tests. */
internal data class NativeRhythmProfile(val fingerprint: String, val evidence: String,
    val modeRead: String, val stateRead: String, val spectrumRead: List<String>) {
    init {
        require(fingerprint.isNotBlank() && evidence.isNotBlank())
        require(modeRead.isNotBlank() && stateRead.isNotBlank() && spectrumRead.size == 16)
        require((spectrumRead + modeRead + stateRead).distinct().size == 18)
    }
}

internal object NativeRhythmProfiles {
    // No vehicle has been qualified for this project. Intents/preferences cannot enable this path.
    private val verified: List<NativeRhythmProfile> = emptyList()
    fun forFingerprint(fingerprint: String) = verified.singleOrNull { it.fingerprint == fingerprint }
}

data class NativeRhythmState(val mode: Int, val state: Int, val spectrum: AmbientSpectrum,
    val lamps: AmbientSnapshot) {
    init { require(mode in 0..255 && state in 0..255) }
    fun isTransitionBetween(old: NativeRhythmState, target: NativeRhythmState): Boolean {
        val actualBands = spectrum.values()
        val oldBands = old.spectrum.values()
        val targetBands = target.spectrum.values()
        return lamps == old.lamps && lamps == target.lamps &&
            (mode == old.mode || mode == target.mode) && (state == old.state || state == target.state) &&
            actualBands.indices.all { actualBands[it] == oldBands[it] || actualBands[it] == targetBands[it] }
    }
}

interface NativeRhythmIo {
    fun read(): NativeRhythmState
    fun control(mode: Int, state: Int)
    fun spectrum(values: AmbientSpectrum)
    fun nowMs(): Long
    fun sleepMs(milliseconds: Long)
}

/** Experimental backend contract, exercised with fixtures; runtime allowlist remains empty. */
class NativeRhythmController(private val io: NativeRhythmIo) {
    val initial = io.read()
    private var current = initial
    private var pending: NativeRhythmState? = null
    private var changed = false
    private var overridden = false
    private var closed = false
    private var result: AmbientRestoration? = null
    init { check(initial.mode != 2 || initial.state != 1) { "已有原生律动控制器运行" } }

    private fun manual(): Nothing { overridden = true; throw AmbientManualOverride() }
    fun keep() {
        check(!closed && pending == null)
        if (io.read() != current) manual()
    }
    private fun write(expected: NativeRhythmState, command: () -> Unit) {
        if (io.read() != current) manual()
        pending = expected
        changed = true
        command()
        val deadline = io.nowMs() + 1600
        do {
            io.sleepMs(70)
            val actual = io.read()
            if (actual == expected) { current = actual; pending = null; return }
            if (!actual.isTransitionBetween(current, expected)) manual()
        } while (io.nowMs() < deadline)
        error("原生律动反馈未确认")
    }
    fun spectrum(values: AmbientSpectrum) {
        keep()
        if (current.mode != 2 || current.state != 1)
            write(current.copy(mode = 2, state = 1)) { io.control(2, 1) }
        if (current.spectrum != values)
            write(current.copy(spectrum = values)) { io.spectrum(values) }
    }
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
                    write(current.copy(mode = 2, state = 2)) { io.control(2, 2) }
                    write(current.copy(spectrum = initial.spectrum)) { io.spectrum(initial.spectrum) }
                    write(initial) { io.control(initial.mode, initial.state) }
                }
                check(io.read() == initial)
                AmbientRestoration.RESTORED
            }
        } catch (_: AmbientManualOverride) { AmbientRestoration.OVERRIDDEN }
        catch (_: Exception) { AmbientRestoration.UNCONFIRMED }
        return result!!
    }
}
