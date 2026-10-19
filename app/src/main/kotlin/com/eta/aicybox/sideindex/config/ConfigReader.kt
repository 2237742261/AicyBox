package com.eta.aicybox.sideindex.config

import android.content.Context
import android.net.Uri
import android.provider.Settings
import com.eta.aicybox.sideindex.Const
import com.eta.aicybox.sideindex.util.XLog
import java.io.File

/**
 * Hook 进程侧的配置读取，通道顺序：
 *   0) Settings.Secure（主通道，任何进程免权限，不依赖模块应用存活）
 *   1) 模块的 ContentProvider
 *   2) 直读 SharedPreferences XML
 *
 * ⚠️ 1.32.0 起去掉 XSharedPreferences：新版 API（targetApiVersion=102）禁止调用
 *    de.robv.android.xposed.* ；而 Secure 通道本来就是主通道，实测一直可用。
 */
object ConfigReader {

    private val IDLE = ModuleConfig(enabled = false, source = "unavailable")
    private const val FAILURE_WARN_INTERVAL_MS = 60_000L
    private const val PROVIDER_MIN_INTERVAL_MS = 1500L

    @Volatile private var direct: DirectPrefs? = null
    @Volatile private var cached: ModuleConfig = ModuleConfig()
    @Volatile private var lastReloadAt = 0L
    @Volatile private var lastSource = "none"
    @Volatile private var lastFailureWarnAt = 0L
    @Volatile private var configAvailable = false
    @Volatile private var appContext: Context? = null
    @Volatile private var lastProviderAt = 0L
    @Volatile private var secureHealed = false
    private var initialized = false

    fun attach(context: Context?) {
        if (appContext != null) return
        val ctx = context?.applicationContext ?: context ?: return
        appContext = ctx
    }

    fun current(): ModuleConfig {
        ensureInit()
        val interval = cached.reloadIntervalMs.toLong().coerceAtLeast(300L)
        val now = System.currentTimeMillis()
        if (now - lastReloadAt < interval) return if (configAvailable) cached else IDLE
        synchronized(this) {
            if (System.currentTimeMillis() - lastReloadAt < interval) {
                return if (configAvailable) cached else IDLE
            }
            lastReloadAt = System.currentTimeMillis()
            load()?.let {
                cached = it
                XLog.setVerbose(it.logVerbose)
            } ?: run {
                if (!configAvailable && now - lastFailureWarnAt > FAILURE_WARN_INTERVAL_MS) {
                    lastFailureWarnAt = now
                    XLog.w("暂时读不到模块配置，本次不启用索引（打开一次模块设置页即可生成）")
                }
            }
        }
        return if (configAvailable) cached else IDLE
    }

    fun invalidate() { lastReloadAt = 0L }

    fun sourceName(): String { ensureInit(); return lastSource }

    private fun ensureInit() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            initialized = true
            direct = DirectPrefs(PrefFiles.modulePrefsFile())
            load()?.let {
                cached = it
                XLog.setVerbose(it.logVerbose)
            }
            XLog.i("配置初始化：source=$lastSource 可读=$configAvailable")
        }
    }

    private fun load(): ModuleConfig? {
        appContext?.let { ctx ->
            SecureConfigChannel.read(ctx)?.let {
                if (lastSource != "secure") XLog.i("配置来源：Settings.Secure")
                lastSource = "secure"
                configAvailable = true
                return it
            }
        }

        // 1.32.2：Secure 为空时，靠 provider / 直读 XML 拿到的配置要顺手写回 Secure，
        // 否则「重启后必须点开一次模块 App」会复发（provider 依赖 App 进程，XML 被 SELinux 拦）
        readViaProvider()?.let {
            appContext?.let { ctx -> healSecure(ctx, it) }
            return it
        }

        val d = direct
        if (d != null && d.exists()) {
            val map = d.read()
            if (map != null) {
                val fromXml = ModuleConfig.read(MapPrefSource(map), "direct-xml")
                lastSource = "direct-xml"
                configAvailable = true
                appContext?.let { ctx -> healSecure(ctx, fromXml) }
                return fromXml
            }
        }
        if (!configAvailable) lastSource = "none"
        return null
    }

    /**
     * 配置通道自愈（1.32.2）：SystemUI 侧读到配置后把 Settings.Secure 补上。
     *
     * 为什么需要：`eta_sideindex_config` 是 SystemUI 里唯一不依赖模块 App 进程的通道，
     * 但设置页只在「用户真的改了某一项」时才写它 —— 从没改过设置的用户 Secure 一直是空的，
     * 重启后 SystemUI 只能去问 provider（App 进程没起来就失败），表现就是
     * 「必须手动点开一次模块 App 才有索引」。SystemUI 持有 WRITE_SECURE_SETTINGS，
     * 这里直接写，不碰 su。
     */
    private fun healSecure(ctx: Context, config: ModuleConfig) {
        if (secureHealed) return
        val payload = ConfigCodec.encode(config)
        if (payload.isEmpty()) return
        try {
            Settings.Secure.putString(ctx.contentResolver, SecureConfigChannel.KEY, payload)
            secureHealed = true
            XLog.i("配置通道自愈：已把配置写回 Settings.Secure（${payload.length} 字节）")
        } catch (t: Throwable) {
            XLog.d("配置通道自愈失败：${t.javaClass.simpleName}")
        }
    }

    private fun readViaProvider(): ModuleConfig? {

        val ctx = appContext ?: return null
        val now = System.currentTimeMillis()
        if (now - lastProviderAt < PROVIDER_MIN_INTERVAL_MS) return null
        lastProviderAt = now
        return try {
            val bundle = ctx.contentResolver.call(
                Uri.parse(Const.PROVIDER_URI), Const.PROVIDER_METHOD_GET, null, null
            )
            val payload = bundle?.getString(Const.PROVIDER_KEY_PAYLOAD)
            ConfigCodec.decode(payload)?.also {
                lastSource = "provider"
                configAvailable = true
                XLog.d("配置来源：provider")
            }
        } catch (t: Throwable) {
            XLog.d("provider 读取失败：${t.javaClass.simpleName}")
            null
        }
    }

}

