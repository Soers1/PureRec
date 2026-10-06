package com.purerec.core

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import kotlin.math.abs

/**
 * 摇一摇检测。
 *
 * 用加速度计的三轴增量判断，只有连续两次采样之间的变化量够大才算一次摇动，
 * 比直接看模长更不容易被「慢慢举起手机」误触发。
 */
class ShakeDetector(
    context: Context,
    private val level: ShakeLevel,
    private val onShake: () -> Unit,
) : SensorEventListener {

    companion object {
        private const val TAG = "PureRec-Shake"
        /** 两次触发之间的冷却，避免一次摇动被算成好几次。 */
        private const val COOLDOWN_MS = 1200L
    }

    private val manager =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val sensor = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var lastX = 0f
    private var lastY = 0f
    private var lastZ = 0f
    private var primed = false
    private var lastTriggerAt = 0L

    /** 传感器是否真的存在。 */
    val available: Boolean get() = sensor != null

    fun start() {
        val s = sensor ?: return
        primed = false
        try {
            manager?.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
        } catch (t: Throwable) {
            Log.w(TAG, "加速度计不可用", t)
        }
    }

    fun stop() {
        try {
            manager?.unregisterListener(this)
        } catch (_: Throwable) {
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        if (!primed) {
            lastX = x
            lastY = y
            lastZ = z
            primed = true
            return
        }

        val delta = abs(x - lastX) + abs(y - lastY) + abs(z - lastZ)
        lastX = x
        lastY = y
        lastZ = z

        if (delta < level.threshold) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastTriggerAt < COOLDOWN_MS) return
        lastTriggerAt = now
        onShake()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
