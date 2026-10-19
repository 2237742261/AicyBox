package com.eta.aicybox.platform.coloros

import android.content.ComponentName
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import java.lang.reflect.Field
import java.lang.reflect.Method
import kotlin.math.abs

/**
 * @author bomo 2026-10-19 在 system_server 里直接读写 ColorOS 小窗状态。
 *
 * 用途（聚合支付栏）：
 * - [topTaskBounds]：拿到当前最上层小窗的**屏幕上真实可见矩形** → 支付栏贴到它下方、并按它的宽度缩放；
 * - [hasTopTask]：一个小窗都不剩时立刻把支付栏收掉；
 * - [closeTopTask]：切换支付方式前关掉旧小窗。
 *
 * ⚠️ 几何坑（0.2.61 修正，用户 2026-10-19 反馈「支付栏挂在屏幕最底端」）：
 * ColorOS 小窗的 `Task.getBounds()` 与 `FlexibleTaskController.getFlexibleTaskVisibleBounds(Task)`
 * 拿到的都是**布局尺寸**，不是屏幕上的可见矩形。实测（计算器小窗）：
 * - 布局 bounds = `(104,264)-(1184,2184)`（1080x1920，右边还超出屏幕）；
 * - `ITaskExt.getScale()` = 0.8083；
 * - 屏幕上真正占的就是 `(104,264)-(977,1816)`（873x1552）。
 *
 * 也就是说可见矩形 = 布局 bounds 的 left/top 不动、宽高乘 scale
 * （ColorOS 内部方法 `scaleBoundsWithFixLeftTop` 就是这么算的）。
 * 用布局矩形画栏，栏就会比小窗宽、并掉到屏幕最底端。
 *
 * 反射目标全部来自本模块已在用的 `com.android.server.wm.FlexibleTaskController`
 * （见 `OutsideTapCloseHookInstaller` 里同一套方法名）：
 * `getTopZoomTask()`、`getFlexibleTaskVisibleBounds(task)`、`exitFlexibleTask(task, boolean, int, int)`、
 * `mFlexibleTasks`；缩放链是 `Task.getWrapper().getExtImpl().getScale()`。
 * 任何一步反射失败都**静默降级**（返回 null / false），由调用方退回「屏幕底部固定栏」。
 */
internal class ColorOsFlexibleWindowAccess(
    private val controller: Any,
    loader: ClassLoader,
    private val logger: (Int, String, Throwable?) -> Unit,
) {
    private val controllerClass: Class<*> =
        runCatching { loader.loadClass(CONTROLLER_CLASS) }.getOrElse { controller.javaClass }

    private val flexibleTasksField: Field? = findField("mFlexibleTasks")
    private val topZoomTaskMethod: Method? = findMethod("getTopZoomTask", 0)
    private val visibleBoundsMethod: Method? = findMethod("getFlexibleTaskVisibleBounds", 1)
    private val exitFlexibleTaskMethod: Method? = findMethod("exitFlexibleTask", 4)

    /**
     * @author bomo 2026-10-19 ColorOS 自己的手势状态：
     * `isTaskInDraggingOrScaling(Task)` / `isInDragingOrScaling()`。
     *
     * ⚠️ 为什么需要它：缩放小窗时 `Task.getBounds()` **不逐帧更新**（松手前后才提交），
     * 所以「靠几何连续变化猜手势」会漏判 —— 用户实测 0.2.62 拖动缩放时支付栏根本没藏。
     * 直接问 controller 的拖动/缩放状态，第一拍（40ms 内）就能藏起来。
     */
    private val taskAdjustingMethod: Method? = findMethod("isTaskInDraggingOrScaling", 1)
    private val anyAdjustingMethod: Method? = findMethod("isInDragingOrScaling", 0)

    /**
     * @author bomo 2026-10-19 小窗是否正在被关掉：`isDismissZoomWindow()` / `isForceHideZoomWindow()`。
     *
     * 关闭动画里 task 还活着、bounds 也会跳一下，支付栏在那一刻被放出来就会「闪一下又消失」
     * （用户实测）。这两个状态为真时一律保持隐藏。
     */
    private val dismissZoomMethod: Method? = findMethod("isDismissZoomWindow", 0)
    private val forceHideZoomMethod: Method? = findMethod("isForceHideZoomWindow", 0)

    /** @author bomo 2026-10-19 小窗是否收成了「迷你」形态（`isTaskInMiniFlexible(Task)`）。 */
    private val miniMethod: Method? = findMethod("isTaskInMiniFlexible", 1)

    private var failureLogged = false

    /** `Task.getBounds()` 的反射句柄（按 Task 实际类缓存）。 */
    private var cachedTaskClass: Class<*>? = null
    private var cachedTaskBoundsMethod: Method? = null

    /** `Task.getWrapper().getExtImpl().getScale()` 这条缩放链的反射句柄。 */
    private var scaleChainClass: Class<*>? = null
    private var taskWrapperMethod: Method? = null
    private var extImplMethod: Method? = null
    private var scaleMethod: Method? = null

    private var lastBoundsLog: String? = null
    private var lastBoundsLogAt = 0L

    /** @author bomo 2026-10-19 读 `Task.taskId`（判断切换支付方式后新窗有没有顶上来）。 */
    private var cachedTaskIdClass: Class<*>? = null
    private var cachedTaskIdField: Field? = null

    /** @author bomo 0.2.71 判断「小窗还在最上层吗」（`Task.isTopRootTaskInDisplayArea()` 等）。 */
    private var cachedOnTopClass: Class<*>? = null
    private var cachedTopInAreaMethod: Method? = null
    private var cachedFocusedRootMethod: Method? = null

    /** @author bomo 2026-10-19 读最上层 Activity（同 App 换页面时 taskId 不变，只能比 Activity）。 */
    private var cachedRecordClass: Class<*>? = null
    private var cachedRecordMethod: Method? = null
    private var cachedComponentClass: Class<*>? = null
    private var cachedComponentField: Field? = null

    /** 关键方法是否都拿到了；拿不到就别指望能贴小窗，直接用固定位置。 */
    val isUsable: Boolean
        get() = topZoomTaskMethod != null && visibleBoundsMethod != null

    /** 当前最上层小窗的**屏幕上真实可见区域**；没有小窗或反射失败返回 null。 */
    fun topTaskBounds(): Rect? {
        val task = topZoomTask() ?: return null
        val layout = liveTaskBounds(task)
        val scale = liveTaskScale(task)
        val controllerVisible = controllerVisibleBounds(task)
        val resolved =
            when {
                layout == null -> controllerVisible
                scale == null -> controllerVisible ?: layout
                abs(scale - 1f) <= SCALE_EPSILON -> layout
                scale in MIN_REASONABLE_SCALE..MAX_REASONABLE_SCALE -> scaledVisibleBounds(layout, scale)
                else -> controllerVisible ?: layout
            } ?: return null
        noteBounds(resolved, layout, controllerVisible, scale)
        return resolved
    }

    /** 最上层是否还有小窗活着（比 bounds 更快反映「窗口已经关了」）。 */
    fun hasTopTask(): Boolean = topZoomTask() != null

    /**
     * @author bomo 最上层小窗是否**正被拖动或缩放**（ColorOS 手势状态）。
     *
     * 用来在拖拽期间把支付栏藏起来：`Task.getBounds()` 在缩放手势里不逐帧提交，
     * 只靠几何变化判断会漏（用户实测 0.2.62 没藏住）。
     * 反射失败一律返回 false，退回调用方的几何兜底判断。
     */
    fun isTopTaskAdjusting(): Boolean {
        val task = topZoomTask() ?: return false
        taskAdjustingMethod?.let { method ->
            val result =
                try {
                    method.invoke(controller, task) as? Boolean
                } catch (_: ReflectiveOperationException) {
                    null
                } catch (_: RuntimeException) {
                    null
                }
            if (result != null) return result
        }
        anyAdjustingMethod?.let { method ->
            val result =
                try {
                    method.invoke(controller) as? Boolean
                } catch (_: ReflectiveOperationException) {
                    null
                } catch (_: RuntimeException) {
                    null
                }
            if (result != null) return result
        }
        return false
    }

    /**
     * @author bomo 2026-10-19 最上层小窗是否处于迷你形态。
     *
     * 迷你小窗（缩成屏幕边上的小胶囊）没有支付意义，用户要求此时不挂支付栏，
     * 放大回正常尺寸再显示。反射失败返回 false（调用方还有几何兜底）。
     */
    fun isTopTaskMini(): Boolean {
        val method = miniMethod ?: return false
        val task = topZoomTask() ?: return false
        return try {
            method.invoke(controller, task) as? Boolean ?: false
        } catch (_: ReflectiveOperationException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }

    /** 小窗是否正在被关掉（关闭动画期间为真）；反射拿不到就返回 false。 */
    fun isTopTaskClosing(): Boolean {
        if (invokeBoolean(dismissZoomMethod)) return true
        return invokeBoolean(forceHideZoomMethod)
    }

    private fun invokeBoolean(method: Method?): Boolean {
        if (method == null) return false
        return try {
            method.invoke(controller) as? Boolean ?: false
        } catch (_: ReflectiveOperationException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }

    /** 诊断用：一次把小窗的布局 / 缩放 / 可见矩形全打出来（`am broadcast` 触发）。 */
    fun describeTopTask(): String {
        val task = topZoomTask() ?: return "topTask=null"
        return "layout=${liveTaskBounds(task)} controller=${controllerVisibleBounds(task)} " +
            "scale=${liveTaskScale(task)} resolved=${topTaskBounds()}"
    }

    /** ColorOS 的 `getFlexibleTaskVisibleBounds(task)`：内部就是「布局 bounds × task.getScale()」。 */
    private fun controllerVisibleBounds(task: Any): Rect? {
        val method = visibleBoundsMethod ?: return null
        return try {
            val rect = method.invoke(controller, task) as? Rect ?: return null
            if (rect.isEmpty) null else Rect(rect)
        } catch (exception: ReflectiveOperationException) {
            report("PAY_BAR_BOUNDS_REFLECTION_FAILED", exception)
            null
        } catch (exception: RuntimeException) {
            report("PAY_BAR_BOUNDS_FAILED", exception)
            null
        }
    }

    /** `Task.getBounds()`：拖动缩放期间实时变化的**布局** bounds。 */
    private fun liveTaskBounds(task: Any): Rect? {
        val cls = task.javaClass
        if (cls !== cachedTaskClass) {
            cachedTaskClass = cls
            cachedTaskBoundsMethod =
                runCatching { cls.getMethod("getBounds") }.getOrNull()?.also { method ->
                    method.isAccessible = true
                }
        }
        val method = cachedTaskBoundsMethod ?: return null
        return try {
            val rect = method.invoke(task) as? Rect ?: return null
            if (rect.isEmpty) null else Rect(rect)
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    /**
     * 小窗当前缩放比例（`Task.getWrapper().getExtImpl().getScale()`）。
     *
     * 这条链就是 ColorOS 自己的：`getFlexibleTaskVisibleBounds(Task)` 用它去缩 `Task.getBounds()`，
     * 所以拿它算出来的矩形才是屏幕上那份几何。
     */
    private fun liveTaskScale(task: Any): Float? {
        val cls = task.javaClass
        if (cls !== scaleChainClass) {
            scaleChainClass = cls
            taskWrapperMethod = findNoArgMethod(cls, "getWrapper")
            extImplMethod = taskWrapperMethod?.let { method -> findNoArgMethod(method.returnType, "getExtImpl") }
            scaleMethod = extImplMethod?.let { method -> findNoArgMethod(method.returnType, "getScale") }
        }
        val wrapper = invokeNoArg(task, taskWrapperMethod) ?: return null
        val ext = invokeNoArg(wrapper, extImplMethod) ?: return null
        return invokeNoArg(ext, scaleMethod) as? Float
    }

    /** 和 ColorOS `scaleBoundsWithFixLeftTop` 同一套算法：left/top 不动，宽高按比例缩。 */
    private fun scaledVisibleBounds(
        layout: Rect,
        scale: Float,
    ): Rect =
        Rect(
            layout.left,
            layout.top,
            layout.left + (layout.width() * scale + 0.5f).toInt(),
            layout.top + (layout.height() * scale + 0.5f).toInt(),
        )

    /** 同一份几何只记一次日志；拖动缩放时 1.5s 节流，避免刷屏。 */
    private fun noteBounds(
        visible: Rect,
        layout: Rect?,
        controllerVisible: Rect?,
        scale: Float?,
    ) {
        val now = SystemClock.uptimeMillis()
        val desc =
            "PAY_BAR_BOUNDS visible=$visible layout=$layout " +
                "controller=$controllerVisible scale=$scale"
        if (desc == lastBoundsLog) return
        // 拖动缩放时 scale/几何每帧都在变，这里统一按时间节流（早退），别把日志刷爆。
        if (now - lastBoundsLogAt < BOUNDS_LOG_THROTTLE_MS) return
        lastBoundsLog = desc
        lastBoundsLogAt = now
        logger(Log.INFO, desc, null)
    }

    private fun findNoArgMethod(
        cls: Class<*>,
        name: String,
    ): Method? {
        var current: Class<*>? = cls
        while (current != null) {
            val found = runCatching { current.getMethod(name) }.getOrNull()
            if (found != null) {
                found.isAccessible = true
                return found
            }
            current = current.superclass
        }
        return null
    }

    private fun invokeNoArg(
        target: Any,
        method: Method?,
    ): Any? {
        if (method == null) return null
        return try {
            method.invoke(target)
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    /** 关掉当前最上层小窗；返回是否真的调到了关闭入口。 */
    fun closeTopTask(): Boolean {
        val task = topZoomTask() ?: return false
        val method = exitFlexibleTaskMethod ?: return false
        return try {
            method.invoke(controller, task, true, 0, 0)
            true
        } catch (exception: ReflectiveOperationException) {
            report("PAY_BAR_CLOSE_REFLECTION_FAILED", exception)
            false
        } catch (exception: RuntimeException) {
            report("PAY_BAR_CLOSE_FAILED", exception)
            false
        }
    }

    /**
     * @author bomo 0.2.71 最上层小窗现在是不是屏幕最上层（被别的界面盖住时为 false）。
     *
     * 支付栏是 overlay 窗口，天然压在 Activity 之上；用户要求「小窗被压到下面时支付栏也一起被盖住」
     * （支付宝指纹校验页抢到前台那张），只能自己问 WMS：`Task.isTopRootTaskInDisplayArea()`
     * （是不是所在 DisplayArea 的栈顶）+ `Task.isFocusedRootTaskOnDisplay()`（是不是有焦点）。
     * 两个方法都是包可见（acc=0x0），必须 `getDeclaredMethod` 沿父类找，`getMethod` 找不到。
     * 拿不到返回 null（调用方保持原状，别把栏误藏）。
     */
    fun isTopTaskOnTop(): Boolean? {
        val task = topZoomTask() ?: return null
        val cls = task.javaClass
        if (cls !== cachedOnTopClass) {
            cachedOnTopClass = cls
            cachedTopInAreaMethod = findDeclaredNoArgMethod(cls, "isTopRootTaskInDisplayArea")
            cachedFocusedRootMethod = findDeclaredNoArgMethod(cls, "isFocusedRootTaskOnDisplay")
        }
        val topInArea = invokeBool(task, cachedTopInAreaMethod) ?: return null
        if (!topInArea) return false
        return invokeBool(task, cachedFocusedRootMethod) ?: true
    }

    private fun invokeBool(
        target: Any,
        method: Method?,
    ): Boolean? {
        if (method == null) return null
        return try {
            method.invoke(target) as? Boolean
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    /** 沿父类找**包可见/私有的**无参方法（`getMethod` 只认 public）。 */
    private fun findDeclaredNoArgMethod(
        cls: Class<*>,
        name: String,
    ): Method? {
        var current: Class<*>? = cls
        while (current != null) {
            current.declaredMethods
                .firstOrNull { method -> method.name == name && method.parameterCount == 0 }
                ?.let { method ->
                    method.isAccessible = true
                    return method
                }
            current = current.superclass
        }
        return null
    }

    /**
     * @author bomo 2026-10-19 最上层小窗的 taskId；没有小窗或反射失败返回 -1。
     *
     * 切换支付方式改成「先开新窗、再关旧窗」之后，靠它判断新窗到底起来没有
     * （`hasTopTask()` 不够用：旧窗还活着时它也是 true）。
     */
    fun topTaskId(): Int {
        val task = topZoomTask() ?: return -1
        return taskIdOf(task)
    }

    /**
     * 最上层小窗当前显示的 Activity 类名；拿不到返回 null。
     *
     * ⚠️ 本机 framework 的 `Task` **没有** `topActivity()`（Android 17 / ColorOS）：
     * 只有 `getTopVisibleActivity()`（退回 `topRunningActivity()`）→ `ActivityRecord.mActivityComponent`。
     * 一开始按 AOSP 老代码写 `topActivity()` 反射，永远拿到 null（切换支付方式判断会退化成纯等超时）。
     */
    fun topTaskActivity(): String? {
        val task = topZoomTask() ?: return null
        val record = topActivityRecordOf(task) ?: return null
        val component = activityComponentOf(record) ?: return null
        return component.className
    }

    private fun topActivityRecordOf(task: Any): Any? {
        val cls = task.javaClass
        if (cls !== cachedRecordClass) {
            cachedRecordClass = cls
            cachedRecordMethod =
                sequenceOf("getTopVisibleActivity", "topRunningActivity")
                    .mapNotNull { name -> findNoArgMethod(cls, name) }
                    .firstOrNull()
        }
        return invokeNoArg(task, cachedRecordMethod)
    }

    private fun activityComponentOf(record: Any): ComponentName? {
        val cls = record.javaClass
        if (cls !== cachedComponentClass) {
            cachedComponentClass = cls
            cachedComponentField =
                generateSequence<Class<*>>(cls) { it.superclass }
                    .mapNotNull { candidate ->
                        runCatching { candidate.getDeclaredField("mActivityComponent") }.getOrNull()
                    }.firstOrNull()
                    ?.also { field -> field.isAccessible = true }
        }
        val field = cachedComponentField ?: return null
        return try {
            field.get(record) as? ComponentName
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    /** 按 taskId 关掉指定小窗（切换支付方式时关旧的那个，新的已经在它上面了）。 */
    fun closeTaskById(taskId: Int): Boolean {
        val task = taskById(taskId) ?: return false
        val method = exitFlexibleTaskMethod ?: return false
        return try {
            method.invoke(controller, task, true, 0, 0)
            true
        } catch (exception: ReflectiveOperationException) {
            report("PAY_BAR_CLOSE_OLD_REFLECTION_FAILED", exception)
            false
        } catch (exception: RuntimeException) {
            report("PAY_BAR_CLOSE_OLD_FAILED", exception)
            false
        }
    }

    private fun taskById(taskId: Int): Any? {
        val field = flexibleTasksField ?: return null
        val tasks =
            try {
                field.get(controller) as? Collection<*>
            } catch (_: ReflectiveOperationException) {
                null
            } catch (_: RuntimeException) {
                null
            } ?: return null
        synchronized(tasks) {
            return tasks.firstOrNull { candidate -> candidate != null && taskIdOf(candidate) == taskId }
        }
    }

    private fun taskIdOf(task: Any): Int {
        val field = taskIdField(task) ?: return -1
        return try {
            field.getInt(task)
        } catch (_: ReflectiveOperationException) {
            -1
        } catch (_: RuntimeException) {
            -1
        }
    }

    /**
     * `Task` 的 taskId 字段句柄（Task 类固定，缓存一份）。
     *
     * ⚠️ 本机（Android 17 / ColorOS）字段名是 **`mTaskId`**，AOSP 老代码那种 `taskId` 不存在；
     * 两个名字都试，找不到就返回 null（调用方退回「到点兜底」）。
     */
    private fun taskIdField(task: Any): Field? {
        val cls = task.javaClass
        if (cls !== cachedTaskIdClass) {
            cachedTaskIdClass = cls
            cachedTaskIdField = null
            for (name in TASK_ID_FIELD_NAMES) {
                var current: Class<*>? = cls
                while (current != null && cachedTaskIdField == null) {
                    cachedTaskIdField =
                        runCatching { current.getDeclaredField(name) }
                            .getOrNull()
                            ?.takeIf { field -> field.type == Int::class.javaPrimitiveType }
                            ?.also { field -> field.isAccessible = true }
                    current = current.superclass
                }
                if (cachedTaskIdField != null) break
            }
        }
        return cachedTaskIdField
    }

    /** 是否还有任何小窗活着（用于「小窗关了就把支付栏收掉」）。 */
    fun hasTask(): Boolean {
        val field = flexibleTasksField ?: return true // 读不到就别误收栏
        return try {
            val tasks = field.get(controller) as? Collection<*> ?: return true
            synchronized(tasks) { tasks.any { it != null } }
        } catch (exception: ReflectiveOperationException) {
            true
        } catch (exception: RuntimeException) {
            true
        }
    }

    private fun topZoomTask(): Any? {
        val method = topZoomTaskMethod ?: return null
        return try {
            method.invoke(controller)
        } catch (exception: ReflectiveOperationException) {
            report("PAY_BAR_TOP_TASK_REFLECTION_FAILED", exception)
            null
        } catch (exception: RuntimeException) {
            report("PAY_BAR_TOP_TASK_FAILED", exception)
            null
        }
    }

    private fun findField(name: String): Field? {
        var current: Class<*>? = controllerClass
        while (current != null) {
            runCatching {
                return current.getDeclaredField(name).also { it.isAccessible = true }
            }
            current = current.superclass
        }
        return null
    }

    private fun findMethod(
        name: String,
        parameterCount: Int,
    ): Method? {
        var current: Class<*>? = controllerClass
        while (current != null) {
            current.declaredMethods.firstOrNull { method ->
                method.name == name && method.parameterCount == parameterCount
            }?.let { method ->
                method.isAccessible = true
                return method
            }
            current = current.superclass
        }
        return null
    }

    /** 同一类失败只记一次，避免 300ms 轮询把日志刷爆。 */
    private fun report(
        code: String,
        throwable: Throwable,
    ) {
        if (failureLogged) return
        failureLogged = true
        logger(Log.WARN, code, throwable)
    }

    private companion object {
        const val CONTROLLER_CLASS = "com.android.server.wm.FlexibleTaskController"
        const val SCALE_EPSILON = 0.001f
        const val MIN_REASONABLE_SCALE = 0.2f
        const val MAX_REASONABLE_SCALE = 1.05f
        const val BOUNDS_LOG_THROTTLE_MS = 1_500L

        /** Task 的 taskId 字段名（ColorOS 是 mTaskId，AOSP 老版本是 taskId）。 */
        val TASK_ID_FIELD_NAMES = arrayOf("mTaskId", "taskId")
    }
}
