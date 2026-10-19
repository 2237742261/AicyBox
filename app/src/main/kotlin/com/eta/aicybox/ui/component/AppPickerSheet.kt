package com.eta.aicybox.ui.component

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.eta.aicybox.R
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.icon.basic.Search
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * @author bomo 2026-10-19 「选择应用」的统一弹层。
 *
 * 背景：索引页的「应用黑名单」原来跳一个自绘 View 的旧 Activity（AppPickerActivity），
 * 和「扇形应用」「耳机音乐应用」的选择弹层完全两个样式。用户要求统一，于是抽成这一份：
 *  - 顶部搜索框（带放大镜，Miuix TextField）；
 *  - 列表每行 = 应用图标 + 名称 + 包名，已选中的行右侧打勾；
 *  - [multiSelect] = true 时点击即切换勾选（黑名单），false 时点击直接回调选中项（单选）。
 */
internal data class PickerApp(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap?,
)

/**
 * 读取本机已安装的用户应用，按名称排序；图标顺手栅格化成 44dp 位图。
 *
 * 0.3.10：系统应用彻底过滤（`FLAG_SYSTEM` 之外再算上 `FLAG_UPDATED_SYSTEM_APP`——ColorOS 的
 * 预装应用被更新过之后只置后一个标志），并且要求「有启动入口」（没有 launcher intent 的
 * 服务/插件列在黑名单里没有意义）。
 *
 * @param includePackages 已经在名单里的包名：即使算系统应用 / 没有启动入口也照样列出，
 *   否则用户在这里取消不了它。
 */
internal fun loadPickerApps(
    context: Context,
    includePackages: Collection<String> = emptyList(),
): List<PickerApp> {
    val pm = context.packageManager
    val density = context.resources.displayMetrics.density
    val iconSize = (44 * density).toInt().coerceAtLeast(1)
    val apps =
        try {
            pm.getInstalledApplications(0)
        } catch (_: Exception) {
            return emptyList()
        }
    return apps
        .mapNotNull { info ->
            if (info.packageName !in includePackages) {
                if (isSystemApp(info)) return@mapNotNull null
                val launchable =
                    runCatching { pm.getLaunchIntentForPackage(info.packageName) }.getOrNull() != null
                if (!launchable) return@mapNotNull null
            }
            val label =
                runCatching { pm.getApplicationLabel(info).toString() }.getOrNull()
                    ?: return@mapNotNull null
            PickerApp(info.packageName, label, runCatching { appIcon(pm.getApplicationIcon(info), iconSize) }.getOrNull())
        }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
}

/**
 * 0.3.10：判断系统/预装应用。
 *
 * 只看 flags 不够：ColorOS 的 `com.coloros.*`、`com.oplus.*` 预装应用（例如「AI 拍一拍」
 * `com.coloros.ocrscanner`）既没有 `FLAG_SYSTEM` 也没有 `FLAG_UPDATED_SYSTEM_APP`，却装在
 * `/my_product`、`/product`、`/system_ext` 这些只读分区里。用户自己装的应用一定在 `/data/` 下，
 * 所以「APK 不在 /data/ 就算系统应用」最稳。
 */
private fun isSystemApp(info: ApplicationInfo): Boolean {
    val flagged =
        (info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
    if (flagged) return true
    val dir = info.sourceDir ?: return false
    return !dir.startsWith("/data/")
}

private fun appIcon(drawable: Drawable, size: Int): ImageBitmap {
    val bitmap =
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { target ->
            val canvas = Canvas(target)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
        }
    return bitmap.asImageBitmap()
}

/**
 * 统一的应用选择弹层。
 *
 * @param selected 已选中的包名集合（单选模式用来显示当前项）。
 * @param multiSelect true = 多选（点一下切换勾选，弹层不关）；false = 单选（点一下回调 [onPick]）。
 */
@Composable
internal fun AppPickerSheet(
    show: Boolean,
    title: String,
    apps: List<PickerApp>,
    selected: Set<String>,
    multiSelect: Boolean,
    onDismiss: () -> Unit,
    onToggle: (String) -> Unit = {},
    onPick: (String) -> Unit = {},
) {
    var query by rememberSaveable(show) { mutableStateOf("") }
    val candidates =
        remember(apps, query) {
            if (query.isBlank()) {
                apps
            } else {
                apps.filter {
                    it.label.contains(query, ignoreCase = true) ||
                        it.packageName.contains(query, ignoreCase = true)
                }
            }
        }
    OverlayBottomSheet(
        show = show,
        title = title,
        onDismissRequest = onDismiss,
    ) {
        Column {
            TextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                label = stringResource(R.string.search_apps_placeholder),
                useLabelAsPlaceholder = true,
                singleLine = true,
                leadingIcon = {
                    Icon(
                        imageVector = MiuixIcons.Basic.Search,
                        contentDescription = null,
                        modifier = Modifier.padding(start = 12.dp, end = 8.dp),
                    )
                },
            )
            LazyColumn(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                if (apps.isEmpty()) {
                    item(key = "apps_loading") {
                        BasicComponent(
                            title = stringResource(R.string.available_apps_loading),
                            summary = stringResource(R.string.available_apps_loading_summary),
                            enabled = false,
                        )
                    }
                } else if (candidates.isEmpty()) {
                    item(key = "apps_no_match") {
                        BasicComponent(
                            title = stringResource(R.string.no_matching_apps),
                            enabled = false,
                        )
                    }
                } else {
                    items(candidates, key = { it.packageName }) { app ->
                        BasicComponent(
                            title = app.label,
                            summary = app.packageName,
                            startAction = {
                                val icon = app.icon
                                if (icon != null) {
                                    Image(
                                        bitmap = icon,
                                        contentDescription = null,
                                        modifier = Modifier.size(44.dp).squircleClip(12.dp),
                                    )
                                }
                            },
                            endActions =
                                if (app.packageName in selected) {
                                    {
                                        Icon(
                                            imageVector = MiuixIcons.Basic.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp),
                                            tint = MiuixTheme.colorScheme.primary,
                                        )
                                    }
                                } else {
                                    null
                                },
                            onClick = {
                                if (multiSelect) {
                                    onToggle(app.packageName)
                                } else {
                                    onPick(app.packageName)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
