package com.echosight.app

/**
 * 单目测距的时序滤波。
 *
 * 同一目标连续帧的距离先过中位数窗口（抗检测框跳变离群），再过指数平滑，
 * 避免语音播报的数字剧烈跳动；方位角也做轻量平滑，防止在分级阈值附近
 * 来回切换导致方位说法反复横跳。
 *
 * 注意只对最终要播报的那一个目标调用 [smooth]——同一画面里可能同时出现多个
 * 同类目标（比如三把椅子），全部喂进来会把不同物体的距离混在一起。
 */
class DistanceTracker(
    private val medianWin: Int = 5,
    private val emaAlpha: Float = 0.35f,
    private val angleAlpha: Float = 0.4f,
    private val ttlMs: Long = 1500L,
) {
    private class Track {
        val buf = ArrayList<Float>(8)
        var ema: Float? = null
        var angle: Float? = null
        var ts: Long = 0L
    }

    private val tracks = HashMap<Int, Track>()

    /** 清空滤波状态；传 null 清全部，传类别 id 只清该类别（切换目标时用）。 */
    fun reset(targetId: Int? = null) {
        if (targetId == null) tracks.clear() else tracks.remove(targetId)
    }

    /** 对选中目标的测距结果做平滑，返回替换了 dist / angleDeg / direction 的副本。 */
    fun smooth(targetId: Int, hit: Guidance.Hit, now: Long): Guidance.Hit {
        val t = tracks.getOrPut(targetId) { Track() }
        if (now - t.ts > ttlMs) {
            // 目标丢了很久，旧距离不再有参考价值，重新起算
            t.buf.clear()
            t.ema = null
            t.angle = null
        }

        t.buf.add(hit.dist)
        while (t.buf.size > medianWin) t.buf.removeAt(0)
        val med = median(t.buf)

        val prevEma = t.ema
        val ema = if (prevEma == null) med else emaAlpha * med + (1f - emaAlpha) * prevEma
        t.ema = ema

        val prevAngle = t.angle
        val angle = if (prevAngle == null) hit.angleDeg
                    else angleAlpha * hit.angleDeg + (1f - angleAlpha) * prevAngle
        t.angle = angle
        t.ts = now

        return hit.copy(
            dist = ema,
            angleDeg = angle,
            direction = Guidance.directionWords(angle),
        )
    }

    private fun median(values: List<Float>): Float {
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2]
               else (sorted[n / 2 - 1] + sorted[n / 2]) / 2f
    }
}
