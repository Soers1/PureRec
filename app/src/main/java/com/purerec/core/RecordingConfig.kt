package com.purerec.core

import android.content.Intent
import android.os.Build

/** 分辨率档位。[longSideCap] = 0 表示使用设备物理最大分辨率。 */
enum class ResolutionPreset(val id: Int, val label: String, val longSideCap: Int) {
    NATIVE(0, "设备最大", 0),
    FHD(1, "1080P", 1920),
    HD(2, "720P", 1280),
    SD(3, "480P", 854);

    companion object {
        fun fromId(id: Int): ResolutionPreset = entries.firstOrNull { it.id == id } ?: NATIVE
    }
}

/** 帧率档位。 */
enum class FrameRate(val value: Int, val label: String) {
    F24(24, "24"),
    F30(30, "30"),
    F60(60, "60");

    companion object {
        fun fromValue(v: Int): FrameRate = entries.firstOrNull { it.value == v } ?: F30
    }
}

/** 码率档位。[mbps] = 0 表示按分辨率自动计算。 */
enum class BitrateLevel(val id: Int, val label: String, val mbps: Int) {
    AUTO(0, "自动", 0),
    LOW(1, "省空间", 0),
    HIGH(2, "高画质", 0);

    companion object {
        fun fromId(id: Int): BitrateLevel = entries.firstOrNull { it.id == id } ?: AUTO
    }
}

/** 录制前倒计时档位（秒）。 */
enum class CountdownOption(val seconds: Int, val label: String) {
    OFF(0, "关"),
    S3(3, "3 秒"),
    S5(5, "5 秒"),
    S10(10, "10 秒");

    companion object {
        fun fromSeconds(s: Int): CountdownOption =
            entries.firstOrNull { it.seconds == s } ?: OFF
    }
}

/** 定时自动停止档位（分钟，0 = 不限）。 */
enum class AutoStopOption(val minutes: Int, val label: String) {
    OFF(0, "不限"),
    M15(15, "15 分钟"),
    M30(30, "30 分钟"),
    M60(60, "1 小时"),
    M120(120, "2 小时");

    companion object {
        fun fromMinutes(m: Int): AutoStopOption =
            entries.firstOrNull { it.minutes == m } ?: OFF
    }
}

/** 摇一摇灵敏度。 */
enum class ShakeLevel(val id: Int, val label: String, val threshold: Float) {
    LOW(0, "低", 26f),
    MID(1, "中", 19f),
    HIGH(2, "高", 14f);

    companion object {
        fun fromId(id: Int): ShakeLevel = entries.firstOrNull { it.id == id } ?: MID
    }
}

/**
 * 一次录制的全部参数。
 */
data class RecordingConfig(
    val preset: ResolutionPreset = ResolutionPreset.NATIVE,
    val fps: Int = 30,
    val bitrateLevel: BitrateLevel = BitrateLevel.AUTO,
    val recordMic: Boolean = true,
    val recordInternal: Boolean = false,
    val useCamera: Boolean = false,
    val cameraFront: Boolean = true,

    // ——— 便捷录制相关 ———
    /** 录制前倒计时秒数，0 = 直接开录。 */
    val countdown: Int = 0,
    /** 摇一摇停止录制。 */
    val shakeToStop: Boolean = false,
    val shakeLevel: Int = ShakeLevel.MID.id,
    /** 定时自动停止（分钟），0 = 不限。 */
    val autoStopMinutes: Int = 0,
    /** 麦克风增强：自动增益 + 降噪。 */
    val micEnhance: Boolean = false,
    /** 录制中保持屏幕常亮。 */
    val keepScreenOn: Boolean = true,
    /** 低电量自动停止。 */
    val lowBatteryStop: Boolean = false,
    val lowBatteryThreshold: Int = 15,
    /** 录完自动导出到系统相册。 */
    val autoExportToGallery: Boolean = false,
    /** 文件名前缀。 */
    val fileNamePrefix: String = "PureRec",
) {
    /** 是否真的有音频轨（内部声音需要 Android 10+）。 */
    val hasAudio: Boolean
        get() = recordMic || (recordInternal && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)

    /** 内部声音在本机是否可用。 */
    val internalAudioSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    fun writeTo(intent: Intent): Intent = intent.apply {
        putExtra(EXTRA_PRESET, preset.id)
        putExtra(EXTRA_FPS, fps)
        putExtra(EXTRA_BITRATE, bitrateLevel.id)
        putExtra(EXTRA_MIC, recordMic)
        putExtra(EXTRA_INTERNAL, recordInternal)
        putExtra(EXTRA_CAMERA, useCamera)
        putExtra(EXTRA_CAMERA_FRONT, cameraFront)
        putExtra(EXTRA_SHAKE_STOP, shakeToStop)
        putExtra(EXTRA_SHAKE_LEVEL, shakeLevel)
        putExtra(EXTRA_AUTO_STOP_MIN, autoStopMinutes)
        putExtra(EXTRA_MIC_ENHANCE, micEnhance)
        putExtra(EXTRA_LOW_BATTERY_STOP, lowBatteryStop)
        putExtra(EXTRA_LOW_BATTERY_THRESHOLD, lowBatteryThreshold)
    }

    companion object {
        const val EXTRA_PRESET = "cfg_preset"
        const val EXTRA_FPS = "cfg_fps"
        const val EXTRA_BITRATE = "cfg_bitrate"
        const val EXTRA_MIC = "cfg_mic"
        const val EXTRA_INTERNAL = "cfg_internal"
        const val EXTRA_CAMERA = "cfg_camera"
        const val EXTRA_CAMERA_FRONT = "cfg_camera_front"
        const val EXTRA_SHAKE_STOP = "cfg_shake_stop"
        const val EXTRA_SHAKE_LEVEL = "cfg_shake_level"
        const val EXTRA_AUTO_STOP_MIN = "cfg_auto_stop_min"
        const val EXTRA_MIC_ENHANCE = "cfg_mic_enhance"
        const val EXTRA_LOW_BATTERY_STOP = "cfg_low_battery_stop"
        const val EXTRA_LOW_BATTERY_THRESHOLD = "cfg_low_battery_threshold"

        fun fromIntent(intent: Intent): RecordingConfig = RecordingConfig(
            preset = ResolutionPreset.fromId(intent.getIntExtra(EXTRA_PRESET, 0)),
            fps = intent.getIntExtra(EXTRA_FPS, 30),
            bitrateLevel = BitrateLevel.fromId(intent.getIntExtra(EXTRA_BITRATE, 0)),
            recordMic = intent.getBooleanExtra(EXTRA_MIC, true),
            recordInternal = intent.getBooleanExtra(EXTRA_INTERNAL, false),
            useCamera = intent.getBooleanExtra(EXTRA_CAMERA, false),
            cameraFront = intent.getBooleanExtra(EXTRA_CAMERA_FRONT, true),
            shakeToStop = intent.getBooleanExtra(EXTRA_SHAKE_STOP, false),
            shakeLevel = intent.getIntExtra(EXTRA_SHAKE_LEVEL, ShakeLevel.MID.id),
            autoStopMinutes = intent.getIntExtra(EXTRA_AUTO_STOP_MIN, 0),
            micEnhance = intent.getBooleanExtra(EXTRA_MIC_ENHANCE, false),
            lowBatteryStop = intent.getBooleanExtra(EXTRA_LOW_BATTERY_STOP, false),
            lowBatteryThreshold = intent.getIntExtra(EXTRA_LOW_BATTERY_THRESHOLD, 15),
        )
    }
}
