package com.eta.aicybox.config

import android.content.ComponentName
import android.content.SharedPreferences

internal data class ModuleSettingsSnapshot(
    val enabled: Boolean = ModulePreferences.DEFAULT_ENABLED,
    val leftCornerEnabled: Boolean = ModulePreferences.DEFAULT_CORNER_ENABLED,
    val rightCornerEnabled: Boolean = ModulePreferences.DEFAULT_CORNER_ENABLED,
    val cornerTriggerRangeDp: Int = ModulePreferences.DEFAULT_CORNER_TRIGGER_RANGE_DP,
    /** @author bomo 触发热区形状（见 [TriggerShape]）；默认扇形，即改造前的原有行为。 */
    val triggerShape: TriggerShape = TriggerShape.DEFAULT,
    /** @author bomo 触发热区横向长度 dp（三角形使用）；未配置时沿用扇形半径值。 */
    val triggerHorizontalDp: Int = ModulePreferences.DEFAULT_CORNER_TRIGGER_RANGE_DP,
    /** @author bomo 触发热区纵向高度 dp（三角形使用）；未配置时沿用扇形半径值。 */
    val triggerVerticalDp: Int = ModulePreferences.DEFAULT_CORNER_TRIGGER_RANGE_DP,
    /** @author bomo 「全部」面板整体缩放百分比（见 `ModulePreferences.KEY_PANEL_SCALE_PERCENT`）。 */
    val panelScalePercent: Int = ModulePreferences.DEFAULT_PANEL_SCALE_PERCENT,
    /** @author bomo 角落轮盘整体缩放百分比（见 `ModulePreferences.KEY_RADIAL_SCALE_PERCENT`）。 */
    val radialScalePercent: Int = ModulePreferences.DEFAULT_RADIAL_SCALE_PERCENT,
    /** @author bomo 2026-10-19 轮盘切换图标震动开关 + 强度百分比（0~100）。 */
    val radialVibrateEnabled: Boolean = ModulePreferences.DEFAULT_RADIAL_VIBRATE_ENABLED,
    val radialVibrateStrength: Int = ModulePreferences.DEFAULT_RADIAL_VIBRATE_STRENGTH,
    val pinsSaved: Boolean = false,
    val pinnedComponents: List<ComponentName> = emptyList(),
    val innerPinsSaved: Boolean = false,
    val innerPinnedComponents: List<ComponentName> = emptyList(),
    val outsideTapCloseMode: OutsideTapCloseMode =
        ModulePreferences.DEFAULT_OUTSIDE_TAP_CLOSE_MODE,
    val handleSwipeUpToMiniEnabled: Boolean =
        ModulePreferences.DEFAULT_HANDLE_SWIPE_UP_TO_MINI_ENABLED,
    val pauseInLandscape: Boolean = ModulePreferences.DEFAULT_PAUSE_IN_LANDSCAPE,
    val pauseInGameMode: Boolean = ModulePreferences.DEFAULT_PAUSE_IN_GAME_MODE,
    /** @author bomo 开发模式（诊断日志总开关）；默认关，见 [ModulePreferences.KEY_DEVELOPER_MODE]。 */
    val developerMode: Boolean = ModulePreferences.DEFAULT_DEVELOPER_MODE,
    /** @author bomo 气泡功能开关（SystemUI 注入用）。 */
    val aicyBubblesEnabled: Boolean = ModulePreferences.DEFAULT_AICY_BUBBLES_ENABLED,
    val flashlightBubbleEnabled: Boolean = ModulePreferences.DEFAULT_FLASHLIGHT_BUBBLE_ENABLED,
    val keepAwakeBubbleEnabled: Boolean = ModulePreferences.DEFAULT_KEEPAWAKE_BUBBLE_ENABLED,
    val wechatBubbleEnabled: Boolean = ModulePreferences.DEFAULT_WECHAT_BUBBLE_ENABLED,
    val qqBubbleEnabled: Boolean = ModulePreferences.DEFAULT_QQ_BUBBLE_ENABLED,
    val appInstallBubbleEnabled: Boolean = ModulePreferences.DEFAULT_APP_INSTALL_BUBBLE_ENABLED,
    val earphoneBubbleEnabled: Boolean = ModulePreferences.DEFAULT_EARPHONE_BUBBLE_ENABLED,

    /** @author bomo 2026-10-18：耳机气泡自定义音乐应用列表（每行一个包名）。 */
    val earphoneMusicPkgs: String = ModulePreferences.DEFAULT_EARPHONE_MUSIC_PKGS,
    val linkBubbleEnabled: Boolean = ModulePreferences.DEFAULT_LINK_BUBBLE_ENABLED,
    val shareBubbleEnabled: Boolean = ModulePreferences.DEFAULT_SHARE_BUBBLE_ENABLED,
    val browserBubbleEnabled: Boolean = ModulePreferences.DEFAULT_BROWSER_BUBBLE_ENABLED,
    /** @author bomo 气泡位置（dp，相对屏幕左下角）。 */
    val bubblePosXDp: Int = ModulePreferences.DEFAULT_BUBBLE_POS_X_DP,
    val bubblePosYDp: Int = ModulePreferences.DEFAULT_BUBBLE_POS_Y_DP,

    /** @author bomo 2026-10-19 聚合支付方式排序（逗号分隔的渠道 id，见 [PayChannel.parseOrder]）。 */
    val payOrder: String = ModulePreferences.DEFAULT_PAY_ORDER,
) {
    fun isPausedByEnvironment(landscape: Boolean, gameMode: Boolean): Boolean =
        (pauseInLandscape && landscape) || (pauseInGameMode && gameMode)

    fun writeTo(editor: SharedPreferences.Editor): SharedPreferences.Editor {
        editor
            .putBoolean(ModulePreferences.KEY_MODULE_ENABLED, enabled)
            .putBoolean(ModulePreferences.KEY_LEFT_CORNER_ENABLED, leftCornerEnabled)
            .putBoolean(ModulePreferences.KEY_RIGHT_CORNER_ENABLED, rightCornerEnabled)
            .putBoolean(ModulePreferences.KEY_PAUSE_IN_LANDSCAPE, pauseInLandscape)
            .putBoolean(ModulePreferences.KEY_PAUSE_IN_GAME_MODE, pauseInGameMode)
            .putBoolean(ModulePreferences.KEY_DEVELOPER_MODE, developerMode)
            .putBoolean(ModulePreferences.KEY_AICY_BUBBLES_ENABLED, aicyBubblesEnabled)
            .putBoolean(ModulePreferences.KEY_FLASHLIGHT_BUBBLE_ENABLED, flashlightBubbleEnabled)
            .putBoolean(ModulePreferences.KEY_KEEPAWAKE_BUBBLE_ENABLED, keepAwakeBubbleEnabled)
            .putBoolean(ModulePreferences.KEY_WECHAT_BUBBLE_ENABLED, wechatBubbleEnabled)
            .putBoolean(ModulePreferences.KEY_QQ_BUBBLE_ENABLED, qqBubbleEnabled)
            .putBoolean(ModulePreferences.KEY_APP_INSTALL_BUBBLE_ENABLED, appInstallBubbleEnabled)
            .putBoolean(ModulePreferences.KEY_EARPHONE_BUBBLE_ENABLED, earphoneBubbleEnabled)
            .putString(ModulePreferences.KEY_EARPHONE_MUSIC_PKGS, earphoneMusicPkgs)
            .putBoolean(ModulePreferences.KEY_LINK_BUBBLE_ENABLED, linkBubbleEnabled)
            .putBoolean(ModulePreferences.KEY_SHARE_BUBBLE_ENABLED, shareBubbleEnabled)
            .putBoolean(ModulePreferences.KEY_BROWSER_BUBBLE_ENABLED, browserBubbleEnabled)
            .putInt(ModulePreferences.KEY_BUBBLE_POS_X_DP, bubblePosXDp)
            .putInt(ModulePreferences.KEY_BUBBLE_POS_Y_DP, bubblePosYDp)
            .putString(
                ModulePreferences.KEY_PAY_ORDER,
                PayChannel.encodeOrder(PayChannel.parseOrder(payOrder)),
            )
            .putInt(
                ModulePreferences.KEY_CORNER_TRIGGER_RANGE_DP,
                ModulePreferences.coerceCornerTriggerRangeDp(cornerTriggerRangeDp),
            )
            .putInt(
                ModulePreferences.KEY_TRIGGER_SHAPE,
                triggerShape.storedValue,
            )
            .putInt(
                ModulePreferences.KEY_TRIGGER_HORIZONTAL_DP,
                ModulePreferences.coerceTriggerExtentDp(triggerHorizontalDp),
            )
            .putInt(
                ModulePreferences.KEY_TRIGGER_VERTICAL_DP,
                ModulePreferences.coerceTriggerExtentDp(triggerVerticalDp),
            )
            .putInt(
                ModulePreferences.KEY_PANEL_SCALE_PERCENT,
                ModulePreferences.coercePanelScalePercent(panelScalePercent),
            )
            .putInt(
                ModulePreferences.KEY_RADIAL_SCALE_PERCENT,
                ModulePreferences.coerceRadialScalePercent(radialScalePercent),
            )
            .putBoolean(
                ModulePreferences.KEY_RADIAL_VIBRATE_ENABLED,
                radialVibrateEnabled,
            )
            .putInt(
                ModulePreferences.KEY_RADIAL_VIBRATE_STRENGTH,
                ModulePreferences.coerceRadialVibrateStrength(radialVibrateStrength),
            )
            .putInt(
                ModulePreferences.KEY_OUTSIDE_TAP_CLOSE_MODE,
                outsideTapCloseMode.storedValue,
            )
            .putBoolean(
                ModulePreferences.KEY_HANDLE_SWIPE_UP_TO_MINI_ENABLED,
                handleSwipeUpToMiniEnabled,
            )
        if (pinsSaved) {
            editor.putString(
                ModulePreferences.KEY_CORNER_PINS,
                encodePinnedComponents(pinnedComponents),
            )
        } else {
            editor.remove(ModulePreferences.KEY_CORNER_PINS)
        }
        if (innerPinsSaved) {
            editor.putString(
                ModulePreferences.KEY_CORNER_INNER_PINS,
                encodeComponents(innerPinnedComponents, ModulePreferences.MAX_INNER_APPS),
            )
        } else {
            editor.remove(ModulePreferences.KEY_CORNER_INNER_PINS)
        }
        return editor
    }

    companion object {
        fun readFrom(preferences: SharedPreferences): ModuleSettingsSnapshot {
            val enabled =
                preferences.getBoolean(
                    ModulePreferences.KEY_MODULE_ENABLED,
                    ModulePreferences.DEFAULT_ENABLED,
                )
            val leftEnabled =
                preferences.getBoolean(
                    ModulePreferences.KEY_LEFT_CORNER_ENABLED,
                    ModulePreferences.DEFAULT_CORNER_ENABLED,
                )
            val rightEnabled =
                preferences.getBoolean(
                    ModulePreferences.KEY_RIGHT_CORNER_ENABLED,
                    ModulePreferences.DEFAULT_CORNER_ENABLED,
                )
            val cornerTriggerRangeDp =
                ModulePreferences.coerceCornerTriggerRangeDp(
                    preferences.getInt(
                        ModulePreferences.KEY_CORNER_TRIGGER_RANGE_DP,
                        ModulePreferences.DEFAULT_CORNER_TRIGGER_RANGE_DP,
                    ),
                )
            // @author bomo 触发热区：形状默认扇形（即改造前的行为）；横/纵向长度未配置时
            // 沿用扇形半径值，这样从扇形切到三角形时热区尺度不会突变。
            val triggerShape =
                TriggerShape.fromStoredValue(
                    preferences.getInt(
                        ModulePreferences.KEY_TRIGGER_SHAPE,
                        TriggerShape.DEFAULT.storedValue,
                    ),
                )
            fun storedTriggerExtentDp(key: String): Int =
                ModulePreferences.coerceTriggerExtentDp(
                    if (preferences.contains(key)) {
                        preferences.getInt(key, cornerTriggerRangeDp)
                    } else {
                        cornerTriggerRangeDp
                    },
                )
            val triggerHorizontalDp =
                storedTriggerExtentDp(ModulePreferences.KEY_TRIGGER_HORIZONTAL_DP)
            val triggerVerticalDp =
                storedTriggerExtentDp(ModulePreferences.KEY_TRIGGER_VERTICAL_DP)
            val panelScalePercent =
                ModulePreferences.coercePanelScalePercent(
                    preferences.getInt(
                        ModulePreferences.KEY_PANEL_SCALE_PERCENT,
                        ModulePreferences.DEFAULT_PANEL_SCALE_PERCENT,
                    ),
                )
            val radialScalePercent =
                ModulePreferences.coerceRadialScalePercent(
                    preferences.getInt(
                        ModulePreferences.KEY_RADIAL_SCALE_PERCENT,
                        ModulePreferences.DEFAULT_RADIAL_SCALE_PERCENT,
                    ),
                )
            val radialVibrateEnabled =
                preferences.getBoolean(
                    ModulePreferences.KEY_RADIAL_VIBRATE_ENABLED,
                    ModulePreferences.DEFAULT_RADIAL_VIBRATE_ENABLED,
                )
            val radialVibrateStrength =
                ModulePreferences.coerceRadialVibrateStrength(
                    preferences.getInt(
                        ModulePreferences.KEY_RADIAL_VIBRATE_STRENGTH,
                        ModulePreferences.DEFAULT_RADIAL_VIBRATE_STRENGTH,
                    ),
                )
            val outsideTapCloseMode =
                OutsideTapCloseMode.fromStoredValue(
                    preferences.getInt(
                        ModulePreferences.KEY_OUTSIDE_TAP_CLOSE_MODE,
                        ModulePreferences.DEFAULT_OUTSIDE_TAP_CLOSE_MODE.storedValue,
                    ),
                )
            val handleSwipeUpToMiniEnabled =
                preferences.getBoolean(
                    ModulePreferences.KEY_HANDLE_SWIPE_UP_TO_MINI_ENABLED,
                    ModulePreferences.DEFAULT_HANDLE_SWIPE_UP_TO_MINI_ENABLED,
                )
            val pinsSaved = preferences.contains(ModulePreferences.KEY_CORNER_PINS)
            val innerPinsSaved = preferences.contains(ModulePreferences.KEY_CORNER_INNER_PINS)
            val pins =
                if (pinsSaved) {
                    decodePinnedComponents(
                        preferences.getString(ModulePreferences.KEY_CORNER_PINS, "") ?: "",
                    )
                } else {
                    emptyList()
                }
            return ModuleSettingsSnapshot(
                enabled = enabled,
                innerPinsSaved = innerPinsSaved,
                innerPinnedComponents =
                    if (innerPinsSaved) {
                        decodeComponents(
                            preferences.getString(ModulePreferences.KEY_CORNER_INNER_PINS, "") ?: "",
                        )
                    } else {
                        emptyList()
                    },
                leftCornerEnabled = leftEnabled,
                rightCornerEnabled = rightEnabled,
                cornerTriggerRangeDp = cornerTriggerRangeDp,
                triggerShape = triggerShape,
                triggerHorizontalDp = triggerHorizontalDp,
                triggerVerticalDp = triggerVerticalDp,
                panelScalePercent = panelScalePercent,
                radialScalePercent = radialScalePercent,
                radialVibrateEnabled = radialVibrateEnabled,
                radialVibrateStrength = radialVibrateStrength,
                pinsSaved = pinsSaved,
                pinnedComponents = pins,
                outsideTapCloseMode = outsideTapCloseMode,
                handleSwipeUpToMiniEnabled = handleSwipeUpToMiniEnabled,
                pauseInLandscape = preferences.getBoolean(
                    ModulePreferences.KEY_PAUSE_IN_LANDSCAPE,
                    ModulePreferences.DEFAULT_PAUSE_IN_LANDSCAPE,
                ),
                pauseInGameMode = preferences.getBoolean(
                    ModulePreferences.KEY_PAUSE_IN_GAME_MODE,
                    ModulePreferences.DEFAULT_PAUSE_IN_GAME_MODE,
                ),
                developerMode = preferences.getBoolean(
                    ModulePreferences.KEY_DEVELOPER_MODE,
                    ModulePreferences.DEFAULT_DEVELOPER_MODE,
                ),
                aicyBubblesEnabled = preferences.getBoolean(
                    ModulePreferences.KEY_AICY_BUBBLES_ENABLED,
                    ModulePreferences.DEFAULT_AICY_BUBBLES_ENABLED,
                ),
                flashlightBubbleEnabled = preferences.getBoolean(
                    ModulePreferences.KEY_FLASHLIGHT_BUBBLE_ENABLED,
                    ModulePreferences.DEFAULT_FLASHLIGHT_BUBBLE_ENABLED,
                ),
                keepAwakeBubbleEnabled = preferences.getBoolean(
                    ModulePreferences.KEY_KEEPAWAKE_BUBBLE_ENABLED,
                    ModulePreferences.DEFAULT_KEEPAWAKE_BUBBLE_ENABLED,
                ),
                wechatBubbleEnabled = preferences.getBoolean(
                    ModulePreferences.KEY_WECHAT_BUBBLE_ENABLED,
                    ModulePreferences.DEFAULT_WECHAT_BUBBLE_ENABLED,
                ),
                qqBubbleEnabled = preferences.getBoolean(
                    ModulePreferences.KEY_QQ_BUBBLE_ENABLED,
                    ModulePreferences.DEFAULT_QQ_BUBBLE_ENABLED,
                ),
                appInstallBubbleEnabled = preferences.getBoolean(
                    ModulePreferences.KEY_APP_INSTALL_BUBBLE_ENABLED,
                    ModulePreferences.DEFAULT_APP_INSTALL_BUBBLE_ENABLED,
                ),
                earphoneBubbleEnabled = preferences.getBoolean(
                    ModulePreferences.KEY_EARPHONE_BUBBLE_ENABLED,
                    ModulePreferences.DEFAULT_EARPHONE_BUBBLE_ENABLED,
                ),
                earphoneMusicPkgs = preferences.getString(
                    ModulePreferences.KEY_EARPHONE_MUSIC_PKGS,
                    ModulePreferences.DEFAULT_EARPHONE_MUSIC_PKGS,
                ) ?: ModulePreferences.DEFAULT_EARPHONE_MUSIC_PKGS,
                linkBubbleEnabled = preferences.getBoolean(
                    ModulePreferences.KEY_LINK_BUBBLE_ENABLED,
                    ModulePreferences.DEFAULT_LINK_BUBBLE_ENABLED,
                ),
                shareBubbleEnabled = preferences.getBoolean(
                    ModulePreferences.KEY_SHARE_BUBBLE_ENABLED,
                    ModulePreferences.DEFAULT_SHARE_BUBBLE_ENABLED,
                ),
                browserBubbleEnabled = preferences.getBoolean(
                    ModulePreferences.KEY_BROWSER_BUBBLE_ENABLED,
                    ModulePreferences.DEFAULT_BROWSER_BUBBLE_ENABLED,
                ),
                bubblePosXDp = preferences.getInt(
                    ModulePreferences.KEY_BUBBLE_POS_X_DP,
                    ModulePreferences.DEFAULT_BUBBLE_POS_X_DP,
                ),
                bubblePosYDp = preferences.getInt(
                    ModulePreferences.KEY_BUBBLE_POS_Y_DP,
                    ModulePreferences.DEFAULT_BUBBLE_POS_Y_DP,
                ),
                payOrder =
                    PayChannel.encodeOrder(
                        PayChannel.parseOrder(
                            preferences.getString(
                                ModulePreferences.KEY_PAY_ORDER,
                                ModulePreferences.DEFAULT_PAY_ORDER,
                            ),
                        ),
                    ),
            )
        }

        fun encodePinnedComponents(components: List<ComponentName>): String =
            encodeComponents(components, ModulePreferences.MAX_PINNED_APPS)

        fun encodeComponents(components: List<ComponentName>, limit: Int): String =
            components
                .asSequence()
                .distinct()
                .take(limit)
                .joinToString("\n", transform = ComponentName::flattenToString)

        fun decodeComponents(value: String): List<ComponentName> =
            PinnedComponentCodec
                .decodeRaw(value, Int.MAX_VALUE)
                .asSequence()
                .mapNotNull(ComponentName::unflattenFromString)
                .toList()

        fun decodePinnedComponents(value: String): List<ComponentName> =
            PinnedComponentCodec
                .decodeRaw(value)
                .asSequence()
                .mapNotNull(ComponentName::unflattenFromString)
                .toList()
    }
}
