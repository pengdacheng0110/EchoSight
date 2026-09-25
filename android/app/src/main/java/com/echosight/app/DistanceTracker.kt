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
 *
 * ## 线程约定：这个类会被两个线程碰
 *
 * [smooth] 由分析线程每帧调用（CameraX 的 analysisExecutor）；
 * [reset] 由主线程调用（`switchTarget` 走的是 `lifecycleScope.launch`，
 * 默认就是 Dispatchers.Main）。
 *
 * 而 [tracks] 是普通 HashMap，不是线程安全的容器。切换目标的那一刻，
 * 主线程在 `remove`、分析线程在 `getOrPut` + 改 Track 内部字段，
 * 两边同时动同一张表 —— 结构可能被改坏（丢条目、拿到半更新的 Track）。
 * 后果不是崩溃，而是**播报出来的距离被上一个目标的滤波历史污染**：
 * 换了目标之后头几秒的数字是错的，听起来却完全正常。
 * 这类"看起来一切正常的错数据"对盲人用户最危险，所以两个方法都加锁。
 *
 * 锁的代价可以忽略：分析线程每帧一次、主线程只在切目标时一次，
 * 实际几乎不会争用（uncontended lock 只有几十纳秒）。
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

    /**
     * 清空滤波状态；传 null 清全部，传类别 id 只清该类别（切换目标时用）。
     *
     * @Synchronized 的理由见类文档「线程约定」——本方法在主线程跑，
     * 而 [smooth] 在分析线程跑，两者共用 [tracks]。
     */
    @Synchronized
    fun reset(targetId: Int? = null) {
        if (targetId == null) tracks.clear() else tracks.remove(targetId)
    }

    /**
     * 对选中目标的测距结果做平滑，返回替换了 dist / angleDeg / direction 的副本。
     *
     * @Synchronized 的理由见类文档「线程约定」。
     */
    @Synchronized
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
