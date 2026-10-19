package com.eta.aicybox.sideindex.overlay

import com.eta.aicybox.sideindex.LxApi
import com.eta.aicybox.sideindex.util.XLog
import java.lang.reflect.Method

/**
 * 系统界面「遮挡」状态：通知中心 / 控制中心展开、锁屏可见时不给索引呼出（1.32.4）。
 *
 * 为什么需要：索引是全局能力，前台判定只看「谁在最上面」，而通知栏、控制中心、
 * 锁屏都是 SystemUI 自己的窗口，判定里看起来仍然「前台是桌面/应用」→ 这些界面上
 * 从边缘左右滑也会把索引弹出来，盖在人家上面（用户 2026-10-17 报的场景）。
 *
 * 用哪条路读状态：`NotificationShadeWindowControllerImpl.apply(NotificationShadeWindowState)`
 * —— SystemUI 每次要把窗口状态落到窗口上都会走它，传进来的 state 里就有现成的几个布尔量：
 *   - `keyguardShowing`      锁屏可见
 *   - `shadeOrQsExpanded`    通知栏 / 控制中心已经展开（含正在跟手展开）
 *   - `panelVisible`         通知栏面板可见
 *   - `qsExpanded` / `qsPanelVisible`  控制中心那一页
 * 这里只钩一个方法、把状态存成 volatile 布尔，触摸回调里读的是字段（零反射、零 binder），
 * 不会在输入回调线程上惹事（见 BackGestureGate 关于 "GestureUI" 线程的注释）。
 *
 * ⚠️ 只在手指落在感应带内时查（[blockedReason]），别放进每个事件的热路径。
 */
object SystemCover {

    private const val NSWC = "com.android.systemui.shade.NotificationShadeWindowControllerImpl"
    private const val STATE = "com.android.systemui.shade.NotificationShadeWindowState"

    /** 锁屏可见。 */
    @Volatile private var keyguard = false

    /** 通知栏 / 控制中心展开或正在展开。 */
    @Volatile private var shade = false

    /** 控制中心那一页（QS）展开。 */
    @Volatile private var qs = false

    private var installed = false

    fun install(cl: ClassLoader) {
        if (installed) return
        installed = true
        try {
            val cls = LxApi.findClass(NSWC, cl)
            if (cls == null) {
                XLog.d { "系统遮挡：进程里没有 $NSWC" }
                return
            }
            val stateCls = LxApi.findClass(STATE, cl)
            if (stateCls == null) {
                XLog.d { "系统遮挡：进程里没有 $STATE" }
                return
            }
            var target: Method? = null
            for (m in cls.declaredMethods) {
                if (m.name != "apply") continue
                val ps = m.parameterTypes
                if (ps.size == 1 && ps[0] == stateCls) {
                    target = m
                    break
                }
            }
            if (target == null) {
                XLog.d { "系统遮挡：$NSWC 上没有 apply($STATE)" }
                return
            }
            val ok = LxApi.hookBefore(target) { scope ->
                readState(scope.chain.args.firstOrNull())
            }
            if (ok) {
                XLog.i { "系统遮挡：通知栏/控制中心/锁屏状态已接管（钩住 $NSWC.apply）" }
            } else {
                XLog.d { "系统遮挡：钩 $NSWC.apply 失败" }
            }
        } catch (t: Throwable) {
            XLog.d { "系统遮挡：安装失败 ${t.javaClass.simpleName} ${t.message}" }
        }
    }

    /** 从 state 里抽四个布尔；只在结论变化时打一行 verbose 日志（apply 会频繁调用）。 */
    private fun readState(state: Any?) {
        if (state == null) return
        try {
            val kg = LxApi.getBooleanField(state, "keyguardShowing")
            val sh = LxApi.getBooleanField(state, "shadeOrQsExpanded") ||
                LxApi.getBooleanField(state, "panelVisible")
            val q = LxApi.getBooleanField(state, "qsExpanded") ||
                LxApi.getBooleanField(state, "qsPanelVisible")
            if (kg != keyguard || sh != shade || q != qs) {
                keyguard = kg
                shade = sh
                qs = q
                XLog.d { "系统遮挡：锁屏=$kg 通知栏=$sh 控制中心=$q" }
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * 当前该不该屏蔽索引：返回原因（写日志用），null = 不屏蔽。
     *
     * 纯字段读取，可以在输入回调线程上直接调。
     */
    fun blockedReason(): String? = when {
        keyguard -> "锁屏"
        shade || qs -> "通知中心/控制中心"
        else -> null
    }
}
