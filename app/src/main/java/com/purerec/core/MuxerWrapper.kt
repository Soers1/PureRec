package com.purerec.core

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/**
 * MediaMuxer 的线程安全包装。
 *
 * 视频 / 音频两条编码线程都会往这里写，必须等所有轨都拿到 outputFormat
 * 之后才能 muxer.start()，否则会抛 IllegalStateException。
 */
internal class MuxerWrapper(path: String, orientationHint: Int) {

    private val muxer: MediaMuxer = MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val lock = Object()
    private var expectedTracks = 0
    private var addedTracks = 0
    private var started = false
    private var released = false
    private val sampleCount = AtomicInteger(0)

    init {
        if (orientationHint != 0) {
            try {
                muxer.setOrientationHint(((orientationHint % 360) + 360) % 360)
            } catch (_: Throwable) {
            }
        }
    }

    fun expectTracks(count: Int) {
        synchronized(lock) { expectedTracks = count }
    }

    fun addTrack(format: MediaFormat): Int = synchronized(lock) {
        check(!released) { "muxer already released" }
        val index = muxer.addTrack(format)
        addedTracks++
        if (!started && addedTracks >= expectedTracks) {
            muxer.start()
            started = true
        }
        index
    }

    fun writeSampleData(trackIndex: Int, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        synchronized(lock) {
            if (!started || released || trackIndex < 0) return
            try {
                muxer.writeSampleData(trackIndex, buffer, info)
                sampleCount.incrementAndGet()
            } catch (_: Throwable) {
                // 单帧写入失败不致命，继续录
            }
        }
    }

    /** @return true 表示文件里有真实内容 */
    fun release(): Boolean {
        synchronized(lock) {
            if (released) return sampleCount.get() > 0
            released = true
            try {
                if (started) muxer.stop()
            } catch (_: Throwable) {
            }
            try {
                muxer.release()
            } catch (_: Throwable) {
            }
            return sampleCount.get() > 0
        }
    }
}
