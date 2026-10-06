package com.purerec.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.os.SystemClock

/**
 * 录制状态。Service 与 UI 在同一个进程内，直接用 Compose 状态通信，简单可靠。
 */
object RecordingState {

    var isRecording by mutableStateOf(false)
        private set

    /** 暂停中（只在 isRecording 为 true 时有意义）。 */
    var isPaused by mutableStateOf(false)
        private set

    /** 录制开始时间（SystemClock.elapsedRealtime 基准）。 */
    var startedAt by mutableLongStateOf(0L)
        private set

    /** 累计暂停的毫秒数，用来把计时里的暂停时间扣掉。 */
    private var pausedTotalMs = 0L

    /** 当前这一段暂停的开始时刻，0 表示没在暂停。 */
    private var pausedSince = 0L

    /** 当前正在写入的文件路径。 */
    var currentFile by mutableStateOf<String?>(null)
        private set

    /** 最近一次错误提示。 */
    var lastError by mutableStateOf<String?>(null)

    /** 最近一次成功保存的文件路径（UI 用于提示）。 */
    var lastSaved by mutableStateOf<String?>(null)

    /** 最近一次自动停止的原因，UI 用完清掉。 */
    var lastAutoStopReason by mutableStateOf<String?>(null)

    fun onStarted(path: String) {
        currentFile = path
        startedAt = SystemClock.elapsedRealtime()
        pausedTotalMs = 0L
        pausedSince = 0L
        isPaused = false
        isRecording = true
        lastError = null
    }

    fun onPaused() {
        if (!isRecording || isPaused) return
        pausedSince = SystemClock.elapsedRealtime()
        isPaused = true
    }

    fun onResumed() {
        if (!isRecording || !isPaused) return
        if (pausedSince > 0) pausedTotalMs += SystemClock.elapsedRealtime() - pausedSince
        pausedSince = 0L
        isPaused = false
    }

    fun onStopped(path: String?) {
        isRecording = false
        isPaused = false
        startedAt = 0L
        pausedTotalMs = 0L
        pausedSince = 0L
        currentFile = null
        if (path != null) lastSaved = path
    }

    fun onError(message: String) {
        isRecording = false
        isPaused = false
        startedAt = 0L
        pausedTotalMs = 0L
        pausedSince = 0L
        currentFile = null
        lastError = message
    }

    fun consumeError() {
        lastError = null
    }

    fun consumeSaved() {
        lastSaved = null
    }

    fun consumeAutoStopReason() {
        lastAutoStopReason = null
    }

    /** 已经录了多久（不含暂停时间）。 */
    fun elapsedMillis(): Long {
        if (!isRecording || startedAt <= 0) return 0L
        val now = SystemClock.elapsedRealtime()
        val livePause = if (pausedSince > 0) now - pausedSince else 0L
        return (now - startedAt - pausedTotalMs - livePause).coerceAtLeast(0L)
    }
}
