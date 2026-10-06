package com.purerec.core

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.min

/**
 * 把屏幕画面（必要时叠加摄像头小窗）用 OpenGL 合成后送进编码器的输入 Surface。
 *
 * 结构：
 *   MediaProjection → VirtualDisplay → Surface → SurfaceTexture#1（屏幕纹理）
 *   Camera2         → Surface → SurfaceTexture#2（摄像头纹理）
 *                        ↓ OpenGL 合成
 *                   编码器输入 Surface
 */
internal class ScreenRenderThread(
    private val encoderSurface: Surface,
    private val videoWidth: Int,
    private val videoHeight: Int,
    private val fps: Int,
    private val cameraBufferSize: android.util.Size?,
    private val mirrorCamera: Boolean,
) {

    companion object {
        private const val TAG = "PureRec-GL"
        private const val EGL_RECORDABLE_ANDROID = 0x3142

        /** 摄像头小窗宽度占画面宽度的比例。 */
        private const val CAMERA_FRACTION = 0.32f

        /** 小窗与画面边缘的留白（NDC）。 */
        private const val CAMERA_MARGIN = 0.06f

        private const val VERTEX_SHADER = """
            uniform mat4 uMvpMatrix;
            uniform mat4 uTexMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTextureCoord;
            varying vec2 vTextureCoord;
            void main() {
                gl_Position = uMvpMatrix * aPosition;
                vTextureCoord = (uTexMatrix * aTextureCoord).xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform samplerExternalOES sTexture;
            uniform float uMirror;
            void main() {
                vec2 uv = vTextureCoord;
                if (uMirror > 0.5) { uv.x = 1.0 - uv.x; }
                gl_FragColor = texture2D(sTexture, uv);
            }
        """

        private val SCREEN_VERTICES = floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f
        )

        private val TEX_COORDS = floatArrayOf(
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f
        )

        private val IDENTITY = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f
        )
    }

    // ------------------------------------------------------------ 对外接口

    /** 交给 MediaProjection.createVirtualDisplay 的屏幕 Surface。 */
    lateinit var screenSurface: Surface
        private set

    /** 交给 Camera2 的摄像头 Surface（未启用摄像头时为 null）。 */
    var cameraSurface: Surface? = null
        private set

    // ------------------------------------------------------------ 内部状态

    private val readyLatch = CountDownLatch(1)
    private val beginLatch = CountDownLatch(1)
    private val frameSemaphore = Semaphore(0)

    private val callbackThread = HandlerThread("PureRec-TexCallback").apply { start() }
    private val callbackHandler = Handler(callbackThread.looper)

    private var screenTexture: SurfaceTexture? = null
    private var cameraTexture: SurfaceTexture? = null

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var screenTexId = 0
    private var cameraTexId = 0

    private var program = 0
    private var aPosition = 0
    private var aTextureCoord = 0
    private var uMvpMatrix = 0
    private var uTexMatrix = 0
    private var uMirror = 0

    private lateinit var vertexBuffer: FloatBuffer
    private lateinit var texBuffer: FloatBuffer

    private val screenTexMatrix = FloatArray(16)
    private val cameraTexMatrix = FloatArray(16)

    @Volatile
    private var newScreenFrame = false

    @Volatile
    private var newCameraFrame = false

    @Volatile
    private var running = false

    /**
     * 暂停中：不再消费屏幕新帧（画面冻在最后一帧），但依旧按节奏把画面送进编码器，
     * 这样视频时间轴保持连续，合并出来不会出现时间跳变。
     */
    @Volatile
    var paused = false

    /** 视频时间戳基准：音频轨从 0 开始，视频也必须从 0 开始，否则 muxer 里两条轨对不上。 */
    @Volatile
    private var baseNs = 0L

    private var renderThread: Thread? = null

    // ------------------------------------------------------------ 生命周期

    fun start() {
        renderThread = Thread({ runGl() }, "PureRec-GLThread").also { it.start() }
    }

    /** 阻塞等待 EGL 与屏幕 Surface 就绪。 */
    fun awaitReady(timeoutMs: Long = 5000): Boolean =
        try {
            readyLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            false
        }

    /** 各组件都挂好之后，放行渲染循环。 */
    fun beginRendering() {
        baseNs = System.nanoTime()
        beginLatch.countDown()
    }

    fun stop() {
        running = false
        beginLatch.countDown()
        frameSemaphore.release(4)
        try {
            renderThread?.join(3000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        renderThread = null
        callbackThread.quitSafely()
    }

    // ------------------------------------------------------------ GL 主流程

    private fun runGl() {
        try {
            setupEgl()
            setupGl()
            createSurfaces()
            readyLatch.countDown()

            beginLatch.await()
            running = true
            renderLoop()
        } catch (t: Throwable) {
            Log.e(TAG, "GL 渲染线程异常", t)
            readyLatch.countDown()
        } finally {
            releaseGl()
        }
    }

    private fun setupEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw RuntimeException("eglGetDisplay 失败")
        }
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw RuntimeException("eglInitialize 失败")
        }

        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0) ||
            numConfigs[0] <= 0 || configs[0] == null
        ) {
            throw RuntimeException("eglChooseConfig 失败")
        }

        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )
        eglContext = EGL14.eglCreateContext(
            eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0
        )
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            throw RuntimeException("eglCreateContext 失败")
        }

        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, configs[0], encoderSurface, surfaceAttribs, 0
        )
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            throw RuntimeException("eglCreateWindowSurface 失败")
        }
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw RuntimeException("eglMakeCurrent 失败")
        }
    }

    private fun setupGl() {
        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        if (program == 0) throw RuntimeException("着色器编译失败")

        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        aTextureCoord = GLES20.glGetAttribLocation(program, "aTextureCoord")
        uMvpMatrix = GLES20.glGetUniformLocation(program, "uMvpMatrix")
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
        uMirror = GLES20.glGetUniformLocation(program, "uMirror")

        vertexBuffer = toBuffer(SCREEN_VERTICES)
        texBuffer = toBuffer(TEX_COORDS)
    }

    private fun createSurfaces() {
        val texIds = IntArray(2)
        GLES20.glGenTextures(2, texIds, 0)
        screenTexId = texIds[0]
        cameraTexId = texIds[1]

        // 屏幕纹理
        val screen = SurfaceTexture(texIds[0])
        screen.setDefaultBufferSize(videoWidth, videoHeight)
        screen.setOnFrameAvailableListener({
            newScreenFrame = true
            frameSemaphore.release()
        }, callbackHandler)
        screenTexture = screen
        screenSurface = Surface(screen)

        // 摄像头纹理
        val camSize = cameraBufferSize
        if (camSize != null) {
            val cam = SurfaceTexture(texIds[1])
            cam.setDefaultBufferSize(camSize.width, camSize.height)
            cam.setOnFrameAvailableListener({
                newCameraFrame = true
            }, callbackHandler)
            cameraTexture = cam
            cameraSurface = Surface(cam)
        } else {
            GLES20.glDeleteTextures(1, intArrayOf(texIds[1]), 0)
        }

        for (id in texIds) {
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, id)
            GLES20.glTexParameterf(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR.toFloat()
            )
            GLES20.glTexParameterf(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat()
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE
            )
        }
    }

    private fun renderLoop() {
        val intervalMs = (1000L / fps.coerceAtLeast(1))
        Log.i(TAG, "渲染开始 ${videoWidth}x$videoHeight @${fps}fps")

        while (running) {
            try {
                frameSemaphore.tryAcquire(intervalMs, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                break
            }

            if (!running) break
            while (frameSemaphore.tryAcquire()) {
                // 丢掉堆积的帧信号，只消费最新一帧
            }

            if (paused) {
                // 暂停：不消费新帧，让画面停在最后一帧上；堆着的帧信号直接丢掉
                newScreenFrame = false
                newCameraFrame = false
                while (frameSemaphore.tryAcquire()) {
                }
            } else {
                if (newScreenFrame) {
                    try {
                        screenTexture?.updateTexImage()
                    } catch (_: Throwable) {
                    }
                    newScreenFrame = false
                }
                if (newCameraFrame) {
                    try {
                        cameraTexture?.updateTexImage()
                    } catch (_: Throwable) {
                    }
                    newCameraFrame = false
                }
            }

            drawFrame()

            val ok = try {
                EGLExt.eglPresentationTimeANDROID(
                    eglDisplay, eglSurface, System.nanoTime() - baseNs
                )
                EGL14.eglSwapBuffers(eglDisplay, eglSurface)
            } catch (_: Throwable) {
                false
            }
            if (!ok) {
                Log.w(TAG, "eglSwapBuffers 失败，停止渲染")
                break
            }
        }
        Log.i(TAG, "渲染结束")
    }

    private fun drawFrame() {
        GLES20.glViewport(0, 0, videoWidth, videoHeight)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(program)
        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glEnableVertexAttribArray(aTextureCoord)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glVertexAttribPointer(aTextureCoord, 2, GLES20.GL_FLOAT, false, 0, texBuffer)

        // —— 屏幕铺满 ——
        screenTexture?.getTransformMatrix(screenTexMatrix)
        GLES20.glUniformMatrix4fv(uMvpMatrix, 1, false, IDENTITY, 0)
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, screenTexMatrix, 0)
        GLES20.glUniform1f(uMirror, 0f)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, screenTexId)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // —— 摄像头小窗叠加 ——
        val camTex = cameraTexture
        if (camTex != null) {
            camTex.getTransformMatrix(cameraTexMatrix)
            val mvp = cameraMatrix(cameraTexMatrix)
            GLES20.glUniformMatrix4fv(uMvpMatrix, 1, false, mvp, 0)
            GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, cameraTexMatrix, 0)
            GLES20.glUniform1f(uMirror, if (mirrorCamera) 1f else 0f)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexId)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aTextureCoord)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
    }

    /**
     * 根据摄像头纹理变换矩阵推算小窗在 NDC 中的位置与尺寸。
     * 变换矩阵里含转置信息时说明画面被旋转了 90°，此时宽高比要取倒数。
     */
    private fun cameraMatrix(texMatrix: FloatArray): FloatArray {
        val camSize = cameraBufferSize ?: return IDENTITY
        val transposed = abs(texMatrix[0]) < 0.5f && abs(texMatrix[5]) < 0.5f
        val onScreenAspect = if (transposed) {
            camSize.height.toFloat() / camSize.width.toFloat()
        } else {
            camSize.width.toFloat() / camSize.height.toFloat()
        }

        var halfW = CAMERA_FRACTION
        var halfH = halfW * (videoWidth.toFloat() / videoHeight.toFloat()) / onScreenAspect

        // 防止小窗超出画面
        val maxHalf = 0.92f
        if (halfH > maxHalf) {
            val k = maxHalf / halfH
            halfH *= k
            halfW *= k
        }

        val centerX = 1f - CAMERA_MARGIN - halfW
        val centerY = -1f + CAMERA_MARGIN + halfH

        return floatArrayOf(
            halfW, 0f, 0f, 0f,
            0f, halfH, 0f, 0f,
            0f, 0f, 1f, 0f,
            centerX, centerY, 0f, 1f
        )
    }

    private fun releaseGl() {
        running = false
        try {
            screenTexture?.setOnFrameAvailableListener(null)
            screenTexture?.release()
        } catch (_: Throwable) {
        }
        try {
            cameraTexture?.setOnFrameAvailableListener(null)
            cameraTexture?.release()
        } catch (_: Throwable) {
        }
        screenTexture = null
        cameraTexture = null

        try {
            if (::screenSurface.isInitialized) screenSurface.release()
        } catch (_: Throwable) {
        }
        try {
            cameraSurface?.release()
        } catch (_: Throwable) {
        }
        cameraSurface = null

        if (program != 0) {
            try {
                GLES20.glDeleteProgram(program)
            } catch (_: Throwable) {
            }
            program = 0
        }

        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            try {
                EGL14.eglMakeCurrent(
                    eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
                )
            } catch (_: Throwable) {
            }
            try {
                if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
            } catch (_: Throwable) {
            }
            try {
                if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
            } catch (_: Throwable) {
            }
            try {
                EGL14.eglTerminate(eglDisplay)
            } catch (_: Throwable) {
            }
        }
        eglSurface = EGL14.EGL_NO_SURFACE
        eglContext = EGL14.EGL_NO_CONTEXT
        eglDisplay = EGL14.EGL_NO_DISPLAY
    }

    // ------------------------------------------------------------ 工具

    private fun toBuffer(array: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(array.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(array)
                position(0)
            }

    private fun buildProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compile(GLES20.GL_VERTEX_SHADER, vertexSource) ?: return 0
        val fragment = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSource) ?: return 0
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vertex)
        GLES20.glAttachShader(p, fragment)
        GLES20.glLinkProgram(p)
        val status = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            Log.e(TAG, "链接程序失败: " + GLES20.glGetProgramInfoLog(p))
            GLES20.glDeleteProgram(p)
            return 0
        }
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        return p
    }

    private fun compile(type: Int, source: String): Int? {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            Log.e(TAG, "编译着色器失败: " + GLES20.glGetShaderInfoLog(shader))
            GLES20.glDeleteShader(shader)
            return null
        }
        return shader
    }

}
