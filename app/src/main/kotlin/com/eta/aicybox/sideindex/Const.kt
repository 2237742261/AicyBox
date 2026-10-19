package com.eta.aicybox.sideindex

object Const {
    const val MODULE_PACKAGE = "com.eta.aicybox"
    const val PREFS_NAME = "sideindex_config"
    const val LOG_TAG = "EtaSideIndex"
    const val SYSTEMUI_PACKAGE = "com.android.systemui"

    /** 配置读取通道（跨应用 ContentProvider，作为 Secure 通道的备用） */
    const val PROVIDER_AUTHORITY = "com.eta.aicybox.sideindex.config"
    const val PROVIDER_METHOD_GET = "get"
    const val PROVIDER_KEY_PAYLOAD = "payload"
    const val PROVIDER_URI = "content://$PROVIDER_AUTHORITY"
}
