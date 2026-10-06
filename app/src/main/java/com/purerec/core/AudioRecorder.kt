package com.purerec.core

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/**
 * 音频采集 + 混音 + AAC 编码。
 *
 * 支持三种组合：
 *  - 只录麦克风
 *  - 只录系统内部声音（Android 10+，AudioPlaybackCapture）
 *  - 两者同时录 → 在同一线程里逐帧做饱和加法混音
 *
 * 全链路单线程：读 PCM → 混音 → 喂 AAC 编码器 → 抽输出写 muxer。
 */
@SuppressLint("MissingPermission")
internal class AudioRecorder(
    private val muxer: MuxerWrapper,
    private val projection: MediaProjection,
    private val useMic: Boolean,
    private val useInternal: Boolean,
    private val enhanceMic: Boolean = false,
    private val sampleRate: Int = 44_100,
) {

    companion object {
        const val MIME = "audio/mp4a-latm"
        private const val FRAMES_PER_BUFFER = 1024
    }

    private var codec: MediaCodec? = null
    private var micRecord: AudioRecord? = null
    private var internalRecord: AudioRecord? = null
    private var channelCount = 2
    private var thread: Thread? = null

    private var gainControl: android.media.audiofx.AutomaticGainControl? = null
    private var noiseSuppressor: android.media.audiofx.NoiseSuppressor? = null

    @Volatile
    private var running = false

    /**
     * 暂停中：麦克风/内录都停下，改喂静音 PCM。
     * 这样时间轴不断，合并后音画依然对齐。
     */
    @Volatile
    var paused = false

    private var trackIndex = -1
    private val bufferInfo = MediaCodec.BufferInfo()
    private var lastPtsUs = 0L

    fun start() {
        setupRecords()
        codec = createCodec(channelCount)
        startRecords()
        applyMicEnhance()
        running = true
        thread = Thread({ loop() }, "PureRec-Audio").also { it.start() }
    }

    /**
     * 麦克风增强：自动增益（AGC）+ 降噪（NS）。
     * 有些机型/音源不支持，拿不到就直接跳过，绝不影响录制。
     */
    private fun applyMicEnhance() {
        if (!enhanceMic) return
        val mic = micRecord ?: return
        try {
            if (android.media.audiofx.AutomaticGainControl.isAvailable()) {
                gainControl =
                    android.media.audiofx.AutomaticGainControl.create(mic.audioSessionId)
                gainControl?.enabled = true
            }
        } catch (_: Throwable) {
            gainControl = null
        }
        try {
            if (android.media.audiofx.NoiseSuppressor.isAvailable()) {
                noiseSuppressor = android.media.audiofx.NoiseSuppressor.create(mic.audioSessionId)
                noiseSuppressor?.enabled = true
            }
        } catch (_: Throwable) {
            noiseSuppressor = null
        }
    }

    private fun releaseEffects() {
        try {
            gainControl?.enabled = false
            gainControl?.release()
        } catch (_: Throwable) {
        }
        try {
            noiseSuppressor?.enabled = false
            noiseSuppressor?.release()
        } catch (_: Throwable) {
        }
        gainControl = null
        noiseSuppressor = null
    }

    // ---------------------------------------------------------------- 初始化

    private fun setupRecords() {
        var channels = 2
        var mic: AudioRecord? = null
        var internal: AudioRecord? = null

        try {
            if (useMic) mic = createMicRecord(channels)
            if (useInternal && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                internal = createInternalRecord(channels)
            }
        } catch (_: Throwable) {
            safeRelease(mic)
            safeRelease(internal)
            // 立体声不支持的机器回退单声道
            channels = 1
            mic = null
            internal = null
            if (useMic) mic = createMicRecord(channels)
            if (useInternal && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                internal = createInternalRecord(channels)
            }
        }

        if (mic == null && internal == null) {
            throw IllegalStateException("音频源初始化失败")
        }

        channelCount = channels
        micRecord = mic
        internalRecord = internal
    }

    private fun channelMask(channels: Int): Int =
        if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO

    private fun bufferBytes(channels: Int): Int {
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, channelMask(channels), AudioFormat.ENCODING_PCM_16BIT
        )
        val wanted = FRAMES_PER_BUFFER * channels * 2 * 4
        return max(minBuf * 2, wanted).coerceAtLeast(4096)
    }

    @Suppress("DEPRECATION")
    private fun createMicRecord(channels: Int): AudioRecord {
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelMask(channels),
            AudioFormat.ENCODING_PCM_16BIT,
            bufferBytes(channels)
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            safeRelease(record)
            throw IllegalStateException("麦克风不可用")
        }
        return record
    }

    private fun createInternalRecord(channels: Int): AudioRecord {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw IllegalStateException("系统内部声音需要 Android 10 及以上")
        }
        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask(channels))
            .build()

        val record = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferBytes(channels))
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build()

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            safeRelease(record)
            throw IllegalStateException("系统内部声音不可用")
        }
        return record
    }

    private fun createCodec(channels: Int): MediaCodec {
        val format = MediaFormat.createAudioFormat(MIME, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, if (channels == 2) 192_000 else 96_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65_536)
        }
        val c = MediaCodec.createEncoderByType(MIME)
        c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        c.start()
        return c
    }

    private fun startRecords() {
        micRecord?.let {
            if (it.recordingState != AudioRecord.RECORDSTATE_RECORDING) it.startRecording()
        }
        internalRecord?.let {
            if (it.recordingState != AudioRecord.RECORDSTATE_RECORDING) it.startRecording()
        }
    }

    // ---------------------------------------------------------------- 采集循环

    private fun loop() {
        val channels = channelCount
        val size = FRAMES_PER_BUFFER * channels
        val mix = ShortArray(size)
        val tmp = ShortArray(size)
        val frameUs = FRAMES_PER_BUFFER * 1_000_000L / sampleRate
        var ptsUs = 0L

        while (running) {
            java.util.Arrays.fill(mix, 0)

            if (paused) {
                // 暂停：不读任何音源，按正常节奏喂静音，保证时间轴连贯
                try {
                    Thread.sleep((frameUs / 1000).coerceAtLeast(1L))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
                encode(mix, ptsUs)
                ptsUs += frameUs
                lastPtsUs = ptsUs
                continue
            }

            val mic = micRecord
            val internal = internalRecord

            try {
                if (mic != null) {
                    mic.read(mix, 0, size)
                } else if (internal != null) {
                    internal.read(mix, 0, size)
                }

                if (mic != null && internal != null) {
                    val read = internal.read(tmp, 0, size)
                    if (read > 0) {
                        for (i in 0 until min(read, size)) {
                            mix[i] = (mix[i] + tmp[i]).coerceIn(-32768, 32767).toShort()
                        }
                    }
                }
            } catch (_: Throwable) {
                // 单次读失败不影响后续
            }

            encode(mix, ptsUs)
            ptsUs += frameUs
            lastPtsUs = ptsUs
        }

        finish()
    }

    private fun encode(data: ShortArray, ptsUs: Long) {
        val c = codec ?: return
        val totalBytes = data.size * 2

        val pcm = ByteBuffer.allocate(totalBytes).order(ByteOrder.LITTLE_ENDIAN)
        pcm.asShortBuffer().put(data)

        var offset = 0
        var guard = 0
        while (offset < totalBytes && guard < 64) {
            guard++
            val inputIndex = try {
                c.dequeueInputBuffer(10_000)
            } catch (_: Throwable) {
                -1
            }
            if (inputIndex < 0) {
                drain(false)
                continue
            }
            val input = try {
                c.getInputBuffer(inputIndex)
            } catch (_: Throwable) {
                null
            }
            if (input == null) {
                drain(false)
                continue
            }
            input.clear()
            val chunk = min(input.remaining(), totalBytes - offset)
            pcm.position(offset)
            pcm.limit(offset + chunk)
            input.put(pcm)
            val sampleOffsetUs = (offset / (channelCount * 2)) * 1_000_000L / sampleRate
            try {
                c.queueInputBuffer(inputIndex, 0, chunk, ptsUs + sampleOffsetUs, 0)
            } catch (_: Throwable) {
            }
            offset += chunk
        }
        drain(false)
    }

    /**
     * @param endOfStream true 时一直抽到 EOS
     */
    private fun drain(endOfStream: Boolean) {
        val c = codec ?: return
        var idle = 0
        while (true) {
            val index = try {
                c.dequeueOutputBuffer(bufferInfo, if (endOfStream) 10_000L else 0L)
            } catch (_: Throwable) {
                return
            }

            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                    idle++
                    if (idle > 120) return
                }

                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    idle = 0
                    if (trackIndex < 0) {
                        trackIndex = try {
                            muxer.addTrack(c.outputFormat)
                        } catch (_: Throwable) {
                            -1
                        }
                    }
                }

                index >= 0 -> {
                    idle = 0
                    var eos = false
                    try {
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            bufferInfo.size = 0
                        }
                        if (bufferInfo.size > 0 && trackIndex >= 0) {
                            c.getOutputBuffer(index)?.let { buffer ->
                                buffer.position(bufferInfo.offset)
                                buffer.limit(bufferInfo.offset + bufferInfo.size)
                                muxer.writeSampleData(trackIndex, buffer, bufferInfo)
                            }
                        }
                        eos = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    } catch (_: Throwable) {
                    } finally {
                        try {
                            c.releaseOutputBuffer(index, false)
                        } catch (_: Throwable) {
                        }
                    }
                    if (eos) return
                }
            }
        }
    }

    private fun finish() {
        val c = codec ?: return
        try {
            val index = c.dequeueInputBuffer(20_000)
            if (index >= 0) {
                c.queueInputBuffer(index, 0, 0, lastPtsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
        } catch (_: Throwable) {
        }
        drain(true)
    }

    fun stop() {
        running = false
        try {
            thread?.join(3000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        thread = null

        releaseEffects()

        try {
            micRecord?.let {
                if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop()
            }
        } catch (_: Throwable) {
        }
        try {
            internalRecord?.let {
                if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop()
            }
        } catch (_: Throwable) {
        }

        safeRelease(micRecord)
        safeRelease(internalRecord)
        micRecord = null
        internalRecord = null

        try {
            codec?.stop()
        } catch (_: Throwable) {
        }
        try {
            codec?.release()
        } catch (_: Throwable) {
        }
        codec = null
    }

    private fun safeRelease(record: AudioRecord?) {
        try {
            record?.release()
        } catch (_: Throwable) {
        }
    }
}
