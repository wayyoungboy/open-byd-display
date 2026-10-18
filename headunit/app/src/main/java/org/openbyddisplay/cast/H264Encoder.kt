package org.openbyddisplay.cast

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface

/** AVC encoder that emits Annex-B access units to a callback. */
class H264Encoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrate: Int = 8_000_000,
    private val onAccessUnit: (bytes: ByteArray, ptsUs: Long, keyFrame: Boolean) -> Unit,
) : AutoCloseable {
    private var codec: MediaCodec? = null
    private var spsPps: ByteArray? = null
    var inputSurface: Surface? = null
        private set

    fun start() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = c.createInputSurface()
        c.start()
        codec = c
    }

    fun drain(timeoutUs: Long = 10_000L) {
        val c = codec ?: return
        val info = MediaCodec.BufferInfo()
        while (true) {
            val index = c.dequeueOutputBuffer(info, timeoutUs)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                index >= 0 -> {
                    val buffer = c.getOutputBuffer(index)
                    if (buffer != null && info.size > 0) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val bytes = ByteArray(info.size)
                        buffer.get(bytes)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            spsPps = bytes
                        } else {
                            val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0 ||
                                info.flags and MediaCodec.BUFFER_FLAG_SYNC_FRAME != 0
                            val au = if (key && spsPps != null) spsPps!! + bytes else bytes
                            onAccessUnit(au, info.presentationTimeUs, key)
                        }
                    }
                    c.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> return
            }
        }
    }

    override fun close() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { inputSurface?.release() }
        codec = null
        inputSurface = null
    }
}
