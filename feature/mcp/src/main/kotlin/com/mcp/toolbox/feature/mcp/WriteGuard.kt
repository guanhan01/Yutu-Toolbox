package com.mcp.toolbox.feature.mcp

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 写类工具的全局开关。
 *
 * 这个开关原先挂在「内置 MCP Server」的配置里（`BuiltInMcpServer.Config.allowWrite`），
 * 因为当时写入能力主要面向外部 MCP 客户端。内置 Server 移除后，写能力只剩 AI 一侧在
 * 使用，但**门槛不能跟着消失**：`file.write`、`linux.file_write`、`apk.*`、`db.*` 的写
 * 一旦默认放开，等于让模型无需任何授权就能改动手机文件与数据。
 *
 * 所以这里保留同一个开关、同一份语义（默认关闭），只把配置从 Server 挪出来，
 * 入口移到「AI 设置」页。旧版本写在 `mcp-server` 配置里的值会在首次读取时迁移过来，
 * 已在旧版开启过写入的用户不会被静默关掉。
 */
object WriteGuard {

    private const val PREFS = "write-guard"
    private const val KEY_ALLOW = "allowWrite"

    /** 旧内置 Server 的存储位置，仅用于一次性迁移。 */
    private const val LEGACY_PREFS = "mcp-server"
    private const val LEGACY_KEY = "config"

    private val _allowed = MutableStateFlow(false)
    val allowed: StateFlow<Boolean> = _allowed

    /** 同步读取，供工具 handler（非挂起函数）使用。 */
    val allowedNow: Boolean get() = _allowed.value

    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val value = if (prefs.contains(KEY_ALLOW)) {
            prefs.getBoolean(KEY_ALLOW, false)
        } else {
            readLegacy(context)
        }
        _allowed.value = value
        if (!prefs.contains(KEY_ALLOW)) prefs.edit().putBoolean(KEY_ALLOW, value).apply()
        loaded = true
    }

    fun set(context: Context, allow: Boolean) {
        _allowed.value = allow
        loaded = true
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ALLOW, allow).apply()
    }

    /** 旧版 `mcp-server` 配置里的 allowWrite；不存在时按关闭处理。 */
    private fun readLegacy(context: Context): Boolean = runCatching {
        val raw = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
            .getString(LEGACY_KEY, null) ?: return@runCatching false
        org.json.JSONObject(raw).optBoolean("allowWrite", false)
    }.getOrDefault(false)

    /** 统一的拒绝文案。原先散在 9 个工具集里，且指路的是已被移除的 MCP 页面。 */
    fun requireWrite() {
        if (!_allowed.value) {
            error("写操作未开启：请在「AI 设置」里打开「允许写入」后重试")
        }
    }
}
