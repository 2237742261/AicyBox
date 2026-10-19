package com.eta.aicybox.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.SwipeRight
import androidx.compose.material.icons.rounded.SwipeUp
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.eta.aicybox.R
import com.eta.aicybox.config.CornerCallMode
import com.eta.aicybox.config.ModulePreferences
import kotlinx.coroutines.launch
import com.eta.aicybox.config.OutsideTapCloseMode
import com.eta.aicybox.config.PayChannel
import com.eta.aicybox.config.TriggerShape
import com.eta.aicybox.framework.FrameworkConnectionIssue
import com.eta.aicybox.framework.FrameworkConnectionState
import com.eta.aicybox.framework.FrameworkConnectionStatus
import com.eta.aicybox.window.RadialIconGeometry
import com.eta.aicybox.ui.component.TopBarBackdrop
import com.eta.aicybox.ui.component.captureForTopBar
import com.eta.aicybox.ui.component.rememberTopBarBackdrop
import com.eta.aicybox.ui.component.topBarContainerColor
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import kotlin.math.roundToInt

@Composable
internal fun ControlScreen(
    state: FrameworkConnectionState,
    onModuleEnabledChange: (Boolean) -> Unit,
    /** @author bomo 轮盘呼出方式（常规 / 仅左下 / 仅右下）变更。 */
    onCornerCallModeChange: (CornerCallMode) -> Unit,
    onCornerTriggerRangeChange: (Int) -> Unit,
    /** @author bomo 触发热区形状（扇形 / 三角形）变更。 */
    onTriggerShapeChange: (TriggerShape) -> Unit,
    /** @author bomo 三角形热区横向长度变更（沿屏幕底边）。 */
    onTriggerHorizontalDpChange: (Int) -> Unit,
    /** @author bomo 三角形热区纵向高度变更（沿屏幕侧边）。 */
    onTriggerVerticalDpChange: (Int) -> Unit,
    /** @author bomo 「全部」面板缩放百分比变更（设置界面滑条，热生效）。 */
    onPanelScaleChange: (Int) -> Unit,
    /** @author bomo 角落轮盘缩放百分比变更（设置界面滑条；呼出轮盘时读取，热生效）。 */
    onRadialScaleChange: (Int) -> Unit,
    /** @author bomo 2026-10-19 轮盘切换图标震动开关。 */
    onRadialVibrateEnabledChange: (Boolean) -> Unit,
    /** @author bomo 2026-10-19 轮盘震动强度（10~100）。 */
    onRadialVibrateStrengthChange: (Int) -> Unit,
    onOutsideTapCloseModeChange: (OutsideTapCloseMode) -> Unit,
    onHandleSwipeUpToMiniEnabledChange: (Boolean) -> Unit,
    /** @author bomo 2026-10-19 聚合支付方式排序入口（进下一页拖动排序）。 */
    onNavigateToPayOrder: () -> Unit,
    onPauseInLandscapeChange: (Boolean) -> Unit,
    onPauseInGameModeChange: (Boolean) -> Unit,
    onNavigateToPinnedApps: () -> Unit,
    /**
     * @author bomo 2026-10-19 预览浮层交给 NavHost 画在**整窗**最上层。
     *
     * 原来直接画在本页的 `BoxWithConstraints(fillMaxSize)` 里，而这个 Box 已经被底部 tab 栏
     * 抬高了一截（NavHost 的 Scaffold 把 bottomBar 的高度算进 innerPadding），
     * 所以角标预览看着离屏幕底边远了、像被 tab 顶上去。
     */
    onOverlayPreviewChange: (ScreenOverlayPreview?) -> Unit,
) {
    val backdrop = rememberTopBarBackdrop()
    val topBarColor = topBarContainerColor(backdrop)
    val scrollBehavior = MiuixScrollBehavior()
    // @author bomo 拖动任一条触发热区滑条时，用「其余项的已确认值 + 正在拖动的草稿值」
    // 拼出预览快照；松手即置空收回预览。三条滑条共用同一个预览通道。
    var cornerRangePreview by remember { mutableStateOf<TriggerRangePreview?>(null) }
    // @author bomo 拖动「轮盘缩放」滑条时用草稿百分比画出轮盘将占用的范围（松手收回）。
    var radialScalePreview by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(state.canChangeSettings) {
        if (!state.canChangeSettings) {
            cornerRangePreview = null
            radialScalePreview = null
        }
    }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val windowWidth = maxWidth
        val isWideScreen = windowWidth >= WideWindowMinWidth
        Scaffold(
            contentWindowInsets =
                WindowInsets.systemBars
                    .union(WindowInsets.displayCutout)
                    .union(WindowInsets.ime),
            topBar = {
                TopBarBackdrop(backdrop) {
                    if (isWideScreen) {
                        SmallTopAppBar(
                            title = stringResource(R.string.screen_settings_title),
                            color = topBarColor,
                            scrollBehavior = scrollBehavior,
                        )
                    } else {
                        TopAppBar(
                            title = stringResource(R.string.screen_settings_title),
                            color = topBarColor,
                            scrollBehavior = scrollBehavior,
                        )
                    }
                }
            },
        ) { innerPadding ->
            val layoutDirection = LocalLayoutDirection.current
            val safeStart = innerPadding.calculateStartPadding(layoutDirection)
            val safeEnd = innerPadding.calculateEndPadding(layoutDirection)
            val safeWidth = (windowWidth - safeStart - safeEnd).coerceAtLeast(0.dp)
            val centeredSide = maxOf(ScreenHorizontalMargin, (safeWidth - ScreenContentMaxWidth) / 2)
            Box(modifier = Modifier.fillMaxSize().captureForTopBar(backdrop)) {
                LazyColumn(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .consumeWindowInsets(innerPadding)
                            .scrollEndHaptic()
                            .overScrollVertical()
                            .nestedScroll(scrollBehavior.nestedScrollConnection),
                    verticalArrangement = Arrangement.spacedBy(CardSpacing),
                    contentPadding =
                        PaddingValues(
                            start = safeStart + centeredSide,
                            top = innerPadding.calculateTopPadding() + ScreenTopSpacing,
                            end = safeEnd + centeredSide,
                            bottom = innerPadding.calculateBottomPadding() + ScreenBottomSpacing,
                        ),
                    overscrollEffect = null,
                ) {
                    // @author bomo 2026-10-19 用户要求：小窗的总开关像「建议」页的 master 开关一样独立成一张卡，
                    // 不再和「轮盘呼出方式／触发热区」挤在同一张卡里。
                    item(key = "master") {
                        ModuleMasterCard(
                            state = state,
                            onModuleEnabledChange = onModuleEnabledChange,
                        )
                    }
                    item(key = "settings") {
                        SettingsCard(
                            state,
                            onCornerCallModeChange,
                            onCornerTriggerRangeChange,
                            onTriggerShapeChange = onTriggerShapeChange,
                            onTriggerHorizontalDpChange = onTriggerHorizontalDpChange,
                            onTriggerVerticalDpChange = onTriggerVerticalDpChange,
                            onCornerRangePreviewChange = { cornerRangePreview = it },
                            onPanelScaleChange = onPanelScaleChange,
                            onRadialScaleChange = onRadialScaleChange,
                            onRadialScalePreviewChange = { radialScalePreview = it },
                            onRadialVibrateEnabledChange = onRadialVibrateEnabledChange,
                            onRadialVibrateStrengthChange = onRadialVibrateStrengthChange,
                            onNavigateToPinnedApps,
                        )
                    }
                    item(key = "window_interaction") {
                        WindowInteractionCard(
                            state = state,
                            onOutsideTapCloseModeChange = onOutsideTapCloseModeChange,
                            onHandleSwipeUpToMiniEnabledChange =
                                onHandleSwipeUpToMiniEnabledChange,
                        )
                    }
                    item(key = "payment") {
                        PaymentCard(
                            state = state,
                            onNavigateToPayOrder = onNavigateToPayOrder,
                        )
                    }
                    item(key = "environment") {
                        EnvironmentCard(
                            state,
                            onPauseInLandscapeChange,
                            onPauseInGameModeChange,
                        )
                    }
                }
            }
        }
    }
    LaunchedEffect(cornerRangePreview, radialScalePreview) {
        val range = cornerRangePreview
        val radial = radialScalePreview
        onOverlayPreviewChange(
            when {
                range != null ->
                    ScreenOverlayPreview.CornerRange(
                        preview = range,
                        leftEnabled = state.settings.leftCornerEnabled,
                        rightEnabled = state.settings.rightCornerEnabled,
                    )
                radial != null ->
                    ScreenOverlayPreview.RadialScale(
                        percent = radial,
                        leftEnabled = state.settings.leftCornerEnabled,
                        rightEnabled = state.settings.rightCornerEnabled,
                    )
                else -> null
            },
        )
    }
}

@Composable
private fun EnvironmentCard(
    state: FrameworkConnectionState,
    onPauseInLandscapeChange: (Boolean) -> Unit,
    onPauseInGameModeChange: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        SwitchPreference(
            checked = state.settings.pauseInLandscape,
            onCheckedChange = onPauseInLandscapeChange,
            title = stringResource(R.string.pause_in_landscape_title),
            summary = stringResource(R.string.pause_in_landscape_summary),
            enabled = state.canChangeSettings,
            startAction = { PreferenceIcon(Icons.Rounded.ScreenRotation, state.canChangeSettings) },
        )
        SwitchPreference(
            checked = state.settings.pauseInGameMode,
            onCheckedChange = onPauseInGameModeChange,
            title = stringResource(R.string.pause_in_game_mode_title),
            summary = stringResource(R.string.pause_in_game_mode_summary),
            enabled = state.canChangeSettings,
            startAction = { PreferenceIcon(Icons.Rounded.SportsEsports, state.canChangeSettings) },
        )
            }
}

/**
 * @author bomo 2026-10-19 聚合支付卡片。
 *
 * 轮盘里只有**一个**「聚合支付」槽：点它打开排序第一项，并在小窗下方弹出支付栏切换。
 * 这里只放一个入口，去下一页调排序（顺序同时决定「默认打开哪一项」和「支付栏的排列」）。
 */
@Composable
private fun PaymentCard(
    state: FrameworkConnectionState,
    onNavigateToPayOrder: () -> Unit,
) {
    val order = PayChannel.parseOrder(state.settings.payOrder)
    Card(modifier = Modifier.fillMaxWidth()) {
        ArrowPreference(
            title = stringResource(R.string.pay_order_title),
            summary = order.joinToString(" · ") { channel -> channel.displayLabel },
            onClick = onNavigateToPayOrder,
            enabled = state.canChangeSettings,
            startAction = { PreferenceIcon(Icons.Rounded.Payments, state.canChangeSettings) },
        )
    }
}

@Composable
private fun WindowInteractionCard(
    state: FrameworkConnectionState,
    onOutsideTapCloseModeChange: (OutsideTapCloseMode) -> Unit,
    onHandleSwipeUpToMiniEnabledChange: (Boolean) -> Unit,
) {
    val modes = OutsideTapCloseMode.entries
    val items =
        listOf(
            DropdownItem(text = stringResource(R.string.outside_tap_mode_disabled)),
            DropdownItem(text = stringResource(R.string.outside_tap_mode_handle_double_tap)),
            DropdownItem(text = stringResource(R.string.outside_tap_mode_single)),
            DropdownItem(text = stringResource(R.string.outside_tap_mode_double)),
        )
    Card(modifier = Modifier.fillMaxWidth()) {
        OverlaySpinnerPreference(
            items = items,
            selectedIndex = modes.indexOf(state.settings.outsideTapCloseMode),
            title = stringResource(R.string.outside_tap_close_title),
            summary = stringResource(R.string.outside_tap_close_summary),
            enabled = state.canChangeSettings,
            startAction = { PreferenceIcon(Icons.Rounded.TouchApp, state.canChangeSettings) },
            onSelectedIndexChange = { index ->
                modes.getOrNull(index)?.let(onOutsideTapCloseModeChange)
            },
        )
        SwitchPreference(
            checked = state.settings.handleSwipeUpToMiniEnabled,
            onCheckedChange = onHandleSwipeUpToMiniEnabledChange,
            title = stringResource(R.string.handle_swipe_up_to_mini_title),
            summary = stringResource(R.string.handle_swipe_up_to_mini_summary),
            enabled = state.canChangeSettings,
            startAction = { PreferenceIcon(Icons.Rounded.SwipeUp, state.canChangeSettings) },
        )
    }
}

/**
 * @author bomo 2026-10-19 小窗模块总开关（独立成卡，与「建议」页的 Aicy 建议总闸同款排版）。
 *
 * 只放一个开关：模块本身是否生效。关掉时下方所有小窗设置项统一置灰。
 */
@Composable
private fun ModuleMasterCard(
    state: FrameworkConnectionState,
    onModuleEnabledChange: (Boolean) -> Unit,
) {
    val moduleSummary =
        when {
            !state.canChangeSettings && state.status != FrameworkConnectionStatus.Connected ->
                stringResource(R.string.module_enabled_summary_waiting)
            state.missingScopes.isNotEmpty() -> stringResource(R.string.module_enabled_summary_scope)
            state.settings.enabled -> stringResource(R.string.module_enabled_summary_on)
            else -> stringResource(R.string.module_enabled_summary_off)
        }
    Card(modifier = Modifier.fillMaxWidth()) {
        SwitchPreference(
            checked = state.settings.enabled,
            onCheckedChange = onModuleEnabledChange,
            title = stringResource(R.string.module_enabled_title),
            summary = moduleSummary,
            enabled = state.canChangeSettings,
            startAction = {
                PreferenceIcon(Icons.Rounded.PowerSettingsNew, state.canChangeSettings)
            },
        )
    }
}

@Composable
private fun SettingsCard(
    state: FrameworkConnectionState,
    /** @author bomo 轮盘呼出方式（常规 / 仅左下 / 仅右下）变更。 */
    onCornerCallModeChange: (CornerCallMode) -> Unit,
    onCornerTriggerRangeChange: (Int) -> Unit,
    /** @author bomo 触发热区形状变更。 */
    onTriggerShapeChange: (TriggerShape) -> Unit,
    /** @author bomo 三角形热区横向长度变更。 */
    onTriggerHorizontalDpChange: (Int) -> Unit,
    /** @author bomo 三角形热区纵向高度变更。 */
    onTriggerVerticalDpChange: (Int) -> Unit,
    onCornerRangePreviewChange: (TriggerRangePreview?) -> Unit,
    /** @author bomo 「全部」面板缩放百分比变更。 */
    onPanelScaleChange: (Int) -> Unit,
    /** @author bomo 角落轮盘缩放百分比变更（热生效）。 */
    onRadialScaleChange: (Int) -> Unit,
    /** @author bomo 拖动「轮盘缩放」滑条时的草稿百分比（null = 松手，收回预览）。 */
    onRadialScalePreviewChange: (Int?) -> Unit,
    /** @author bomo 2026-10-19 轮盘切换图标震动开关。 */
    onRadialVibrateEnabledChange: (Boolean) -> Unit,
    /** @author bomo 2026-10-19 轮盘震动强度。 */
    onRadialVibrateStrengthChange: (Int) -> Unit,
    onNavigateToPinnedApps: () -> Unit,
) {
    val appsSummary =
        when {
            !state.settings.pinsSaved -> stringResource(R.string.radial_apps_recent_summary)
            state.settings.pinnedComponents.isEmpty() -> stringResource(R.string.radial_apps_empty_summary)
            else -> stringResource(R.string.radial_apps_count_summary, state.settings.pinnedComponents.size)
        }
    // @author bomo 模块总开关关掉时，其余小窗项一并置灰（用户 2026-10-17 要求，与建议页一致）。
    val settingsEnabled = state.canChangeSettings && state.settings.enabled
    Card(modifier = Modifier.fillMaxWidth()) {
        // @author bomo 2026-10-17 用户要求：「左下角手势」与「右下角手势」合并成一个下拉选择。
        CornerCallModePreference(
            mode =
                CornerCallMode.resolve(
                    leftEnabled = state.settings.leftCornerEnabled,
                    rightEnabled = state.settings.rightCornerEnabled,
                ),
            enabled = settingsEnabled,
            onModeChange = onCornerCallModeChange,
        )
        TriggerShapePreference(
            shape = state.settings.triggerShape,
            enabled = settingsEnabled,
            onShapeChange = onTriggerShapeChange,
        )
        // @author bomo 只显示当前形状用得上的滑条（用户 2026-09-22 要求）：
        // 扇形 → 半径；三角形 → 横向 + 纵向。不做"都画出来再置灰"，
        // 避免用户以为灰色滑条调了会生效。
        if (state.settings.triggerShape == TriggerShape.Triangle) {
            RemoteDpSliderPreference(
                icon = Icons.Rounded.SwapHoriz,
                confirmedValue = state.settings.triggerHorizontalDp,
                isUpdating = state.isUpdating,
                enabled = settingsEnabled,
                title = stringResource(R.string.trigger_horizontal_title),
                summary = stringResource(R.string.trigger_horizontal_summary),
                onPreviewChange = { dp ->
                    onCornerRangePreviewChange(
                        dp?.let {
                            TriggerRangePreview(
                                shape = TriggerShape.Triangle,
                                rangeDp = state.settings.cornerTriggerRangeDp,
                                horizontalDp = it,
                                verticalDp = state.settings.triggerVerticalDp,
                            )
                        },
                    )
                },
                onCommit = onTriggerHorizontalDpChange,
                coerce = { ModulePreferences.coerceTriggerExtentDp(it) },
                minDp = ModulePreferences.MIN_TRIGGER_EXTENT_DP,
                maxDp = ModulePreferences.MAX_TRIGGER_EXTENT_DP,
            )
            RemoteDpSliderPreference(
                icon = Icons.Rounded.SwapVert,
                confirmedValue = state.settings.triggerVerticalDp,
                isUpdating = state.isUpdating,
                enabled = settingsEnabled,
                title = stringResource(R.string.trigger_vertical_title),
                summary = stringResource(R.string.trigger_vertical_summary),
                onPreviewChange = { dp ->
                    onCornerRangePreviewChange(
                        dp?.let {
                            TriggerRangePreview(
                                shape = TriggerShape.Triangle,
                                rangeDp = state.settings.cornerTriggerRangeDp,
                                horizontalDp = state.settings.triggerHorizontalDp,
                                verticalDp = it,
                            )
                        },
                    )
                },
                onCommit = onTriggerVerticalDpChange,
                coerce = { ModulePreferences.coerceTriggerExtentDp(it) },
                minDp = ModulePreferences.MIN_TRIGGER_EXTENT_DP,
                maxDp = ModulePreferences.MAX_TRIGGER_EXTENT_DP,
            )
        } else {
            RemoteDpSliderPreference(
                icon = Icons.Rounded.Straighten,
                confirmedValue = state.settings.cornerTriggerRangeDp,
                isUpdating = state.isUpdating,
                enabled = settingsEnabled,
                title = stringResource(R.string.corner_trigger_range_title),
                summary = stringResource(R.string.corner_trigger_range_summary),
                onPreviewChange = { dp ->
                    onCornerRangePreviewChange(
                        dp?.let {
                            TriggerRangePreview(
                                shape = TriggerShape.Sector,
                                rangeDp = it,
                                horizontalDp = state.settings.triggerHorizontalDp,
                                verticalDp = state.settings.triggerVerticalDp,
                            )
                        },
                    )
                },
                onCommit = onCornerTriggerRangeChange,
            )
        }
        // @author bomo 面板缩放滑条：写入远端配置后，侧边栏进程在**下次打开面板**时读取，
        // 因此调整后无需重启/重装即可看到效果。
        RemotePercentSliderPreference(
            icon = Icons.Rounded.Dashboard,
            confirmedValue = state.settings.panelScalePercent,
            isUpdating = state.isUpdating,
            enabled = settingsEnabled,
            title = stringResource(R.string.panel_scale_title),
            summary = stringResource(R.string.panel_scale_summary),
            onCommit = onPanelScaleChange,
        )
        // @author bomo 轮盘缩放滑条：system_server 每次呼出轮盘时读这份配置，
        // 配置推送通道（ConfigPushReceiver）通了以后滑完立即生效，不需要重装或重启。
        RemotePercentSliderPreference(
            icon = Icons.Rounded.Straighten,
            confirmedValue = state.settings.radialScalePercent,
            isUpdating = state.isUpdating,
            enabled = settingsEnabled,
            title = stringResource(R.string.radial_scale_title),
            summary = stringResource(R.string.radial_scale_summary),
            onCommit = onRadialScaleChange,
            onPreviewChange = onRadialScalePreviewChange,
            minPercent = ModulePreferences.MIN_RADIAL_SCALE_PERCENT,
            maxPercent = ModulePreferences.MAX_RADIAL_SCALE_PERCENT,
            coerce = ModulePreferences::coerceRadialScalePercent,
        )
        // @author bomo 2026-10-19 轮盘切换图标时的震动反馈（用户要求：开关 + 可调强度）。
        SwitchPreference(
            checked = state.settings.radialVibrateEnabled,
            onCheckedChange = onRadialVibrateEnabledChange,
            title = stringResource(R.string.radial_vibrate_title),
            summary = stringResource(R.string.radial_vibrate_summary),
            enabled = settingsEnabled,
            startAction = { PreferenceIcon(Icons.Rounded.Vibration, settingsEnabled) },
        )
        if (state.settings.radialVibrateEnabled) {
            RemotePercentSliderPreference(
                icon = Icons.Rounded.Tune,
                confirmedValue = state.settings.radialVibrateStrength,
                isUpdating = state.isUpdating,
                enabled = settingsEnabled,
                title = stringResource(R.string.radial_vibrate_strength_title),
                summary = stringResource(R.string.radial_vibrate_strength_summary),
                onCommit = onRadialVibrateStrengthChange,
                minPercent = ModulePreferences.MIN_RADIAL_VIBRATE_STRENGTH,
                maxPercent = ModulePreferences.MAX_RADIAL_VIBRATE_STRENGTH,
                coerce = ModulePreferences::coerceRadialVibrateStrength,
            )
        }
        ArrowPreference(
            title = stringResource(R.string.radial_apps_title),
            summary = appsSummary,
            onClick = onNavigateToPinnedApps,
            enabled = settingsEnabled,
            startAction = { PreferenceIcon(Icons.Rounded.Apps, state.canChangeSettings) },
        )
    }
}

/**
 * @author bomo 轮盘呼出方式选择器（2026-10-17 用户要求）：
 * 把原来的两个开关合并成一个下拉，**样式与「触发热区形状」一致**
 * （`OverlaySpinnerPreference` + 右侧箭头）。选项顺序即 [CornerCallMode.entries]：
 * 常规（默认）/ 左下角呼出 / 右下角呼出。
 */
@Composable
private fun CornerCallModePreference(
    mode: CornerCallMode,
    enabled: Boolean,
    onModeChange: (CornerCallMode) -> Unit,
) {
    val modes = CornerCallMode.entries
    val items =
        listOf(
            DropdownItem(text = stringResource(R.string.corner_call_mode_both)),
            DropdownItem(text = stringResource(R.string.corner_call_mode_left)),
            DropdownItem(text = stringResource(R.string.corner_call_mode_right)),
        )
    OverlaySpinnerPreference(
        items = items,
        selectedIndex = modes.indexOf(mode).coerceAtLeast(0),
        title = stringResource(R.string.corner_call_mode_title),
        summary = stringResource(R.string.corner_call_mode_summary),
        enabled = enabled,
        startAction = { PreferenceIcon(Icons.Rounded.SwipeRight, enabled) },
        onSelectedIndexChange = { index -> modes.getOrNull(index)?.let(onModeChange) },
    )
}

/**
 * @author bomo 触发热区形状选择器；条目顺序即 [TriggerShape.entries] 顺序。
 */
@Composable
private fun TriggerShapePreference(
    shape: TriggerShape,
    enabled: Boolean,
    onShapeChange: (TriggerShape) -> Unit,
) {
    val shapes = TriggerShape.entries
    val items =
        listOf(
            DropdownItem(text = stringResource(R.string.trigger_shape_sector)),
            DropdownItem(text = stringResource(R.string.trigger_shape_triangle)),
        )
    OverlaySpinnerPreference(
        items = items,
        selectedIndex = shapes.indexOf(shape),
        title = stringResource(R.string.trigger_shape_title),
        summary = stringResource(R.string.trigger_shape_summary),
        enabled = enabled,
        startAction = { PreferenceIcon(Icons.Rounded.Category, enabled) },
        onSelectedIndexChange = { index -> shapes.getOrNull(index)?.let(onShapeChange) },
    )
}

/**
 * @author bomo dp 数值滑条。默认按「角落触发范围」（扇形半径）钳制与取值；
 * 三角形热区的横向 / 纵向长度通过 [coerce] 与 [minDp] / [maxDp] 覆盖为同一量纲的另一组范围。
 */
@Composable
private fun RemoteDpSliderPreference(
    icon: ImageVector,
    confirmedValue: Int,
    isUpdating: Boolean,
    enabled: Boolean,
    title: String,
    summary: String,
    onPreviewChange: (Int?) -> Unit,
    onCommit: (Int) -> Unit,
    coerce: (Int) -> Int = { ModulePreferences.coerceCornerTriggerRangeDp(it) },
    minDp: Int = ModulePreferences.MIN_CORNER_TRIGGER_RANGE_DP,
    maxDp: Int = ModulePreferences.MAX_CORNER_TRIGGER_RANGE_DP,
) {
    var draftValue by rememberSaveable { mutableFloatStateOf(confirmedValue.toFloat()) }
    var isDragging by remember { mutableStateOf(false) }
    LaunchedEffect(confirmedValue, isUpdating, enabled) {
        if (!enabled) {
            isDragging = false
            onPreviewChange(null)
        }
        if (!isDragging && !isUpdating) draftValue = confirmedValue.toFloat()
    }
    SliderPreference(
        value = draftValue,
        onValueChange = { value ->
            isDragging = true
            val draft = coerce(value.roundToInt())
            draftValue = draft.toFloat()
            onPreviewChange(draft)
        },
        title = title,
        summary = summary,
        valueText = stringResource(R.string.dp_value, draftValue.roundToInt()),
        enabled = enabled,
        startAction = { PreferenceIcon(icon, enabled) },
        valueRange = minDp.toFloat()..maxDp.toFloat(),
        steps = (maxDp - minDp - 1).coerceAtLeast(0),
        onValueChangeFinished = {
            isDragging = false
            onPreviewChange(null)
            val committed = coerce(draftValue.roundToInt())
            draftValue = committed.toFloat()
            if (committed != confirmedValue) onCommit(committed)
        },
    )
}

/**
 * @author bomo 百分比滑条（「全部」面板缩放 / 角落轮盘缩放共用）。
 *
 * 与 [RemoteDpSliderPreference] 的差异只有钳制函数与文案；刻意独立实现而不改写既有 dp 滑条，
 * 以免影响已发布行为。范围与钳制由调用方传入（[minPercent] / [maxPercent] / [coerce]），
 * 默认仍是面板缩放那一套，老调用点无需改动。
 */
@Composable
private fun RemotePercentSliderPreference(
    icon: ImageVector,
    confirmedValue: Int,
    isUpdating: Boolean,
    enabled: Boolean,
    title: String,
    summary: String,
    onCommit: (Int) -> Unit,
    /** @author bomo 拖动中的草稿值回调（null = 结束拖动）；不传则没有预览。 */
    onPreviewChange: (Int?) -> Unit = {},
    minPercent: Int = ModulePreferences.MIN_PANEL_SCALE_PERCENT,
    maxPercent: Int = ModulePreferences.MAX_PANEL_SCALE_PERCENT,
    coerce: (Int) -> Int = ModulePreferences::coercePanelScalePercent,
) {
    var draftValue by rememberSaveable { mutableFloatStateOf(confirmedValue.toFloat()) }
    var isDragging by remember { mutableStateOf(false) }
    LaunchedEffect(confirmedValue, isUpdating, enabled) {
        if (!enabled) onPreviewChange(null)
        if (!isDragging && !isUpdating) draftValue = confirmedValue.toFloat()
    }
    SliderPreference(
        value = draftValue,
        onValueChange = { value ->
            isDragging = true
            val draft = coerce(value.roundToInt())
            draftValue = draft.toFloat()
            onPreviewChange(draft)
        },
        title = title,
        summary = summary,
        valueText = stringResource(R.string.percent_value, draftValue.roundToInt()),
        enabled = enabled,
        startAction = { PreferenceIcon(icon, enabled) },
        valueRange = minPercent.toFloat()..maxPercent.toFloat(),
        steps = maxPercent - minPercent - 1,
        onValueChangeFinished = {
            isDragging = false
            onPreviewChange(null)
            val committed = coerce(draftValue.roundToInt())
            draftValue = committed.toFloat()
            if (committed != confirmedValue) onCommit(committed)
        },
    )
}

/** @author bomo 触发热区预览快照：拖动任一条滑条时，用「其余项已确认值 + 本条草稿值」组成。 */
internal data class TriggerRangePreview(
    val shape: TriggerShape,
    val rangeDp: Int,
    val horizontalDp: Int,
    val verticalDp: Int,
)

/**
 * @author bomo 触发热区预览。几何与命中判定同源（见 `CornerTriggerRegion`）：
 * 扇形为四分之一圆；三角形以屏幕角落为直角顶点，两条直角边分别沿底边（横向）与侧边（纵向），
 * 斜边朝内。预览只作示意，不参与任何手势判定。
 */
@Composable
internal fun CornerRangePreview(
    shape: TriggerShape,
    rangeDp: Int,
    horizontalDp: Int,
    verticalDp: Int,
    leftEnabled: Boolean,
    rightEnabled: Boolean,
) {
    val activeColor = MiuixTheme.colorScheme.primary
    val inactiveColor = MiuixTheme.colorScheme.onSurfaceVariantSummary
    Canvas(modifier = Modifier.fillMaxSize()) {
        val radius = rangeDp.dp.toPx()
        val horizontal = horizontalDp.dp.toPx()
        val vertical = verticalDp.dp.toPx()
        val bottom = size.height
        val arcSize = Size(radius * 2f, radius * 2f)
        val strokeWidth = 2.dp.toPx()
        val dash = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 5.dp.toPx()))

        fun drawSector(startAngle: Float, topLeft: Offset, enabled: Boolean) {
            val color = if (enabled) activeColor else inactiveColor
            drawArc(
                color = color.copy(alpha = if (enabled) 0.22f else 0.08f),
                startAngle = startAngle,
                sweepAngle = 90f,
                useCenter = true,
                topLeft = topLeft,
                size = arcSize,
            )
            drawArc(
                color = color.copy(alpha = if (enabled) 0.82f else 0.30f),
                startAngle = startAngle,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, pathEffect = dash),
            )
        }

        /** @param cornerX 直角顶点横坐标；[mirror] 为真时（右下角）直角边向左延伸。 */
        fun drawTriangle(cornerX: Float, mirror: Boolean, enabled: Boolean) {
            val color = if (enabled) activeColor else inactiveColor
            val path =
                Path().apply {
                    moveTo(cornerX, bottom)
                    lineTo(if (mirror) cornerX - horizontal else cornerX + horizontal, bottom)
                    lineTo(cornerX, bottom - vertical)
                    close()
                }
            drawPath(path, color = color.copy(alpha = if (enabled) 0.22f else 0.08f))
            drawPath(
                path,
                color = color.copy(alpha = if (enabled) 0.82f else 0.30f),
                style = Stroke(width = strokeWidth, pathEffect = dash),
            )
        }

        when (shape) {
            TriggerShape.Sector -> {
                drawSector(
                    startAngle = 270f,
                    topLeft = Offset(-radius, bottom - radius),
                    enabled = leftEnabled,
                )
                drawSector(
                    startAngle = 180f,
                    topLeft = Offset(size.width - radius, bottom - radius),
                    enabled = rightEnabled,
                )
            }

            TriggerShape.Triangle -> {
                drawTriangle(cornerX = 0f, mirror = false, enabled = leftEnabled)
                drawTriangle(cornerX = size.width, mirror = true, enabled = rightEnabled)
            }
        }
    }
}

/**
 * @author bomo 轮盘缩放预览：拖动「轮盘缩放」滑条时，在左右下角画出轮盘将占用的范围。
 *
 * **画法与「角落触发范围」预览（[CornerRangePreview]）完全一致**（用户 2026-10-16 要求「效果统一」）：
 * 同一个四分之一圆、同样的实心扇形 + 虚线描边、同样的启用/未启用配色，
 * 只是半径换成**轮盘外缘**（= 图标圆心半径 + 图标半径 + 留白），也就是「呼出来后这一圈占多大」。
 *
 * 换算与 system_server 侧**同源**（口径取自 `RadialIconGeometry.previewOuterEdgeDp`，不另抄一份）：
 * `每基准 dp = 窗口短边 / 400`，100% 时外缘 ≈ 675 + 64 + 4 = 743px；
 * 105% 时 ≈ 709 + 67 + 4 = 780px；再往上**半径继续长、图标封顶**（本机短边 1080）。
 * 只作示意，不参与任何手势判定。
 */
@Composable
internal fun RadialScalePreview(
    percent: Int,
    leftEnabled: Boolean,
    rightEnabled: Boolean,
) {
    val activeColor = MiuixTheme.colorScheme.primary
    val inactiveColor = MiuixTheme.colorScheme.onSurfaceVariantSummary
    Canvas(modifier = Modifier.fillMaxSize()) {
        val unit = minOf(size.width, size.height) / RadialIconGeometry.BASE_SHORT_EDGE_DP
        val outerEdge = RadialIconGeometry.previewOuterEdgeDp(percent) * unit
        val bottom = size.height
        val arcSize = Size(outerEdge * 2f, outerEdge * 2f)
        val strokeWidth = 2.dp.toPx()
        val dash = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 5.dp.toPx()))

        fun drawSector(startAngle: Float, topLeft: Offset, enabled: Boolean) {
            val color = if (enabled) activeColor else inactiveColor
            drawArc(
                color = color.copy(alpha = if (enabled) 0.22f else 0.08f),
                startAngle = startAngle,
                sweepAngle = 90f,
                useCenter = true,
                topLeft = topLeft,
                size = arcSize,
            )
            drawArc(
                color = color.copy(alpha = if (enabled) 0.82f else 0.30f),
                startAngle = startAngle,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, pathEffect = dash),
            )
        }

        drawSector(
            startAngle = 270f,
            topLeft = Offset(-outerEdge, bottom - outerEdge),
            enabled = leftEnabled,
        )
        drawSector(
            startAngle = 180f,
            topLeft = Offset(size.width - outerEdge, bottom - outerEdge),
            enabled = rightEnabled,
        )
    }
}

@Composable
internal fun PreferenceIcon(imageVector: ImageVector, enabled: Boolean = true) {
    Icon(
        imageVector = imageVector,
        contentDescription = null,
        modifier = Modifier.padding(end = 6.dp).size(24.dp),
        tint =
            if (enabled) MiuixTheme.colorScheme.onBackground
            else MiuixTheme.colorScheme.disabledOnSecondaryVariant,
    )
}

@Composable
internal fun frameworkPresentation(state: FrameworkConnectionState): FrameworkPresentation =
    when (state.status) {
        FrameworkConnectionStatus.Waiting -> FrameworkPresentation(stringResource(R.string.framework_status_waiting), stringResource(R.string.framework_waiting_summary))
        FrameworkConnectionStatus.Connected -> {
            val unknown = stringResource(R.string.framework_unknown_value)
            FrameworkPresentation(
                stringResource(R.string.framework_status_connected),
                stringResource(
                    R.string.framework_connected_summary,
                    state.frameworkName ?: unknown,
                    state.frameworkVersion ?: unknown,
                    state.apiVersion?.toString() ?: unknown,
                ),
            )
        }
        FrameworkConnectionStatus.Incompatible -> FrameworkPresentation(stringResource(R.string.framework_status_incompatible), stringResource(state.issue.incompatibleSummary()))
        FrameworkConnectionStatus.Error -> FrameworkPresentation(stringResource(R.string.framework_status_error), stringResource(state.issue.errorSummary()))
    }

@StringRes
private fun FrameworkConnectionIssue?.incompatibleSummary(): Int =
    when (this) {
        FrameworkConnectionIssue.ServiceApiTooOld -> R.string.framework_api_too_old_summary
        FrameworkConnectionIssue.RemoteCapabilityMissing -> R.string.framework_remote_missing_summary
        FrameworkConnectionIssue.SystemCapabilityMissing -> R.string.framework_system_missing_summary
        FrameworkConnectionIssue.MultipleServices -> R.string.framework_multiple_services_summary
        else -> R.string.framework_connection_failed_summary
    }

@StringRes
private fun FrameworkConnectionIssue?.errorSummary(): Int =
    when (this) {
        FrameworkConnectionIssue.WriteFailed -> R.string.framework_write_failed_summary
        else -> R.string.framework_connection_failed_summary
    }

internal data class FrameworkPresentation(val label: String, val summary: String)

internal val ScreenHorizontalMargin = 12.dp
internal val ScreenContentMaxWidth = 600.dp
internal val WideWindowMinWidth = 600.dp
internal val ScreenTopSpacing = 0.dp
internal val ScreenBottomSpacing = 12.dp
private val CardSpacing = 12.dp

/**
 * @author bomo 2026-10-19 设置页里正在拖动的预览。
 *
 * 由 [ControlScreen] 上报、[FlymeFreeformNavHost] 画在整窗最上层 —— 只有这样
 * 角标/轮盘预览才会贴着真实屏幕底边，而不是被底部 tab 栏顶上去。
 */
internal sealed interface ScreenOverlayPreview {
    data class CornerRange(
        val preview: TriggerRangePreview,
        val leftEnabled: Boolean,
        val rightEnabled: Boolean,
    ) : ScreenOverlayPreview

    data class RadialScale(
        val percent: Int,
        val leftEnabled: Boolean,
        val rightEnabled: Boolean,
    ) : ScreenOverlayPreview
}
