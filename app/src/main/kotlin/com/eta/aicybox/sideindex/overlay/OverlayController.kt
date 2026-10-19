package com.eta.aicybox.sideindex.overlay

import android.content.BroadcastReceiver
import android.content.ComponentCallbacks
import android.content.Context
import android.database.ContentObserver
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.eta.aicybox.sideindex.Const
import com.eta.aicybox.sideindex.config.ConfigReader
import com.eta.aicybox.sideindex.config.ModuleConfig
import com.eta.aicybox.sideindex.config.SecureConfigChannel
import com.eta.aicybox.sideindex.config.Side
import com.eta.aicybox.sideindex.util.BlurredWallpaper
import com.eta.aicybox.sideindex.util.Display
import com.eta.aicybox.sideindex.util.Haptics
import com.eta.aicybox.sideindex.util.XLog
import kotlin.math.abs

/**
 * 全局索引浮层控制器（运行在注入的 SystemUI 进程里）。
 *
 * 关键设计：
 *   - **自愈**：每 TICK 毫秒检查一次「该挂感应带」与「实际有没有挂」，
 *     不一致就重建。这样即使某次挂载失败或被系统回收，也会自动恢复，不需要重启手机。
 *   - **横屏**：Configuration + 窗口宽高双校验（不依赖"自动旋转"开关），旋转即时响应。
 *   - **应用范围**：白名单/黑名单按当前前台应用判定，不在范围内时手势直接放行给系统。
 *   - **字母条跟随手指**：手指落在字母条中间，A→Z 只需滑半个条高。
 */
class OverlayController(
    private val context: Context,
    private val windowManager: WindowManager,
    private val configProvider: () -> ModuleConfig
) {

    /** PASS = 这次手势已判定为"横滑让给桌面"，正在把手指镜像注入给桌面翻页 */
    private enum class State { IDLE, PENDING, ACTIVE, PASS }

    private val handler = Handler(Looper.getMainLooper())
    private val strips = ArrayList<Strip>()

    private var config = ModuleConfig()
    private var running = false

    private var overlay: IndexOverlayView? = null
    private var overlayVisible = false
    private var fromRight = true
    private var state = State.IDLE

    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var migrated = false
    private var hovered: LaunchableApp? = null
    private var activateRetries = 0
    /** 最近一次收到触摸事件的时刻 + 本次手势的事件数：给"事件流断了"的兜底自愈用 */
    private var lastEventAt = 0L
    private var gestureEventCount = 0
    /** 本次手势里 MOVE 的最大位移：诊断"呼不出来"时用来判断事件到底有没有送到 */
    private var gestureMaxDy = 0f
    private var gestureMaxDx = 0f
    /** root 注入是否可用（null=还没试过）：不可用就不要再反复调 su，免得每次都弹授权 */
    @Volatile private var suAvailable: Boolean? = null
    private var screenW = 0
    private var screenH = 0
    /** 屏幕尺寸缓存：windowMetrics 是 binder 调用，心跳里每 1.2 秒取两次太浪费（还会撞上手势） */
    private var boundsCache: Rect? = null

    /** 横竖切换时间戳：横屏进入后的前 3 秒不响应手势（旋转期间坐标/布局不稳，实测感应带会被挂到旧方向） */
    @Volatile private var landscapeSince = 0L
    @Volatile private var lastLandscapeFlag = false

    /** 检测横竖切换并记录进入横屏的时刻（心跳 + Configuration 回调都会调） */
    private fun refreshLandscapeGate() {
        val land = try {
            Display.isLandscape(context, windowManager)
        } catch (t: Throwable) {
            false
        }
        if (land != lastLandscapeFlag) {
            lastLandscapeFlag = land
            landscapeSince = if (land) System.currentTimeMillis() else 0L
            XLog.d("横竖切换：landscape=$land")
        }
    }

    private var lastRotation = -1

    private fun screenBounds(): Rect {
        // ⚠️ 伪横屏应用里 display 旋转可能晚于 Configuration 回调：只按"config 变化"失效缓存，
        // 会把竖屏 bounds 缓存住（感应带挂错位置、与字母条错开且不再纠正）。
        // 这里同时比对 display rotation，再靠心跳定期强制失效兜底
        val rot = try {
            (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay?.rotation ?: -1
        } catch (t: Throwable) {
            -1
        }
        boundsCache?.let { if (rot == lastRotation) return it }
        val rect = Display.screenBounds(windowManager, context)
        boundsCache = rect
        lastRotation = rot
        return rect
    }

    private var tickCount = 0

    private data class Strip(
        val view: View,
        val isRight: Boolean,
        var width: Int,
        var height: Int,
        var x: Int,
        var y: Int
    )

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            refreshLandscapeGate()
            if (!screenOn()) {
                handler.postDelayed(this, TICK_IDLE_MS)
                return
            }
            // 手指按下/滑动中：跳过心跳里的较重工作，别和手势抢主线程。
            // 但如果事件流已经很久没动静（UP 被系统丢掉、手指早已离开），必须复位：
            // 否则浮层会一直挂在屏幕上，而且心跳被永久跳过，自愈也一起停摆。
            if (state != State.IDLE) {
                val silent = System.currentTimeMillis() - lastEventAt
                if (silent > GESTURE_STALL_MS) {
                    XLog.w("手势事件流停滞 ${silent}ms（疑似 UP 被系统丢弃），强制收起复位")
                    resetGesture("事件流停滞")
                }
                handler.postDelayed(this, TICK_MS)
                return
            }
            // 走到这里 state == IDLE。自愈：浮层还挂着但手势已经结束 —— 正常路径不会走到这，
            // 兜「某个分支漏了 hide」「旁听侧丢了手势没通知」这类漏。手指还按着时 state != IDLE，
            // 所以放在这条 else 路径上不会误收。
            if (overlayVisible) {
                XLog.w("浮层可见但手势已结束，收起（自愈）")
                resetGesture("浮层可见但空闲")
            }
            try {
                val latest = configProvider()
                if (latest != config) {
                    XLog.i("配置热更新：${latest.summary()}")
                    config = latest
                    AppRepository.starLimit = config.gridColumns * 2
                    AppRepository.invalidate()
                    strips.forEach { applyGestureExclusion(it.view) }
                    syncStripTouchability()
                    overlay?.let { applyBackgroundMode(it) }
                }
                ForegroundApp.refresh(context) // 前台应用缓存在后台刷，别放手势路径
                // 桌面壁纸模糊背景：趁在桌面停留的空档先生成好（壁纸没换就不会重算），
                // 呼出时直接铺上；生成在后台线程，主线程只收结果
                if (!overlayVisible && config.immersive) {
                    val b = screenBounds()
                    BlurredWallpaper.ensure(context, b.width(), b.height())
                }
                syncStrips()
                syncStripTouchability()
                if (!AppRepository.isSettled()) {
                    // 开机早期 PackageManager 还没扫完，清单会残缺：主动后台重查补全。
                    // ⚠️ 不能只靠 apps() 的短 TTL：心跳周期和 TTL 都是 2.5s，边界上常常刚好
                    // 判定"缓存还没过期"而整拍不查，补全被拖到用户呼出那一刻才发生。
                    AppRepository.probeAsync(context)
                } else if (AppRepository.groups().isEmpty()) {

                    AppRepository.prewarm(context)
                }
                cleanupStaleOverlay()
                selfCheck()
            } catch (t: Throwable) {
                XLog.e("心跳异常", t)
            }
            // 定期失效屏幕尺寸缓存：兜底"旋转后 currentWindowMetrics 迟迟不更新"的时序问题
            if ((tickCount++ % 4) == 0) boundsCache = null
            handler.postDelayed(this, TICK_MS)
        }
    }

    private val componentCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            // 旋转即时生效，不等心跳；屏幕尺寸缓存要一起失效
            boundsCache = null
            refreshLandscapeGate()
            try { syncStrips() } catch (t: Throwable) { XLog.e("旋转处理失败", t) }
        }

        override fun onLowMemory() {}
    }

    /**
     * 应用装上/卸下：
     *   - 模块自己被卸载 → 立即清理残留（广播比心跳自检快）
     *   - 别的应用有变化 → 立刻重建索引清单并预热图标，不用等缓存过期（最长 2 分钟）
     */
    private val packageRemovedReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            val pkg = intent.data?.schemeSpecificPart ?: return
            if (action == Intent.ACTION_PACKAGE_REMOVED) {
                if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
                if (pkg == Const.MODULE_PACKAGE) {
                    clearResidueNow()
                    return
                }
            }
            if (pkg == context.packageName) return
            XLog.i("应用变化（$pkg），重建索引清单")
            try {
                AppRepository.reloadAsync(context)
            } catch (t: Throwable) {
                XLog.d("重建清单失败：${t.javaClass.simpleName}")
            }
        }
    }

    private fun registerSelfRemovalWatcher() {
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addDataScheme("package")
            }
            context.registerReceiver(packageRemovedReceiver, filter)
        } catch (t: Throwable) {
            XLog.d("注册卸载监听失败：$t")
        }
    }

    private fun unregisterSelfRemovalWatcher() {
        try {
            context.unregisterReceiver(packageRemovedReceiver)
        } catch (t: Throwable) {
        }
    }

    /**
     * 亮屏／息屏。息屏时停掉穿透看护（屏幕关着不用空转轮询），亮屏立刻同步一次触摸模式
     * （否则拿着息屏前的旧状态干等下一次心跳）。心跳在息屏时本来就不做同步，不会又把看护拉起来。
     */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            try {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_ON -> syncStripTouchability()
                    Intent.ACTION_SCREEN_OFF -> stopWatch()
                }
            } catch (t: Throwable) {
                XLog.d("亮息屏处理失败：" + t.javaClass.simpleName)
            }
        }
    }

    private fun registerScreenWatcher() {
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            context.registerReceiver(screenReceiver, filter)
        } catch (t: Throwable) {
            XLog.d("注册亮息屏监听失败：$t")
        }
    }

    private fun unregisterScreenWatcher() {
        try {
            context.unregisterReceiver(screenReceiver)
        } catch (t: Throwable) {
        }
    }

    /** 清掉系统设置库里的残留键：模块卸载后不该留任何东西 */
    private fun clearResidueNow() {
        var cleared = 0
        for (key in listOf(SecureConfigChannel.KEY, "eta_sideindex_star")) {
            try {
                if (android.provider.Settings.Secure.getString(context.contentResolver, key) != null) {
                    android.provider.Settings.Secure.putString(context.contentResolver, key, null)
                    cleared++
                }
            } catch (t: Throwable) {
            }
        }
        XLog.i("模块已卸载，清理残留配置：$cleared 项")
    }

    /** 息屏时用户不会操作：暂停常规心跳，只留低频自愈，省电 */
    private fun screenOn(): Boolean = try {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        pm?.isInteractive ?: true
    } catch (t: Throwable) {
        true
    }

    /** 配置变化立即生效（ContentObserver 替代心跳轮询：更省电、响应更快） */
    private val configObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            ConfigReader.invalidate()
        }
    }

    private fun registerConfigObserver() {
        try {
            context.contentResolver.registerContentObserver(
                android.provider.Settings.Secure.getUriFor("eta_sideindex_config"), false, configObserver
            )
        } catch (t: Throwable) {
            XLog.d("注册配置监听失败：$t")
        }
    }

    private fun unregisterConfigObserver() {
        try {
            context.contentResolver.unregisterContentObserver(configObserver)
        } catch (t: Throwable) {
        }
    }

    fun start() {
        if (running) return
        running = true
        config = configProvider()
        AppRepository.starLimit = config.gridColumns * 2
        XLog.a("索引浮层启动：${config.summary()}")
        try { context.registerComponentCallbacks(componentCallbacks) } catch (t: Throwable) { XLog.d("注册配置回调失败：$t") }
        registerSelfRemovalWatcher()
        registerScreenWatcher()
        registerConfigObserver()
        // 呼出判定改由触摸旁听驱动（感应带窗口不再接收触摸，见 BackGestureGate）。
        // ⚠️ 旁听回调跑在 SystemUI 的 "GestureUI" 线程上，动视图必须回主线程；
        // 而且事件对象回调结束就会被回收 —— 必须拷一份再 post。
        BackGestureGate.onTouch = { e, right ->
            try {
                val copy = MotionEvent.obtain(e)
                handler.post {
                    try {
                        handleTouch(right, copy, fromMonitor = true)
                    } catch (t: Throwable) {
                        XLog.e("旁听手势异常", t)
                    } finally {
                        copy.recycle()
                    }
                }
            } catch (t: Throwable) {
                XLog.e("旁听事件拷贝失败", t)
            }
        }
        // 手指落在感应带内时的即时接管判定（见 probeTakeoverNow）：
        // 补救「心跳 2.5 秒一拍」导致回桌面后立刻侧滑被丢弃的问题。
        // ⚠️ 这个回调跑在 "GestureUI" 线程上，只能做只读判定，绝不能碰视图。
        BackGestureGate.onDownProbe = { probeTakeoverNow() }
        // 旁听侧丢弃了已接管的手势（比如中途 active 变 false）→ 通知控制器收起。
        // 不加这条，状态机会卡在 ACTIVE：浮层挂在屏幕上、点不到、也收不起来，
        // 而触摸全落到后面的应用（用户 2026-10-16 实测的现象）。
        BackGestureGate.onGestureDropped = {
            handler.post {
                if (state != State.IDLE) {
                    XLog.w("旁听侧丢弃手势，收起浮层复位")
                    resetGesture("旁听侧丢弃手势")
                }
            }
        }
        ensureOverlayWindow()
        AppRepository.prewarm(context)
        syncStrips()
        handler.postDelayed(tick, TICK_MS)
    }

    fun stop() {
        running = false
        abortMirrorSwipe()
        BackGestureGate.onTouch = null
        BackGestureGate.onDownProbe = null
        BackGestureGate.update(null, null, false)
        stopWatch()
        watchThread?.quitSafely()
        watchThread = null
        watchHandler = null
        handler.removeCallbacksAndMessages(null)
        try { context.unregisterComponentCallbacks(componentCallbacks) } catch (_: Throwable) {}
        hideOverlay(animated = false, reason = "停止")
        overlay?.let { removeOverlayWindow(it) }
        removeStrips()
        unregisterSelfRemovalWatcher()
        unregisterConfigObserver()
        unregisterScreenWatcher()
        XLog.i("索引浮层已停止")
    }

    // ---------------- 感应带（含自愈） ----------------

    private fun shouldHaveStrips(): Boolean = config.enabled

    private fun syncStrips() {
        val want = shouldHaveStrips()
        if (!want) {
            if (strips.isNotEmpty()) {
                XLog.i("当前不该显示感应带（总开关已关），摘掉")
                removeStrips()
            }
            return
        }
        val wantCount = (if (config.onRight()) 1 else 0) + (if (config.onLeft()) 1 else 0)
        if (strips.size != wantCount || strips.any { it.view.parent == null }) {
            XLog.i("感应带缺失或数量不符，自动重建（自愈）")
            rebuildStrips()
            return
        }
        // 配置或屏幕尺寸变了（改字母条高度、转向、改触发区宽度）：把已挂的带子挪到新位置
        for (strip in strips) {
            val lp = stripLayout(strip.isRight)
            if (lp.width == strip.width && lp.height == strip.height &&
                lp.x == strip.x && lp.y == strip.y
            ) continue
            try {
                windowManager.updateViewLayout(strip.view, lp)
                strip.width = lp.width
                strip.height = lp.height
                strip.x = lp.x
                strip.y = lp.y
                XLog.d("感应带已更新：${lp.width}×${lp.height} @${lp.x},${lp.y}")
                applyGestureExclusion(strip.view)
            } catch (t: Throwable) {
                XLog.d("更新感应带失败：$t")
            }
        }
    }

    /** 按配置挂触发带：默认左右两侧都挂（both_sides 关掉只留右侧） */
    private fun rebuildStrips() {
        removeStrips()
        if (config.onRight()) addStrip(isRight = true)
        if (config.onLeft()) addStrip(isRight = false)
        XLog.i("感应带已挂载：${strips.size} 条（呼出侧=${config.triggerSide}）")
    }

    private fun addStrip(isRight: Boolean) {
        val view = View(context).apply { setBackgroundColor(0x01000000) }
        view.setOnTouchListener { _, event -> handleTouch(isRight, event) }

        val lp = stripLayout(isRight)
        try {
            windowManager.addView(view, lp)
            strips.add(Strip(view, isRight, lp.width, lp.height, lp.x, lp.y))
            applyGestureExclusion(view)
        } catch (t: Throwable) {
            XLog.e("挂载感应带失败", t)
        }
    }

    /**
     * 触发区 = 字母条本身：高度就是字母条高度（bar_height_percent），
     * 位置由 trigger_position_percent 指定（50 = 屏幕垂直居中），只挂右侧。
     * 只有这一段边缘接管手势，屏幕其他位置照常滚动/返回，不再整条屏高都拦。
     */
    private fun stripLayout(isRight: Boolean): WindowManager.LayoutParams {
        val bounds = screenBounds()
        screenW = bounds.width()
        screenH = bounds.height()
        // 横屏独立参数（写死，无设置入口）：条高/触发高 85% 屏高、垂直位置 50% 屏高；
        // 触发宽度继承竖屏的触发宽度设置。竖屏全部走配置
        val landscape = Display.isLandscape(context, windowManager)
        val heightPercent = if (landscape) ModuleConfig.FIXED_LANDSCAPE_BAR_PERCENT else config.barHeightPercent
        val positionPercent = if (landscape) 50 else config.triggerPositionPercent
        val height = (bounds.height() * heightPercent / 100)
            .coerceAtLeast(Display.dp(context, 200))
            .coerceAtMost(bounds.height())
        // 横屏触发宽度写死 22dp；竖屏走用户设置
        val triggerWidth = if (landscape) ModuleConfig.FIXED_LANDSCAPE_TRIGGER_DP else config.triggerWidthDp
        val width = Display.dp(context, triggerWidth).coerceAtLeast(Display.dp(context, 6))
        val center = bounds.height() * positionPercent / 100f
        val top = (center - height / 2f).toInt().coerceIn(0, (bounds.height() - height).coerceAtLeast(0))
        return WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                // 1.31.0：感应带窗口**永不接收触摸**。它一旦可触摸就是那块区域的触摸目标 ——
                // 系统返回手势会把它当"非桌面窗口上的边缘滑动"而冒返回箭头，且窗口吞掉 DOWN 之后
                // 桌面翻页再也拿不到这根手指（ColorOS 实测，手势排除对 overlay 窗口不生效）。
                // 呼出手势改由 BackGestureGate 旁听 SystemUI 手势监视器的事件驱动。
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or if (isRight) Gravity.END else Gravity.START
            y = top
        }
    }

    private fun applyGestureExclusion(view: View, desktopNow: Boolean = ForegroundApp.isLauncher(context)) {
        view.post {
            try {
                // 排除条件（任一条命中就整块排除）：
                //  1. 用户关了"允许系统返回"；
                //  2. **站在桌面上** —— 桌面没有"返回"这回事，不排除的话边缘往里滑会冒出系统
                //     返回指示、桌面也不翻页（1.30.23 修）。应用里仍按 allowSystemBack 决定：
                //     横/斜滑让给系统返回，竖滑（≥2 倍横滑）才归索引
                val exclude = desktopNow || !config.allowSystemBack
                view.systemGestureExclusionRects =
                    if (exclude) listOf(Rect(0, 0, view.width, view.height)) else emptyList()
            } catch (t: Throwable) {
                XLog.d("设置排除区失败：${t.javaClass.simpleName}")
            }
        }
    }

    private fun removeStrips() {
        for (strip in strips) {
            try { windowManager.removeView(strip.view) } catch (t: Throwable) { XLog.d("移除感应带失败：$t") }
        }
        strips.clear()
    }

    // ---------------- 手势 ----------------

    /**
     * @param fromMonitor true = 事件来自触摸旁听（BackGestureGate），坐标已经是屏幕坐标。
     *                    这条路上我们不消费事件（只是旁听），所以"点击透传""横滑镜像"都不能做
     *                    —— 桌面/应用自己已经收到并处理了，再注入就是两次动作。
     */
    private fun handleTouch(isRight: Boolean, event: MotionEvent, fromMonitor: Boolean = false): Boolean {
        try {
        val strip: Strip? =
            if (fromMonitor) null else (strips.firstOrNull { it.isRight == isRight } ?: return false)
        val x: Float
        val y: Float
        if (strip == null) {
            x = event.x
            y = event.y
        } else {
            val loc = IntArray(2)
            strip.view.getLocationOnScreen(loc)
            x = loc[0] + event.x
            y = loc[1] + event.y
        }

        lastEventAt = System.currentTimeMillis()

        // 遮罩自愈（1.31.17）：每个事件都顺手比对一次（内部有节流）。
        // 修的是「全局模式滑动途中遮罩变透明、之后一直透明」。
        fixBackdropIfNeeded()
        gestureEventCount++

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 诊断用：每次按下都留一行，方便看"第二次为什么没反应"
                XLog.d(
                    "DOWN x=$x y=$y state=$state 感应带=${strips.size} " +
                        "${ForegroundApp.debugValue(context)} 桌面=${ForegroundApp.launcherPackage(context)}"
                )
                // 应用范围筛选：不在范围内就完全不接管这次手势（放行给系统/应用）
                if (!allowedHere()) {
                    state = State.IDLE
                    return false
                }
                fromRight = isRight
                downX = x
                downY = y
                lastY = y
                state = State.PENDING
                migrated = false
                hovered = null
                activateRetries = 0
                gestureMaxDy = 0f
                gestureMaxDx = 0f
                gestureEventCount = 1
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (state == State.PASS) {
                    // 这次手势已经交给桌面了：只负责把位移继续镜像过去
                    mirrorSwipeMove(x, y)
                    return true
                }
                if (state == State.IDLE) return false
                val dy = y - downY
                val dx = x - downX
                if (abs(dy) > gestureMaxDy) gestureMaxDy = abs(dy)
                if (abs(dx) > gestureMaxDx) gestureMaxDx = abs(dx)
                val slop = activationDistancePx()
                if (state == State.PENDING) {

                    // 全局呼出模式要求更"竖"的滑动才成立：给斜向的系统返回手势让路
                    // （全屏应用/游戏里右缘斜滑是返回，不收紧的话返回会被呼出判定抢走）
                    val straight = if (config.onlyLauncher || ForegroundApp.isLauncher(context)) {
                        STRAIGHT_LAUNCHER
                    } else {
                        STRAIGHT_APP
                    }
                    when {
                        abs(dy) >= slop && abs(dy) >= abs(dx) * straight -> {
                            // 返回箭头正在显示 → 这根手指是"用户想返回又后悔、把箭头推回去"的那次
                            // （1.31.23）。用户 2026-10-16 实测：推回去的途中手指上下抖一下，
                            // 竖滑成立就把索引呼出来了。DOWN 时的检查盖不住这种"滑到一半箭头才出现"。
                            if (BackGestureUi.isShowing()) {
                                XLog.i("返回箭头显示中：放弃呼出索引，让给返回手势")
                                resetGesture("返回箭头显示中")
                                return true
                            }
                            state = State.ACTIVE
                            XLog.i("滑动手势成立：dy=${dy.toInt()} dx=${dx.toInt()} slop=${slop.toInt()}")
                            activateOverlay(y)
                        }
                        abs(dx) > slop && abs(dx) > abs(dy) * 1.4f -> {
                            XLog.d("判为横滑让给系统：dy=${dy.toInt()} dx=${dx.toInt()} slop=${slop.toInt()}")
                            // 桌面上的横滑 = 桌面翻页。感应带把 DOWN 吃掉了，桌面永远收不到这根手指，
                            // 于是把手指镜像成一条合成滑动注入给桌面（起点往屏幕内侧挪一点，避开感应带自己）。
                            // 应用里不镜像：那里的横滑要留给系统返回手势，注进去反而变成应用收到一次滑动。
                            // 1.31.0 起感应带窗口不再吃触摸，桌面/应用本来就会收到这根横滑
                            // （桌面翻页、系统返回都归它们），所以旁听路径绝不能再注入一遍。
                            // 只有"窗口来源"的事件（理论上已不会发生）才走老的镜像兜底。
                            if (!fromMonitor && ForegroundApp.isLauncher(context) &&
                                startMirrorSwipe(isRight, downX, downY)
                            ) {
                                state = State.PASS
                                return true
                            }
                            state = State.IDLE
                            return false
                        }
                        else -> return true
                    }
                }

                val view = overlay ?: return true
                // 屏幕宽用 screenBounds()（带 rotation 失效的缓存，缓存命中无 binder）：
                // 呼出瞬间 screenW 字段可能还是上一次方向的旧值，用它会把"离开索引条"
                // 判定算成负数 —— 平移到网格上仍被当成"在条上滑动"，字母一直切
                val boundsW = screenBounds().width()
                val inward = if (fromRight) boundsW - x else x
                // 横屏：分界 = 边缘往里 FIXED_LANDSCAPE_LOCK_DP（21dp）—— 超过就锁字母进入挑图标
                val barZone = if (Display.isLandscape(context, windowManager)) {
                    Display.dp(context, ModuleConfig.FIXED_LANDSCAPE_LOCK_DP).toFloat()
                } else {
                    (view.barWidthPx() + Display.dp(context, 8)).toFloat()
                }
                // 「冻结」判据：手指离开索引条（跑到条外，或明显是横向移动）就停止跟随字母 ——
                // 横移去挑图标的中途 y 漂一点，也不会把 M 带成 N。
                // ⚠️ 必须**双向**：手指回到条内立刻解冻、恢复跟字母。
                // 旧写法用「相对按下点内移 24dp」是单向的，一旦冻上这轮手势就再也跟不了字母。
                // 离开索引条 = 真的横移出去了：再加"横向位移 > 10dp"条件 ——
                // 否则大跨度竖滑时手指斜一点（x 漂出分界）就被误锁，表现成"滑着滑着卡住"
                val leftBar = inward > barZone && abs(dx) > Display.dp(context, 10)
                val sideways = abs(dx) > Display.dp(context, 10) && abs(dx) > abs(dy) * 1.4f
                val inGrid = leftBar || sideways
                if (inGrid != migrated) {
                    migrated = inGrid
                    XLog.d("锁字母：migrated=$migrated inward=${inward.toInt()} barZone=${barZone.toInt()} dx=${dx.toInt()} dy=${dy.toInt()}")
                    view.setHover(null)
                }

                if (migrated) {
                    // 手指进网格挑图标：先把还没追上的网格补上，避免点到上一组的内容
                    view.flushPendingGrid()
                    val app = view.appAt(x, y)
                    if (app !== hovered) {
                        hovered = app
                        view.setHover(app)
                    }
                } else {
                    view.setAnchorY(y)
                    view.selectByY(y)
                    if (hovered != null) {
                        hovered = null
                        view.setHover(null)
                    }
                }
                lastY = y
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (state == State.PASS) {
                    // 镜像手势收尾：补一个同坐标的 UP，让桌面自己按位移/速度决定翻不翻页
                    endMirrorSwipe(x, y)
                    state = State.IDLE
                    return true
                }
                val wasActive = state == State.ACTIVE
                val wasPending = state == State.PENDING
                val cancelled = event.actionMasked == MotionEvent.ACTION_CANCEL
                state = State.IDLE
                if (!wasActive) {
                    XLog.d(
                        "手势未激活结束：${if (cancelled) "CANCEL" else "UP"} " +
                            "最大位移 dy=${gestureMaxDy.toInt()} dx=${gestureMaxDx.toInt()} " +
                            "slop=${activationDistancePx().toInt()}"
                    )
                    // 只是在感应带上点了一下（没滑动）——把这次点击原样透传给下面的界面，
                    // 否则「多任务界面点卡片旁空白回桌面」会被感应带整个吃掉，点哪儿都没反应
                    // （1.31.0 起感应带窗口不再接收触摸，旁听来的事件本来就没被吃，不用透传）
                    if (wasPending && !cancelled && strip != null) {
                        XLog.d("感应带上的纯点击，透传给下层：x=${x.toInt()} y=${y.toInt()}")
                        passThroughTap(strip, x, y)
                    }
                    return true
                }

                val target = hovered
                if (event.actionMasked == MotionEvent.ACTION_UP && target != null) {
                    XLog.i("松手启动应用：${target.label}（小窗=${config.freeformLaunch}）")
                    hideOverlay(animated = false, reason = "松手启动应用")
                    AppRepository.launch(context, target, config.freeformLaunch)
                    // ★ 收藏栏只统计"通过索引栏打开"的次数；记完让清单失效，下次呼出带上新排序
                    AppRepository.recordLaunch(context, target.packageName)
                    AppRepository.invalidate()
                } else {
                    hideOverlay(animated = true, reason = if (cancelled) "手势取消" else "松手")
                }
                migrated = false
                hovered = null
                return true
            }
        }
        return true
        } catch (t: Throwable) {
            // 手势回调里抛异常会一路抛进 SystemUI 的事件分发：这里必须全兜住
            XLog.e("手势处理异常", t)
            resetGesture("手势异常")
            return false
        }
    }

    // ---------------- 横滑镜像（桌面翻页透传） ----------------

    /**
     * 桌面上的「边缘往里滑」本来是桌面自己的翻页手势。
     *
     * 但感应带必须在 DOWN 时就消费触摸（不消费就收不到 MOVE，竖滑呼出无从成立），
     * 于是这根手指永远到不了桌面 —— 用户看到的是"不翻页，还冒出个系统返回指示"。
     *
     * 解法：判定为横滑后把手指**镜像**成一条合成手势注入回桌面：
     *   - 起点 = 手指按下点往屏幕内侧挪 [MIRROR_INSET_DP]（避开感应带窗口自己，别把注入又收回到自己这里）
     *   - 后续 MOVE 按同一偏移跟随（y 不动，桌面看到的落点和手指一致）
     *   - 松手补一个同坐标 UP：桌面按自己的位移/速度判据决定翻页还是弹回
     * 注入不可用（没权限/接口变化）就退回老行为：什么都不做，不崩。
     */
    private var mirActive = false
    private var mirShift = 0f
    private var mirDownX = 0f
    private var mirDownY = 0f
    private var mirDownTime = 0L
    private var mirLastAt = 0L
    private var mirLastX = 0f
    private var mirLastY = 0f

    private fun startMirrorSwipe(isRight: Boolean, x: Float, y: Float): Boolean {
        val shift = Display.dp(context, MIRROR_INSET_DP).toFloat() * (if (isRight) -1f else 1f)
        val now = android.os.SystemClock.uptimeMillis()
        if (!injectTouch(MotionEvent.ACTION_DOWN, x + shift, y, now, now)) return false
        mirActive = true
        mirShift = shift
        mirDownX = x
        mirDownY = y
        mirLastX = x
        mirLastY = y
        mirDownTime = now
        mirLastAt = now
        XLog.d("横滑镜像：注入 DOWN @${(x + shift).toInt()},${y.toInt()}（手指 ${x.toInt()},${y.toInt()}）")
        return true
    }

    private fun mirrorSwipeMove(x: Float, y: Float) {
        if (!mirActive) return
        mirLastX = x
        mirLastY = y
        val now = android.os.SystemClock.uptimeMillis()
        if (now - mirLastAt < MIRROR_MIN_MS) return
        mirLastAt = now
        if (!injectTouch(MotionEvent.ACTION_MOVE, x + mirShift, y, mirDownTime, now)) {
            mirActive = false
            XLog.d("横滑镜像：MOVE 注入失败，本次放弃")
        }
    }

    private fun endMirrorSwipe(x: Float, y: Float) {
        if (!mirActive) return
        mirActive = false
        val now = android.os.SystemClock.uptimeMillis()
        if (!injectTouch(MotionEvent.ACTION_UP, x + mirShift, y, mirDownTime, now)) {
            XLog.d("横滑镜像：UP 注入失败")
        }
    }

    /** 事件流断了（UP 被系统丢掉）时补上镜像手势的 UP，别让桌面一直以为手指还按着 */
    private fun abortMirrorSwipe() {
        if (!mirActive) return
        XLog.d("横滑镜像：事件流中断，补发 UP")
        endMirrorSwipe(mirLastX, mirLastY)
    }

    /** 注入一次触摸：IInputManager binder（SystemUI 是 system uid，有 INJECT_EVENTS 权限） */
    private fun injectTouch(action: Int, x: Float, y: Float, downTime: Long, eventTime: Long): Boolean = try {
        val iim = inputManagerInterface() ?: return false
        val e = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
            .apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
        val ok = com.eta.aicybox.sideindex.LxApi
            .callMethod(iim, "injectInputEvent", e, 0) as? Boolean ?: false
        e.recycle()
        ok
    } catch (t: Throwable) {
        XLog.d("注入触摸异常：" + t.javaClass.simpleName)
        false
    }

    @Volatile private var iimCache: Any? = null

    private fun inputManagerInterface(): Any? {
        iimCache?.let { return it }
        val binder = com.eta.aicybox.sideindex.LxApi.callStaticMethod(
            Class.forName("android.os.ServiceManager"), "getService", "input"
        ) as? android.os.IBinder ?: return null
        val iim = com.eta.aicybox.sideindex.LxApi.callStaticMethod(
            Class.forName("android.hardware.input.IInputManager\$Stub"), "asInterface", binder
        ) ?: return null
        iimCache = iim
        return iim
    }

    /**
     * 把感应带上的一次"纯点击"透传给下层界面。
     *
     * 感应带必须消费 ACTION_DOWN（不消费就收不到后续 MOVE，竖滑呼出无从成立），
     * 代价是"点一下"也会被吃掉 —— 典型症状是多任务界面点卡片旁的空白回不了桌面。
     * 所以手指抬起、且这次手势完全没滑动时，把感应带临时设成不接收触摸，
     * 再用 root 注入一次同坐标点击，让下面那一层收到本该收到的那一下。
     * 注入不可用（没有 root）时退回"回桌面"，在多任务界面语义相同。
     */
    private fun passThroughTap(strip: Strip, x: Float, y: Float) {
        try {
            val lp = strip.view.layoutParams as? WindowManager.LayoutParams ?: return
            val original = lp.flags
            handler.postDelayed({
                try {
                    lp.flags = original or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    windowManager.updateViewLayout(strip.view, lp)
                } catch (t: Throwable) {
                    XLog.d("透传：暂停触摸失败 ${t.javaClass.simpleName}")
                    return@postDelayed
                }
                Thread {
                    // 首选 InputManager 直接注入：SystemUI 是 system uid，有 INJECT_EVENTS 权限，
                    // 不依赖 su —— 本机 su 对 SystemUI 不可用，"点触发区没反应"就是它导致的
                    var injected = injectTapViaInputManager(x, y)
                    if (!injected && suAvailable != false) {
                        try {
                            val process = Runtime.getRuntime()
                                .exec(arrayOf("su", "-c", "input tap ${x.toInt()} ${y.toInt()}"))
                            injected = process.waitFor(1500, java.util.concurrent.TimeUnit.MILLISECONDS) &&
                                process.exitValue() == 0
                            suAvailable = injected
                        } catch (t: Throwable) {
                            XLog.d("透传：su 注入异常 ${t.javaClass.simpleName}")
                            suAvailable = false
                        }
                    }
                    if (!injected) {
                        // 两种注入都失败：什么都不做，好过突然回到桌面
                        XLog.d("透传：两种注入都失败，本次点击丢弃")
                    }
                    handler.postDelayed({
                        try {
                            lp.flags = original
                            windowManager.updateViewLayout(strip.view, lp)
                        } catch (t: Throwable) {
                        }
                    }, 150L)
                }.start()
            }, 60L)
        } catch (t: Throwable) {
            XLog.d("透传异常：${t.javaClass.simpleName}")
        }
    }

    /**
     * 注入一次点击：走 `IInputManager` binder（ServiceManager 拿 "input" 服务）。
     * SystemUI 是 system uid、有 INJECT_EVENTS 权限，比 su 可靠 ——
     * `InputManager.getInstance()` 在新系统上已返回 null（踩过），所以直接用 binder 接口。
     */
    private fun injectTapViaInputManager(x: Float, y: Float): Boolean = try {
        val binder = com.eta.aicybox.sideindex.LxApi.callStaticMethod(
            Class.forName("android.os.ServiceManager"), "getService", "input"
        ) as? android.os.IBinder ?: run {
            XLog.d("透传：拿不到 input 服务")
            return false
        }
        val iim = com.eta.aicybox.sideindex.LxApi.callStaticMethod(
            Class.forName("android.hardware.input.IInputManager\$Stub"), "asInterface", binder
        ) ?: run {
            XLog.d("透传：IInputManager asInterface 失败")
            return false
        }
        val now = android.os.SystemClock.uptimeMillis()
        val down = android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_DOWN, x, y, 0)
            .apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
        val up = android.view.MotionEvent.obtain(now, now + 40L, android.view.MotionEvent.ACTION_UP, x, y, 0)
            .apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
        val okDown = com.eta.aicybox.sideindex.LxApi
            .callMethod(iim, "injectInputEvent", down, 0) as? Boolean ?: false
        val okUp = com.eta.aicybox.sideindex.LxApi
            .callMethod(iim, "injectInputEvent", up, 0) as? Boolean ?: false
        down.recycle()
        up.recycle()
        XLog.d("透传：IInputManager 注入 down=$okDown up=$okUp")
        okDown && okUp
    } catch (t: Throwable) {
        XLog.i("透传：注入异常 " + t.javaClass.simpleName + " " + t.message)
        false
    }

    // ---------------- 穿透看护（回桌面立刻恢复可触摸） ----------------

    /**
     * 感应带处在触摸穿透时启动的后台看护。
     *
     * 触摸模式只在 [syncStripTouchability] 里同步，而它过去只由心跳（[TICK_MS] = 2.5 秒）驱动：
     * 从应用回到桌面后最长 2.5 秒里感应带还是**穿透**的 —— 侧滑根本到不了模块，
     * 触摸落给桌面，被桌面的「桌面下滑打开控制中心」接走（用户实测：QQ／相册看图回桌面立刻侧滑会拉下控制中心）。
     *
     * 这里在穿透期间用独立线程按 [WATCH_MS] 查一次[ForegroundApp.topIsLauncher]（只查顶层包名，
     * 一次 ATMS 调用），一回到桌面就回主线程重同步。回到桌面后看护自动停，之后仍由心跳兜底
     * ——「桌面 + 小窗／分屏」那种需要看 task 可见性的判定留在心跳里，不进这个 5Hz 轮询。
     *
     * 顺带把反方向也收紧了：从桌面进应用后，穿透状态最多 200ms 就位，
     * 不会像以前那样在应用里先「吃掉」右缘 2.5 秒的触摸。
     */
    @Volatile private var watching = false
    private var watchThread: android.os.HandlerThread? = null
    private var watchHandler: Handler? = null

    private val watchTick: Runnable = object : Runnable {
        override fun run() {
            if (!running || !watching) return
            val onDesktop = try {
                ForegroundApp.topIsLauncher(context)
            } catch (t: Throwable) {
                false
            }
            if (onDesktop) {
                // updateViewLayout 得回视图线程
                handler.post {
                    if (state == State.IDLE) {
                        syncStripTouchability()
                    } else {
                        // 手势进行中不动窗口，稍后再试（心跳也会兜底）
                        retryWatch()
                    }
                }
                return
            }
            watchHandler?.postDelayed(this, WATCH_MS)
        }
    }

    /** 看护再排一拍（抽成方法，避免在 watchTick 自己的初始化里引用自己导致类型推断递归） */
    private fun retryWatch() {
        watchHandler?.postDelayed(watchTick, WATCH_MS)
    }

    private fun startWatch() {
        if (!running || watching) return
        watching = true
        val h = watchHandler ?: run {
            val t = android.os.HandlerThread("EtaSideIndexWatch")
            t.start()
            watchThread = t
            Handler(t.looper).also { watchHandler = it }
        }
        h.postDelayed(watchTick, WATCH_MS)
        XLog.d("穿透看护：启动（${WATCH_MS}ms 一查，回桌面立刻恢复触摸）")
    }

    private fun stopWatch() {
        if (!watching) return
        watching = false
        watchHandler?.removeCallbacks(watchTick)
        XLog.d("穿透看护：停止")
    }

    /**
     * 手指落在感应带内时的**即时**接管判定（由 BackGestureGate 在 "GestureUI" 线程上同步调用）。
     *
     * 为什么需要：`BackGestureGate.active` 只由心跳（[TICK_MS] = 2.5 秒）经
     * [syncStripTouchability] 更新。从应用回到桌面后、下一次心跳到来之前它仍是 false，
     * 那一根手势会被触摸旁听的 DOWN 分支直接丢掉 —— 用户表现为「点开图片回桌面立刻侧滑
     * 呼不出索引」，且偶发（取决于心跳相位）。1.30.20 曾用「穿透看护」5Hz 轮询修过同一个
     * 症状，1.31.0 改成触摸旁听后那段看护失效，问题复发。
     *
     * 这里只重算「要不要接管」并回写，不碰视图、不动感应带范围：
     * 视图相关的 [syncStripTouchability] 仍然只在主线程的心跳里跑。
     */
    private fun probeTakeoverNow(): Boolean {
        val onDesktop = try {
            ForegroundApp.isLauncher(context)
        } catch (t: Throwable) {
            // 判定失败时宁可放行：代价只是多走一次 allowedHere（那里还会再判一次）
            true
        }
        val want = !config.onlyLauncher || onDesktop
        BackGestureGate.setOnDesktop(onDesktop)
        BackGestureGate.setActive(want)
        if (want) XLog.d("呼出接管：即时判定放行（补心跳的相位差）")
        return want
    }

    /**
     * 仅桌面模式 + 前台不是桌面时，把感应带设成**触摸穿透**：
     * 应用里右缘的滑动（微信列表滚动条之类）与点击就完全不受影响，不需要透传兜底。
     * 桌面 / 全局模式下保持可触摸（要呼出索引）。
     */
    private fun syncStripTouchability() {
        // 感应带窗口始终不可触摸（见 stripLayout 里的注释），所以这里只做两件事：
        //   1. 告诉触摸旁听（BackGestureGate）感应带在屏幕上的范围；
        //   2. 告诉它要不要把带内手势转给模块做呼出判定 —— 口径同以前：
        //      仅桌面模式只在桌面接管，全局索引模式到处接管。
        // 注意：这里是**心跳路径**（2.5 秒一拍），只负责把范围与接管状态收敛到稳定值；
        // 心跳间隙里的即时判定由 [probeTakeoverNow] 在手指 DOWN 落带时补上。
        val onDesktop = ForegroundApp.isLauncher(context)
        val want = !config.onlyLauncher || onDesktop
        // 同步「是不是桌面」：触发区内下滑拦控制中心要用（桌面下拉走桌面自己那条链路）
        BackGestureGate.setOnDesktop(onDesktop)
        BackGestureGate.update(
            right = bandRect(true),
            left = bandRect(false),
            on = want
        )
        // 触摸看护（1.30.20）已无用：窗口不再切换触摸模式，留着只是空转
        stopWatch()
    }

    /** 感应带在屏幕坐标系里的范围（返回手势屏蔽用；还没挂上或已摘掉时返回 null） */
    private fun bandRect(isRight: Boolean): Rect? {
        val strip = strips.firstOrNull { it.isRight == isRight } ?: return null
        if (strip.view.parent == null) return null
        return try {
            val loc = IntArray(2)
            strip.view.getLocationOnScreen(loc)
            Rect(loc[0], loc[1], loc[0] + strip.view.width, loc[1] + strip.view.height)
        } catch (t: Throwable) {
            null
        }
    }

    private fun allowedHere(): Boolean {
        // 手势路径上同步刷一次前台（binder 调用，几毫秒）：否则刚从桌面返回时会读到旧缓存而误判
        ForegroundApp.refreshNow(context)
        val foreground = ForegroundApp.current(context)
        if (foreground.isEmpty()) return true // 拿不到前台应用时不拦，宁可用得上
        // 全局呼出索引是上级开关：关掉就只在桌面呼出，游戏屏蔽与黑名单都不参与
        if (config.onlyLauncher && !ForegroundApp.isLauncher(context)) {
            XLog.d("手势不放行：不在桌面（${ForegroundApp.debugValue(context)} 桌面=${ForegroundApp.launcherPackage(context)}）")
            return false
        }
        // 横屏进入不足 3 秒：旋转期间坐标/布局不稳，先不响应（避免刚转过来就误触/错位）
        if (Display.isLandscape(context, windowManager) && landscapeSince > 0 &&
            System.currentTimeMillis() - landscapeSince < LANDSCAPE_GATE_MS
        ) {
            XLog.d("横屏进入不足 ${LANDSCAPE_GATE_MS / 1000} 秒，暂不响应呼出")
            return false
        }
        // 游戏中屏蔽（ColorOS 游戏模式信号，游戏中 = 1）
        if (config.gameBlock) {
            val gm = gameModeValue()
            if (gm == 1) {
                XLog.i("游戏中（debug_gamemode_value=$gm），屏蔽呼出手势")
                return false
            }
        }
        // 横屏时暂停（1.32.6）：横屏下不响应侧滑，切回竖屏恢复
        if (config.landscapePause && Display.isLandscape(context, windowManager)) {
            XLog.d("横屏暂停：本次手势不呼出（横屏模式）")
            return false
        }
        // 应用黑名单：这些应用里不响应（静默放行，不刷日志）
        if (config.blacklist.isNotBlank() &&
            config.blacklist.split(',').map { it.trim() }.contains(foreground)
        ) {
            return false
        }
        return true
    }

    /** ColorOS 游戏模式信号：1 = 游戏中；读不到返回 -1（一律视为不在游戏） */
    private fun gameModeValue(): Int = try {
        android.provider.Settings.Global.getInt(context.contentResolver, "debug_gamemode_value")
    } catch (t: Throwable) {
        -1
    }

    private fun activateOverlay(y: Float) {
        // 抢指针：桌面/应用的同期手势（比如桌面下滑打开控制中心）收到 cancel，别和索引同时动作
        BackGestureGate.pilfer()
        boundsCache = null // 呼出瞬间重取屏幕尺寸（旋转时序兜底）
        screenBounds().let { screenW = it.width(); screenH = it.height() }
        // 呼出前再复核一次前台：从按下到"滑动成立"有 100ms 以上，这段时间里
        // "刚切走"的 UsageStats 事件可能已经写入，能减少在别的应用里偶尔误呼出
        ForegroundApp.refreshNow(context)
        if (config.onlyLauncher && !ForegroundApp.isLauncher(context)) {
            XLog.d("呼出前复核：已不在桌面（${ForegroundApp.debugValue(context)}），取消呼出")
            resetGesture("呼出前复核失败")
            return
        }
        val loaded = AppRepository.apps(context)
        if (!AppRepository.isSettled() && activateRetries < 8) {
            // 开机头几秒 PackageManager 还没扫完，清单是残缺的（曾出现"只有 Y 和 S"）：
            // 先别急着展示残缺条，等一小拍重查，拿到完整清单再显示。
            // ⚠️ 重试间隔必须远小于"用户一次滑动的时长"：早期是 700ms×4，开机后第一次呼出
            // 常常等不到清单就绪就被松手作废 —— 表现就是"只有震动、界面不出来"。
            activateRetries++
            XLog.i("应用清单尚未就绪，延迟重试 ${activateRetries}/8")
            handler.postDelayed({ if (state == State.ACTIVE) activateOverlay(y) }, 200L)
            return
        }
        activateRetries = 0
        if (loaded.isEmpty()) {
            XLog.w("没有可启动的应用，索引浮层不显示")
            state = State.IDLE
            return
        }

        val view = ensureOverlayWindow() ?: run {
            state = State.IDLE
            return
        }
        // 兜底：刚回桌面、心跳还没来得及预热时就呼出（只有沉浸模式需要壁纸）
        if (config.immersive) {
            val bounds = screenBounds()
            BlurredWallpaper.ensure(context, bounds.width(), bounds.height())
        }
        view.visibility = View.VISIBLE
        // ⚠️ 这个字段以前**从来没有被赋成 true**（只有初始化和 hideOverlay 里的 false），
        // 结果两处判断都恒真，R8 也能据此把它当常量折叠掉（1.31.23 才发现）。
        overlayVisible = true
        view.prepare(
            loaded, AppRepository.groups(), config, fromRight,
            Display.isLandscape(context, windowManager)
        )
        // 字母条固定贴在触发区上（不再以手指为圆心展开）：触发区和索引条完全重合。
        // 直接读已挂载感应带的 y，别再走 stripLayout()（那里会同步取 windowMetrics，是 binder 调用）
        view.placeBarAt(strips.firstOrNull { it.isRight == fromRight }?.y ?: strips.firstOrNull()?.y ?: 0)
        if (Display.isLandscape(context, windowManager)) {
            // 伪横屏应用里感应带的挂载坐标可能是竖屏值：把感应带重新对齐到字母条实际位置，
            // 否则触摸判定区与可见字母条错开（字母条贴底、顶部几个字母摸不到）
            syncStripToBar()
        }
        view.fadeInBar()
        view.fadeInBackdrop()
        view.setAnchorY(y)
        view.selectByY(y)
        if (config.vibrateOn()) Haptics.tick(context, config.vibrateStrength, null)
        XLog.i("索引浮层已激活：${AppRepository.groups().size} 个字母，${loaded.size} 个应用，侧=${if (fromRight) "右" else "左"}")
    }

    /** 把呼出侧感应带对齐到字母条的实际屏幕位置（伪横屏场景专用） */
    private fun syncStripToBar() {
        val view = overlay ?: return
        val rect = view.barScreenRect()
        if (rect.isEmpty) return
        for (strip in strips) {
            if (strip.isRight != fromRight) continue
            try {
                val lp = strip.view.layoutParams as? WindowManager.LayoutParams ?: continue
                if (lp.height == rect.height() && lp.y == rect.top) return
                lp.height = rect.height()
                lp.y = rect.top
                windowManager.updateViewLayout(strip.view, lp)
                strip.height = lp.height
                strip.y = lp.y
                XLog.d("感应带已对齐字母条：${lp.width}×${lp.height} @${lp.x},${lp.y}")
            } catch (t: Throwable) {
                XLog.d("感应带对齐失败：" + t.javaClass.simpleName)
            }
        }
    }

    /**
     * 背景模式落到浮层窗口上：
     *  - 沉浸模式（immersive）：呼出背景 = 模糊桌面壁纸，浮层不需要系统模糊；
     *  - 非沉浸：不提取壁纸，给浮层开 FLAG_BLUR_BEHIND，系统直接把背后的真实内容模糊掉
     *    （模糊半径 16dp，与旧版窗口模糊一致）。
     *    设备不支持跨窗口模糊时自动退回纯遮罩。
     * 配置热更新 / 窗口重建都会走到这里；手势进行中心跳会提前 return，不会中途改 flag。
     */
    private fun applyBackgroundMode(view: IndexOverlayView) {
        try {
            val lp = view.layoutParams as? WindowManager.LayoutParams ?: return
            val blurBehind = !config.immersive && isBlurSupported()
            if (blurBehind) {
                lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                lp.blurBehindRadius = Display.dp(context, 16)
            } else {
                lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
                lp.blurBehindRadius = 0
            }
            if (view.parent != null) windowManager.updateViewLayout(view, lp)
        } catch (t: Throwable) {
            XLog.d("背景模式应用失败：" + t.javaClass.simpleName)
        }
    }

    private fun isBlurSupported(): Boolean = try {
        windowManager.isCrossWindowBlurEnabled
    } catch (t: Throwable) {
        false
    }

    /**
     * 索引浮层窗口常驻：以前每次手势都 addView/removeView 一个全屏窗口
     * （WMS binder + Surface 创建销毁，每次几十毫秒），这是"呼出不跟手"的大头。
     * 现在启动时创建一次，之后只切换可见性、内容与透明度。
     */
    private fun ensureOverlayWindow(): IndexOverlayView? {
        overlay?.let { if (it.parent != null) return it }
        val view = IndexOverlayView(context)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            try {
                if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
            } catch (t: Throwable) {
                XLog.d("setFitInsetsTypes 不可用：${t.javaClass.simpleName}")
            }
            try {
                if (Build.VERSION.SDK_INT >= 28) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            } catch (t: Throwable) {
                XLog.d("刘海适配不可用：${t.javaClass.simpleName}")
            }
        }
        return try {
            windowManager.addView(view, lp)
            view.visibility = View.INVISIBLE
            // 模糊壁纸生成完成后（可能呼出期间才回来）直接换图
            BlurredWallpaper.setListener { bmp -> view.setBackgroundBitmap(bmp) }
            overlay = view
            applyBackgroundMode(view)
            XLog.i("索引浮层窗口已常驻：背景=" + if (config.immersive) "模糊壁纸" else "系统窗口模糊")
            view
        } catch (t: Throwable) {
            XLog.e("显示索引浮层失败", t)
            null
        }
    }

    /** 结束一次手势并收起浮层：异常路径与「事件流停滞」自愈共用 */
    private fun resetGesture(reason: String) {
        state = State.IDLE
        abortMirrorSwipe()
        migrated = false
        hovered = null
        try { overlay?.setHover(null) } catch (t: Throwable) { }
        hideOverlay(animated = false, reason = reason)
    }

    private fun hideOverlay(animated: Boolean, reason: String = "") {
        val view = overlay ?: return
        overlayVisible = false
        XLog.i("收起浮层：$reason（${if (animated) "淡出" else "立即"}）")
        if (animated) {
            view.dismissAnimated()
            handler.postDelayed({ if (!overlayVisible) view.visibility = View.INVISIBLE }, 150L)
        } else {
            view.hideImmediately()
            view.visibility = View.INVISIBLE
        }
    }

    private fun removeOverlayWindow(view: IndexOverlayView) {
        try { windowManager.removeView(view) } catch (t: Throwable) { XLog.d("移除索引浮层失败：$t") }
        if (overlay === view) overlay = null
    }

    /** 窗口常驻后这里只负责"被系统回收了就重建" */
    private fun cleanupStaleOverlay() {
        val view = overlay
        if (view != null && view.parent == null) {
            XLog.i("索引浮层窗口被系统回收，重建")
            overlay = null
            ensureOverlayWindow()
        }
    }

    private fun activationDistancePx(): Float {
        // 灵敏度越高，触发所需滑动距离越小（更跟手）
        // 1.32.5：以 40dp 为基准，灵敏度只做 ±8dp 微调。
        // 1.32.7：竖屏基准抬到 45dp（防误触再收紧）；横屏保持 40dp 不动。
        val sens = config.sensitivity.coerceIn(0, 100)
        val base = if (Display.isLandscape(context, windowManager)) TRIGGER_DISTANCE_DP_LANDSCAPE
                   else TRIGGER_DISTANCE_DP_PORTRAIT
        val dp = base - (sens - 50) / 50f * TRIGGER_DISTANCE_RANGE_DP
        return Display.dp(context, dp.toInt().coerceAtLeast(24)).toFloat()
    }

    private var lastBackdropFixAt = 0L

    /**
     * 遮罩自愈（1.31.17）：颜色不是 0x66000000 就补回去。
     *
     * 修的是群友反馈的「全局模式滑动途中遮罩变透明、之后一直透明」。
     * 代码里没有把 scrim 改成透明的路径（只在 addView 与 prepare() 赋值），
     * 只能是被系统回收了 RenderNode 背景 —— 跨窗口模糊 + 长时间动画叠加时偶发、不可复现。
     * 所以按节流主动比对；getBackgroundColor 是本地字段读取，不碰 binder。
     * 自愈失败绝不能影响手势：异常吞掉，下一次再试。
     */
    private fun fixBackdropIfNeeded() {
        val now = System.currentTimeMillis()
        if (now - lastBackdropFixAt < BACKDROP_FIX_INTERVAL_MS) return
        lastBackdropFixAt = now
        val view = overlay ?: return
        try {
            if (view.ensureBackdrop()) {
                XLog.w("遮罩被改成透明，已补回（自愈）")
            }
        } catch (t: Throwable) {
        }
    }

    private var lastSelfCheckAt = 0L

    /**
     * 模块被卸载后，SystemUI 里已注入的代码还会活到下次重启：
     * 趁这段时间把 Settings 里的残留配置清掉，避免重装时读到旧值。
     */
    private fun selfCheck() {
        val now = System.currentTimeMillis()
        if (now - lastSelfCheckAt < 30_000) return
        lastSelfCheckAt = now
        val installed = try {
            context.packageManager.getPackageInfo(Const.MODULE_PACKAGE, 0)
            true
        } catch (t: Throwable) {
            false
        }
        if (installed) return
        var cleared = 0
        for (key in listOf("eta_sideindex_config", "eta_sideindex_star")) {
            try {
                if (android.provider.Settings.Secure.getString(context.contentResolver, key) != null) {
                    android.provider.Settings.Secure.putString(context.contentResolver, key, null)
                    cleared++
                }
            } catch (t: Throwable) {
            }
        }
        XLog.i("模块已卸载，清理残留配置：$cleared 项")
    }

    private companion object {
        const val TICK_MS = 2500L

        /**
         * 呼出所需的最小竖直位移（dp），默认灵敏度下就是它（1.32.5）。
         *
         * 以前这个门槛由灵敏度直接算出来：`10 - sens*0.07` → 3~10dp（≈9~30px）。
         * 也就是手指往下挪十几像素就算「滑动了」，判据擦边到经不起任何抖动：
         * 用户实测「不经意的返回手势偶尔会把索引弹出来」，事后又难复现 —— 就是这种擦边判定。
         * 抬到 40dp（≈120px @3x）后，一根横向为主的手势根本走不到这个位移。
         */
        const val TRIGGER_DISTANCE_DP_LANDSCAPE = 40f

        /** 竖屏触发距离基准（1.32.7 抬到 45dp；横屏保持 40dp 不动）。 */
        const val TRIGGER_DISTANCE_DP_PORTRAIT = 45f

        /** 灵敏度对触发距离的微调幅度（dp）：灵敏度 100 → -8dp，0 → +8dp。 */
        const val TRIGGER_DISTANCE_RANGE_DP = 8f

        /**
         * 桌面模式的竖直度要求：|dy| >= |dx| * 1.5（1.32.5 起；以前是 1f）。
         *
         * 桌面判定本身会有误判（顶层是桌面 + 小窗/分屏时也按桌面处理），
         * 所以这里不能再容忍「斜着滑」：横滑（桌面翻页 / 系统返回）必须完全走不掉。
         */
        const val STRAIGHT_LAUNCHER = 1.5f

        /** 应用内的竖直度要求（给系统返回手势让路，沿用旧值）。 */
        const val STRAIGHT_APP = 2f


        /** 遮罩自愈的最小间隔（ms）。手势每帧都会走，必须节流。 */
        const val BACKDROP_FIX_INTERVAL_MS = 300L

        /**
         * 「穿透看护」轮询间隔。
         *
         * 感应带处于触摸穿透（仅桌面模式 + 前台非桌面）时，触摸模式原本只由心跳同步：
         * 从应用回到桌面后最长 [TICK_MS] 里感应带还是穿透的 —— 这期间侧滑根本到不了模块，
         * 触摸落给桌面，被桌面的「桌面下滑打开控制中心」接走
         * （实测：QQ／相册看图 → 回桌面立刻侧滑会拉下控制中心，等一秒再滑就正常）。
         * 所以穿透期间用后台线程按这个间隔单独查顶层任务，一回到桌面立刻恢复可触摸。
         */
        const val WATCH_MS = 200L

        /** 横滑镜像：注入起点相对手指按下点往屏幕内侧挪这么多 dp（避开感应带窗口自己） */
        const val MIRROR_INSET_DP = 100

        /** 镜像 MOVE 的最小间隔：手指 ~120Hz，注入不必每个事件都跟 */
        const val MIRROR_MIN_MS = 10L
        /** 横屏进入后的静默期：这段时间内不响应呼出 */
        const val LANDSCAPE_GATE_MS = 1500L
        /** 息屏时的兜底心跳间隔 */
        const val TICK_IDLE_MS = 10_000L
        /** 手势进行中多久没收到任何事件就判定事件流断了（UP 被系统丢弃）：强制收起复位 */
        const val GESTURE_STALL_MS = 5000L
    }
}
