package com.eta.aicybox.hook

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import com.eta.aicybox.config.SharedStateProtocol
import com.eta.aicybox.R
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.display.DisplayManager
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import io.github.libxposed.api.XposedModule
import com.eta.aicybox.config.ModuleSettingsSnapshot
import com.eta.aicybox.hook.logFiltered

/**
 * @author bomo 统一圆形气泡（SystemUI 注入版，2026-10-17 用户拍板换掉悬浮窗方案）。
 *
 * 在 SystemUI 进程里用 [WindowManager] 创建**圆形气泡窗口**（`TYPE_STATUS_BAR_PANEL` 层，
 * 不受悬浮窗权限限制、不被普通全屏应用盖住、App 被杀后台也不失效，因为逻辑在 SystemUI 进程）。
 *
 * 手势：**下滑 / 右滑消失**（上滑预留未来功能）；点击触发 [onClick]。
 *
 * 当前首接**手电筒气泡**：监听 CameraManager 手电筒 → 开灯弹气泡（圆形黄底），
 * 点击关灯+气泡消失；滑动只消气泡。
 */
@SuppressLint("DiscouragedPrivateApi", "PrivateApi", "RtlHardcoded", "WrongConstant")
internal class SystemUiBubbleMonitor(
    private val context: Context,
    private val module: XposedModule,
    private val configuration: ProcessConfiguration,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager = context.getSystemService(WindowManager::class.java)
        ?: error("WindowManager unavailable")
    private val density = context.resources.displayMetrics.density

    private var settings = ModuleSettingsSnapshot(enabled = false)
    private var bubbleView: View? = null
    private var bubbleLayoutParams: WindowManager.LayoutParams? = null

    /** @author bomo 2026-10-19 横屏监听（横屏直接屏蔽气泡，用户明确不要开关）。 */
    private var displayManager: DisplayManager? = null
    private var displayListener: DisplayManager.DisplayListener? = null

    // 手电筒监听
    private var cameraManager: CameraManager? = null
    private var torchCallback: CameraManager.TorchCallback? = null
    private var flashlightOn = false

    // 手势状态
    private var downX = 0f
    private var downY = 0f
    private var fingerDown = false
    private var moved = false
    private var onClick: (() -> Unit)? = null
    private var autoDismissRunnable: Runnable? = null

    /** 一次气泡展示请求（图标 + 点击回调 + 自动消失时长，0 = 常驻）。 */
    private class BubbleRequest(
        val icon: Drawable,
        val onClick: () -> Unit,
        val autoDismissMs: Long,
        /** @author bomo 2026-10-22(用户要求)：气泡归属应用（QQ / 微信）。非空时，该应用一进入前台就收掉气泡。 */
        val ownerPackage: String? = null,
    )

    private enum class BubbleLayer { TEMP, BASE }

    /** 常驻层：手电筒气泡。被临时气泡盖住后仍记着，临时气泡退场要恢复它。 */
    private var baseBubble: BubbleRequest? = null

    /** 当前展示的临时气泡（正在计时的那条）。 */
    private var activeTemp: BubbleRequest? = null

    /** 排队等展示的临时气泡（FIFO，满了丢最旧的），保证「旧气泡不被提前顶掉」。 */
    private val tempQueue = ArrayDeque<BubbleRequest>()

    /** 当前展示的是哪一层。 */
    private var shownLayer: BubbleLayer? = null
    private var aicyFeatures: SystemUiAicyFeatures? = null

    fun start() {
        // 气泡开关来自共享配置（App 经 ConfigPushReceiver 推送）
        aicyFeatures = SystemUiAicyFeatures(context, module, this).also { it.onSettingsChanged(configuration.snapshot) }
        configuration.observe { snapshot ->
            if (Looper.myLooper() == Looper.getMainLooper()) acceptSettings(snapshot)
            else mainHandler.post { acceptSettings(snapshot) }
        }
        acceptSettings(configuration.snapshot)
        registerKeepAwakeReceiver()
        registerOrientationListener()
        module.logFiltered(Log.INFO, TAG, "SYSTEMUI_BUBBLE_READY")
    }

    private fun acceptSettings(snapshot: ModuleSettingsSnapshot) {
        val prev = settings
        settings = snapshot
        // 手电筒气泡
        val flashOn = snapshot.aicyBubblesEnabled && snapshot.flashlightBubbleEnabled
        if (flashOn) ensureFlashlightListener()
        else removeFlashlightListener()
        aicyFeatures?.onSettingsChanged(snapshot)
    }


    private fun registerKeepAwakeReceiver() {
        // @author bomo system_server 检测到"半暗中点亮屏幕"后广播，此处弹常亮气泡
        val filter = IntentFilter(SharedStateProtocol.ACTION_KEEP_AWAKE_TRIGGER)
        context.registerReceiver(keepAwakeReceiver, filter, Context.RECEIVER_EXPORTED)
        // 2026-10-18 息屏必隐藏所有气泡：挂着的 2003 悬浮窗可能挡在 ColorOS 锁屏前抢占输入
        // （用户连续几次"息屏后点不亮/电源键唤不醒"的最大嫌疑，关掉常亮气泡就不复现）。
        // 息屏瞬间强制收掉气泡窗口，避免任何窗口残留锁屏上。
        val offFilter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        context.registerReceiver(screenOffReceiver, offFilter, Context.RECEIVER_EXPORTED)
        // @author bomo 2026-10-19 用户反馈「手电筒气泡熄屏再亮屏就没了」：
        // 息屏时按「锁屏上不留悬浮窗」统一隐藏了气泡，亮屏必须把还亮着的灯的气泡补回来。
        val onFilter = IntentFilter(Intent.ACTION_SCREEN_ON)
        context.registerReceiver(screenOnReceiver, onFilter, Context.RECEIVER_EXPORTED)
    }

    /** 亮屏补回手电筒气泡。 */
    private val screenOnReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != Intent.ACTION_SCREEN_ON) return
                mainHandler.postDelayed(
                    {
                        if (flashlightOn &&
                            settings.aicyBubblesEnabled &&
                            settings.flashlightBubbleEnabled
                        ) {
                            module.logFiltered(Log.DEBUG, TAG, "SCREEN_ON -> RESTORE_FLASHLIGHT_BUBBLE")
                            onFlashlightChanged(true)
                        }
                    },
                    SCREEN_ON_RESTORE_DELAY_MS,
                )
            }
        }

    private val keepAwakeReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == SharedStateProtocol.ACTION_KEEP_AWAKE_TRIGGER) {
                    aicyFeatures?.onKeepAwakeTrigger()
                }
            }
        }

    private val screenOffReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_SCREEN_OFF) {
                    // 息屏：隐藏所有气泡窗口（含手电筒，避免锁屏上有悬浮窗）
                    module.logFiltered(Log.DEBUG, TAG, "SCREEN_OFF -> HIDE_ALL_BUBBLES")
                    hideBubble()
                    aicyFeatures?.onScreenOffRelease()
                }
            }
        }
    /** @author bomo 2026-10-19：SystemUI hook 通知 → 弹 QQ 联系人气泡（免授权方案）。 */
    internal fun onExternalQqNotification(sbn: android.service.notification.StatusBarNotification) {
        mainHandler.post {
            aicyFeatures?.showQqBubbleFromSbn(sbn)
            aicyFeatures?.showWxBubbleFromSbn(sbn)
        }
    }

    // ---------- 手电筒 ----------

    private fun ensureFlashlightListener() {
        if (cameraManager != null) return
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
        val cb =
            object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                    if (!hasFlashUnit(cm, cameraId)) return
                    if (enabled == flashlightOn) return
                    flashlightOn = enabled
                    module.logFiltered(
                        Log.DEBUG, TAG, "TORCH_MODE camera=$cameraId on=$enabled",
                    )
                    onFlashlightChanged(enabled)
                }
            }
        try {
            cm.registerTorchCallback(cb, null)
            cameraManager = cm
            torchCallback = cb
            module.logFiltered(Log.INFO, TAG, "FLASHLIGHT_BUBBLE_LISTENER_READY")
        } catch (e: Exception) {
            module.logFiltered(Log.WARN, TAG, "FLASHLIGHT_BUBBLE_LISTENER_FAIL", e)
        }
    }

    private fun removeFlashlightListener() {
        torchCallback?.let { cameraManager?.unregisterTorchCallback(it) }
        cameraManager = null
        torchCallback = null
        flashlightOn = false
        hideBubbleNow()
    }

    private fun hasFlashUnit(cm: CameraManager, cameraId: String): Boolean =
        try {
            cm.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
        } catch (_: Exception) {
            false
        }

    /** @author bomo 2026-10-18：从模块 APK 加载 drawable 资源（SystemUI 进程跨包读取）。 */
    private fun moduleDrawable(resId: Int): Drawable? =
        try {
            val moduleCtx =
                context.createPackageContext(
                    SharedStateProtocol.MODULE_PACKAGE,
                    Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY,
                )
            moduleCtx.getDrawable(resId)
        } catch (_: Exception) {
            null
        }

    private fun onFlashlightChanged(on: Boolean) {
        if (!on) {
            // @author bomo 2026-10-18：用户反馈「手电筒关闭后气泡仍存在」。改为关灯即隐藏气泡。
            // 0.2.62：只收常驻层 —— 关灯不能顺带把正在展示的临时气泡（安装/链接提示）也抹掉。
            clearBaseBubble()
            return
        }
        showBubble(
            // @author bomo 2026-10-18：换用户提供的手电筒图标资源（flashlight_bubble，跨包加载模块资源）
            icon = moduleDrawable(R.drawable.flashlight_bubble) ?: circleColor(Color.argb(255, 255, 208, 0)),
            onClick = {
                // 点击关灯同时隐藏气泡（手电筒气泡常驻直到关闭，关闭即消失）
                turnOffFlashlight()
                hideBubble()
            },
        )
    }

    private fun turnOffFlashlight() {
        val cm = cameraManager ?: return
        try {
            val id =
                cm.cameraIdList.firstOrNull {
                    hasFlashUnit(cm, it) && it.startsWith("0")
                } ?: return
            cm.setTorchMode(id, false)
        } catch (_: Exception) {
        }
    }

    // ---------- 消息气泡（QQ / 微信）：进入归属应用即消失 ----------

    // @author bomo 2026-10-22(用户要求)：来 QQ/微信消息时左下角弹气泡；用户从这个应用进去（点通知或点图标）
    // 之后气泡就没意义了 —— 盯前台顶层任务，一旦是归属应用立刻收掉，不用等 autoDismiss。
    private var msgWatchOwner: String? = null

    // 前台检测要 exec dumpsys，放独立线程跑，别在 SystemUI 主线程上抖动画
    private var msgWatchHandler: Handler? = null

    private val msgWatchRunnable =
        object : Runnable {
            override fun run() {
                val owner = msgWatchOwner ?: return
                val top = currentTopTaskPackage()
                if (top != null && top == owner) {
                    module.logFiltered(Log.DEBUG, TAG, "MSG_BUBBLE_ENTER_APP $owner -> HIDE")
                    hideMsgBubble(owner)
                    return
                }
                msgWatchHandler?.postDelayed(this, MSG_WATCH_INTERVAL_MS)
            }
        }

    private fun ensureMsgWatchHandler(): Handler {
        msgWatchHandler?.let { return it }
        val thread = android.os.HandlerThread("aicy-msg-watch")
        thread.start()
        return Handler(thread.looper).also { msgWatchHandler = it }
    }

    private fun startMsgWatch(owner: String) {
        msgWatchOwner = owner
        val handler = ensureMsgWatchHandler()
        handler.removeCallbacks(msgWatchRunnable)
        handler.postDelayed(msgWatchRunnable, MSG_WATCH_INTERVAL_MS)
    }

    private fun stopMsgWatch() {
        msgWatchOwner = null
        msgWatchHandler?.removeCallbacks(msgWatchRunnable)
    }

    /**
     * @author bomo 2026-10-22(用户要求)：收掉归属该应用的消息气泡。
     *
     * 两路触发：① 前台顶层任务变成该应用（msgWatchRunnable）；② 该应用的通知被移除（点通知进去时
     * 通知会 cancel，由 onMsgNotificationRemoved 调进来）。
     */
    internal fun hideMsgBubble(owner: String) {
        mainHandler.post {
            tempQueue.removeAll { it.ownerPackage == owner }
            val shown = activeTemp
            if (shown != null && shown.ownerPackage == owner) {
                module.logFiltered(Log.DEBUG, TAG, "MSG_BUBBLE_DISMISS owner=$owner")
                finishTempBubble()
            } else if (msgWatchOwner == owner) {
                stopMsgWatch()
            }
        }
    }

    /** @author bomo 2026-10-22：通知被移除（点击 / 清掉）→ 归属该应用的消息气泡一起收。 */
    internal fun onMsgNotificationRemoved(pkg: String) {
        mainHandler.post {
            tempQueue.removeAll { it.ownerPackage == pkg }
            val shown = activeTemp
            if (shown != null && shown.ownerPackage == pkg) {
                module.logFiltered(Log.DEBUG, TAG, "MSG_BUBBLE_NOTIFY_REMOVED $pkg -> HIDE")
                finishTempBubble()
            }
        }
    }

    /**
     * @author bomo 2026-10-22：当前顶层任务包名（读 dumpsys activity activities 开头，取第一个 Task）。
     *
     * 与 AicyFeatures.foregroundApp() 的区别：这里不做「全屏 bounds」判定 —— 小窗打开聊天也算进入应用；
     * 只在消息气泡存活的那几秒里每 700ms 调一次，读到第一个 Task 就断开，开销可控。
     */
    private fun currentTopTaskPackage(): String? {
        var process: Process? = null
        return try {
            process = Runtime.getRuntime().exec(arrayOf("/system/bin/dumpsys", "activity", "activities"))
            val reader = java.io.BufferedReader(java.io.InputStreamReader(process.inputStream))
            var result: String? = null
            var read = 0
            while (read < 400) {
                val line = reader.readLine() ?: break
                read++
                if (line.length > 4000) continue
                if (!line.trim().startsWith("* Task{")) continue
                val aIdx = line.indexOf(" A=")
                if (aIdx >= 0) {
                    var part = line.substring(aIdx + 3)
                    val colon = part.indexOf(':')
                    if (colon >= 0) part = part.substring(colon + 1)
                    result = part.trim().substringBefore(' ').substringBefore('}')
                }
                break
            }
            runCatching { reader.close() }
            result
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { process?.destroy() }
        }
    }

    // ---------- 气泡窗口 ----------

    internal fun showBubble(
        icon: Drawable,
        onClick: () -> Unit,
        autoDismissMs: Long = 0L,
        ownerPackage: String? = null,
    ) {
        mainHandler.post {
            val request = BubbleRequest(icon, onClick, autoDismissMs, ownerPackage)
            if (autoDismissMs <= 0L) {
                // @author bomo 2026-10-19 常驻气泡（手电筒）：只占「底层」。
                // 正在展示临时气泡时不抢占，等它满后退场再把手电筒图标放回来
                // （用户：手电筒气泡不能被覆盖；只有关灯才能消失）。
                baseBubble = request
                if (activeTemp == null) showNow(request, BubbleLayer.BASE)
                return@post
            }
            // 临时气泡：**绝不顶掉还没到时间的当前气泡**，排队等它自己到点退场。
            if (activeTemp == null) {
                showNow(request, BubbleLayer.TEMP)
            } else {
                if (tempQueue.size >= BUBBLE_QUEUE_MAX) tempQueue.removeFirstOrNull()
                tempQueue.addLast(request)
                module.logFiltered(Log.DEBUG, TAG, "BUBBLE_QUEUED size=${tempQueue.size}")
            }
        }
    }

    /** @author bomo 带自动消失的气泡（链接/安装/常亮等临时提示用）。 */
    internal fun showBubbleTemporary(
        icon: Drawable,
        onClick: () -> Unit,
        autoDismissMs: Long,
        ownerPackage: String? = null,
    ) {
        showBubble(icon, onClick, autoDismissMs, ownerPackage)
    }

    /**
     * @author bomo 2026-10-19 展示一条气泡（复用同一个窗口）。
     *
     * [BubbleLayer.TEMP] 会挂上自动消失计时；[BubbleLayer.BASE] 是常驻层（手电筒），不计时。
     */
    private fun showNow(
        request: BubbleRequest,
        layer: BubbleLayer,
    ) {
        if (isLandscapeNow()) {
            // 用户 2026-10-19：横屏（游戏 / 全屏视频）直接屏蔽气泡，不提供开关。
            module.logFiltered(Log.DEBUG, TAG, "BUBBLE_SKIP_LANDSCAPE")
            return
        }
        removeAutoDismiss()
        shownLayer = layer
        // @author bomo 2026-10-22(用户要求)：点消息气泡本身就是进聊天 -> 先收气泡再启动小窗。
        onClick =
            if (request.ownerPackage != null) {
                {
                    stopMsgWatch()
                    runCatching { request.onClick.invoke() }
                    if (shownLayer == BubbleLayer.TEMP) finishTempBubble()
                }
            } else {
                request.onClick
            }
        if (layer == BubbleLayer.TEMP) {
            activeTemp = request
            // 消息气泡：盯住归属应用，它一进前台就收掉（不用等自动消失）
            if (request.ownerPackage != null) startMsgWatch(request.ownerPackage) else stopMsgWatch()
            if (request.autoDismissMs > 0L) {
                val runnable = Runnable { finishTempBubble() }
                autoDismissRunnable = runnable
                mainHandler.postDelayed(runnable, request.autoDismissMs)
            }
        } else {
            activeTemp = null
        }
        val existing = bubbleView as? BubbleWindowView
        if (existing != null) {
            existing.setDrawable(request.icon)
            return
        }
        val sizePx = (BUBBLE_SIZE_DP * density).toInt()
        val view = BubbleWindowView(context).apply { setDrawable(request.icon) }
        val params =
            WindowManager.LayoutParams(
                sizePx,
                sizePx,
                CORNER_BUBBLE_WINDOW_TYPE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                x = (settings.bubblePosXDp * density).toInt()
                y = (settings.bubblePosYDp * density).toInt()
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                setFitInsetsTypes(0)
            }
        view.setOnTouchListener { v, ev -> onBubbleTouch(v, ev) }
        try {
            windowManager.addView(view, params)
            bubbleView = view
            bubbleLayoutParams = params
        } catch (e: Exception) {
            bubbleView = null
            shownLayer = null
            module.logFiltered(Log.WARN, TAG, "BUBBLE_ADD_VIEW_FAIL", e)
        }
    }

    // ---------- 横屏屏蔽 ----------

    /**
     * @author bomo 2026-10-19 横屏（游戏 / 全屏视频）直接屏蔽气泡，不做开关。
     *
     * 判据用显示旋转：SystemUI 的 resources 配置在旋转时不一定同步刷新，旋转角最可靠。
     */
    private fun isLandscapeNow(): Boolean {
        val rotation = runCatching { windowManager.defaultDisplay.rotation }.getOrNull()
        if (rotation != null) return rotation != android.view.Surface.ROTATION_0
        val metrics = context.resources.displayMetrics
        return metrics.widthPixels > metrics.heightPixels
    }

    private fun registerOrientationListener() {
        val manager = context.getSystemService(DisplayManager::class.java) ?: return
        displayManager = manager
        val listener =
            object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) = Unit

                override fun onDisplayRemoved(displayId: Int) = Unit

                override fun onDisplayChanged(displayId: Int) {
                    if (displayId != android.view.Display.DEFAULT_DISPLAY) return
                    mainHandler.post { syncBubbleOrientation() }
                }
            }
        displayListener = listener
        manager.registerDisplayListener(listener, mainHandler)
    }

    /** 转横屏时收掉气泡窗口（手电筒气泡只收窗口、状态留着），转回竖屏再放回来。 */
    private fun syncBubbleOrientation() {
        if (isLandscapeNow()) {
            if (bubbleView != null) {
                module.logFiltered(Log.DEBUG, TAG, "BUBBLE_HIDE_LANDSCAPE")
                removeAutoDismiss()
                tempQueue.clear()
                activeTemp = null
                removeBubbleWindow()
            }
            return
        }
        if (bubbleView == null && activeTemp == null) {
            baseBubble?.let { request -> showNow(request, BubbleLayer.BASE) }
        }
    }

    /** 临时气泡到点：先放队列里的下一条，没有就恢复手电筒，再没有才真收窗口。 */
    private fun finishTempBubble() {
        stopMsgWatch()
        removeAutoDismiss()
        activeTemp = null
        val next = tempQueue.removeFirstOrNull()
        if (next != null) {
            showNow(next, BubbleLayer.TEMP)
            return
        }
        val base = baseBubble
        if (base != null) {
            showNow(base, BubbleLayer.BASE)
            return
        }
        removeBubbleWindow()
    }

    /** 用户在当前气泡上点击/滑动：临时气泡按「到点」处理，常驻气泡直接收掉。 */
    private fun dismissShownBubble() {
        if (shownLayer == BubbleLayer.TEMP) finishTempBubble() else hideBubbleNow()
    }

    private fun removeAutoDismiss() {
        autoDismissRunnable?.let { mainHandler.removeCallbacks(it) }
        autoDismissRunnable = null
    }

    private fun removeBubbleWindow() {
        stopMsgWatch()
        shownLayer = null
        onClick = null
        val v = bubbleView ?: return
        bubbleView = null
        try {
            windowManager.removeView(v)
        } catch (_: Exception) {
        }
    }

    /** 立刻收掉所有气泡（点击/滑动/息屏用），连带清空队列与常驻层。 */
    private fun hideBubbleNow() {
        removeAutoDismiss()
        tempQueue.clear()
        activeTemp = null
        baseBubble = null
        removeBubbleWindow()
    }

    private fun hideBubble() {
        mainHandler.post { hideBubbleNow() }
    }

    /** 只收掉常驻层（关手电筒）：正在展示的临时气泡不受影响。 */
    private fun clearBaseBubble() {
        baseBubble = null
        if (shownLayer == BubbleLayer.BASE) hideBubbleNow()
    }

    private fun onBubbleTouch(view: View, event: MotionEvent): Boolean {
        // 2026-10-18 用户复批定稿（第5版手势）：
        // - 下滑 / 右滑（主方向）只要位移超阈值就消失（**不要求松手在气泡外**，之前太严格导致"划不掉"）；
        //   这样手电筒气泡也能用下滑/右滑划掉。
        // - 上滑 / 左滑绝不消失。
        // - 未滑动在气泡上松手 → 点击。
        // - 按下放大反馈。
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                fingerDown = true
                moved = false
                view.setPressed(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!fingerDown) return true
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                val threshold = SWIPE_THRESHOLD_DP * density
                if (Math.abs(dx) > threshold || Math.abs(dy) > threshold) moved = true
                // 下滑 / 右滑主导且超阈值 → 立即消失（所有气泡通用，包括手电筒）
                if (moved && dismissDirection(dx, dy)) {
                    fingerDown = false
                    view.setPressed(false)
                    dismissShownBubble()
                    return true
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasDown = fingerDown
                fingerDown = false
                view.setPressed(false)
                if (!wasDown) return true
                // 未滑动（位移未超阈值）→ 点击
                if (!moved) {
                    val cb = onClick
                    // 点击临时气泡 → 只推进队列/恢复手电筒；点击手电筒 → 整体收掉。
                    dismissShownBubble()
                    // @author bomo 2026-10-21 用户要求：点击 Aicy 气泡要有震动反馈。
                    com.eta.aicybox.sideindex.util.Haptics.tick(context, 80, view)
                    cb?.invoke()
                    return true
                }
                // 上滑/左滑（不消失）→ 无操作，只做移动结束处理
                return true
            }
        }
        return false
    }

    /** 主方向是否为 下滑 / 右滑（决定性手势，触发消失）。 */
    private fun dismissDirection(dx: Float, dy: Float): Boolean {
        // 纵向为主：只有 dy>threshold 才下滑；横向为主：只有 dx>threshold 才右滑
        val aDx = kotlin.math.abs(dx)
        val aDy = kotlin.math.abs(dy)
        return if (aDy >= aDx) dy > 0 else dx > 0
    }

private fun circleColor(color: Int): Drawable {
        val sizePx = (BUBBLE_SIZE_DP * density).toInt()
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        return BitmapDrawable(context.resources, bmp)
    }

    private companion object {
        const val TAG = "SystemUiBubble"
        const val CORNER_BUBBLE_WINDOW_TYPE = 2003 // TYPE_SYSTEM_ALERT，参考欧泡小窗同款窗口类型（2003 比 2024 更稳，实测能显示且不闪）
        const val BUBBLE_SIZE_DP = 47.5f

        /** @author bomo 2026-10-19 临时气泡排队上限（超出丢最旧的），防止刷屏式连发。 */
        const val BUBBLE_QUEUE_MAX = 3

        /** @author bomo 2026-10-22 消息气泡的前台检测间隔（ms）：进 QQ/微信后要"立刻"消失，700ms 内收掉。 */
        const val MSG_WATCH_INTERVAL_MS = 700L
        const val BUBBLE_MARGIN_DP = 16
        /** @author bomo 滑动判定阈值（dp）：超过才是滑动（2026-10-18 用户要求加大，杜绝轻滑误判）。 */
        const val SWIPE_THRESHOLD_DP = 18f
        /** @author bomo 松手位置判定时气泡范围的额外容差（dp），手指滑出这个范围才算消失。 */
        const val BUBBLE_LEAVE_SLOP_DP = 20f
        /** @author bomo 亮屏后等状态栏收起再把被息屏隐藏的手电筒气泡补回来。 */
        const val SCREEN_ON_RESTORE_DELAY_MS = 600L
    }
}

/** SystemUI 气泡的圆形绘制 View（2026-10-18 改：Flyme 小窗式圆形应用图标，去掉了方块图标）。 */
@android.annotation.SuppressLint("ViewConstructor")
private class BubbleWindowView(context: Context) : android.view.View(context) {
    private val ringPaint =
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = resources.displayMetrics.density * 2f
            color = android.graphics.Color.argb(0x4D, 0xFF, 0xFF, 0xFF)
        }
    private var drawable: android.graphics.drawable.Drawable? = null


    fun setDrawable(d: android.graphics.drawable.Drawable) {
        drawable = d.mutate().apply { isFilterBitmap = true }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        // 按下放大反馈：isPressed 时半径放大 ~15%（用户 2026-10-18 要求按下有反馈）
        val scale = if (isPressed) PRESSED_SCALE else 1f
        val r = (minOf(width, height) / 2f - resources.displayMetrics.density * 2f) * scale
        // 先裁圆再画图标 → 圆形的 Flyme 小窗样式（不再有方块）
        canvas.save()
        canvas.scale(scale, scale, cx, cy)
        // 用允许非自适应位图的圆形区域裁剪
        canvas.clipPath(
            android.graphics.Path().apply {
                addCircle(cx, cy, r, android.graphics.Path.Direction.CW)
            },
        )
        drawable?.let {
            it.setBounds(0, 0, width, height)
            it.draw(canvas)
        }
        canvas.restore()
        // 按下时描边加粗加亮，进一步强化反馈
        val ringColor =
            if (isPressed) {
                android.graphics.Color.argb(0x8C, 0xFF, 0xFF, 0xFF)
            } else {
                android.graphics.Color.argb(0x4D, 0xFF, 0xFF, 0xFF)
            }
        ringPaint.color = ringColor
        val ringStroke = resources.displayMetrics.density * (if (isPressed) 3f else 2f)
        ringPaint.strokeWidth = ringStroke
        canvas.drawCircle(cx, cy, r, ringPaint)
    }

    private companion object {
        const val PRESSED_SCALE = 1.15f
    }

}
