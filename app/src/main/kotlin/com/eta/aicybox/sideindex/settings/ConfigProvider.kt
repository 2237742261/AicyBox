package com.eta.aicybox.sideindex.settings

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import com.eta.aicybox.sideindex.Const
import com.eta.aicybox.sideindex.config.ConfigCodec
import com.eta.aicybox.sideindex.config.SecureConfigChannel
import com.eta.aicybox.sideindex.util.XLog

/**
 * 配置读取通道（备用）。主通道是 Settings.Secure；
 * provider 在这里作为其它 ROM/环境的兜底，只暴露模块设置项，不含隐私数据。
 */
class ConfigProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        val ctx = context
        if (ctx != null) {
            try {
                if (PrefStore.isFreshInstall(ctx)) {
                    // 数据被清除（或首次安装）：系统设置库里的旧配置也要清掉，
                    // 否则"清除数据"之后配置会原样回来（ColorOS 的 SP 隔离存储不受 pm clear 影响）
                    SecureConfigChannel.clear(ctx)
                    // ColorOS 把 SP 隔离到 /data/misc/apexdata 下，pm clear 清不掉，必须自己清，
                    // 否则随后的写入会把旧值又读回来
                    PrefStore.prefs(ctx).edit().clear().commit()
                    XLog.i("检测到应用数据被清除，已重置配置通道")
                }
                PrefStore.markInstalled(ctx)
            } catch (t: Throwable) {
                XLog.d("启动自检失败：$t")
            }
        }
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val result = Bundle()
        if (method == Const.PROVIDER_METHOD_GET) {
            val ctx = context ?: return result
            val payload = ConfigCodec.encode(PrefStore.currentConfig(ctx))
            result.putString(Const.PROVIDER_KEY_PAYLOAD, payload)
            XLog.d("ConfigProvider 返回配置（${payload.length} 字节）")
        }
        return result
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?
    ): Int = 0
}
