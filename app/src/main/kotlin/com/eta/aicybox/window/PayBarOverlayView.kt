package com.eta.aicybox.window

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.eta.aicybox.config.PayChannel

/**
 * @author bomo 2026-10-19 「聚合支付」小窗下方的支付方式切换栏（Flyme 式）。
 *
 * 形态：贴在**小窗正下方**的白色圆角胶囊，里面按用户排序排 4 个**圆形**支付图标，
 * 点任意一个就切到该支付方式。整条栏的宽高由小窗 bounds 按比例算出
 * （见 `ColorOsFreeformCoordinator.updatePayBar`），所以小窗放大缩小时栏跟着缩放。
 *
 * 只依赖 android.view（跑在 system_server 里），不引 Compose。
 */
internal class PayBarOverlayView(
    context: Context,
    private val items: List<Item>,
    private val onSelected: (PayChannel) -> Unit,
) : View(context) {

    /** 一个支付方式：渠道 + 图标（侧边栏工具目录的原厂 PNG）。 */
    internal data class Item(
        val channel: PayChannel,
        val icon: Bitmap?,
    )

    private var barWidthPx = 0
    private var barHeightPx = 0
    private var iconSizePx = 0f
    private var itemWidthPx = 0f

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BACKGROUND_COLOR }
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SELECTED_COLOR }
    private val barRect = RectF()
    private val barPath = Path()
    private val iconRect = RectF()
    private val selectedRect = RectF()

    private var selectedIndex = 0
    private var pressedIndex = -1

    /** @author bomo 2026-10-22 上次成功切换的时间（毫秒），用于节流：切换过快时忽略本次点击。 */
    private var lastSwitchAt = 0L

    /**
     * @author bomo 2026-10-19 深浅色跟随系统：深色模式下栏用深灰底，否则系统一换深色
     * 这条白栏就特别扎眼（用户实测反馈）。
     *
     * 由 `ColorOsFreeformCoordinator` 每拍按 `Resources.getSystem().configuration` 喂进来
     * （system_server 里的 View 收不到 Activity 那套配置回调）。
     */
    private var darkMode = false

    fun setDarkMode(dark: Boolean) {
        if (dark == darkMode) return
        darkMode = dark
        applyColors()
        invalidate()
    }

    private fun applyColors() {
        backgroundPaint.color = if (darkMode) BACKGROUND_COLOR_DARK else BACKGROUND_COLOR
        selectedPaint.color = if (darkMode) SELECTED_COLOR_DARK else SELECTED_COLOR
    }

    /** 栏里一共几个支付方式（日志用）。 */
    val itemCount: Int get() = items.size

    /** 由外部按小窗尺寸驱动；宽高变化时重算所有比例。 */
    fun setBarSize(
        widthPx: Int,
        heightPx: Int,
    ) {
        if (widthPx <= 0 || heightPx <= 0) return
        if (widthPx == barWidthPx && heightPx == barHeightPx) return
        barWidthPx = widthPx
        barHeightPx = heightPx
        itemWidthPx = widthPx.toFloat() / items.size.coerceAtLeast(1)
        // 图标直径 = 栏高 × 0.72（0.2.62 用户要求图标再大一点），且不超过单项宽度 × 0.72。
        iconSizePx = minOf(heightPx * 0.72f, itemWidthPx * 0.72f)
        requestLayout()
        invalidate()
    }

    fun select(index: Int) {
        if (index !in items.indices || index == selectedIndex) return
        selectedIndex = index
        invalidate()
    }

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        setMeasuredDimension(barWidthPx.coerceAtLeast(1), barHeightPx.coerceAtLeast(1))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (barWidthPx <= 0 || barHeightPx <= 0) return
        barRect.set(0f, 0f, width.toFloat(), height.toFloat())
        // 0.2.62 用户要求「边框改成圆角」：四角全圆（胶囊形）。
        // 0.2.58 曾经只圆下沿（为了贴住小窗当一体），但现在栏浮在小窗下方，圆角反而更像一体的悬浮条。
        val radius = minOf(barHeightPx * 0.5f, barWidthPx * 0.06f)
        barPath.reset()
        barPath.addRoundRect(
            barRect,
            floatArrayOf(radius, radius, radius, radius, radius, radius, radius, radius),
            Path.Direction.CW,
        )
        canvas.drawPath(barPath, backgroundPaint)

        items.forEachIndexed { index, item ->
            val centerX = itemWidthPx * (index + 0.5f)
            val centerY = height / 2f
            if (index == selectedIndex) {
                val radius = iconSizePx / 2f + barHeightPx * 0.10f
                selectedRect.set(
                    centerX - radius,
                    centerY - radius,
                    centerX + radius,
                    centerY + radius,
                )
                canvas.drawRoundRect(selectedRect, radius, radius, selectedPaint)
            }
            val half = iconSizePx / 2f
            iconRect.set(centerX - half, centerY - half, centerX + half, centerY + half)
            item.icon?.let { bitmap ->
                // 圆形裁切：原厂工具图标是方图，裁圆后才和 Flyme 的观感一致。
                val save = canvas.save()
                canvas.clipPath(
                    android.graphics.Path().apply { addCircle(centerX, centerY, half, android.graphics.Path.Direction.CW) },
                )
                canvas.drawBitmap(bitmap, null, iconRect, null)
                canvas.restoreToCount(save)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val idx = indexAt(event.x)
                // @author bomo 2026-10-22 当前选中的支付方式按钮不可点击：点自己没意义，
                // DOWN 直接返回 false（不吃触摸），避免重复触达已选渠道。
                if (idx >= 0 && idx == selectedIndex) {
                    pressedIndex = -1
                    return false
                }
                pressedIndex = idx
                return pressedIndex >= 0
            }
            MotionEvent.ACTION_UP -> {
                val index = indexAt(event.x)
                val hit = pressedIndex
                pressedIndex = -1
                if (index >= 0 && index == hit && index != selectedIndex) {
                    // @author bomo 2026-10-22 切换节流：过快点击（相对上次成功切换 < 1200ms）直接忽略，
                    // 不改高亮也不回调 —— 否则小窗没切、高亮却跳走，之后想切回目标项会被“当前项不可点”挡住。
                    val now = SystemClock.uptimeMillis()
                    if (now - lastSwitchAt < SWITCH_BLOCK_MS) return true
                    lastSwitchAt = now
                    selectedIndex = index
                    invalidate()
                    onSelected(items[index].channel)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedIndex = -1
                return true
            }
        }
        return true
    }

    private fun indexAt(x: Float): Int {
        if (itemWidthPx <= 0f) return -1
        val index = (x / itemWidthPx).toInt()
        return if (index in items.indices) index else -1
    }

    private companion object {
        // 注意：带 .toInt() 的表达式不是编译期常量，只能用 val 不能 const val。
        val BACKGROUND_COLOR = 0xFFFFFFFF.toInt()
        const val SELECTED_COLOR = 0x1F000000

        /** @author bomo 切换节流窗口：支付栏 1.2 秒内只响应一次切换（2026-10-22 用户 1.8→1.5→1.2s）。 */
        const val SWITCH_BLOCK_MS = 1200L

        /** 深色模式：深灰卡片底 + 白色 14% 选中圈（白底会晃眼）。 */
        val BACKGROUND_COLOR_DARK = 0xFF1F1F22.toInt()
        const val SELECTED_COLOR_DARK = 0x24FFFFFF
    }
}
