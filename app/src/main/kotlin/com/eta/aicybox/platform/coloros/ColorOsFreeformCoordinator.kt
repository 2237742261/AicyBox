package com.eta.aicybox.platform.coloros

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.WindowInsets
import com.eta.aicybox.config.ConfigSnapshotCodec
import com.eta.aicybox.config.ModulePreferences
import com.eta.aicybox.config.PayChannel
import com.eta.aicybox.config.SharedSettings
import java.util.ArrayDeque
import com.eta.aicybox.config.ToolCatalogCodec
import com.eta.aicybox.config.ModuleSettingsSnapshot
import com.eta.aicybox.config.SharedStateProtocol
import com.eta.aicybox.gesture.AdaptiveCornerGestureConfig
import com.eta.aicybox.gesture.CornerGestureConfig
import com.eta.aicybox.gesture.CornerGestureEngine
import com.eta.aicybox.gesture.CornerTriggerRegion
import com.eta.aicybox.gesture.GestureAction
import com.eta.aicybox.hook.ConfigPushReceiver
import com.eta.aicybox.hook.ModuleEnvironmentState
import com.eta.aicybox.hook.ProcessConfiguration
import com.eta.aicybox.window.CornerRadialOverlayView
import com.eta.aicybox.window.OverlaySafeInsets
import com.eta.aicybox.window.PayBarOverlayView
import java.lang.reflect.Proxy
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** system_server 中的唯一长期所有者：WMS 指针监听、Overlay、目录缓存与启动适配。 */
internal class ColorOsFreeformCoordinator(
    private val controller: Any,
    private val classLoader: ClassLoader,
    private val configuration: ProcessConfiguration,
    private val environmentState: ModuleEnvironmentState,
    private val logger: (priority: Int, code: String, throwable: Throwable?) -> Unit,
) : CornerRadialOverlayView.Listener {
    private val context = readField(controller, "mContext") as Context
    private val handler = Handler(Looper.getMainLooper())
    private val catalogExecutor =
        ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue<Runnable>(1),
            { task -> Thread(task, CATALOG_THREAD_NAME) },
            ThreadPoolExecutor.DiscardOldestPolicy(),
        )
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val gestureEngine = CornerGestureEngine()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val launcher = ColorOsFreeformLauncher(context)

    /** @author bomo 2026-10-19 支付栏（小窗下方那条支付方式切换栏）。 */
    private var payBarView: PayBarOverlayView? = null
    private val payBarDismissRunnable = Runnable { removePayBar() }

    /** 支付栏当前排序（点图标时用来同步选中态）。 */
    private var payBarOrder: List<PayChannel> = emptyList()

    /** 支付栏跟随小窗的轮询（贴位置 + 按小窗缩放 + 小窗消失即收栏）。 */
    private val payBarTickRunnable = Runnable { updatePayBar() }

    /**
     * @author bomo 0.2.73 系统验证弹窗（指纹 / 面容）盖上来的那段，把支付栏藏起来。
     *
     * 2026-10-19 `dumpsys window windows` 实测：盖住界面的是
     * `Window{… u0 BiometricPrompt}` —— package=com.android.systemui、mOwnerUid=10264、
     * type=KEYGUARD_DIALOG(2009)、fillxfill、fl=DIM_BEHIND|SECURE|SHOW_WHEN_LOCKED，
     * 也就是 SystemUI 的 AuthController 弹窗；第三方指纹支付插件最终也走这条系统通道。
     * 而 WMS 的窗口列表里它排在支付栏（type=2012）**之后**，所以靠改窗口类型躲不开。
     *
     * 我们本身跑在 system_server，直接翻 WMS 的 `mWindowMap` 最准：只要有一个可见的
     * KEYGUARD_DIALOG 窗口就藏栏。零 exec、零 IPC、不依赖 dumpsys。
     * （0.2.72 的 `dumpsys biometric` 轮询被 SELinux 挡住 system_server 的 exec，已删除。）
     */
    private var payBarAuthBlocked = false
    private var payBarAuthCheckedAt = 0L

    /**
     * @author bomo 2026-10-19 等小窗建出来再挂栏。
     *
     * 小窗是**异步创建**的；0.2.58/0.2.59 都是先 `addView`（默认落在屏幕底部）再靠轮询挪过去，
     * 用户看到的就是「支付栏从底端飞到小窗下方」。现在改成：先不挂窗，等拿到小窗 bounds 再挂，
     * 第一帧就在最终位置。
     */
    private val payBarAttachRunnable = Runnable { attachPayBarIfReady() }

    /** 支付栏窗口是否已经真正 addView 过（挂之前 `payBarView` 已经存在，用来占位）。 */
    private var payBarAttached = false

    /** 切换支付方式期间（旧窗已关、新窗还没出来）不许收栏。 */
    private var payBarSwitchUntil = 0L

    /** 连续几拍读不到小窗 bounds（小窗关闭动画期间会短暂为 null，所以不第一拍就收）。 */
    private var payBarMissingTicks = 0

    /**
     * 支付栏是否已经见过小窗（0.2.59 修）。
     *
     * ⚠️ 小窗是**异步创建**的：`startActivity` 返回时 flexible task 还不存在。
     * 0.2.58 在 addView 后立刻跑一次轮询，看到「没有小窗」就把栏收掉了 ——
     * 用户反馈的「重启后呼出聚合支付没有支付栏」就是这个。
     * 现在只有**先见过小窗、之后它消失**才收栏；从没见过则等宽限期 + 60s 兜底。
     */
    private var payBarSawWindow = false
    private var payBarShownAt = 0L

    /** 一次切换的令牌：切换被后一次点击覆盖时，旧的「补发一次」回调自动作废。 */
    private var paySwitchToken = 0

    /**
     * @author bomo 2026-10-19 用户正在拖动 / 缩放小窗：支付栏先藏起来，
     * 停手 [PAY_BAR_SETTLE_MS] 后直接出现在新位置。
     *
     * 之前是「栏留在原处、等松手后再跳过去」，用户嫌突兀。
     */
    private var payBarAdjusting = false
    private var payBarSettleAt = 0L
    private var payBarAdjustFromGesture = false

    /**
     * @author bomo 2026-10-19 小窗正在消失（关闭动画/读数断层）的抑制期。
     *
     * 修用户反馈的「关小窗时栏先消失、闪一下又出现、再消失」：关闭动画里 task 会短暂还有、
     * bounds 也会跳一下，被当成「刚停手」于是把栏放出来露一下。这段时间内一律不许显示。
     */
    private var payBarClosingUntil = 0L

    /** 停手后到真正显示之间的确认期结束时刻：这期间小窗要是没了就直接收栏。 */
    private var payBarConfirmUntil = 0L

    /** 栏真正挂上窗口的时刻：几何变化判拖动的宽限期从这里算。 */
    private var payBarAttachAt = 0L

    /** 当前小窗是否为迷你形态（迷你时不显示支付栏）。 */
    private var payBarMini = false

    /**
     * @author bomo 2026-10-19 切换支付方式时「新窗已经出现」+ 出现后再给 400ms 稳定期。
     *
     * 用户实测：栏内切支付方式希望支付栏**一直不消失**；但切换完再上滑关窗必须立刻消失。
     * 所以宽限保护只到「新窗出现后稳定」为止，之后一切恢复常规。
     */
    private var payBarSwitchSawNewWindow = false
    private var payBarSwitchSettleUntil = 0L

    /** @author bomo 0.2.70 「先开新窗、再关旧窗」用的一组状态。 */
    private var paySwitchBaseTaskId = -1
    private var paySwitchBaseActivity: String? = null
    private var payBarSwitchLandAt = 0L

    /** @author bomo 连续/过快点击支付栏时，1 秒内只响应一次切换（防连点导致多次关窗/开窗叠加出问题）。 */
    private var payBarSwitchBlockedUntil = 0L

    /** @author bomo 0.2.71 小窗被别的界面盖住了（支付宝指纹校验页等）→ 支付栏也一起藏起来。 */
    private var payBarCovered = false

    /** 覆盖判据的信号是否可信：先见过一次「小窗在最上层」才敢用（防止字段恒 false 把栏永久藏掉）。 */
    private var payBarCoverTrusted = false
    private var payBarGeometryKey: String? = null
    private var payBarGeometryStreak = 0
    private var payBarLastGeometryAt = 0L

    /** ColorOS 小窗状态读取/关闭（同一份反射也用于「切换支付方式时关旧窗」）。 */
    private val flexibleWindow = ColorOsFlexibleWindowAccess(controller, classLoader, logger)
    private val sidebar = ColorOsSidebarClient(context, handler, logger)
    @Volatile
    private var latestToolCatalog: String? = null

    /** 最近小窗内存列表（头部最新）；跨重启由配置播种，跨进程由 Settings.Global 供读。 */
    private val recentFreeformEntries = ArrayDeque<ComponentName>()

    /** 最近一次指针位置；工具执行时作为 PageRout 点击坐标传给侧边栏。 */
    @Volatile
    private var latestPointerX = 0f

    @Volatile
    private var latestPointerY = 0f

    private val appCatalog =
        ColorOsAppCatalog(
            context,
            catalogExecutor,
            logger,
            toolCatalog = { latestToolCatalog ?: configuration.readToolCatalog() },
        ) { snapshot ->
            handler.post {
                // 后台任务完成时配置可能已再次变化，旧结果不得覆盖新外观。
                if (snapshot.matches(lastSettings)) {
                    catalogSnapshot = snapshot
                    overlay?.updateRadialAppearance(snapshot)
                }
            }
        }
    init {
        // 侧边栏直发 App 的广播会被 ColorOS 后台启动管控拦截，改经 system_server 转发。
        sidebar.onToolCatalog = ::publishToolCatalogToApp
        // 「最近小窗」点击：侧边栏无法自建小窗，转回 system_server 走既有启动链路。
        sidebar.onRecentComponent = { component ->
            handler.post { publishRecentFreeform(component) }
        }
        sidebar.onLaunchComponent = { component ->
            handler.post {
                if (isGestureEnvironmentAllowed()) {
                    launchCommittedApp(RadialAppEntry(component, component.className, EMPTY_ICON))
                }
            }
        }
    }

    private val packageReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (lastSettings.enabled) appCatalog.refresh(lastSettings)
            }
        }

    @Volatile
    private var catalogSnapshot = AppCatalogSnapshot()
    private var pointerListener: Any? = null
    @Volatile
    private var pointerRegistered = false
    @Volatile
    private var pointerGeneration = 0L
    private var overlay: CornerRadialOverlayView? = null
    private var morePanelActive = false
    private var lastSettings = ModuleSettingsSnapshot(enabled = false)
    private var activeEnvironmentApproved = false
    private var activeGestureConfig: CornerGestureConfig? = null
    private val pointerQueueLock = Any()
    private var pendingMove: QueuedPointerEvent? = null
    private var movePosted = false
    private var lastOverlayFailureLogAt = -OVERLAY_FAILURE_LOG_INTERVAL_MS

    fun start() {
        handler.post {
            recentFreeformEntries.addAll(configuration.readRecentFreeform())
            registerToolRequestReceiver()
            registerConfigPushReceiver()
            registerCoordinatorProbeResponder()
            // 开机主动拉目录：Settings 副本不再依赖"用户先开一次面板"。
            sidebar.requestToolCatalog()
            environmentState.start(context)
            environmentState.observe { applySettings(configuration.snapshot) }
            registerPackageObserver()
            configuration.observe {
                handler.post { applySettings(configuration.snapshot) }
            }
        }
        logger(Log.INFO, "SYSTEM_GESTURE_COORDINATOR_READY", null)
    }

    private fun registerPackageObserver() {
        try {
            val filter =
                IntentFilter().apply {
                    addAction(Intent.ACTION_PACKAGE_ADDED)
                    addAction(Intent.ACTION_PACKAGE_CHANGED)
                    addAction(Intent.ACTION_PACKAGE_REMOVED)
                    addAction(Intent.ACTION_PACKAGE_REPLACED)
                    addDataScheme("package")
                }
            // Coordinator 与 system_server 同生命周期；热重载被拒绝，因此只注册一次。
            context.registerReceiver(packageReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } catch (exception: RuntimeException) {
            logger(Log.WARN, "PACKAGE_OBSERVER_UNAVAILABLE", exception)
        }
    }

    private fun applySettings(settings: ModuleSettingsSnapshot) {
        val selectionChanged =
            settings.pinsSaved != lastSettings.pinsSaved ||
                settings.pinnedComponents != lastSettings.pinnedComponents
        lastSettings = settings
        if (environmentState.isGestureAllowed(refreshKeyguard = true) && (settings.leftCornerEnabled || settings.rightCornerEnabled)) {
            val resuming = !pointerRegistered
            registerPointerListener()
            if (resuming || selectionChanged || !catalogSnapshot.matches(settings) || catalogSnapshot.radialApps.isEmpty()) {
                appCatalog.refresh(settings, reloadApps = resuming || selectionChanged || catalogSnapshot.radialApps.isEmpty())
            }
        } else {
            sidebar.cancel()
            unregisterPointerListener()
            activeEnvironmentApproved = false
            activeGestureConfig = null
            gestureEngine.cancel()
            removeOverlay()
        }
    }

    private fun registerPointerListener() {
        if (pointerRegistered) return
        val generation = pointerGeneration
        try {
            val listenerInterface =
                Class.forName(
                    "android.view.WindowManagerPolicyConstants\$PointerEventListener",
                    false,
                    classLoader,
                )
            val listener =
                Proxy.newProxyInstance(classLoader, arrayOf(listenerInterface)) { proxy, method, args ->
                    when (method.name) {
                        "onPointerEvent" -> {
                            val event = args?.firstOrNull() as? MotionEvent
                            if (event != null) enqueuePointerEvent(event, generation)
                            null
                        }
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === args?.firstOrNull()
                        "toString" -> "FlymeFreeformPointerListener"
                        else -> null
                    }
                }
            val windowManagerService = readWindowManagerService()
            val register = findMethod(windowManagerService.javaClass, "registerPointerEventListener", 2)
            register.invoke(windowManagerService, listener, Display.DEFAULT_DISPLAY)
            pointerListener = listener
            pointerRegistered = true
            logger(Log.INFO, "SYSTEM_POINTER_LISTENER_REGISTERED", null)
        } catch (exception: ReflectiveOperationException) {
            logger(Log.WARN, "SYSTEM_POINTER_LISTENER_UNAVAILABLE", exception)
        } catch (exception: RuntimeException) {
            logger(Log.WARN, "SYSTEM_POINTER_LISTENER_FAILED", exception)
        }
    }

    private fun enqueuePointerEvent(event: MotionEvent, generation: Long) {
        if (!pointerRegistered || generation != pointerGeneration) return
        val copy = QueuedPointerEvent(MotionEvent.obtain(event), generation)
        if (event.actionMasked != MotionEvent.ACTION_MOVE) {
            val precedingMove =
                synchronized(pointerQueueLock) {
                    pendingMove.also { pendingMove = null }
                }
            handler.post {
                precedingMove?.let(::processPointerEvent)
                processPointerEvent(copy)
            }
            return
        }
        var shouldPost = false
        synchronized(pointerQueueLock) {
            pendingMove?.event?.recycle()
            pendingMove = copy
            if (!movePosted) {
                movePosted = true
                shouldPost = true
            }
        }
        if (shouldPost) handler.post(::drainPendingMove)
    }

    private fun drainPendingMove() {
        val event =
            synchronized(pointerQueueLock) {
                movePosted = false
                pendingMove.also { pendingMove = null }
            } ?: return
        processPointerEvent(event)
    }

    private fun processPointerEvent(queued: QueuedPointerEvent) {
        latestPointerX = queued.event.x
        latestPointerY = queued.event.y
        val event = queued.event
        try {
            if (queued.generation != pointerGeneration || !pointerRegistered) return
            handlePointerEvent(event)
        } catch (exception: RuntimeException) {
            gestureEngine.cancel()
            removeOverlay()
            unregisterPointerListener()
            logger(Log.ERROR, "SYSTEM_POINTER_PROCESSING_FAILED", exception)
        } finally {
            event.recycle()
        }
    }

    private fun unregisterPointerListener() {
        pointerGeneration++
        clearPendingMove()
        val listener = pointerListener ?: return
        pointerRegistered = false
        try {
            val windowManagerService = readWindowManagerService()
            val unregister = findMethod(windowManagerService.javaClass, "unregisterPointerEventListener", 2)
            unregister.invoke(windowManagerService, listener, Display.DEFAULT_DISPLAY)
        } catch (exception: ReflectiveOperationException) {
            logger(Log.WARN, "SYSTEM_POINTER_LISTENER_REMOVE_FAILED", exception)
        } catch (exception: RuntimeException) {
            logger(Log.WARN, "SYSTEM_POINTER_LISTENER_REMOVE_FAILED", exception)
        } finally {
            pointerListener = null
        }
    }

    private fun clearPendingMove() {
        synchronized(pointerQueueLock) {
            pendingMove?.event?.recycle()
            pendingMove = null
            movePosted = false
        }
    }

    private fun handlePointerEvent(event: MotionEvent) {
        if (morePanelActive) return
        val settings = lastSettings
        if (!pointerRegistered || !environmentState.isGestureAllowed()) {
            cancelActiveGesture()
            return
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            activeEnvironmentApproved = isGestureEnvironmentAllowed()
            if (!activeEnvironmentApproved) {
                activeGestureConfig = null
                return
            }
        } else if (!activeEnvironmentApproved) {
            return
        }
        if (overlay != null && !isDynamicEnvironmentAllowed()) {
            activeEnvironmentApproved = false
            cancelActiveGesture()
            return
        }
        val metrics = context.resources.displayMetrics
        val config =
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                AdaptiveCornerGestureConfig.create(
                    displayWidth = metrics.widthPixels.toFloat(),
                    displayHeight = metrics.heightPixels.toFloat(),
                    touchSlop = touchSlop,
                    density = metrics.density,
                    // @author bomo 热区由设置的形状 + 横/纵长度（三角形）与半径（扇形）决定。
                    triggerZone = CornerTriggerRegion.of(settings, metrics.density),
                    leftEnabled = settings.leftCornerEnabled,
                    rightEnabled = settings.rightCornerEnabled,
                ).also { activeGestureConfig = it }
            } else {
                activeGestureConfig ?: return
            }
        val action =
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    gestureEngine.down(event.getPointerId(0), event.x, event.y, config)
                MotionEvent.ACTION_MOVE -> {
                    val index = event.findPointerIndex(event.getPointerId(0)).coerceAtLeast(0)
                    gestureEngine.move(
                        event.getPointerId(index),
                        event.pointerCount,
                        event.getX(index),
                        event.getY(index),
                        config,
                    )
                }
                MotionEvent.ACTION_UP -> gestureEngine.up(event.getPointerId(event.actionIndex))
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> gestureEngine.cancel()
                else -> if (gestureEngine.isClaimed) gestureEngine.cancel() else GestureAction.Ignore
            }
        when (action) {
            is GestureAction.Activate ->
                showOverlay(
                    side = action.side,
                    x = action.x,
                    y = action.y,
                )
            is GestureAction.Update -> {
                val selected = overlay?.updateGesture(action.x, action.y)
                gestureEngine.setSelection(selected)
            }
            is GestureAction.Commit -> overlay?.finishGesture()
            GestureAction.Cancel -> overlay?.cancelGesture()
            GestureAction.Ignore, GestureAction.PassThrough -> Unit
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            activeEnvironmentApproved = false
            activeGestureConfig = null
        }
    }

    private fun showOverlay(
        side: com.eta.aicybox.gesture.CornerSide,
        x: Float,
        y: Float,
    ) {
        removeOverlay()
        // 再次呼出轮盘时收掉支付栏：用户已经在做别的操作，残留的栏会挡手。
        removePayBar()
        morePanelActive = false
        val params = createOverlayParams(focusable = false)
        var view: CornerRadialOverlayView? = null
        try {
            view = CornerRadialOverlayView(context, this)
            // @author bomo 键盘高度只留一条诊断日志，**不再参与几何**：
            // 轮盘位置恒定在角落，被键盘遮挡的问题改由窗口层级解决（见 OVERLAY_WINDOW_TYPE）。
            currentImeBottomInset()
            view.begin(
                side = side,
                catalog = catalogSnapshot,
                x = x,
                y = y,
                radialScalePercent = lastSettings.radialScalePercent,
                vibrateEnabled = lastSettings.radialVibrateEnabled,
                vibrateStrength = lastSettings.radialVibrateStrength,
            )
            overlay = view
            windowManager.addView(view, params)
        } catch (exception: RuntimeException) {
            handleOverlayFailure(view, "SYSTEM_OVERLAY_ADD_FAILED", exception)
        } catch (error: LinkageError) {
            handleOverlayFailure(view, "SYSTEM_OVERLAY_ADD_FAILED", error)
        }
    }

    private fun handleOverlayFailure(
        view: CornerRadialOverlayView?,
        diagnosticCode: String,
        throwable: Throwable,
    ) {
        gestureEngine.cancel()
        if (view != null) {
            overlay = view
            removeOverlay()
        } else {
            overlay = null
        }
        logOverlayFailure(diagnosticCode, throwable)
    }

    private fun logOverlayFailure(
        diagnosticCode: String,
        throwable: Throwable,
    ) {
        val now = SystemClock.uptimeMillis()
        if (now - lastOverlayFailureLogAt >= OVERLAY_FAILURE_LOG_INTERVAL_MS) {
            lastOverlayFailureLogAt = now
            logger(Log.WARN, diagnosticCode, throwable)
        }
    }

    override fun onAppCommitted(entry: RadialAppEntry) {
        val generation = pointerGeneration
        activeEnvironmentApproved = false
        gestureEngine.cancel()
        removeOverlay()
        handler.post {
            if (generation == pointerGeneration) launchCommittedApp(entry)
        }
    }

    private fun launchCommittedApp(entry: RadialAppEntry) {
        if (!isGestureEnvironmentAllowed()) return
        // @author bomo 2026-10-19 「支付」伪条目：自己发 Intent，走同一份 ColorOS 小窗启动参数。
        // 刻意不复用侧边栏原厂工具（runTool）：原厂 AlipayPayCodeTool 在多窗口且支付宝已在前台时
        // 会直接放弃启动（"block in split screen mode, alipay is already opened"），且一律全屏。
        if (ModulePreferences.isPayComponent(entry.component)) {
            launchPay()
            return
        }
        // 工具（小布识屏 / 屏幕翻译等）是侧边栏进程内的 AbsTool，不能当应用启动：
        // 转交侧边栏执行，不创建小窗，因此也不计入「最近小窗」。
        if (ModulePreferences.isToolComponent(entry.component)) {
            if (!isGestureEnvironmentAllowed()) {
                // 环境闸门（横屏/游戏模式/关键系统界面前台）拦截时不触发执行，之前是静默的。
                logger(Log.WARN, "TOOL_LAUNCH_ENV_BLOCKED ${entry.component.className}", null)
                return
            }
            logger(
                    Log.DEBUG,
                    "TOOL_LAUNCH_ATTEMPT ${entry.component.className} @${latestPointerX.toInt()},${latestPointerY.toInt()}",
                    null,
                )
            if (!sidebar.runTool(entry.component.className, latestPointerX, latestPointerY)) {
                logger(Log.WARN, "TOOL_LAUNCH_REJECTED ${entry.component.className}", null)
            }
            return
        }
        when (val result = launcher.launch(entry.component)) {
            FreeformLaunchResult.Started -> publishRecentFreeform(entry.component)
            FreeformLaunchResult.TargetUnavailable ->
                logger(Log.WARN, "FREEFORM_LAUNCH_TARGET_UNAVAILABLE", null)
            is FreeformLaunchResult.Failed ->
                logger(Log.WARN, result.diagnosticCode, result.cause)
        }
    }

    /**
     * @author bomo 2026-10-19 聚合支付执行：打开排序第一项（小窗），并弹出支付栏供临时切换。
     *
     * 不走 `publishRecentFreeform` —— 支付是一次性动作，不该顶掉「最近小窗」里的应用。
     * 微信/支付宝未安装时只打诊断码，不抛异常也不弹选择器。
     */
    private fun launchPay() {
        val order = PayChannel.parseOrder(configuration.snapshot.payOrder)
        val first = order.firstOrNull() ?: PayChannel.DEFAULT
        if (!startPay(first)) return
        showPayBar(order, first)
    }

    /** 启动单个支付方式（固定小窗）；返回是否真的发出去了。 */
    private fun startPay(channel: PayChannel): Boolean {
        if (!PayIntentFactory.isAvailable(context, channel)) {
            logger(Log.WARN, "PAY_TARGET_UNAVAILABLE ${channel.storedValue}", null)
            return false
        }
        logger(Log.INFO, "PAY_LAUNCH ${channel.storedValue}", null)
        return when (val result = launcher.launchIntent(PayIntentFactory.build(channel), freeform = true)) {
            FreeformLaunchResult.Started -> true
            FreeformLaunchResult.TargetUnavailable -> {
                logger(Log.WARN, "PAY_TARGET_UNRESOLVED ${channel.storedValue}", null)
                false
            }
            is FreeformLaunchResult.Failed -> {
                logger(Log.WARN, result.diagnosticCode, result.cause)
                false
            }
        }
    }

    /**
     * @author bomo 2026-10-19 切换支付方式：**先关掉旧小窗，再开新的**。
     *
     * ColorOS 收窗口是异步的，立刻启动新页面有可能被塞进还没销毁的旧 task 里，
     * 所以关成功时延后 [PAY_SWITCH_DELAY_MS] 再启动；关不掉就直接开（宁可叠一个也别点了没反应）。
     */
    /**
     * @author bomo 2026-10-19 切换支付方式：**先关旧小窗 → 支付栏保持 → 再开新小窗**（用户 0.2.71 指定）。
     *
     * 中间那一段「一个窗都没有」是正常的：靠 [payBarSwitchHolding] 让支付栏不收也不藏。
     * 0.2.70 试过「先开新窗、再关旧窗」（想让小窗视觉不断），用户否掉：新窗会先跟旧窗叠一下，观感不对。
     * 关窗是异步的，立刻启动新页面有可能被塞进还没销毁的旧 task 里，所以关成功时延后
     * [PAY_SWITCH_DELAY_MS] 再启动；关不掉就直接开（宁可叠一个也别点了没反应）。
     */
    private fun switchPay(channel: PayChannel) {
        val now = SystemClock.uptimeMillis()
        // @author bomo 连点防抖：1 秒内只响应一次切换，避免快速/频繁点击触发多次
        // closeTopTask + startPay（关窗异步，连点会叠出脏状态，如多次关/开小窗、轮盘状态错乱）。
        if (now < payBarSwitchBlockedUntil) {
            logger(Log.DEBUG, "PAY_SWITCH_BLOCKED ${channel.storedValue} remain=${
                (payBarSwitchBlockedUntil - now)}ms", null)
            return
        }
        payBarSwitchBlockedUntil = now + PAY_SWITCH_BLOCK_MS
        payBarView?.select(payBarOrder.indexOf(channel))
        // 旧窗要关、新窗还没出来，这段时间「没有小窗」是正常的，不许收栏。
        // 0.2.59 的坑：一点就 PAY_BAR_HIDDEN，于是新支付方式的小窗下方再也长不出支付栏。
        payBarSwitchUntil = now + PAY_BAR_SWITCH_GRACE_MS
        payBarSwitchSawNewWindow = false
        payBarSwitchSettleUntil = 0L
        payBarSawWindow = false
        paySwitchBaseTaskId = flexibleWindow.topTaskId()
        paySwitchBaseActivity = flexibleWindow.topTaskActivity()
        payBarSwitchLandAt = now + PAY_SWITCH_LAND_MS
        val token = ++paySwitchToken
        val closed = flexibleWindow.closeTopTask()
        logger(
            Log.DEBUG,
            "PAY_SWITCH ${channel.storedValue} base=${paySwitchBaseTaskId} closed=$closed",
            null,
        )
        handler.postDelayed(
            {
                if (token != paySwitchToken) return@postDelayed
                if (startPay(channel)) {
                    val startedAt = SystemClock.uptimeMillis()
                    payBarSwitchUntil = startedAt + PAY_BAR_SWITCH_GRACE_MS
                    payBarSwitchSawNewWindow = false
                    payBarSwitchSettleUntil = 0L
                    payBarSwitchLandAt = startedAt + PAY_SWITCH_LAND_MS
                    // 点的那一瞬间栏刚好被收掉（旧窗先关）时，新窗也要重新长出支付栏。
                    if (payBarView == null) showPayBar(payBarOrder, channel)
                    confirmPaySwitchLanded(channel, token)
                } else {
                    // 新支付方式起不来：别留一条点不动的栏
                    removePayBar()
                }
            },
            if (closed) PAY_SWITCH_DELAY_MS else 0L,
        )
    }

    /**
     * @author bomo 2026-10-19 「点一下没反应、要点两下」的兜底。
     *
     * 实测 0.2.60 日志：点微信扫一扫时 `PAY_LAUNCH wechat_scan` 确实发出去了，
     * 但那一拍没有小窗被建成（再点一次时 `closeTopTask` 返回 `closed=false`，说明根本没有小窗）。
     * 微信这类「跳板 Activity」第一次常常只把主进程拉起来、来不及进小窗。
     * 所以发完 1.2s 还没看到任何小窗就**自动补发一次**，用户只需要点一下。
     */
    /**
     * @author bomo 0.2.70 新窗是否已经真的顶上来。
     *
     * taskId 变了 = 跨 App 切换（ColorOS 新建了一个 freeform task）；
     * Activity 变了 = 同 App 换页面（复用同一个 task）；
     * 都没变（微信这类跳板 Activity 不换 task 也不换 Activity）交给
     * [PAY_SWITCH_LAND_MS] 的到点兜底，别无限等。
     */
    private fun paySwitchWindowLanded(): Boolean {
        val currentId = flexibleWindow.topTaskId()
        if (currentId < 0) return false
        if (currentId != paySwitchBaseTaskId) return true
        val currentActivity = flexibleWindow.topTaskActivity()
        val baseActivity = paySwitchBaseActivity
        return baseActivity != null && currentActivity != null && currentActivity != baseActivity
    }

    /** 检测「新窗已经顶上来」并收尾（关旧窗）。 */
    private fun detectPaySwitchLanded(now: Long) {
        if (payBarSwitchSawNewWindow) return
        if (now >= payBarSwitchUntil) return
        val landed = paySwitchWindowLanded()
        if (!landed && now < payBarSwitchLandAt) return
        payBarSwitchSawNewWindow = true
        payBarSwitchSettleUntil = now + PAY_BAR_SWITCH_SETTLE_MS
        // 切换前残留的「拖动中 / 关闭中 / 待确认」状态一律清掉，
        // 否则稳定期一结束就会把栏藏一下再放出来（用户实测的「先消失再出现」）。
        payBarAdjusting = false
        payBarClosingUntil = 0L
        payBarConfirmUntil = 0L
        logger(
            Log.DEBUG,
            "PAY_BAR_SWITCH_LANDED id=${flexibleWindow.topTaskId()} " +
                "activity=${flexibleWindow.topTaskActivity()} landed=$landed",
            null,
        )
    }


    /**
     * @author bomo 2026-10-19 「点一下没反应、要点两下」的兜底。
     *
     * 实测 0.2.60 日志：点微信扫一扫时 `PAY_LAUNCH wechat_scan` 确实发出去了，
     * 但那一拍没有小窗被建成（再点一次时 `closeTopTask` 返回 `closed=false`，说明根本没有小窗）。
     * 微信这类「跳板 Activity」第一次常常只把主进程拉起来、来不及进小窗。
     * 所以发完 [PAY_SWITCH_VERIFY_MS] 还没换上新窗就**自动补发一次**，用户只需要点一下。
     */
    private fun confirmPaySwitchLanded(
        channel: PayChannel,
        token: Int,
    ) {
        handler.postDelayed(
            {
                if (token != paySwitchToken) return@postDelayed
                if (payBarView == null) return@postDelayed
                // 0.2.70：先开新窗再关旧窗，所以「还有小窗」不等于成功 —— 必须看最上层窗有没有换。
                if (paySwitchWindowLanded()) return@postDelayed
                logger(
            Log.DEBUG, "PAY_SWITCH_RETRY ${channel.storedValue}", null)
                if (startPay(channel)) {
                    val now = SystemClock.uptimeMillis()
                    payBarSwitchUntil = now + PAY_BAR_SWITCH_GRACE_MS
                    payBarSwitchSawNewWindow = false
                    payBarSwitchSettleUntil = 0L
                    payBarSwitchLandAt = now + PAY_SWITCH_LAND_MS
                }
            },
            PAY_SWITCH_VERIFY_MS,
        )
    }

    /**
     * @author bomo 2026-10-19 支付栏：贴在小窗正下方、**与小窗同宽**，随小窗缩放。
     *
     * 位置与尺寸由 [updatePayBar] 每 [PAY_BAR_TICK_MS] 跟随一次；小窗关掉后整条栏立刻收掉。
     */
    private fun showPayBar(
        order: List<PayChannel>,
        current: PayChannel,
    ) {
        removePayBar()
        payBarOrder = order
        val items = order.map { channel -> PayBarOverlayView.Item(channel, payBarIcon(channel)) }
        val view = PayBarOverlayView(context, items) { channel -> switchPay(channel) }
        view.select(order.indexOf(current))
        payBarView = view
        payBarAttached = false
        payBarSawWindow = false
        payBarShownAt = SystemClock.uptimeMillis()
        payBarSwitchUntil = 0L
        payBarSwitchSawNewWindow = false
        payBarSwitchSettleUntil = 0L
        paySwitchBaseTaskId = -1
        paySwitchBaseActivity = null
        payBarSwitchLandAt = 0L
        payBarCovered = false
        payBarCoverTrusted = false
        payBarAdjusting = false
        payBarClosingUntil = 0L
        payBarConfirmUntil = 0L
        payBarAttachAt = 0L
        payBarMini = false
        payBarGeometryKey = null
        payBarGeometryStreak = 0
        attachPayBarIfReady()
    }

    /** 拿到小窗 bounds 才 addView；等不到（超时）就退回屏幕底部，但只挂一次。 */
    private fun attachPayBarIfReady() {
        val view = payBarView ?: return
        if (payBarAttached) return
        val bounds = flexibleWindow.topTaskBounds()
        if (bounds == null) {
            if (SystemClock.uptimeMillis() - payBarShownAt <= PAY_BAR_ATTACH_TIMEOUT_MS) {
                handler.removeCallbacks(payBarAttachRunnable)
                handler.postDelayed(payBarAttachRunnable, PAY_BAR_ATTACH_TICK_MS)
                return
            }
            // 用户 2026-10-19：先等小窗出现再显示栏。等不到就别挂在屏幕底部乱晃，直接收掉。
            logger(
            Log.DEBUG, "PAY_BAR_ATTACH_GIVEUP", null)
            removePayBar()
            return
        }
        val params = createPayBarParams(bounds)
        view.setBarSize(params.width, params.height)
        view.setDarkMode(isSystemDark())
        view.alpha = 0f
        try {
            windowManager.addView(view, params)
        } catch (exception: RuntimeException) {
            logger(Log.WARN, "PAY_BAR_ADD_FAILED", exception)
            payBarView = null
            return
        } catch (error: LinkageError) {
            logger(Log.WARN, "PAY_BAR_ADD_FAILED", error)
            payBarView = null
            return
        }
        payBarAttached = true
        payBarAttachAt = SystemClock.uptimeMillis()
        if (bounds != null) payBarSawWindow = true
        view.animate().alpha(1f).setDuration(PAY_BAR_FADE_IN_MS).start()
        schedulePayBarDismiss()
        logger(
            Log.DEBUG,
            "PAY_BAR_SHOWN count=${view.itemCount} usable=${flexibleWindow.isUsable} " +
                "at=${params.x},${params.y} size=${params.width}x${params.height} anchored=${bounds != null}",
            null,
        )
        handler.removeCallbacks(payBarTickRunnable)
        handler.postDelayed(payBarTickRunnable, PAY_BAR_TICK_MS)
    }

    /**
     * 跟随小窗：拿最上层小窗的可见 bounds，把栏贴到它正下方并按它的宽度铺满。
     * 一个小窗都不剩 → 立刻收掉支付栏（用户要的「小窗关闭后支付栏消失」）。
     */
    private fun updatePayBar() {
        val view = payBarView ?: return
        if (!payBarAttached) {
            attachPayBarIfReady()
            return
        }
        val bounds = flexibleWindow.topTaskBounds()
        val taskAlive = flexibleWindow.hasTopTask()
        if (bounds != null && taskAlive) {
            val now = SystemClock.uptimeMillis()
            detectPaySwitchLanded(now)
            val holding = payBarSwitchHolding(now)
            val stabilizing = payBarSwitchStabilizing(now)
            val switching = holding || stabilizing
            payBarSawWindow = true
            payBarMissingTicks = 0
            // @author bomo 0.2.71 小窗被别的界面（支付宝指纹校验页等）盖住时，支付栏也要跟着被盖住：
            // 栏是 overlay 窗口，天然压在 Activity 之上，所以只能自己判断「小窗还在最上层吗」，不是就藏掉。
            // 信号要先见过一次 true 才敢用；切换支付方式期间不判（那会儿窗本来就在换）。
            val coverSignal = if (switching) null else flexibleWindow.isTopTaskOnTop()
            if (coverSignal == true) payBarCoverTrusted = true
            val covered = payBarCoverTrusted && coverSignal == false
            if (covered != payBarCovered) {
                logger(
            Log.DEBUG, "PAY_BAR_COVERED covered=$covered", null)
                payBarCovered = covered
                // 重新露出来时几何可能已经变了：给一段宽限期只更新基线，别把栏再藏一下。
                if (!covered) payBarAttachAt = now
            }
            // @author bomo 0.2.73 系统验证弹窗（SystemUI AuthController）在不在；在就藏栏。
            val authBlocked = if (switching) payBarAuthBlocked else refreshPayBarAuthBlocked(now)
            if (authBlocked != payBarAuthBlocked) {
                logger(
            Log.DEBUG, "PAY_BAR_AUTH_DIALOG blocked=$authBlocked", null)
                payBarAuthBlocked = authBlocked
                // 验证结束：小窗几何可能变了，给一段宽限期只更新基线，别把栏再藏一下。
                if (!authBlocked) payBarAttachAt = now
            }
            // @author bomo 2026-10-19 迷你小窗（缩成边上小胶囊 / 缩得很小）不挂支付栏，等放大回正常尺寸再出现。
            // 只判高度会漏：迷你态的 bounds 有可能仍是未缩放的布局尺寸，所以补一条「可见宽度 < 屏幕 45%」的兜底
            // （实测缩到 261px 宽的小窗下方照样挂着栏，用户 2026-10-19 反馈）。
            val metrics = context.resources.displayMetrics
            val miniDetected =
                flexibleWindow.isTopTaskMini() ||
                    bounds.height() < (metrics.density * PAY_BAR_MINI_MAX_HEIGHT_DP) ||
                    bounds.width() < (metrics.widthPixels * PAY_BAR_MINI_MAX_WIDTH_RATIO)
            // 切换期间新窗刚建出来，尺寸/形态还不是最终值，这几十毫秒别判迷你（否则栏会躲一下）。
            val mini = miniDetected && !switching
            if (mini != payBarMini) {
                logger(
            Log.DEBUG, "PAY_BAR_MINI mini=$mini size=${bounds.width()}x${bounds.height()}", null)
            }
            payBarMini = mini
            if (mini || covered || payBarAuthBlocked) {
                // 迷你时不去改几何：栏保持上次的位置与尺寸，放大后直接贴合，也不会缩成一条小细条。
                payBarAdjusting = false
                payBarClosingUntil = 0L
                payBarConfirmUntil = 0L
                // 迷你态可能停很久，别让 60s 超时把栏收掉（放大后还得出现）。
                schedulePayBarDismiss()
            } else {
                // 先问 ColorOS 是不是正在拖动/缩放（这个信号在手势第一拍就有，
                // 而 Task.getBounds() 在缩放中不逐帧提交，只靠几何猜会漏）。
                // 切换后新窗刚出现那 400ms 也不判拖动，否则新位置那一次几何跳变会把栏藏掉。
                val suppress = switching
                val gestureAdjusting = if (suppress) false else flexibleWindow.isTopTaskAdjusting()
                applyPayBarGeometry(view, bounds, gestureAdjusting, suppressAdjust = suppress)
            }
            applyPayBarAdjustingVisibility()
        } else {
            // 迷你态读不到 bounds（胶囊形态不一定算 zoom task）：保留栏，别收掉，
            // 否则放大回正常尺寸后再也没有栏可显示（用户 2026-10-19 要求）。
            if (flexibleWindow.isTopTaskMini()) {
                payBarMini = true
                payBarMissingTicks = 0
                payBarAdjusting = false
                payBarClosingUntil = 0L
                payBarConfirmUntil = 0L
                schedulePayBarDismiss()
                applyPayBarAdjustingVisibility()
            } else {
                payBarMissingTicks++
                // 读数断层就进入「正在关闭」抑制期：关闭动画里 task 会短暂复活、bounds 会跳一下，
                // 那时如果把栏放出来就会「闪一下」（用户 2026-10-19 反馈）。
                payBarClosingUntil = SystemClock.uptimeMillis() + PAY_BAR_CLOSING_GUARD_MS
                // 宽限期只保护「新窗还没出现」这一段。新窗一旦出现，之后再读不到小窗就是用户上滑关掉了，
                // 必须按正常速度立刻收栏。0.2.68 把稳定期也算进保护里，所以「切换过之后再上滑关」
                // 要等 700ms 才收（用户 2026-10-19 实测反馈），这里去掉。
                val switching = payBarSwitchHolding(SystemClock.uptimeMillis())
                // 小窗关掉后要在 100ms 级内收栏（用户嫌慢），但关闭动画里 bounds 会短暂为 null，
                // 所以连续几拍读不到才收；task 已经没了则更快收。
                val limit = if (taskAlive) PAY_BAR_MISSING_TICKS else PAY_BAR_GONE_TICKS
                if (!switching && payBarSawWindow && payBarMissingTicks >= limit) {
                    logger(
            Log.DEBUG,
                        "PAY_BAR_HIDDEN window_gone saw=$payBarSawWindow ticks=$payBarMissingTicks " +
                            "taskAlive=$taskAlive",
                        null,
                    )
                    removePayBar()
                    return
                }
            }
        }
        handler.removeCallbacks(payBarTickRunnable)
        handler.postDelayed(payBarTickRunnable, PAY_BAR_TICK_MS)
    }

    private fun applyPayBarGeometry(
        view: PayBarOverlayView,
        bounds: Rect,
        gestureAdjusting: Boolean = false,
        /** 切换支付方式的稳定期内不判「用户在拖」，否则新窗一出现栏就被藏一下。 */
        suppressAdjust: Boolean = false,
    ) {
        val metrics = context.resources.displayMetrics
        // 用户要求：栏和小窗**同宽**（原来只有 0.66 倍，显得又小又断）。
        val barWidth = bounds.width().coerceAtLeast(1)
        val barHeight = (barWidth * PAY_BAR_HEIGHT_RATIO).toInt().coerceAtLeast(1)
        view.setBarSize(barWidth, barHeight)
        view.setDarkMode(isSystemDark())

        val x = bounds.left.coerceIn(0, (metrics.widthPixels - barWidth).coerceAtLeast(0))
        // 用户 2026-10-19：栏和小窗之间留一点间隙，别贴着（原来紧贴，看着发闷）。
        val gap = (PAY_BAR_GAP_DP * metrics.density).toInt().coerceAtLeast(1)
        var y = bounds.bottom + gap
        if (y + barHeight > metrics.heightPixels) {
            // 小窗本身已经贴到屏幕底部：栏压在小窗下沿**内侧**（同样留间隙），
            // 别让它被夹到屏幕最底端、和小窗之间空一条缝（用户 2026-10-19 反馈）。
            y = bounds.bottom - gap - barHeight
        }
        y = y.coerceIn(0, (metrics.heightPixels - barHeight).coerceAtLeast(0))

        if (suppressAdjust) {
            // @author bomo 0.2.70 稳定期只更新基线、不算「用户在拖」：
            // 否则稳定期一结束，第一拍就把新窗那一次几何跳变当成拖动，栏又要藏一下再出来（用户实测「闪一下」）。
            payBarGeometryKey = "$y,$barWidth,$barHeight"
            payBarGeometryStreak = 1
            payBarLastGeometryAt = SystemClock.uptimeMillis()
        } else {
            notePayBarGeometry(y = y, width = barWidth, height = barHeight)
        }
        payBarAdjustFromGesture = gestureAdjusting
        if (gestureAdjusting) {
            // 手势信号优先：拖动/缩放期间每拍续期，停手后由 [applyPayBarAdjustingVisibility] 到点放出。
            payBarAdjusting = true
            payBarSettleAt = SystemClock.uptimeMillis() + PAY_BAR_SETTLE_MS
        }

        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        if (params.x == x && params.y == y && params.width == barWidth && params.height == barHeight) {
            return
        }
        params.x = x
        params.y = y
        params.width = barWidth
        params.height = barHeight
        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: IllegalArgumentException) {
            // 窗口已被系统移除；轮询下一拍会发现并收尾。
        } catch (exception: RuntimeException) {
            logger(Log.WARN, "PAY_BAR_LAYOUT_FAILED", exception)
        }
    }

    /**
     * 系统当前是否深色模式。
     *
     * 用 `Resources.getSystem()`（全局资源）而不是自己的 context：system_server 里的窗口
     * 没有 Activity 那套配置回调，只有系统资源这份是随全局 uimode 走的。
     */
    private fun isSystemDark(): Boolean =
        runCatching {
            val uiMode = Resources.getSystem().configuration.uiMode
            (uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }.getOrDefault(false)

    /**
     * 几何一变就算「用户在动小窗」，把栏藏起来并延长 [payBarSettleAt]。
     *
     * 0.2.63：原来要求「连续两拍变化」才认，但 `Task.getBounds()` 在缩放手势里**不逐帧提交**，
     * 一次手势往往只变一两拍甚至只在松手时变，所以单次变化就触发（松手后那拍会顺带把栏藏一下再放出，
     * 正好落在「松手、大小定下来之后才显示」的点上）。
     */
    private fun notePayBarGeometry(
        y: Int,
        width: Int,
        height: Int,
    ) {
        val key = "$y,$width,$height"
        if (key == payBarGeometryKey) return
        payBarGeometryKey = key
        payBarGeometryStreak = 1
        val now = SystemClock.uptimeMillis()
        payBarLastGeometryAt = now
        // 栏刚挂上时几何也从初始值跳到目标位置，那不是「用户在拖」，别把首现的栏藏起来。
        if (now - payBarAttachAt < PAY_BAR_STARTUP_GRACE_MS) return
        payBarAdjusting = true
        payBarSettleAt = now + PAY_BAR_SETTLE_MS
    }

    /**
     * 切换支付方式、**新窗还没出现**这段：栏不收、也不被藏（旧窗已关、新窗未起是正常空窗期）。
     * 新窗一出现就结束 —— 之后读不到小窗只能是用户上滑关掉了，必须立刻收栏。
     */
    private fun payBarSwitchHolding(now: Long): Boolean =
        now < payBarSwitchUntil && !payBarSwitchSawNewWindow

    /** 新窗刚出现那 400ms：几何还在跳，这段时间只「不因关闭/拖动而隐藏」，不参与收栏判断。 */
    private fun payBarSwitchStabilizing(now: Long): Boolean =
        now < payBarSwitchSettleUntil

    /**
     * @author bomo 2026-10-19 支付栏显隐（重写）：
     *
     * - 拖动/缩放、关闭中、停手确认期 → 立刻藏起来（alpha 0，不吃触摸）；
     * - 其他情况 → 140ms 淡入（用户要求「出现别太生硬」）；
     * - **切换支付方式期间强制显示**：旧窗关掉到新窗出来这段时间栏不该消失。
     */
    private fun applyPayBarAdjustingVisibility() {
        val view = payBarView ?: return
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        val now = SystemClock.uptimeMillis()
        if (payBarAdjusting && now >= payBarSettleAt) {
            payBarAdjusting = false
            payBarGeometryStreak = 0
            // 到点先不急着显示：再等 [PAY_BAR_CONFIRM_MS] 确认小窗还活着。
            // 关小窗那条路上 task 会在几百毫秒内消失，这一小段就能把「闪一下」挡掉。
            payBarConfirmUntil = now + PAY_BAR_CONFIRM_MS
            logger(
            Log.DEBUG, "PAY_BAR_ADJUST_END at=${params.x},${params.y}", null)
        }
        val switching = payBarSwitchHolding(now) || payBarSwitchStabilizing(now)
        val closing = now < payBarClosingUntil || flexibleWindow.isTopTaskClosing()
        val hidden =
            payBarMini ||
                payBarCovered ||
                payBarAuthBlocked ||
                (!switching && (payBarAdjusting || closing || now < payBarConfirmUntil))

        val flags =
            if (hidden) {
                params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            } else {
                params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            }
        if (params.flags != flags) {
            params.flags = flags
            try {
                windowManager.updateViewLayout(view, params)
            } catch (_: IllegalArgumentException) {
                // 窗口已被系统移除；轮询会收尾。
            } catch (exception: RuntimeException) {
                logger(Log.WARN, "PAY_BAR_ADJUST_FAILED", exception)
            }
        }
        if (hidden) {
            if (view.alpha != 0f) {
                view.animate().cancel()
                view.alpha = 0f
                logger(
            Log.DEBUG,
                    "PAY_BAR_ADJUST_START from=${if (payBarAdjustFromGesture) "gesture" else "geometry"}",
                    null,
                )
            }
        } else if (view.alpha < 1f) {
            view.animate().alpha(1f).setDuration(PAY_BAR_FADE_IN_MS).start()
        }
    }

    private fun schedulePayBarDismiss() {
        handler.removeCallbacks(payBarDismissRunnable)
        handler.postDelayed(payBarDismissRunnable, PAY_BAR_TIMEOUT_MS)
    }

    /**
     * @author bomo 0.2.73 系统验证弹窗在不在（带 [PAY_BAR_AUTH_CHECK_MS] 节流）。
     *
     * 验证弹窗至少活几百毫秒，150ms 查一次足够跟上，不必每拍都翻窗口表。
     */
    private fun refreshPayBarAuthBlocked(now: Long): Boolean {
        if (now - payBarAuthCheckedAt < PAY_BAR_AUTH_CHECK_MS) return payBarAuthBlocked
        payBarAuthCheckedAt = now
        return readKeyguardDialogVisible()
    }

    /** 翻 WMS 的 `mWindowMap`：有没有可见的 `KEYGUARD_DIALOG`（= 验证 / 解锁类弹窗）。 */
    private fun readKeyguardDialogVisible(): Boolean {
        return try {
            val windowManagerService = readWindowManagerService()
            val map = readField(windowManagerService, "mWindowMap")
            if (map !is Map<*, *>) {
                false
            } else {
                val windows: List<Any> = synchronized(map) { map.values.filterNotNull() }
                windows.any { window -> isVisibleKeyguardDialog(window) }
            }
        } catch (_: ReflectiveOperationException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun isVisibleKeyguardDialog(window: Any): Boolean {
        return try {
            val attrs = readField(window, "mAttrs") as? WindowManager.LayoutParams
            attrs != null &&
                attrs.type == WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG &&
                (findMethod(window.javaClass, "isVisible", 0).invoke(window) as? Boolean == true)
        } catch (_: ReflectiveOperationException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun removePayBar() {
        handler.removeCallbacks(payBarDismissRunnable)
        handler.removeCallbacks(payBarTickRunnable)
        handler.removeCallbacks(payBarAttachRunnable)
        payBarAuthBlocked = false
        payBarAuthCheckedAt = 0L
        payBarSwitchUntil = 0L
        payBarSwitchSawNewWindow = false
        payBarSwitchSettleUntil = 0L
        paySwitchBaseTaskId = -1
        paySwitchBaseActivity = null
        payBarSwitchLandAt = 0L
        payBarCovered = false
        payBarCoverTrusted = false
        payBarSawWindow = false
        payBarAttached = false
        payBarMissingTicks = 0
        payBarAdjusting = false
        payBarClosingUntil = 0L
        payBarConfirmUntil = 0L
        payBarAttachAt = 0L
        payBarMini = false
        payBarGeometryKey = null
        payBarGeometryStreak = 0
        val view = payBarView ?: return
        payBarView = null
        // @author bomo 2026-10-19 消失也淡出，别突然截断（用户要求出现/消失都别太生硬）。
        view.animate().cancel()
        view.animate()
            .alpha(0f)
            .setDuration(PAY_BAR_FADE_OUT_MS)
            .withEndAction {
                try {
                    windowManager.removeViewImmediate(view)
                } catch (_: IllegalArgumentException) {
                    // 已被系统移除；本地所有权仍需清空。
                } catch (exception: RuntimeException) {
                    logger(Log.WARN, "PAY_BAR_REMOVE_FAILED", exception)
                }
            }.start()
    }

    /** 支付栏图标：优先用侧边栏工具目录里原厂的专用 PNG，缺失时退回应用图标。 */
    private fun payBarIcon(channel: PayChannel): Bitmap? {
        val catalog = latestToolCatalog
        if (catalog != null) {
            ToolCatalogCodec.decode(catalog)
                .firstOrNull { record -> record.alias == channel.storedValue }
                ?.iconPng
                ?.let { png -> BitmapFactory.decodeByteArray(png, 0, png.size) }
                ?.let { bitmap -> return bitmap }
        }
        return try {
            val drawable = context.packageManager.getApplicationIcon(PayIntentFactory.packageOf(channel))
            val size = (PAY_BAR_ICON_DP * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
            Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
                drawable.setBounds(0, 0, size, size)
                drawable.draw(Canvas(bitmap))
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun createPayBarParams(bounds: Rect?): WindowManager.LayoutParams {
        val metrics = context.resources.displayMetrics
        val barWidth = (bounds?.width() ?: metrics.widthPixels).coerceAtLeast(1)
        val barHeight = (barWidth * PAY_BAR_HEIGHT_RATIO).toInt().coerceAtLeast(1)
        val params = WindowManager.LayoutParams(
            barWidth,
            barHeight,
            OVERLAY_WINDOW_TYPE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bounds?.left ?: ((metrics.widthPixels - barWidth) / 2)
            // @author bomo 首挂位置与 applyPayBarGeometry 完全一致（含 clamp + gap + 贴底判断），
            // 否则首帧 addView 在 bounds.bottom 无 gap 处，下一拍 updateViewLayout 又加 gap，
            // 栏首现时往下「哆嗦」了一下（用户反馈）。
            if (bounds != null) {
                x = bounds.left.coerceIn(0, (metrics.widthPixels - barWidth).coerceAtLeast(0))
                val gap = (PAY_BAR_GAP_DP * metrics.density).toInt().coerceAtLeast(1)
                var y = bounds.bottom + gap
                if (y + barHeight > metrics.heightPixels) {
                    y = bounds.bottom - gap - barHeight
                }
                y = y.coerceIn(0, (metrics.heightPixels - barHeight).coerceAtLeast(0))
            } else {
                y = metrics.heightPixels - barHeight
            }
            setFitInsetsTypes(0)
            title = "FlymeFreeformPayBar"
        }
        return params
    }

    /**
     * Hook 进程的框架远端配置是只读的（实测 `edit()` 抛 `UnsupportedOperationException`），
     * 因此「最近小窗」经广播交给模块 App 落盘，侧边栏进程随后按只读方式读取渲染。
     */
    /**
     * 把侧边栏工具目录转发给模块 App。
     * system_server 发出的广播不受厂商「后台启动管控」限制（实测侧边栏进程会被拦），
     * App 收到后落盘，设置界面的工具列表与扇形的工具图标都读这份数据。
     */
    /**
     * App 打开设置时会广播索要工具目录（被动中继可能赶上 App 冷启动被厂商启动策略延迟），
     * 这里注册接收器：收到请求就回发一次目录广播并刷新 Settings 文本副本。
     */
    private fun registerToolRequestReceiver() {
        try {
            context.registerReceiver(
                object : BroadcastReceiver() {
                    override fun onReceive(receiverContext: Context?, intent: Intent?) {
                        val cached = latestToolCatalog
                        if (cached == null) {
                            // 开机后还没拿到过目录：向侧边栏现拉一次，回包会再走本写入。
                            sidebar.requestToolCatalog()
                            return
                        }
                        SharedSettings.writeToolCatalog(context, stripIcons(cached))
                        broadcastToolCatalog(shrinkIcons(cached))
                        logger(Log.DEBUG, "TOOL_CATALOG_SERVED", null)
                    }
                },
                IntentFilter(SharedStateProtocol.ACTION_REQUEST_TOOLS),
            )
        } catch (exception: Exception) {
            logger(Log.WARN, "TOOL_REQUEST_RECEIVER_FAILED", exception)
        }
    }

    /**
     * @author bomo 接收模块 App 推来的**配置变更**（编解码见 `ConfigSnapshotCodec`）。
     *
     * 本进程读 RemotePreferences 是 `start()` 时的**进程内缓存**，无法自行感知设置改动
     * （见 `ProcessConfiguration.refreshNow()` 注释）；由 App 主动推送是唯一可靠的实时通道。
     * 解码失败时保持原配置不动，只留一条诊断码，不影响既有行为。
     */
    private fun registerConfigPushReceiver() {
        // @author bomo 必须导出：发送方是模块 App（另一个 UID），
        // 非导出接收器收不到跨 UID 广播（旧实现因此从来没生效过，日志里 CONFIG_PUSH_* 恒为 0）。
        ConfigPushReceiver.register(context, configuration, logger) { debugDumpPayBounds() }
    }

    /**
     * @author bomo 2026-10-19 诊断：把当前小窗的布局 / 缩放 / 可见矩形打一条日志。
     *
     * 触发：`am broadcast -a com.eta.aicybox.action.DEBUG_DUMP_BOUNDS`
     * （只在开发模式打开时生效；配合 `PAY_BAR_BOUNDS` 日志核对支付栏贴哪，不必真的呼出聚合支付）。
     */
    private fun debugDumpPayBounds() {
        if (!configuration.snapshot.developerMode) return
        logger(
            Log.DEBUG,
            "PAY_DEBUG_BOUNDS ${flexibleWindow.describeTopTask()} " +
                "bar=${payBarView?.let { view -> "${view.width}x${view.height}" }} " +
                "attached=$payBarAttached saw=$payBarSawWindow",
            null,
        )
    }

    /**
     * @author bomo 回答 SystemUI 的「轮盘协调器在不在」探针（2026-10-17）。
     *
     * 环节：SystemUI 的角落热区必须先确认轮盘本体（本类）已在 system_server 里活着，
     * 才允许抢占角落触摸；否则会出现「热区吃掉返回手势但轮盘不出现」。
     * 回执不加签名权限：载荷只是一个「在」的信号，伪造它最多让热区复用一次本模块手势，
     * 不涉及配置与特权。
     */
    private fun registerCoordinatorProbeResponder() {
        try {
            context.registerReceiver(
                object : BroadcastReceiver() {
                    override fun onReceive(receiverContext: Context?, intent: Intent?) {
                        logger(Log.DEBUG, "COORDINATOR_READY_QUERY_RECEIVED", null)
                        try {
                            context.sendBroadcast(
                                Intent(SharedStateProtocol.ACTION_COORDINATOR_READY)
                                    .setPackage(SYSTEM_UI_PACKAGE),
                            )
                        } catch (exception: RuntimeException) {
                            logger(Log.WARN, "COORDINATOR_READY_REPLY_FAILED", exception)
                        }
                    }
                },
                IntentFilter(SharedStateProtocol.ACTION_COORDINATOR_QUERY),
                Context.RECEIVER_EXPORTED,
            )
            logger(Log.INFO, "COORDINATOR_READY_RESPONDER_INSTALLED", null)
            // @author bomo 另外**不等询问先喊一声**：SystemUI 可能比本协调器先起来，
            // 它的探针会在本接收器注册之前就发完；这一条主动报到了，双向都能兜住。
            context.sendBroadcast(
                Intent(SharedStateProtocol.ACTION_COORDINATOR_READY)
                    .setPackage(SYSTEM_UI_PACKAGE),
            )
        } catch (exception: RuntimeException) {
            logger(Log.WARN, "COORDINATOR_READY_RESPONDER_FAILED", exception)
        }
    }

    /**
     * 收到侧边栏目录：内存留全量（含图标，供扇形渲染）。
     *
     * 下行分两路（Settings.Global 单值实测上限 32KB，带图标目录约 60-110KB 必被拒）：
     * - Settings 写**去图标文本版**（约 700B，稳定），作低配兜底；
     * - 定向广播**压缩图标版**（72px WEBP）给 App 落远端配置——system_server 发出的
     *   广播不受厂商后台启动管控拦截（实测侧边栏进程直发会被拦），App 端选择应用
     *   界面的图标从此读取。
     */
    private fun publishToolCatalogToApp(catalog: String) {
        latestToolCatalog = catalog
        SharedSettings.writeToolCatalog(context, stripIcons(catalog))
        broadcastToolCatalog(shrinkIcons(catalog))
        logger(Log.DEBUG, "TOOL_CATALOG_RELAYED", null)
    }

    /** Settings 侧目录副本：去图标纯文本（`Settings.Global` 单值上限 32KB，实测 60KB 被拒）。 */
    private fun stripIcons(catalog: String): String =
        ToolCatalogCodec.encode(
            ToolCatalogCodec.decode(catalog).map { record -> record.copy(iconPng = null) },
        )

    /** 定向广播目录给模块 App（setPackage 显式指定），App 收到后落远端配置。 */
    private fun broadcastToolCatalog(catalog: String) {
        runCatching {
            context.sendBroadcast(
                Intent(SharedStateProtocol.ACTION)
                    .setPackage(SharedStateProtocol.MODULE_PACKAGE)
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    .putExtra(SharedStateProtocol.EXTRA_KIND, SharedStateProtocol.KIND_TOOL_CATALOG)
                    .putExtra(SharedStateProtocol.EXTRA_CATALOG, catalog),
            )
        }.onSuccess {
            logger(Log.DEBUG, "TOOL_CATALOG_BROADCAST", null)
        }.onFailure { exception ->
            logger(Log.WARN, "TOOL_CATALOG_BROADCAST_FAILED", exception)
        }
    }

    /**
     * 广播侧的目录副本压缩：图标统一压到 72px WEBP（每枚约 2-3KB，总量 60-110KB），
     * 广播 Intent 与 App 侧远端配置（SharedPreferences）均可承载。
     */
    private fun shrinkIcons(catalog: String): String =
        ToolCatalogCodec.encode(
            ToolCatalogCodec.decode(catalog).map { record ->
                record.copy(iconPng = record.iconPng?.let(::shrinkToWebp))
            },
        )

    private fun shrinkToWebp(bytes: ByteArray): ByteArray? =
        runCatching {
            val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val scaled = Bitmap.createScaledBitmap(source, TOOL_ICON_PX, TOOL_ICON_PX, true)
            java.io.ByteArrayOutputStream().use { stream ->
                scaled.compress(Bitmap.CompressFormat.WEBP, 85, stream)
                stream.toByteArray()
            }
        }.getOrNull()

    /**
     * 记录「小窗打开」：内存列表即时更新，Settings.Global 供侧边栏面板实时读取
     * （跨进程广播在 ColorOS 上会被启动管控/延迟策略干扰，已弃用）。
     */
    private fun publishRecentFreeform(component: ComponentName) {
        recentFreeformEntries.remove(component)
        recentFreeformEntries.addFirst(component)
        while (recentFreeformEntries.size > ModulePreferences.MAX_RECENT_FREEFORM) {
            recentFreeformEntries.removeLast()
        }
        SharedSettings.writeRecentFreeform(context, recentFreeformEntries.toList())
    }

    override fun onMorePanelRequested() {
        if (!isGestureEnvironmentAllowed()) {
            removeOverlay()
            return
        }
        val view = overlay ?: return
        morePanelActive = true
        activeEnvironmentApproved = false
        gestureEngine.cancel()
        if (!sidebar.open(
                // @author bomo 用手势真实方位决定面板锚向：原厂 mIsLeft 在自建面板路径下恒为默认值，
                // 会导致右侧呼出也贴左。
                leftSide = view.invokedSide == com.eta.aicybox.gesture.CornerSide.Left,
                beforeOpen = {
                    if (overlay === view && lastSettings.enabled && isGestureEnvironmentAllowed()) {
                        view.retainBackdropForPanel()
                        true
                    } else false
                },
                onResult = { result ->
                    if (overlay === view) {
                        if (result == ColorOsSidebarClient.Outcome.Fallback && lastSettings.enabled && isGestureEnvironmentAllowed() &&
                            context.getSystemService(android.os.UserManager::class.java)?.isUserForeground == true
                        ) {
                            view.visibility = android.view.View.VISIBLE
                            showBuiltInMorePanel(view)
                        } else if (result != ColorOsSidebarClient.Outcome.Shown) removeOverlay()
                    }
                },
                onExitStarted = { if (overlay === view) view.beginBackdropExit() },
                onHideBackdrop = { hidden ->
                    if (overlay === view) view.hideBackdropAfterFrame(hidden) else hidden()
                },
                onClosed = { if (overlay === view) removeOverlay() },
            )
        ) removeOverlay()
    }

    private fun showBuiltInMorePanel(view: CornerRadialOverlayView) {
        val params = createOverlayParams(focusable = true)
        try {
            windowManager.updateViewLayout(view, params)
            view.showBuiltInMorePanel()
            view.requestFocus()
        } catch (exception: RuntimeException) {
            logger(Log.WARN, "SYSTEM_OVERLAY_FOCUS_FAILED", exception)
            removeOverlay()
        }
    }

    override fun onDismissRequested() {
        activeEnvironmentApproved = false
        gestureEngine.cancel()
        removeOverlay()
    }

    override fun onCleanupFailed(throwable: Throwable) {
        logOverlayFailure("SYSTEM_OVERLAY_DISPOSE_FAILED", throwable)
    }

    /**
     * @author bomo 轮盘几何回报（见 `CornerRadialOverlayView.onRadialMetrics`）。
     * `radiusPx` 才是**实际渲染**半径：它已经含 `fitScale` 收口与轮盘缩放百分比，
     * 与 `RadialIconGeometry.BASE_RADIUS_DP` 不是一回事（358dp 曾被屏宽压成 330dp 的观感）。
     */
    override fun onRadialMetrics(radiusPx: Float, iconPx: Float) {
        logger(
            Log.DEBUG,
            "RADIAL_METRICS radiusPx=${radiusPx.toInt()} iconPx=${iconPx.toInt()} " +
                "scalePercent=${lastSettings.radialScalePercent}",
            null,
        )
    }

    /**
     * @author bomo 轮盘落点回报（见 `CornerRadialOverlayView.onRadialPlacement`）。
     * 位置类投诉先看这一行：`origin` 应约等于 (屏宽 − 右 inset, 屏高 − 底 inset)，
     * 即物理右下角（右侧呼出）/ 左下角（左侧呼出）。
     */
    override fun onRadialPlacement(
        originX: Float,
        originY: Float,
        viewWidth: Int,
        viewHeight: Int,
        insets: OverlaySafeInsets,
    ) {
        logger(
            Log.DEBUG,
            "RADIAL_PLACEMENT origin=${originX.toInt()},${originY.toInt()} " +
                "view=${viewWidth}x$viewHeight " +
                "insets=${insets.left.toInt()},${insets.top.toInt()}," +
                "${insets.right.toInt()},${insets.bottom.toInt()}",
            null,
        )
    }

    private fun createOverlayParams(focusable: Boolean): WindowManager.LayoutParams {
        // 非聚焦窗只监听指针（触摸交给 WMS 指针监听链路）；聚焦面板要自己收按键。
        val inputFlags =
            if (focusable) {
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
            } else {
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            OVERLAY_WINDOW_TYPE,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                inputFlags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.FILL
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            setFitInsetsTypes(0)
            title = "FlymeFreeformCornerOverlay"
        }
    }

    private fun cancelActiveGesture() {
        activeGestureConfig = null
        gestureEngine.cancel()
        overlay?.cancelGesture()
    }

    private fun removeOverlay() {
        val view = overlay ?: return
        overlay = null
        if (sidebar.isPending) sidebar.cancel()
        morePanelActive = false
        val cleanupFailure = view.disposeOverlay()
        try {
            windowManager.removeViewImmediate(view)
        } catch (_: IllegalArgumentException) {
            // 已被系统移除；本地所有权仍需清空。
        } catch (exception: RuntimeException) {
            logger(Log.WARN, "SYSTEM_OVERLAY_REMOVE_FAILED", exception)
        }
        cleanupFailure?.let { throwable ->
            logOverlayFailure("SYSTEM_OVERLAY_DISPOSE_FAILED", throwable)
        }
    }

    private fun isGestureEnvironmentAllowed(): Boolean {
        if (!environmentState.isGestureAllowed(refreshKeyguard = true)) return false
        return !isCriticalSystemUiForeground()
    }

    private fun isDynamicEnvironmentAllowed(): Boolean = environmentState.isGestureAllowed()

    private fun isCriticalSystemUiForeground(): Boolean {
        focusedWindowPackage()?.let { packageName ->
            if (packageName in CRITICAL_PACKAGES) return true
        }
        return try {
            val atms = readField(controller, "mAtms") ?: return false
            val root = readField(atms, "mRootWindowContainer") ?: return false
            val task = findMethod(root.javaClass, "getTopDisplayFocusedRootTask", 0).invoke(root) ?: return false
            val activity =
                findMethod(task.javaClass, "topRunningActivity", 0).invoke(task) ?: return false
            val packageName = readField(activity, "packageName") as? String ?: return false
            packageName in CRITICAL_PACKAGES
        } catch (_: ReflectiveOperationException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun focusedWindowPackage(): String? =
        try {
            val windowManagerService = readWindowManagerService()
            val root = readField(windowManagerService, "mRoot") ?: return null
            val display =
                findMethod(root.javaClass, "getTopFocusedDisplayContent", 0).invoke(root) ?: return null
            val window = readField(display, "mCurrentFocus") ?: return null
            findMethod(window.javaClass, "getOwningPackage", 0).invoke(window) as? String
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: RuntimeException) {
            null
        }

    private fun readWindowManagerService(): Any {
        val atms = readField(controller, "mAtms") ?: throw NoSuchFieldException("mAtms")
        return readField(atms, "mWindowManager") ?: throw NoSuchFieldException("mWindowManager")
    }

    /**
     * @author bomo 输入法底部高度（px）—— **只做诊断，不再参与避让**。
     *
     * 2026-10-16 结论：轮盘位置恒定在屏幕角落，被输入法遮挡的问题改由窗口层级解决
     * （见 [OVERLAY_WINDOW_TYPE]）。这里保留取数只为在日志里留一条
     * `RADIAL_IME_INSET value=… src=…`，方便确认「键盘到底弹了多高」。
     *
     * 两条路径，**必须先问 InsetsState**：
     * 1. 小窗所在 `DisplayContent` 的 raw `InsetsState` 里 `ITYPE_IME` 源（真实键盘高度，
     *    实测 `visibleFrame=[0,1536][1080,2344]` → 808px）；
     * 2. `mImeWindow`（ColorOS 17）/ `mInputMethodWindow`（旧版）的 `getFrame()` ——
     *    ColorOS 17 上这个 frame 是**整块屏幕**（实测 `[0,120][1080,2344]`），
     *    `displayHeight - frame.top` 会得到 2224px 的假值，只能当兜底。
     */
    private fun currentImeBottomInset(): Float {
        val displayHeight = context.resources.displayMetrics.heightPixels
        val display = displayContentForIme()
        if (display == null) return recordImeInset(0f, "no_display")
        imeInsetFromInsetsState(display, displayHeight)?.let { return recordImeInset(it, "insets_state") }
        imeInsetFromImeWindow(display, displayHeight)?.let { return recordImeInset(it, "ime_window") }
        return recordImeInset(0f, "unavailable")
    }

    private fun recordImeInset(
        value: Float,
        source: String,
    ): Float {
        logger(Log.DEBUG, "RADIAL_IME_INSET value=$value src=$source", null)
        return value
    }

    /** 优先用小窗自己所在的显示区（`FlexibleTaskController.mFlexibleTasks`，与外部点击 hook 同一条路径）。 */
    private fun displayContentForIme(): Any? {
        try {
            val tasks = readField(controller, "mFlexibleTasks")
            if (tasks != null) {
                val task =
                    synchronized(tasks) {
                        (tasks as? Iterable<*>)?.firstOrNull { it != null }
                    }
                if (task != null) {
                    findMethod(task.javaClass, "getDisplayContent", 0).invoke(task)?.let { return it }
                }
            }
        } catch (_: ReflectiveOperationException) {
        } catch (_: RuntimeException) {
        }
        return try {
            val windowManagerService = readWindowManagerService()
            val root = readField(windowManagerService, "mRoot") ?: return null
            findMethod(root.javaClass, "getTopFocusedDisplayContent", 0).invoke(root)
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    /** 路径 1：`DisplayContent` 里的输入法窗口。返回 null = 这条路径不可用，换下一条。 */
    private fun imeInsetFromImeWindow(
        display: Any,
        displayHeight: Int,
    ): Float? =
        try {
            val imeWindow =
                readField(display, "mImeWindow")
                    ?: readField(display, "mInputMethodWindow")
                    ?: return null
            val visible =
                findMethod(imeWindow.javaClass, "isVisible", 0).invoke(imeWindow) as? Boolean
            if (visible != true) return 0f
            val frame =
                findMethod(imeWindow.javaClass, "getFrame", 0).invoke(imeWindow)
                    as? android.graphics.Rect ?: return null
            imeInsetFromFrame(frame, displayHeight)
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: RuntimeException) {
            null
        }

    /** 路径 2：显示区 raw `InsetsState` 的 IME 源 frame。 */
    private fun imeInsetFromInsetsState(
        display: Any,
        displayHeight: Int,
    ): Float? =
        try {
            val insetsController =
                findMethod(display.javaClass, "getInsetsStateController", 0).invoke(display)
                    ?: return null
            val insetsState =
                findMethod(insetsController.javaClass, "getRawInsetsState", 0).invoke(insetsController)
                    ?: return null
            val source =
                findMethod(insetsState.javaClass, "getSource", 1)
                    .invoke(insetsState, WindowInsets.Type.ime())
                    ?: return null
            val visible = findMethod(source.javaClass, "isVisible", 0).invoke(source) as? Boolean
            if (visible != true) return 0f
            val frame =
                findMethod(source.javaClass, "getFrame", 0).invoke(source) as? android.graphics.Rect
                    ?: return null
            imeInsetFromFrame(frame, displayHeight)
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: RuntimeException) {
            null
        }

    /**
     * 输入法 frame → 底部避让量（px）。
     *
     * frame 明显不合理（空 / 不在屏幕内）时返回 null，交给下一条路径；
     * 上限取屏幕高度的 [MAX_IME_HEIGHT_RATIO]：键盘不可能那么高。一旦拿到坏 frame，
     * 宁可把轮盘画小一点，也不能让 `fitScale` 把整圈缩成一个点（表现出来就是「只有遮罩没有轮盘」）。
     */
    private fun imeInsetFromFrame(
        frame: android.graphics.Rect,
        displayHeight: Int,
    ): Float? {
        if (frame.height() <= 0) return null
        val inset = (displayHeight - frame.top).toFloat()
        if (inset <= 0f) return null
        return inset.coerceAtMost(displayHeight * MAX_IME_HEIGHT_RATIO)
    }

    private fun readField(instance: Any, name: String): Any? {
        var current: Class<*>? = instance.javaClass
        while (current != null) {
            try {
                return current.getDeclaredField(name).also { it.isAccessible = true }.get(instance)
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            }
        }
        return null
    }

    private fun findMethod(type: Class<*>, name: String, parameterCount: Int): java.lang.reflect.Method {
        var current: Class<*>? = type
        while (current != null) {
            current.declaredMethods.firstOrNull { method ->
                method.name == name && method.parameterCount == parameterCount
            }?.let { method ->
                method.isAccessible = true
                return method
            }
            current = current.superclass
        }
        throw NoSuchMethodException(name)
    }

    private data class QueuedPointerEvent(val event: MotionEvent, val generation: Long)

    private companion object {
        /** 小窗启动链路不使用图标，占位 1x1 即可。 */
        val EMPTY_ICON = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

        /** @author bomo 支付栏兜底自动消失时间（正常由「小窗关闭」收掉，这里只防泄漏）。 */
        const val PAY_BAR_TIMEOUT_MS = 60_000L

        /** @author bomo 支付栏跟随小窗的轮询间隔（25fps）。小窗拖动缩放时要「黏」在窗口上。 */
        const val PAY_BAR_TICK_MS = 40L

        /** @author bomo 等小窗出现的挂栏超时；超时还没小窗就退回屏幕底部（只挂一次）。 */
        const val PAY_BAR_ATTACH_TIMEOUT_MS = 6_000L

        /** @author bomo 等小窗出现期间的轮询间隔。 */
        const val PAY_BAR_ATTACH_TICK_MS = 40L

        /** @author bomo 切换支付方式时，等旧小窗收完再启动新页面的延迟。 */
        const val PAY_SWITCH_DELAY_MS = 250L

        /** @author bomo 连点防抖窗口：支付栏切换过快会出问题，2.5 秒内只响应一次。 */
        const val PAY_SWITCH_BLOCK_MS = 2500L

        /** @author bomo 连续多少拍读不到小窗才认为小窗真的关了（40ms 一拍 → 120ms）。 */
        const val PAY_BAR_MISSING_TICKS = 3

        /** @author bomo 切换支付方式期间的免收栏宽限期（旧窗关掉到新窗出来）。 */
        const val PAY_BAR_SWITCH_GRACE_MS = 2_500L

        /** @author bomo 发完切换后多久还没看到小窗就自动补发一次（修「要点两下」）。 */
        const val PAY_SWITCH_VERIFY_MS = 1_200L

        /** @author bomo task 已经没了（比 bounds 更早）时的收栏拍数，2 拍≈80ms。 */
        const val PAY_BAR_GONE_TICKS = 2

        /** @author bomo 停手多久后把支付栏重新显示出来（ms）；要盖住 ColorOS 松手后的回弹动画。 */
        const val PAY_BAR_SETTLE_MS = 320L

        /** @author bomo 栏刚挂上后的这段时间内不按「几何变化」判拖动（ms），避免首现被藏。 */
        const val PAY_BAR_STARTUP_GRACE_MS = 400L

        /** @author bomo 小窗读数断层后的抑制期（ms）：这段时间一律不许把栏放出来，防「闪一下」。 */
        const val PAY_BAR_CLOSING_GUARD_MS = 800L

        /** 停手后先确认这么久（ms）再显示，避免「刚显示就收栏」的闪烁。 */
        const val PAY_BAR_CONFIRM_MS = 300L

        /** @author bomo 支付栏出现 / 消失的淡入淡出时长（ms）。 */
        const val PAY_BAR_FADE_IN_MS = 55L
        const val PAY_BAR_FADE_OUT_MS = 120L

        /** @author bomo 可见高度低于它的算迷你小窗（dp）；正常小窗远高于此。 */
        const val PAY_BAR_MINI_MAX_HEIGHT_DP = 110f

        /** @author bomo 可见宽度小于屏幕这个比例的也按迷你处理（兜底：迷你态 bounds 可能仍是布局尺寸）。 */
        const val PAY_BAR_MINI_MAX_WIDTH_RATIO = 0.45f

        /** 切换后新窗出现再多保护这么久（ms），避免新位置那一次几何跳变把栏藏掉。 */
        const val PAY_BAR_SWITCH_SETTLE_MS = 400L

        /** 0.2.70 切换支付方式后「新窗最迟什么时候算落地」：跳板 Activity 不换 task 也不换 Activity，到点就别再等。 */
        const val PAY_SWITCH_LAND_MS = 900L

        /** 0.2.73 翻 WMS 窗口表看「验证弹窗在不在」的节流间隔（ms）。 */
        const val PAY_BAR_AUTH_CHECK_MS = 150L

        /** @author bomo 支付栏与小窗之间留的间隙（dp）。 */
        const val PAY_BAR_GAP_DP = 8f

        /** @author bomo 支付栏宽度 = 小窗宽度（同宽）；高度 = 宽度 × 0.13（Flyme 观感）。 */
        const val PAY_BAR_HEIGHT_RATIO = 0.16f

        /** @author bomo 支付栏图标栅格化尺寸（dp），仅在拿不到原厂 PNG 时使用。 */
        const val PAY_BAR_ICON_DP = 34f

        const val CATALOG_THREAD_NAME = "FlymeFreeform-Catalog"

        /** @author bomo SystemUI 包名：握手回执只定向发它，避免广播扩散。 */
        const val SYSTEM_UI_PACKAGE = "com.android.systemui"

        /** 设置侧目录副本的图标边长（px）。 */
        const val TOOL_ICON_PX = 72
        const val OVERLAY_FAILURE_LOG_INTERVAL_MS = 10_000L

        /** 输入法避让量的上限（占屏幕高度比例）；坏 frame 时宁可把轮盘画小，也不能让整圈缩成一个点。 */
        const val MAX_IME_HEIGHT_RATIO = 0.55f

        /**
         * @author bomo 轮盘 / 面板的窗口类型 —— **不要改回 `TYPE_APPLICATION_OVERLAY`**。
         *
         * 实机 `dumpsys window windows` 的 `mBaseLayer` 就是 WMS 层级表（layer × 10000）：
         * `APPLICATION_OVERLAY`(2038) = 111000（层 11） **低于** `INPUT_METHOD`(2011) = 131000（层 13），
         * 所以键盘一弹就把贴角的轮盘整圈盖住（用户 2026-10-16 报「只有遮罩没轮盘」）。
         * `TYPE_INPUT_METHOD_DIALOG`(2012) 层 14：刚好压在输入法上面、状态栏（层 15）下面，
         * 不产生 insets 源、不碰输入法本身，是满足「轮盘位置不动又不被键盘盖住」的最小改动。
         */
        val OVERLAY_WINDOW_TYPE = WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG
        val CRITICAL_PACKAGES =
            setOf(
                "com.android.systemui",
                "com.android.permissioncontroller",
                "com.google.android.permissioncontroller",
                "com.android.packageinstaller",
            )
    }
}
