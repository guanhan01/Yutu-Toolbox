package com.mcp.toolbox.feature.web

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 浏览器 UA 模式。 */
enum class UserAgentMode { MOBILE, DESKTOP, CUSTOM }

const val UA_MOBILE: String =
    "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/126.0.0.0 Mobile Safari/537.36"

const val UA_DESKTOP: String =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/126.0.0.0 Safari/537.36"

data class WebTab(
    val id: String,
    val url: String = "",
    val title: String = "新标签页",
    val progress: Float = 0f,
    val loading: Boolean = false,
)

data class Bookmark(val title: String, val url: String, val createdAt: Long = System.currentTimeMillis())

data class HistoryEntry(val title: String, val url: String, val visitedAt: Long)

data class WebPageSettings(
    val userAgentMode: UserAgentMode = UserAgentMode.MOBILE,
    val customUserAgent: String = "",
    val javaScriptEnabled: Boolean = true,
    val blockImages: Boolean = false,
    val adBlockEnabled: Boolean = true,
    val blockRules: List<String> = DEFAULT_BLOCK_RULES,
)

val DEFAULT_BLOCK_RULES: List<String> = listOf(
    "doubleclick.net",
    "googleadservices.com",
    "googlesyndication.com",
    "adservice.google.com",
    "ads.example.com",
    "tracking.example.com",
)

data class WebUiState(
    val tabs: List<WebTab> = listOf(WebTab(id = "tab-1")),
    val activeTabId: String = "tab-1",
    val addressText: String = "",
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val pageSettings: WebPageSettings = WebPageSettings(),
    val bookmarks: List<Bookmark> = emptyList(),
    val history: List<HistoryEntry> = emptyList(),
    val readerMode: Boolean = false,
    val readerTitle: String = "",
    val readerText: String = "",
    val readerFontSize: Float = 16f,
) {
    val activeTab: WebTab get() = tabs.firstOrNull { it.id == activeTabId } ?: tabs.first()
}

/**
 * 网页模块状态容器。
 *
 * 只负责「标签元数据 + 页面偏好 + 书签/历史」的可持久化状态；
 * WebView 实例由 UI 层持有（WebView 必须绑定 Activity/主题上下文，不适合放进 VM）。
 */
class WebViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("web_module", Application.MODE_PRIVATE)

    private val _state = MutableStateFlow(
        WebUiState(
            pageSettings = loadSettings(),
            bookmarks = loadBookmarks(),
            history = loadHistory(),
        ),
    )
    val state: StateFlow<WebUiState> = _state.asStateFlow()

    // ---------- 标签 ----------

    fun newTab(): String {
        val tab = WebTab(id = "tab-" + UUID.randomUUID().toString().take(8))
        _state.value = _state.value.let {
            it.copy(tabs = it.tabs + tab, activeTabId = tab.id, addressText = "", readerMode = false)
        }
        return tab.id
    }

    fun selectTab(id: String) {
        val tab = _state.value.tabs.firstOrNull { it.id == id } ?: return
        _state.value = _state.value.copy(
            activeTabId = id,
            addressText = tab.url,
            canGoBack = false,
            canGoForward = false,
            readerMode = false,
            readerText = "",
        )
    }

    fun closeTab(id: String) {
        val current = _state.value
        if (current.tabs.size <= 1) {
            // 最后一个标签不关闭，只清空
            _state.value = current.copy(
                tabs = listOf(WebTab(id = current.activeTabId)),
                addressText = "",
                canGoBack = false,
                canGoForward = false,
                readerMode = false,
                readerText = "",
            )
            return
        }
        val remaining = current.tabs.filterNot { it.id == id }
        val nextActive = if (current.activeTabId == id) remaining.last().id else current.activeTabId
        _state.value = current.copy(
            tabs = remaining,
            activeTabId = nextActive,
            addressText = remaining.first { it.id == nextActive }.url,
            readerMode = false,
            readerText = "",
        )
    }

    fun onPageStarted(tabId: String, url: String) {
        updateTab(tabId) { it.copy(url = url, loading = true, progress = 0.05f) }
        if (tabId == _state.value.activeTabId) {
            _state.value = _state.value.copy(addressText = url, readerMode = false, readerText = "")
        }
    }

    fun onProgress(tabId: String, progress: Float) {
        updateTab(tabId) { it.copy(progress = progress, loading = progress < 1f) }
    }

    fun onPageFinished(tabId: String, url: String, title: String) {
        updateTab(tabId) { it.copy(url = url, title = title.ifBlank { url }, loading = false, progress = 1f) }
        recordHistory(title.ifBlank { url }, url)
    }

    fun onTitle(tabId: String, title: String) {
        updateTab(tabId) { it.copy(title = title.ifBlank { it.url }) }
    }

    fun setNavigationState(canBack: Boolean, canForward: Boolean, currentUrl: String?) {
        _state.value = _state.value.copy(
            canGoBack = canBack,
            canGoForward = canForward,
            addressText = currentUrl?.takeIf { it.isNotBlank() } ?: _state.value.addressText,
        )
    }

    fun setAddressText(text: String) {
        _state.value = _state.value.copy(addressText = text)
    }

    fun syncAddressFromTab(tab: WebTab) {
        _state.value = _state.value.copy(addressText = tab.url)
    }

    private fun updateTab(tabId: String, transform: (WebTab) -> WebTab) {
        _state.value = _state.value.copy(
            tabs = _state.value.tabs.map { if (it.id == tabId) transform(it) else it },
        )
    }

    // ---------- 页面偏好 ----------

    fun updateSettings(transform: (WebPageSettings) -> WebPageSettings) {
        val next = transform(_state.value.pageSettings)
        _state.value = _state.value.copy(pageSettings = next)
        persistSettings(next)
    }

    fun addBlockRule(rule: String) {
        val cleaned = rule.trim().lowercase().removePrefix("http://").removePrefix("https://").trim('/')
        if (cleaned.isBlank()) return
        updateSettings { s -> if (s.blockRules.contains(cleaned)) s else s.copy(blockRules = s.blockRules + cleaned) }
    }

    fun removeBlockRule(rule: String) {
        updateSettings { s -> s.copy(blockRules = s.blockRules - rule) }
    }

    /** 真实生效的广告拦截判断：按域名后缀匹配。 */
    fun isBlocked(url: String): Boolean {
        val settings = _state.value.pageSettings
        if (!settings.adBlockEnabled) return false
        val host = runCatching { java.net.URI(url).host ?: return false }.getOrNull() ?: return false
        return settings.blockRules.any { host == it || host.endsWith(".$it") }
    }

    // ---------- 书签 / 历史 ----------

    fun toggleBookmark(title: String, url: String) {
        if (url.isBlank()) return
        val current = _state.value
        val exists = current.bookmarks.any { it.url == url }
        val next = if (exists) current.bookmarks.filterNot { it.url == url }
        else listOf(Bookmark(title.ifBlank { url }, url)) + current.bookmarks
        _state.value = current.copy(bookmarks = next)
        persistBookmarks(next)
    }

    fun isBookmarked(url: String): Boolean = _state.value.bookmarks.any { it.url == url }

    fun removeBookmark(url: String) {
        val next = _state.value.bookmarks.filterNot { it.url == url }
        _state.value = _state.value.copy(bookmarks = next)
        persistBookmarks(next)
    }

    fun clearHistory() {
        _state.value = _state.value.copy(history = emptyList())
        prefs.edit().remove(KEY_HISTORY).apply()
    }

    private fun recordHistory(title: String, url: String) {
        if (url.isBlank()) return
        val entry = HistoryEntry(title, url, System.currentTimeMillis())
        val next = (listOf(entry) + _state.value.history.filterNot { it.url == url }).take(120)
        _state.value = _state.value.copy(history = next)
        persistHistory(next)
    }

    // ---------- 阅读模式 ----------

    fun setReader(title: String, text: String) {
        _state.value = _state.value.copy(readerMode = true, readerTitle = title, readerText = text)
    }

    fun closeReader() {
        _state.value = _state.value.copy(readerMode = false, readerText = "", readerTitle = "")
    }

    fun setReaderFontSize(size: Float) {
        _state.value = _state.value.copy(readerFontSize = size)
    }

    // ---------- 持久化（SharedPreferences + JSON，无第三方依赖） ----------

    private fun persistSettings(settings: WebPageSettings) {
        prefs.edit()
            .putString(KEY_UA_MODE, settings.userAgentMode.name)
            .putString(KEY_UA_CUSTOM, settings.customUserAgent)
            .putBoolean(KEY_JS, settings.javaScriptEnabled)
            .putBoolean(KEY_BLOCK_IMAGES, settings.blockImages)
            .putBoolean(KEY_ADBLOCK, settings.adBlockEnabled)
            .putString(KEY_RULES, JSONArray(settings.blockRules).toString())
            .apply()
    }

    private fun loadSettings(): WebPageSettings {
        val rules = prefs.getString(KEY_RULES, null)?.let { raw ->
            runCatching {
                val array = JSONArray(raw)
                (0 until array.length()).map { array.getString(it) }
            }.getOrNull()
        } ?: DEFAULT_BLOCK_RULES
        return WebPageSettings(
            userAgentMode = prefs.getString(KEY_UA_MODE, null)
                ?.let { runCatching { UserAgentMode.valueOf(it) }.getOrNull() } ?: UserAgentMode.MOBILE,
            customUserAgent = prefs.getString(KEY_UA_CUSTOM, "").orEmpty(),
            javaScriptEnabled = prefs.getBoolean(KEY_JS, true),
            blockImages = prefs.getBoolean(KEY_BLOCK_IMAGES, false),
            adBlockEnabled = prefs.getBoolean(KEY_ADBLOCK, true),
            blockRules = rules,
        )
    }

    private fun persistBookmarks(list: List<Bookmark>) {
        val array = JSONArray()
        list.forEach { array.put(JSONObject().put("t", it.title).put("u", it.url).put("c", it.createdAt)) }
        prefs.edit().putString(KEY_BOOKMARKS, array.toString()).apply()
    }

    private fun loadBookmarks(): List<Bookmark> {
        val raw = prefs.getString(KEY_BOOKMARKS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                Bookmark(obj.getString("t"), obj.getString("u"), obj.optLong("c"))
            }
        }.getOrDefault(emptyList())
    }

    private fun persistHistory(list: List<HistoryEntry>) {
        val array = JSONArray()
        list.forEach { array.put(JSONObject().put("t", it.title).put("u", it.url).put("v", it.visitedAt)) }
        prefs.edit().putString(KEY_HISTORY, array.toString()).apply()
    }

    private fun loadHistory(): List<HistoryEntry> {
        val raw = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                HistoryEntry(obj.getString("t"), obj.getString("u"), obj.optLong("v"))
            }
        }.getOrDefault(emptyList())
    }

    private companion object {
        const val KEY_UA_MODE = "ua_mode"
        const val KEY_UA_CUSTOM = "ua_custom"
        const val KEY_JS = "js_enabled"
        const val KEY_BLOCK_IMAGES = "block_images"
        const val KEY_ADBLOCK = "adblock"
        const val KEY_RULES = "block_rules"
        const val KEY_BOOKMARKS = "bookmarks"
        const val KEY_HISTORY = "history"
    }
}
