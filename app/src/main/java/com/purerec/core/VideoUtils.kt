package com.purerec.core

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.util.Size
import kotlin.math.max
import kotlin.math.min

/**
 * 编码能力探测 + 分辨率收敛。
 *
 * 「按手机最大分辨率录制」这件事不能硬来：不同 SoC 的 H.264 编码器对
 * 最大尺寸、对齐要求都不一样，直接丢一个 1440x3200 过去会 config 失败。
 * 所以这里先查编码器真实支持范围，再逐级往下收敛。
 */
object VideoUtils {

    private const val MIME = "video/avc"

    fun avcCapabilities(): MediaCodecInfo.CodecCapabilities? {
        return try {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            for (info in list.codecInfos) {
                if (!info.isEncoder) continue
                for (type in info.supportedTypes) {
                    if (type.equals(MIME, ignoreCase = true)) {
                        return info.getCapabilitiesForType(MIME)
                    }
                }
            }
            null
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * 在设备最大分辨率基础上，收敛到一个编码器真正吃得下的尺寸。
     */
    fun resolveSize(desiredWidth: Int, desiredHeight: Int): Size {
        var w = desiredWidth - desiredWidth % 2
        var h = desiredHeight - desiredHeight % 2
        if (w < 2) w = 2
        if (h < 2) h = 2

        val caps = avcCapabilities()?.videoCapabilities ?: return Size(w, h)

        if (caps.isSizeSupported(w, h)) return Size(w, h)

        // 1) 先把宽高各自钳进支持范围（lower / upper 是编码器声明的上下限）
        val rw = caps.supportedWidths
        val rh = caps.supportedHeights
        val loW = rw.lower.toInt()
        val hiW = rw.upper.toInt()
        val loH = rh.lower.toInt()
        val hiH = rh.upper.toInt()
        if (loW <= hiW) w = w.coerceIn(loW, hiW)
        if (loH <= hiH) h = h.coerceIn(loH, hiH)
        w -= w % 2
        h -= h % 2
        if (caps.isSizeSupported(w, h)) return Size(w, h)

        // 2) 按比例逐级缩小
        var scale = 0.94
        while (scale > 0.2) {
            val tw = (desiredWidth * scale).toInt() / 2 * 2
            val th = (desiredHeight * scale).toInt() / 2 * 2
            if (tw >= 2 && th >= 2 && caps.isSizeSupported(tw, th)) return Size(tw, th)
            scale -= 0.04
        }

        // 3) 16 对齐再试一轮（部分编码器要求）
        var a = min(w, desiredWidth) / 16 * 16
        var b = min(h, desiredHeight) / 16 * 16
        if (a >= 16 && b >= 16 && caps.isSizeSupported(a, b)) return Size(a, b)

        // 4) 保底
        return Size(1280, 720)
    }

    /**
     * 按档位算出目标尺寸，再交给编码器能力收敛。
     */
    fun targetSize(preset: ResolutionPreset, maxWidth: Int, maxHeight: Int): Size {
        if (preset.longSideCap <= 0) return resolveSize(maxWidth, maxHeight)
        val longSide = max(maxWidth, maxHeight)
        if (longSide <= preset.longSideCap) return resolveSize(maxWidth, maxHeight)
        val ratio = preset.longSideCap.toFloat() / longSide
        val w = (maxWidth * ratio).toInt()
        val h = (maxHeight * ratio).toInt()
        return resolveSize(w, h)
    }

    /** 自动码率：按分辨率与帧率估算，单位 bps。 */
    fun autoBitrate(width: Int, height: Int, fps: Int, level: BitrateLevel): Int {
        val base = width.toLong() * height.toLong() * fps.toLong()
        // 经验系数：静态画面居多，0.08 bit/pixel 已经相当通透
        val factor = when (level) {
            BitrateLevel.LOW -> 0.045
            BitrateLevel.HIGH -> 0.14
            BitrateLevel.AUTO -> 0.085
        }
        val bps = (base * factor).toLong()
        val floor = 2_500_000L
        val ceil = 80_000_000L
        return bps.coerceIn(floor, ceil).toInt()
    }

    /** 保证尺寸是偶数，H.264 硬性要求。 */
    fun even(v: Int): Int = if (v % 2 == 0) v else v - 1
}
