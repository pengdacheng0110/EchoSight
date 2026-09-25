package com.echosight.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * 用重力传感器实时给出相机俯仰角，供地面法测距使用。
 *
 * 原先俯仰角是写死的常量（[Guidance.CAM_PITCH_DEG]），但手持手机时它会一直变，
 * 地面法算出来的距离就跟着飘。接上传感器后俯仰角随手机姿态实时更新，
 * 高度表缺失的类别也能靠地面法给出可用距离。
 *
 * 传感器不可用（模拟器、部分设备没有 TYPE_GRAVITY）时自动回退到固定值。
 *
 * 用法：在拿到相机权限后 [start]，在 [android.app.Activity.onDestroy] 里 [stop]。
 */
class CameraTilt(
    context: Context,
    private val fallbackDeg: Float = Guidance.CAM_PITCH_DEG,
) : SensorEventListener {

    /** 当前俯仰角（度），正值=镜头朝下俯拍。传感器不可用时恒为 fallbackDeg。 */
    @Volatile
    var depressionDeg: Float = fallbackDeg
        private set

    private val manager: SensorManager? =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private val gravitySensor: Sensor? =
        manager?.getDefaultSensor(Sensor.TYPE_GRAVITY)

    private var smoothed: Float? = null
    private var registered = false

    /** 设备是否真的提供了重力传感器。 */
    val available: Boolean get() = gravitySensor != null

    /** 幂等：重复调用不会重复注册。 */
    fun start() {
        val s = gravitySensor ?: return
        if (registered) return
        if (manager?.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME) == true) {
            registered = true
        }
    }

    fun stop() {
        if (!registered) return
        manager?.unregisterListener(this)
        registered = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        val v = event.values
        if (v.size < 3) return
        val raw = TiltMath.depressionDeg(v[0], v[1], v[2])
        // 手机在手里会晃，重力估计本身也有噪声，低通后再交给测距，
        // 否则地面法的距离会跟着手抖一起跳。
        val prev = smoothed
        val next = if (prev == null) raw else ALPHA * raw + (1f - ALPHA) * prev
        smoothed = next
        depressionDeg = next
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // 精度变化不影响使用，无需处理
    }

    private companion object {
        /** 低通系数，越小越稳但跟随越慢。 */
        const val ALPHA = 0.15f
    }
}
