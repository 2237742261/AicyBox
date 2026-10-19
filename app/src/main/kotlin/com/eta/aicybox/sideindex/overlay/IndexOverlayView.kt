package com.eta.aicybox.sideindex.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.eta.aicybox.sideindex.config.ModuleConfig
import com.eta.aicybox.sideindex.config.Side
import com.eta.aicybox.sideindex.index.IndexGroup
import com.eta.aicybox.sideindex.ui.BarStyle
import com.eta.aicybox.sideindex.ui.IndexBarView
import com.eta.aicybox.sideindex.util.BlurredWallpaper
import com.eta.aicybox.sideindex.util.Display
import com.eta.aicybox.sideindex.util.Haptics
import com.eta.aicybox.sideindex.util.StarDraw
import com.eta.aicybox.sideindex.util.XLog
import kotlin.math.min

/**
 * 索引浮层（最终形态）：
 *   - 触发侧出现的字母条，位置跟随手指（手指永远在字母条中间，A→Z 只需滑半个条高）
 *   - 大号白色字母（无圆底）
 *   - 该字母的应用图标网格：位置跟随手指，横移过去直接点选
 *   - 全屏毛玻璃（窗口级模糊 + 半透明遮罩）
 */
/** 网格构建的最小间隔：特别快滑时先把字母条和气泡更新到位，网格等这一小段滑完再追上去 */
private const val GRID_MIN_INTERVAL_MS = 45L

/**
 * 换字母的迟滞系数：手指要离开当前字母的格心超过 step × 这个值，才允许换到相邻字母。
 * 0.5 就是命中区边界本身，多出来的 0.3 格是防抖余量 —— 往图标方向横移时 y 会轻微漂移，
 * 没有这道门槛就会在 M/N 这种相邻字母之间乱跳。
 */
private const val GROUP_SWITCH_HYSTERESIS = 0.8f

/** 遮罩色：黑色 40% 压暗（统一白字，不跟深浅模式联动）。 */
private const val SCRIM_COLOR = 0x66000000.toInt()

/**
 * 网格/气泡组距屏幕边缘的额外内缩（dp），叠在字母条宽度之外。
 *
 * 2026-10-16：用户反馈「慢慢上下滑选字母时手指会不小心落到应用图标上，松手就直接
 * 把那个应用打开了」—— 图标离字母条太近。这里 6dp → 14dp（+8dp）。
 * 左右两侧、横竖屏共用同一个值，保证镜像对称。
 *
 * ⚠️ 三处必须一起用这个常量，漏一处就会错位：
 * [gridMetrics] 的宽度预算、[alignCard] 的卡片边距、[placeBubbleGroup] 的 iconCx。
 */
private const val GRID_EDGE_INSET_DP = 14

class IndexOverlayView(context: Context) : FrameLayout(context) {

    var onLaunch: ((LaunchableApp) -> Unit)? = null

    /** 最底层：模糊过的桌面壁纸（壁纸本身没有图标，糊掉细节后正好只提供颜色与氛围） */
    private val bg: ImageView
    private val scrim: View
    private val card: LinearLayout
    private val rowArea: LinearLayout
    private val title: TextView
    private val bar: IndexBarView
    private val bubble: BubbleView
    private val nameLabel: TextView

    private var config = ModuleConfig()
    private var side: Side = Side.RIGHT
    private var apps: List<LaunchableApp> = emptyList()
    private var groups: List<IndexGroup> = emptyList()
    private var currentGroup = -1
    private var lastLetterChangeAt = 0L
    private var lastVibrateAt = 0L
    private var anchorY = -1f
    private var barTopY = 0f
    private var cardRevealed = false
    private var cardSideSet = false





    /** 兜底：万一 alignCard 一直算不出来（异常情况），别让网格永远不显示 */
    private val revealFallback = Runnable {
        if (!cardRevealed) {
            cardRevealed = true
            card.animate().cancel()
            card.alpha = 1f
        }
    }

    init {
        clipChildren = false
        clipToPadding = false

        bg = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            alpha = 0f
        }
        addView(bg, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        scrim = View(context).apply {
            setBackgroundColor(SCRIM_COLOR)  // 黑色遮罩（统一白字，不再跟深浅模式联动）
        }
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))


        card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            background = null
            clipChildren = false
            clipToPadding = false
        }
        title = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(0xCCFFFFFF.toInt())
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(6), 0, dp(6), 0)
        }
        rowArea = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        card.addView(rowArea, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(card, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        bubble = BubbleView(context)
        addView(bubble, LayoutParams(dp(84), dp(84)).apply { gravity = Gravity.TOP or Gravity.END })
        nameLabel = TextView(context).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); setTextColor(0xF2FFFFFF.toInt()); gravity = Gravity.END or Gravity.CENTER_VERTICAL; setPadding(dp(4), 0, dp(4), 0); maxWidth = (resources.displayMetrics.widthPixels * 0.5f).toInt(); ellipsize = android.text.TextUtils.TruncateAt.END; visibility = INVISIBLE; alpha = 0f }
        addView(title, LayoutParams(dp(84), LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.TOP or Gravity.END })
        addView(nameLabel, LayoutParams(LayoutParams.WRAP_CONTENT, dp(84)).apply { gravity = Gravity.TOP or Gravity.END })

        bar = IndexBarView(context).apply {
            isEnabled = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            fadeTo(0f)
        }
        addView(bar, LayoutParams(LayoutParams.WRAP_CONTENT, dp(600)).apply { gravity = Gravity.TOP or Gravity.END })
    }

    /**
     * 遮罩自愈（1.31.17）。
     *
     * 有群友反馈：全局模式下滑着滑着遮罩变透明了，之后整根手势都没遮罩。
     * 代码里没有任何路径会把 scrim 背景改成透明（只在 init 与 prepare() 两处赋值），
     * 所以只能是被系统回收了 RenderNode 背景 —— 跨窗口模糊 + 长时间动画叠加时偶发、不可复现。
     *
     * 手势过程中由 OverlayController.fixBackdropIfNeeded() 不定期比对一次，颜色不对就补回来
     * （节流见 OverlayController.BACKDROP_FIX_INTERVAL_MS）。
     * getBackgroundColor 是本地字段读取、不碰 binder，比手势路径上任何 ATMS 调用都便宜。
     *
     * @return 是否真的补了一次（仅供日志）
     */
    fun ensureBackdrop(): Boolean {
        val dr = scrim.background as? android.graphics.drawable.ColorDrawable
        if (dr?.color == SCRIM_COLOR) return false
        scrim.setBackgroundColor(SCRIM_COLOR)
        return true
    }

    fun prepare(
        apps: List<LaunchableApp>,
        groups: List<IndexGroup>,
        newConfig: ModuleConfig,
        fromRight: Boolean,
        landscape: Boolean
    ) {
        config = newConfig
        scrim.setBackgroundColor(SCRIM_COLOR)  // 每次呼出都把遮罩补回来（1.31.17：见 ensureBackdrop）
        landscapeMode = landscape
        side = if (fromRight) Side.RIGHT else Side.LEFT
        this.apps = apps
        // 分组没变就保留网格缓存：跨手势复用，呼出时直接挂载已有行，比每次重建快得多
        this.groups = groups
        cacheScreenSize()
        cardSideSet = false
        bubbleSideSet = false
        lastVisibleRows = -1
        currentGroup = -1
        anchorY = -1f

        styleBar()
        bar.submit(groups, -1)
        bar.fadeTo(0f)
        bubble.hideImmediately()
        hideNameLabelNow()
        scrim.animate().cancel()
        scrim.alpha = 0f
        if (config.immersive) {
            // 沉浸模式：背景 = 模糊桌面壁纸（心跳里已预热，呼出直接取现成的）
            // 背景/遮罩都从 0 开始，由 fadeInBackdrop() 淡入 —— 直接铺满太突兀
            setBackgroundBitmap(BlurredWallpaper.current())
            bg.animate().cancel()
            bg.alpha = 0f
        } else {
            // 非沉浸：壁纸不参与，浮层窗口自带 FLAG_BLUR_BEHIND，系统直接模糊背后内容
            bg.animate().cancel()
            bg.setImageBitmap(null)
            bg.alpha = 0f
        }
        card.animate().cancel()
        // 网格卡片的位置要等测量完（alignCard）才算得准。默认布局在左上角，
        // 先藏起来再淡入，否则第一帧会画在屏幕顶部 —— 就是"图标在顶上闪一下"。
        title.setTextColor(0xCCFFFFFF.toInt())
        removeCallbacks(revealFallback)
        post { cacheWindowOffset() }
        card.alpha = 0f
        cardRevealed = false
        postDelayed(revealFallback, 600L)
        card.visibility = VISIBLE
        title.text = ""
        // 收起时 title 会被淡出，下次呼出必须先恢复不透明（文本在网格绑定时才填）
        title.animate().cancel()
        title.alpha = 1f
    }

    private fun styleBar() {
        bar.style = BarStyle.from(context, config, side, maxBarTextSizePx())
        val lp = bar.layoutParams as LayoutParams
        lp.gravity = Gravity.TOP or (if (side == Side.RIGHT) Gravity.END else Gravity.START)
        lp.width = bar.style.viewWidthPx
        bar.layoutParams = lp
    }

    /**
     * 字母字号上限 = 「字母条高 38% 屏高」时的字号。
     * 触发高度继续调大时字号不再增长，多出来的高度只用来拉开字母之间的间隙。
     */
    private fun maxBarTextSizePx(): Float {
        val fixed = Display.sp(context, ModuleConfig.FIXED_BAR_TEXT_SIZE_SP)
        if (groups.isEmpty()) return fixed
        // 横屏：按 85% 屏高的条高自然取字号（≈竖屏触发高度 36% 时的字号），
        // 不套 38% 竖屏封顶 —— 那会把横屏的字缩得只有一半大
        val letterArea = if (isLandscapeNow()) {
            realScreenH() * 0.85f * 0.96f
        } else {
            realScreenH() * (ModuleConfig.FIXED_TEXT_CAP_BAR_PERCENT / 100f) * 0.96f
        }
        val step = letterArea / groups.size
        return min(fixed, step * 0.95f)
    }

    /**
     * 字母条固定贴在触发区位置（top 由宿主按触发区算好传入），不再以手指为圆心漂移，
     * 触发区和索引条永远重合。
     */
    /**
     * 屏幕尺寸：横屏/旋转后 displayMetrics 可能还是旧值，所以用 windowMetrics。
     * ⚠️ `currentWindowMetrics` 是**跨进程调用**，绝不能落在"每次换字母"的路径上：
     * 每个手势只在 prepare() 里取一次，之后换字母、对齐网格、摆气泡全读缓存。
     *（旧版 realScreenH() 是每次 alignCard 都调一次，快滑时等于每帧一次 binder）
     */
    private var screenWCache = 0
    private var screenHCache = 0
    /** 气泡组的最后一次落点：值没变就不重复赋值 layoutParams */
    private var lastBubbleTop = Int.MIN_VALUE
    private var lastBubbleMargin = Int.MIN_VALUE
    private var bubbleSideSet = false
    /** 上一次构建时可见行数：只有它变了才需要"布局后再校正一次" */
    private var lastVisibleRows = -1

    private fun cacheScreenSize() {
        try {
            val b = (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
                ?.currentWindowMetrics?.bounds
            if (b != null && b.width() > 0 && b.height() > 0) {
                screenWCache = b.width()
                screenHCache = b.height()
                return
            }
        } catch (t: Throwable) {
        }
        screenWCache = resources.displayMetrics.widthPixels
        screenHCache = resources.displayMetrics.heightPixels
    }

    private fun realScreenW(): Int {
        if (screenWCache <= 0) cacheScreenSize()
        return if (screenWCache > 0) screenWCache else resources.displayMetrics.widthPixels
    }

    private fun realScreenH(): Int {
        if (screenHCache <= 0) cacheScreenSize()
        return if (screenHCache > 0) screenHCache else resources.displayMetrics.heightPixels
    }

    /**
     * 横竖屏状态：由宿主（OverlayController）在呼出时判定好传进来。
     * ⚠️ 不在视图里自己判定 —— SystemUI 的 configuration 与 currentWindowMetrics 在旋转瞬间
     * 可能不同步，两处判定不一致就会出现"字母条按横屏画、气泡组按竖屏摆"的错位。
     */
    private var landscapeMode = false

    private fun isLandscapeNow(): Boolean = landscapeMode

    fun placeBarAt(top: Int) {
        val screenH = realScreenH()
        // 横屏独立参数（写死）：条高 = 横屏屏高 85%，且**自行居中** —— 伪横屏应用里宿主传来的
        // 感应带坐标可能仍是竖屏值（旋转同步延迟），照搬会把条顶到贴底；竖屏走配置并尊重用户设置的垂直位置
        val landscape = isLandscapeNow()
        val hPct = if (landscape) ModuleConfig.FIXED_LANDSCAPE_BAR_PERCENT else config.barHeightPercent
        val barH = (screenH * hPct / 100).coerceAtLeast(dp(200))
        val t = if (landscape) ((screenH - barH) / 2).coerceAtLeast(0)
                else top.coerceIn(0, (screenH - barH).coerceAtLeast(0))
        barTopY = t.toFloat()
        val lp = bar.layoutParams as LayoutParams
        lp.height = barH
        lp.topMargin = t
        lp.bottomMargin = 0
        bar.layoutParams = lp

        // 气泡 / 「N 个应用」/ 应用名的位置**不在这里定**：它们跟着网格卡片走
        //（selectByY → alignCard → placeBubbleGroup），这里只摆字母条本身。
        // 之前这里还有一整套 t-98dp / t-30dp 的定位，随即就被 placeBubbleGroup 覆盖 ——
        // 两套常量（98/30 与 122/68/75）各写一遍，纯粹是历史包袱
    }

    fun fadeInBar() {
        val gap = System.currentTimeMillis() - lastLetterChangeAt
        // 淡入尽量短：长淡入本身就会被感知成"呼出慢"
        bar.fadeTo(1f, if (gap in 1..160) 100L else 160L)
        bubble.show()
    }

    /** 背景（模糊壁纸 / 系统窗口模糊）+ 黑色遮罩：150ms 淡入，别"啪一下铺满" */
    fun fadeInBackdrop() {
        scrim.animate().cancel()
        scrim.animate().alpha(1f).setDuration(320L).setInterpolator(DecelerateInterpolator(1.5f)).start()
        bg.animate().cancel()
        bg.animate().alpha(1f).setDuration(320L).setInterpolator(DecelerateInterpolator(1.5f)).start()
    }

    /** 立即隐藏（无动画）：浮层窗口常驻后由宿主在需要时直接调用 */
    fun hideImmediately() {
        removeCallbacks(revealFallback)
        scrim.animate().cancel()
        scrim.alpha = 0f
        bg.animate().cancel()
        bg.alpha = 0f
        bar.fadeTo(0f, 0L)
        bubble.hideImmediately()
        hideNameLabelNow()
        title.animate().cancel()
        title.alpha = 0f
        card.animate().cancel()
        card.alpha = 0f
    }

    fun dismissAnimated() {
        // 松手后要"立马消失"：淡出压到 60~70ms，别再让用户等 200ms
        // 收起走干脆路线：背景/遮罩 100ms 最先消失（模糊不拖尾），内容 120ms 跟上
        bar.fadeTo(0f, 120L)
        bubble.hide()
        hideNameLabelNow()
        // ⚠️「N 个应用」原来不在收起动画里：别的元素 80~120ms 淡完，它一直保持不透明，
        // 得等窗口整体 INVISIBLE（150ms 后）才跟着消失，看起来就是"它比别人慢一拍"。
        // 跟内容同步 120ms 淡出。
        title.animate().cancel()
        title.animate().alpha(0f).setDuration(120L).start()
        scrim.animate().alpha(0f).setDuration(100L).start()
        bg.animate().cancel()
        bg.animate().alpha(0f).setDuration(100L).start()
        card.animate().alpha(0f).setDuration(120L).withEndAction { card.visibility = INVISIBLE }.start()
    }

    fun setAnchorY(y: Float) { anchorY = y }

    /** 窗口在屏幕上的偏移：布局完成后缓存一次（原来每个格子都跨进程查一次位置，每帧 20 次 binder） */
    private var windowOffsetX = 0
    private var windowOffsetY = 0

    private fun cacheWindowOffset() {
        try {
            val loc = IntArray(2)
            card.getLocationOnScreen(loc)
            windowOffsetX = loc[0] - card.left
            windowOffsetY = loc[1] - card.top - card.translationY.toInt()
        } catch (t: Throwable) {
        }
    }

    fun appAt(x: Float, y: Float): LaunchableApp? {
        val baseX = windowOffsetX + card.left
        val baseY = windowOffsetY + card.top + card.translationY.toInt()
        for (r in 0 until rowArea.childCount) {
            val row = rowArea.getChildAt(r) as? LinearLayout ?: continue
            val rowX = baseX + rowArea.left + row.left
            val rowY = baseY + rowArea.top + row.top
            for (c in 0 until row.childCount) {
                val tile = row.getChildAt(c) as? LinearLayout ?: continue
                val tx = rowX + tile.left
                val ty = rowY + tile.top
                if (x >= tx && x <= tx + tile.width && y >= ty && y <= ty + tile.height) {
                    return tile.tag as? LaunchableApp
                }
            }
        }
        return null
    }

    private fun updateNameLabel(app: LaunchableApp?) {
        if (app == null) { hideNameLabelNow(); return }
        if (nameLabel.visibility == VISIBLE && nameLabel.text.toString() == app.label) return
        nameLabel.animate().cancel()
        nameLabel.text = app.label
        nameLabel.visibility = VISIBLE
        nameLabel.alpha = 0f
        nameLabel.animate().alpha(1f).setDuration(80L).start()
    }

    private fun hideNameLabelNow() {
        nameLabel.animate().cancel()
        nameLabel.alpha = 0f
        nameLabel.visibility = INVISIBLE
    }

    fun setHover(app: LaunchableApp?) {
        updateNameLabel(app)
        forEachTile { tile, tagged ->
            val hit = app != null && tagged === app
            val targetY = if (hit) -dp(5).toFloat() else 0f
            val targetScale = if (hit) 1.12f else 1f
            if (tile.translationY != targetY || tile.scaleX != targetScale) {
                tile.animate().cancel()
                tile.animate().translationY(targetY).scaleX(targetScale).scaleY(targetScale)
                    .setDuration(110L).setInterpolator(DecelerateInterpolator()).start()
            }
        }
    }

    fun selectByY(screenY: Float): Int {
        if (groups.isEmpty()) return -1
        val localY = screenY - barTopY
        var index = bar.indexAtY(localY)
        if (index < 0 || index >= groups.size) return -1
        // 迟滞（施密特触发）：手指还赖在当前字母的格心附近时，不因为 y 抖一点就换字母。
        // 换字母要真的滑出去 0.8 格；回头也要退到 0.8 格以内 —— 快滑不受影响（一格早就越过去了）。
        val cur = currentGroup
        if (cur in groups.indices && index != cur) {
            val step = bar.stepPx()
            if (step > 0f && kotlin.math.abs(localY - bar.letterCenterY(cur)) < step * GROUP_SWITCH_HYSTERESIS) {
                index = cur
            }
        }
        if (index == currentGroup) return index
        lastLetterChangeAt = System.currentTimeMillis()
        currentGroup = index
        bar.setCurrentIndex(index)
        bubble.setLetter(groups[index].label)
        bubble.pulse()
        // 气泡先按字母位置落一下（快滑时气泡要跟着手指），网格构建完再按卡片实际位置校正
        placeBubbleGroup(barTopY + bar.letterCenterY(index))
        scheduleGrid(index)
        vibrate()
        return index
    }

    fun barWidthPx(): Int = bar.style.viewWidthPx

    /** 字母条在屏幕上的实际位置（感应带要与它严格重合） */
    fun barScreenRect(): Rect = try {
        val loc = IntArray(2)
        bar.getLocationOnScreen(loc)
        Rect(loc[0], loc[1], loc[0] + bar.width, loc[1] + bar.height)
    } catch (t: Throwable) {
        Rect()
    }

    /** 模糊壁纸生成完成后由宿主回调进来（主线程） */
    fun setBackgroundBitmap(bitmap: Bitmap?) {
        try {
            bg.setImageBitmap(bitmap)
        } catch (t: Throwable) {
            XLog.d("设置模糊背景失败：${t.javaClass.simpleName}")
        }
    }

    /** 每行图标数 × 格子宽度（图标 + 固定 5.5dp 间距）；格子宽被屏幕宽度兜住，绝不溢出 */
    private fun gridMetrics(): Pair<Int, Int> {
        val icon = dp(ModuleConfig.FIXED_ICON_SIZE_DP)
        val columns = config.gridColumns.coerceIn(4, 5)
        val gapPx = (ModuleConfig.FIXED_GRID_GAP_DP * resources.displayMetrics.density + 0.5f).toInt()
        val reserved = bar.style.viewWidthPx + dp(GRID_EDGE_INSET_DP) + dp(16)
        val available = (realScreenW() - reserved).coerceAtLeast(icon * 3)
        val pitch = (icon + gapPx).coerceAtMost(available / columns)
        return columns to pitch
    }

    private fun forEachTile(block: (View, LaunchableApp?) -> Unit) {
        for (r in 0 until rowArea.childCount) {
            val row = rowArea.getChildAt(r) as? LinearLayout ?: continue
            for (c in 0 until row.childCount) {
                val tile = row.getChildAt(c)
                block(tile, tile.tag as? LaunchableApp)
            }
        }
    }

    /** 网格缓存：每组只构建一次，切字母只是挂/摘现成的行——这是滑动跟手的关键 */
    /** 网格复用池：行与图标控件只创建一次，换组只更新内容与可见性。
     *  以前每次换字母都 removeAllViews + addView，会触发整卡重新布局，是快速滑动时网格滞后的主因 */
    private var gridColumns = 0
    /** 当前组的应用数：横屏气泡组贴实际图标边缘时用 */
    private var lastItemCount = 0
    private var lastGridBuildAt = 0L
    private var pendingGridIndex = -1

    private val pendingGridRunnable = Runnable {
        val idx = pendingGridIndex
        pendingGridIndex = -1
        if (idx >= 0) buildGrid(idx)
    }

    /** 手指要挑图标了：把还没追上的网格立刻补上，绝不能让它点到上一组的内容 */
    fun flushPendingGrid() {
        val idx = pendingGridIndex
        if (idx < 0) return
        pendingGridIndex = -1
        removeCallbacks(pendingGridRunnable)
        buildGrid(idx)
    }

    /**
     * 把「大气泡字母 + N 个应用 + 应用名」整组摆好：水平对准呼出侧那一列图标（居中、间隔 1 个汉字），
     * 垂直挂在 anchorY 上方。anchorY = **网格第一行图标的中心**，不是字母格心 ——
     * 卡片被屏幕底边夹住时（多行 + 靠下的字母）两者会差出一整行，气泡就会压在图标上。
     */
    private fun placeBubbleGroup(anchorY: Float) {
        val screenW = realScreenW()
        val (columns, pitch) = gridMetrics()
        val barW = bar.style.viewWidthPx
        // 呼出侧那一列的图标中心：右侧 = 最右列，左侧 = 最左列（整体镜像）
        val nearEnd = side == Side.RIGHT
        val iconCx = if (nearEnd) screenW - barW - dp(GRID_EDGE_INSET_DP) - pitch / 2f else barW + dp(GRID_EDGE_INSET_DP) + pitch / 2f
        // 横屏布局：整组（气泡/标题/应用名）放网格首行旁边（间隔 4dp），垂直居中于首行
        val bubbleMargin: Int
        val bubbleTop: Int
        if (isLandscapeNow()) {
            // 应用少时图标聚在行的一侧（RTL 靠右），按"网格边缘"定位会留出大空隙 ——
            // 改为贴着**实际显示的图标**边缘（可见数量 n = min(items, 列数)）
            val n = lastItemCount.coerceAtMost(columns)
            val gridEdge = if (nearEnd) iconCx - pitch / 2f - (columns - 1) * pitch   // 网格左缘
                           else iconCx + pitch / 2f + (columns - 1) * pitch           // 网格右缘
            val contentEdge = if (nearEnd) gridEdge + (columns - n) * pitch            // 实际图标左缘
                              else gridEdge - (columns - n) * pitch                    // 实际图标右缘
            bubbleMargin = if (nearEnd) (screenW - contentEdge + dp(4)).toInt()
                           else (contentEdge + dp(4)).toInt()
            bubbleTop = (anchorY - dp(42)).toInt().coerceAtLeast(dp(6))
        } else {
            // 竖屏布局：气泡中心对到呼出侧那一列图标中心上，整组在字母上方
            //（1.24.1：整组上移 2dp，三者相对位置不动，左右侧呼出共用这里）
            bubbleMargin = if (nearEnd) (screenW - iconCx - dp(42)).toInt() else (iconCx - dp(42)).toInt()
            bubbleTop = (anchorY - dp(26) - dp(14) - dp(84)).toInt().coerceAtLeast(dp(6))
        }
        // 落点没变就别再赋值 layoutParams：换字母时这里会被调用两次
        //（selectByY 给即时位置、alignCard 用卡片实际位置校正），相同值重复赋值 = 白跑一次 requestLayout
        // 日志放到判空之后：这里每个 MOVE 都会进来一次，位置没变就不该刷日志
        if (bubbleSideSet && bubbleTop == lastBubbleTop && bubbleMargin == lastBubbleMargin) return
        XLog.d("气泡组：横屏=$landscapeMode 屏宽=$screenW 列=$columns pitch=$pitch 锚点=${anchorY.toInt()} 边距=$bubbleMargin 顶=$bubbleTop")
        bubbleSideSet = true
        lastBubbleTop = bubbleTop
        lastBubbleMargin = bubbleMargin
        val blp = bubble.layoutParams as LayoutParams
        blp.gravity = Gravity.TOP or (if (nearEnd) Gravity.END else Gravity.START)
        blp.topMargin = bubbleTop
        blp.rightMargin = if (nearEnd) bubbleMargin else 0
        blp.leftMargin = if (nearEnd) 0 else bubbleMargin
        bubble.layoutParams = blp
        val nlp = nameLabel.layoutParams as LayoutParams
        nlp.gravity = Gravity.TOP or (if (nearEnd) Gravity.END else Gravity.START)
        nlp.topMargin = bubbleTop
        nlp.height = dp(84)
        // 应用名与气泡的间距：77dp（= 气泡宽 84 - 7；支持小数故直接按像素算）
        val nameGap = (77f * resources.displayMetrics.density + 0.5f).toInt()
        nlp.rightMargin = if (nearEnd) bubbleMargin + nameGap else 0
        nlp.leftMargin = if (nearEnd) 0 else bubbleMargin + nameGap
        nameLabel.layoutParams = nlp
        (title.layoutParams as? LayoutParams)?.let { tlp ->
            tlp.gravity = Gravity.TOP or (if (nearEnd) Gravity.END else Gravity.START)
            tlp.topMargin = bubbleTop + dp(68)
            tlp.width = dp(84)
            tlp.rightMargin = if (nearEnd) bubbleMargin else 0
            tlp.leftMargin = if (nearEnd) 0 else bubbleMargin
            title.layoutParams = tlp
        }
    }

    private fun scheduleGrid(index: Int) {
        val now = System.currentTimeMillis()
        val since = now - lastGridBuildAt
        if (since >= GRID_MIN_INTERVAL_MS) {
            pendingGridIndex = -1
            removeCallbacks(pendingGridRunnable)
            buildGrid(index)
        } else {
            pendingGridIndex = index
            removeCallbacks(pendingGridRunnable)
            postDelayed(pendingGridRunnable, GRID_MIN_INTERVAL_MS - since)
        }
    }

    private fun buildGrid(groupIndex: Int) {
        lastGridBuildAt = System.currentTimeMillis()
        if (groupIndex !in groups.indices) return
        val group = groups[groupIndex]
        val items = apps.subList(group.start, (group.end + 1).coerceAtMost(apps.size))
        lastItemCount = items.size
        val (columns, pitch) = gridMetrics()
        ensureGridShape(items.size, columns, pitch)

        var i = 0
        for (r in 0 until rowArea.childCount) {
            val row = rowArea.getChildAt(r) as LinearLayout
            for (c in 0 until row.childCount) {
                val tile = row.getChildAt(c) as? LinearLayout ?: continue
                if (i < items.size) {
                    bindTile(tile, items[i])
                    tile.visibility = VISIBLE
                    i++
                } else {
                    tile.tag = null
                    tile.visibility = INVISIBLE
                }
            }
        }
        val titleText = "${items.size} 个应用"
        if (title.text.toString() != titleText) title.text = titleText
        // 位置由 alignCard() 负责；行是复用的（已测量），多数情况能同步对齐
        if (config.logVerbose) logGridLayout(groupIndex)
        val visibleRows = (0 until rowArea.childCount).count { rowArea.getChildAt(it).visibility == VISIBLE }
        alignCard()
        // 行数变了才会让卡片高度变，需要在布局完成后再用实测高度修正一次（否则"最后一行被吞"）；
        // 行数没变时卡片高度也没变，再跑一遍只是白费一次布局遍历
        if (visibleRows != lastVisibleRows) {
            lastVisibleRows = visibleRows
            card.post { alignCard() }
        }
        // 行数变化会让卡片高度变，等布局完成后再用实测高度修正一次
        //（否则"最后一行超出屏幕被吞"：同步对齐时 card.height 还是旧行数的高度）
    }

    /** 行列不够才创建、多了只隐藏（不 remove，避免反复触发布局） */
    private fun ensureGridShape(itemCount: Int, columns: Int, pitch: Int) {
        if (gridColumns != columns) {
            rowArea.removeAllViews()
            gridColumns = columns
        }
        val rowsNeeded = ((itemCount + columns - 1) / columns).coerceAtLeast(1)
        while (rowArea.childCount < rowsNeeded) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutDirection = if (side == Side.RIGHT) LAYOUT_DIRECTION_RTL else LAYOUT_DIRECTION_LTR
                clipChildren = false
                clipToPadding = false
            }
            for (c in 0 until columns) {
                row.addView(buildTile(), LinearLayout.LayoutParams(pitch, LinearLayout.LayoutParams.WRAP_CONTENT))
            }
            rowArea.addView(
                row,
                LinearLayout.LayoutParams(pitch * columns, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    // 行间留 4dp（1.20.4 起由 6dp 收紧）：悬停上浮 5dp 时不会压到上一排
                    if (rowArea.childCount > 0) topMargin = dp(4)
                }
            )
        }
        val dir = if (side == Side.RIGHT) LAYOUT_DIRECTION_RTL else LAYOUT_DIRECTION_LTR
        for (r in 0 until rowArea.childCount) {
            val row = rowArea.getChildAt(r)
            // 复用池里的行是上一次呼出留下的：左右切换时必须把行方向一起改掉，
            // 否则右侧呼出会沿用左侧的 LTR（列表第一个跑到最左边）
            if (row.layoutDirection != dir) row.layoutDirection = dir
            row.visibility = if (r < rowsNeeded) VISIBLE else INVISIBLE
        }
    }

    /**
     * 把网格第一行图标的垂直中心对齐到当前高亮字母的格心。
     * 之前这里漏算了卡片内边距与标题高度，图标整体比字母低了大半行。
     */
    private fun alignCard(): Boolean {
        val firstRow = rowArea.getChildAt(0) as? LinearLayout ?: return false
        val firstTile = firstRow.getChildAt(0) as? LinearLayout ?: return false
        val firstIcon = firstTile.getChildAt(0) ?: return false
        if (firstIcon.height <= 0 || card.height <= 0) return false
        // rowArea.top 相对卡片、已含卡片内边距和标题高度，再往下累计格子内偏移
        val iconCenterInCard = rowArea.top + firstTile.top + firstIcon.top + firstIcon.height / 2f
        val target = if (currentGroup in groups.indices) {
            barTopY + bar.letterCenterY(currentGroup)
        } else {
            anchorY
        }
        // 真实屏幕高度（横屏旋转后 displayMetrics 会是旧值，导致卡片被屏幕底边裁掉"吞"掉一行）
        val screenH = realScreenH()
        // ⚠️ 这里的高度**不能**用 card.height：网格是复用池，换到小分组时"多余的行"只是 INVISIBLE，
        // 仍然占着布局高度 → maxTop 偏小 → 卡片被过早顶到上面，表现就是
        // "索引停在很靠下的字母时，气泡/「N 个应用」和图标叠在一起，网格下方空出两行身位"。
        // 只累计当前可见行的高度，卡片才能老老实实跟着字母走。
        var visibleRowsHeight = 0
        for (i in 0 until rowArea.childCount) {
            val row = rowArea.getChildAt(i)
            if (row.visibility != VISIBLE) continue
            val lp = row.layoutParams as? LinearLayout.LayoutParams
            visibleRowsHeight += row.height + (lp?.topMargin ?: 0)
        }
        val visibleCardHeight =
            if (visibleRowsHeight > 0) rowArea.top + visibleRowsHeight + card.paddingBottom else card.height
        val maxTop = (screenH - visibleCardHeight - dp(12)).coerceAtLeast(dp(12))
        // 横屏：气泡组在网格旁边（不在卡片上方），不需要给气泡留顶部空间 ——
        // 否则首行会被强制下移 100+dp，与字母格心错开一截（用户实测"网格上方空隙大"）。
        // 竖屏：气泡在字母上方，底部空间不够时不能只顾着把卡片往上顶
        val lower = if (isLandscapeNow()) dp(12)
                    else (dp(6) + dp(122) - iconCenterInCard.toInt()).coerceAtMost(maxTop)
        val top = (target - iconCenterInCard).toInt().coerceIn(lower, maxTop)
        // 左右边距只在第一次设置；上下定位改用 translationY（不触发整卡 requestLayout，大跨度滑动才跟得上）
        if (!cardSideSet) {
            val offset = bar.style.viewWidthPx + dp(GRID_EDGE_INSET_DP)
            (card.layoutParams as LayoutParams).let { lp ->
                lp.gravity = Gravity.TOP or (if (side == Side.RIGHT) Gravity.END else Gravity.START)
                lp.setMargins(
                    if (side == Side.RIGHT) dp(8) else offset,
                    0,
                    if (side == Side.RIGHT) offset else dp(8),
                    0
                )
                card.layoutParams = lp
            }
            cardSideSet = true
        }
        card.translationY = top.toFloat()
        // 气泡组跟着卡片的实际落点走：卡片被屏幕底边夹住时，按字母格心摆的气泡会错开一整行
        placeBubbleGroup(top + iconCenterInCard)
        // 窗口位置固定：只在呼出时取一次（以前每次换字母都要跨进程取两次）
        if (!cardRevealed) {
            cardRevealed = true
            removeCallbacks(revealFallback)
            card.animate().alpha(1f).setDuration(160L).start()
        }
        return true
    }

    /** 创建一个空的图标格（内容由 bindTile 填），只在需要时创建一次 */
    private fun buildTile(): LinearLayout {
        val tile = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        val iconSize = dp(ModuleConfig.FIXED_ICON_SIZE_DP)
        val icon = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        tile.addView(icon, LinearLayout.LayoutParams(iconSize, iconSize))
        return tile
    }

    /** 复用格子：只更新内容（图标/tag）与悬停状态，不重建视图 */
    private fun bindTile(tile: LinearLayout, app: LaunchableApp) {
        tile.tag = app
        tile.translationY = 0f
        tile.scaleX = 1f
        tile.scaleY = 1f
        val icon = tile.getChildAt(0) as? ImageView
        val bitmap = AppRepository.iconBitmap(context, app, dp(ModuleConfig.FIXED_ICON_SIZE_DP))
        if (icon != null) {
            if (bitmap != null) icon.setImageBitmap(bitmap) else icon.setImageDrawable(null)
        }
    }

    private fun vibrate() {
        if (!config.vibrateOn()) return
        val now = System.currentTimeMillis()
        if (now - lastVibrateAt < 30) return
        lastVibrateAt = now
        Haptics.tick(context, config.vibrateStrength, null)
    }

    private fun logGridLayout(groupIndex: Int) { postDelayed({ try { val r = rowArea.getChildAt(0) as? LinearLayout; val t = r?.getChildAt(0); if (t != null) { val l = IntArray(2); t.getLocationOnScreen(l); val rows = (0 until rowArea.childCount).count { rowArea.getChildAt(it).visibility == VISIBLE }; XLog.d("网格 组=${groups.getOrNull(groupIndex)?.label ?: "-"} 可见行=$rows items[0] x=${l[0]} y=${l[1]} 卡片top=${card.top} 卡片翻译Y=${card.translationY.toInt()} 屏宽=${realScreenW()} 屏高=${realScreenH()} 应用=${(t.tag as? LaunchableApp)?.label ?: "-"}") } } catch (th: Throwable) {} }, 150L) }
    private fun dp(value: Int): Int = Display.dp(context, value)

    /** 大号白色字母（无圆底，带一层偏移投影） */
    private inner class BubbleView(context: Context) : View(context) {
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        private var letter = ""

        init {
            alpha = 0f
            scaleX = 0.88f
            scaleY = 0.88f
        }

        fun setLetter(value: String) { letter = value; invalidate() }

        fun show() {
            animate().cancel()
            animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(150L).start()
        }

        fun hide() {
            animate().cancel()
            animate().alpha(0f).scaleX(0.9f).scaleY(0.9f).setDuration(80L).start()
        }

        fun hideImmediately() {
            animate().cancel()
            alpha = 0f
            scaleX = 0.88f
            scaleY = 0.88f
        }

        fun pulse() {
            val fast = System.currentTimeMillis() - lastLetterChangeAt < 160
            alpha = 1f
            animate().cancel()
            scaleX = if (fast) 0.95f else 0.9f
            scaleY = scaleX
            animate().scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(if (fast) 90L else 150L)
                .setInterpolator(OvershootInterpolator(1.3f))
                .start()
        }


        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (letter.isEmpty()) return
            val cx = width / 2f
            val cy = height / 2f
            text.textSize = width * 0.62f + dp(6)  // 1.30.11：比例字号基础上再 +4dp
            if (StarDraw.isStar(letter)) { StarDraw.draw(canvas, cx, cy, text.textSize * 0.5f, Color.WHITE); return }
            val baseline = cy - (text.descent() + text.ascent()) / 2f
            // 统一白字（背景是黑色遮罩），带一层投影保证可读
            text.color = Color.WHITE
            canvas.drawText(letter, cx, baseline, text)
        }
    }
}
