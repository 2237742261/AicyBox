package com.eta.aicybox.ui

import android.content.Intent
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Height
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.SettingsBackupRestore
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.SwipeRight
import androidx.compose.material.icons.rounded.VerticalAlignCenter
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.eta.aicybox.R
import com.eta.aicybox.sideindex.config.ModuleConfig
import com.eta.aicybox.sideindex.config.PrefKeys
import com.eta.aicybox.sideindex.config.Side
import com.eta.aicybox.sideindex.settings.PrefStore
import com.eta.aicybox.sideindex.ui.TriggerBandView
import com.eta.aicybox.sideindex.util.Haptics
import com.eta.aicybox.ui.component.AppPickerSheet
import com.eta.aicybox.ui.component.TopBarBackdrop
import com.eta.aicybox.ui.component.loadPickerApps
import com.eta.aicybox.ui.component.captureForTopBar
import com.eta.aicybox.ui.component.rememberTopBarBackdrop
import com.eta.aicybox.ui.component.topBarContainerColor
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import kotlin.math.roundToInt

/**
 * @author bomo 2026-10-19 「索引」页。
 *
 * 侧滑索引（原 `com.eta.sideindexx` 模块）整体移植进来后，设置项原本塞在一个二级 Activity
 * （`sideindex.settings.SettingsActivity`，自绘 View 的旧样式）里，与「小窗」「建议」两页的
 * Miuix 卡片风格不一致。这里改成**直接在页内铺开**：
 *  - 所有开关/滑条/下拉都是 Miuix preference，和另外两页同款排版；
 *  - 写操作仍走 [PrefStore.write]，它会把完整配置推到 `Settings.Secure` 通道，
 *    SystemUI 里的承载进程立即热生效，不用重启系统界面；
 *  - 2026-10-19 起「应用黑名单」也不再跳旧 Activity，改用与扇形应用同款的
 *    [com.eta.aicybox.ui.component.AppPickerSheet] 多选弹层（用户要求界面统一）。
 *
 * @param onBandPreview 拖动触发区滑条时把「将生效的配置」上报给 NavHost，由它画在整窗最上层
 *   （`TriggerBandView`，不消费触摸）；松手 900ms 后上报 null 收回。
 */
@Composable
internal fun IndexScreen(
    onBandPreview: (ModuleConfig?) -> Unit = {},
) {
    val context = LocalContext.current.applicationContext
    var config by remember { mutableStateOf(PrefStore.currentConfig(context)) }
    // @author bomo 2026-10-19 黑名单改为弹层多选（原来是跳 AppPickerActivity）
    var blacklistPickerOpen by remember { mutableStateOf(false) }
    var holdPreview by remember { mutableStateOf(false) }
    var lastVibrationAt by remember { mutableStateOf(0L) }

    fun reload() {
        config = PrefStore.currentConfig(context)
    }

    /** 黑名单包名集合（逗号分隔字符串 <-> Set）。 */
    val blacklistPackages =
        remember(config.blacklist) {
            config.blacklist
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()
        }

    fun commit(block: (android.content.SharedPreferences.Editor) -> Unit) {
        PrefStore.write(context, block)
        reload()
    }

    fun preview(updated: ModuleConfig) {
        holdPreview = true
        onBandPreview(updated)
    }

    // 从「应用黑名单」选择页返回时要刷新计数（黑名单在那边改的）
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) reload()
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(holdPreview) {
        if (!holdPreview) {
            delay(900)
            onBandPreview(null)
        }
    }

    val backdrop = rememberTopBarBackdrop()
    val topBarColor = topBarContainerColor(backdrop)
    val scrollBehavior = MiuixScrollBehavior()

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
                            title = stringResource(R.string.screen_index_title),
                            color = topBarColor,
                            scrollBehavior = scrollBehavior,
                        )
                    } else {
                        TopAppBar(
                            title = stringResource(R.string.screen_index_title),
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
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding =
                        PaddingValues(
                            start = safeStart + centeredSide,
                            top = innerPadding.calculateTopPadding() + ScreenTopSpacing,
                            end = safeEnd + centeredSide,
                            bottom = innerPadding.calculateBottomPadding() + ScreenBottomSpacing,
                        ),
                    overscrollEffect = null,
                ) {
                    item(key = "master") {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            SwitchPreference(
                                checked = config.enabled,
                                onCheckedChange = { value ->
                                    commit { it.putBoolean(PrefKeys.ENABLED, value) }
                                },
                                title = stringResource(R.string.index_enabled_title),
                                summary = stringResource(R.string.index_enabled_summary),
                                startAction = { PreferenceIcon(Icons.Rounded.PowerSettingsNew) },
                            )
                        }
                    }
                    item(key = "gesture") {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            TriggerSidePreference(
                                side = config.triggerSide,
                                enabled = config.enabled,
                                onSideChange = { side ->
                                    commit {
                                        it.putString(PrefKeys.TRIGGER_SIDE, side)
                                        // 老键同步写，兼容还按 both_sides 读的旧版本
                                        it.putBoolean(PrefKeys.BOTH_SIDES, side == "both")
                                    }
                                },
                            )
                            IndexSliderPreference(
                                icon = Icons.Rounded.SwapHoriz,
                                title = stringResource(R.string.index_trigger_width_title),
                                summary = stringResource(R.string.index_trigger_width_summary),
                                value = config.triggerWidthDp,
                                minValue = 0,
                                maxValue = 35,
                                unitRes = R.string.index_value_dp,
                                enabled = config.enabled,
                                onPreview = { v -> preview(config.copy(triggerWidthDp = v)) },
                                onCommit = { v -> commit { it.putInt(PrefKeys.TRIGGER_WIDTH_DP, v) } },
                                onPreviewEnd = { holdPreview = false },
                            )
                            IndexSliderPreference(
                                icon = Icons.Rounded.Height,
                                title = stringResource(R.string.index_trigger_height_title),
                                summary = stringResource(R.string.index_trigger_height_summary),
                                value = config.barHeightPercent,
                                minValue = 20,
                                maxValue = 100,
                                unitRes = R.string.index_value_percent,
                                enabled = config.enabled,
                                onPreview = { v -> preview(config.copy(barHeightPercent = v)) },
                                onCommit = { v -> commit { it.putInt(PrefKeys.BAR_HEIGHT_PERCENT, v) } },
                                onPreviewEnd = { holdPreview = false },
                            )
                            IndexSliderPreference(
                                icon = Icons.Rounded.VerticalAlignCenter,
                                title = stringResource(R.string.index_position_title),
                                summary = stringResource(R.string.index_position_summary),
                                value = config.triggerPositionPercent,
                                minValue = 0,
                                maxValue = 100,
                                unitRes = R.string.index_value_percent,
                                enabled = config.enabled,
                                onPreview = { v -> preview(config.copy(triggerPositionPercent = v)) },
                                onCommit = { v -> commit { it.putInt(PrefKeys.TRIGGER_POSITION_PERCENT, v) } },
                                onPreviewEnd = { holdPreview = false },
                            )
                            IndexSliderPreference(
                                icon = Icons.Rounded.Vibration,
                                title = stringResource(R.string.index_vibrate_strength_title),
                                summary = stringResource(R.string.index_vibrate_strength_summary),
                                value = config.vibrateStrength,
                                minValue = 0,
                                maxValue = 100,
                                unitRes = R.string.index_value_percent,
                                enabled = config.enabled,
                                onPreview = { v ->
                                    // 拖动时按当前强度实时震一下（120ms 节流），0 = 不震
                                    if (v > 0) {
                                        val now = System.currentTimeMillis()
                                        if (now - lastVibrationAt >= 120) {
                                            lastVibrationAt = now
                                            Haptics.tick(context, v, null)
                                        }
                                    }
                                },
                                onCommit = { v -> commit { it.putInt(PrefKeys.VIBRATE_STRENGTH, v) } },
                                onPreviewEnd = { holdPreview = false },
                            )
                        }
                    }
                    item(key = "display") {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            SwitchPreference(
                                checked = config.immersive,
                                onCheckedChange = { value ->
                                    // 与「全局索引」互斥：开沉浸自动关全局
                                    commit {
                                        it.putBoolean(PrefKeys.IMMERSIVE, value)
                                        if (value) it.putBoolean(PrefKeys.ONLY_LAUNCHER, true)
                                    }
                                },
                                title = stringResource(R.string.index_immersive_title),
                                summary = stringResource(R.string.index_immersive_summary),
                                enabled = config.enabled,
                                startAction = { PreferenceIcon(Icons.Rounded.BlurOn, config.enabled) },
                            )
                            SwitchPreference(
                                checked = !config.onlyLauncher,
                                onCheckedChange = { value ->
                                    // 与「沉浸模式」互斥：开全局自动关沉浸
                                    commit {
                                        it.putBoolean(PrefKeys.ONLY_LAUNCHER, !value)
                                        if (value) it.putBoolean(PrefKeys.IMMERSIVE, false)
                                    }
                                },
                                title = stringResource(R.string.index_global_title),
                                summary = stringResource(R.string.index_global_summary),
                                enabled = config.enabled,
                                startAction = { PreferenceIcon(Icons.Rounded.Public, config.enabled) },
                            )
                            SwitchPreference(
                                checked = config.gameBlock,
                                onCheckedChange = { value ->
                                    commit { it.putBoolean(PrefKeys.GAME_BLOCK, value) }
                                },
                                title = stringResource(R.string.index_game_block_title),
                                summary = stringResource(R.string.index_game_block_summary),
                                enabled = config.enabled,
                                startAction = { PreferenceIcon(Icons.Rounded.SportsEsports, config.enabled) },
                            )
                            SwitchPreference(
                                checked = config.landscapePause,
                                onCheckedChange = { value ->
                                    commit { it.putBoolean(PrefKeys.LANDSCAPE_PAUSE, value) }
                                },
                                title = stringResource(R.string.index_landscape_pause_title),
                                summary = stringResource(R.string.index_landscape_pause_summary),
                                enabled = config.enabled,
                                startAction = { PreferenceIcon(Icons.Rounded.ScreenRotation, config.enabled) },
                            )
                            val blacklistCount =
                                config.blacklist.split(',').count { it.isNotBlank() }
                            ArrowPreference(
                                title = stringResource(R.string.index_blacklist_title),
                                summary =
                                    if (blacklistCount == 0) {
                                        stringResource(R.string.index_blacklist_empty)
                                    } else {
                                        stringResource(R.string.index_blacklist_count, blacklistCount)
                                    },
                                // @author bomo 2026-10-19 用户要求：黑名单选择应用界面和扇形应用统一，
                                // 不再跳自绘 View 的旧 AppPickerActivity，改用 AppPickerSheet 弹层。
                                onClick = { blacklistPickerOpen = true },
                                enabled = config.enabled,
                                startAction = { PreferenceIcon(Icons.Rounded.Block, config.enabled) },
                            )
                            ColumnsPreference(
                                columns = config.gridColumns,
                                enabled = config.enabled,
                                onColumnsChange = { columns ->
                                    commit { it.putInt(PrefKeys.GRID_COLUMNS, columns) }
                                },
                            )
                        }
                    }
                    item(key = "launch") {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            SwitchPreference(
                                checked = config.freeformLaunch,
                                onCheckedChange = { value ->
                                    commit { it.putBoolean(PrefKeys.FREEFORM_LAUNCH, value) }
                                },
                                title = stringResource(R.string.index_freeform_title),
                                summary = stringResource(R.string.index_freeform_summary),
                                enabled = config.enabled,
                                startAction = { PreferenceIcon(Icons.Rounded.OpenInNew, config.enabled) },
                            )
                        }
                    }
                }
            }
        }

        // @author bomo 2026-10-19 应用黑名单选择：与「扇形应用」「耳机音乐」共用同一个弹层组件。
        if (blacklistPickerOpen) {
            val pickerApps =
                remember { loadPickerApps(context, includePackages = blacklistPackages) }
            AppPickerSheet(
                show = blacklistPickerOpen,
                title = stringResource(R.string.index_blacklist_title),
                apps = pickerApps,
                selected = blacklistPackages,
                multiSelect = true,
                onDismiss = { blacklistPickerOpen = false },
                onToggle = { pkg ->
                    val updated =
                        if (pkg in blacklistPackages) {
                            blacklistPackages - pkg
                        } else {
                            blacklistPackages + pkg
                        }
                    commit { it.putString(PrefKeys.BLACKLIST, updated.joinToString(",")) }
                },
            )
        }
    }
}

/**
 * @author bomo 2026-10-19 触发区预览（画在整窗最上层）。
 *
 * 直接复用移植过来的 [TriggerBandView]：它 `isClickable=false`，不消费触摸，
 * 叠在设置页上面不影响任何交互；和旧设置页的高亮区域是同一份绘制逻辑。
 */
@Composable
internal fun IndexBandPreview(config: ModuleConfig) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx -> TriggerBandView(ctx) },
        update = { view ->
            view.updateConfig(config, Side.RIGHT, config.onLeft() && config.onRight())
            view.setLive(true)
        },
    )
}

/** 呼出方式：右侧 / 左侧 / 两侧均可。 */
@Composable
private fun TriggerSidePreference(
    side: String,
    enabled: Boolean,
    onSideChange: (String) -> Unit,
) {
    val values = listOf("right", "left", "both")
    val items =
        listOf(
            DropdownItem(text = stringResource(R.string.index_side_right)),
            DropdownItem(text = stringResource(R.string.index_side_left)),
            DropdownItem(text = stringResource(R.string.index_side_both)),
        )
    OverlaySpinnerPreference(
        items = items,
        selectedIndex = values.indexOf(side).coerceAtLeast(0),
        title = stringResource(R.string.index_trigger_side_title),
        summary = stringResource(R.string.index_trigger_side_summary),
        enabled = enabled,
        startAction = { PreferenceIcon(Icons.Rounded.SwipeRight, enabled) },
        onSelectedIndexChange = { index -> values.getOrNull(index)?.let(onSideChange) },
    )
}

/** 每行图标数：4 个 / 5 个。 */
@Composable
private fun ColumnsPreference(
    columns: Int,
    enabled: Boolean,
    onColumnsChange: (Int) -> Unit,
) {
    val items =
        listOf(
            DropdownItem(text = stringResource(R.string.index_columns_4)),
            DropdownItem(text = stringResource(R.string.index_columns_5)),
        )
    OverlaySpinnerPreference(
        items = items,
        selectedIndex = if (columns <= 4) 0 else 1,
        title = stringResource(R.string.index_columns_title),
        enabled = enabled,
        startAction = { PreferenceIcon(Icons.Rounded.GridView, enabled) },
        onSelectedIndexChange = { index -> onColumnsChange(if (index == 0) 4 else 5) },
    )
}

/**
 * @author bomo 2026-10-19 整数滑条（dp 或百分比），拖动时通过 [onPreview] 上报草稿值，
 * 松手（[onPreviewEnd]）后再写配置。写法和「小窗」页的滑条保持一致。
 */
@Composable
private fun IndexSliderPreference(
    icon: ImageVector,
    title: String,
    summary: String,
    value: Int,
    minValue: Int,
    maxValue: Int,
    @StringRes unitRes: Int,
    enabled: Boolean,
    onPreview: (Int) -> Unit,
    onCommit: (Int) -> Unit,
    onPreviewEnd: () -> Unit = {},
) {
    var draftValue by rememberSaveable { mutableFloatStateOf(value.toFloat()) }
    var isDragging by remember { mutableStateOf(false) }
    LaunchedEffect(value, enabled) {
        if (!isDragging) draftValue = value.toFloat()
    }
    // @author bomo 2026-10-20 数值并到标题里（"触发宽度 · 20 dp"）：
    // 原来走 SliderPreference 的 valueText，右侧要占一格宽度，说明文字只能挤在半行里换行，
    // 看着和别的开关说明没对齐。现在数值跟标题同一行，说明文字吃满整行。
    val valueLabel = stringResource(unitRes, draftValue.roundToInt())
    SliderPreference(
        value = draftValue,
        onValueChange = { raw ->
            isDragging = true
            val draft = raw.roundToInt().coerceIn(minValue, maxValue)
            draftValue = draft.toFloat()
            onPreview(draft)
        },
        title = "$title · $valueLabel",
        summary = summary,
        enabled = enabled,
        startAction = { PreferenceIcon(icon, enabled) },
        valueRange = minValue.toFloat()..maxValue.toFloat(),
        steps = (maxValue - minValue - 1).coerceAtLeast(0),
        onValueChangeFinished = {
            isDragging = false
            onPreviewEnd()
            val committed = draftValue.roundToInt().coerceIn(minValue, maxValue)
            draftValue = committed.toFloat()
            if (committed != value) onCommit(committed)
        },
    )
}
