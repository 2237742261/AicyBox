package com.eta.aicybox.platform.coloros

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.eta.aicybox.config.PayChannel

/**
 * @author bomo 支付渠道 → 启动 Intent（2026-10-19）。
 *
 * **参数来源**：反编译本机 `/product/app/SmartSideBar/SmartSideBar.apk` 的
 * `WechatPayCodeTool` / `WechatScanTool` / `AlipayPayCodeTool` / `AlipayScanTool`
 * （ColorOS 侧边栏自带的「聚合支付」四个工具），保证与系统原厂行为逐字节一致：
 *
 * | 渠道 | Intent |
 * | --- | --- |
 * | 微信付款码 | action `com.tencent.mm.ui.ShortCutDispatchAction` + component
 *   `com.tencent.mm/.ui.ShortCutDispatchActivity` + extra
 *   `LauncherUI.Shortcut.LaunchType=launch_type_offline_wallet` |
 * | 微信扫一扫 | action `com.tencent.mm.action.BIZSHORTCUT` + 同一 component + extra
 *   `LauncherUI.From.Scaner.Shortcut=true`（原厂不设 component，靠 intent-filter 落到同一 Activity；
 *   这里显式设置更稳） |
 * | 支付宝付款码 | `ACTION_VIEW` + `alipays://platformapi/startapp?appId=20000056&chInfo=ch_oppoSide` |
 * | 支付宝扫一扫 | 同上，`appId=10000007` |
 *
 * ⚠️ 不要改成 `startActivity(intent)` 裸调用：单给 `windowingMode` 并不会变小窗，
 * 必须走 [ColorOsFreeformLauncher] 的 ColorOS 启动 Bundle（`mZoomLaunchFlags=4`）。
 */
internal object PayIntentFactory {
    const val PACKAGE_WECHAT = "com.tencent.mm"
    const val PACKAGE_ALIPAY = "com.eg.android.AlipayGphone"

    private const val WECHAT_SHORTCUT_ACTIVITY = "com.tencent.mm.ui.ShortCutDispatchActivity"
    private const val WECHAT_PAYCODE_ACTION = "com.tencent.mm.ui.ShortCutDispatchAction"
    private const val WECHAT_SCAN_ACTION = "com.tencent.mm.action.BIZSHORTCUT"
    private const val WECHAT_LAUNCH_TYPE_KEY = "LauncherUI.Shortcut.LaunchType"
    private const val WECHAT_PAYCODE_LAUNCH_TYPE = "launch_type_offline_wallet"
    private const val WECHAT_SCAN_EXTRA = "LauncherUI.From.Scaner.Shortcut"
    private const val ALIPAY_PAYCODE_APP_ID = "20000056"
    private const val ALIPAY_SCAN_APP_ID = "10000007"

    /** 渠道所属应用包名（图标、安装检测、未安装时提示都用它）。 */
    fun packageOf(channel: PayChannel): String =
        when (channel) {
            PayChannel.WechatPayCode, PayChannel.WechatScan -> PACKAGE_WECHAT
            PayChannel.AlipayPayCode, PayChannel.AlipayScan -> PACKAGE_ALIPAY
        }

    /** 渠道对应的应用是否已安装；未安装时不弹空窗、也不打异常。 */
    fun isAvailable(
        context: Context,
        channel: PayChannel,
    ): Boolean =
        runCatching {
            context.packageManager.getPackageInfo(packageOf(channel), 0)
        }.isSuccess

    fun build(channel: PayChannel): Intent {
        val intent =
            when (channel) {
                PayChannel.WechatPayCode ->
                    Intent(WECHAT_PAYCODE_ACTION)
                        .setComponent(ComponentName(PACKAGE_WECHAT, WECHAT_SHORTCUT_ACTIVITY))
                        .putExtra(WECHAT_LAUNCH_TYPE_KEY, WECHAT_PAYCODE_LAUNCH_TYPE)
                PayChannel.WechatScan ->
                    Intent(WECHAT_SCAN_ACTION)
                        .setComponent(ComponentName(PACKAGE_WECHAT, WECHAT_SHORTCUT_ACTIVITY))
                        .putExtra(WECHAT_SCAN_EXTRA, true)
                PayChannel.AlipayPayCode -> alipay(ALIPAY_PAYCODE_APP_ID)
                PayChannel.AlipayScan -> alipay(ALIPAY_SCAN_APP_ID)
            }
        return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun alipay(appId: String): Intent =
        Intent(
            Intent.ACTION_VIEW,
            Uri.parse("alipays://platformapi/startapp?appId=$appId&chInfo=ch_oppoSide"),
        ).setPackage(PACKAGE_ALIPAY)
}
