package com.echosight.app

import kotlin.math.acos
import kotlin.math.sqrt

/**
 * 由重力向量推算相机光轴的俯仰角。纯函数，便于脱离传感器单独测试。
 *
 * 推导：
 *   - 后置摄像头的光轴在设备坐标系里指向 −Z（从屏幕背面射出），即 o = (0, 0, −1)；
 *   - 重力传感器静止时的读数 g 指向世界"上"方向在设备坐标系中的表示，
 *     单位化后记为 u（手机平放屏幕朝上时 u = (0, 0, 1)）；
 *   - 光轴相对水平面的仰角 = 90° − acos(o · u)，俯角取负号：
 *
 *         俯角 = acos(o · u) − 90° = acos(−u.z) − 90°
 *
 *   因为 o = (0, 0, −1)，点积只剩 z 分量，所以只需要重力的 z 分量。
 *
 * 校验（见 scripts 里的自检）：
 *   手机平放、屏幕朝上   u = (0, 0, 1)  → 俯角  90°（镜头垂直朝下）
 *   手机竖持、镜头朝前   u = (0, 1, 0)  → 俯角   0°（光轴水平）
 *   手机后仰、镜头朝上   u = (0, 0.87, −0.5) → 俯角 −30°（光轴抬高 30°）
 */
object TiltMath {

    /**
     * @param gx 重力传感器 x 分量（设备坐标系，m/s²）
     * @param gy 重力传感器 y 分量
     * @param gz 重力传感器 z 分量
     * @return 相机光轴相对水平面的俯角（度），正值=镜头朝下俯拍，负值=朝上仰拍
     */
    fun depressionDeg(gx: Float, gy: Float, gz: Float): Float {
        val norm = sqrt(gx * gx + gy * gy + gz * gz)
        if (norm < 1e-3f) return 0f            // 数据无效，按水平处理
        val cosTheta = (-gz / norm).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(cosTheta).toDouble()).toFloat() - 90f
    }
}
