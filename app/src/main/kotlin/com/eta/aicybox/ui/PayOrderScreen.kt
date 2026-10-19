package com.eta.aicybox.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.eta.aicybox.R
import com.eta.aicybox.config.PayChannel
import com.eta.aicybox.framework.FrameworkConnectionState
import com.eta.aicybox.ui.component.TopBarBackdrop
import com.eta.aicybox.ui.component.captureForTopBar
import com.eta.aicybox.ui.component.rememberTopBarBackdrop
import com.eta.aicybox.ui.component.topBarContainerColor
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * @author bomo 2026-10-19 「聚合支付方式排序」页。
 *
 * 顺序同时决定两件事：
 * 1. 轮盘点「聚合支付」时**默认打开哪一项**（永远是最上面那个）；
 * 2. 小窗下方支付栏里图标的排列（从左到右）。
 *
 * 用上下箭头而不是拖拽：这页只有 4 项、也不常改，拖拽的代码量与误触成本都不划算。
 */
@Composable
internal fun PayOrderScreen(
    state: FrameworkConnectionState,
    onBack: () -> Unit,
    onOrderChange: (List<PayChannel>) -> Unit,
) {
    val order = PayChannel.parseOrder(state.settings.payOrder)
    val backdrop = rememberTopBarBackdrop()
    val topBarColor = topBarContainerColor(backdrop)
    val scrollBehavior = MiuixScrollBehavior()
    val title = stringResource(R.string.pay_order_screen_title)
    val navigationIcon: @Composable () -> Unit = {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.back_action),
                modifier = Modifier.size(24.dp),
                tint = MiuixTheme.colorScheme.onSurface,
            )
        }
    }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWide = maxWidth >= WideWindowMinWidth
        Scaffold(
            contentWindowInsets =
                WindowInsets.systemBars
                    .union(WindowInsets.displayCutout)
                    .union(WindowInsets.ime),
            topBar = {
                TopBarBackdrop(backdrop) {
                    if (isWide) {
                        SmallTopAppBar(
                            title = title,
                            color = topBarColor,
                            scrollBehavior = scrollBehavior,
                            navigationIcon = navigationIcon,
                        )
                    } else {
                        TopAppBar(
                            title = title,
                            color = topBarColor,
                            scrollBehavior = scrollBehavior,
                            navigationIcon = navigationIcon,
                        )
                    }
                }
            },
        ) { innerPadding ->
            Box(modifier = Modifier.fillMaxSize().captureForTopBar(backdrop)) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .consumeWindowInsets(innerPadding)
                            .padding(innerPadding)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = ScreenHorizontalMargin, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.pay_order_hint),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                    Card(modifier = Modifier.fillMaxWidth()) {
                        order.forEachIndexed { index, channel ->
                            val canMoveUp = index > 0
                            val canMoveDown = index < order.lastIndex
                            BasicComponent(
                                title = channel.displayLabel,
                                summary = stringResource(R.string.pay_order_position, index + 1),
                                enabled = state.canChangeSettings,
                                startAction = {
                                    PreferenceIcon(Icons.Rounded.Payments, state.canChangeSettings)
                                },
                                endActions = {
                                    IconButton(
                                        onClick = {
                                            if (canMoveUp) {
                                                onOrderChange(swap(order, index, index - 1))
                                            }
                                        },
                                        enabled = canMoveUp && state.canChangeSettings,
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.KeyboardArrowUp,
                                            contentDescription = stringResource(R.string.pay_order_move_up),
                                            modifier = Modifier.size(22.dp),
                                            tint = MiuixTheme.colorScheme.onSurface,
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            if (canMoveDown) {
                                                onOrderChange(swap(order, index, index + 1))
                                            }
                                        },
                                        enabled = canMoveDown && state.canChangeSettings,
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.KeyboardArrowDown,
                                            contentDescription = stringResource(R.string.pay_order_move_down),
                                            modifier = Modifier.size(22.dp),
                                            tint = MiuixTheme.colorScheme.onSurface,
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 交换两项；越界时原样返回（按钮已禁用，这里只是兜底）。 */
private fun swap(
    order: List<PayChannel>,
    from: Int,
    to: Int,
): List<PayChannel> {
    if (from !in order.indices || to !in order.indices) return order
    return order.toMutableList().apply {
        val item = this[from]
        this[from] = this[to]
        this[to] = item
    }
}
