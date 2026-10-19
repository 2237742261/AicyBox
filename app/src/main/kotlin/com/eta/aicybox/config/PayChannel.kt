package com.eta.aicybox.config

/**
 * @author bomo 轮盘「支付」条目可选的渠道（2026-10-19）。
 *
 * [storedValue] 刻意与 ColorOS 侧边栏原厂工具的别名后缀保持一致
 * （`coloros_ep_tool_wechat_paycode` → `wechat_paycode`），便于对照排查；
 * 但执行**不走**侧边栏工具，而是模块自建 Intent（见 `PayIntentFactory`）：
 * 原厂 `AlipayPayCodeTool.handle()` 在多窗口且支付宝已在前台时会直接放弃启动
 * （日志 `block in split screen mode, alipay is already opened`），
 * 且原厂工具一律普通全屏启动，拿不到小窗。
 */
internal enum class PayChannel(
    val storedValue: String,
    /** 支付栏/设置页的显示名（system_server 里取不到模块资源，直接常量）。 */
    val displayLabel: String,
) {
    WechatPayCode("wechat_paycode", "微信付款"),
    AlipayPayCode("alipay_paycode", "支付宝付款"),
    WechatScan("wechat_scan", "微信扫一扫"),
    AlipayScan("alipay_scan", "支付宝扫一扫"),
    ;

    companion object {
        val DEFAULT: PayChannel = WechatPayCode

        /** 默认排序：两个付款码在前、扫一扫在后。 */
        val DEFAULT_ORDER: List<PayChannel> =
            listOf(WechatPayCode, AlipayPayCode, WechatScan, AlipayScan)

        /** 未知值（旧配置、手改配置）一律回落到默认渠道，绝不抛异常。 */
        fun fromStoredValue(value: String?): PayChannel =
            entries.firstOrNull { channel -> channel.storedValue == value } ?: DEFAULT

        /**
         * 解析「聚合支付方式排序」配置（逗号分隔的 [storedValue]）。
         *
         * 缺项、重复项、脏值都会被补齐成完整 4 项 —— 支付栏永远有 4 个图标可点，
         * 不会因为配置残缺出现空格子。
         */
        fun parseOrder(value: String?): List<PayChannel> {
            val parsed =
                value
                    ?.split(',')
                    ?.mapNotNull { raw ->
                        val id = raw.trim()
                        entries.firstOrNull { channel -> channel.storedValue == id }
                    }.orEmpty()
            return (parsed + DEFAULT_ORDER).distinct()
        }

        fun encodeOrder(order: List<PayChannel>): String =
            parseOrder(order.joinToString(",") { channel -> channel.storedValue })
                .joinToString(",") { channel -> channel.storedValue }
    }
}
