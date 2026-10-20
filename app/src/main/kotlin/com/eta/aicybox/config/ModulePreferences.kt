package com.eta.aicybox.config

import android.content.ComponentName

/** App 与 Hook 进程共享的框架配置协议；已发布的名称和类型不可随意复用。 */
internal object ModulePreferences {
    const val GROUP = "module_runtime"
    const val KEY_MODULE_ENABLED = "enabled"
    const val KEY_LEFT_CORNER_ENABLED = "corner_left_enabled"
    const val KEY_RIGHT_CORNER_ENABLED = "corner_right_enabled"
    const val KEY_CORNER_TRIGGER_RANGE_DP = "corner_trigger_range_dp"

    /** @author bomo 触发热区形状（存 [TriggerShape.storedValue]）。 */
    const val KEY_TRIGGER_SHAPE = "trigger_shape_v1"

    /** @author bomo 触发热区横向长度（沿屏幕底边，dp）；矩形 / 三角形使用。 */
    const val KEY_TRIGGER_HORIZONTAL_DP = "trigger_horizontal_dp_v1"

    /** @author bomo 触发热区纵向高度（沿屏幕侧边，dp）；矩形 / 三角形使用。 */
    const val KEY_TRIGGER_VERTICAL_DP = "trigger_vertical_dp_v1"
    const val KEY_CORNER_PINS = "corner_pins_v1"
    const val KEY_OUTSIDE_TAP_CLOSE_MODE = "outside_tap_close_mode_v1"
    const val KEY_HANDLE_SWIPE_UP_TO_MINI_ENABLED = "handle_swipe_up_to_mini_enabled"
    const val KEY_PAUSE_IN_LANDSCAPE = "pause_in_landscape"
    const val KEY_PAUSE_IN_GAME_MODE = "pause_in_game_mode"

    /**
     * @author bomo 开发模式（诊断日志总开关，默认关；用户 2026-10-17 定）。
     *
     * 关闭时 Hook 侧只输出**关键事件**（安装、模块状态、配置应用、错误）；
     * 轮盘/面板几何、图标度量、每次手势与工具调用这类**诊断行一律不出**
     * （它们已改成 `Log.DEBUG` 级，被 `ModuleLogGate` 挡掉）。
     * 打开即恢复全套诊断输出 —— 等于以前「只有 debug 包才看得到」的那批日志，
     * 因此不再需要单独发 debug 包。
     */
    const val KEY_DEVELOPER_MODE = "developer_mode_v1"
    const val DEFAULT_DEVELOPER_MODE = false

    /** @author bomo 气泡功能开关（走 SystemUI 注入，见 hook/SystemUiBubbleMonitor）。 */
    const val KEY_AICY_BUBBLES_ENABLED = "aicy_bubbles_enabled"
    const val DEFAULT_AICY_BUBBLES_ENABLED = false
    const val KEY_FLASHLIGHT_BUBBLE_ENABLED = "flashlight_bubble_enabled"
    const val DEFAULT_FLASHLIGHT_BUBBLE_ENABLED = false
    const val KEY_KEEPAWAKE_BUBBLE_ENABLED = "keepawake_bubble_enabled"
    const val DEFAULT_KEEPAWAKE_BUBBLE_ENABLED = false
    const val KEY_WECHAT_BUBBLE_ENABLED = "wechat_bubble_enabled"
    const val DEFAULT_WECHAT_BUBBLE_ENABLED = false
    const val KEY_QQ_BUBBLE_ENABLED = "qq_bubble_enabled"
    const val DEFAULT_QQ_BUBBLE_ENABLED = false
    const val KEY_APP_INSTALL_BUBBLE_ENABLED = "app_install_bubble_enabled"
    const val DEFAULT_APP_INSTALL_BUBBLE_ENABLED = false
    const val KEY_EARPHONE_BUBBLE_ENABLED = "earphone_bubble_enabled"
    const val DEFAULT_EARPHONE_BUBBLE_ENABLED = false

    /** @author bomo 2026-10-18：耳机气泡可选的自定义音乐应用列表（每行一个包名，参考欧泡 oupao_music_custom）。 */
    const val KEY_EARPHONE_MUSIC_PKGS = "earphone_music_pkgs"
    const val DEFAULT_EARPHONE_MUSIC_PKGS = ""
    const val KEY_LINK_BUBBLE_ENABLED = "link_bubble_enabled"
    const val DEFAULT_LINK_BUBBLE_ENABLED = false
    const val KEY_SHARE_BUBBLE_ENABLED = "share_bubble_enabled"
    const val DEFAULT_SHARE_BUBBLE_ENABLED = false
    const val KEY_BROWSER_BUBBLE_ENABLED = "browser_bubble_enabled"
    const val DEFAULT_BROWSER_BUBBLE_ENABLED = false

    /** @author bomo 链接识别落地浏览器包名（空 = 用系统默认浏览器）。 */
    const val KEY_BROWSER_PKG = "browser_pkg"
    const val DEFAULT_BROWSER_PKG = ""

    /** @author bomo 气泡位置（dp，相对屏幕左下角），参考欧泡 oupao_bub_pos。 */
    const val KEY_BUBBLE_POS_X_DP = "bubble_pos_x_dp"
    const val DEFAULT_BUBBLE_POS_X_DP = 12
    const val KEY_BUBBLE_POS_Y_DP = "bubble_pos_y_dp"
    const val DEFAULT_BUBBLE_POS_Y_DP = 96
    const val MIN_BUBBLE_POS_DP = 0
    const val MAX_BUBBLE_POS_DP = 800

    /** 最近以小窗打开的应用（system_server 写入，侧边栏进程读取后渲染「最近小窗」区块）。 */
    const val KEY_RECENT_FREEFORM = "recent_freeform_v1"

    /** 内圈固定应用（v2 配置，与外圈 KEY_CORNER_PINS 相互独立）。 */
    const val KEY_CORNER_INNER_PINS = "corner_inner_pins_v1"

    /**
     * 侧边栏工具目录（侧边栏进程写入，App 与 system_server 读取）。
     * 工具（小布识屏 / 屏幕翻译 / 截屏等）不是独立应用，无法用 LauncherApps 枚举，
     * 只能由侧边栏进程从原厂 ToolEntryHelper 导出。
     */
    const val KEY_TOOL_CATALOG = "tool_catalog_v1"

    /**
     * @author bomo 「全部」面板整体缩放百分比（80 = 原尺寸的 80%）。
     * 写入方是模块 App（设置界面的滑条），侧边栏进程开面板时按只读方式取值，
     * 因此改完滑条**下次打开面板即生效**，无需重启。
     */
    const val KEY_PANEL_SCALE_PERCENT = "panel_scale_percent_v1"
    const val DEFAULT_PANEL_SCALE_PERCENT = 80
    const val MIN_PANEL_SCALE_PERCENT = 50
    const val MAX_PANEL_SCALE_PERCENT = 100

    /**
     * @author bomo 角落轮盘整体缩放百分比（100 = 基准半径，见 `RadialIconGeometry.BASE_RADIUS_DP`）。
     *
     * 写入方是模块 App（设置界面的滑条）；system_server 在**每次呼出轮盘时**取值，
     * 配合配置推送通道（`ConfigPushReceiver`）改完滑条立即生效，不需要重装或重启。
     * 轮盘尺寸是唯一需要反复试的参数，做成运行时可调可把「改代码→出包→装机→重启」整条链路省掉。
     */
    const val KEY_RADIAL_SCALE_PERCENT = "radial_scale_percent_v1"
    /** @author bomo 默认 105%（2026-10-17 用户定：图标在 105% 封顶，此值即「满尺寸」）。 */
    const val DEFAULT_RADIAL_SCALE_PERCENT = 100
    const val MIN_RADIAL_SCALE_PERCENT = 70
    const val MAX_RADIAL_SCALE_PERCENT = 115

    /**
     * @author bomo 2026-10-19 轮盘震动：切换选中图标时震一下（用户要求，开关 + 强度可调）。
     *
     * 强度是 0~100 的百分比，SystemUI 侧映射到 `VibrationEffect` 的 amplitude（1~255）。
     */
    const val KEY_RADIAL_VIBRATE_ENABLED = "radial_vibrate_enabled_v1"
    const val DEFAULT_RADIAL_VIBRATE_ENABLED = false
    const val KEY_RADIAL_VIBRATE_STRENGTH = "radial_vibrate_strength_v1"
    const val DEFAULT_RADIAL_VIBRATE_STRENGTH = 70
    const val MIN_RADIAL_VIBRATE_STRENGTH = 10
    const val MAX_RADIAL_VIBRATE_STRENGTH = 100

    const val DEFAULT_ENABLED = false
    /** @author bomo 2026-10-20 改回 true：它是**呼出入口**，关掉等于小窗完全用不了
     *  （而且两个键都是 false 时设置页仍显示「常规」，看着像好的，实际没热区）。 */
    const val DEFAULT_CORNER_ENABLED = true
    const val DEFAULT_CORNER_TRIGGER_RANGE_DP = 50

    /**
     * @author bomo 默认「双击小横条」（用户 2026-10-16 实测后改回）。
     * 双击底部小横条是明确指向小窗的动作，不会误伤单击 / 双击空白处这类系统手势；
     * 早前默认「不执行」会让双击小横条看起来像功能失效，故默认恢复为可用状态。
     */
    val DEFAULT_OUTSIDE_TAP_CLOSE_MODE = OutsideTapCloseMode.Disabled
    /** @author bomo 2026-10-21 用户要求默认关：上滑小横条切换迷你小窗默认关闭。 */
    const val DEFAULT_HANDLE_SWIPE_UP_TO_MINI_ENABLED = false
    /** @author bomo 2026-10-19 所有开关默认关闭。 */
    const val DEFAULT_PAUSE_IN_LANDSCAPE = false
    /** @author bomo 2026-10-19 所有开关默认关闭。 */
    const val DEFAULT_PAUSE_IN_GAME_MODE = false
    const val MIN_CORNER_TRIGGER_RANGE_DP = 24

    /** @author bomo 上限 120dp（用户 2026-10-16 定；原为 160）。 */
    const val MAX_CORNER_TRIGGER_RANGE_DP = 120

    /**
     * @author bomo 触发热区横/纵向长度（三角形用）的取值范围（dp）。
     * **与扇形半径共用同一上下限**（用户 2026-09-22 定）：三者在热区里同属"延伸长度"，
     * 各留一套数字只会让口径漂移；上限一致后，三角形拉到最大与扇形最大半径等价。
     */
    const val MIN_TRIGGER_EXTENT_DP = MIN_CORNER_TRIGGER_RANGE_DP
    const val MAX_TRIGGER_EXTENT_DP = MAX_CORNER_TRIGGER_RANGE_DP

    /**
     * 扇形最多固定几个应用（不含「更多」）：外圈最多 6 个 + 内圈最多 5 个 = 11。
     * 固定顺序即圈层顺序：前 6 个在外圈，第 7~11 个进内圈。
     */
    const val MAX_PINNED_APPS = 11

    /** 外圈应用上限。 */
    const val OUTER_PINNED_APPS = 6

    /** 内圈应用上限。 */
    const val MAX_INNER_APPS = 5

    /** 「最近小窗」区块最多展示几个最近应用；超出按时间截断（最近的在最左）。 */
    const val MAX_RECENT_FREEFORM = 8

    /**
     * 工具在固定列表里的伪包名：`ComponentName(TOOL_PACKAGE, alias)`。
     * 复用既有 ComponentName 存储与去重逻辑，不新增一套固定项格式。
     */
    const val TOOL_PACKAGE = "com.eta.aicybox.tool"

    fun isToolComponent(component: ComponentName): Boolean =
        component.packageName == TOOL_PACKAGE && component.className.isNotEmpty()

    /**
     * @author bomo 2026-10-19「聚合支付」条目在固定列表里的伪包名：
     * `ComponentName(PAY_PACKAGE, PAY_ALIAS)`。
     *
     * 与工具伪组件同一套思路（复用 ComponentName 存储 / 去重 / 排序），
     * 但**执行路径完全不同**：工具交给侧边栏进程跑 `AbsTool.handle()`，
     * 支付由 system_server 自己发 Intent 并带 ColorOS 小窗启动参数
     * （见 `PayIntentFactory` 与 `ColorOsFreeformLauncher.launchIntent`）。
     */
    const val PAY_PACKAGE = "com.eta.aicybox.pay"
    const val PAY_ALIAS = "pay"

    fun isPayComponent(component: ComponentName): Boolean =
        component.packageName == PAY_PACKAGE && component.className == PAY_ALIAS

    /**
     * 聚合支付方式排序（逗号分隔的 [PayChannel.storedValue]）。
     * 轮盘点「聚合支付」= 打开排序第一项，并在小窗下方弹出支付栏切换。
     */
    const val KEY_PAY_ORDER = "pay_order_v1"
    const val DEFAULT_PAY_ORDER = "wechat_scan,alipay_scan,wechat_paycode,alipay_paycode"

    fun coerceCornerTriggerRangeDp(value: Int): Int =
        value.coerceIn(MIN_CORNER_TRIGGER_RANGE_DP, MAX_CORNER_TRIGGER_RANGE_DP)

    /** @author bomo 触发热区横/纵向长度钳制（三角形用）。 */
    fun coerceTriggerExtentDp(value: Int): Int =
        value.coerceIn(MIN_TRIGGER_EXTENT_DP, MAX_TRIGGER_EXTENT_DP)

    /** @author bomo 面板缩放百分比钳制；下限防止条目小到点不中，上限即原尺寸。 */
    fun coercePanelScalePercent(value: Int): Int =
        value.coerceIn(MIN_PANEL_SCALE_PERCENT, MAX_PANEL_SCALE_PERCENT)

    fun coerceRadialScalePercent(value: Int): Int =
        value.coerceIn(MIN_RADIAL_SCALE_PERCENT, MAX_RADIAL_SCALE_PERCENT)

    /** @author bomo 轮盘震动强度百分比钳制（下限保证能感觉到，上限即系统最大振幅）。 */
    fun coerceRadialVibrateStrength(value: Int): Int =
        value.coerceIn(MIN_RADIAL_VIBRATE_STRENGTH, MAX_RADIAL_VIBRATE_STRENGTH)
}
