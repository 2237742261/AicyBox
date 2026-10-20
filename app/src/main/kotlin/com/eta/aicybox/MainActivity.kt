package com.eta.aicybox

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eta.aicybox.ui.FlymeFreeformNavHost
import com.eta.aicybox.ui.theme.FlymeFreeformTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        window.isNavigationBarContrastEnforced = false

        val repository =
            (application as FlymeFreeformApplication).frameworkConnectionRepository
        val appRepository =
            (application as FlymeFreeformApplication).launcherAppRepository
        setContent {
            val state = repository.state.collectAsStateWithLifecycle().value
            val apps = appRepository.apps.collectAsStateWithLifecycle().value
            FlymeFreeformTheme {
                FlymeFreeformNavHost(
                    state = state,
                    apps = apps,
                    onModuleEnabledChange = repository::setModuleEnabled,
                    onCornerCallModeChange = repository::setCornerCallMode,
                    onCornerTriggerRangeChange = repository::setCornerTriggerRangeDp,
                    onTriggerShapeChange = repository::setTriggerShape,
                    onTriggerHorizontalDpChange = repository::setTriggerHorizontalDp,
                    onTriggerVerticalDpChange = repository::setTriggerVerticalDp,
                    onPanelScaleChange = repository::setPanelScalePercent,
                    onRadialScaleChange = repository::setRadialScalePercent,
                    onRadialVibrateEnabledChange = repository::setRadialVibrateEnabled,
                    onRadialVibrateStrengthChange = repository::setRadialVibrateStrength,
                    onOutsideTapCloseModeChange = repository::setOutsideTapCloseMode,
                    onHandleSwipeUpToMiniEnabledChange =
                        repository::setHandleSwipeUpToMiniEnabled,
                    onPayOrderChange = repository::setPayOrder,
                    onPauseInLandscapeChange = repository::setPauseInLandscape,
                    onPauseInGameModeChange = repository::setPauseInGameMode,
                    onDeveloperModeChange = repository::setDeveloperMode,
                    onResetAllSettings = repository::resetAllSettings,
                    onAicyBubblesChange = repository::setAicyBubblesEnabled,
                    onFlashlightBubbleChange = repository::setFlashlightBubbleEnabled,
                    onKeepAwakeBubbleChange = repository::setKeepAwakeBubbleEnabled,
                    onWechatBubbleChange = repository::setWechatBubbleEnabled,
                    onQqBubbleChange = repository::setQqBubbleEnabled,
                    onAppInstallBubbleChange = repository::setAppInstallBubbleEnabled,
                    onEarphoneBubbleChange = repository::setEarphoneBubbleEnabled,
                    onEarphoneMusicPkgsChange = repository::setEarphoneMusicPkgs,
                    onLinkBubbleChange = repository::setLinkBubbleEnabled,
                    onShareBubbleChange = repository::setShareBubbleEnabled,
                    onBrowserBubbleChange = repository::setBrowserBubbleEnabled,
                    onBrowserPkgChange = repository::setBrowserPkg,
                    onBubblePosChange = repository::setBubblePos,
                    onRequestScopes = repository::requestMissingScopes,
                    onRestartScope = repository::restartScope,
                    onPinnedComponentsChange = repository::setPinnedComponents,
                    onInnerPinnedComponentsChange = repository::setInnerPinnedComponents,
                )
            }
        }
    }
}
