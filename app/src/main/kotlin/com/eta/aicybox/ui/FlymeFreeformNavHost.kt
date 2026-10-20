package com.eta.aicybox.ui

import android.content.ComponentName
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CropSquare
import androidx.compose.material.icons.rounded.House
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.eta.aicybox.R
import com.eta.aicybox.apps.InstalledLauncherApp
import com.eta.aicybox.config.CornerCallMode
import com.eta.aicybox.config.OutsideTapCloseMode
import com.eta.aicybox.config.PayChannel
import com.eta.aicybox.config.TriggerShape
import com.eta.aicybox.framework.FrameworkConnectionState
import com.eta.aicybox.framework.ScopeRestartOutcome
import com.eta.aicybox.sideindex.config.ModuleConfig
import kotlinx.serialization.Serializable
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 底部导航的三个页签。 */
private enum class HomeTab(
    val labelRes: Int,
    val icon: ImageVector,
) {
    Home(R.string.tab_home, Icons.Rounded.House),
    Settings(R.string.tab_settings, Icons.Rounded.CropSquare),
    Features(R.string.tab_features, Icons.Rounded.Lightbulb),

    /** @author bomo 2026-10-19 新增「索引」页：侧滑索引（从 SideIndexX 移植）的开关与入口。 */
    Index(R.string.tab_index, Icons.Rounded.Sort),
}

@Serializable
internal sealed interface AppRoute : NavKey {
    @Serializable
    data object Settings : AppRoute

    @Serializable
    data object PinnedApps : AppRoute

    /** @author bomo 2026-10-19 聚合支付方式排序页。 */
    @Serializable
    data object PayOrder : AppRoute
}

@Composable
internal fun FlymeFreeformNavHost(
    state: FrameworkConnectionState,
    apps: List<InstalledLauncherApp>,
    onModuleEnabledChange: (Boolean) -> Unit,
    onCornerCallModeChange: (CornerCallMode) -> Unit,
    onCornerTriggerRangeChange: (Int) -> Unit,
    onTriggerShapeChange: (TriggerShape) -> Unit,
    onTriggerHorizontalDpChange: (Int) -> Unit,
    onTriggerVerticalDpChange: (Int) -> Unit,
    onPanelScaleChange: (Int) -> Unit,
    onRadialScaleChange: (Int) -> Unit,
    /** @author bomo 2026-10-19 轮盘切换图标震动开关 + 强度。 */
    onRadialVibrateEnabledChange: (Boolean) -> Unit,
    onRadialVibrateStrengthChange: (Int) -> Unit,
    onOutsideTapCloseModeChange: (OutsideTapCloseMode) -> Unit,
    onHandleSwipeUpToMiniEnabledChange: (Boolean) -> Unit,
    /** @author bomo 2026-10-19 聚合支付方式排序变更（顺序 = 默认打开项 + 支付栏排列）。 */
    onPayOrderChange: (List<PayChannel>) -> Unit,
    onPauseInLandscapeChange: (Boolean) -> Unit,
    onPauseInGameModeChange: (Boolean) -> Unit,
    onDeveloperModeChange: (Boolean) -> Unit,
    /** @author bomo 2026-10-19 首页「恢复默认设置」：把小窗 / 建议 / 索引的全部设置写回默认值。 */
    onResetAllSettings: () -> Unit,
    onAicyBubblesChange: (Boolean) -> Unit,
    onFlashlightBubbleChange: (Boolean) -> Unit,
    onKeepAwakeBubbleChange: (Boolean) -> Unit,
    onWechatBubbleChange: (Boolean) -> Unit,
    onQqBubbleChange: (Boolean) -> Unit,
    onAppInstallBubbleChange: (Boolean) -> Unit,
    onEarphoneBubbleChange: (Boolean) -> Unit,
    onEarphoneMusicPkgsChange: (String) -> Unit,
    onLinkBubbleChange: (Boolean) -> Unit,
    onShareBubbleChange: (Boolean) -> Unit,
    onBrowserBubbleChange: (Boolean) -> Unit,
    onBrowserPkgChange: (String) -> Unit,
    onBubblePosChange: (Int, Int) -> Unit,
    onRequestScopes: () -> Unit,
    onRestartScope: suspend () -> ScopeRestartOutcome,
    onPinnedComponentsChange: (List<ComponentName>) -> Unit,
    onInnerPinnedComponentsChange: (List<ComponentName>) -> Unit,
) {
    var selectedTab by rememberSaveable { mutableStateOf(HomeTab.Home) }

    val backStack = rememberNavBackStack(AppRoute.Settings)
    val popBackStack = remember(backStack) {
        {
            if (backStack.size > 1) {
                backStack.removeLastOrNull()
            }
            Unit
        }
    }
    val navigateToPinnedApps = remember(backStack) {
        {
            if (backStack.lastOrNull() != AppRoute.PinnedApps) {
                backStack.add(AppRoute.PinnedApps)
            }
        }
    }
    val navigateToPayOrder = remember(backStack) {
        {
            if (backStack.lastOrNull() != AppRoute.PayOrder) {
                backStack.add(AppRoute.PayOrder)
            }
        }
    }

    // @author bomo 2026-10-19 预览画在 Scaffold 外层：整窗坐标系，不吃底部 tab 栏的内边距。
    var overlayPreview by remember { mutableStateOf<ScreenOverlayPreview?>(null) }
    // @author bomo 2026-10-19 索引页的触发区预览（同样画在整窗最上层，避免被悬浮 tab 栏抬高）。
    var indexBandPreview by remember { mutableStateOf<ModuleConfig?>(null) }
    LaunchedEffect(selectedTab) {
        overlayPreview = null
        indexBandPreview = null
    }
    // @author bomo 2026-10-19 沉浸：整窗铺一层主题背景色。
    // Scaffold 的容器背景只画在它自己的布局范围内，状态栏、底部手势条那两条系统栏区域
    // 露出的是 Activity 窗口背景（本机是纯黑），于是 tab 栏下面、状态栏上面各出现一条黑边。
    // 在 enableEdgeToEdge 之后，这个 fillMaxSize 的 Box 会一直铺到屏幕物理边，
    // 系统栏区域自然与页面同色，黑边消失。
    Box(
        modifier =
            androidx.compose.ui.Modifier
                .fillMaxSize()
                .background(MiuixTheme.colorScheme.background),
    ) {
        Scaffold(
            bottomBar = {
                // @author bomo 2026-10-19 用户要求换回悬浮胶囊之前的样子：整宽贴底的 NavigationBar。
                // 底部手势条那一条仍然由外层 Box 铺主题底色，不会露出纯黑的窗口背景。
                NavigationBar {
                    HomeTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = tab == selectedTab,
                            onClick = { selectedTab = tab },
                            icon = tab.icon,
                            label = stringResource(tab.labelRes),
                        )
                    }
                }
            },
        ) { innerPadding ->
        androidx.compose.foundation.layout.Box(
            androidx.compose.ui.Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (selectedTab) {
                HomeTab.Home ->
                    HomeScreen(
                        state = state,
                        onRequestScopes = onRequestScopes,
                        onRestartScope = onRestartScope,
                        onDeveloperModeChange = onDeveloperModeChange,
                        onResetAllSettings = onResetAllSettings,
                    )
                HomeTab.Index -> IndexScreen(onBandPreview = { indexBandPreview = it })

                HomeTab.Features -> FeatureScreen(
                    aicyEnabled = state.settings.aicyBubblesEnabled,
                    flashlightEnabled = state.settings.flashlightBubbleEnabled,
                    keepAwakeEnabled = state.settings.keepAwakeBubbleEnabled,
                    wechatEnabled = state.settings.wechatBubbleEnabled,
                    qqEnabled = state.settings.qqBubbleEnabled,
                    appInstallEnabled = state.settings.appInstallBubbleEnabled,
                    earphoneEnabled = state.settings.earphoneBubbleEnabled,
                    earphoneMusicPkgs = state.settings.earphoneMusicPkgs,
                    linkEnabled = state.settings.linkBubbleEnabled,
                    shareEnabled = state.settings.shareBubbleEnabled,
                    browserEnabled = state.settings.browserBubbleEnabled,
                    browserPkg = state.settings.browserPkg,
                    onAicyEnabledChange = onAicyBubblesChange,
                    onFlashlightEnabledChange = onFlashlightBubbleChange,
                    onKeepAwakeEnabledChange = onKeepAwakeBubbleChange,
                    onWechatEnabledChange = onWechatBubbleChange,
                    onQqEnabledChange = onQqBubbleChange,
                    onAppInstallEnabledChange = onAppInstallBubbleChange,
                    onEarphoneEnabledChange = onEarphoneBubbleChange,
                    onEarphoneMusicPkgsChange = onEarphoneMusicPkgsChange,
                    onLinkEnabledChange = onLinkBubbleChange,
                    onShareEnabledChange = onShareBubbleChange,
                    onBrowserEnabledChange = onBrowserBubbleChange,
                    onBrowserPkgChange = onBrowserPkgChange,
                    bubblePosXDp = state.settings.bubblePosXDp,
                    bubblePosYDp = state.settings.bubblePosYDp,
                    onBubblePosChange = onBubblePosChange,
                )

                HomeTab.Settings ->
                    NavDisplay(
                        backStack = backStack,
                        modifier = Modifier.fillMaxSize(),
                        onBack = popBackStack,
                        // @author bomo 2026-10-19 用户反馈进「扇形应用 / 聚合支付」页时那个「圆角展开」
                        // 动画很怪：库的默认转场（scale + 圆角裁剪）去掉，换成 120ms 淡入淡出。
                        transitionSpec = { fadeIn(tween(120)) togetherWith fadeOut(tween(120)) },
                        popTransitionSpec = { fadeIn(tween(120)) togetherWith fadeOut(tween(120)) },
                        entryProvider =
                            entryProvider {
                                entry<AppRoute.Settings> {
                                    ControlScreen(
                                        state = state,
                                        onModuleEnabledChange = onModuleEnabledChange,
                                        onCornerCallModeChange = onCornerCallModeChange,
                                        onCornerTriggerRangeChange = onCornerTriggerRangeChange,
                                        onTriggerShapeChange = onTriggerShapeChange,
                                        onTriggerHorizontalDpChange = onTriggerHorizontalDpChange,
                                        onTriggerVerticalDpChange = onTriggerVerticalDpChange,
                                        onPanelScaleChange = onPanelScaleChange,
                                        onRadialScaleChange = onRadialScaleChange,
                                        onRadialVibrateEnabledChange =
                                            onRadialVibrateEnabledChange,
                                        onRadialVibrateStrengthChange =
                                            onRadialVibrateStrengthChange,
                                        onOutsideTapCloseModeChange = onOutsideTapCloseModeChange,
                                        onHandleSwipeUpToMiniEnabledChange =
                                            onHandleSwipeUpToMiniEnabledChange,
                                        onNavigateToPayOrder = navigateToPayOrder,
                                        onPauseInLandscapeChange = onPauseInLandscapeChange,
                                        onPauseInGameModeChange = onPauseInGameModeChange,
                                        onNavigateToPinnedApps = navigateToPinnedApps,
                                        onOverlayPreviewChange = { overlayPreview = it },
                                    )
                                }
                                entry<AppRoute.PinnedApps> {
                                    PinnedAppsScreen(
                                        state = state,
                                        apps = apps,
                                        onBack = popBackStack,
                                        onPinnedComponentsChange = onPinnedComponentsChange,
                                        onInnerPinnedComponentsChange = onInnerPinnedComponentsChange,
                                    )
                                }
                                entry<AppRoute.PayOrder> {
                                    PayOrderScreen(
                                        state = state,
                                        onBack = popBackStack,
                                        onOrderChange = onPayOrderChange,
                                    )
                                }
                            },
                    )
            }
        }
    }

        overlayPreview?.let { preview ->
            when (preview) {
                is ScreenOverlayPreview.CornerRange ->
                    CornerRangePreview(
                        shape = preview.preview.shape,
                        rangeDp = preview.preview.rangeDp,
                        horizontalDp = preview.preview.horizontalDp,
                        verticalDp = preview.preview.verticalDp,
                        leftEnabled = preview.leftEnabled,
                        rightEnabled = preview.rightEnabled,
                    )
                is ScreenOverlayPreview.RadialScale ->
                    RadialScalePreview(
                        percent = preview.percent,
                        leftEnabled = preview.leftEnabled,
                        rightEnabled = preview.rightEnabled,
                    )
            }
        }
        // @author bomo 2026-10-19 索引页拖动触发区滑条时的实时高亮（TriggerBandView，不消费触摸）。
        indexBandPreview?.let { config -> IndexBandPreview(config) }
    }
}
