package com.eta.aicybox.sideindex.overlay

import android.os.Handler
import android.os.Looper
import android.graphics.Rect
import android.view.InputEvent
import android.view.MotionEvent
import com.eta.aicybox.sideindex.LxApi
import com.eta.aicybox.sideindex.util.XLog
import kotlin.math.abs

/**
 * 感应带区域的触摸来源（1.31.0 起）+ 控制中心屏蔽（1.31.2 起）。
 *
 * 为什么不再用悬浮窗收触摸：感应带窗口一旦可触摸，它就是那块区域的**触摸目标** ——
 *   ①系统返回手势会把这次边缘滑动当成"非桌面窗口上的滑动"而冒返回箭头；
 *   ②窗口吞掉 DOWN 之后，桌面自己的翻页手势再也拿不到这根手指。
 * 试过的手势排除（`systemGestureExclusionRects`）在 ColorOS 上对 overlay 窗口**不生效**。
 *
 * 所以改成：感应带窗口**始终不可触摸**（触摸落给桌面/应用自己，桌面该翻页翻页、该返回返回），
 * 模块只**旁听** SystemUI 的返回手势监视器
 * （`com.android.systemui.shared.system.InputChannelCompat$InputEventReceiver$1`，监听器是 ColorOS 的
 * `DisplayBackGestureHandlerImpl$inputEventReceiver$1`，它能收到整块屏幕的触摸），
 * 把"落在感应带范围内"的那一根手势转给 OverlayController 做呼出判定。
 *
 * ⚠️ 旁听**绝不能吞事件**：在监视器/监听器这层吞掉（无论 finishInputEvent 传 true 还是 false）
 *    会让窗口彻底收不到这次触摸（1.30.27/30 实测：返回没了、索引也呼不出、桌面也不翻页）。
 *    回调还跑在 SystemUI 的 "GestureUI" 线程上，动视图必须回主线程（见 OverlayController 里的拷贝 + post）。
 *
 * 控制中心：桌面上"下滑打开控制中心"是桌面向 SystemUI 要面板，抢指针不一定拦得住，
 * 所以感应带内手势进行期间直接跳过 shade 的展开入口（用户要求：区内下滑不拉控制中心，区外照旧）。
 */
object BackGestureGate {

    /**
     * 判定「向下滑」的最小位移（px）。超过就认为用户想下拉控制中心，
     * 于是抢指针把它拦掉。取 8px 是「看得出方向、又不至于太晚」的折中：
     * 太晚（比如 30px）控制中心已经开始跟手，拦下来观感是「拉到一半又缩回去」。
     */
    private const val SHADE_PILFER_SLOP_PX = 8f

    /** 主线程 handler：抢指针必须回主线程做，见下面 MOVE 分支的注释。 */
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 一根已经接管的带内手势被旁听侧丢弃时的回调（1.31.23）。
     *
     * 必须通知控制器：否则它还在等 UP，状态机会永久卡在 ACTIVE —— 浮层挂在屏幕上、
     * 点不到、也收不起来，而触摸全落到后面的应用（用户实测就是这个现象）。
     */
     var onGestureDropped: (() -> Unit)? = null

    /** 感应带在屏幕坐标系里的范围（左右各一条，由 OverlayController 更新） */
    @Volatile private var bandRight: Rect? = null
    @Volatile private var bandLeft: Rect? = null

    /** 是否要把感应带内的手势转给模块（= 模块要不要接管这次呼出判定） */
    @Volatile private var active = false

    /**
     * 前台是不是桌面（由控制器在心跳/即时判定里同步）。
     *
     * 只给「触发区内下滑拦控制中心」用：桌面下拉走的是桌面自己那条链路，
     * 而应用内下拉是 SystemUI 的事（已有 CommandQueue 拦截）。
     * 全局模式下 [active] 到处为 true，不加这个守卫会把应用内的下滑一起抢掉。
     */
    @Volatile private var onDesktop = false

    /** 触摸回调：(事件, 是否右侧) -> Unit。由 OverlayController 装 */
    @Volatile var onTouch: ((MotionEvent, Boolean) -> Unit)? = null

    /**
     * 手指落在感应带内、但 [active] 还是心跳给的旧值时，问一次控制器「现在该不该接管」。
     * 控制器会同步刷新前台判定并回写 [active]（见 OverlayController.probeTakeoverNow）。
     *
     * 为什么需要：`active` 只由心跳更新，而心跳是 2.5 秒一拍 —— 从应用回到桌面后最长
     * 2.5 秒里它还是 false，这一根手势会被下面的 DOWN 分支直接丢掉。用户表现为
     * 「点开图片回桌面立刻侧滑呼不出索引」，偶发（取决于心跳相位）。
     */
    @Volatile var onDownProbe: (() -> Unit)? = null

    private var installed = false
    private val seenReceivers = HashSet<String>()

    /** 只在第一次说：出现过哪几类手势接收器（排障用，恒输出） */
    private val compatSeen = HashSet<String>()

    /** 只在第一次说：哪一类手势接收器挖不到监听器字段 */
    private val compatMiss = HashSet<String>()
    private val hookedListeners = HashSet<String>()

    /** 本次手势是否从感应带内开始（开始后整根跟着走，手指滑出带子也算） */
    private var trackingSide = 0

    /** 感应带内是否有手势正在进行（屏蔽控制中心的判据） */
    @Volatile private var bandGestureActive = false

    /**
     * 本根带内手势的起点。用于在「桌面触发区快速下滑」时尽早抢指针，
     * 阻断桌面继续把这根手势解释成控制中心下拉；触发区外不受影响。
     */
    private var bandDownX = 0f
    private var bandDownY = 0f
    private var shadePilfered = false

    /** 上一次处理过的事件指纹：SystemUI 里有两个手势监视器，同一个事件会被各送一遍 */
    private var lastEventKey = ""

    /** 抢指针用的监视器（从返回手势处理器里反射拿） */
    @Volatile private var inputMonitor: Any? = null
    private var monitorSearched = false

    fun install(cl: ClassLoader) {
        if (installed) return
        installed = true
        hookDispatch(cl)
        hookReceivers(cl)
        hookShadeExpansion(cl)
    }

    /**
     * @author bomo 2026-10-20 兜底旁听：直接钩 `InputEventReceiver.dispatchInputEvent`。
     *
     * 为什么需要：ColorOS 的返回手势监视器（`DisplayBackGestureHandlerImpl`）在 **SystemUI 启动早期**
     * 就把它的 `InputChannelCompat$InputEventReceiver` 实例建好了，而模块是延迟注入的 ——
     * 只钩构造函数的话一个实例都碰不到（0.3.5 实测：日志里一个「手势接收器」都没出现，
     * 手势完全旁听不到，索引永远呼不出）。
     *
     * `dispatchInputEvent` 是 `InputEventReceiver` 自己的私有入口，所有子类（含已存在的实例）
     * 的每个输入事件都会经过它，所以在这里拦不依赖实例何时创建。
     *
     * 代价：SystemUI 进程里每个输入事件都会过一下这里，所以关着（[active] = false）时尽快返回。
     */
    private fun hookDispatch(cl: ClassLoader) {
        val cls = LxApi.findClass("android.view.InputEventReceiver", cl)
        if (cls == null) {
            XLog.w("触摸旁听：进程里没有 InputEventReceiver（无法旁听）")
            return
        }
        val m = try {
            cls.declaredMethods.firstOrNull {
                it.name == "dispatchInputEvent" && it.parameterTypes.size == 2 &&
                    it.parameterTypes[1] == InputEvent::class.java
            }
        } catch (_: Throwable) {
            null
        }
        if (m == null) {
            XLog.w("触摸旁听：没找到 InputEventReceiver.dispatchInputEvent")
            return
        }
        val ok = LxApi.hookBefore(m) { scope ->
            try {
                onRawEvent(scope.chain.args.getOrNull(1) as? MotionEvent)
            } catch (_: Throwable) {
            }
        }
        XLog.w(if (ok) "触摸旁听：dispatchInputEvent 已接管（覆盖已存在的接收器）" else "触摸旁听：dispatchInputEvent 钩挂失败")
    }

    /**
     * 来自 [hookDispatch] 的事件。
     *
     * 一个 receiver 的事件坐标是**它自己窗口**的坐标系：只有全屏（窗口在 0,0）的那几条通道
     * （返回手势监视器、锁屏、通知中心）才满足 raw == 本地坐标，用这个条件把窗口量级的事件滤掉。
     * 锁屏／控制中心那条由 `SystemCover` 在 DOWN 时挡掉（见 onListenerEvent）。
     */
    private fun onRawEvent(e: MotionEvent?) {
        if (e == null) return
        val down = e.actionMasked == MotionEvent.ACTION_DOWN
        if (!active && !down) return
        val rx = e.rawX
        val ry = e.rawY
        if (abs(rx - e.x) > 1f || abs(ry - e.y) > 1f) return
        if (down && sideOf(rx, ry) == 0) return
        val copy = try {
            MotionEvent.obtain(e)
        } catch (_: Throwable) {
            return
        }
        try {
            copy.setLocation(rx, ry)
            onListenerEvent(copy)
        } finally {
            copy.recycle()
        }
    }

    fun update(right: Rect?, left: Rect?, on: Boolean) {
        bandRight = right
        bandLeft = left
        setActive(on)
    }

    /** 同步「现在是不是桌面」。触发区内下滑拦控制中心要用（见 onDesktop）。 */
    fun setOnDesktop(value: Boolean) {
        onDesktop = value
    }

    /**
     * 只更新「要不要接管」，不动感应带范围。
     *
     * 给手势路径上的**即时判定**用（见 [onDownProbe]）：[active] 平时只由心跳更新，
     * 心跳是 2.5 秒一拍，从应用回到桌面后最长 2.5 秒里它还是 false。
     */
    fun setActive(on: Boolean) {
        active = on
        if (!on) {
            trackingSide = 0
            bandGestureActive = false
        }
    }

    // ---------------- 抢指针 ----------------

    /** 呼出成立时抢走指针（桌面/应用的同期手势收到 cancel） */
    fun pilfer() {
        for (m in monitorCandidates) pilferOn(m, 0)
    }

    private val monitorCandidates = ArrayList<Any>()

    /**
     * ColorOS 里 `DisplayBackGestureHandlerImpl.inputMonitorCompat` 拿到的不是
     * `android.view.InputMonitor` 本体（1.31.1 实测：直接调 pilferPointers 抛 NoSuchMethodError），
     * 它是个只有 dispose/getInputReceiver/getSurface 的包装。所以把"类型名带 InputMonitor"的对象
     * 全收集起来（含包装里挖出来的），逐个试常见名字；全失败就把候选对象的方法名打进日志。
     */
    private fun pilferOn(m: Any, depth: Int) {
        for (n in listOf("pilferPointers", "pilfer")) {
            try {
                LxApi.callMethod(m, n)
                XLog.d { "呼出抢指针：${m.javaClass.simpleName}.$n() 成功" }
                return
            } catch (_: Throwable) {
            }
        }
        if (depth < 2) {
            for (g in listOf("getInputMonitor", "getRealInputMonitor", "getMonitor")) {
                try {
                    val inner = LxApi.callMethod(m, g) ?: continue
                    if (inner !== m) {
                        pilferOn(inner, depth + 1)
                        return
                    }
                } catch (_: Throwable) {
                }
            }
        }
        XLog.d {
            "呼出抢指针失败：${m.javaClass.name} 方法=" +
                m.javaClass.methods.joinToString(",") { it.name }
        }
    }

    // ---------------- 控制中心屏蔽 ----------------

    private fun hookShadeExpansion(cl: ClassLoader) {
        val cls = LxApi.findClass("com.android.systemui.statusbar.CommandQueue", cl)
        if (cls == null) {
            XLog.d("控制中心屏蔽：进程里没有 CommandQueue")
            return
        }
        var n = 0
        var expand = 0
        for (m in cls.declaredMethods) {
            val rt = m.returnType
            val canSkip = rt == java.lang.Void.TYPE || rt == java.lang.Boolean.TYPE
            val isExpand = m.name.contains("xpand")
            if (!isExpand && !canSkip) continue
            val ok = LxApi.hookBefore(m) { scope ->
                if (!bandGestureActive) return@hookBefore
                // 诊断：感应带内手势期间，CommandQueue 上到底被调了哪些方法（找控制中心那条路）
                XLog.d { "控制中心屏蔽：手势期间 CommandQueue.${m.name} 被调用" }
                if (isExpand && canSkip) {
                    XLog.i { "控制中心屏蔽：感应带内手势期间跳过 ${m.name}" }
                    scope.setResult(if (rt == java.lang.Boolean.TYPE) false else null)
                }
            }
            if (ok) {
                n++
                if (isExpand) expand++
            } else {
                XLog.d { "控制中心屏蔽：钩 ${m.name} 失败" }
            }
        }
        XLog.i { "控制中心屏蔽：CommandQueue 钩住 $n 个入口（其中展开 $expand 个）" }
    }

    // ---------------- 触摸旁听 ----------------

    private fun hookReceivers(cl: ClassLoader) {
        val base = LxApi.findClass("android.view.InputEventReceiver", cl)
        if (base == null) {
            XLog.w("触摸旁听：进程里没有 InputEventReceiver（无法旁听）")
            return
        }
        val n = LxApi.hookConstructorsAfter(base) { obj ->
            try {
                onReceiverCreated(obj)
            } catch (_: Throwable) {
            }
        }
        if (n > 0) {
            XLog.w("触摸旁听：已开始监听输入接收器（$n 个构造）")
        } else {
            XLog.w("触摸旁听：钩 InputEventReceiver 构造失败")
        }
    }

    private fun onReceiverCreated(obj: Any) {
        val cn = obj.javaClass.name
        if (seenReceivers.add(cn)) XLog.d { "触摸旁听：输入接收器 $cn" }
        if (!cn.contains("InputChannelCompat")) return
        if (compatSeen.add(cn)) XLog.w("触摸旁听：出现手势接收器 $cn")
        val listener = findListener(obj)
        if (listener == null) {
            if (compatMiss.add(cn)) XLog.w("触摸旁听：$cn 找不到监听器字段（这一类旁听不到）")
            return
        }
        hookListener(listener, cn)
    }

    private fun findListener(obj: Any): Any? {
        val label = obj.javaClass.name
        try {
            var cls: Class<*>? = obj.javaClass
            var depth = 0
            while (cls != null && depth < 4) {
                for (f in cls.declaredFields) {
                    if (!f.name.contains("istener", ignoreCase = true)) continue
                    try {
                        f.isAccessible = true
                        val v = f.get(obj) ?: continue
                        val vc = v.javaClass.name
                        if (vc.startsWith("com.android") || vc.startsWith("com.oplus")) {
                            XLog.d { "触摸旁听：$label 的监听器 = $vc" }
                            return v
                        }
                    } catch (t: Throwable) {
                    }
                }
                cls = cls.superclass
                depth++
            }
        } catch (_: Throwable) {
        }
        return null
    }

    private fun hookListener(listener: Any, owner: String) {
        val lc = listener.javaClass.name
        if (!hookedListeners.add(lc)) return
        var n = 0
        for (m in listener.javaClass.declaredMethods) {
            val first = m.parameterTypes.firstOrNull() ?: continue
            val paramOk = first == MotionEvent::class.java || first == InputEvent::class.java ||
                (first == java.lang.Object::class.java && m.name == "invoke")
            if (!paramOk) continue
            val ok = LxApi.hookBefore(m) { scope ->
                onListenerEvent(scope.chain.args.firstOrNull() as? MotionEvent)
            }
            if (ok) n++ else XLog.d { "触摸旁听：钩 ${m.name} 失败" }
        }
        if (n > 0) {
            XLog.w("触摸旁听：监听器 $lc（来自 $owner）钩住 $n 个入口")
        } else {
            XLog.w("触摸旁听：监听器 $lc 没有可钩的入口")
        }
        if (lc.contains("BackGesture")) findInputMonitor(listener)
    }

    /**
     * 旁听回调本体。
     *
     * ⚠️ 跑在 SystemUI 的 "GestureUI" 输入回调线程上：这里只能做本地、只读的事，
     * 任何 binder（尤其 pilfer）都要 `mainHandler.post` 回主线程，否则输入通道会卡死（1.31.23）。
     */
    private fun onListenerEvent(e: MotionEvent?) {
        if (e == null) return
        // 同一个事件会被两个监视器各送一遍（实测一次手势打出两组"手势成立"）：
        // 按 downTime+action+坐标 去重，只处理第一份
        val key = e.downTime.toString() + ":" + e.actionMasked + ":" +
            e.x.toInt() + ":" + e.y.toInt()
        if (key == lastEventKey) return
        lastEventKey = key
        // 只旁听、不改写返回值 —— 吞事件会让窗口彻底收不到这根手指

        // DOWN 单独走：先看手指落在哪；落在带内但 active 还是心跳给的旧值时，
        // 做一次即时判定（onDownProbe）。不这么做，「从应用回桌面后立刻侧滑」
        // 会因 active 陈旧而被整根丢掉 —— 心跳 2.5 秒一拍，实测偶发。
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            val side = sideOf(e.x, e.y)

            // 返回箭头正在显示 → 这根手指不给索引（1.31.17）。
            // 覆盖两个方向：
            //   ①刚呼出箭头、还没开始滑动 —— 别和返回手势抢；
            //   ②**把箭头慢慢推回去**的途中（不松手所以不触发返回）——
            //     那根向内滑动会被下面的呼出判定当成索引手势，误把索引弹出来。
            //     用户 2026-10-16 实测的场景。
            // 只在落带内时查（每个事件都反射太贵）。
            if (side != 0) {
                // 系统界面自己盖在上面（1.32.4）：通知中心/控制中心展开、锁屏可见 → 不给索引。
                // 这些界面上从边缘左右滑，以前会被判成索引呼出、把索引盖在它们上面。
                // 只读本地字段，不碰 binder，可以在输入回调线程上直接调。
                val covered = try {
                    SystemCover.blockedReason()
                } catch (_: Throwable) {
                    null
                }
                if (covered != null) {
                    trackingSide = 0
                    bandGestureActive = false
                    XLog.d { "系统遮挡（$covered）：本次手势不参与索引（DOWN 落在带内）" }
                    return
                }
                val arrow = try {
                    BackGestureUi.isShowing()
                } catch (_: Throwable) {
                    false
                }
                if (arrow) {
                    trackingSide = 0
                    bandGestureActive = false
                    XLog.d("返回箭头显示中：本次手势不参与索引（DOWN 落在带内）")
                    return
                }
            }

            if (side != 0 && !active) {
                try {
                    onDownProbe?.invoke()
                } catch (_: Throwable) {
                }
            }
            trackingSide = if (active) side else 0
            bandGestureActive = trackingSide != 0
            bandDownX = e.x
            bandDownY = e.y
            shadePilfered = false
            if (trackingSide != 0) onTouch?.invoke(e, trackingSide == 1)
            return
        }

        if (!active) {
            val wasTracking = trackingSide != 0
            trackingSide = 0
            bandGestureActive = false
            if (wasTracking) {
                try {
                    onGestureDropped?.invoke()
                } catch (_: Throwable) {
                }
            }
            return
        }

        // 带内手势进行中：一旦判定为「向下滑」就抢指针。
        // 桌面下拉控制中心走的是桌面自己那条链路（不是 SystemUI 的 CommandQueue），
        // 只在呼出成立时才 pilfer 已经太晚 —— 控制中心已经跟手下拉了。
        // 这里在 MOVE 阶段尽早抢：桌面收到 cancel，就不会继续展开控制中心；
        // 触发区外的手势根本不进这里，桌面下拉照旧。
        if (trackingSide != 0 && !shadePilfered && onDesktop) {
            val dy = e.y - bandDownY
            val dx = e.x - bandDownX
            if (dy > 0f && abs(dy) > abs(dx) && abs(dy) > SHADE_PILFER_SLOP_PX) {
                shadePilfered = true
                // ⚠️ 绝不能在这条线程上直接 pilfer()：这是 SystemUI 的输入回调
                // 线程（"GestureUI"），抢指针要向 system_server 发请求，而它可能
                // 正等这次事件 finishInputEvent 结束 → 死锁。1.31.22 实测偶发
                // 「索引定格、点不到、却能操作后面的应用」= 输入通道卡死。
                // 挪到主线程只晚几毫秒，换来不卡死。
                mainHandler.post { pilfer() }
                XLog.d { "触发区内下滑：抢指针拦控制中心 dy=${dy.toInt()} dx=${dx.toInt()}" }
            }
        }

        when (e.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val side = trackingSide
                trackingSide = 0
                bandGestureActive = false
                shadePilfered = false
                if (side != 0) onTouch?.invoke(e, side == 1)
                return
            }
        }
        if (trackingSide != 0) onTouch?.invoke(e, trackingSide == 1)
    }

    /**
     * 从返回手势处理器里找它的 InputMonitor（抢指针要用）。
     * 监听器往往是 `Xxx$inputEventReceiver$1` 这种匿名类，monitor 在外层对象里，
     * 所以走广度优先：自己的字段 → 类型像 SystemUI/Oplus 的字段值（比如 this$0）继续挖。
     */
    private fun findInputMonitor(listener: Any) {
        if (monitorSearched) return
        monitorSearched = true
        try {
            val queue = ArrayDeque<Any>()
            queue.add(listener)
            var visited = 0
            while (queue.isNotEmpty() && visited < 12) {
                val obj = queue.removeFirst()
                visited++
                var cls: Class<*>? = obj.javaClass
                var depth = 0
                while (cls != null && depth < 6 && cls != Any::class.java) {
                    for (f in cls.declaredFields) {
                        if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                        val type = f.type.name
                        try {
                            f.isAccessible = true
                            if (type.contains("InputMonitor")) {
                                val v = f.get(obj) ?: continue
                                if (!monitorCandidates.contains(v)) monitorCandidates.add(v)
                                XLog.i {
                                    "触摸旁听：候选监视器 ${cls?.simpleName}.${f.name} → ${v.javaClass.simpleName} " +

                                        "方法=" + v.javaClass.methods.joinToString(",") { it.name }
                                }
                                // 包装对象里可能藏着真身，继续往下挖
                                queue.add(v)
                                continue
                            }
                            if (type.startsWith("com.android.systemui") || type.startsWith("com.oplus")) {
                                f.get(obj)?.let { queue.add(it) }
                            }
                        } catch (t: Throwable) {
                        }
                    }
                    cls = cls.superclass
                    depth++
                }
            }
            XLog.d { "触摸旁听：没找到 InputMonitor（扫了 $visited 个对象）" }
        } catch (_: Throwable) {
        }

    }

    /** 1 = 右侧带内，2 = 左侧带内，0 = 都不在 */
    private fun sideOf(x: Float, y: Float): Int {
        val xi = x.toInt()
        val yi = y.toInt()
        bandRight?.let { if (it.contains(xi, yi)) return 1 }
        bandLeft?.let { if (it.contains(xi, yi)) return 2 }
        return 0
    }
}
