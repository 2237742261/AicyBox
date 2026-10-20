package com.eta.aicybox.hook

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.net.Uri
import android.os.PowerManager
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import com.eta.aicybox.config.SharedStateProtocol
import com.eta.aicybox.config.ModuleSettingsSnapshot
import com.eta.aicybox.hook.logFiltered
import com.eta.aicybox.R
import io.github.libxposed.api.XposedModule

/**
 * @author bomo Aicy 建议的 SystemUI 侧其余功能（2026-10-17 抄欧泡小窗）：
 *
 * - **复制链接直达**（sugLink）：监听剪贴板，复制到链接 → 识别是主流平台分享还是普通链接，
 *   弹对应应用图标气泡，点击小窗/跳应用打开
 * - **分享链接识别**（sugWeb / mapPkg）：域名 → 应用包名映射
 * - **应用安装完成**（sugInstall）：PACKAGE_ADDED → 弹该应用图标气泡，点击打开
 * - **耳机连接**（sugMusic）：蓝牙耳机连接 → 弹音乐应用图标气泡，点击打开音乐应用
 * - **屏幕常亮**（sugKeep）：息屏前压暗 → 气泡，点击进入常亮（WakeLock 0xA）
 *
 * 宿主是 [SystemUiBubbleMonitor]（负责气泡窗口与手势），本类只负责"检测事件 + 弹对应气泡"。
 */
internal class SystemUiAicyFeatures(
    private val context: Context,
    private val module: XposedModule,
    private val host: SystemUiBubbleMonitor,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var settings = ModuleSettingsSnapshot(enabled = false)

    // 监听器状态
    private var receiversRegistered = false
    private var clipRegistered = false

    // ---------- QQ 消息提醒（需求2，2026-10-19）：App 的 NotificationListenerService 广播 → 弹气泡 ----------

    // ---------- 屏幕常亮：Pano 式 dumpsys display 轮询（2026-10-18 替换 system_server hook） ----------
    // Pano 思路：在宿主进程内轮询 `dumpsys display` 的 mScreenState，检测到「压暗(DIM)」即弹气泡，
    // 全程不碰 system_server / 不依赖广播，改代码只需重启 SystemUI 进程。
    private var keepAwakeThread: HandlerThread? = null
    private var keepAwakeHandler: Handler? = null
    private var keepAwakeRunning = false
    private var lastKeepAwakeState: String? = null
    @Volatile private var dimmed = false
    private val keepAwakePollRunnable = object : Runnable {
        override fun run() {
            if (!keepAwakeRunning) return
            try {
                val state = readScreenState()
                if (state != null && state != lastKeepAwakeState) {
                    val prev = lastKeepAwakeState
                    lastKeepAwakeState = state
                    // @author bomo 2026-10-18(用户要求)：气泡不在压暗(DIM)时弹，而要在「压暗后用户点亮屏幕」时弹。
                    // 逻辑：DIM 记为 dimmed → 之后回到 BRIGHT（用户点亮）且之前压暗过 → 弹气泡。
                    when (state) {
                        "DIM" -> {
                            dimmed = true
                            module.logFiltered(Log.DEBUG, TAG, "KEEP_AWAKE_DIM prev=$prev")
                        }
                        else -> {
                            // BRIGHT/其他 = 用户点亮了屏幕
                            if (dimmed) {
                                dimmed = false
                                module.logFiltered(Log.DEBUG, TAG, "KEEP_AWAKE_SCREEN_WOKE $prev -> $state TRIGGER")
                                mainHandler.post { onKeepAwakeTrigger() }
                            }
                        }
                    }
                }
                if (lastKeepAwakeState == null) lastKeepAwakeState = state
            } catch (e: Throwable) {
                module.logFiltered(Log.WARN, TAG, "KEEP_AWAKE_POLL_ERR", e)
            }
            keepAwakeHandler?.postDelayed(this, KEEP_AWAKE_POLL_MS)
        }
    }

    /** 读取 dumpsys display 的 mScreenState（BRIGHT/DIM/OFF/DOZE 之一），失败返回 null。 */
    /** 读取 dumpsys display 的电源策略（Priority）：优先 mPendingRequestLocked=policy=…，返回 DIM/BRIGHT/DOZE/OFF。 */
    private fun readScreenState(): String? = try {
        val process = Runtime.getRuntime().exec(arrayOf("/system/bin/dumpsys", "display"))
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        var state: String? = null
        reader.use { br ->
            while (true) {
                val line = br.readLine() ?: break
                if (line.length > 2000) continue
                val idx = line.indexOf("mPendingRequestLocked=policy=")
                if (idx >= 0) {
                    // 形如 mPendingRequestLocked=policy=BRIGHT, ... → 取 policy 值
                    val v = line.substring(idx + "mPendingRequestLocked=policy=".length).substringBefore(',').trim()
                    if (v.isNotEmpty()) { state = v; break }
                }
            }
            // 上面定位不到再退回 mPowerRequest=policy=…（备用锚点）
            if (state == null) {
                val extra = reader.readText()
                for (tok in listOf("mPendingRequestLocked=policy=", "mPowerRequest=policy=")) {
                    val i = extra.indexOf(tok)
                    if (i >= 0) {
                        val v = extra.substring(i + tok.length).substringBefore(',').trim()
                        if (v.isNotEmpty()) { state = v; break }
                    }
                }
            }
        }
        process.waitFor()
        state
    } catch (_: Throwable) {
        null
    }

    private fun startKeepAwakeMonitor() {
        if (keepAwakeRunning || keepAwakeHandler != null) return
        val thread = HandlerThread("keepAwake-monitor")
        thread.start()
        keepAwakeThread = thread
        keepAwakeHandler = Handler(thread.looper)
        keepAwakeRunning = true
        lastKeepAwakeState = null
        dimmed = false
        keepAwakeHandler?.post(keepAwakePollRunnable)
        module.logFiltered(Log.INFO, TAG, "KEEP_AWAKE_MONITOR_START")
    }

    private fun stopKeepAwakeMonitor() {
        keepAwakeRunning = false
        keepAwakeHandler?.removeCallbacksAndMessages(null)
        keepAwakeHandler = null
        keepAwakeThread?.quitSafely()
        keepAwakeThread = null
        module.logFiltered(Log.INFO, TAG, "KEEP_AWAKE_MONITOR_STOP")
    }

    /** 平台域名 → 应用包名（抄欧泡 mapPkg，覆盖常用分享平台）。 */
    private val platformMap =
        linkedMapOf(
            "taobao.com" to "com.taobao.taobao",
            "tb.cn" to "com.taobao.taobao",
            "jd.com" to "com.jingdong.app.mall",
            "v.douyin.com" to "com.ss.android.ugc.aweme",
            "iesdouyin.com" to "com.ss.android.ugc.aweme",
            "douyin.com" to "com.ss.android.ugc.aweme",
            "bilibili.com" to "tv.danmaku.bili",
            "b23.tv" to "tv.danmaku.bili",
            "weibo.com" to "com.sina.weibo",
            "xiaohongshu" to "com.xingin.xhs",
            "xhslink" to "com.xingin.xhs",
            "yangkeduo.com" to "com.xunmeng.pinduoduo",
            "pinduoduo.com" to "com.xunmeng.pinduoduo",
            "coolapk.com" to "com.coolapk.market",
            "pan.quark.cn" to "com.quark.browser",
            "goofish.com" to "com.taobao.idlefish",
            "1688.com" to "com.alibaba.wireless",
            "amap.com" to "com.autonavi.minimap",
            "qunar.com" to "com.Qunar",
            "kugou.com" to "com.kugou.android",
        )

    /** @return 配置变更后刷新各监听器启停。 */
    fun onSettingsChanged(snapshot: ModuleSettingsSnapshot) {
        settings = snapshot
        val aicy = snapshot.aicyBubblesEnabled
        val want = aicy && (snapshot.linkBubbleEnabled || snapshot.shareBubbleEnabled ||
            snapshot.appInstallBubbleEnabled || snapshot.earphoneBubbleEnabled ||
            snapshot.keepAwakeBubbleEnabled)
        if (want) registerReceivers() else unregisterReceivers()
        // 屏幕常亮：Pano 式轮询监测（2026-10-18）
        val keepAwakeWanted = aicy && snapshot.keepAwakeBubbleEnabled
        if (keepAwakeWanted) startKeepAwakeMonitor() else stopKeepAwakeMonitor()
    }

    // ---------- QQ 消息提醒（需求2） ----------



    /** @author bomo 2026-10-19：SystemUI hook 到 QQ 通知 → 弹气泡。 */
    fun showQqBubbleFromSbn(sbn: android.service.notification.StatusBarNotification) {
        showMsgBubbleFromSbn(sbn, QQ_PACKAGE, settings.aicyBubblesEnabled && settings.qqBubbleEnabled, QQ_PACKAGE, "QQ")
    }

    /** @author bomo 2026-10-19：微信消息提醒（同 QQ 方案，不 hook 微信进程）。 */
    fun showWxBubbleFromSbn(sbn: android.service.notification.StatusBarNotification) {
        showMsgBubbleFromSbn(sbn, WX_PACKAGE, settings.aicyBubblesEnabled && settings.wechatBubbleEnabled, WX_PACKAGE, "WeChat")
    }

    private fun showMsgBubbleFromSbn(
        sbn: android.service.notification.StatusBarNotification,
        targetPkg: String,
        enabled: Boolean,
        iconPkg: String,
        logTag: String,
    ) {
        val pkg = runCatching { sbn.packageName }.getOrNull() ?: return
        if (pkg != targetPkg) return
        if (!enabled) return
        val n = runCatching { sbn.notification }.getOrNull() ?: return
        val title = n.extras?.getString(android.app.Notification.EXTRA_TITLE)?.takeIf { it.isNotBlank() } ?: return
        val contentIntent = n.contentIntent ?: return
        // 联系人头像：大图标通常就是联系人头像（ColorOS 通知栏同源），取不到退化应用图标
        val avatar: Drawable? =
            runCatching {
                // API23+ 大图标是 Icon；旧版是 Bitmap（n.largeIcon deprecated）
                n.getLargeIcon()?.loadDrawable(context)
            }.getOrNull()
        host.showBubbleTemporary(
            icon = avatar ?: appIcon(iconPkg) ?: solidCircle(Color.argb(255, 36, 138, 240)),
            onClick = {
                // 以小窗方式打开对应联系人聊天框：拆出 intent + ActivityOptions(自由窗) 启动
                launchPendingIntentFreeform(contentIntent)
            },
            autoDismissMs = 6000L,
            // @author bomo 2026-10-22(用户要求)：标签这个气泡属于哪个应用 —— 用户从 QQ/微信进去后气泡立即消失
            ownerPackage = targetPkg,
        )
        module.logFiltered(Log.DEBUG, TAG, "${logTag}_NOTIFY_BUBBLE title=$title avatar=${avatar != null}")
    }

    /** @author bomo 2026-10-19：把 QQ 通知的 PendingIntent 以小窗(自由窗)方式打开聊天框。 */
    private fun launchPendingIntentFreeform(pi: PendingIntent) {
        val intent = pendingIntentToIntent(pi)
        if (intent != null) {
            val comp = intent.component?.flattenToString() ?: intent.action
            module.logFiltered(Log.DEBUG, TAG, "QQ_FREEFORM_TRY intent=$comp")
            try {
                launchAsFreeform(intent)
                module.logFiltered(Log.DEBUG, TAG, "QQ_FREEFORM_LAUNCHED $comp")
                return
            } catch (e: Exception) {
                module.logFiltered(Log.WARN, TAG, "QQ_FREEFORM_FAIL $comp", e)
            }
        }
        // 兜底：拆 intent 失败则直接 send（全屏）
        try {
            module.logFiltered(Log.INFO, TAG, "QQ_FREEFORM_FALLBACK_SEND intent=${intent?.component?.flattenToString() ?: "null"}")
            pi.send()
        } catch (_: Exception) {
        }
    }

    /** 反射从 PendingIntent 拿内部 Intent（兼容老 SDK），取不到返回 null。 */
    private fun pendingIntentToIntent(pi: PendingIntent): Intent? =
        try {
            val f = PendingIntent::class.java.getDeclaredField("mIntent")
            f.isAccessible = true
            (f.get(pi) as? Intent)?.let { Intent(it) }
        } catch (_: Throwable) {
            try {
                val m = PendingIntent::class.java.getDeclaredMethod("getIntent")
                m.isAccessible = true
                (m.invoke(pi) as? Intent)?.let { Intent(it) }
            } catch (_: Throwable) {
                null
            }
        }

    private fun registerReceivers() {
        if (receiversRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        try {
            // Android 14+：动态注册接收其它 App 广播必须显式 RECEIVER_EXPORTED，否则收不到
            // （MT 管理器等第三方安装器发的 PACKAGE_ADDED 因此被静默丢弃 → "安装完没气泡"）。
            // SCREEN 与 HEADSET 是系统广播，用 RECEIVER_EXPORTED 同样安全。
            context.registerReceiver(packageReceiver, filter, Context.RECEIVER_EXPORTED)
            context.registerReceiver(
                screenReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_SCREEN_OFF)
                },
                Context.RECEIVER_EXPORTED,
            )
            // @author bomo 2026-10-18：耳机用 AudioManager 设备回调（同时覆盖有线/蓝牙/USB），
            // 替代 ACTION_HEADSET_PLUG（只覆盖 3.5mm 有线，蓝牙耳机连接不触发）。
            registerHeadsetAudioCallback()
            registerClipListener()
            receiversRegistered = true
            module.logFiltered(Log.INFO, TAG, "AICY_FEATURES_RECEIVERS_READY")
        } catch (e: RuntimeException) {
            module.logFiltered(Log.WARN, TAG, "AICY_FEATURES_REGISTER_FAIL", e)
        }
    }

    // ---------- 耳机/蓝牙：AudioManager 设备回调（2026-10-18） ----------

    private var audioManager: AudioManager? = null
    private var audioCallback: AudioDeviceCallback? = null
    private var lastEarplugId = -1

    private fun registerHeadsetAudioCallback() {
        if (audioCallback != null) return
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val cb =
            object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
                    for (d in addedDevices) {
                        if (isHeadsetDevice(d)) {
                            lastEarplugId = d.id
                            if (settings.aicyBubblesEnabled && settings.earphoneBubbleEnabled) {
                                module.logFiltered(Log.DEBUG, TAG, "EARPHONE_DEVICE_ADDED type=${d.type} id=${d.id}")
                                onEarphoneConnected()
                            }
                            break
                        }
                    }
                }

                override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
                    for (d in removedDevices) {
                        if (d.id == lastEarplugId) lastEarplugId = -1
                    }
                }
            }
        try {
            am.registerAudioDeviceCallback(cb, null)
            audioManager = am
            audioCallback = cb
            module.logFiltered(Log.INFO, TAG, "EARPHONE_AUDIO_CALLBACK_READY")
        } catch (e: Exception) {
            module.logFiltered(Log.WARN, TAG, "EARPHONE_AUDIO_CALLBACK_FAIL", e)
        }
    }

    private fun unregisterHeadsetAudioCallback() {
        audioCallback?.let { audioManager?.unregisterAudioDeviceCallback(it) }
        audioManager = null
        audioCallback = null
        lastEarplugId = -1
    }

    /** 判断音频设备是否为耳机/蓝牙音频输出（有线 TYPE_WIRED_*、蓝牙 TYPE_BLUETOOTH_*）。 */
    private fun isHeadsetDevice(d: AudioDeviceInfo): Boolean {
        val t = d.type
        return t == AudioDeviceInfo.TYPE_WIRED_HEADSET || t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
            t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            t == AudioDeviceInfo.TYPE_USB_HEADSET || t == AudioDeviceInfo.TYPE_BLE_HEADSET ||
            t == AudioDeviceInfo.TYPE_BLE_SPEAKER
    }

    private fun registerClipListener() {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager ?: return
        try {
            cm.addPrimaryClipChangedListener(clipListener)
            clipRegistered = true
        } catch (_: Exception) {
        }
    }

    private fun unregisterClipListener() {
        if (!clipRegistered) return
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager ?: return
        try {
            cm.removePrimaryClipChangedListener(clipListener)
        } catch (_: Exception) {
        }
        clipRegistered = false
    }

    private fun unregisterReceivers() {
        if (!receiversRegistered) return
        try {
            context.unregisterReceiver(packageReceiver)
            context.unregisterReceiver(screenReceiver)
            unregisterHeadsetAudioCallback()
            unregisterClipListener()
        } catch (_: Exception) {
        }
        receiversRegistered = false
    }

    /** 应用安装完成 */
    /** 复制链接 */
    private val clipListener =
        android.content.ClipboardManager.OnPrimaryClipChangedListener {
            // 复制链接直达 与 分享链接识别 共用剪贴板监听；任一开启即响应
            if (!settings.aicyBubblesEnabled || (!settings.linkBubbleEnabled && !settings.shareBubbleEnabled)) return@OnPrimaryClipChangedListener
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager ?: return@OnPrimaryClipChangedListener
            val clip = cm.primaryClip ?: return@OnPrimaryClipChangedListener
            if (clip.itemCount == 0) return@OnPrimaryClipChangedListener
            val text = try { clip.getItemAt(0).coerceToText(context).toString() } catch (_: Exception) { return@OnPrimaryClipChangedListener }
            if (text.isBlank()) return@OnPrimaryClipChangedListener
            onClipboardText(text)
        }

    private val packageReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (!settings.aicyBubblesEnabled || !settings.appInstallBubbleEnabled) return
                val action = intent.action
                if (action != Intent.ACTION_PACKAGE_ADDED && action != Intent.ACTION_PACKAGE_REPLACED) return
                // 2026-10-18：覆盖安装/同版本安装也要弹气泡（移除 EXTRA_REPLACING 过滤）
                val pkg =
                    intent.dataString
                        ?.removePrefix("package:")
                        ?: return
                if (pkg == context.packageName) return
                module.logFiltered(Log.DEBUG, TAG, "PACKAGE_EVENT action=$action pkg=$pkg")
                onAppInstalled(pkg)
            }
        }

    /** 屏幕常亮：息屏(压暗) → 弹气泡 */
    private val screenReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (!settings.aicyBubblesEnabled || !settings.keepAwakeBubbleEnabled) return
                // SCREEN_OFF：释放常亮。常亮触发(SCREEN_ON 半暗唤醒)改由 system_server PowerGroup hook 广播
                if (intent.action == Intent.ACTION_SCREEN_OFF) onScreenOffRelease()
            }
        }

    // ---------- 复制链接 + 分享链接识别 ----------

    private fun onClipboardText(text: String) {
        val url = extractUrl(text) ?: return
        // 平台分享链接优先 → 对应应用（用 ACTION_VIEW + 原链接，让平台打开具体内容页，
        // 而不是 getLaunchIntent 只开 app 主页 —— 这正是"点击只跳 app、不跳链接页"的根因）
        val targetPkg = matchPlatform(text) ?: matchPlatform(url)
        val pm = context.packageManager
        val viewIntent =
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        var finalIntent: Intent? = viewIntent
        var iconPkg = targetPkg
        if (targetPkg != null) {
            // 平台链接：ACTION_VIEW + 该链接，且优先定向到平台 app，让平台解析短链跳内容页
            val resolved = try {
                pm.resolveActivity(
                    viewIntent,
                    PackageManager.MATCH_DEFAULT_ONLY,
                )
            } catch (_: Exception) {
                null
            }
            // 若系统解析得到的就是平台自身，直接用；否则显式 setPackage 平台
            val naturallyPlatform =
                resolved?.activityInfo?.packageName == targetPkg
            if (!naturallyPlatform) finalIntent = Intent(viewIntent).apply { setPackage(targetPkg) }
        } else {
            // 普通链接 → 浏览器
            val browser = resolveBrowser()
            if (browser != null) {
                iconPkg = browser
                finalIntent = Intent(viewIntent).apply { setPackage(browser) }
            }
        }
        val finalIntentRef = finalIntent ?: return
        host.showBubbleTemporary(
            icon = iconPkg?.let { appIcon(it) } ?: solidCircle(Color.LTGRAY),
            onClick = {
                try {
                    // 统一改小窗打开（用户 2026-10-18 要求：气泡应用跳转都改成小窗）
                    launchAsFreeform(finalIntentRef)
                } catch (_: Exception) {
                }
            },
            autoDismissMs = 6000L,
        )
        module.logFiltered(Log.DEBUG, TAG, "LINK_BUBBLE url=$url target=$iconPkg")
    }

    /**
     * @author bomo 2026-10-18：气泡点击统一以小窗（ColorOS 自由窗）打开目标。
     * 在 SystemUI 进程用 startActivity + 自由窗 Bundle 参数启动，效果与轮盘小窗一致。
     */
    private fun launchAsFreeform(intent: Intent) {
        val options = activityOptionsFreeform()
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (options != null) {
                context.startActivity(intent, options)
            } else {
                context.startActivity(intent)
            }
        } catch (_: Exception) {
        }
    }

    private fun activityOptionsFreeform(): android.os.Bundle? =
        try {
            android.app.ActivityOptions.makeBasic().let { opts ->
                android.app.ActivityOptions::class.java
                    .getDeclaredMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                    .also { it.isAccessible = true }
                    .invoke(opts, 100)
                opts.toBundle()
            }
        } catch (_: Exception) {
            null
        }

    private fun matchPlatform(text: String): String? {
        val t = text.lowercase()
        for ((domain, pkg) in platformMap) {
            if (t.contains(domain)) return pkg
        }
        return null
    }

    private fun resolveBrowser(): String? {
        val pm = context.packageManager
        val intent =
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("http://example.com"))
        val info = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) ?: return null
        return info.activityInfo?.packageName
    }

    private fun extractUrl(text: String): String? {
        // 找第一个 http(s):// 开头的 URL
        val lower = text.lowercase()
        var idx = lower.indexOf("http://")
        if (idx < 0) idx = lower.indexOf("https://")
        if (idx < 0) {
            // 也认 www. / 短链（b23.tv 等不带协议）
            return null
        }
        var end = idx
        while (end < text.length && !text[end].isWhitespace() && text[end] != '\u3000') end++
        return text.substring(idx, end)
    }

    // ---------- 耳机连接 ----------

    private fun onEarphoneConnected() {
        // 方案A(2026-10-18)：优先用用户在设置里选中的单个音乐包名；未装则退化到内置默认列表第一个装的
        val selected = settings.earphoneMusicPkgs.trim()
        val pkgs =
            if (selected.isNotEmpty()) listOf(selected)
            else DEFAULT_MUSIC_PKGS
        val pm = context.packageManager
        for (pkg in pkgs) {
            val intent = pm.getLaunchIntentForPackage(pkg) ?: continue
            host.showBubbleTemporary(
                icon = appIcon(pkg) ?: solidCircle(Color.LTGRAY),
                onClick = {
                    // 统一小窗打开（用户 2026-10-18）
                    launchAsFreeform(intent)
                },
                autoDismissMs = 6000L,
            )
            module.logFiltered(Log.DEBUG, TAG, "EARPHONE_BUBBLE music=$pkg")
            return
        }
    }

    // ---------- 应用安装 ----------


    private fun onAppInstalled(pkg: String) {
        val pm = context.packageManager
        // 允许无 launch intent 的应用也弹气泡（MT 装的部分工具/无桌面应用 getLaunchIntent 可能为 null，
        // 旧实现直接 return 导致"装了不弹"）；有 intent 则点击可打开，没有则只展示提示。
        val intent = pm.getLaunchIntentForPackage(pkg)
        host.showBubbleTemporary(
            icon = appIcon(pkg) ?: solidCircle(Color.LTGRAY),
            onClick = {
                // 统一小窗打开（用户 2026-10-18）；无 launch intent 时点击只消失
                if (intent != null) launchAsFreeform(intent)
            },
            autoDismissMs = 6000L,
        )
        module.logFiltered(Log.DEBUG, TAG, "APP_INSTALL_BUBBLE pkg=$pkg hasLaunch=${intent != null}")
    }

    // ---------- 屏幕常亮 ----------

    private var keepWakeLock: PowerManager.WakeLock? = null
    private var keepAppPkg: String? = null
    private val keepWatchRunnable = object : Runnable {
        override fun run() {
            if (keepWakeLock?.isHeld != true) return
            val current = foregroundApp()
            val anchored = keepAppPkg
            if (anchored != null && current != anchored) {
                // 切换了应用 → 常亮失效
                module.logFiltered(Log.DEBUG, TAG, "KEEP_SCREEN_ON_APP_SWITCHED $anchored -> $current RELEASE")
                releaseScreenOn()
                return
            }
            mainHandler.postDelayed(this, 800L)
        }
    }

    /** @author bomo 外部（system_server PowerGroup hook 或 SCREEN_ON 广播）触发：弹"点击保持常亮"气泡。 */
    fun onKeepAwakeTrigger() {
        if (keepWakeLock?.isHeld == true) return
        // @author bomo 2026-10-19(用户要求)：锁屏页面下压暗→点亮不该弹常亮气泡（刚在锁屏压暗点亮解锁会误弹）
        if (isKeyguardLocked()) {
            module.logFiltered(Log.DEBUG, TAG, "KEEP_AWAKE_SKIP_LOCKED")
            return
        }
        host.showBubbleTemporary(
            // @author bomo 2026-10-18：换用户提供的常亮图标资源（keep_awake_bubble，从模块 APK 加载）
            icon = moduleDrawable(R.drawable.keep_awake_bubble) ?: solidCircle(Color.argb(255, 96, 169, 250)),
            onClick = {
                keepScreenOn()
            },
            autoDismissMs = 6000L,
        )
        module.logFiltered(Log.DEBUG, TAG, "KEEP_AWAKE_BUBBLE_SHOW")
    }

    internal fun onScreenOffRelease() {
        // 息屏：释放常亮
        lastKeepAwakeState = null // 2026-10-18 息屏后重置，避免再点亮时把闪烁当 DIM 跃迁
        dimmed = false
        releaseScreenOn()
    }

    private fun releaseScreenOn() {
        mainHandler.removeCallbacks(keepWatchRunnable)
        keepAppPkg = null
        try {
            if (keepWakeLock?.isHeld == true) keepWakeLock?.release()
        } catch (_: Exception) {
        }
    }

    /**
     * 前台全屏主应用包名（临时常亮锚定）。@author bomo 2026-10-19
     * ColorOS 小窗会抢 topResumedActivity 焦点，且小窗任务也 visible+fullscreen，
     * 但它们 bounds 不是全屏。判定：解析 dumpsys activity activities 的顶层 `* Task{...}`，
     * 若该任务 visible=true 且 mode=fullscreen 且 mBounds 覆盖整屏(0,0-W,H) 则是前台主应用。
     * 小窗 bounds 非全屏→排除→小窗打开不影响主应用，常亮保持。
     */
    private fun foregroundApp(): String? {
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("/system/bin/dumpsys", "activity", "activities"))
            val reader = java.io.BufferedReader(java.io.InputStreamReader(proc.inputStream))
            val SCREEN_W = context.resources.displayMetrics.widthPixels
            val SCREEN_H = context.resources.displayMetrics.heightPixels
            var pkg: String? = null
            var visible = false
            var isFullscreen = false
            var boundsFull = false
            val lines = reader.readLines()
            proc.waitFor()
            for (line in lines) {
                if (line.length > 4000) continue
                if (line.trim().startsWith("* Task{")) {
                    // 结算上一任务
                    if (visible && isFullscreen && boundsFull && pkg != null) return pkg
                    // 新任务解析
                    pkg = null; visible = false; isFullscreen = false; boundsFull = false
                    val aIdx = line.indexOf(" A=")
                    if (aIdx >= 0) {
                        var pp = line.substring(aIdx + 3)
                        val c = pp.indexOf(':'); if (c >= 0) pp = pp.substring(c + 1)
                        pkg = pp.trim().substringBefore(' ').substringBefore('}')
                    }
                    if (line.contains("visible=true")) visible = true
                    if (line.contains("mode=fullscreen")) isFullscreen = true
                    continue
                }
                // task 块内找 mBounds
                if (pkg != null && !boundsFull) {
                    val bIdx = line.indexOf("mBounds=Rect(")
                    if (bIdx >= 0) {
                        val s2 = line.substring(bIdx + "mBounds=Rect(".length)
                        val m = Regex("(\\d+),\\s*(\\d+)\\s*-\\s*(\\d+),\\s*(\\d+)").find(s2)
                        if (m != null) {
                            val l = m.groupValues[1].toInt()
                            val r = m.groupValues[3].toInt()
                            val b = m.groupValues[4].toInt()
                            if (l == 0 && r >= SCREEN_W - 2 && b >= SCREEN_H - 2) boundsFull = true
                        }
                    }
                }
            }
            // 收尾最后一个任务
            if (visible && isFullscreen && boundsFull && pkg != null) return pkg
        } catch (_: Throwable) {
        }
        return fallbackKeepForeground()
    }

    /** @author bomo 2026-10-19：是否处于锁屏（Keyguard 锁定）。 */
    private fun isKeyguardLocked(): Boolean =
        try {
            val km = context.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
            km.isKeyguardLocked
        } catch (_: Exception) {
            false
        }

    private fun fallbackKeepForeground(): String? =
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val tasks = am.getRunningTasks(1)
            if (tasks.isNotEmpty()) tasks[0].topActivity?.packageName else null
        } catch (_: Exception) {
            null
        }

    private fun keepScreenOn() {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            var wl = keepWakeLock
            if (wl == null) {
                wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "aicy:keep")
                wl.setReferenceCounted(false)
                keepWakeLock = wl
            }
            if (!wl.isHeld) {
                // @author bomo 2026-10-19(用户修复)：原来 acquire(60s) 到期后系统按原息屏计时直接黑屏。
                // 改为无限期持有，由 keepWatchRunnable(切应用) 或 onScreenOffRelease(息屏) 释放，
                // 实现「当前应用内持续常亮，退出/息屏才恢复」。
                wl.acquire()
            }
            // 锚定当前前台应用；切换应用或下次熄屏都释放（用户 2026-10-18：仅限当前应用）
            keepAppPkg = foregroundApp()
            module.logFiltered(Log.DEBUG, TAG, "KEEP_SCREEN_ON_ACQUIRED app=${keepAppPkg}")
            mainHandler.removeCallbacks(keepWatchRunnable)
            mainHandler.postDelayed(keepWatchRunnable, 800L)
        } catch (e: Exception) {
            module.logFiltered(Log.WARN, TAG, "KEEP_SCREEN_ON_FAIL", e)
        }
    }

    // ---------- 应用图标 ----------

    /** @author bomo 2026-10-18：从模块 APK 加载 drawable 资源（SystemUI 进程跨包读取）。 */
    private fun moduleDrawable(resId: Int): Drawable? =
        try {
            val moduleCtx =
                context.createPackageContext(
                    SharedStateProtocol.MODULE_PACKAGE,
                    Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY,
                )
            moduleCtx.getDrawable(resId)
        } catch (_: Exception) {
            null
        }

    private fun appIcon(pkg: String): Drawable? =
        try {
            context.packageManager.getApplicationIcon(pkg)
        } catch (_: Exception) {
            null
        }

    private fun solidCircle(color: Int): Drawable {
        val size = (48 * context.resources.displayMetrics.density).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawCircle(size / 2f, size / 2f, size / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        return BitmapDrawable(context.resources, bmp)
    }

    companion object {
        private const val TAG = "AicyFeatures"
        private const val QQ_PACKAGE = "com.tencent.mobileqq"
        private const val WX_PACKAGE = "com.tencent.mm"

        /** @author bomo 2026-10-18 Pano 式 dumpsys 轮询间隔（ms）。用户 2026-10-18 要求放宽省电：250→500。 */
        const val KEEP_AWAKE_POLL_MS = 500L

        /** @author bomo 2026-10-18：内置默认音乐应用列表（用户自定义为空时退化使用）。 */
        private val DEFAULT_MUSIC_PKGS =
            listOf(
                "com.netease.cloudmusic",
                "com.tencent.qqmusic",
                "com.kugou.android",
                "com.sds.android.ttpod",
            )
    }
}