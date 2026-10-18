package org.openbyddisplay.ambient

import org.junit.Assert.*
import org.junit.Test

class AmbientSpectrumTest {
    private fun tone(bin: Int, value: Int = 100) = ByteArray(1024).also { it[2 * bin] = value.toByte() }
    @Test fun packedFftUsesSignedComplexBinsAndMilliHertzWithoutDcLeak() {
        val fft = tone(2).also { it[0] = 127; it[1] = 127; it[4] = (-80).toByte(); it[5] = (-60).toByte() }
        val sample = AmbientSpectrumAnalyzer().analyze(fft, 48_000_000, 100)
        val values = sample.spectrum!!.values()
        // 2 * 48000 / 1024 = 93.75 Hz, in [90,125); DC/Nyquist are excluded.
        assertEquals(3, values.indices.maxBy { values[it] })
        assertTrue(values[3] > 0)
        assertEquals(1, values.count { it > 0 })
    }
    @Test fun bassOnsetHasMoreEffectThanAnEquallyStrongTrebleTone() {
        val bass = AmbientSpectrumAnalyzer().analyze(tone(2), 48_000_000, 100)
        val treble = AmbientSpectrumAnalyzer().analyze(tone(128), 48_000_000, 100)
        assertTrue(bass.level > treble.level)
    }
    @Test fun sustainedAudioDoesNotCreateAStreamOfNewBeatsAndSilenceDecays() {
        val analyzer = AmbientSpectrumAnalyzer()
        var sample = analyzer.analyze(tone(2), 48_000_000, 0)
        assertEquals(1L, sample.beats)
        repeat(40) { sample = analyzer.analyze(tone(2), 48_000_000, (it + 1) * 100L) }
        assertEquals(1L, sample.beats)
        repeat(40) { sample = analyzer.analyze(ByteArray(1024), 48_000_000, 4100 + it * 100L) }
        assertTrue(sample.level < 0.001)
        assertTrue(sample.spectrum!!.values().all { it == 0 })
        sample = analyzer.analyze(tone(2), 48_000_000, 8200)
        assertEquals(2L, sample.beats)
    }
    @Test fun outputIsBoundedAndCaptureBufferIsNeverRetained() {
        val data = tone(2)
        val sample = AmbientSpectrumAnalyzer().analyze(data, 48_000_000, 0)
        val spectrum = sample.spectrum!!
        val original = spectrum.values()
        data.fill(0)
        spectrum.values().fill(99)
        assertArrayEquals(original, spectrum.values())
        assertTrue(sample.level in 0.0..1.0)
        assertTrue(original.all { it in 0..15 })
    }
    @Test fun silentUnresolvedLowBandsStayZero() {
        val sample = AmbientSpectrumAnalyzer().analyze(ByteArray(1024), 48_000_000, 0)
        assertEquals(0.0, sample.level, 0.0)
        assertEquals(0L, sample.beats)
        assertArrayEquals(IntArray(16), sample.spectrum!!.values())
    }
    @Test fun malformedSizeRateClockAndNativeValuesAreRejected() {
        for (block in listOf<() -> Unit>(
            { AmbientSpectrumAnalyzer().analyze(ByteArray(500), 48_000_000, 0) },
            { AmbientSpectrumAnalyzer().analyze(ByteArray(1024), 48000, 0) },
            { AmbientSpectrum(IntArray(15)) }, { AmbientSpectrum(IntArray(16) { 16 }) },
            { AmbientSpectrumAnalyzer().apply { analyze(tone(2), 48_000_000, 100) }.analyze(tone(2), 48_000_000, 50) }
        )) { try { block(); fail("bad input accepted") } catch (_: IllegalArgumentException) { } }
    }
}
