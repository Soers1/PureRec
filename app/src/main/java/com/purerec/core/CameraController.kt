package com.purerec.core

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import kotlin.math.abs

/**
 * 摄像头小窗采集。输出直接进 [ScreenRenderThread] 提供的 Surface。
 */
@SuppressLint("MissingPermission")
internal class CameraController(
    private val context: Context,
    private val front: Boolean,
) {

    companion object {
        private const val TAG = "PureRec-Camera"

        fun findCameraId(context: Context, front: Boolean): String? {
            return try {
                val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val wanted = if (front) {
                    CameraCharacteristics.LENS_FACING_FRONT
                } else {
                    CameraCharacteristics.LENS_FACING_BACK
                }
                manager.cameraIdList.firstOrNull { id ->
                    manager.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.LENS_FACING) == wanted
                }
            } catch (t: Throwable) {
                Log.w(TAG, "查找摄像头失败", t)
                null
            }
        }

        /**
         * 选一个适合做小窗的采集尺寸。注意这里选的是「缓冲区尺寸」，
         * 画面方向由 SurfaceTexture 的变换矩阵负责，这里不用管。
         */
        fun pickBufferSize(context: Context, cameraId: String): Size? {
            return try {
                val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val map = manager.getCameraCharacteristics(cameraId)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
                val sizes = map.getOutputSizes(SurfaceTexture::class.java) ?: return null
                if (sizes.isEmpty()) return null

                val target = 1280L * 720L
                sizes.filter { it.width <= 1920 && it.height <= 1080 }
                    .minByOrNull { abs(it.width.toLong() * it.height - target) }
                    ?: sizes.minByOrNull { it.width.toLong() * it.height }
            } catch (t: Throwable) {
                Log.w(TAG, "查询摄像头尺寸失败", t)
                null
            }
        }
    }

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    fun open(surface: Surface, onError: (Throwable) -> Unit) {
        val cameraId = findCameraId(context, front)
        if (cameraId == null) {
            onError(IllegalStateException("没有找到可用摄像头"))
            return
        }

        val ht = HandlerThread("PureRec-Camera").apply { start() }
        thread = ht
        val h = Handler(ht.looper)
        handler = h

        try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    createSession(camera, surface, h, onError)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    device = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    device = null
                    onError(IllegalStateException("摄像头打开失败 ($error)"))
                }
            }, h)
        } catch (t: Throwable) {
            onError(t)
        }
    }

    private fun createSession(
        camera: CameraDevice,
        surface: Surface,
        h: Handler,
        onError: (Throwable) -> Unit,
    ) {
        try {
            @Suppress("DEPRECATION")
            camera.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        if (device == null) {
                            s.close()
                            return
                        }
                        session = s
                        if (!startRepeating(camera, s, surface, h)) {
                            onError(IllegalStateException("摄像头预览启动失败"))
                        }
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        onError(IllegalStateException("摄像头会话配置失败"))
                    }
                },
                h
            )
        } catch (t: Throwable) {
            onError(t)
        }
    }

    private fun startRepeating(
        camera: CameraDevice,
        s: CameraCaptureSession,
        surface: Surface,
        h: Handler,
    ): Boolean {
        // 部分机型不支持连续对焦模式，先带 AF 试，失败再去掉
        if (tryRepeating(camera, s, surface, h, withAf = true)) return true
        return tryRepeating(camera, s, surface, h, withAf = false)
    }

    private fun tryRepeating(
        camera: CameraDevice,
        s: CameraCaptureSession,
        surface: Surface,
        h: Handler,
        withAf: Boolean,
    ): Boolean = try {
        val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(surface)
            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            if (withAf) {
                set(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                )
            }
        }
        s.setRepeatingRequest(builder.build(), null, h)
        true
    } catch (t: Throwable) {
        Log.w(TAG, "setRepeatingRequest 失败 (withAf=$withAf)", t)
        false
    }

    fun close() {
        try {
            session?.close()
        } catch (_: Throwable) {
        }
        session = null
        try {
            device?.close()
        } catch (_: Throwable) {
        }
        device = null
        thread?.quitSafely()
        thread = null
        handler = null
    }
}
