package com.eta.aicybox.ui

import android.os.Build
import android.widget.Toast
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
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SettingsBackupRestore
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.eta.aicybox.R
import com.eta.aicybox.framework.FrameworkConnectionIssue
import com.eta.aicybox.framework.FrameworkConnectionState
import com.eta.aicybox.framework.FrameworkConnectionStatus
import com.eta.aicybox.framework.ScopeRestartOutcome
import com.eta.aicybox.sideindex.config.ModuleConfig
import com.eta.aicybox.sideindex.config.PrefKeys
import com.eta.aicybox.sideindex.settings.PrefStore
import com.eta.aicybox.ui.component.TopBarBackdrop
import com.eta.aicybox.ui.component.captureForTopBar
import com.eta.aicybox.ui.component.rememberTopBarBackdrop
import com.eta.aicybox.ui.component.topBarContainerColor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * @author bomo 首页：授权与激活（服务框架状态 + Root 权限）、设备信息、重启作用域、开发者模式。
 * 原本散落在设置页的「框架服务 / 重启作用域 / 开发者模式」三项挪进这里，
 * 设置页（小窗 Tab）只保留小窗功能本体。
 */
@Composable
internal fun HomeScreen(
    state: FrameworkConnectionState,
    onRequestScopes: () -> Unit,
    onRestartScope: suspend () -> ScopeRestartOutcome,
    onDeveloperModeChange: (Boolean) -> Unit,
    /** @author bomo 2026-10-19 首页「恢复默认设置」（原在索引页，用户要求挪到首页）。 */
    onResetAllSettings: () -> Unit,
) {
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
                            title = stringResource(R.string.screen_home_title),
                            color = topBarColor,
                            scrollBehavior = scrollBehavior,
                        )
                    } else {
                        TopAppBar(
                            title = stringResource(R.string.screen_home_title),
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
                    verticalArrangement = Arrangement.spacedBy(HOME_CARD_SPACING),
                    contentPadding =
                        PaddingValues(
                            start = safeStart + centeredSide,
                            top = innerPadding.calculateTopPadding() + ScreenTopSpacing,
                            end = safeEnd + centeredSide,
                            bottom = innerPadding.calculateBottomPadding() + ScreenBottomSpacing,
                        ),
                    overscrollEffect = null,
                ) {
                    item(key = "activation") { ActivationCard(state, onRequestScopes) }
                    item(key = "device") { DeviceInfoCard() }
                    item(key = "restart") { HomeRestartPreference(onRestartScope) }
                    // @author bomo 2026-10-19 用户要求：恢复默认设置放「重启作用域进程」下面（原在索引页）。
                    item(key = "reset") { HomeResetPreference(onResetAllSettings) }
                    item(key = "developer") {
                        HomeDeveloperModePreference(state, onDeveloperModeChange)
                    }
                }
            }
        }
    }
}

/** 授权与激活卡片：服务框架（连接 / LSPosed 激活态）+ Root 权限。 */
@Composable
private fun ActivationCard(
    state: FrameworkConnectionState,
    onRequestScopes: () -> Unit,
) {
    val presentation = frameworkPresentation(state)
    val rootOk = rememberRootAvailable()
    Card(modifier = Modifier.fillMaxWidth()) {
        BasicComponent(
            title = stringResource(R.string.framework_service_title),
            summary = presentation.summary,
            endActions = {
                Text(
                    text = presentation.label,
                    color = MiuixTheme.colorScheme.onSurface,
                    style = MiuixTheme.textStyles.body2,
                )
            },
            startAction = { PreferenceIcon(Icons.Rounded.AccountTree) },
        )
        BasicComponent(
            title = stringResource(R.string.root_permission_title),
            summary = stringResource(R.string.root_permission_summary),
            endActions = {
                Text(
                    text =
                        stringResource(
                            if (rootOk) R.string.root_status_granted else R.string.root_status_denied,
                        ),
                    color = MiuixTheme.colorScheme.onSurface,
                    style = MiuixTheme.textStyles.body2,
                )
            },
            startAction = { PreferenceIcon(Icons.Rounded.Security) },
        )
        if (state.status == FrameworkConnectionStatus.Connected && state.missingScopes.isNotEmpty()) {
            val summary =
                if (state.isRequestingScope) stringResource(R.string.scope_requesting)
                else if (state.issue == FrameworkConnectionIssue.ScopeRequestFailed) {
                    stringResource(R.string.scope_request_failed)
                } else {
                    stringResource(R.string.scope_missing, state.missingScopes.joinToString(" · "))
                }
            BasicComponent(
                title = stringResource(R.string.scope_title),
                summary = summary,
                onClick = onRequestScopes,
                enabled = state.canRequestScope,
                startAction = { PreferenceIcon(Icons.Rounded.AccountTree, state.canRequestScope) },
            )
        }
    }
}

/** 设备信息卡片：设备型号 / 安卓版本 / 系统版本 / 软件版本。 */
@Composable
private fun DeviceInfoCard() {
    val context = LocalContext.current
    val versionName =
        remember(context) {
            runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }
                .getOrNull()
                ?.versionName
                ?: context.getString(R.string.framework_unknown_value)
        }
    Card(modifier = Modifier.fillMaxWidth()) {
        DeviceInfoRow(
            icon = Icons.Rounded.Smartphone,
            title = stringResource(R.string.device_model_title),
            value = Build.MODEL,
        )
        DeviceInfoRow(
            icon = Icons.Rounded.Devices,
            title = stringResource(R.string.android_version_title),
            value = "Android ${Build.VERSION.RELEASE}",
        )
        DeviceInfoRow(
            icon = Icons.Rounded.Info,
            title = stringResource(R.string.android_system_version_title),
            value = Build.DISPLAY,
        )
        DeviceInfoRow(
            icon = Icons.Rounded.BugReport,
            title = stringResource(R.string.app_version_title),
            value = versionName,
        )
    }
}

@Composable
private fun DeviceInfoRow(
    icon: ImageVector,
    title: String,
    value: String,
) {
    BasicComponent(
        title = title,
        summary = value,
        startAction = { PreferenceIcon(icon) },
    )
}

/** 首页的「重启作用域」按钮（原在设置页，挪到这里，行为不变）。 */
@Composable
private fun HomeRestartPreference(onRestartScope: suspend () -> ScopeRestartOutcome) {
    val scope = rememberCoroutineScope()
    var restarting by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<ScopeRestartOutcome?>(null) }
    val context = LocalContext.current
    LaunchedEffect(outcome) {
        val current = outcome ?: return@LaunchedEffect
        val message =
            when (current) {
                ScopeRestartOutcome.Restarted -> R.string.scope_restart_done
                ScopeRestartOutcome.StoppedWithoutConnection -> R.string.scope_restart_stopped
                ScopeRestartOutcome.Failed -> R.string.scope_restart_failed
            }
        Toast.makeText(context, context.getString(message), Toast.LENGTH_SHORT).show()
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        BasicComponent(
            title = stringResource(R.string.scope_restart_title),
            summary = stringResource(R.string.scope_restart_summary),
            onClick = {
                if (!restarting) {
                    restarting = true
                    outcome = null
                    scope.launch {
                        outcome = onRestartScope()
                        restarting = false
                    }
                }
            },
            enabled = !restarting,
            startAction = { PreferenceIcon(Icons.Rounded.PowerSettingsNew, !restarting) },
        )
    }
}

/**
 * 首页的开发模式开关（原在设置页环境卡片，挪到这里）。
 *
 * @author bomo 2026-10-19 用户要求「日志都归首页的开发模式控制」：这个开关同时写索引模块的
 * `log_verbose`，一个开关管住两套详细日志（小窗 = developerMode，索引 = logVerbose）。
 */
@Composable
private fun HomeDeveloperModePreference(
    state: FrameworkConnectionState,
    onDeveloperModeChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current.applicationContext
    Card(modifier = Modifier.fillMaxWidth()) {
        SwitchPreference(
            checked = state.settings.developerMode,
            onCheckedChange = { value ->
                onDeveloperModeChange(value)
                // 索引侧的详细日志跟着一起开关，避免两个地方各管一半。
                runCatching {
                    PrefStore.write(context) { it.putBoolean(PrefKeys.LOG_VERBOSE, value) }
                }
            },
            title = stringResource(R.string.developer_mode_title),
            summary = stringResource(R.string.developer_mode_summary),
            enabled = state.canChangeSettings,
            startAction = { PreferenceIcon(Icons.Rounded.BugReport, state.canChangeSettings) },
        )
    }
}

/**
 * @author bomo 2026-10-19 首页「恢复默认设置」：小窗 / 建议 / 索引三块设置一起回默认值。
 *
 * 分两步写：小窗与建议走 LSPosed 共享配置（[onResetAllSettings]），
 * 索引配置是本应用自己的 SP + Secure 通道（[PrefStore]），在这里直接清。
 */
@Composable
private fun HomeResetPreference(onResetAllSettings: () -> Unit) {
    val context = LocalContext.current
    var showDialog by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        BasicComponent(
            title = stringResource(R.string.settings_reset_title),
            summary = stringResource(R.string.settings_reset_summary),
            onClick = { showDialog = true },
            startAction = { PreferenceIcon(Icons.Rounded.SettingsBackupRestore) },
        )
    }
    OverlayDialog(
        show = showDialog,
        onDismissRequest = { showDialog = false },
        title = stringResource(R.string.settings_reset_title),
        summary = stringResource(R.string.settings_reset_message),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(
                text = stringResource(R.string.index_reset_cancel),
                onClick = { showDialog = false },
                modifier = Modifier.weight(1f),
            )
            TextButton(
                text = stringResource(R.string.index_reset_confirm),
                onClick = {
                    showDialog = false
                    onResetAllSettings()
                    val appContext = context.applicationContext
                    runCatching {
                        PrefStore.prefs(appContext).edit().clear().commit()
                        // 收藏计数存在 Secure（eta_sideindex_star）：恢复默认时一起清空
                        android.provider.Settings.Secure.putString(
                            appContext.contentResolver,
                            "eta_sideindex_star",
                            null,
                        )
                        // 默认值必须显式写回 SP 与 Secure：只清 SP 的话 write() 会把 Secure 里的旧配置铺回来
                        PrefStore.applyConfig(appContext, ModuleConfig())
                    }
                    Toast.makeText(
                        context,
                        context.getString(R.string.settings_reset_done),
                        Toast.LENGTH_SHORT,
                    ).show()
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 读取一次 Root（su）是否可用；探测方式与 ScopeRestarter 一致。 */
@Composable
private fun rememberRootAvailable(): Boolean {
    val context = LocalContext.current.applicationContext
    return remember(context) {
        runCatching {
            val p = ProcessBuilder("su", "-c", "id -u").redirectErrorStream(true).start()
            val finished = p.waitFor(3, TimeUnit.SECONDS)
            if (finished) {
                (p.inputStream.bufferedReader().readLine() ?: "").isNotBlank()
            } else {
                false
            }
        }.getOrDefault(false)
    }
}

private val HOME_CARD_SPACING = 12.dp