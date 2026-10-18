package org.openbyddisplay.cast

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.view.Surface

/** One thread owns codec calls and release, including partially initialized encoders. */
class H264Encoder(private val width: Int, private val height: Int, private val fps: Int,
                  private val bitrate: Int = 3_000_000) : AutoCloseable {
    private var codec: MediaCodec? = null
    private val normalizer = AvcAccessUnits()
    private var originUs: Long? = null
    var releaseSucceeded = true
        private set
    var inputSurface: Surface? = null
        private set
    data class Frame(val au: AvcAccessUnits.Unit, val ptsMs: Long)
    fun start() {
        check(codec == null)
        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec = c
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
        c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = c.createInputSurface()
        c.start()
    }
    fun reconnect() {
        normalizer.reconnect(); originUs = null
        codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
    }
    fun next(): Frame? {
        val c = codec ?: error("编码器未启动")
        val info = MediaCodec.BufferInfo()
        // Bound each drain so stop and heartbeat cannot starve on a busy encoder.
        repeat(8) {
            val index = c.dequeueOutputBuffer(info, 10_000)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return null
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    listOf("csd-0", "csd-1").forEach { name -> c.outputFormat.getByteBuffer(name)?.let { normalizer.configuration(AvcAccessUnits.bytes(it)) } }
                }
                index >= 0 -> {
                    try {
                        check(info.size <= AvcAccessUnits.MAX && info.size >= 0)
                        val buffer = c.getOutputBuffer(index)
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset); buffer.limit(info.offset + info.size)
                            val bytes = AvcAccessUnits.bytes(buffer)
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) normalizer.configuration(bytes)
                            else normalizer.frame(bytes)?.let { au ->
                                val origin = originUs ?: info.presentationTimeUs.also { originUs = it }
                                val pts = (info.presentationTimeUs - origin) / 1000
                                require(pts >= 0)
                                return Frame(au, pts)
                            }
                        }
                        check(info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM == 0) { "编码器提前结束" }
                    } finally { c.releaseOutputBuffer(index, false) }
                }
                else -> return null
            }
        }
        return null
    }
    override fun close() {
        runCatching { codec?.stop() }
        if (runCatching { codec?.release() }.isFailure) releaseSucceeded = false
        if (runCatching { inputSurface?.release() }.isFailure) releaseSucceeded = false
        codec = null; inputSurface = null
    }
}
