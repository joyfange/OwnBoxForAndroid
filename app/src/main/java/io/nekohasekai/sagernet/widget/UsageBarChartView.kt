package io.nekohasekai.sagernet.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.graphics.ColorUtils
import kotlin.math.max
import kotlin.math.min

/**
 * 每日用量柱状图：圆角渐变柱，选中的柱高亮并在上方标注数值，点柱子切换日期。
 */
class UsageBarChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density

    private var values: List<Long> = emptyList()
    private var labels: List<String> = emptyList()
    private var valueText: (Long) -> String = { it.toString() }
    var selectedIndex = -1
        private set
    var onBarSelected: ((Int) -> Unit)? = null

    private var accent = 0xFF6C8CFF.toInt()
    private var labelColor = 0x99FFFFFF.toInt()
    private var gridColor = 0x22FFFFFF

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1f }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 10.5f * density
    }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bubbleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 11f * density
        isFakeBoldText = true
        color = 0xFFFFFFFF.toInt()
    }

    private var progress = 1f
    private var animator: ValueAnimator? = null
    private val rect = RectF()
    private val bubbleRect = RectF()

    private val topSpace = 30f * density
    private val bottomSpace = 22f * density

    fun setColors(accentColor: Int, labelTextColor: Int, gridLineColor: Int) {
        accent = accentColor
        labelColor = labelTextColor
        gridColor = gridLineColor
        labelPaint.color = labelColor
        gridPaint.color = gridColor
        emptyPaint.color = ColorUtils.setAlphaComponent(labelColor, 0x26)
        bubblePaint.color = accentColor
        invalidate()
    }

    fun setData(
        newValues: List<Long>,
        newLabels: List<String>,
        selected: Int,
        formatter: (Long) -> String,
        animate: Boolean = true,
    ) {
        values = newValues
        labels = newLabels
        selectedIndex = selected
        valueText = formatter
        animator?.cancel()
        if (animate) {
            progress = 0f
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 600L
                interpolator = DecelerateInterpolator(1.4f)
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

    fun select(index: Int) {
        selectedIndex = index
        invalidate()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    private fun slotWidth(): Float {
        val n = max(values.size, 1)
        return (width - paddingLeft - paddingRight).toFloat() / n
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val n = values.size
        if (n == 0 || width == 0) return
        val chartTop = paddingTop + topSpace
        val chartBottom = height - paddingBottom - bottomSpace
        val chartHeight = chartBottom - chartTop
        if (chartHeight <= 0f) return

        // 三条淡网格线
        for (i in 0..2) {
            val y = chartTop + chartHeight * i / 2f
            canvas.drawLine(paddingLeft.toFloat(), y, (width - paddingRight).toFloat(), y, gridPaint)
        }

        val maxValue = max(values.maxOrNull() ?: 0L, 1L).toFloat()
        val slot = slotWidth()
        val barWidth = min(slot * 0.62f, 22f * density)
        val corner = min(barWidth / 2f, 6f * density)
        val labelStep = when {
            n <= 8 -> 1
            n <= 16 -> 2
            else -> 5
        }

        for (i in 0 until n) {
            val cx = paddingLeft + slot * i + slot / 2f
            val left = cx - barWidth / 2f
            val right = cx + barWidth / 2f
            val v = values[i]
            val h = if (v <= 0L) 0f else max(chartHeight * (v / maxValue) * progress, 3f * density)
            val selected = i == selectedIndex

            if (h <= 0f) {
                rect.set(left, chartBottom - 3f * density, right, chartBottom)
                canvas.drawRoundRect(rect, corner, corner, emptyPaint)
            } else {
                rect.set(left, chartBottom - h, right, chartBottom)
                val top = if (selected) accent else ColorUtils.setAlphaComponent(accent, 0x9A)
                val bottom = ColorUtils.setAlphaComponent(accent, if (selected) 0x88 else 0x33)
                barPaint.shader = LinearGradient(0f, rect.top, 0f, rect.bottom, top, bottom, Shader.TileMode.CLAMP)
                canvas.drawRoundRect(rect, corner, corner, barPaint)
                barPaint.shader = null
            }

            val showLabel = selected || i % labelStep == 0 || i == n - 1
            if (showLabel && i < labels.size) {
                labelPaint.isFakeBoldText = selected
                labelPaint.color = if (selected) accent else labelColor
                canvas.drawText(labels[i], cx, chartBottom + 15f * density, labelPaint)
            }
        }

        // 选中柱上方的数值气泡
        if (selectedIndex in 0 until n && progress >= 1f) {
            val v = values[selectedIndex]
            val text = valueText(v)
            val cx = paddingLeft + slot * selectedIndex + slot / 2f
            val h = if (v <= 0L) 3f * density else max(chartHeight * (v / maxValue), 3f * density)
            val textWidth = bubbleTextPaint.measureText(text)
            val bw = textWidth + 14f * density
            val bh = 20f * density
            var bl = cx - bw / 2f
            bl = bl.coerceIn(paddingLeft.toFloat(), (width - paddingRight).toFloat() - bw)
            val bt = max(chartBottom - h - bh - 6f * density, paddingTop.toFloat())
            bubbleRect.set(bl, bt, bl + bw, bt + bh)
            canvas.drawRoundRect(bubbleRect, bh / 2f, bh / 2f, bubblePaint)
            val ty = bubbleRect.centerY() - (bubbleTextPaint.descent() + bubbleTextPaint.ascent()) / 2f
            canvas.drawText(text, bubbleRect.centerX(), ty, bubbleTextPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (values.isEmpty()) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
            MotionEvent.ACTION_UP -> {
                val index = ((event.x - paddingLeft) / slotWidth()).toInt().coerceIn(0, values.size - 1)
                if (index != selectedIndex) {
                    selectedIndex = index
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    invalidate()
                    onBarSelected?.invoke(index)
                }
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
