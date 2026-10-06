package com.purerec.core

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 一次完整录制的编排者：屏幕采集 → OpenGL 合成 → H.264/AAC 编码 → MP4 封装。
 *
 * 生命周期：start() 之后一直在录，stop() 幂等，结束后回调 onStopped(文件或 null)。
 */
class ScreenRecorder(
    private val context: Context,
    private val config: RecordingConfig,
    private val projection: MediaProjection,
) {

    companion object {
        private const val TAG = "PureRec-Recorder"
        const val DIR_NAME = "PureRec"

        /** 录像保存目录（应用私有外部目录，卸载才会清掉，无需任何存储权限）。 */
        fun outputDir(context: Context): File {
            val external = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            val dir = File(external ?: context.filesDir, DIR_NAME)
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

        fun newOutputFile(context: Context, prefix: String = "PureRec"): File {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val name = prefix.ifBlank { "PureRec" }
            return File(outputDir(context), "${name}_$stamp.mp4")
        }
    }

    /** 录制结束回调，参数为成品文件；录制失败或没内容时为 null。 */
    var onStopped: ((File?) -> Unit)? = null

    lateinit var outputFile: File
        private set

    var videoWidth = 0
        private set

    var videoHeight = 0
        private set

    var videoBitrate = 0
        private set

    private val workerThread = HandlerThread("PureRec-Worker").apply { start() }
    private val workerHandler = Handler(workerThread.looper)

    private var muxer: MuxerWrapper? = null
    private var videoEncoder: VideoEncoder? = null
    private var audioRecorder: AudioRecorder? = null
    private var renderThread: ScreenRenderThread? = null
    private var cameraController: CameraController? = null
    private var virtualDisplay: VirtualDisplay? = null

    private val stopping = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // 用户从系统状态栏 / 系统弹窗里点了「停止」
            Log.i(TAG, "MediaProjection 被系统停止")
            Thread({ stopInternal() }, "PureRec-AutoStop").start()
        }
    }

    @Throws(Throwable::class)
    fun start() {
        val native = DisplayHelper.realSize(context)
        val size = VideoUtils.targetSize(config.preset, native.first, native.second)
        videoWidth = size.width
        videoHeight = size.height
        videoBitrate = VideoUtils.autoBitrate(
            videoWidth, videoHeight, config.fps, config.bitrateLevel
        )

        outputFile = newOutputFile(context, config.fileNamePrefix)
        val orientationHint = DisplayHelper.rotation(context) * 90
        val hasAudio = config.hasAudio

        Log.i(
            TAG,
            "开始录制 ${videoWidth}x$videoHeight @${config.fps}fps " +
                "${videoBitrate / 1000}kbps audio=$hasAudio hint=$orientationHint"
        )

        muxer = MuxerWrapper(outputFile.absolutePath, orientationHint).apply {
            expectTracks(if (hasAudio) 2 else 1)
        }

        videoEncoder = VideoEncoder(
            muxer = muxer!!,
            width = videoWidth,
            height = videoHeight,
            fps = config.fps,
            bitrate = videoBitrate,
        )

        // 摄像头小窗（可选）
        var cameraId: String? = null
        var cameraBuffer: android.util.Size? = null
        if (config.useCamera) {
            cameraId = CameraController.findCameraId(context, config.cameraFront)
            if (cameraId != null) {
                cameraBuffer = CameraController.pickBufferSize(context, cameraId)
            }
            if (cameraBuffer == null) {
                Log.w(TAG, "摄像头不可用，本次录制不带画中画")
                cameraId = null
            }
        }

        val renderer = ScreenRenderThread(
            encoderSurface = videoEncoder!!.inputSurface,
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            fps = config.fps,
            cameraBufferSize = cameraBuffer,
            mirrorCamera = config.cameraFront,
        )
        renderThread = renderer
        renderer.start()

        if (!renderer.awaitReady(6000)) {
            throw IllegalStateException("渲染引擎初始化超时")
        }

        projection.registerCallback(projectionCallback, workerHandler)
        virtualDisplay = projection.createVirtualDisplay(
            "PureRec",
            videoWidth,
            videoHeight,
            DisplayHelper.densityDpi(context),
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            renderer.screenSurface,
            null,
            workerHandler
        )

        videoEncoder!!.start()

        if (hasAudio) {
            try {
                audioRecorder = AudioRecorder(
                    muxer = muxer!!,
                    projection = projection,
                    useMic = config.recordMic,
                    useInternal = config.recordInternal,
                    enhanceMic = config.micEnhance,
                ).also { it.start() }
            } catch (t: Throwable) {
                Log.w(TAG, "音频初始化失败，本次仅录画面", t)
                audioRecorder = null
                // 只等视频轨，避免 muxer 永远不 start
                muxer?.expectTracks(1)
            }
        }

        val camSurface = renderer.cameraSurface
        if (cameraId != null && camSurface != null) {
            cameraController = CameraController(context, config.cameraFront).also { controller ->
                controller.open(camSurface) { t ->
                    Log.w(TAG, "摄像头启动失败，继续录制", t)
                }
            }
        }

        renderer.beginRendering()
    }

    /** 暂停录制：画面冻结、音频静音，时间轴不断。 */
    fun pause() {
        renderThread?.paused = true
        audioRecorder?.paused = true
    }

    /** 恢复录制。 */
    fun resume() {
        renderThread?.paused = false
        audioRecorder?.paused = false
    }

    /** 停止录制。幂等，重复调用只有第一次生效。 */
    fun stop() {
        Thread({ stopInternal() }, "PureRec-Stop").start()
    }

    private fun stopInternal() {
        if (!stopping.compareAndSet(false, true)) return
        if (!stopped.compareAndSet(false, true)) return

        Log.i(TAG, "停止录制")

        closeQuietly { renderThread?.stop() }
        closeQuietly { virtualDisplay?.release() }
        virtualDisplay = null
        closeQuietly { cameraController?.close() }
        cameraController = null
        closeQuietly { audioRecorder?.stop() }
        audioRecorder = null
        closeQuietly { videoEncoder?.stop() }
        videoEncoder = null

        val hasContent = try {
            muxer?.release() ?: false
        } catch (_: Throwable) {
            false
        }
        muxer = null

        closeQuietly { projection.unregisterCallback(projectionCallback) }
        closeQuietly { projection.stop() }
        closeQuietly { workerThread.quitSafely() }

        val file = outputFile
        val valid = hasContent && file.exists() && file.length() > 0
        if (!valid) {
            try {
                file.delete()
            } catch (_: Throwable) {
            }
        }

        onStopped?.invoke(if (valid) file else null)
    }

    private inline fun closeQuietly(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.w(TAG, "清理步骤出错（忽略）", t)
        }
    }
}
