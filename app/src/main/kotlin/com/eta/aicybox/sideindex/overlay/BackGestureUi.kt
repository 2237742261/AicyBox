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
    }

    /** 箭头是否正在显示。只在需要时调用（每个事件都反射太贵）。 */
    fun isShowing(): Boolean {
        for (v in views) {
            try {
                if (v.visibility == View.VISIBLE && v.alpha > ALPHA_EPS) return true
            } catch (_: Throwable) {
            }
        }
        return false
    }

    fun instanceCount(): Int = views.size

    private const val SIDE_GESTURE_NAV_VIEW =
        "com.oplus.systemui.navigationbar.gesture.sidegesture.view.SideGestureNavView"
    private const val ALPHA_EPS = 0.01f
}
