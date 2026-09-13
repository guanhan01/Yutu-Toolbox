package com.mcp.toolbox.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 更新检查的状态。 */
sealed interface UpdateState {
    /** 尚未开始。 */
    data object Idle : UpdateState

    /** 请求进行中。 */
    data object Checking : UpdateState

    /** 已是最新版本。 */
    data class UpToDate(val current: String) : UpdateState

    /** 有新版本可用。 */
    data class Available(
        val current: String,
        val latest: String,
        val releaseUrl: String,
        val notes: String?,
    ) : UpdateState

    /** 检查失败（无网络、被限流等），不影响页面其余内容。 */
    data class Failed(val reason: String) : UpdateState
}

/**
 * 通过 GitHub Releases 检查新版本。
 *
 * 只发起一次 GET 请求，不携带任何设备标识或用户信息；任何失败都降级为 [UpdateState.Failed]，
 * 由界面显示为“检查失败”，不会阻塞或打断关于页的其他内容。
 */
object UpdateChecker {

    /** 项目主页，供关于页展示与跳转。 */
    const val PROJECT_URL = "https://github.com/guanhan01/Yutu-Toolbox"

    private const val LATEST_RELEASE_API =
        "https://api.github.com/repos/guanhan01/Yutu-Toolbox/releases/latest"

    private const val TIMEOUT_MS = 10_000

    suspend fun check(currentVersion: String): UpdateState = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "Yutu-Toolbox")
            }
            try {
                val code = conn.responseCode
                if (code != HttpURLConnection.HTTP_OK) {
                    return@runCatching UpdateState.Failed("HTTP $code")
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val tag = json.optString("tag_name").trim().removePrefix("v")
                val releaseUrl = json.optString("html_url")
                    .ifBlank { "$PROJECT_URL/releases" }
                when {
                    tag.isBlank() -> UpdateState.Failed("empty tag")
                    isNewer(tag, currentVersion) -> UpdateState.Available(
                        current = currentVersion,
                        latest = tag,
                        releaseUrl = releaseUrl,
                        notes = json.optString("body").takeIf { it.isNotBlank() },
                    )
                    else -> UpdateState.UpToDate(currentVersion)
                }
            } finally {
                conn.disconnect()
            }
        }.getOrElse { UpdateState.Failed(it.message ?: it::class.simpleName ?: "unknown") }
    }

    /**
     * [latest] 是否比 [current] 新。
     *
     * 按 `.` 与 `-` 分段做数值比较：每段取开头连续数字，取不到则记 0；
     * 缺失的段按 0 处理，因此 `1.2` 与 `1.2.0` 视为同一版本。
     * 形如 `1.0.0-beta1` 的预发布版本不会被判定为比 `1.0.0` 新。
     */
    fun isNewer(latest: String, current: String): Boolean {
        val a = parse(latest)
        val b = parse(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun parse(version: String): List<Int> =
        version.removePrefix("v")
            .split('.', '-', '+')
            .map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
}
