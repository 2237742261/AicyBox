package com.eta.aicybox.sideindex.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.eta.aicybox.sideindex.config.ModuleConfig
import com.eta.aicybox.sideindex.config.Side
import com.eta.aicybox.sideindex.util.Display

/**
 * 触发区高亮：设置页调参时实时显示（静态），也可用于调试（闪烁）。
 * 不消费触摸，叠在谁的上面都不影响原交互。
 * 开启"两侧均可呼出"时左右两条一起画。
 */
class TriggerBandView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var config = ModuleConfig()
    private var currentSide: Side = Side.RIGHT
    private var bothSides = false

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun updateConfig(
        newConfig: ModuleConfig,
        side: Side = Side.RIGHT,
        both: Boolean = newConfig.onLeft() && newConfig.onRight()
    ) {
        config = newConfig
        currentSide = side
        bothSides = both
        invalidate()
    }

    fun setLive(show: Boolean) {
        removeCallbacks(hideRunnable)
        alpha = if (show) 1f else 0f
        invalidate()
    }

    fun hideLater(delayMs: Long = 900L) {
        removeCallbacks(hideRunnable)
        postDelayed(hideRunnable, delayMs)
    }

    private val hideRunnable = Runnable { setLive(false) }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        if (bothSides) {
            drawBand(canvas, Side.RIGHT)
            drawBand(canvas, Side.LEFT)
        } else {
            drawBand(canvas, currentSide)
        }
    }

    private fun drawBand(canvas: Canvas, side: Side) {
        val size = Display.dp(context, config.triggerWidthDp).toFloat()
        val left = if (side == Side.RIGHT) width - size else 0f
        val right = left + size
        val band = bandHeight()
        val center = height * config.triggerPositionPercent / 100f
        val top = (center - band / 2f).coerceIn(0f, (height - band).coerceAtLeast(0f))
        val bottom = top + band
        val accent = config.barHighlightColor

        fillPaint.color = (accent and 0x00FFFFFF) or (0x6E shl 24)
        canvas.drawRect(left, top, right, bottom, fillPaint)

        edgePaint.color = (accent and 0x00FFFFFF) or (0xF0 shl 24)
        edgePaint.strokeWidth = Display.dp(context, 2).toFloat()
        edgePaint.pathEffect = DashPathEffect(
            floatArrayOf(Display.dp(context, 7).toFloat(), Display.dp(context, 7).toFloat()), 0f
        )
        val edgeX = if (side == Side.RIGHT) left else right
        canvas.drawLine(edgeX, top, edgeX, bottom, edgePaint)

        textPaint.color = (accent and 0x00FFFFFF) or (0xFF shl 24)
        textPaint.textSize = Display.sp(context, 12)
        val centerX = (left + right) / 2f
        val centerY = (top + bottom) / 2f
        canvas.save()
        canvas.rotate(if (side == Side.RIGHT) 90f else -90f, centerX, centerY)
        canvas.drawText(
            "触发区 ${config.triggerWidthDp}dp · 位置 ${config.triggerPositionPercent}% · 高 ${config.barHeightPercent}%",
            centerX, centerY, textPaint
        )
        canvas.restore()
    }

    /** 触发区高度 = 字母条高度（和实际拦截区域一致） */
    private fun bandHeight(): Float =
        (height * config.barHeightPercent / 100f)
            .coerceAtLeast(Display.dp(context, 200).toFloat())
            .coerceAtMost(height.toFloat())
}
