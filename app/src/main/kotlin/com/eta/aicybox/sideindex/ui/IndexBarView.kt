package com.eta.aicybox.sideindex.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.eta.aicybox.sideindex.config.ModuleConfig
import com.eta.aicybox.sideindex.config.Side
import com.eta.aicybox.sideindex.index.IndexGroup
import com.eta.aicybox.sideindex.util.Display
import com.eta.aicybox.sideindex.util.StarDraw
import kotlin.math.min

data class BarStyle(
    val side: Side,
    val barWidthPx: Int,
    val radiusPx: Float,
    val bgColor: Int,
    val textColor: Int,
    val highlightColor: Int,
    val textSizePx: Float,
    /** 字号上限：触发高度调大后字号不再增长，只拉开字母间距（按 38% 屏高时的字号定死） */
    val maxTextSizePx: Float,
    val fadeMs: Int,
    val viewWidthPx: Int
) {
    companion object {
        fun from(ctx: Context, config: ModuleConfig, side: Side, maxTextSizePx: Float): BarStyle {
            val barWidth = Display.dp(ctx, ModuleConfig.FIXED_BAR_WIDTH_DP)
            val alpha = (Color.alpha(config.barBgColor) * config.barBgAlpha / 100).coerceIn(0, 255)
            return BarStyle(
                side = side,
                barWidthPx = barWidth,
                radiusPx = Display.dp(ctx, config.barRadiusDp).toFloat(),
                bgColor = (config.barBgColor and 0x00FFFFFF) or (alpha shl 24),
                textColor = config.barTextColor,
                highlightColor = config.barHighlightColor,
                textSizePx = Display.sp(ctx, ModuleConfig.FIXED_BAR_TEXT_SIZE_SP),
                maxTextSizePx = maxTextSizePx,
                fadeMs = config.barFadeMs,
                viewWidthPx = barWidth + Display.dp(ctx, 2)
            )
        }
    }
}

/** 纵向字母条：只负责绘制（触摸统一由宿主 Controller 裁决） */
class IndexBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var style: BarStyle = BarStyle(
        side = Side.RIGHT,
        barWidthPx = Display.dp(context, ModuleConfig.FIXED_BAR_WIDTH_DP),
        radiusPx = Display.dp(context, 15).toFloat(),
        bgColor = 0x9E1F2126.toInt(),
        textColor = 0xFFE8EAED.toInt(),
        highlightColor = 0xFF3D7EFF.toInt(),
        textSizePx = Display.sp(context, ModuleConfig.FIXED_BAR_TEXT_SIZE_SP),
        maxTextSizePx = Display.sp(context, ModuleConfig.FIXED_BAR_TEXT_SIZE_SP),
        fadeMs = 180,
        viewWidthPx = Display.dp(context, ModuleConfig.FIXED_BAR_WIDTH_DP + 2)
    )
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    private val groups = ArrayList<IndexGroup>()
    private var currentIndex = -1
    private var alphaValue = 1f
    private var animator: ValueAnimator? = null

    private val letterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun submit(list: List<IndexGroup>, index: Int) {
        groups.clear()
        groups.addAll(list)
        currentIndex = index
        invalidate()
    }

    fun setCurrentIndex(index: Int) {
        if (index != currentIndex) {
            currentIndex = index
            invalidate()
        }
    }

    fun fadeTo(target: Float, durationMs: Long = style.fadeMs.toLong()) {
        animator?.cancel()
        if (durationMs <= 0L) {
            alphaValue = target
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(alphaValue, target).apply {
            duration = durationMs
            addUpdateListener {
                alphaValue = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /** 视图内 y → 分组下标 */
    fun indexAtY(y: Float): Int {        if (groups.isEmpty()) return -1
        val top = letterAreaTop()
        val bottom = letterAreaBottom()
        val span = (bottom - top).coerceAtLeast(1f)
        val ratio = ((y - top) / span).coerceIn(0f, 0.9999f)
        return (ratio * groups.size).toInt().coerceIn(0, groups.size - 1)
    }

    /** 每个分组占的像素高度（换字母的迟滞判定用） */
    fun stepPx(): Float {
        if (groups.isEmpty()) return 0f
        return ((letterAreaBottom() - letterAreaTop()) / groups.size).coerceAtLeast(1f)
    }

    /** 某个分组字母在视图内的中心 y（用于把应用网格与高亮字母对齐） */
    fun letterCenterY(index: Int): Float {
        if (groups.isEmpty()) return height / 2f
        val top = letterAreaTop()
        val bottom = letterAreaBottom()
        val step = (bottom - top) / groups.size
        return top + step * index.coerceIn(0, groups.size - 1) + step / 2f
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = style.viewWidthPx.coerceAtLeast(Display.dp(context, 24))
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)
        val height = if (heightSize > 0) heightSize else suggestedMinimumHeight
        setMeasuredDimension(width, height)
    }

    private fun letterAreaTop(): Float = (height * 0.02f).coerceAtLeast(Display.dp(context, 6).toFloat())
    private fun letterAreaBottom(): Float = (height - letterAreaTop()).coerceAtLeast(letterAreaTop() + 1f)

    private fun applyAlpha(color: Int): Int {
        val a = (Color.alpha(color) * alphaValue).toInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (a shl 24)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (groups.isEmpty() || alphaValue <= 0.01f) return
        val s = style
        // 字母中心到呼出侧屏幕边缘的距离：原来是"半个条宽"（≈11.5dp），
        // 按用户要求把这层留白收窄一半 —— 写死 8dp，字母到屏幕边缘的空白从 ~7dp 收到 ~3.5dp。
        // 只动字母条自己的绘制位置；图标网格的边距在 IndexOverlayView 里单独控制，不受影响。

        // 字母条不画背景胶囊（既不美观又浪费合成），字母直接叠在内容上。
        // 呼出时底下永远压着黑色 scrim + 模糊壁纸，所以这里写死浅色字，
        // 不要再跟深浅模式联动（1.13.2 起已去掉阴影与自适应；旧的 setDark()/阴影绘制是死代码，已删）
        val normalColor = 0xE6FFFFFF.toInt()

        val top = letterAreaTop()
        val bottom = letterAreaBottom()
        val step = (bottom - top) / groups.size
        // 字号上限：触发高度调大后字母不再跟着变大，多出来的高度只拉开间距
        val textSize = min(min(s.textSizePx, s.maxTextSizePx), step * 0.95f).coerceAtLeast(6f)

        val centerX = if (s.side == Side.RIGHT) {
            width - Display.dp(context, ModuleConfig.FIXED_BAR_EDGE_INSET_DP).toFloat()
        } else {
            Display.dp(context, ModuleConfig.FIXED_BAR_EDGE_INSET_DP).toFloat()
        }
        for (i in groups.indices) {
            val isCurrent = i == currentIndex
            // 当前字母放大加粗 + 高亮色，其余字母用常规字重
            letterPaint.textSize = if (isCurrent) textSize * 1.4f else textSize
            letterPaint.typeface = Typeface.DEFAULT_BOLD
            val color = if (isCurrent) s.highlightColor else normalColor
            letterPaint.color = color
            val centerY = top + step * i + step / 2f
            val label = groups[i].label
            if (StarDraw.isStar(label)) {
                // ★ 自绘五角星：字体里没这个字形时 drawText 会画成空白，看起来像收藏栏没出现
                StarDraw.draw(canvas, centerX, centerY, letterPaint.textSize * 0.5f, color)
            } else {
                val baseline = centerY - (letterPaint.descent() + letterPaint.ascent()) / 2f
                canvas.drawText(label, centerX, baseline, letterPaint)
            }
        }
    }
}
