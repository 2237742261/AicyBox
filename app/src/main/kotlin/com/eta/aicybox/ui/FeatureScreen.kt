package com.eta.aicybox.ui

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
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.Column
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.basic.BasicComponent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Headset
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Comment
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.InstallDesktop
import androidx.compose.material.icons.rounded.Message
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Link
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.eta.aicybox.R
import com.eta.aicybox.ui.component.AppPickerSheet
import com.eta.aicybox.ui.component.loadPickerApps
import com.eta.aicybox.ui.component.TopBarBackdrop
import com.eta.aicybox.ui.component.captureForTopBar
import com.eta.aicybox.ui.component.rememberTopBarBackdrop
import com.eta.aicybox.ui.component.topBarContainerColor
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * @author bomo 建议 Tab（Aicy 建议）：统一气泡模板的功能开关页。
 *
 * 开关走 LSPosed 共享配置（App 经 ConfigPushReceiver 推给 SystemUI 注入进程）。
 * 本页只是受控开关 UI。除手电筒外其它功能目前是「开关占位」，SystemUI 侧逻辑逐个接。
 */
@Composable
internal fun FeatureScreen(
    aicyEnabled: Boolean,
    flashlightEnabled: Boolean,
    keepAwakeEnabled: Boolean,
    wechatEnabled: Boolean,
    qqEnabled: Boolean,
    appInstallEnabled: Boolean,
    earphoneEnabled: Boolean,
    earphoneMusicPkgs: String,
    linkEnabled: Boolean,
    shareEnabled: Boolean,
    browserEnabled: Boolean,
    onAicyEnabledChange: (Boolean) -> Unit,
    onFlashlightEnabledChange: (Boolean) -> Unit,
    onKeepAwakeEnabledChange: (Boolean) -> Unit,
    onWechatEnabledChange: (Boolean) -> Unit,
    onQqEnabledChange: (Boolean) -> Unit,
    onAppInstallEnabledChange: (Boolean) -> Unit,
    onEarphoneEnabledChange: (Boolean) -> Unit,
    onEarphoneMusicPkgsChange: (String) -> Unit,
    onLinkEnabledChange: (Boolean) -> Unit,
    onShareEnabledChange: (Boolean) -> Unit,
    onBrowserEnabledChange: (Boolean) -> Unit,
    bubblePosXDp: Int,
    bubblePosYDp: Int,
    onBubblePosChange: (Int, Int) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    // @author bomo 2026-10-20 用户要求：建议页里点气泡开关给一下震动反馈。
    // @author bomo 2026-10-21 用户取消：建议页气泡开关点击原本有震动反馈，现去掉。
    fun tapped(change: (Boolean) -> Unit): (Boolean) -> Unit = { value -> change(value) }
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberTopBarBackdrop()
    val topBarColor = topBarContainerColor(backdrop)

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
                            title = stringResource(R.string.screen_features_title),
                            color = topBarColor,
                            scrollBehavior = scrollBehavior,
                        )
                    } else {
                        TopAppBar(
                            title = stringResource(R.string.screen_features_title),
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
                                checked = aicyEnabled,
                                onCheckedChange = tapped(onAicyEnabledChange),
                                title = stringResource(R.string.aicy_master_title),
                                summary = stringResource(R.string.aicy_master_summary),
                                startAction = { PreferenceIcon(Icons.Rounded.Campaign) },
                            )
                        }
                    }
                    item(key = "system_bubbles") {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            SwitchPreference(
                                checked = flashlightEnabled,
                                onCheckedChange = tapped(onFlashlightEnabledChange),
                                title = stringResource(R.string.flashlight_bubble_title),
                                summary = stringResource(R.string.flashlight_bubble_summary),
                                enabled = aicyEnabled,
                                startAction = { PreferenceIcon(Icons.Rounded.FlashlightOn, aicyEnabled) },
                            )
                            SwitchPreference(
                                checked = keepAwakeEnabled,
                                onCheckedChange = tapped(onKeepAwakeEnabledChange),
                                title = stringResource(R.string.keep_awake_bubble_title),
                                summary = stringResource(R.string.keep_awake_bubble_summary),
                                enabled = aicyEnabled,
                                startAction = { PreferenceIcon(Icons.Rounded.Visibility, aicyEnabled) },
                            )
                            SwitchPreference(
                                checked = appInstallEnabled,
                                onCheckedChange = tapped(onAppInstallEnabledChange),
                                title = stringResource(R.string.app_install_bubble_title),
                                summary = stringResource(R.string.app_install_bubble_summary),
                                enabled = aicyEnabled,
                                startAction = { PreferenceIcon(Icons.Rounded.InstallDesktop, aicyEnabled) },
                            )
                            SwitchPreference(
                                checked = earphoneEnabled,
                                onCheckedChange = tapped(onEarphoneEnabledChange),
                                title = stringResource(R.string.earphone_bubble_title),
                                summary = stringResource(R.string.earphone_bubble_summary),
                                enabled = aicyEnabled,
                                startAction = { PreferenceIcon(Icons.Rounded.Headset, aicyEnabled) },
                            )
                            // @author bomo 2026-10-18：耳机连接 → 选择打开哪个音乐 app（方案A：自动扫描已装 + 自定义）
                            EarphoneMusicSelector(
                                selectedPkg = earphoneMusicPkgs,
                                onSelectedPkgChange = onEarphoneMusicPkgsChange,
                                enabled = aicyEnabled && earphoneEnabled,
                            )
                        }
                    }
                    item(key = "message_bubbles") {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            SwitchPreference(
                                checked = wechatEnabled,
                                onCheckedChange = tapped(onWechatEnabledChange),
                                title = stringResource(R.string.wechat_bubble_title),
                                summary = stringResource(R.string.wechat_bubble_summary),
                                enabled = aicyEnabled,
                                startAction = { PreferenceIcon(Icons.Rounded.Message, aicyEnabled) },
                            )
                            SwitchPreference(
                                checked = qqEnabled,
                                onCheckedChange = tapped(onQqEnabledChange),
                                title = stringResource(R.string.qq_bubble_title),
                                summary = stringResource(R.string.qq_bubble_summary),
                                enabled = aicyEnabled,
                                startAction = { PreferenceIcon(Icons.Rounded.Comment, aicyEnabled) },
                            )
                        }
                    }
                    item(key = "link_bubbles") {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            SwitchPreference(
                                checked = linkEnabled,
                                onCheckedChange = tapped(onLinkEnabledChange),
                                title = stringResource(R.string.link_bubble_title),
                                summary = stringResource(R.string.link_bubble_summary),
                                enabled = aicyEnabled,
                                startAction = { PreferenceIcon(Icons.Rounded.ContentCopy, aicyEnabled) },
                            )
                            SwitchPreference(
                                checked = shareEnabled,
                                onCheckedChange = tapped(onShareEnabledChange),
                                title = stringResource(R.string.share_bubble_title),
                                summary = stringResource(R.string.share_bubble_summary),
                                enabled = aicyEnabled,
                                startAction = { PreferenceIcon(Icons.Rounded.Link, aicyEnabled) },
                            )
                        }
                    }
                    item(key = "bubble_pos_edit") {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            top.yukonga.miuix.kmp.basic.BasicComponent(
                                title = stringResource(R.string.bubble_pos_edit_title),
                                summary = stringResource(R.string.bubble_pos_edit_summary),
                                onClick = { launchBubblePosEdit(context) },
                                startAction = { PreferenceIcon(Icons.Rounded.ContentCopy) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * @author bomo 2026-10-19：耳机连接 → 选择打开哪个音乐/视频应用。
 * 下拉 = 内置主流音乐/视频清单 与本机已装应用比对，只展示「已安装的」；末项「自定义应用」。
 * 选中「自定义应用」→ 下方出现包名输入框（过滤换行，仅允许单包名）；
 * 切回列表某项 → 输入框消失、原自定义包名被覆盖失效。
 * selectedPkg 语义：内置清单内=选中项；非清单内且非空=自定义包名；空=未选择。
 */
private val MAIN_STREAM_MEDIA_PKGS = listOf(
    // 音乐
    "com.netease.cloudmusic", "com.tencent.qqmusic", "com.kugou.android",
    "com.sds.android.ttpod", "com.baidu.ting", "com.ximalaya.ting.android",
    "cn.kuwo.player", "com.tencent.qqplayer", "com.miui.player",
    "com.ting.mp3.android", "fm.qingting.qtradio", "com.duokan.phone.remotecontroller",
    // 视频/直播
    "tv.danmaku.bili", "com.bilibili.app.in", "com.tencent.qqlive", "com.qiyi.video",
    "com.youku.phone", "com.mgtv.mango", "com.ss.android.ugc.aweme", // 抖音
    "com.ss.android.ugc.live", "com.smile.gifmaker", // 快手/皮皮虾
    "com.duoduo.video", "com.xiaomi.videoplayer", "com.miui.video",
)

/** 内置主流清单 与本机已装应用比对，返回已安装的音视频包名。 */
fun scanInstalledMainStream(context: android.content.Context): List<String> {
    val pm = context.packageManager
    return MAIN_STREAM_MEDIA_PKGS.filter { pkg ->
        try {
            pm.getApplicationInfo(pkg, 0) != null
        } catch (_: Exception) {
            false
        }
    }
}

@Composable
private fun EarphoneMusicSelector(
    selectedPkg: String,
    onSelectedPkgChange: (String) -> Unit,
    enabled: Boolean,
) {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val noneLabel = stringResource(R.string.earphone_music_none_summary)
    val customLabel = stringResource(R.string.earphone_music_custom_label)
    val pickerTitle = stringResource(R.string.earphone_music_picker_title)
    // 内置清单比对已装（保序：音乐在前、视频在后 → 自动选第一个即「优先音乐，其次视频」）
    val mediaPkgs = remember(context) { scanInstalledMainStream(context) }
    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    // 首次且没配过：自动落一个默认。
    //   - 有已装音视频 → 自动选清单第一个（优先音乐）；
    //   - 什么音视频都没装 → 视为「自定义」，自动弹开应用选择。
    // @author bomo 用户要求去掉「未选择」项：要么真实选中一个，要么直接进自定义选择。
    val autoOpenedRef = remember { mutableStateOf(false) }
    val resolvedPkg = remember(selectedPkg, mediaPkgs) {
        if (selectedPkg.isNotEmpty()) selectedPkg else mediaPkgs.firstOrNull() ?: ""
    }
    LaunchedEffect(selectedPkg, mediaPkgs) {
        if (selectedPkg.isEmpty() && !autoOpenedRef.value) {
            autoOpenedRef.value = true
            if (mediaPkgs.isNotEmpty()) {
                onSelectedPkgChange(mediaPkgs.first())
            } else {
                pickerOpen = true
            }
        }
    }
    // 下拉选项：已装主流清单(label) + 末项「自定义应用」。去掉了「未选择」。
    val labels =
        remember(context, mediaPkgs, customLabel) {
            buildList {
                for (pkg in mediaPkgs) add(appLabel(context, pkg) ?: pkg)
                add(customLabel)
            }
        }
    // selectedPkg 已由 LaunchedEffect 兜底成非空（resolvedPkg），索引映射：
    // 在清单内 → mediaPkgs 序号；自定义 → labels 末位。
    val selIndex =
        when {
            resolvedPkg in mediaPkgs -> mediaPkgs.indexOf(resolvedPkg)
            else -> labels.lastIndex // 自定义
        }
    // @author bomo 2026-10-19 修「音乐/视频应用」比上面「耳机连接提醒」整体右移一块：
    // 这里原来外面套了一层 padding(horizontal = 16dp) 的 Column，而 preference 内部本来
    // 就有自己的边距，再包一层就多缩进了 16dp。去掉外层包裹，直接放到卡片里，和开关左对齐。
    OverlaySpinnerPreference(
        items = labels.map { DropdownItem(text = it) },
        selectedIndex = selIndex,
        title = stringResource(R.string.earphone_music_selector_title),
        summary =
            when {
                resolvedPkg.isEmpty() -> noneLabel // 极罕见：既无已装音视频又没选自定义
                resolvedPkg in mediaPkgs -> appLabel(context, resolvedPkg) ?: resolvedPkg
                else -> resolvedPkg // 自定义包名
            },
        enabled = enabled,
        startAction = { PreferenceIcon(Icons.Rounded.Headphones, enabled) },
        onSelectedIndexChange = { index ->
            when {
                index in mediaPkgs.indices -> onSelectedPkgChange(mediaPkgs[index])
                else -> {
                    // 选中「自定义应用」：打开应用选择弹层
                    pickerOpen = true
                }
            }
        },
    )
    // 自定义应用选择弹层（与扇形应用 / 黑名单共用同一个统一组件）
    if (pickerOpen) {
        val pickerApps =
            remember(pickerOpen, selectedPkg) {
                loadPickerApps(context, includePackages = listOf(selectedPkg))
            }
        AppPickerSheet(
            show = pickerOpen,
            title = pickerTitle,
            apps = pickerApps,
            selected = if (selectedPkg.isEmpty()) emptySet() else setOf(selectedPkg),
            multiSelect = false,
            onDismiss = { pickerOpen = false },
            onPick = { pkg ->
                onSelectedPkgChange(pkg)
                pickerOpen = false
            },
        )
    }
}

private fun appLabel(context: android.content.Context, pkg: String): String? =
    try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) {
        null
    }

private fun launchBubblePosEdit(context: android.content.Context) {
    try {
        val i = android.content.Intent(context, com.eta.aicybox.bubble.BubblePosEditActivity::class.java)
        i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
    } catch (_: Exception) {
    }
}
