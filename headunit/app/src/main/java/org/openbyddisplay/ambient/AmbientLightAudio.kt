package org.openbyddisplay.ambient

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread

/** One polling worker, one latest immutable sample. No audio file, network or implicit microphone. */
internal class AmbientLightAudio(context: Context, source: AmbientAudioSource) : AutoCloseable {
    @Volatile var sample = AmbientMusicSample()
        private set
    @Volatile var failure: String? = null
        private set
    @Volatile private var closed = false
    private val recorder: AudioRecord?
    private val worker: Thread
    init {
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            "请先授权音乐音频分析"
        }
        var owned: Visualizer? = null
        var microphone: AudioRecord? = null
        try {
            if (source == AmbientAudioSource.PLAYBACK) {
                owned = Visualizer(0)
                val size = Visualizer.getCaptureSizeRange().last()
                check(owned.setCaptureSize(size) == Visualizer.SUCCESS)
                owned.setScalingMode(Visualizer.SCALING_MODE_AS_PLAYED)
                owned.setMeasurementMode(Visualizer.MEASUREMENT_MODE_PEAK_RMS)
                check(owned.setEnabled(true) == Visualizer.SUCCESS)
            } else {
                val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(minimum > 0)
                @Suppress("MissingPermission")
                val acquired = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 4096))
                microphone = acquired
                check(acquired.state == AudioRecord.STATE_INITIALIZED)
                acquired.startRecording()
                check(acquired.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            }
        } catch (t: Exception) { owned?.release(); microphone?.release(); throw t }
        recorder = microphone
        val capture = owned
        val record = microphone
        worker = thread(name = "OpenBYD-AmbientAudio", isDaemon = true) {
            val analyzer = AmbientSpectrumAnalyzer()
            val envelope = AmbientMusicEnvelope()
            val fft = ByteArray(capture?.captureSize ?: 1024)
            val pcm = ShortArray(800)
            val rms = Visualizer.MeasurementPeakRms()
            var last = SystemClock.elapsedRealtime()
            var fftBeats = 0L
            var rmsBeats = 0L
            var totalBeats = 0L
            try {
                while (!closed) {
                    val now = SystemClock.elapsedRealtime()
                    val update = if (capture != null && capture.getFft(fft) == Visualizer.SUCCESS) {
                        analyzer.analyze(fft, capture.samplingRate, now).also {
                            totalBeats += maxOf(0, it.beats - fftBeats); fftBeats = it.beats
                        }
                    } else {
                        val amplitude = if (capture != null) {
                            check(capture.getMeasurementPeakRms(rms) == Visualizer.SUCCESS) { "音源已失效" }
                            AmbientMusicEnvelope.milliBelRms(rms.mRms)
                        } else {
                            val count = record!!.read(pcm, 0, pcm.size)
                            check(count > 0) { "麦克风音源已中断" }
                            AmbientMusicEnvelope.pcmRms(pcm, count)
                        }
                        AmbientMusicSample(envelope.update(amplitude, now - last), envelope.beatCount, null, now,
                            if (capture == null) "麦克风 RMS" else "RMS 回退").also {
                            totalBeats += maxOf(0, it.beats - rmsBeats); rmsBeats = it.beats
                        }
                    }
                    sample = update.copy(beats = totalBeats)
                    last = now
                    Thread.sleep(100)
                }
            } catch (t: Exception) { if (!closed) failure = t.message ?: "音频分析中断" }
            finally { capture?.release(); record?.release() }
        }
    }
    override fun close() {
        closed = true
        runCatching { recorder?.stop() }
        worker.interrupt()
        worker.join(2000)
        check(!worker.isAlive) { "音频资源释放未确认" }
    }
}
