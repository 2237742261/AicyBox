package com.eta.aicybox.sideindex.util

import android.util.Log
import com.eta.aicybox.sideindex.Const
import com.eta.aicybox.sideindex.LxApi

/**
 * 日志出口（1.32.0 收敛口径）。
 *
 *  - [e] / [w]：**永远输出**（只有出错才说话）
 *  - [i]（关键事件）与 [d]（诊断）**默认安静** —— 配置里打开 `log_verbose` 才输出，
 *    所以发布出去的包在 LSPosed 日志里是干净的
 *  - i / d 各有「字符串」与「lambda」两个重载：热路径（每个 MOVE、每次手势）一律用 lambda，
 *    关着的时候连字符串都不拼，这是"优化代码效率"的一部分
 *
 * LSPosed 日志走 [LxApi.log]（模块进程里 XposedInterface 不存在时自动降级成只写 logcat）。
 */
object XLog {

    @Volatile private var verbose = false

    fun setVerbose(value: Boolean) {
        verbose = value
    }

    fun isVerbose(): Boolean = verbose

    /**
     * 无论 log_verbose 开没开都输出。
     * 只给「每个进程一条」的模块加载行用 —— 出问题时至少能确认模块到底有没有被注入。
     */
    fun a(msg: String) = write(Log.INFO, msg, null)

    fun d(msg: String) {

        if (verbose) write(Log.DEBUG, msg, null)
    }

    fun d(msg: () -> String) {
        if (verbose) write(Log.DEBUG, msg(), null)
    }

    fun i(msg: String) {
        if (verbose) write(Log.INFO, msg, null)
    }

    fun i(msg: () -> String) {
        if (verbose) write(Log.INFO, msg(), null)
    }

    fun w(msg: String, t: Throwable? = null) = write(Log.WARN, msg, t)

    fun e(msg: String, t: Throwable? = null) = write(Log.ERROR, msg, t)

    private fun write(priority: Int, msg: String, t: Throwable?) {
        val line = "[" + levelChar(priority) + "] " + msg
        try {
            Log.println(priority, Const.LOG_TAG, line)
            if (t != null) Log.println(Log.ERROR, Const.LOG_TAG, Log.getStackTraceString(t))
        } catch (_: Throwable) {
        }
        LxApi.log(priority, Const.LOG_TAG, line, t)
    }

    private fun levelChar(priority: Int): Char = when (priority) {
        Log.VERBOSE -> 'V'
        Log.DEBUG -> 'D'
        Log.INFO -> 'I'
        Log.WARN -> 'W'
        else -> 'E'
    }
}
