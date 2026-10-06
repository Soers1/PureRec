package com.purerec.core

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface

/**
 * H.264 硬编。画面从 [inputSurface] 进来（OpenGL 渲染输出），
 * 输出在独立线程里抽出来写进 muxer。
 */
internal class VideoEncoder(
    private val muxer: MuxerWrapper,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrate: Int,
) {

    companion object {
        const val MIME = "video/avc"
        private const val TIMEOUT_US = 10_000L
    }

    val inputSurface: Surface
    private val codec: MediaCodec
    private val bufferInfo = MediaCodec.BufferInfo()

    private var trackIndex = -1
    private var drainThread: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    private var eosSignaled = false

    init {
        val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
            )
        }
        codec = MediaCodec.createEncoderByType(MIME)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
        codec.start()
    }

    fun start() {
        if (running) return
        running = true
        drainThread = Thread({ drainLoop() }, "PureRec-VideoDrain").also { it.start() }
    }

    private fun drainLoop() {
        var sawEos = false
        var idle = 0
        while (!sawEos) {
            val index = try {
                codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            } catch (_: Throwable) {
                break
            }
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    idle++
                    // EOS 之后最多再等约 1 秒，防止个别编码器不发 EOS 标志卡死
                    if (eosSignaled && idle > 100) break
                }

                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    idle = 0
                    if (trackIndex < 0) {
                        trackIndex = try {
                            muxer.addTrack(codec.outputFormat)
                        } catch (_: Throwable) {
                            -1
                        }
                    }
                }

                index >= 0 -> {
                    idle = 0
                    try {
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            bufferInfo.size = 0
                        }
                        if (bufferInfo.size > 0 && trackIndex >= 0) {
                            codec.getOutputBuffer(index)?.let { buffer ->
                                buffer.position(bufferInfo.offset)
                                buffer.limit(bufferInfo.offset + bufferInfo.size)
                                muxer.writeSampleData(trackIndex, buffer, bufferInfo)
                            }
                        }
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            sawEos = true
                        }
                    } catch (_: Throwable) {
                    } finally {
                        try {
                            codec.releaseOutputBuffer(index, false)
                        } catch (_: Throwable) {
                        }
                    }
                }
            }
        }
    }

    fun stop() {
        running = false
        if (!eosSignaled) {
            eosSignaled = true
            try {
                codec.signalEndOfInputStream()
            } catch (_: Throwable) {
            }
        }
        try {
            drainThread?.join(4000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        drainThread = null
        try {
            codec.stop()
        } catch (_: Throwable) {
        }
        try {
            codec.release()
        } catch (_: Throwable) {
        }
        try {
            inputSurface.release()
        } catch (_: Throwable) {
        }
    }
}
