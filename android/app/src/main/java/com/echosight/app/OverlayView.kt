package com.echosight.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.min

/**
 * 屏幕上要画的一个目标：检测框 + 标注文字。
 *
 * @param primary 是否为最近目标。最近目标是语音播报的那个，画得更醒目；
 *                同画面的其他同类目标压低对比度，避免喧宾夺主。
 */
data class DrawBox(val box: DetBox, val title: String, val primary: Boolean = false)

/**
 * 在相机预览上画框。相机画面按 fitCenter 居中显示，坐标映射保持同样规则。
 *
 * 可读性上有几点考虑：
 *  - 文字尺寸走 sp（原先是硬编码 38px，在 1080p 屏上只有约 12sp，偏小）；
 *  - 标注做成圆角胶囊，并钳制在视图内，右侧目标的文字不会跑出屏幕；
 *  - 框下面垫一层半透明深色描边，浅色画面上也能看清；
 *  - 画面中心留一个很淡的准星，给陪行的人一个"正前方"的参照。
 */
class OverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var drawBoxes: List<DrawBox> = emptyList()
        set(value) {
            // 排序挪到赋值处：onDraw 每帧都会跑，原先在里面 sortedBy
            // 等于每帧新建一个列表（30fps 就是 30 次/秒的无谓分配）。
            field = value.sortedBy { it.primary }
            postInvalidate()
        }

    var frameWidth = 480
    var frameHeight = 640

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    /** 调色板统一放在 colors.xml，这里只取用，避免色值在代码里再抄一份。 */
    private fun colorOf(id: Int) = ContextCompat.getColor(context, id)

    private val labelTextSize = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, 15f, resources.displayMetrics)

    /** 框底阴影：浅色画面上保证轮廓可见 */
    private val boxShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(6f)
        color = colorOf(R.color.box_shadow)
        strokeJoin = Paint.Join.ROUND
    }
    private val primaryBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3.5f)
        color = colorOf(R.color.box_primary)
        strokeJoin = Paint.Join.ROUND
    }
    private val secondaryBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = colorOf(R.color.box_secondary)
        strokeJoin = Paint.Join.ROUND
    }
    private val secondaryLabelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = colorOf(R.color.box_label_bg)
    }
    private val primaryLabelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = colorOf(R.color.box_label_bg_primary)
    }
    private val secondaryLabelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorOf(R.color.white)
        textSize = labelTextSize
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val primaryLabelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorOf(R.color.box_label_text_primary)
        textSize = labelTextSize
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val reticlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        strokeCap = Paint.Cap.ROUND
        color = colorOf(R.color.reticle)
    }

    private val rect = RectF()
    private val labelRect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (frameWidth <= 0 || frameHeight <= 0) return

        // 与 PreviewView 的 fitCenter 保持同一套映射规则
        val scale = min(width.toFloat() / frameWidth, height.toFloat() / frameHeight)
        val contentW = frameWidth * scale
        val contentH = frameHeight * scale
        val offX = (width - contentW) / 2f
        val offY = (height - contentH) / 2f

        drawReticle(canvas, offX, offY, contentW, contentH)

        val padH = dp(10f)
        val padV = dp(7f)
        val radius = dp(8f)
        val gap = dp(6f)
        val edge = dp(4f)

        // 先画次要目标、后画最近目标，保证最近目标压在最上层
        // （顺序已在 drawBoxes 的 setter 里排好）
        for (d in drawBoxes) {
            val b = d.box
            rect.set(
                offX + b.x1 * scale, offY + b.y1 * scale,
                offX + b.x2 * scale, offY + b.y2 * scale)

            canvas.drawRect(rect, boxShadowPaint)
            canvas.drawRect(rect, if (d.primary) primaryBoxPaint else secondaryBoxPaint)

            val textPaint = if (d.primary) primaryLabelTextPaint else secondaryLabelTextPaint
            val chipW = textPaint.measureText(d.title) + padH * 2f
            val chipH = labelTextSize + padV * 2f

            // 默认贴在框上方；顶部放不下就挪进框内顶部
            var top = rect.top - chipH - gap
            if (top < edge) top = rect.top + gap
            // 水平方向钳制在视图内，避免右侧目标的文字跑出屏幕
            var left = rect.left
            if (left + chipW > width - edge) left = width - edge - chipW
            if (left < edge) left = edge

            labelRect.set(left, top, left + chipW, top + chipH)
            canvas.drawRoundRect(
                labelRect, radius, radius,
                if (d.primary) primaryLabelBgPaint else secondaryLabelBgPaint)

            // 基线取胶囊垂直居中位置
            val baseline = labelRect.centerY() -
                (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(d.title, left + padH, baseline, textPaint)
        }
    }

    /** 画面正中的淡准星，标出"正前方"；没有目标时也画，方便先对准方向。 */
    private fun drawReticle(
        canvas: Canvas, offX: Float, offY: Float, contentW: Float, contentH: Float
    ) {
        val cx = offX + contentW / 2f
        val cy = offY + contentH / 2f
        val r = dp(13f)
        val arm = dp(7f)
        canvas.drawCircle(cx, cy, r, reticlePaint)
        canvas.drawLine(cx - r - arm, cy, cx - r, cy, reticlePaint)
        canvas.drawLine(cx + r, cy, cx + r + arm, cy, reticlePaint)
        canvas.drawLine(cx, cy - r - arm, cx, cy - r, reticlePaint)
        canvas.drawLine(cx, cy + r, cx, cy + r + arm, reticlePaint)
    }
}
