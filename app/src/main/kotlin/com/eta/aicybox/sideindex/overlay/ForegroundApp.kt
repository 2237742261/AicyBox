package com.eta.aicybox.sideindex.overlay

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.eta.aicybox.sideindex.util.XLog
import com.eta.aicybox.sideindex.LxApi

/**
 * 前台应用判定（只用来判断"是不是在桌面"）。
 *
 * 主判据是 **ActivityTaskManager 的当前焦点任务** —— 系统里最直接、最实时的"谁在前面"，
 * 用 LxApi 调用可以绕过隐藏 API 限制。
 * 拿不到时才退回 UsageStats 的 MOVE_TO_FOREGROUND 事件流（它有延迟、也有盲区）。
 *
 * 走过的弯路（别回头）：
 *   - `ActivityManager.getRunningAppProcesses()` 的 importance 在 ColorOS 上完全不可信：
 *     它会把几十个常驻系统进程（com.oplus.* / com.heytap.* / com.qti.* / system …）都标成
 *     "前台"，桌面和后台应用根本分不出来。
 *   - 普通反射读 android.app.ActivityTaskManager 会被隐藏 API 限制拦掉（日志里 atm 一直是空）。
 */
object ForegroundApp {

    private const val REFRESH_INTERVAL_MS = 3000L

    /** ActivityTaskManager 的窗口模式：1 = 全屏（其余是小窗 / 分屏 / 弹性窗口） */
    private const val WINDOWING_MODE_FULLSCREEN = 1

    @Volatile private var cached = ""
    @Volatile private var lastRefreshAt = 0L
    @Volatile private var refreshing = false

    /** UsageStats 事件里最近一次切到前台的包名（sticky） */
    @Volatile private var usageCurrent = ""
    private var lastEventAt = 0L

    @Volatile private var launcherPkg: String? = null

    /** 上一次打过的判定结论：心跳每 2.5 秒问一次，结论没变就不重复打日志 */
    @Volatile private var lastVerdict = ""

    private fun logVerdict(verdict: String) {
        if (verdict == lastVerdict) return
        lastVerdict = verdict
        XLog.d("前台判定：$verdict")
    }

    fun current(ctx: Context): String = cached

    /** 心跳调用：后台刷新（失败保留旧值） */
    fun refresh(ctx: Context) {
        val now = System.currentTimeMillis()
        if (now - lastRefreshAt < REFRESH_INTERVAL_MS) return
        lastRefreshAt = now
        if (refreshing) return
        refreshing = true
        Thread {
            try {
                val detected = detect(ctx)
                if (detected.isNotEmpty()) cached = detected
            } catch (t: Throwable) {
                XLog.d("刷新前台应用失败：${t.javaClass.simpleName}")
            } finally {
                refreshing = false
            }
        }.start()
    }

    /** 手势路径上同步刷一次（一次 ATMS 调用 + 一次事件查询，几毫秒） */
    fun refreshNow(ctx: Context) {
        try {
            val top = atmTop(ctx)
            if (top.isNotEmpty()) {
                cached = top
                return
            }
            detect(ctx)
        } catch (t: Throwable) {
            XLog.d("同步刷新前台失败：${t.javaClass.simpleName}")
        }
    }

    /** 桌面包名（默认 Launcher） */
    fun launcherPackage(ctx: Context): String {
        launcherPkg?.let { return it }
        val resolved = try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            ctx.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
        } catch (t: Throwable) {
            null
        }.orEmpty()
        if (resolved.isNotEmpty()) launcherPkg = resolved
        return resolved
    }

    /**
     * 前台是不是桌面。
     * 以 ActivityTaskManager 的顶层包名为准；只有拿不到时才退回事件流。
     */
    fun isLauncher(ctx: Context): Boolean {
        val launcher = launcherPackage(ctx)
        if (launcher.isEmpty()) return true
        val top = atmTop(ctx)
        if (top.isNotEmpty()) {
            val here = top == launcher || top.startsWith("$launcher:")
            if (here) return true
            // 顶层不是桌面：再看是不是「桌面 + 小窗 / 分屏」——ColorOS 的弹性小窗会把焦点任务抢给
            // 被打开的应用，桌面其实还在下面。这种情况也该能呼出，否则用户得先点一下桌面。
            if (desktopStillThere(ctx)) {
                logVerdict("顶层=$top，非全屏（桌面+小窗/分屏）→ 按桌面处理")
                return true
            }
            logVerdict("顶层=$top → 不在桌面")
            return false
        }
        refreshNow(ctx)
        val byUsage = usageCurrent
        if (byUsage.isEmpty()) return true // 两个信号都拿不到：宽松放行，否则功能直接不可用
        logVerdict("兜底事件流：使用中=$byUsage")
        return byUsage == launcher
    }

    /**
     * 廉价版前台判定：只问「顶层任务是不是桌面」——一次 ATMS 调用，不做小窗／分屏兜底，
     * 也不打日志。
     *
     * 给「穿透看护」（OverlayController 里 5Hz 轮询的那种）用：
     * [isLauncher] 在主判据不成立时还会顺手拉一次 getAllRootTaskInfos 判断桌面在不在场，
     * 那个调用（和它拼的诊断字符串）不能按 5Hz 来跑。
     */
    fun topIsLauncher(ctx: Context): Boolean {
        val launcher = launcherPackage(ctx)
        if (launcher.isEmpty()) return true
        val top = atmTop(ctx)
        if (top.isEmpty()) return false
        return top == launcher || top.startsWith("$launcher:")
    }

    /** 当前用于判断的值（日志用） */
    fun debugValue(ctx: Context): String {
        val u = usageCurrent.ifEmpty { "-" }
        val a = atmTop(ctx).ifEmpty { "-" }
        return "usage=$u atm=$a"
    }

    // ---------------- 实现 ----------------

    /** UsageStats 增量事件：找出这段时间内最后一次切到前台的包名（只作兜底） */
    private fun detect(ctx: Context): String {
        try {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            if (usm != null) {
                val now = System.currentTimeMillis()
                val from = if (lastEventAt > 0) lastEventAt - 1000 else now - 60_000
                val events = usm.queryEvents(from, now)
                if (events != null) {
                    val ev = UsageEvents.Event()
                    var pkg: String? = null
                    var ts = 0L
                    while (events.hasNextEvent()) {
                        events.getNextEvent(ev)
                        if (ev.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                            val p = ev.packageName
                            if (!p.isNullOrEmpty() && ev.timeStamp >= ts) {
                                ts = ev.timeStamp
                                pkg = p
                            }
                        }
                    }
                    lastEventAt = now
                    if (pkg != null) usageCurrent = pkg
                }
            }
        } catch (t: Throwable) {
            XLog.d("UsageStats 前台事件失败：${t.javaClass.simpleName}")
        }
        return usageCurrent
    }

    /**
     * 顶层应用包名：ActivityTaskManager 的当前焦点任务。
     * 隐藏 API 必须走 LxApi，普通反射会被隐藏 API 限制拦掉。
     */
    private fun atmTop(ctx: Context): String {
        return try {
            val cl = LxApi.findClass("android.app.ActivityTaskManager", ctx.classLoader)
                ?: return ""
            val service = LxApi.callStaticMethod(cl, "getService") ?: return ""
            val focused = try {
                LxApi.callMethod(service, "getFocusedRootTaskInfo")
            } catch (t: Throwable) {
                XLog.d("getFocusedRootTaskInfo 失败：${t.javaClass.simpleName}")
                null
            }
            readTopActivityPackage(focused)?.let { if (it.isNotEmpty()) return it }
            val tasks = try {
                LxApi.callMethod(service, "getTasks", 1) as? List<*>
            } catch (t: Throwable) {
                XLog.d("getTasks 失败：${t.javaClass.simpleName}")
                null
            }
            readTopActivityPackage(tasks?.firstOrNull()) ?: ""
        } catch (t: Throwable) {
            XLog.d("ActivityTaskManager 取顶层失败：${t.javaClass.simpleName} ${t.message}")
            ""
        }
    }

    /**
     * 顶层不是桌面时，再确认一次「桌面是不是还在屏幕上」。
     *
     * ColorOS 的小窗 / 迷你窗 / 分屏都是「桌面还在下面、应用浮在上面」的形态：焦点任务被应用抢走，
     * 但桌面对用户来说仍然看得见、点得到，这时也该允许呼出（否则得先点一下桌面才能再用索引）。
     *
     * 判据（依次尝试）：
     *   1. 存在**可见的** Launcher root task —— 最准。全屏应用盖住桌面时 home task 的 `visible`
     *      是 false（`dumpsys activity activities` 里能直接看到），所以不会在全屏应用里误放行；
     *   2. 拿不到 root task 列表时退回：焦点任务不是全屏模式（windowingMode != 1）。
     *
     * ⚠️ ColorOS 的小窗是把**全屏 task** 缩放显示，所以小窗场景下焦点任务的 windowingMode 仍是
     * 1（fullscreen）—— 不能拿它当"是不是小窗"的判据（1.21.10 就栽在这，日志里压根没进这条分支）。
     */
    private fun desktopStillThere(ctx: Context): Boolean {
        val launcher = launcherPackage(ctx)
        if (launcher.isEmpty()) return false
        return try {
            val cl = LxApi.findClass("android.app.ActivityTaskManager", ctx.classLoader)
                ?: return false
            val service = LxApi.callStaticMethod(cl, "getService") ?: return false
            val infos = try {
                LxApi.callMethod(service, "getAllRootTaskInfos") as? List<*>
            } catch (t: Throwable) {
                XLog.d("桌面在场判定：getAllRootTaskInfos 不可用（${t.javaClass.simpleName}）")
                null
            }
            if (infos != null) {
                // 只挑桌面那个任务看可见性：以前把每个任务都拼进日志（几十个包的巨长行、每 2.5 秒一条），
                // 既刷屏又白费字符串拼接
                var taskCount = 0
                var launcherVisible = false
                for (info in infos) {
                    if (info == null) continue
                    taskCount++
                    val pkg = readTopActivityPackage(info) ?: "-"
                    if (pkg != launcher && !pkg.startsWith("$launcher:")) continue
                    val visible = try {
                        LxApi.getBooleanField(info, "visible")
                    } catch (t: Throwable) {
                        true
                    }
                    if (visible) launcherVisible = true
                }
                logVerdict(
                    "桌面在场判定：任务=$taskCount 桌面可见=$launcherVisible" +
                        if (launcherVisible) " → 按桌面处理" else " → 按非桌面处理"
                )
                // 能拿到任务列表时以「桌面是否可见」为准（1.32.7）。
                // 以前这里桌面不可见还会继续掉进下面的 fallback（focusedMode != 全屏 → 视为
                // 桌面+小窗/分屏）返回 true —— 全屏应用里 focusedMode 实测有时不是 1，就会被误判，
                // 表现：只桌面模式下应用内侧滑照样呼出索引。小窗/分屏场景桌面仍 visible=true，
                // 仍会走 launcherVisible 放行，不受影响。
                return launcherVisible
            }
            val focused = try {
                LxApi.callMethod(service, "getFocusedRootTaskInfo")
            } catch (t: Throwable) {
                null
            }
            val focusedMode = try {
                LxApi.getIntField(focused, "windowingMode")
            } catch (t: Throwable) {
                WINDOWING_MODE_FULLSCREEN
            }
            focusedMode != WINDOWING_MODE_FULLSCREEN
        } catch (t: Throwable) {
            XLog.d("桌面在场判定失败：${t.javaClass.simpleName}")
            false
        }
    }

    private fun readTopActivityPackage(taskInfo: Any?): String? {
        if (taskInfo == null) return null
        val top = try {
            LxApi.getObjectField(taskInfo, "topActivity")
        } catch (t: Throwable) {
            null
        } ?: try {
            LxApi.getObjectField(taskInfo, "baseActivity")
        } catch (t: Throwable) {
            null
        }
        return when (top) {
            null -> null
            is ComponentName -> top.packageName
            else -> try {
                LxApi.getObjectField(top, "packageName") as? String
            } catch (t: Throwable) {
                null
            }
        }
    }
}
