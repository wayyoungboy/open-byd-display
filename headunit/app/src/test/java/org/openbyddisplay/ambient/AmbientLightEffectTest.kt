package org.openbyddisplay.ambient

import org.junit.Assert.*
import org.junit.Test

class AmbientLightEffectTest {
    private val initial = AmbientSnapshot(28, 7, 6, 3, 2)

    @Test fun chosenIntervalHoldsEachConfirmedLevelUntilItsOwnDeadline() {
        for (interval in listOf(250L, 500L, 1500L, 5000L)) {
            val engine = AmbientLightEffect(AmbientConfig(AmbientEffect.DYNAMIC, intervalMs = interval), initial)
            assertEquals(AmbientFrame(28, 1), engine.frame(0))
            engine.onFrameConfirmed(120)
            assertEquals(AmbientFrame(28, 1), engine.frame(120 + interval - 1))
            assertEquals(AmbientFrame(28, 2), engine.frame(120 + interval))
        }
    }

    @Test fun dynamicCompletesOneColorBeforeChangingAtMinimumBrightness() {
        val engine = AmbientLightEffect(AmbientConfig(AmbientEffect.DYNAMIC), initial)
        val levels = listOf(1, 2, 3, 4, 5, 6, 5, 4, 3, 2, 1)
        for ((i, brightness) in levels.withIndex()) {
            val t = i * 600L
            assertEquals(AmbientFrame(28, brightness), engine.frame(t))
            engine.onFrameConfirmed(t + 100)
            assertEquals(AmbientFrame(28, brightness), engine.frame(t + 599))
        }
        assertEquals(AmbientFrame(29, 1), engine.frame(6600))
        engine.onFrameConfirmed(6700)
        assertEquals(AmbientFrame(29, 2), engine.frame(7200))
    }

    @Test fun slowOrMissingAcknowledgmentNeverSkipsBrightnessOrCompressesDwell() {
        val engine = AmbientLightEffect(AmbientConfig(AmbientEffect.DYNAMIC), initial)
        assertEquals(AmbientFrame(28, 1), engine.frame(0))
        assertEquals(AmbientFrame(28, 1), engine.frame(60000))
        engine.onFrameConfirmed(60000)
        assertEquals(AmbientFrame(28, 1), engine.frame(60499))
        assertEquals(AmbientFrame(28, 2), engine.frame(60500))
        assertEquals(AmbientFrame(28, 2), engine.frame(120000))
        engine.onFrameConfirmed(120000)
        assertEquals(AmbientFrame(28, 2), engine.frame(120499))
        assertEquals(AmbientFrame(28, 3), engine.frame(120500))
    }

    @Test fun repeatedKeepAcknowledgmentsDoNotFreezeTheBreathingCycle() {
        val engine = AmbientLightEffect(AmbientConfig(AmbientEffect.DYNAMIC), initial)
        engine.frame(0)
        engine.onFrameConfirmed(0)
        for (t in listOf(150L, 350L, 499L)) {
            assertEquals(AmbientFrame(28, 1), engine.frame(t))
            engine.onFrameConfirmed(t)
        }
        assertEquals(AmbientFrame(28, 2), engine.frame(500))
    }

    @Test fun reducedBrightnessAndSingleLevelStillVisitAllColorsAtTheirDarkest() {
        for (maximum in 1..6) {
            val engine = AmbientLightEffect(AmbientConfig(AmbientEffect.DYNAMIC, maxBrightness = maximum), initial)
            val framesPerColor = 2 * (maximum - 1) + 1
            var previous: AmbientFrame? = null
            val colors = mutableSetOf<Int>()
            for (step in 0..31 * framesPerColor) {
                val t = step * 500L
                val frame = engine.frame(t)
                assertTrue(frame.brightness in 1..maximum)
                previous?.let {
                    if (frame.color != it.color) {
                        assertEquals(1, it.brightness)
                        assertEquals(1, frame.brightness)
                    } else assertTrue(kotlin.math.abs(frame.brightness - it.brightness) <= 1)
                }
                colors += frame.color
                engine.onFrameConfirmed(t)
                previous = frame
            }
            assertEquals(31, colors.size)
            assertEquals(28, previous!!.color)
        }
    }

    @Test fun musicChangesColorOnlyOnBeatsAndHonorsSingleColorOption() {
        val config = AmbientConfig(AmbientEffect.MUSIC, maxBrightness = 4)
        val engine = AmbientLightEffect(config, initial)
        assertEquals(AmbientFrame(28, 1), engine.frame(0, 0.0, 0))
        assertEquals(AmbientFrame(2, 4), engine.frame(900, 1.0, 1))
        assertEquals(engine.frame(900, 1.0, 1), engine.frame(5000, 1.0, 1))
        assertEquals(28, AmbientLightEffect(config.copy(musicMulticolor = false), initial).frame(900, 1.0, 1).color)
        assertEquals(1, engine.frame(0, Double.NaN).brightness)
        assertEquals(4, engine.frame(0, 5.0).brightness)
    }

    @Test fun envelopeAttacksReleasesAndDoesNotGenerateBeatsInSilence() {
        val music = AmbientMusicEnvelope()
        repeat(20) { assertEquals(0.0, music.update(0.0, 50), 0.0001) }
        assertEquals(0L, music.beatCount)
        assertTrue(music.update(0.5, 50) > 0.5)
        assertEquals(1L, music.beatCount)
        repeat(10) { music.update(0.0, 50) }
        assertTrue(music.update(0.0, 50) < 0.1)
        music.update(0.5, 50)
        assertEquals(2L, music.beatCount)
        repeat(4) { music.update(0.5, 50) }
        assertEquals(2L, music.beatCount)
    }

    @Test fun rmsUsesValidCountAndConvertsDecibels() {
        assertEquals(0.5, AmbientMusicEnvelope.pcmRms(shortArrayOf(16384, -16384, 0), 2), 0.0001)
        assertEquals(0.0, AmbientMusicEnvelope.pcmRms(shortArrayOf(1), 0), 0.0)
        assertEquals(0.1, AmbientMusicEnvelope.milliBelRms(-2000), 0.0001)
    }

    @Test fun transitionAllowsPartialCanFeedbackButRejectsForeignChanges() {
        val target = initial.withFrame(AmbientFrame(3, 5))
        assertTrue(initial.copy(frontColor = 3).isTransitionBetween(initial, target))
        assertFalse(initial.copy(frontColor = 8).isTransitionBetween(initial, target))
        assertEquals(initial, AmbientSnapshot.parse(initial.wire()))
        assertEquals(AmbientSnapshot(28, 3, 6, 5, 2), initial.withFrame(AmbientFrame(3, 5), 2))
    }

    @Test(expected = IllegalArgumentException::class) fun invalidVehicleFeedbackCannotBecomeBaseline() {
        AmbientSnapshot(65535, 28, 6, 6, 3)
    }
}
