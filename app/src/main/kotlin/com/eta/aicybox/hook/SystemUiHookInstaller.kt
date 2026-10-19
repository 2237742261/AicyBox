package com.eta.aicybox.hook

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.util.concurrent.atomic.AtomicBoolean

@SuppressLint("PrivateApi")
internal class SystemUiHookInstaller(
    private val module: XposedModule,
    private val configuration: ProcessConfiguration,
) {
    private val bound = AtomicBoolean(false)
    private var inputMonitor: SystemUiCornerInputMonitor? = null
    private var bubbleMonitor: SystemUiBubbleMonitor? = null

    fun install(classLoader: ClassLoader) {
        try {
            // ColorOS 17 把 SystemUI 的 Application 迁到 application.impl 包下；
            // 按候选顺序解析，并用 getMethod 沿层次查找 onCreate。
            val applicationClass =
                SYSTEM_UI_APPLICATION_CLASSES
                    .firstNotNullOfOrNull { name ->
                        runCatching { classLoader.loadClass(name) }.getOrNull()
                    }
                    ?: throw ClassNotFoundException(SYSTEM_UI_APPLICATION_CLASSES.first())
            val onCreate = applicationClass.getMethod("onCreate")
            module
                .hook(onCreate)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("flymefreeform.systemui.corner_spy_owner")
                .intercept { chain ->
                    val result = chain.proceed()
                    val context = chain.thisObject as? Context
                    if (context != null && bound.compareAndSet(false, true)) {
                        startInputMonitor(context)
                    }
                    result
                }
            module.logFiltered(Log.INFO, TAG, "SYSTEMUI_CORNER_SPY_HOOK_INSTALLED")
        } catch (exception: ReflectiveOperationException) {
            module.logFiltered(Log.WARN, TAG, "SYSTEMUI_APPLICATION_TARGET_UNAVAILABLE", exception)
        } catch (exception: LinkageError) {
            module.logFiltered(Log.WARN, TAG, "SYSTEMUI_APPLICATION_TARGET_LINKAGE_FAILED", exception)
        }
        installQqNotificationHook(classLoader)
    }

    /** @author bomo 2026-10-19：hook SystemUI 自带 NotificationListener，拿 QQ 通知弹气泡（免授权）。 */
    private fun installQqNotificationHook(classLoader: ClassLoader) {
        try {
            val listenerClass = classLoader.loadClass(QQ_NOTIFICATION_LISTENER_CLASS)
            val method =
                listenerClass.getMethod(
                    "onNotificationPosted",
                    android.service.notification.StatusBarNotification::class.java,
                    android.service.notification.NotificationListenerService.RankingMap::class.java,
                )
            module
                .hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("flymefreeform.systemui.qq_notify")
                .intercept { chain ->
                    val result = chain.proceed()
                    try {
                        val sbn = runCatching { chain.getArg(0) as? android.service.notification.StatusBarNotification }.getOrNull()
                        if (sbn != null) {
                            bubbleMonitor?.onExternalQqNotification(sbn)
                        }
                    } catch (_: Throwable) {
                    }
                    result
                }
            module.logFiltered(Log.INFO, TAG, "SYSTEMUI_QQ_NOTIFY_HOOK_INSTALLED")
        } catch (exception: Throwable) {
            module.logFiltered(Log.WARN, TAG, "SYSTEMUI_QQ_NOTIFY_HOOK_FAIL", exception)
        }
    }

    private fun startInputMonitor(context: Context) {
        try {
            val monitor = SystemUiCornerInputMonitor(context, module, configuration)
            inputMonitor = monitor
            monitor.start()
            module.logFiltered(Log.INFO, TAG, "SYSTEMUI_CORNER_SPY_READY")
            try {
                val bubble = SystemUiBubbleMonitor(context, module, configuration)
                bubbleMonitor = bubble
                bubble.start()
            } catch (e: ReflectiveOperationException) {
                module.logFiltered(Log.WARN, TAG, "SYSTEMUI_BUBBLE_API_UNAVAILABLE", e)
            } catch (e: RuntimeException) {
                module.logFiltered(Log.WARN, TAG, "SYSTEMUI_BUBBLE_START_FAILED", e)
            } catch (e: LinkageError) {
                module.logFiltered(Log.WARN, TAG, "SYSTEMUI_BUBBLE_LINKAGE_FAILED", e)
            }
            startSideIndex(context)
        } catch (exception: ReflectiveOperationException) {
            module.logFiltered(Log.WARN, TAG, "SYSTEMUI_CORNER_SPY_API_UNAVAILABLE", exception)
        } catch (exception: RuntimeException) {
            module.logFiltered(Log.WARN, TAG, "SYSTEMUI_CORNER_SPY_START_FAILED", exception)
        } catch (exception: LinkageError) {
            module.logFiltered(Log.WARN, TAG, "SYSTEMUI_CORNER_SPY_LINKAGE_FAILED", exception)
        }
    }

    /**
     * @author bomo 2026-10-19 侧滑索引（从 `com.eta.sideindexx` 移植）。
     *
     * 它自己那套只在「系统界面」进程里工作：同一个 SystemUI Application 的 Context 直接交给
     * [com.eta.aicybox.sideindex.overlay.SystemUiHost]，由它常驻控制器按配置挂/摘感应带。
     * 开关等配置仍走它原来的 `Settings.Secure` 通道（见 PrefStore / ConfigReader）。
     */
    private fun startSideIndex(context: Context) {
        try {
            com.eta.aicybox.sideindex.LxApi.attach(module)
            // @author bomo 2026-10-20 移植时漏了这一步：ConfigReader 没拿到 Context 就永远读不到
            // Settings.Secure 通道，OverlayController 每 1.2 秒拿一次 IDLE 配置（enabled=false），
            // 表现为「索引怎么都呼不出来」，日志里刷 "暂时读不到模块配置"。
            com.eta.aicybox.sideindex.config.ConfigReader.attach(context)
            com.eta.aicybox.sideindex.config.ConfigReader.current()
            com.eta.aicybox.sideindex.overlay.SystemUiHost.sync(context)
            module.logFiltered(Log.INFO, TAG, "SYSTEMUI_SIDE_INDEX_READY")
        } catch (e: ReflectiveOperationException) {
            module.logFiltered(Log.WARN, TAG, "SYSTEMUI_SIDE_INDEX_UNAVAILABLE", e)
        } catch (e: RuntimeException) {
            module.logFiltered(Log.WARN, TAG, "SYSTEMUI_SIDE_INDEX_FAILED", e)
        } catch (e: LinkageError) {
            module.logFiltered(Log.WARN, TAG, "SYSTEMUI_SIDE_INDEX_LINKAGE_FAILED", e)
        }
    }

    private companion object {
        const val TAG = "FlymeFreeform"
        const val QQ_NOTIFICATION_LISTENER_CLASS = "com.android.systemui.statusbar.NotificationListener"
        val SYSTEM_UI_APPLICATION_CLASSES =
            listOf(
                // ColorOS 17
                "com.android.systemui.application.impl.SystemUIApplicationImpl",
                // ColorOS 16 及更早
                "com.android.systemui.SystemUIApplication",
            )
    }
}
