package com.mcp.toolbox.feature.capture

import android.content.Context

/** 抓包配置持久化：只保存本地配置，不含任何抓包内容。 */
object CapturePrefs {
    private const val FILE = "capture-prefs"
    private const val KEY_MODE = "mode"
    private const val KEY_PACKAGES = "packages"
    private const val KEY_DNS_ONLY = "dns_only"
    private const val KEY_BODY_LIMIT = "body_limit"
    private const val KEY_HTTPS_DECRYPT = "https_decrypt"

    fun load(context: Context): CaptureConfig {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val mode = runCatching {
            CaptureMode.valueOf(prefs.getString(KEY_MODE, CaptureMode.ALL.name) ?: CaptureMode.ALL.name)
        }.getOrDefault(CaptureMode.ALL)
        return CaptureConfig(
            mode = mode,
            packages = prefs.getStringSet(KEY_PACKAGES, emptySet()) ?: emptySet(),
            dnsOnly = prefs.getBoolean(KEY_DNS_ONLY, false),
            bodyLimitBytes = prefs.getInt(KEY_BODY_LIMIT, 64 * 1024),
            httpsDecrypt = prefs.getBoolean(KEY_HTTPS_DECRYPT, false),
        )
    }

    fun save(context: Context, config: CaptureConfig) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_MODE, config.mode.name)
            .putStringSet(KEY_PACKAGES, config.packages)
            .putBoolean(KEY_DNS_ONLY, config.dnsOnly)
            .putInt(KEY_BODY_LIMIT, config.bodyLimitBytes)
            .putBoolean(KEY_HTTPS_DECRYPT, config.httpsDecrypt)
            .apply()
    }
}
