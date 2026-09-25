package com.echosight.app

import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * 把检测结果转成对盲人友好的、以身体为参照的中文指引。
 *
 * 测距部分用双模型融合，思路参考单目视觉测距方案
 * （YOLO 检测 → 相机标定 → 接地参考点 → 地面平面几何）：
 *
 * 模型 A「高度法」——与相机俯仰无关，物体在任何位置（地面/桌面/手中）都适用
 *     d_A = H_real × fx / 框高
 *     依赖类别真实高度先验；框被遮挡或截断时框高失真，误差变大。
 *
 * 模型 B「地面法」——与物体类别无关，高度表缺失的类别同样有效
 *     φ   = atan((v_ref − cy) / fy)        接地参考点相对光轴的下偏角
 *     d_B = h_cam / tan(pitch + φ)         相机离地高 h_cam、俯仰角 pitch
 *     前提是物体确实接触地面；放在桌面上时该假设不成立。
 *
 * 融合：两模型比值落在 [1/FUSE_TOL, FUSE_TOL] 内 → 地面假设成立，按类别高度
 * 可信度加权做几何平均，置信度高；否则判定假设不成立，退回模型 A，置信度低。
 *
 * 参考点用检测框底边中点（接地/接触点）并向上偏移，方位角也用它而不是框中心——
 * 侧方目标时框中心会被目标自身宽度带偏。
 *
 * 时序滤波由 [DistanceTracker] 负责，本对象只做无状态的单帧几何计算。
 */
object Guidance {

    const val STEP_METERS = 0.7f      // 成人一步约 0.7 米

    // ---- 相机参数 ----
    private const val FX_FACTOR = 0.85f   // 兜底焦距系数：fx = 画面宽 × 该值
    private const val CAM_HEIGHT = 1.25f  // 相机镜头离地高度（米），手持约 1.2~1.4

    /**
     * 相机俯仰角的兜底值（度），正值=镜头朝下俯拍。
     * 实际使用时由 [CameraTilt] 从重力传感器实时提供；传感器不可用才用这个常量。
     */
    const val CAM_PITCH_DEG = 0f

    // ---- 参考点与融合 ----
    private const val GROUND_OFFSET = 0.06f   // 接地参考点自框底上移的比例
    private const val FUSE_TOL = 1.6f         // 双模型偏差容忍度
    private const val W_TRUSTED = 0.65f       // 类别高度可信时高度法的融合权重
    private const val W_UNTRUSTED = 0.45f     // 类别高度不可信时高度法权重
    private const val DIST_MIN = 0.15f
    private const val DIST_MAX = 20f

    // ---- 方位分级阈值（度，负=左）----
    private const val DIR_LEFT_DEG = -12f
    private const val DIR_RIGHT_DEG = 12f

    data class Hit(
        val box: DetBox,
        val dist: Float,             // 米
        val angleDeg: Float,         // 负=左，正=右
        val direction: String,       // 正前方 / 左前方 / 你的左侧 …
        val vertical: String,        // 高 / 平视 / 低
        val verticalTip: String,     // 完整说法
        val confidence: Float = 1f,  // 距离置信度 0~1
        val source: String = "height" // fused=双模型一致 / height=仅高度法
    )

    private data class Fused(val dist: Float, val confidence: Float, val source: String)

    /** 按相对身体的角度分级：不使用钟表方向。方位角被平滑后需重新判级，故公开。 */
    fun directionWords(angle: Float): String = when {
        angle <= -35 -> "你的左侧"
        angle <= -10 -> "左前方"
        angle < 10 -> "正前方"
        angle <= 35 -> "右前方"
        else -> "你的右侧"
    }

    /** 高度法：d = H_real × fx / 框高。 */
    private fun heightModel(box: DetBox, fx: Float, targetId: Int): Float {
        val hPx = max(box.y2 - box.y1, 1f)
        val realH = Labels.REAL_HEIGHT[targetId] ?: Labels.DEFAULT_HEIGHT
        return realH * fx / hPx
    }

    /**
     * 地面法：d = h_cam / tan(俯仰角 + 参考点下偏角)。
     * 参考点落在画面水平线以上（总俯角 ≤ 0）时与地面无交点，返回 null。
     *
     * @param pitchDeg 相机俯仰角（度），正值=镜头朝下。手持时由 [CameraTilt] 实时给出。
     */
    private fun groundModel(vRef: Float, cy: Float, fy: Float, pitchDeg: Float): Float? {
        val phi = atan2((vRef - cy).toDouble(), fy.toDouble())
        val depression = Math.toRadians(pitchDeg.toDouble()) + phi
        if (depression <= Math.toRadians(2.0)) return null
        return (CAM_HEIGHT / tan(depression)).toFloat()
    }

    /** 融合两模型：一致则加权几何平均，分歧大则退回高度法。 */
    private fun fuse(dA: Float, dB: Float?, trusted: Boolean): Fused {
        if (dB == null || dB <= 0f) return Fused(dA, 0.5f, "height")
        val ratio = dA / dB
        if (ratio in (1f / FUSE_TOL)..FUSE_TOL) {
            val w = if (trusted) W_TRUSTED else W_UNTRUSTED
            val d = exp(w * ln(dA) + (1f - w) * ln(dB))
            return Fused(d, 0.9f, "fused")
        }
        // 两模型差异过大：物体多半不在假设的地面上（例如杯子在桌上），
        // 此时地面法不可用，退回高度法并降低置信度。
        return Fused(dA, 0.35f, "height")
    }

    /**
     * 单帧无状态测量。同一画面里多个同类目标时对每个框各调一次；
     * 需要跨帧平滑请再用 [DistanceTracker.smooth]。
     *
     * @param pitchDeg 相机俯仰角（度），正值=镜头朝下俯拍。
     *                 默认取常量 [CAM_PITCH_DEG]；实际运行时应传 [CameraTilt.depressionDeg]。
     */
    fun buildHit(box: DetBox, frameW: Float, frameH: Float, targetId: Int,
                 pitchDeg: Float = CAM_PITCH_DEG): Hit {
        val fx = frameW * FX_FACTOR
        val fy = fx
        val cy = frameH / 2f

        // 接地参考点：底边中点按比例上移，抵消"框比实物略大"
        val hPx = max(box.y2 - box.y1, 1f)
        val vRef = box.y2 - GROUND_OFFSET * hPx
        val uRef = (box.x1 + box.x2) / 2f

        val fused = fuse(
            heightModel(box, fx, targetId),
            groundModel(vRef, cy, fy, pitchDeg),
            Labels.heightTrust(targetId)
        )
        val dist = fused.dist.coerceIn(DIST_MIN, DIST_MAX)
        val angle = Math.toDegrees(
            atan2((uRef - frameW / 2f).toDouble(), fx.toDouble())
        ).toFloat()

        // 高低位置：以框的竖向中心判断
        val cyNorm = ((box.y1 + box.y2) / 2f / frameH).coerceIn(0f, 1f)
        val verticalTip = when {
            cyNorm < 0.30f -> "位置偏高，大约在你头部以上"
            cyNorm > 0.66f -> "位置偏低，大约在腰部以下，可能在桌面或地面上"
            else -> "和你的视线差不多高"
        }
        val vertical = when {
            cyNorm < 0.30f -> "高处"
            cyNorm > 0.66f -> "低处"
            else -> "平视"
        }
        return Hit(box, dist, angle, directionWords(angle), vertical, verticalTip,
                   fused.confidence, fused.source)
    }

    /** 距离的口语说法。 */
    fun distanceWords(dist: Float): String {
        if (dist < 0.5f) return "不到半米，伸手就可以摸到"
        if (dist < 0.9f) return "大约一步远，${"%.1f".format(dist)}米"
        val steps = (dist / STEP_METERS).roundToInt().coerceAtLeast(1)
        return "大约${steps}步，${"%.1f".format(dist)}米"
    }

    /** 根据方位和距离给出的行动指引；转动幅度也说清楚。 */
    fun actionTip(hit: Hit): String {
        val parts = ArrayList<String>()
        when {
            hit.angleDeg <= -35 -> parts.add("请向左多转一些身体")
            hit.angleDeg <= -10 -> parts.add("请把身体或手机向左转一点")
            hit.angleDeg >= 35 -> parts.add("请向右多转一些身体")
            hit.angleDeg >= 10 -> parts.add("请把身体或手机向右转一点")
        }
        when {
            hit.dist < 0.5f -> parts.add("就在面前，可以伸手摸了")
            hit.dist < 1.2f -> parts.add("再往前走一两步就到了")
            hit.dist < 3f -> parts.add("请朝着这个方向往前走")
        }
        return if (parts.isEmpty()) "目标在正前方，保持现在的方向"
        else parts.joinToString("，")
    }

    /** 第一次/隔较久看到目标：完整描述。 */
    fun fullReport(name: String, hit: Hit, count: Int): String {
        val countPart = if (count > 1) "看到${count}个${name}，最近的一个"
                        else "看到${name}"
        return "${countPart}在${hit.direction}，${hit.verticalTip}，" +
               "距离${distanceWords(hit.dist)}。${actionTip(hit)}"
    }

    /** 持续看到时的简短播报：告诉用户进展。 */
    fun briefReport(name: String, hit: Hit, distDelta: Float): String {
        val move = when {
            distDelta < -0.2f -> "你在靠近，继续走"
            distDelta > 0.2f -> "注意，你在远离目标"
            else -> "方向保持得很好"
        }
        return "${name}在${hit.direction}，${distanceWords(hit.dist)}，$move。"
    }

    /** 目标丢失时的提示；last 为最近一次看到的目标。 */
    fun lostReport(name: String, last: Hit?, ageMs: Long): String {
        if (last != null && ageMs < 10_000) {
            val turn = when {
                last.angleDeg <= -35 -> "请拿着手机向左多转一些去找"
                last.angleDeg <= -10 -> "请把手机向左转一点去找"
                last.angleDeg >= 35 -> "请拿着手机向右多转一些去找"
                last.angleDeg >= 10 -> "请把手机向右转一点去找"
                else -> "目标可能被挡住了，请原地慢慢转一圈找"
            }
            return "${name}刚刚还在${last.direction}，$turn。"
        }
        return "暂时看不到${name}，请拿着手机慢慢转动身体，上下左右都扫一遍。"
    }
}
