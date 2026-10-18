package org.openbyddisplay.ambient

import org.junit.Assert.*
import org.junit.Test

class NativeRhythmTest {
    private val original = NativeRhythmState(0, 2, AmbientSpectrum(IntArray(16)), AmbientSnapshot(28, 7, 6, 3, 2))
    private class Vehicle(var live: NativeRhythmState) : NativeRhythmIo {
        var time = 0L
        val calls = mutableListOf<String>()
        override fun read() = live
        override fun control(mode: Int, state: Int) { calls += "$mode/$state"; live = live.copy(mode = mode, state = state) }
        override fun spectrum(values: AmbientSpectrum) { calls += "spectrum"; live = live.copy(spectrum = values) }
        override fun nowMs() = time
        override fun sleepMs(milliseconds: Long) { time += milliseconds }
    }
    @Test fun everyProductionPlatformIsUnverifiedAndDisabledByDefault() {
        for (name in listOf("", "unknown", "song-plus-dmi-2021", "tang-plus-dmi-2021", "verified"))
            assertNull(NativeRhythmProfiles.forFingerprint(name))
        assertEquals(AmbientBackendKind.SOFTWARE, AmbientConfig(AmbientEffect.MUSIC).backend)
    }
    @Test fun nativeContractStartsThenSends16BandsAndRestoresOriginalAudioState() {
        val vehicle = Vehicle(original)
        val backend = NativeRhythmController(vehicle)
        val spectrum = AmbientSpectrum(IntArray(16) { it })
        backend.spectrum(spectrum)
        assertEquals(listOf("2/1", "spectrum"), vehicle.calls)
        assertEquals(spectrum, vehicle.live.spectrum)
        backend.keep()
        assertEquals(AmbientRestoration.RESTORED, backend.finish())
        assertEquals(original, vehicle.live)
        assertEquals(listOf("2/1", "spectrum", "2/2", "spectrum", "0/2"), vehicle.calls)
    }
    @Test fun manualLampOrAudioChangesMakeNativeBackendYield() {
        for (lampChange in listOf(true, false)) {
            val vehicle = Vehicle(original)
            val backend = NativeRhythmController(vehicle)
            backend.spectrum(AmbientSpectrum(IntArray(16) { 7 }))
            vehicle.live = if (lampChange) vehicle.live.copy(lamps = original.lamps.copy(frontColor = 8))
                else vehicle.live.copy(mode = 3)
            val foreign = vehicle.live
            assertEquals(AmbientRestoration.OVERRIDDEN, backend.finish())
            assertEquals(foreign, vehicle.live)
            assertEquals(2, vehicle.calls.size)
        }
    }
    @Test fun anotherActiveNativeOwnerIsNotOverwritten() {
        val vehicle = Vehicle(original.copy(mode = 2, state = 1))
        try { NativeRhythmController(vehicle); fail() } catch (_: IllegalStateException) { }
        assertTrue(vehicle.calls.isEmpty())
    }
}
