package com.eta.aicybox.platform.coloros

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle

/** 每次提交都重新校验组件，并仅携带 ColorOS 自由窗参数启动，不退化为普通全屏启动。 */
internal class ColorOsFreeformLauncher(
    private val context: Context,
) {
    fun launch(component: ComponentName): FreeformLaunchResult {
        if (!isLaunchable(component)) return FreeformLaunchResult.TargetUnavailable

        val intent =
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(component)
        return start(intent, freeform = true)
    }

    /**
     * @author bomo 2026-10-19 任意 Intent 的启动（支付深链等）。
     *
     * 与 [launch] 共用同一份 ColorOS 启动 Bundle —— 真正把小窗打开的是
     * `android:activity.mZoomLaunchFlags=4`（实测只给 `windowingMode=100`
     * 时任务仍是 `mode=fullscreen`），所以这里**不能**退回裸 `startActivity`。
     *
     * @param freeform false 时按普通全屏启动（不带任何 ColorOS 小窗参数）。
     */
    fun launchIntent(
        intent: Intent,
        freeform: Boolean = true,
    ): FreeformLaunchResult {
        if (!isResolvable(intent)) return FreeformLaunchResult.TargetUnavailable
        return start(intent, freeform)
    }

    private fun start(
        intent: Intent,
        freeform: Boolean,
    ): FreeformLaunchResult {
        val launchIntent = Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = if (freeform) createLaunchOptions() else null

        return try {
            if (options != null) {
                context.startActivity(launchIntent, options)
            } else {
                context.startActivity(launchIntent)
            }
            FreeformLaunchResult.Started
        } catch (exception: SecurityException) {
            FreeformLaunchResult.Failed("FREEFORM_LAUNCH_SECURITY_REJECTED", exception)
        } catch (exception: RuntimeException) {
            FreeformLaunchResult.Failed("FREEFORM_LAUNCH_START_FAILED", exception)
        }
    }

    /** 深链 / 显式组件能否被系统解析；解析不到时不启动，避免抛异常或弹选择器。 */
    private fun isResolvable(intent: Intent): Boolean =
        try {
            context.packageManager.resolveActivity(intent, 0) != null
        } catch (_: RuntimeException) {
            false
        }

    private fun isLaunchable(component: ComponentName): Boolean =
        try {
            context.packageManager
                .getActivityInfo(component, PackageManager.ComponentInfoFlags.of(0))
                .let { info -> info.enabled && info.applicationInfo.enabled && info.exported }
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: RuntimeException) {
            false
        }

    private fun createLaunchOptions(): Bundle =
        Bundle().apply {
            putInt(ZOOM_FLAGS_KEY, ZOOM_LAUNCH_FLAG)
            putInt(WINDOWING_MODE_KEY, FLEXIBLE_WINDOWING_MODE)
            createPlatformOptions()?.let(::putAll)
        }

    /** 隐藏 setter 只是补强；ColorOS 的两个 Bundle 参数才是不可缺少的启动协议。 */
    @SuppressLint("BlockedPrivateApi")
    private fun createPlatformOptions(): Bundle? =
        try {
            ActivityOptions.makeBasic().let { options ->
                ActivityOptions::class.java
                    .getDeclaredMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                    .also { it.isAccessible = true }
                    .invoke(options, FLEXIBLE_WINDOWING_MODE)
                options.toBundle()
            }
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: RuntimeException) {
            null
        }

    private companion object {
        const val FLEXIBLE_WINDOWING_MODE = 100
        const val ZOOM_LAUNCH_FLAG = 4
        const val WINDOWING_MODE_KEY = "android.activity.windowingMode"
        const val ZOOM_FLAGS_KEY = "android:activity.mZoomLaunchFlags"
    }
}

internal sealed interface FreeformLaunchResult {
    data object Started : FreeformLaunchResult

    data object TargetUnavailable : FreeformLaunchResult

    data class Failed(
        val diagnosticCode: String,
        val cause: Throwable,
    ) : FreeformLaunchResult
}
