package com.eta.aicybox.framework

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.eta.aicybox.config.ModulePreferences
import com.eta.aicybox.config.SharedStateProtocol

/**
 * @author bomo 2026-10-19：QQ 消息通知监听（需求2）。
 * 用系统 NotificationListenerService 监听 QQ 通知（**不 hook QQ 进程**，QQ 更新不受影响）。
 * 收到 QQ 新消息通知 → 提取联系人标题 + 跳转 contentIntent → 发广播给 SystemUI
 * （SystemUiAicyFeatures 收到后弹联系人气泡，点击以小窗打开对应聊天框）。
 *
 * 需用户在系统设置里为本应用开启「通知使用权」（Notification Access）。
 */
class QqNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // 只处理 QQ 消息，且只处理一次（REPLACING=同一会话新消息，保留弹气泡）
        val pkg = sbn.packageName
        if (pkg != QQ_PACKAGE) return
        if (!isQqNotifyEnabled()) return
        val n = sbn.notification ?: return
        // 联系人标题（QQ 通知 title 即联系人/群名）
        val title = n.extras?.getString(Notification.EXTRA_TITLE)?.takeIf { it.isNotBlank() } ?: return
        // 跳转 intent（QQ 自带「点击打开该联系人聊天」的 contentIntent）
        val contentIntent = n.contentIntent ?: return
        if (contentIntent.isActivity) {
            fireQqBubble(title, contentIntent)
        }
    }

    private fun isQqNotifyEnabled(): Boolean {
        // 读取共享配置：QQ 提醒开关
        return try {
            getSharedPreferences(ModulePreferences.GROUP, MODE_PRIVATE)
                .getBoolean(ModulePreferences.KEY_QQ_BUBBLE_ENABLED, false)
        } catch (_: Exception) {
            true
        }
    }

    /** 发广播通知 SystemUI 弹 QQ 联系人气泡。 */
    private fun fireQqBubble(title: String, pi: PendingIntent) {
        try {
            val i =
                Intent(SharedStateProtocol.ACTION_QQ_NOTIFY).apply {
                    putExtra(SharedStateProtocol.EXTRA_QQ_TITLE, title)
                    putExtra(SharedStateProtocol.EXTRA_QQ_PENDING_INTENT, pi)
                }
            sendBroadcast(i)
            Log.i(TAG, "QQ_NOTIFY_BROADCAST title=$title")
        } catch (e: Exception) {
            Log.w(TAG, "QQ_NOTIFY_BROADCAST_FAIL", e)
        }
    }

    private companion object {
        const val TAG = "QqNotify"
        const val QQ_PACKAGE = "com.tencent.mobileqq"
    }
}