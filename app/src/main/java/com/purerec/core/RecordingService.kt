package com.purerec.core

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.purerec.MainActivity
import com.purerec.R
import java.io.File
import java.util.Locale

/**
 * 录制前台服务。
 *
 * 悬浮窗被砍掉之后，控制入口全部走这里：状态栏常驻通知（计时 + 暂停 / 停止），
 * 外加摇一摇、定时到点、电量见底三种自动收尾。
 */
class RecordingService : Service() {

    companion object {
        private const val TAG = "PureRec-Service"

        const val ACTION_START = "com.purerec.action.START"
        const val ACTION_STOP = "com.purerec.action.STOP"
        const val ACTION_PAUSE = "com.purerec.action.PAUSE"
        const val ACTION_RESUME = "com.purerec.action.RESUME"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        private const val CHANNEL_ID = "purerec_recording"
        private const val NOTIFICATION_ID = 0x1001

        /** 启动录制（必须在拿到 MediaProjection 授权之后调用）。 */
        fun start(context: Context, resultCode: Int, data: Intent, config: RecordingConfig) {
            val intent = Intent(context, RecordingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
                config.writeTo(this)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        /** 停止录制。 */
        fun stop(context: Context) {
            send(context, ACTION_STOP)
        }

        /** 暂停 / 继续。 */
        fun pause(context: Context) {
            send(context, ACTION_PAUSE)
        }

        fun resume(context: Context) {
            send(context, ACTION_RESUME)
        }

        fun togglePause(context: Context) {
            send(context, if (RecordingState.isPaused) ACTION_RESUME else ACTION_PAUSE)
        }

        private fun send(context: Context, action: String) {
            val intent = Intent(context, RecordingService::class.java).apply { this.action = action }
            try {
                context.startService(intent)
            } catch (_: Throwable) {
                try {
                    ContextCompat.startForegroundService(context, intent)
                } catch (_: Throwable) {
                }
            }
        }

        fun formatDuration(millis: Long): String {
            val total = millis / 1000
            val h = total / 3600
            val m = (total % 3600) / 60
            val s = total % 60
            return if (h > 0) {
                String.format(Locale.US, "%d:%02d:%02d", h, m, s)
            } else {
                String.format(Locale.US, "%02d:%02d", m, s)
            }
        }
    }

    private var recorder: ScreenRecorder? = null
    private var startFailed = false

    /** 本次录制用的配置（含摇一摇、定时、电量等偏好）。 */
    private var activeConfig: RecordingConfig = RecordingConfig()

    private var shakeDetector: ShakeDetector? = null
    private var batteryRegistered = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val data = intent ?: return
            if (data.action != Intent.ACTION_BATTERY_CHANGED) return
            if (!RecordingState.isRecording) return

            val cfg = activeConfig
            if (!cfg.lowBatteryStop) return

            // 插着电就不管，别在用户充电时莫名其妙把录制掐了
            val plugged = data.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
            if (plugged != 0) return

            val level = data.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = data.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level < 0 || scale <= 0) return

            val percent = level * 100 / scale
            if (percent <= cfg.lowBatteryThreshold) {
                Log.i(TAG, "电量 $percent% 低于阈值，自动停止")
                RecordingState.lastAutoStopReason =
                    "电量只剩 $percent%，已自动保存并停止录制"
                handleStop()
            }
        }
    }

    private val timerHandler = Handler(Looper.getMainLooper())

    private fun postTick() {
        timerHandler.removeCallbacks(timerRunnable)
        timerHandler.postDelayed(timerRunnable, 1000L)
    }

    private val timerRunnable = object : Runnable {
        override fun run() {
            if (!RecordingState.isRecording) return
            updateNotification()
            checkAutoStop()
            timerHandler.postDelayed(this, 1000L)
        }
    }

    /** 到点了就自己收工，省得录着录着忘了关。 */
    private fun checkAutoStop() {
        val limitMinutes = activeConfig.autoStopMinutes
        if (limitMinutes <= 0) return
        val elapsed = RecordingState.elapsedMillis()
        if (elapsed >= limitMinutes * 60_000L) {
            Log.i(TAG, "到达定时上限 ${limitMinutes} 分钟，自动停止")
            RecordingState.lastAutoStopReason = "已到 $limitMinutes 分钟上限，录制自动结束"
            handleStop()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> handleStop()
            ACTION_PAUSE -> handlePause()
            ACTION_RESUME -> handleResume()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------------ 开始

    private fun handleStart(intent: Intent) {
        if (recorder != null) return

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val resultData = parcelableIntent(intent, EXTRA_RESULT_DATA)
        // 录制参数从 Intent 来，应用级偏好（前缀 / 自动导相册）从本地设置补
        val prefs = RecPrefs.load(applicationContext)
        val config = RecordingConfig.fromIntent(intent).copy(
            fileNamePrefix = prefs.fileNamePrefix,
            autoExportToGallery = prefs.autoExportToGallery,
            keepScreenOn = prefs.keepScreenOn,
        )
        activeConfig = config

        // Android 14 起，必须先进入前台（且声明 mediaProjection 类型）才能拿 MediaProjection
        createChannel()
        startForegroundCompat(getString(R.string.notif_title), "")

        if (resultData == null || resultCode != Activity.RESULT_OK) {
            startFailed = true
            RecordingState.onError("录屏授权被取消")
            shutdown()
            return
        }

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = try {
            manager.getMediaProjection(resultCode, resultData)
        } catch (t: Throwable) {
            Log.e(TAG, "getMediaProjection 失败", t)
            null
        }

        if (projection == null) {
            startFailed = true
            RecordingState.onError("无法获取录屏授权")
            shutdown()
            return
        }

        val rec = ScreenRecorder(applicationContext, config, projection)
        rec.onStopped = { file -> onRecordingFinished(file) }
        recorder = rec

        Thread({
            try {
                rec.start()
                RecordingState.onStarted(rec.outputFile.absolutePath)
                startWatchers()
                postTick()
            } catch (t: Throwable) {
                Log.e(TAG, "启动录制失败", t)
                startFailed = true
                RecordingState.onError("启动录制失败：${t.message ?: t.javaClass.simpleName}")
                try {
                    rec.stop()
                } catch (_: Throwable) {
                    shutdown()
                }
            }
        }, "PureRec-Start").start()
    }

    /** 摇一摇 + 电量监控，都是可选功能，任何一项起不来都不影响录制。 */
    private fun startWatchers() {
        val cfg = activeConfig

        if (cfg.shakeToStop) {
            try {
                val detector = ShakeDetector(
                    applicationContext,
                    ShakeLevel.fromId(cfg.shakeLevel),
                ) {
                    if (RecordingState.isRecording) {
                        Log.i(TAG, "摇一摇触发停止")
                        RecordingState.lastAutoStopReason = "摇一摇停下来了，视频已保存"
                        handleStop()
                    }
                }
                if (detector.available) {
                    detector.start()
                    shakeDetector = detector
                }
            } catch (t: Throwable) {
                Log.w(TAG, "摇一摇启动失败", t)
            }
        }

        if (cfg.lowBatteryStop && !batteryRegistered) {
            try {
                registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                batteryRegistered = true
            } catch (t: Throwable) {
                Log.w(TAG, "电量监控注册失败", t)
            }
        }
    }

    private fun stopWatchers() {
        try {
            shakeDetector?.stop()
        } catch (_: Throwable) {
        }
        shakeDetector = null

        if (batteryRegistered) {
            try {
                unregisterReceiver(batteryReceiver)
            } catch (_: Throwable) {
            }
            batteryRegistered = false
        }
    }

    // ------------------------------------------------------------------ 暂停

    private fun handlePause() {
        val rec = recorder ?: return
        if (RecordingState.isPaused) return
        try {
            rec.pause()
        } catch (t: Throwable) {
            Log.w(TAG, "暂停失败", t)
            return
        }
        RecordingState.onPaused()
        updateNotification()
    }

    private fun handleResume() {
        val rec = recorder ?: return
        if (!RecordingState.isPaused) return
        try {
            rec.resume()
        } catch (t: Throwable) {
            Log.w(TAG, "恢复失败", t)
            return
        }
        RecordingState.onResumed()
        updateNotification()
    }

    // ------------------------------------------------------------------ 结束

    private fun handleStop() {
        val rec = recorder
        if (rec == null) {
            shutdown()
            return
        }
        updateNotification()
        rec.stop()
    }

    private fun onRecordingFinished(file: File?) {
        timerHandler.removeCallbacks(timerRunnable)
        stopWatchers()
        recorder = null

        if (file != null) {
            // 录完自动进相册（Android 10+，不额外要权限）
            if (activeConfig.autoExportToGallery && GalleryExporter.supported()) {
                try {
                    val uri = GalleryExporter.export(applicationContext, file)
                    if (uri != null) {
                        Log.i(TAG, "已导出到相册 $uri")
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "自动导出相册失败", t)
                }
            }
            RecordingState.onStopped(file.absolutePath)
        } else {
            RecordingState.onStopped(null)
            if (!startFailed) {
                RecordingState.onError("录制没有生成有效文件，请再试一次")
            }
        }
        shutdown()
    }

    private fun shutdown() {
        timerHandler.removeCallbacks(timerRunnable)
        stopWatchers()
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (_: Throwable) {
        }
        stopSelf()
    }

    override fun onDestroy() {
        timerHandler.removeCallbacks(timerRunnable)
        stopWatchers()
        val rec = recorder
        recorder = null
        if (rec != null) {
            rec.onStopped = null
            try {
                rec.stop()
            } catch (_: Throwable) {
            }
        }
        RecordingState.onStopped(null)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 通知

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.recording_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.recording_channel_desc)
                setShowBadge(false)
                enableVibration(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundCompat(title: String, text: String) {
        val notification = buildNotification(title, text)
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )
    }

    private fun updateNotification() {
        try {
            val elapsed = formatDuration(RecordingState.elapsedMillis())
            val text = if (RecordingState.isPaused) "$elapsed · 已暂停" else elapsed
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(
                NOTIFICATION_ID,
                buildNotification(getString(R.string.notif_title), text)
            )
        } catch (_: Throwable) {
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = actionIntent(1, ACTION_STOP)
        val pauseIntent = actionIntent(
            2,
            if (RecordingState.isPaused) ACTION_RESUME else ACTION_PAUSE
        )
        val pauseLabel = if (RecordingState.isPaused) "继续" else "暂停"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_rec)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openIntent)
            .addAction(0, pauseLabel, pauseIntent)
            .addAction(0, getString(R.string.notif_stop), stopIntent)
            .build()
    }

    private fun actionIntent(requestCode: Int, action: String): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, RecordingService::class.java).apply { this.action = action },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    @Suppress("DEPRECATION")
    private fun parcelableIntent(intent: Intent, key: String): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(key, Intent::class.java)
        } else {
            intent.getParcelableExtra(key)
        }
}
