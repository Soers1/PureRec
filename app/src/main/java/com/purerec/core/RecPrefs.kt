package com.purerec.core

import android.content.Context
import android.content.SharedPreferences

/**
 * 录制参数的本地持久化。纯本地，不联网、不上传。
 */
object RecPrefs {
    private const val FILE = "purerec_settings"
    private const val KEY_PRESET = "preset"
    private const val KEY_FPS = "fps"
    private const val KEY_BITRATE = "bitrate"
    private const val KEY_MIC = "mic"
    private const val KEY_INTERNAL = "internal"
    private const val KEY_CAMERA = "camera"
    private const val KEY_CAMERA_FRONT = "camera_front"

    private const val KEY_COUNTDOWN = "countdown"
    private const val KEY_SHAKE_STOP = "shake_stop"
    private const val KEY_SHAKE_LEVEL = "shake_level"
    private const val KEY_AUTO_STOP_MIN = "auto_stop_min"
    private const val KEY_MIC_ENHANCE = "mic_enhance"
    private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
    private const val KEY_LOW_BATTERY_STOP = "low_battery_stop"
    private const val KEY_LOW_BATTERY_THRESHOLD = "low_battery_threshold"
    private const val KEY_AUTO_EXPORT = "auto_export_gallery"
    private const val KEY_NAME_PREFIX = "name_prefix"

    /** 文件名前缀允许的最大长度。 */
    const val MAX_PREFIX_LENGTH = 16

    private fun sp(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(context: Context): RecordingConfig {
        val p = sp(context)
        return RecordingConfig(
            preset = ResolutionPreset.fromId(p.getInt(KEY_PRESET, ResolutionPreset.NATIVE.id)),
            fps = p.getInt(KEY_FPS, 30),
            bitrateLevel = BitrateLevel.fromId(p.getInt(KEY_BITRATE, BitrateLevel.AUTO.id)),
            recordMic = p.getBoolean(KEY_MIC, true),
            recordInternal = p.getBoolean(KEY_INTERNAL, false),
            useCamera = p.getBoolean(KEY_CAMERA, false),
            cameraFront = p.getBoolean(KEY_CAMERA_FRONT, true),
            countdown = p.getInt(KEY_COUNTDOWN, 0),
            shakeToStop = p.getBoolean(KEY_SHAKE_STOP, false),
            shakeLevel = p.getInt(KEY_SHAKE_LEVEL, ShakeLevel.MID.id),
            autoStopMinutes = p.getInt(KEY_AUTO_STOP_MIN, 0),
            micEnhance = p.getBoolean(KEY_MIC_ENHANCE, false),
            keepScreenOn = p.getBoolean(KEY_KEEP_SCREEN_ON, true),
            lowBatteryStop = p.getBoolean(KEY_LOW_BATTERY_STOP, false),
            lowBatteryThreshold = p.getInt(KEY_LOW_BATTERY_THRESHOLD, 15),
            autoExportToGallery = p.getBoolean(KEY_AUTO_EXPORT, false),
            fileNamePrefix = sanitizePrefix(p.getString(KEY_NAME_PREFIX, "PureRec") ?: "PureRec"),
        )
    }

    fun save(context: Context, config: RecordingConfig) {
        sp(context).edit()
            .putInt(KEY_PRESET, config.preset.id)
            .putInt(KEY_FPS, config.fps)
            .putInt(KEY_BITRATE, config.bitrateLevel.id)
            .putBoolean(KEY_MIC, config.recordMic)
            .putBoolean(KEY_INTERNAL, config.recordInternal)
            .putBoolean(KEY_CAMERA, config.useCamera)
            .putBoolean(KEY_CAMERA_FRONT, config.cameraFront)
            .putInt(KEY_COUNTDOWN, config.countdown)
            .putBoolean(KEY_SHAKE_STOP, config.shakeToStop)
            .putInt(KEY_SHAKE_LEVEL, config.shakeLevel)
            .putInt(KEY_AUTO_STOP_MIN, config.autoStopMinutes)
            .putBoolean(KEY_MIC_ENHANCE, config.micEnhance)
            .putBoolean(KEY_KEEP_SCREEN_ON, config.keepScreenOn)
            .putBoolean(KEY_LOW_BATTERY_STOP, config.lowBatteryStop)
            .putInt(KEY_LOW_BATTERY_THRESHOLD, config.lowBatteryThreshold)
            .putBoolean(KEY_AUTO_EXPORT, config.autoExportToGallery)
            .putString(KEY_NAME_PREFIX, sanitizePrefix(config.fileNamePrefix))
            .apply()
    }

    /**
     * 把用户输入的前缀洗成安全的文件名片段：去掉路径分隔符、保留中文与常见符号。
     */
    fun sanitizePrefix(raw: String): String {
        val cleaned = raw.trim()
            .replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), "")
            .replace(Regex("\\s+"), "_")
        return when {
            cleaned.isEmpty() -> "PureRec"
            cleaned.length > MAX_PREFIX_LENGTH -> cleaned.substring(0, MAX_PREFIX_LENGTH)
            else -> cleaned
        }
    }
}
