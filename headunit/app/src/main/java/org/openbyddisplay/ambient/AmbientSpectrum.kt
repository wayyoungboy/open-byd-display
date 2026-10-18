package org.openbyddisplay.ambient

import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Immutable spectrum. Audio buffers and mutable arrays never cross the worker boundary. */
class AmbientSpectrum(values: IntArray) {
    private val data = values.copyOf()
    init { require(data.size == 16 && data.all { it in 0..15 }) }
    fun values(): IntArray = data.copyOf()
    override fun equals(other: Any?) = other is AmbientSpectrum && data.contentEquals(other.data)
    override fun hashCode() = data.contentHashCode()
}

data class AmbientMusicSample(val level: Double = 0.0, val beats: Long = 0,
    val spectrum: AmbientSpectrum? = null, val capturedAtMs: Long = 0, val analysis: String = "等待音源")

/** Android's packed FFT: DC at byte 0, Nyquist at byte 1; signed real/imag pairs thereafter. */
class AmbientSpectrumAnalyzer {
    private val previous = DoubleArray(16)
    private val smooth = DoubleArray(16)
    private var peak = 0.08
    private var fluxAverage = 0.01
    private var envelope = 0.0
    private var beats = 0L
    private var lastBeat = Long.MIN_VALUE
    private var lastTime: Long? = null

    fun analyze(fft: ByteArray, sampleRateMilliHz: Int, nowMs: Long): AmbientMusicSample {
        require(fft.size in 128..32768 && fft.size.countOneBits() == 1)
        require(sampleRateMilliHz in 8_000_000..192_000_000 && nowMs >= 0)
        val dt = lastTime?.let { (nowMs - it).coerceIn(1, 1000).toDouble() } ?: 100.0
        require(lastTime == null || nowMs >= lastTime!!)
        lastTime = nowMs
        val power = DoubleArray(16)
        val counts = IntArray(16)
        for (bin in 1 until fft.size / 2) {
            val hz = bin * (sampleRateMilliHz / 1000.0) / fft.size
            var band = 0
            while (band < 16 && hz >= edges[band + 1]) band++
            if (band >= 16 || hz < edges[band]) continue
            val amplitude = hypot(fft[2 * bin].toDouble(), fft[2 * bin + 1].toDouble()) / 128.0
            power[band] += amplitude * amplitude
            counts[band]++
        }
        val raw = DoubleArray(16) { if (counts[it] == 0) 0.0 else sqrt(power[it] / counts[it]) }
        val maximum = raw.maxOrNull() ?: 0.0
        peak = maxOf(0.04, maximum, peak * exp(-dt / 4000.0))
        var flux = 0.0
        for (band in raw.indices) {
            val value = if (maximum < 0.012) 0.0 else (raw[band] / peak).coerceIn(0.0, 1.0)
            // A low-frequency onset matters more than sustained speech or high-frequency noise.
            flux += maxOf(0.0, value - previous[band]) * if (band < 6) 2.0 else 0.5
            previous[band] = value
            val tau = if (value > smooth[band]) 45.0 else 220.0
            smooth[band] += (value - smooth[band]) * (1 - exp(-dt / tau))
        }
        flux /= 16.0
        if (maximum >= 0.012 && flux > maxOf(0.025, fluxAverage * 1.8) &&
            (lastBeat == Long.MIN_VALUE || nowMs - lastBeat >= 350)) {
            beats++
            lastBeat = nowMs
        }
        fluxAverage += (flux - fluxAverage) * (1 - exp(-dt / 650.0))
        val bass = sqrt(smooth.take(6).sumOf { it * it } / 6)
        val full = sqrt(smooth.sumOf { it * it } / 16)
        val target = (bass * 0.65 + full * 0.35 + flux * 0.75).coerceIn(0.0, 1.0)
        envelope += (target - envelope) * (1 - exp(-dt / if (target > envelope) 40.0 else 200.0))
        return AmbientMusicSample(envelope, beats,
            AmbientSpectrum(IntArray(16) { (smooth[it] * 15).roundToInt().coerceIn(0, 15) }), nowMs, "16 频带")
    }

    companion object {
        // Independent band layout. Empty bands stay zero when capture resolution is insufficient.
        val edges: List<Int> = listOf(20, 45, 65, 90, 125, 180, 250, 355, 500,
            710, 1000, 1420, 2000, 2840, 4000, 8000, 16000)
    }
}
