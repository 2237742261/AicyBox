package com.eta.aicybox.sideindex.overlay

import android.view.View
import com.eta.aicybox.sideindex.LxApi
import com.eta.aicybox.sideindex.util.XLog
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 返回箭头（系统返回手势 UI）状态读取。
 *
 * 目的：箭头亮起时索引让路（箭头显示 → 不呼出，并且把已经弹出来的索引收掉）。
 * 用户 2026-10-16 报的场景：在应用里准备返回、箭头已呼出，然后把箭头**慢慢推回去**
 * （不松手所以不触发返回），推回去途中那根**向内**的滑动被判成索引呼出。
 *
 * ✅ 能用的路：直接读指示器视图。ColorOS 上那个箭头是
 * `com.oplus.systemui.navigationbar.gesture.sidegesture.view.SideGestureNavView`
 * （实测存在于 SystemUI classes4.dex，是 `View` 子类，内部 setVisibility(0)/(8) 控制显隐）。
 * 判据 = `visibility == VISIBLE && alpha > 0.01`。
 *
 * ❌ 本机实测走不通的路（别再试）：
 * - AOSP `EdgeBackGestureHandler` / ColorOS `EdgeBackGestureHandlerController`：
 *   **没有收 MotionEvent 的方法**（含内部类）。
 * - `onBackStarted/onBackProgressed/onBackCancelled/onBackInvoked`：能钩到 12 个，
 *   但返回手势一次都不走它们。
 * - `BackPanelController`（36 个方法全钩）/ `SideGestureNavView` 的**方法**：
 *   返回手势期间一个都没触发。
 *
 * @author Eta 2026-10-16
 */
object BackGestureUi {

    /** 所有已抓到的箭头视图实例（左右各一，也可能只有一个）。 */
    private val views = CopyOnWriteArraySet<View>()
    @Volatile private var installed = false

    /**
     * 箭头「刚刚出现过」的时间戳（uptime）。
     *
     * 为什么需要宽限期：ColorOS 的箭头是先 `setAlpha(>0)` / `setVisibility(VISIBLE)` 再淡入，
     * 最开始那几帧 alpha 还在 0 附近；如果用户就是在这个窗口里开始侧滑，只看实时状态会判「没显示」
     * 而把索引放出来（用户 2026-10-20 报的「返回 UI 在也照样呼出」）。
     */
    @Volatile private var appearAt = 0L

    fun install(cl: ClassLoader) {
        if (installed) return
        installed = true
        val cls = LxApi.findClass(SIDE_GESTURE_NAV_VIEW, cl) ?: run {
            XLog.d("返回箭头：SystemUI 里没有 $SIDE_GESTURE_NAV_VIEW")
            return
        }
        val n = LxApi.hookConstructorsAfter(cls) { instance ->
            val v = instance as? View ?: return@hookConstructorsAfter
            if (views.add(v)) {
                XLog.i("返回箭头：已抓到实例 ${v.javaClass.simpleName}")
            }
        }
        if (n > 0) XLog.i("返回箭头：构造函数钩子已装上（$n 个）") else XLog.d("返回箭头：钩构造失败")
        hookVisibilityFallback(cl)
    }

    /**
     * @author bomo 2026-10-20 兜底抓**已经存在**的箭头视图。
     *
     * 为什么需要：箭头视图和返回手势接收器一样，在 SystemUI 启动早期就建好了，而模块是延迟注入的 ——
     * 只钩构造函数的话 [views] 一直是空的，[isShowing] 恒为 false，
     * 表现就是「返回箭头明明亮着，索引还是被呼出来」（用户 2026-10-20 报）。
     *
     * 钩 `View.setVisibility(int)`：箭头显隐必然经过它（`setVisibility(0)` / `(8)`），
     * 回调里只认 `SideGestureNavView` 那个类名，其余视图一律放过。
     */
    private fun hookVisibilityFallback(cl: ClassLoader) {
        val view = LxApi.findClass("android.view.View", cl)
        val intType = java.lang.Integer.TYPE
        val m = try {
            view?.getDeclaredMethod("setVisibility", intType)
        } catch (_: Throwable) {
            null
        }
        if (m == null) {
            XLog.d("返回箭头：没找到 View.setVisibility（兜底不生效）")
            return
        }
        val ok = LxApi.hookBefore(m) { scope ->
            try {
                val obj = scope.chain.thisObject
                if (obj is View && obj.javaClass.name == SIDE_GESTURE_NAV_VIEW) {
                    if (views.add(obj)) {
                        XLog.w("返回箭头：兜底抓到已存在的实例 ${obj.javaClass.simpleName}")
                    }
                    if ((scope.chain.args.getOrNull(0) as? Int) == View.VISIBLE) markAppeared()
                }
            } catch (_: Throwable) {
            }
        }
        XLog.w(if (ok) "返回箭头：setVisibility 兜底已装上" else "返回箭头：setVisibility 兜底钩挂失败")
    }

    private fun markAppeared() {
        appearAt = android.os.SystemClock.uptimeMillis()
    }

    /** 箭头是否正在显示。只在需要时调用（每个事件都反射太贵）。 */
    fun isShowing(): Boolean {
        for (v in views) {
            try {
                if (!v.isShown) continue
                if (visibleAlpha(v)) return true
            } catch (_: Throwable) {
            }
        }
        // 淡入 / 淡出那几帧：视图本身还看不出「在显示」，但刚刚确实被点亮过
        return android.os.SystemClock.uptimeMillis() - appearAt < SHOW_GRACE_MS
    }

    /**
     * @author Eta 2026-10-20 判断「这个箭头此刻真的看得见」。
     *
     * 只看 `visibility == VISIBLE` 会漏两种 ColorOS 的实际状态：
     *   ① 箭头挂在会被整块隐藏的容器里（容器 INVISIBLE，自己还是 VISIBLE）；
     *   ② 显隐是靠**祖先的 alpha**（淡出）做的，`View.isShown` 覆盖①但不覆盖②。
     * 所以 `isShown`（含祖先可见性）+ 逐级向上看 alpha。
     */
    private fun visibleAlpha(v: View): Boolean {
        if (v.alpha <= ALPHA_EPS) return false
        var p: android.view.ViewParent? = v.parent
        var depth = 0
        while (p != null && depth < 6) {
            if (p is View && p.alpha <= ALPHA_EPS) return false
            p = p.parent
            depth++
        }
        return true
    }

    fun instanceCount(): Int = views.size

    private const val SIDE_GESTURE_NAV_VIEW =
        "com.oplus.systemui.navigationbar.gesture.sidegesture.view.SideGestureNavView"
    private const val ALPHA_EPS = 0.01f

    /** 「刚出现过」的宽限期：覆盖淡入淡出那几帧。 */
    private const val SHOW_GRACE_MS = 450L
}
