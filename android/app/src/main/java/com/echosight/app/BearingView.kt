package com.echosight.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.abs

/**
 * 方位条：把目标相对画面中心的水平角画成一条刻度带。
 *
 * 角度数据来自 [Guidance.Hit.angleDeg]（负=左，正=右），原先只用于生成语音，
 * 这里顺手可视化，让低视力用户和陪行的人一眼看出目标偏向哪边、偏多少。
 *
 * 刻度位置与 [Guidance.directionWords] 的分级阈值对齐（±10° / ±35°），
 * 所以刻度线落在哪里，和语音里说的"左前方 / 你的左侧"是同一套标准。
 * 超出 [MAX_ANGLE] 的角度贴边显示，并在外侧补一个小三角表示"还在更外侧"。
 */
class BearingView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    /** 目标水平角（度），负=左，正=右。null 表示当前没有目标。 */
    var angleDeg: Float? = null
        set(value) {
            if (field == value) return
            field = value
            postInvalidate()
        }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    /** 调色板统一放在 colors.xml，这里只取用。 */
    private fun colorOf(id: Int) = ContextCompat.getColor(context, id)

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        strokeCap = Paint.Cap.ROUND
        color = colorOf(R.color.bearing_track)
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        strokeCap = Paint.Cap.ROUND
        color = colorOf(R.color.bearing_tick)
    }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
        color = colorOf(R.color.bearing_center)
    }
    private val markerHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = colorOf(R.color.bearing_marker_halo)
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = colorOf(R.color.bearing_marker)
    }
    private val arrow = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val left = dp(6f)
        val right = width - dp(6f)
        if (right <= left) return
        val cy = height / 2f
        val midX = (left + right) / 2f
        val half = (right - left) / 2f

        canvas.drawLine(left, cy, right, cy, trackPaint)

        // 与语音分级阈值对齐的刻度：±10° 是"正前方"的边界，±35° 是"侧方"的边界
        for (a in floatArrayOf(-35f, -10f, 10f, 35f)) {
            val x = midX + half * (a / MAX_ANGLE)
            canvas.drawLine(x, cy - dp(5f), x, cy + dp(5f), tickPaint)
        }
        canvas.drawLine(midX, cy - dp(9f), midX, cy + dp(9f), centerPaint)

        val a = angleDeg ?: return
        val x = midX + half * (a.coerceIn(-MAX_ANGLE, MAX_ANGLE) / MAX_ANGLE)

        canvas.drawCircle(x, cy, dp(7f), markerHaloPaint)
        canvas.drawCircle(x, cy, dp(3.5f), markerPaint)

        // 角度超出量程时，在贴边的一侧补个三角形，表示目标还在更外侧
        if (abs(a) > MAX_ANGLE + 1f) {
            val dir = if (a < 0f) -1f else 1f
            val baseX = x + dir * dp(1f)
            val tipX = (x + dir * dp(10f)).coerceIn(left, right)
            arrow.reset()
            arrow.moveTo(tipX, cy)
            arrow.lineTo(baseX, cy - dp(5f))
            arrow.lineTo(baseX, cy + dp(5f))
            arrow.close()
            canvas.drawPath(arrow, markerPaint)
        }
    }

    private companion object {
        /** 方位条覆盖的最大角度；超出则贴边显示。 */
        const val MAX_ANGLE = 60f
    }
}
