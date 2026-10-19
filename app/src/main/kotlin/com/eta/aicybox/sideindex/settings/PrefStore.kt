package com.eta.aicybox.sideindex.settings

import android.content.Context
import android.content.SharedPreferences
import android.system.Os
import com.eta.aicybox.sideindex.Const
import com.eta.aicybox.sideindex.config.ConfigCodec
import com.eta.aicybox.sideindex.config.ModuleConfig
import com.eta.aicybox.sideindex.config.PrefFiles
import com.eta.aicybox.sideindex.config.PrefKeys
import com.eta.aicybox.sideindex.config.SecureConfigChannel
import com.eta.aicybox.sideindex.config.SharedPrefSource
import com.eta.aicybox.sideindex.util.XLog
import java.io.File

object PrefStore {

    fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(Const.PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 写入并把配置同步到 Settings.Secure（SystemUI 承载靠它取配置）。
     * 注意：先把"当前完整配置"铺进编辑器再应用本次改动 —— 编辑器里若只有被改的那几项，
     * 同步到 Secure 后其他项会退回默认值（卸载重装、应用数据被隔离时尤其明显）。
     */
    fun write(ctx: Context, block: (SharedPreferences.Editor) -> Unit) {
        // 基准必须是"当前生效值"（Secure 优先），否则会把私有镜像里的旧值一起推上去
        val base = currentConfig(ctx)
        val editor = prefs(ctx).edit()
        fillAll(editor, base)
        block(editor)
        editor.commit()
        makeReadable(ctx)
        val after = ModuleConfig.read(SharedPrefSource(prefs(ctx)), "settings")
        // 没有实际改动就别写 Secure：设置页每次打开都会走到这里，
        // 无脑回写会把私有镜像里的旧值覆盖到生效配置上（1.31.4 及以前的实际 bug）
        if (after.copy(source = base.source) == base) return
        SecureConfigChannel.write(ctx, after)
    }

    /**
     * 进设置页时把"生效值"拉进私有 prefs —— **只读，不写 Secure**。
     *
     * 为什么要单独有这个：模块应用没有 READ_SECURE_SETTINGS（能写不能读），
     * 直接 `ModuleConfig.read(prefs)` 拿到的是可能过期的私有镜像。
     * 读不到 Secure 就沿用私有镜像，并留一条日志方便排查。
     */
    fun syncFromChannel(ctx: Context) {
        val truth = SecureConfigChannel.read(ctx)
        if (truth == null) {
            XLog.i("设置页：读不到 Secure 配置，沿用私有镜像（${currentConfig(ctx).summary()}）")
            return
        }
        XLog.i("设置页：已从 Secure 同步生效配置（${truth.summary()}）")
        val editor = prefs(ctx).edit()
        fillAll(editor, truth)
        editor.commit()
        makeReadable(ctx)
    }

    /** 把一个完整配置写进编辑器（键与 ModuleConfig.read 一一对应，勿漏） */
    private fun fillAll(editor: SharedPreferences.Editor, c: ModuleConfig) {
        editor.putBoolean(PrefKeys.ENABLED, c.enabled)
        editor.putBoolean(PrefKeys.BOTH_SIDES, c.bothSides)
        editor.putString(PrefKeys.TRIGGER_SIDE, c.triggerSide)
        editor.putBoolean(PrefKeys.ONLY_LAUNCHER, c.onlyLauncher)
        editor.putBoolean(PrefKeys.ALLOW_SYSTEM_BACK, c.allowSystemBack)
        editor.putInt(PrefKeys.TRIGGER_WIDTH_DP, c.triggerWidthDp)
        editor.putInt(PrefKeys.TRIGGER_POSITION_PERCENT, c.triggerPositionPercent)
        editor.putInt(PrefKeys.SENSITIVITY, c.sensitivity)
        editor.putInt(PrefKeys.BAR_RADIUS_DP, c.barRadiusDp)
        editor.putInt(PrefKeys.BAR_BG_ALPHA, c.barBgAlpha)
        editor.putInt(PrefKeys.BAR_BG_COLOR, c.barBgColor)
        editor.putInt(PrefKeys.BAR_TEXT_COLOR, c.barTextColor)
        editor.putInt(PrefKeys.BAR_HIGHLIGHT_COLOR, c.barHighlightColor)
        editor.putInt(PrefKeys.BAR_FADE_MS, c.barFadeMs)
        editor.putInt(PrefKeys.BAR_HEIGHT_PERCENT, c.barHeightPercent)
        editor.putBoolean(PrefKeys.VIBRATE_ENABLED, c.vibrateEnabled)
        editor.putInt(PrefKeys.VIBRATE_STRENGTH, c.vibrateStrength)
        editor.putBoolean(PrefKeys.VIBRATE_PER_GROUP, c.vibratePerGroup)
        editor.putBoolean(PrefKeys.FREEFORM_LAUNCH, c.freeformLaunch)
        editor.putInt(PrefKeys.ICON_SIZE_DP, c.iconSizeDp)
        editor.putInt(PrefKeys.GRID_COLUMNS, c.gridColumns)
        editor.putBoolean(PrefKeys.IMMERSIVE, c.immersive)
        editor.putBoolean(PrefKeys.GAME_BLOCK, c.gameBlock)
        editor.putString(PrefKeys.BLACKLIST, c.blacklist)
        editor.putBoolean(PrefKeys.LOG_VERBOSE, c.logVerbose)
        editor.putInt(PrefKeys.RELOAD_INTERVAL_MS, c.reloadIntervalMs)
    }

    /** 让 Hook 进程也能读到（配合 LSPosed 的 xposedsharedprefs） */
    fun makeReadable(ctx: Context) {
        val dataDir = ctx.dataDir
        val dir = File(dataDir, "shared_prefs")
        try {
            Os.chmod(dataDir.absolutePath, 0b111_001_001)
            if (dir.isDirectory) Os.chmod(dir.absolutePath, 0b111_001_001)
            val file = File(dir, "${Const.PREFS_NAME}.xml")
            if (file.isFile) Os.chmod(file.absolutePath, 0b110_100_100)
        } catch (t: Throwable) {
            XLog.d("调整配置权限失败：$t")
        }
    }

    /** 首次打开把默认值落盘 */
    fun ensureInitialized(ctx: Context) {
        val existing = prefs(ctx)
        XLog.i("配置初始化检查：私有项=${existing.all.size}")
        if (existing.all.isNotEmpty()) {
            makeReadable(ctx)
            ensureSecureChannel(ctx)
            return
        }
        // 私有 SP 为空 = 数据被清除过（或首次安装）。Settings.Secure 里若还留着旧配置，
        // 先删掉再初始化 —— 否则 currentConfig 会把旧值铺回来，"清除数据"就不等于恢复默认
        try {
            val cleared = SecureConfigChannel.clear(ctx)
            XLog.i("私有配置为空 → 视为数据被清除，重置 Secure 通道：$cleared")
        } catch (t: Throwable) {
        }
        val d = ModuleConfig()
        write(ctx) { e ->
            e.putBoolean(PrefKeys.ENABLED, d.enabled)
            e.putBoolean(PrefKeys.ONLY_LAUNCHER, d.onlyLauncher)
            e.putBoolean(PrefKeys.ALLOW_SYSTEM_BACK, d.allowSystemBack)
            e.putInt(PrefKeys.TRIGGER_WIDTH_DP, d.triggerWidthDp)
            e.putInt(PrefKeys.TRIGGER_POSITION_PERCENT, d.triggerPositionPercent)
            e.putInt(PrefKeys.SENSITIVITY, d.sensitivity)
            e.putInt(PrefKeys.BAR_RADIUS_DP, d.barRadiusDp)
            e.putInt(PrefKeys.BAR_BG_ALPHA, d.barBgAlpha)
            e.putInt(PrefKeys.BAR_HEIGHT_PERCENT, d.barHeightPercent)
            e.putBoolean(PrefKeys.VIBRATE_ENABLED, d.vibrateEnabled)
            e.putInt(PrefKeys.VIBRATE_STRENGTH, d.vibrateStrength)
            e.putBoolean(PrefKeys.VIBRATE_PER_GROUP, d.vibratePerGroup)
            e.putBoolean(PrefKeys.FREEFORM_LAUNCH, d.freeformLaunch)
            e.putInt(PrefKeys.ICON_SIZE_DP, d.iconSizeDp)
            e.putInt(PrefKeys.GRID_COLUMNS, d.gridColumns)
            e.putBoolean(PrefKeys.LOG_VERBOSE, d.logVerbose)
        }
        // write() 里「没有实际改动就不写 Secure」的保护让这条路径也写不进 Secure（base 就是刚铺的默认值）
        ensureSecureChannel(ctx)
    }

    /**
     * 确保 Settings.Secure 里有配置（1.32.2）。
     *
     * Secure 是 SystemUI 侧唯一「不依赖模块 App 进程」的通道：它空着的时候，重启后模块
     * 只能去问 ContentProvider（App 进程没起来就失败），表现就是「必须手动点开一次
     * 模块设置页才有索引」。而 write() 有「没有实际改动就不写 Secure」的保护 ——
     * 从没改过设置的用户永远等不到那一次写入，所以进设置页时补一次。
     */
    private fun ensureSecureChannel(ctx: Context) {
        if (SecureConfigChannel.read(ctx) != null) return
        val config = ModuleConfig.read(SharedPrefSource(prefs(ctx)), "settings")
        if (SecureConfigChannel.write(ctx, config)) {
            XLog.i("配置通道：Secure 为空，已补写当前配置（${config.summary()}）")
        }
    }

    /**
     * 当前配置：Settings.Secure 是真源（SystemUI 侧只读它；卸载重装、应用数据被隔离都不影响它），

     * 读不到才回退到应用私有 SharedPreferences。这样设置页显示的就是实际生效的值。
     */
    fun currentConfig(ctx: Context): ModuleConfig =
        SecureConfigChannel.read(ctx)
            ?: ModuleConfig.read(SharedPrefSource(prefs(ctx)), "settings")

    /** 名单读写（横屏白名单 / 黑名单共用同一套逻辑，键由调用方传入） */
    fun listFor(ctx: Context, key: String): List<String> =
        prefs(ctx).getString(key, "").orEmpty()
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()

    fun containsIn(ctx: Context, key: String, packageName: String): Boolean =
        listFor(ctx, key).contains(packageName)

    /** 勾选 / 取消勾选，返回新的选中状态 */
    fun toggleIn(ctx: Context, key: String, packageName: String): Boolean {
        val list = listFor(ctx, key).toMutableList()
        val added = if (list.contains(packageName)) {
            list.remove(packageName); false
        } else {
            list.add(packageName); true
        }
        saveList(ctx, key, list)
        return added
    }

    fun saveList(ctx: Context, key: String, list: List<String>) {
        write(ctx) { it.putString(key, list.distinct().sorted().joinToString(",")) }
    }

    /** 导出当前配置（与 Settings.Secure 里的格式一致，恢复时直接 decode） */
    fun exportPayload(ctx: Context): String = ConfigCodec.encode(currentConfig(ctx))

    /** 用一个完整配置覆盖（恢复备份）：私有 SP 与 Settings.Secure 一起写 */
    fun applyConfig(ctx: Context, c: ModuleConfig) {
        val editor = prefs(ctx).edit()
        fillAll(editor, c)
        editor.commit()
        makeReadable(ctx)
        SecureConfigChannel.write(ctx, ModuleConfig.read(SharedPrefSource(prefs(ctx)), "settings"))
    }

    /**
     * 清理残留：卸载模块后 Settings.Secure 里的键不会自己消失，重装时会读到旧值。
     * 连私有 SP 一起清掉，让「清理残留」回到真正的初始状态。
     */
    fun clearResidue(ctx: Context): List<String> {
        val cleared = mutableListOf<String>()
        for (key in RESIDUE_SECURE_KEYS) {
            try {
                if (android.provider.Settings.Secure.getString(ctx.contentResolver, key) != null) {
                    if (key == SecureConfigChannel.KEY) SecureConfigChannel.clear(ctx) else android.provider.Settings.Secure.putString(ctx.contentResolver, key, null)
                    cleared.add(key)
                }
            } catch (t: Throwable) {
                XLog.d("清理 $key 失败：$t")
            }
        }
        try {
            prefs(ctx).edit().clear().commit()
        } catch (t: Throwable) {
        }
        return cleared
    }

    /** 卸载后需要清掉的系统设置键（模块配置 + 兜底游戏表） */
    private val RESIDUE_SECURE_KEYS = listOf(SecureConfigChannel.KEY, "eta_game_list")

    /**
     * 数据是否被清除过（或首次安装）：在私有目录放一个标记文件。
     * pm clear 会连 /data/data 一起删掉，标记消失就说明"刚被清过"。
     * 注意：ColorOS 把 SharedPreferences 隔离到 /data/misc/apexdata/... 下，
     * pm clear 清不掉它，所以**不能**靠"SP 为空"来判断（踩过）。
     */
    fun isFreshInstall(ctx: Context): Boolean = !markerFile(ctx).exists()

    fun markInstalled(ctx: Context) {
        try {
            val f = markerFile(ctx)
            f.parentFile?.mkdirs()
            f.writeText(System.currentTimeMillis().toString())
        } catch (t: Throwable) {
            XLog.d("写安装标记失败：$t")
        }
    }

    private fun markerFile(ctx: Context): File = File(ctx.filesDir, "install_marker")

    fun prefsInfo(ctx: Context): String {
        val config = currentConfig(ctx)
        return PrefFiles.describe() +
            "\n应用范围：竖屏全局生效；横屏仅屏蔽游戏" +
            "\n配置通道：" + ConfigChannelState.describe(ctx)
    }

    /** 配置通道自检：Secure 通道是否真的写进去了 */
    object ConfigChannelState {
        fun describe(ctx: Context): String = try {
            val payload = android.provider.Settings.Secure.getString(
                ctx.contentResolver, SecureConfigChannel.KEY
            )
            if (payload.isNullOrEmpty()) {
                "未写入（打开一次本页面即可写入）"
            } else {
                "Settings.Secure 已写入 ${payload.length} 字节"
            }
        } catch (t: Throwable) {
            "读取失败：${t.javaClass.simpleName}"
        }
    }
}
