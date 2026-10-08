package io.nekohasekai.sagernet.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.min

/**
 * 环形占比图：每段一个应用，中间写总量。数据更新时从 12 点方向顺时针展开。
 */
class UsageDonutView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    data class Slice(val value: Long, val color: Int)

    private val density = resources.displayMetrics.density
    private val strokeWidthPx = 18f * density
    private val gapDegrees = 2.2f

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
        strokeCap = Paint.Cap.ROUND
    }
    private val slicePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
        strokeCap = Paint.Cap.BUTT
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = 26f * density
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 12.5f * density
    }

    private var slices: List<Slice> = emptyList()
    private var centerValue = ""
    private var centerLabel = ""
    private var progress = 1f
    private var animator: ValueAnimator? = null
    private val arcRect = RectF()

    fun setColors(trackColor: Int, valueColor: Int, labelColor: Int) {
        trackPaint.color = trackColor
        valuePaint.color = valueColor
        labelPaint.color = labelColor
        invalidate()
    }

    fun setData(newSlices: List<Slice>, value: String, label: String, animate: Boolean = true) {
        slices = newSlices.filter { it.value > 0L }
        centerValue = value
        centerLabel = label
        animator?.cancel()
        if (animate && slices.isNotEmpty()) {
            progress = 0f
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 750L
                interpolator = DecelerateInterpolator(1.6f)
                addUpdateListener {
                    progress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            progress = 1f
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = min(width - paddingLeft - paddingRight, height - paddingTop - paddingBottom).toFloat()
        if (size <= 0f) return
        val cx = paddingLeft + (width - paddingLeft - paddingRight) / 2f
        val cy = paddingTop + (height - paddingTop - paddingBottom) / 2f
        val radius = size / 2f - strokeWidthPx / 2f
        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius)

        canvas.drawArc(arcRect, 0f, 360f, false, trackPaint)

        val total = slices.sumOf { it.value }.toFloat()
        if (total > 0f) {
            val sweepTotal = 360f * progress
            var start = -90f
            val gap = if (slices.size > 1) gapDegrees else 0f
            for (slice in slices) {
                val full = 360f * slice.value / total
                val visible = min(full, (-90f + sweepTotal) - start)
                if (visible <= 0f) break
                val sweep = (visible - gap).coerceAtLeast(0.6f)
                slicePaint.color = slice.color
                canvas.drawArc(arcRect, start + gap / 2f, sweep, false, slicePaint)
                start += full
            }
        }

        val valueY = cy - (valuePaint.descent() + valuePaint.ascent()) / 2f - 7f * density
        canvas.drawText(centerValue, cx, valueY, valuePaint)
        canvas.drawText(centerLabel, cx, valueY + 22f * density, labelPaint)
    }
}
