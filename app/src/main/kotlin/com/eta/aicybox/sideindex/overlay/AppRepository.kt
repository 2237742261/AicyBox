package com.eta.aicybox.sideindex.overlay

import android.app.ActivityOptions
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.LruCache
import com.eta.aicybox.sideindex.Const
import com.eta.aicybox.sideindex.index.Grouping
import com.eta.aicybox.sideindex.index.IndexGroup
import com.eta.aicybox.sideindex.index.Pinyin
import com.eta.aicybox.sideindex.util.XLog
import android.graphics.Bitmap
import android.graphics.Canvas
import com.eta.aicybox.sideindex.config.ModuleConfig
import com.eta.aicybox.sideindex.util.Display
import java.text.Collator

data class LaunchableApp(
    val packageName: String,
    val activityName: String,
    val label: String,
    val initial: String,
    val component: ComponentName
)

/**
 * 可启动应用清单 + 图标缓存。
 * 图标缓存是**进程级静态**的：浮层每次手势重建，缓存必须跨手势复用，
 * 否则每次都要重新加载 100+ 个图标，就是"不跟手"的主因。
 */
object AppRepository {

    private const val CACHE_TTL_MS = 120_000L

    /**
     * 重启后 SystemUI 常常比 PackageManager 先就绪（direct boot / 用户存储还没解锁），
     * 这时查询 CATEGORY_LAUNCHER 只能拿到寥寥几个应用 —— 曾出现"字母条只剩 Y 和 S"。
     * 清单数量明显偏少时改用短 TTL 反复重查，等系统扫完自动补全，不用手动重启系统界面。
     */
    private const val EARLY_TTL_MS = 2_500L
    private const val MIN_EXPECTED_APPS = 15

    @Volatile private var cached: List<LaunchableApp> = emptyList()
    /** 不含 ★ 的原始清单：重排收藏栏时拿它当底座 */
    @Volatile private var baseList: List<LaunchableApp> = emptyList()
    @Volatile private var groups: List<IndexGroup> = emptyList()
    @Volatile private var loadedAt = 0L

    private val iconCache = object : LruCache<String, Drawable?>(256) {}

    @Volatile private var refreshing = false

    /** [probeAsync] 的轮询次数上限（只在开机早期清单残缺时用） */
    @Volatile private var probeCount = 0

    fun apps(ctx: Context, force: Boolean = false): List<LaunchableApp> {
        val now = System.currentTimeMillis()
        val ttl = if (isSettled()) CACHE_TTL_MS else EARLY_TTL_MS
        if (!force && cached.isNotEmpty() && now - loadedAt < ttl) return cached
        if (!force && cached.isNotEmpty()) {
            // 缓存过期：手势路径上千万别同步查 PackageManager（100+ 个应用的 label 要几百毫秒，
            // 就是"呼出不跟手"的元凶）——先返回旧清单，后台刷新，下一次手势用新的
            refreshAsync(ctx)
            return cached
        }
        val list = load(ctx)
        if (list.isNotEmpty()) {
            val wasSettled = isSettled()
            installList(ctx, list)
            loadedAt = now
            if (!wasSettled) {
                val note = if (list.size < MIN_EXPECTED_APPS) "（系统可能还没扫完，稍后自动重查）" else ""
                XLog.i("应用清单就绪：${list.size} 个应用 / ${groups.size} 个字母$note")
            }
        }
        return cached
    }

    /** 清单还没像样（开机早期 PackageManager 还没扫完）时主动补全：无条件后台重查一次，不阻塞调用方 */
    fun probeAsync(ctx: Context) {
        // 上限兜底：真有"可启动应用本来就不足 15 个"的设备，别无限轮询（60 × 2.5s = 150s）
        if (isSettled() || probeCount >= 60) return
        probeCount++
        refreshAsync(ctx)
    }

    /** 后台刷新应用清单（结果只覆盖缓存，不阻塞调用方） */
    private fun refreshAsync(ctx: Context) {
        if (refreshing) return
        refreshing = true
        Thread {
            try {
                val list = load(ctx)
                if (list.isNotEmpty()) {
                    val oldCount = cached.size
                    installList(ctx, list)
                    loadedAt = System.currentTimeMillis()
                    if (list.size != oldCount) {
                        XLog.i("应用清单后台刷新：${list.size} 个应用 / ${groups.size} 个字母")
                    }
                }
            } catch (t: Throwable) {
                XLog.w("后台刷新应用清单失败", t)
            } finally {
                refreshing = false
            }
        }.start()
    }

    /** 清单是否已经"像样"：数量太少说明 PackageManager 还没扫完，先别长缓存 */
    fun isSettled(): Boolean = cached.size >= MIN_EXPECTED_APPS

    /**
     * ★ 收藏栏：只统计"通过索引栏打开"的次数。
     *
     * 次数写在 Settings.Secure 的 [STAR_KEY]（模块在系统界面进程里写自己的配置通道），
     * 格式 `pkg=count,pkg=count`。列表按次数降序返回 —— 网格行是从右往左排的，
     * 所以次数最多的显示在最右边（离手指最近）。
     *
     * [starLimit] 由宿主按"每行图标数 × 2 行"设置。
     */
    @Volatile var starLimit: Int = 10

    fun stars(ctx: Context, limit: Int = starLimit): List<LaunchableApp> = starsFrom(ctx, cached, limit)

    /** 重建清单与分组：★ 收藏栏排在最后（"#" 在最上，字母 A→Z 居中） */
    private fun installList(ctx: Context, list: List<LaunchableApp>) {
        baseList = list
        val stars = starsFrom(ctx, list, starLimit)
        // ★ 追加在清单末尾：分组区间直接接在字母组之后，不用再整体偏移
        cached = if (stars.isEmpty()) list else list + stars
        val starStart = list.size
        val out = ArrayList<IndexGroup>()
        for (g in Grouping.build(list.map { it.initial })) {
            out.add(IndexGroup(g.label, g.start, g.end))
        }
        // ★ 永远在最后一栏；没有打开记录时是空分组（网格里显示 0 个应用）
        out.add(IndexGroup("★", starStart, starStart + stars.size - 1))
        groups = out
    }

    /**
     * ★ 栏（折中方案）：
     *  ① 通过索引栏打开过的应用按次数降序（同次数按最近使用时间降序）—— 保留"我的常用"语义；
     *  ② 其余"设备最近使用"的应用按时间降序补齐 —— 一装上就能看到刚用过的，不会空着。
     * 顺序即显示顺序：网格行是 RTL，列表第一个出现在最右边（最常用/最近的离手指最近）。
     */
    /**
     * ★ 栏：按**应用被打开的总次数**降序（系统累计统计，近 30 天），次数多的排最右、离手指最近。
     * 不用"通过索引栏打开"的加权 —— 加权会让排序频繁跳动，用户想找常用应用时位置老在变（用户反馈）。
     * 系统次数是长期累计值，排序稳定；顺序即显示顺序（网格行是 RTL，第一个在最右）。
     */
    /** `UsageStats.getAppLaunchCount()` 是 SystemApi（公开 SDK 看不到），只能反射读 */
    private fun appLaunchCountOf(s: UsageStats): Int = try {
        UsageStats::class.java.getMethod("getAppLaunchCount").invoke(s) as? Int ?: 0
    } catch (t: Throwable) {
        0
    }

    private fun starsFrom(ctx: Context, list: List<LaunchableApp>, limit: Int): List<LaunchableApp> {
        val counts = launchCounts(ctx)
        if (counts.isEmpty()) return emptyList()
        val byPkg = HashMap<String, LaunchableApp>()
        // 用传进来的 list 建索引，不要用 cached：首次加载时 cached 还是空的，
        // ★ 栏会算成空分组（刚重启系统界面后第一次呼出就是这个现象）
        for (a in list) if (!byPkg.containsKey(a.packageName)) byPkg[a.packageName] = a
        val picked = counts.entries
            .filter { (pkg, n) -> n > 0 && pkg != Const.MODULE_PACKAGE && byPkg.containsKey(pkg) }
            .sortedByDescending { it.value }
            .mapNotNull { byPkg[it.key] }
            .take(limit)
        if (picked.isNotEmpty()) {
            XLog.d("★ 栏（左→右）：" + picked.reversed().joinToString(", ") {
                "${it.label}=${counts[it.packageName] ?: 0}"
            })
        }
        return picked
    }

    /**
     * 近 30 天各应用**被打开的总次数**（包名 → 次数）。
     * 用聚合统计 queryUsageStats 的 `appLaunchCount` 按天桶累加 —— 长期累计值、排序稳定；
     * SystemUI 是系统进程，读 UsageStats 不需要额外授权；结果缓存 30 秒（次数变化慢）。
     */
    @Volatile private var launchCache: Map<String, Int> = emptyMap()
    private var launchCacheAt = 0L

    private fun launchCounts(ctx: Context): Map<String, Int> {
        val now = System.currentTimeMillis()
        if (now - launchCacheAt < 30_000L) return launchCache
        val byLaunch = HashMap<String, Int>()
        val byTime = HashMap<String, Long>()
        try {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            if (usm != null) {
                val stats = usm.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY, now - COUNT_WINDOW_MS, now
                )
                if (stats != null) {
                    for (s in stats) {
                        val n = appLaunchCountOf(s)
                        if (n > 0) byLaunch[s.packageName] = (byLaunch[s.packageName] ?: 0) + n
                        val t = s.totalTimeInForeground
                        if (t > 0L) byTime[s.packageName] = (byTime[s.packageName] ?: 0L) + t
                    }
                }
            }
        } catch (t: Throwable) {
            XLog.d("读取打开次数失败：${t.javaClass.simpleName}")
        }
        // 次数优先；万一系统不给次数（反射失败）就退回"前台时长（分钟）"当常用度
        val result: HashMap<String, Int> = if (byLaunch.isNotEmpty()) {
            byLaunch
        } else {
            val m = HashMap<String, Int>()
            for ((k, v) in byTime) m[k] = (v / 60_000L).toInt().coerceAtLeast(1)
            m
        }
        launchCache = result
        launchCacheAt = now
        return result
    }

    private fun starCounts(ctx: Context): Map<String, Int> {
        val raw = try {
            android.provider.Settings.Secure.getString(ctx.contentResolver, STAR_KEY)
        } catch (t: Throwable) {
            null
        }.orEmpty()
        if (raw.isEmpty()) return emptyMap()
        val map = HashMap<String, Int>()
        raw.split(',').forEach { part ->
            val i = part.indexOf('=')
            if (i <= 0) return@forEach
            val pkg = part.substring(0, i).trim()
            val c = part.substring(i + 1).trim().toIntOrNull() ?: return@forEach
            if (pkg.contains('.')) map[pkg] = c
        }
        return map
    }

    /** 通过索引栏打开一次：次数 +1 写回 Secure（只保留次数最高的前 20 项），并把 ★ 栏立刻重排 */
    fun recordLaunch(ctx: Context, packageName: String) {
        try {
            val counts = starCounts(ctx).toMutableMap()
            counts[packageName] = (counts[packageName] ?: 0) + 1
            val payload = counts.entries
                .sortedByDescending { it.value }
                .take(20)
                .joinToString(",") { "${it.key}=${it.value}" }
            android.provider.Settings.Secure.putString(ctx.contentResolver, STAR_KEY, payload)
            XLog.i("★ 计数更新：$packageName=${counts[packageName]}")
            // 立刻按新计数重排 ★ 栏：否则要等下一次重扫应用清单（最长 2 分钟）才看得到
            refreshStarsNow(ctx)
        } catch (t: Throwable) {
            XLog.d("★ 计数写入失败：${t.javaClass.simpleName}")
        }
    }

    /** 按当前 Secure 里的计数立刻重排 ★ 栏：只重算分组，不重新扫 PackageManager */
    fun refreshStarsNow(ctx: Context) {
        val base = baseList
        if (base.isEmpty()) return
        try {
            installList(ctx, base)
        } catch (t: Throwable) {
            XLog.d("★ 立即刷新失败：${t.javaClass.simpleName}")
        }
    }

    private const val STAR_KEY = "eta_sideindex_star"

    /** 打开次数的统计窗口：30 天 */
    private const val COUNT_WINDOW_MS = 30L * 24 * 3600_000L

    fun groups(): List<IndexGroup> = groups

    /** 启动时/配置变化时预热，避免第一次手势卡顿；顺带后台预加载全部图标（否则首次滑到某组会现场加载图标） */
    fun prewarm(ctx: Context) {
        try { apps(ctx, force = true) } catch (t: Throwable) { XLog.w("预热应用清单失败", t) }
        Thread {
            try {
                val snapshot = cached
                val iconPx = Display.dp(ctx, ModuleConfig.FIXED_ICON_SIZE_DP)
                for (app in snapshot) { icon(ctx, app); iconBitmap(ctx, app, iconPx) }
                if (snapshot.isNotEmpty()) XLog.i("图标预热完成：${snapshot.size} 个")
            } catch (t: Throwable) {
                XLog.w("预热图标失败", t)
            }
        }.start()
    }

    fun invalidate() { loadedAt = 0L }

    /** 有应用装上/卸下时立刻重建清单并预热新图标（全在后台线程，不阻塞手势） */
    fun reloadAsync(ctx: Context) {
        if (refreshing) return
        refreshing = true
        Thread {
            try {
                val list = load(ctx)
                if (list.isNotEmpty()) {
                    installList(ctx, list)
                    loadedAt = System.currentTimeMillis()
                    XLog.i("应用变化后重建清单：${list.size} 个应用 / ${groups.size} 个字母")
                    val iconPx = Display.dp(ctx, ModuleConfig.FIXED_ICON_SIZE_DP)
                    for (app in list) { icon(ctx, app); iconBitmap(ctx, app, iconPx) }
                }
            } catch (t: Throwable) {
                XLog.w("应用变化后重建清单失败", t)
            } finally {
                refreshing = false
            }
        }.start()
    }

    private fun load(ctx: Context): List<LaunchableApp> {
        val pm = ctx.packageManager
        val result = ArrayList<LaunchableApp>()
        try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolved = pm.queryIntentActivities(intent, 0)
            val seen = HashSet<String>()
            for (info in resolved) {
                val ai = info.activityInfo ?: continue
                val key = "${ai.packageName}/${ai.name}"
                if (!seen.add(key)) continue
                val label = try { info.loadLabel(pm).toString() } catch (t: Throwable) { ai.packageName }
                result.add(
                    LaunchableApp(
                        packageName = ai.packageName,
                        activityName = ai.name,
                        label = label,
                        initial = Pinyin.initial(label),
                        component = ComponentName(ai.packageName, ai.name)
                    )
                )
            }
            val collator = Collator.getInstance()
            // "#"（数字/符号开头）排在最前，字母按 A→Z（★ 收藏栏排在最后，见 installList）
            result.sortWith(
                compareBy<LaunchableApp> { it.initial != "#" }
                    .thenBy { it.initial }
                    .thenBy(collator) { it.label }
            )
        } catch (t: Throwable) {
            XLog.w("读取可启动应用失败", t)
        }
        return result
    }

    private val iconBitmapCache = object : LruCache<String, Bitmap>(256) {}

    /**
     * 预渲染成小位图再交给 ImageView。
     * 图标大多是 AdaptiveIconDrawable：每次绘制都要按当前尺寸重新分层合成，
     * 换字母时十几个图标一起重绘就明显吃性能；贴位图只是拷贝像素。
     */
    fun iconBitmap(ctx: Context, app: LaunchableApp, sizePx: Int): Bitmap? {
        val key = "${app.component.flattenToString()}@$sizePx"
        iconBitmapCache.get(key)?.let { return it }
        val drawable = icon(ctx, app) ?: return null
        return try {
            val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, sizePx, sizePx)
            drawable.draw(Canvas(bmp))
            iconBitmapCache.put(key, bmp)
            bmp
        } catch (t: Throwable) {
            null
        }
    }

    fun icon(ctx: Context, app: LaunchableApp): Drawable? {
        val key = app.component.flattenToString()
        iconCache.get(key)?.let { return it }
        val drawable = try {
            ctx.packageManager.getActivityIcon(app.component)
        } catch (t: Throwable) {
            try { ctx.packageManager.getApplicationIcon(app.packageName) } catch (t2: Throwable) { null }
        }
        iconCache.put(key, drawable)
        return drawable
    }

    /** 启动：小窗优先走 ColorOS 弹性窗口协议，失败退 AOSP freeform，再退 root 的 am */
    fun launch(ctx: Context, app: LaunchableApp, freeform: Boolean): Boolean {
        if (freeform) {
            if (launchWithOptions(ctx, app, COLOROS_FLEXIBLE_MODE, zoomFlag = true)) {
                XLog.i("小窗启动（ColorOS 弹性窗口）：${app.packageName}")
                return true
            }
            if (launchWithOptions(ctx, app, AOSP_FREEFORM_MODE, zoomFlag = false)) {
                XLog.i("小窗启动（AOSP freeform）：${app.packageName}")
                return true
            }
            try {
                val cmd = "am start --windowingMode $COLOROS_FLEXIBLE_MODE -n ${app.packageName}/${app.activityName}"
                val code = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd)).waitFor()
                if (code == 0) { XLog.i("小窗启动（su am）：${app.packageName}"); return true }
            } catch (t: Throwable) {
                XLog.w("小窗启动失败（需要 root）：$t")
            }
        }
        return try {
            ctx.startActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(app.component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            )
            true
        } catch (t: Throwable) {
            XLog.e("启动 ${app.packageName} 失败", t)
            false
        }
    }

    private fun launchWithOptions(
        ctx: Context,
        app: LaunchableApp,
        windowingMode: Int,
        zoomFlag: Boolean
    ): Boolean = try {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(app.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val bundle = Bundle()
        bundle.putInt(BUNDLE_WINDOWING_MODE, windowingMode)
        if (zoomFlag) bundle.putInt(BUNDLE_ZOOM_FLAGS, ZOOM_LAUNCH_FLAG)
        try {
            val options = ActivityOptions.makeBasic()
            ActivityOptions::class.java
                .getDeclaredMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
                .invoke(options, windowingMode)
            bundle.putAll(options.toBundle())
        } catch (_: Throwable) {
        }
        ctx.startActivity(intent, bundle)
        true
    } catch (t: Throwable) {
        XLog.d("小窗启动方式失败（mode=$windowingMode）：${t.javaClass.simpleName}")
        false
    }

    /** ColorOS「弹性窗口」私有模式号；AOSP 的 freeform 是 3 */
    private const val COLOROS_FLEXIBLE_MODE = 100
    private const val AOSP_FREEFORM_MODE = 3
    private const val ZOOM_LAUNCH_FLAG = 4
    private const val BUNDLE_WINDOWING_MODE = "android.activity.windowingMode"
    private const val BUNDLE_ZOOM_FLAGS = "android:activity.mZoomLaunchFlags"
}
