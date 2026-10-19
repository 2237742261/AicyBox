package com.eta.aicybox.bubble

import android.app.Activity
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.eta.aicybox.config.ModulePreferences

/**
 * @author bomo 气泡位置调节编辑器（欧泡式）。
 *
 * 透明全屏 Activity：显示一个**可拖动的圆形气泡** + **底部的按钮条**：
 * [重置 | 取消 | 确认]（确认在最右边）。
 * - 拖动气泡 → 实时移动
 * - 重置 → 回到默认位置（16dp / 96dp）
 * - 取消 → 关闭编辑器，不保存
 * - 确认 → 把当前位置 [setBubblePos] 写回 DataStore（经配置推送生效），关闭编辑器
 *
 * 由设置页「调节气泡位置」入口启动。
 */
class BubblePosEditActivity : Activity() {

    private lateinit var bubbleView: View
    private lateinit var frame: FrameLayout
    private val wm by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private val density by lazy { resources.displayMetrics.density }

    // 拖动状态
    private var downX = 0f
    private var downY = 0f
    private var bubbleRawX = 0
    private var bubbleRawY = 0

    // 当前气泡在屏幕上的像素位置（相对左上）
    private var posXpx = 0
    private var posYpx = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        frame = FrameLayout(this)
        setContentView(frame)

        // 从当前配置读取初始位置
        val repository = (application as com.eta.aicybox.FlymeFreeformApplication).frameworkConnectionRepository
        val startXDp = repository.state.value.settings.bubblePosXDp
        val startYDp = repository.state.value.settings.bubblePosYDp
        posXpx = (startXDp * density).toInt()
        // bubblePosY 是距屏幕底部；FrameLayout 从上往下，需转成距顶部
        posYpx = ((screenH - startYDp * density) - bubbleSizePx).toInt().coerceAtLeast(0)

        createBubble()
        createTopHint()
        createBottomButtons()
    }

    private val bubbleSizePx: Int
        // 2026-10-18 与 SystemUI 实际气泡尺寸保持一致（SystemUiBubbleMonitor.BUBBLE_SIZE_DP=46dp），
        // 之前用 64dp 导致"调节时的演示气泡比实际气泡大"，用户反馈不一致。
        get() = (46 * density).toInt()

    /** 可拖动的圆形气泡 */
    private fun createBubble() {
        bubbleView = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(0xE8, 0x22, 0x16, 0xA7))
            }
        }
        val lp = FrameLayout.LayoutParams(bubbleSizePx, bubbleSizePx)
        lp.leftMargin = posXpx
        lp.topMargin = posYpx
        frame.addView(bubbleView, lp)
        bubbleView.setOnTouchListener { _, event -> onBubbleTouch(event) }
    }

    /** 底部的"拖动气泡到想要的位置，然后点确认"提示（2026-10-18 按钮移到顶端后，提示改放底部）。 */
    private fun createTopHint() {
        val hint = TextView(this).apply {
            text = "拖动气泡到想要的位置，然后点确认"
            textSize = 14f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.argb(0xCC, 0x26, 0x26, 0x26))
                cornerRadius = 8 * density
                setPadding(16, 10, 16, 10)
            }
        }
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        lp.bottomMargin = (140 * density).toInt()
        frame.addView(hint, lp)
    }

    /** 顶部按钮条：[重置 | 取消 | 确认]，确认在最右（2026-10-18 用户要求放屏幕顶端，不放底部）。 */
    private fun createBottomButtons() {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.argb(0xEE, 0x26, 0x26, 0x26))
                cornerRadius = 24 * density
            }
            setPadding(8, 6, 8, 6)
        }
        bar.addView(button("重置") { resetPos() })
        bar.addView(button("取消") { cancelEdit() })
        bar.addView(button("确认") { confirmEdit() })

        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        lp.topMargin = (72 * density).toInt()
        frame.addView(bar, lp)
    }

    private fun button(label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 16f
            setTextColor(if (label == "确认") Color.rgb(64, 180, 255) else Color.WHITE)
            setPadding((28 * density).toInt(), (12 * density).toInt(), (28 * density).toInt(), (12 * density).toInt())
            isClickable = true
            setOnClickListener { onClick() }
        }

    /** 拖动气泡 */
    private fun onBubbleTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                bubbleRawX = posXpx
                bubbleRawY = posYpx
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                posXpx = (bubbleRawX + (event.rawX - downX)).toInt().coerceIn(0, screenW - bubbleSizePx)
                posYpx = (bubbleRawY + (event.rawY - downY)).toInt().coerceIn(0, screenH - bubbleSizePx)
                updateBubblePosition()
                return true
            }
        }
        return false
    }

    private fun updateBubblePosition() {
        val lp = bubbleView.layoutParams as FrameLayout.LayoutParams
        lp.leftMargin = posXpx
        lp.topMargin = posYpx
        bubbleView.layoutParams = lp
    }

    private val screenW: Int
        get() = resources.displayMetrics.widthPixels
    private val screenH: Int
        get() = resources.displayMetrics.heightPixels

    private fun resetPos() {
        posXpx = (ModulePreferences.DEFAULT_BUBBLE_POS_X_DP * density).toInt()
        posYpx = (screenH - ModulePreferences.DEFAULT_BUBBLE_POS_Y_DP * density - bubbleSizePx).toInt().coerceAtLeast(0)
        updateBubblePosition()
    }

    private fun cancelEdit() {
        finish()
    }

    private fun confirmEdit() {
        val xDp = (posXpx / density).toInt().coerceIn(0, 800)
        // 距底部 = screenH(px) - 气泡顶部 - 气泡尺寸
        val yDp = (((screenH - posYpx - bubbleSizePx) / density).toInt()).coerceIn(0, 800)
        val repository = (application as com.eta.aicybox.FlymeFreeformApplication).frameworkConnectionRepository
        repository.setBubblePos(xDp, yDp)
        finish()
    }
}