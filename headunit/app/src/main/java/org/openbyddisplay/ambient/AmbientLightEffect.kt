package org.openbyddisplay.ambient

import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class AmbientBackendKind { SOFTWARE, NATIVE }

enum class AmbientEffect(val label: String) { DYNAMIC("动态多色"), MUSIC("音乐律动") }
enum class AmbientAudioSource(val label: String) { PLAYBACK("车机播放声音"), MICROPHONE("车内麦克风") }

data class AmbientConfig(
    val effect: AmbientEffect,
    val maxBrightness: Int = 6,
    val audioSource: AmbientAudioSource = AmbientAudioSource.PLAYBACK,
    val musicMulticolor: Boolean = true,
    val intervalMs: Long = 500,
    val backend: AmbientBackendKind = AmbientBackendKind.SOFTWARE
) {
    init {
        require(maxBrightness in 1..6 && intervalMs in 250..5000)
        require(backend == AmbientBackendKind.SOFTWARE || effect == AmbientEffect.MUSIC)
        require(backend == AmbientBackendKind.SOFTWARE || audioSource == AmbientAudioSource.PLAYBACK)
    }
}

/** Values are the vehicle's 31-color palette and six raw brightness levels, not RGB. */
data class AmbientFrame(val color: Int, val brightness: Int) {
    init { require(color in 1..31 && brightness in 1..6) }
}

data class AmbientSnapshot(val frontColor: Int, val backColor: Int,
    val frontBrightness: Int, val backBrightness: Int, val area: Int) {
    init {
        require(frontColor in 1..31 && backColor in 1..31 &&
            frontBrightness in 1..6 && backBrightness in 1..6 && area in 1..3) {
            "车机未返回有效的氛围灯状态"
        }
    }
    fun fields() = listOf(frontColor, backColor, frontBrightness, backBrightness, area)
    fun wire() = fields().joinToString(" ")
    fun withFrame(frame: AmbientFrame, targetArea: Int = 3): AmbientSnapshot = copy(
        frontColor = if (targetArea != 2) frame.color else frontColor,
        backColor = if (targetArea != 1) frame.color else backColor,
        frontBrightness = if (targetArea != 2) frame.brightness else frontBrightness,
        backBrightness = if (targetArea != 1) frame.brightness else backBrightness,
        area = targetArea)

    /** During CAN delivery a read can contain old and new components. */
    fun isTransitionBetween(old: AmbientSnapshot, target: AmbientSnapshot): Boolean =
        fields().zip(old.fields().zip(target.fields())).all { (actual, pair) ->
            actual == pair.first || actual == pair.second
        }

    companion object {
        fun parse(text: String): AmbientSnapshot {
            val v = text.trim().split(' ').map { it.toInt() }
            require(v.size == 5)
            return AmbientSnapshot(v[0], v[1], v[2], v[3], v[4])
        }
    }
}

class AmbientLightEffect(private val config: AmbientConfig, private val initial: AmbientSnapshot) {
    private var dynamicFrame = AmbientFrame(initial.frontColor, 1)
    private var dynamicStep = -1
    private var pendingStep = 0
    private var pendingFrame: AmbientFrame? = null
    private var nextStepAtMs = Long.MAX_VALUE

    fun frame(elapsedMs: Long, musicLevel: Double = 0.0, beatCount: Long = 0): AmbientFrame {
        val elapsed = elapsedMs.coerceAtLeast(0)
        if (config.effect == AmbientEffect.DYNAMIC) {
            pendingFrame?.let { return it }
            if (dynamicStep >= 0 && elapsed < nextStepAtMs) return dynamicFrame
            // A whole color cycle: 1,2,...,max,...,2,1, then the next color at 1.
            // Advance only after feedback, never jump ahead to catch up with a clock.
            val range = config.maxBrightness - 1
            pendingStep = if (dynamicStep < 0) 0 else (dynamicStep + 1) % (2 * range + 1)
            val color = if (dynamicStep >= 0 && pendingStep == 0) dynamicFrame.color % 31 + 1
                else dynamicFrame.color
            val brightness = 1 + if (pendingStep <= range) pendingStep else 2 * range - pendingStep
            return AmbientFrame(color, brightness).also { pendingFrame = it }
        }
        val offset = if (config.musicMulticolor) (beatCount.coerceAtLeast(0) % 31) * 5 else 0
        val color = ((initial.frontColor - 1 + offset) % 31 + 1).toInt()
        val level = if (musicLevel.isFinite()) musicLevel.coerceIn(0.0, 1.0) else 0.0
        return AmbientFrame(color, 1 + ((config.maxBrightness - 1) * level).roundToInt())
    }

    /** Called after both lamp feedback values match. KEEP replies do not restart the dwell. */
    fun onFrameConfirmed(elapsedMs: Long) {
        val accepted = pendingFrame ?: return
        dynamicFrame = accepted
        dynamicStep = pendingStep
        pendingFrame = null
        nextStepAtMs = elapsedMs.coerceAtLeast(0) + config.intervalMs
    }
}

/** Adaptive input gain with quick attack and a slower release. No audio is retained. */
class AmbientMusicEnvelope {
    private var peak = 0.05
    private var level = 0.0
    private var average = 0.02
    private var clockMs = 0L
    private var lastBeatMs = -1000L
    var beatCount = 0L
        private set
    fun update(rms: Double, elapsedMs: Long): Double {
        val dt = elapsedMs.coerceIn(1, 1000).toDouble()
        val amplitude = if (rms.isFinite()) rms.coerceIn(0.0, 1.0) else 0.0
        clockMs += dt.toLong()
        if (amplitude > maxOf(0.008, average * 1.45) && clockMs - lastBeatMs >= 550) {
            beatCount++
            lastBeatMs = clockMs
        }
        average += (amplitude - average) * (1 - 0.5.pow(dt / 600))
        peak = maxOf(amplitude, peak * 0.5.pow(dt / 4000), 0.015)
        val target = if (amplitude < 0.002) 0.0 else (amplitude / peak).coerceIn(0.0, 1.0)
        val retention = 0.5.pow(dt / if (target > level) 35.0 else 180.0)
        level = (target + (level - target) * retention).coerceIn(0.0, 1.0)
        return level
    }
    companion object {
        fun pcmRms(samples: ShortArray, count: Int): Double {
            require(count in 0..samples.size)
            if (count == 0) return 0.0
            var squares = 0.0
            for (i in 0 until count) { val v = samples[i] / 32768.0; squares += v * v }
            return sqrt(squares / count)
        }
        fun milliBelRms(rms: Int): Double = 10.0.pow(rms.coerceIn(-9600, 0) / 2000.0)
    }
}
