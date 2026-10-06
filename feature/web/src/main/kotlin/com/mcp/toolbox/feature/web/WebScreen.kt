package com.mcp.toolbox.feature.web

import com.mcp.toolbox.core.design.component.MiuixIconRes
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.http.SslError
import android.os.Environment
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mcp.toolbox.core.design.icon.AgentIcons
import com.mcp.toolbox.core.design.component.MiuixButton
import com.mcp.toolbox.core.design.component.MiuixButtonSize
import com.mcp.toolbox.core.design.component.MiuixButtonVariant
import com.mcp.toolbox.core.design.component.MiuixEmptyState
import com.mcp.toolbox.core.design.component.MiuixIcon
import com.mcp.toolbox.core.design.component.MiuixIconButton
import com.mcp.toolbox.core.design.component.MiuixLinearProgress
import com.mcp.toolbox.core.design.component.MiuixListItem
import com.mcp.toolbox.core.design.component.MiuixMenuItem
import com.mcp.toolbox.core.design.component.MiuixMenuDivider
import com.mcp.toolbox.core.design.component.MiuixOverflowMenu
import com.mcp.toolbox.core.design.component.MiuixSegmentedButton
import com.mcp.toolbox.core.design.component.MiuixSlider
import com.mcp.toolbox.core.design.component.MiuixSuperSwitch
import com.mcp.toolbox.core.design.component.MiuixTextField
import com.mcp.toolbox.core.design.component.MiuixText
import com.mcp.toolbox.core.design.component.MiuixTopAppBar
import com.mcp.toolbox.core.design.component.MiuixBottomSheet
import com.mcp.toolbox.core.design.component.miuixClickable
import com.mcp.toolbox.core.design.component.rememberMiuixPressState
import com.mcp.toolbox.core.design.theme.MiuixTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 网页模块：真实 WebView 多标签浏览器。
 *
 * 真实能力：多标签（每标签一个独立 WebView 实例）、前进后退、地址栏跳转（含搜索回退）、
 * 页面进度、UA 切换（移动/桌面/自定义，真实写入 WebSettings）、无图模式（blockNetworkImage）、
 * JS 开关、广告拦截（shouldInterceptRequest 域名后缀匹配）、下载（交给系统 DownloadManager 真实下载，
 * 列表从 DownloadManager 查询）、分享（系统分享面板）、阅读模式（evaluateJavascript 抽取正文）、
 * 书签与历史（本地持久化）。
 */
@Composable
fun WebScreen(
    onBack: () -> Unit,
    onToast: (String) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 进来就直接打开的地址（AI 回答里的链接走这里）。
     *
     * 用 key 触发而不是在 composition 里直接调：`loadUrl` 依赖 WebView 已创建，
     * 而 WebView 是在 AndroidView 的 factory 里才建出来的。等页面组合完成后再加载，
     * 否则第一次打开会静默失败——地址栏有内容、页面却是空白。
     */
    initialUrl: String? = null,
    /**
     * [initialUrl] 已经交给 WebView 之后回调。
     *
     * 由宿主在这里「消费掉」那个待打开地址。
     * **不能**让宿主在传入后就立刻清空：那会让本函数的 `LaunchedEffect` 因 key
     * 变化而重启，未跑完的加载被取消——表现就是「点了链接没反应」。
     */
    onInitialUrlLoaded: () -> Unit = {},
    /**
     * [initialUrl] 的「第几次请求」标识。
     *
     * 只传 URL 不够：用户两次点**同一个**链接时地址没变，
     * effect 的 key 不变、不会重新加载，看起来就是「第二次点了没反应」。
     * 让宿主每次请求都给一个递增的值，重复地址也能触发。
     */
    initialUrlKey: Any? = null,
) {
    val vm: WebViewModel = viewModel()
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val colors = MiuixTheme.colors
    val webViews = remember { mutableStateMapOf<String, WebView>() }
    val scope = rememberCoroutineScope()
    val activeTab = state.activeTab

    var sheetVisible by remember { mutableStateOf(false) }
    var overflowVisible by remember { mutableStateOf(false) }
    var menuAnchor by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var startQuery by remember { mutableStateOf("") }
    var downloads by remember { mutableStateOf<List<DownloadRecord>>(emptyList()) }

    // 已关闭标签的 WebView 需要真正销毁，否则内存泄漏
    LaunchedEffect(state.tabs.map { it.id }) {
        val alive = state.tabs.map { it.id }.toSet()
        webViews.keys.toList().filterNot { alive.contains(it) }.forEach { dead ->
            webViews.remove(dead)?.let { wv ->
                (wv.parent as? ViewGroup)?.removeView(wv)
                wv.stopLoading()
                wv.destroy()
            }
        }
    }

    LaunchedEffect(sheetVisible) {
        if (sheetVisible) downloads = loadSystemDownloads(context)
    }

    fun loadUrl(input: String) {
        val url = normalizeUrl(input) ?: return
        vm.setAddressText(url)
        webViews[activeTab.id]?.loadUrl(url)
    }

    // 带地址进来时自动打开一次。
    // key 用 initialUrl：换了地址就重新加载；同一地址重组不会重复触发。
    LaunchedEffect(initialUrl, initialUrlKey, activeTab.id) {
        val target = initialUrl?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        // 轮询等 WebView 出现：它由 AndroidView 的 factory 创建，
        // 可能比本 effect 晚一两帧。只等一帧是不够的（实测偶尔仍然落空）。
        repeat(WEBVIEW_WAIT_FRAMES) {
            if (webViews[activeTab.id] != null) {
                loadUrl(target)
                onInitialUrlLoaded()
                return@LaunchedEffect
            }
            withFrameNanos { }
        }
    }

    fun currentWebView(): WebView? = webViews[activeTab.id]

    fun readCurrentPage() {
        val wv = currentWebView() ?: run {
            onToast("页面尚未加载完成")
            return
        }
        extractArticle(wv) { title, text ->
            if (text.isBlank()) {
                onToast("未提取到正文，可能不是文章页")
            } else {
                vm.setReader(title.ifBlank { activeTab.title }, text)
                onToast("阅读模式已开启")
            }
        }
    }

    Column(modifier = modifier.fillMaxSize().background(colors.background)) {
        MiuixTopAppBar(
            title = "网页",
            navigationIconRes = AgentIcons.ic_chevron_left,
            onNavigationClick = onBack,
            actions = {
                MiuixIconButton(
                    iconRes = AgentIcons.ic_plus,
                    contentDescription = "新建标签页",
                    onClick = {
                        val id = vm.newTab()
                        webViews[id] // 触发下帧创建
                        onToast("已新建标签页")
                    },
                )
                Box {
                                        // 记下按钮矩形给菜单用：anchor 必填，不传位置会退回父布局（菜单跑偏）。
                    Box(modifier = Modifier.onGloballyPositioned { menuAnchor = it.boundsInWindow() }) {
                        MiuixIconButton(
                                                iconRes = AgentIcons.ic_more_vert,
                                                contentDescription = "更多",
                                                onClick = { overflowVisible = true },
                                            )
                    }
                    MiuixOverflowMenu(expanded = overflowVisible, onDismiss = { overflowVisible = false }, anchor = menuAnchor) {
                        MiuixMenuItem(text = "分享当前页", iconRes = AgentIcons.ic_share, onClick = {
                            overflowVisible = false
                            shareUrl(context, activeTab.url)
                        })
                        MiuixMenuItem(text = "在系统浏览器打开", iconRes = AgentIcons.ic_globe, onClick = {
                            overflowVisible = false
                            openExternally(context, activeTab.url)
                        })
                        MiuixMenuDivider()
                        MiuixMenuItem(text = "清除浏览历史", iconRes = AgentIcons.ic_trash, danger = true, onClick = {
                            overflowVisible = false
                            vm.clearHistory()
                            onToast("浏览历史已清除")
                        })
                    }
                }
            },
        )

        // 标签条：缩略标题 + 关闭 + 新建
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            state.tabs.forEach { tab ->
                val selected = tab.id == state.activeTabId
                val press = rememberMiuixPressState()
                Row(
                    modifier = Modifier
                        .width(150.dp)
                        .height(48.dp)
                        .background(
                            color = if (selected) colors.primaryContainer else colors.surfaceVariant,
                            shape = RoundedCornerShape(MiuixTheme.radius.md),
                        )
                        .miuixClickable(press, true) { vm.selectTab(tab.id) }
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MiuixText(
                        text = tab.title,
                        modifier = Modifier.weight(1f),
                        style = MiuixTheme.typography.labelLarge,
                        color = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    MiuixIconButton(
                        iconRes = AgentIcons.ic_close,
                        contentDescription = "关闭标签",
                        onClick = {
                            webViews.remove(tab.id)?.let { wv ->
                                (wv.parent as? ViewGroup)?.removeView(wv)
                                wv.destroy()
                            }
                            vm.closeTab(tab.id)
                        },
                        buttonSize = 26.dp,
                        iconSize = 15.dp,
                    )
                }
            }
        }

        // 地址栏
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MiuixIconButton(
                iconRes = AgentIcons.ic_chevron_left,
                contentDescription = "后退",
                enabled = state.canGoBack,
                onClick = { currentWebView()?.goBack() },
            )
            MiuixIconButton(
                iconRes = AgentIcons.ic_arrow_right,
                contentDescription = "前进",
                enabled = state.canGoForward,
                onClick = { currentWebView()?.goForward() },
            )
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .background(colors.surfaceVariant, RoundedCornerShape(percent = 50))
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MiuixIconRes(
                    iconRes = if (activeTab.url.startsWith("https")) AgentIcons.ic_lock else AgentIcons.ic_globe,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = colors.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                BasicTextField(
                    value = state.addressText,
                    onValueChange = { vm.setAddressText(it) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = MiuixTheme.typography.bodyMedium.copy(color = colors.onSurface),
                    cursorBrush = SolidColor(colors.primary),
                    decorationBox = { inner ->
                        if (state.addressText.isEmpty()) {
                            MiuixText(
                                text = "搜索或输入网址",
                                style = MiuixTheme.typography.bodyMedium,
                                color = colors.onSurfaceVariant,
                            )
                        }
                        inner()
                    },
                )
                if (state.addressText.isNotEmpty()) {
                    MiuixIconButton(
                        iconRes = AgentIcons.ic_close,
                        contentDescription = "清空",
                        onClick = { vm.setAddressText("") },
                        buttonSize = 26.dp,
                        iconSize = 15.dp,
                    )
                }
            }
            MiuixIconButton(
                iconRes = if (activeTab.loading) AgentIcons.ic_close else AgentIcons.ic_refresh,
                contentDescription = if (activeTab.loading) "停止" else "刷新",
                onClick = {
                    val wv = currentWebView() ?: return@MiuixIconButton
                    if (activeTab.loading) wv.stopLoading() else wv.reload()
                },
            )
            MiuixIconButton(
                iconRes = AgentIcons.ic_search,
                contentDescription = "前往",
                onClick = { loadUrl(state.addressText) },
            )
        }

        if (activeTab.loading) {
            MiuixLinearProgress(progress = activeTab.progress, height = 3.dp)
        } else {
            Spacer(Modifier.height(3.dp))
        }

        // 页面区
        Box(Modifier.weight(1f)) {
            key(activeTab.id) {
                AndroidView(
                    factory = { ctx ->
                        val wv = createConfiguredWebView(ctx, activeTab.id, vm, onToast)
                        (wv.parent as? ViewGroup)?.removeView(wv)
                        webViews[activeTab.id] = wv
                        wv
                    },
                    modifier = Modifier.fillMaxSize().clipToBounds(),
                    update = { wv -> applyPageSettings(wv, vm.state.value.pageSettings) },
                    onRelease = { wv -> (wv.parent as? ViewGroup)?.removeView(wv) },
                )
            }
            if (activeTab.url.isBlank()) {
                StartPage(
                    query = startQuery,
                    onQueryChange = { startQuery = it },
                    bookmarks = state.bookmarks,
                    onOpen = { url ->
                        startQuery = ""
                        loadUrl(url)
                    },
                )
            }

            if (state.readerMode) {
                ReaderPane(
                    title = state.readerTitle,
                    text = state.readerText,
                    fontSize = state.readerFontSize,
                    onFontSizeChange = vm::setReaderFontSize,
                    onClose = vm::closeReader,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // 底部工具条
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface)
                .padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MiuixIconButton(
                iconRes = AgentIcons.ic_chevron_left,
                contentDescription = "后退",
                enabled = state.canGoBack,
                onClick = { currentWebView()?.goBack() },
            )
            MiuixIconButton(
                iconRes = AgentIcons.ic_arrow_right,
                contentDescription = "前进",
                enabled = state.canGoForward,
                onClick = { currentWebView()?.goForward() },
            )
            MiuixIconButton(
                iconRes = if (vm.isBookmarked(activeTab.url)) AgentIcons.ic_bookmark else AgentIcons.ic_bookmark,
                contentDescription = "书签",
                onClick = {
                    if (activeTab.url.isBlank()) {
                        onToast("当前页面为空")
                    } else {
                        vm.toggleBookmark(activeTab.title, activeTab.url)
                        onToast(if (vm.isBookmarked(activeTab.url)) "已加入书签" else "已移除书签")
                    }
                },
            )
            MiuixIconButton(
                iconRes = AgentIcons.ic_download,
                contentDescription = "下载",
                onClick = {
                    sheetVisible = true
                    scope.launch { downloads = loadSystemDownloads(context) }
                },
            )
            MiuixIconButton(
                iconRes = AgentIcons.ic_share,
                contentDescription = "分享",
                onClick = { shareUrl(context, activeTab.url) },
            )
            MiuixIconButton(
                iconRes = AgentIcons.ic_file_text,
                contentDescription = "阅读模式",
                onClick = { readCurrentPage() },
            )
            MiuixIconButton(
                iconRes = AgentIcons.ic_settings,
                contentDescription = "浏览器设置",
                onClick = { sheetVisible = true },
            )
        }
    }

    BrowserSheet(
        visible = sheetVisible,
        onDismiss = { sheetVisible = false },
        vm = vm,
        state = state,
        downloads = downloads,
        currentUrl = activeTab.url,
        currentTitle = activeTab.title,
        onOpen = { url ->
            sheetVisible = false
            loadUrl(url)
        },
        onToast = onToast,
    )
}

/** 起始页：搜索框 + 书签快捷入口。 */
@Composable
private fun StartPage(
    query: String,
    onQueryChange: (String) -> Unit,
    bookmarks: List<Bookmark>,
    onOpen: (String) -> Unit,
) {
    val colors = MiuixTheme.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(32.dp))
        MiuixText(text = "网页", style = MiuixTheme.typography.displaySmall, color = colors.primary)
        Spacer(Modifier.height(6.dp))
        MiuixText(
            text = "输入网址或搜索关键词，支持多标签与广告拦截",
            style = MiuixTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        MiuixTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = "搜索或输入网址",
            leadingIconRes = AgentIcons.ic_search,
        )
        Spacer(Modifier.height(12.dp))
        MiuixButton(
            text = "打开",
            onClick = { if (query.isNotBlank()) onOpen(query) },
            modifier = Modifier.fillMaxWidth(),
            enabled = query.isNotBlank(),
        )
        Spacer(Modifier.height(28.dp))
        if (bookmarks.isNotEmpty()) {
            MiuixText(
                text = "书签",
                modifier = Modifier.fillMaxWidth(),
                style = MiuixTheme.typography.titleSmall,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            bookmarks.take(8).forEach { bookmark ->
                MiuixListItem(
                    title = bookmark.title,
                    subtitle = bookmark.url,
                    leadingIconRes = AgentIcons.ic_bookmark,
                    onClick = { onOpen(bookmark.url) },
                )
            }
        } else {
            MiuixEmptyState(
                title = "还没有书签",
                description = "打开网页后点底部书签图标即可收藏",
                iconRes = AgentIcons.ic_bookmark,
            )
        }
    }
}

/** 阅读模式：正文抽取结果 + 字号调节。 */
@Composable
private fun ReaderPane(
    title: String,
    text: String,
    fontSize: Float,
    onFontSizeChange: (Float) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colors
    Column(modifier = modifier.background(colors.background)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MiuixText(
                text = "阅读模式",
                modifier = Modifier.weight(1f),
                style = MiuixTheme.typography.titleMedium,
                color = colors.primary,
            )
            MiuixIconButton(
                iconRes = AgentIcons.ic_close,
                contentDescription = "退出阅读模式",
                onClick = onClose,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MiuixText(text = "字号", style = MiuixTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            MiuixSlider(
                value = fontSize,
                onValueChange = onFontSizeChange,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                valueRange = 12f..24f,
                steps = 6,
                valueLabel = { "${it.toInt()}sp" },
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            MiuixText(text = title, style = MiuixTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            text.split("\n").filter { it.isNotBlank() }.forEach { paragraph ->
                MiuixText(
                    text = paragraph.trim(),
                    modifier = Modifier.padding(bottom = 10.dp),
                    style = MiuixTheme.typography.bodyLarge.copy(
                        fontSize = fontSize.sp,
                        lineHeight = (fontSize * 1.6f).sp,
                    ),
                )
            }
        }
    }
}

/** 浏览器设置 / 书签 / 历史 / 下载 / 拦截规则 面板。 */
@Composable
private fun BrowserSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    vm: WebViewModel,
    state: WebUiState,
    downloads: List<DownloadRecord>,
    currentUrl: String,
    currentTitle: String,
    onOpen: (String) -> Unit,
    onToast: (String) -> Unit,
) {
    val colors = MiuixTheme.colors
    var section by remember { mutableStateOf("设置") }
    var ruleInput by remember { mutableStateOf("") }
    var uaInput by remember { mutableStateOf(state.pageSettings.customUserAgent) }

    MiuixBottomSheet(visible = visible, onDismiss = onDismiss) {
        MiuixText(
            text = "浏览器",
            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 8.dp),
            style = MiuixTheme.typography.titleMedium,
        )
        MiuixSegmentedButton(
            options = listOf("设置", "书签", "历史", "下载", "拦截"),
            selected = section,
            onSelect = { section = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(8.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(380.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            when (section) {
                "设置" -> {
                    MiuixText(
                        text = "User-Agent",
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
                        style = MiuixTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant,
                    )
                    MiuixSegmentedButton(
                        options = listOf(UserAgentMode.MOBILE, UserAgentMode.DESKTOP, UserAgentMode.CUSTOM),
                        selected = state.pageSettings.userAgentMode,
                        onSelect = { mode -> vm.updateSettings { it.copy(userAgentMode = mode) } },
                        label = {
                            when (it) {
                                UserAgentMode.MOBILE -> "移动端"
                                UserAgentMode.DESKTOP -> "桌面"
                                UserAgentMode.CUSTOM -> "自定义"
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    )
                    if (state.pageSettings.userAgentMode == UserAgentMode.CUSTOM) {
                        MiuixTextField(
                            value = uaInput,
                            onValueChange = {
                                uaInput = it
                                vm.updateSettings { s -> s.copy(customUserAgent = it) }
                            },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            placeholder = "自定义 UA 字符串",
                        )
                    }
                    MiuixSuperSwitch(
                        title = "启用 JavaScript",
                        checked = state.pageSettings.javaScriptEnabled,
                        onCheckedChange = { on -> vm.updateSettings { it.copy(javaScriptEnabled = on) } },
                        subtitle = "关闭后页面交互脚本不执行，需刷新生效",
                        showDivider = true,
                    )
                    MiuixSuperSwitch(
                        title = "无图模式",
                        checked = state.pageSettings.blockImages,
                        onCheckedChange = { on -> vm.updateSettings { it.copy(blockImages = on) } },
                        subtitle = "省流量，仅加载文字与样式",
                        showDivider = true,
                    )
                    MiuixSuperSwitch(
                        title = "广告拦截",
                        checked = state.pageSettings.adBlockEnabled,
                        onCheckedChange = { on -> vm.updateSettings { it.copy(adBlockEnabled = on) } },
                        subtitle = "命中规则库的请求直接返回空响应",
                    )
                }

                "书签" -> if (state.bookmarks.isEmpty()) {
                    MiuixEmptyState(
                        title = "暂无书签",
                        iconRes = AgentIcons.ic_bookmark,
                    )
                } else {
                    state.bookmarks.forEach { bookmark ->
                        MiuixListItem(
                            title = bookmark.title,
                            subtitle = bookmark.url,
                            leadingIconRes = AgentIcons.ic_bookmark,
                            onClick = { onOpen(bookmark.url) },
                            trailing = {
                                MiuixIconButton(
                                    iconRes = AgentIcons.ic_trash,
                                    contentDescription = "删除书签",
                                    onClick = { vm.removeBookmark(bookmark.url) },
                                )
                            },
                        )
                    }
                }

                "历史" -> if (state.history.isEmpty()) {
                    MiuixEmptyState(title = "暂无历史记录", iconRes = AgentIcons.ic_clock)
                } else {
                    state.history.take(40).forEach { entry ->
                        MiuixListItem(
                            title = entry.title,
                            subtitle = "${formatTime(entry.visitedAt)} · ${entry.url}",
                            leadingIconRes = AgentIcons.ic_clock,
                            onClick = { onOpen(entry.url) },
                        )
                    }
                }

                "下载" -> if (downloads.isEmpty()) {
                    MiuixEmptyState(
                        title = "暂无下载任务",
                        description = "网页中的下载会交给系统下载管理器",
                        iconRes = AgentIcons.ic_download,
                    )
                } else {
                    downloads.forEach { record ->
                        MiuixListItem(
                            title = record.title,
                            subtitle = "${record.statusText} · ${record.sizeText} · ${formatTime(record.time)}",
                            leadingIconRes = AgentIcons.ic_download,
                        )
                    }
                }

                "拦截" -> {
                    MiuixText(
                        text = "按域名后缀匹配，例如 doubleclick.net",
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp),
                        style = MiuixTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MiuixTextField(
                            value = ruleInput,
                            onValueChange = { ruleInput = it },
                            modifier = Modifier.weight(1f),
                            placeholder = "添加拦截域名",
                        )
                        Spacer(Modifier.width(8.dp))
                        MiuixButton(
                            text = "添加",
                            onClick = {
                                vm.addBlockRule(ruleInput)
                                ruleInput = ""
                                onToast("规则已添加")
                            },
                            size = MiuixButtonSize.SMALL,
                        )
                    }
                    state.pageSettings.blockRules.forEach { rule ->
                        MiuixListItem(
                            title = rule,
                            leadingIconRes = AgentIcons.ic_blocked,
                            trailing = {
                                MiuixIconButton(
                                    iconRes = AgentIcons.ic_close,
                                    contentDescription = "移除规则",
                                    onClick = { vm.removeBlockRule(rule) },
                                )
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

// ---------------- WebView 构建与真实能力绑定 ----------------

@SuppressLint("SetJavaScriptEnabled")
private fun createConfiguredWebView(
    context: Context,
    tabId: String,
    vm: WebViewModel,
    onToast: (String) -> Unit,
): WebView {
    val webView = WebView(context)
    CookieManager.getInstance().setAcceptCookie(true)
    applyPageSettings(webView, vm.state.value.pageSettings)

    webView.webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            if (!url.startsWith("http")) {
                openExternally(context, url)
                return true
            }
            return false
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            return if (vm.isBlocked(request.url.toString())) {
                WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            } else {
                null
            }
        }

        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
            vm.onPageStarted(tabId, url)
        }

        override fun onPageFinished(view: WebView, url: String) {
            vm.onPageFinished(tabId, url, view.title ?: url)
            vm.setNavigationState(view.canGoBack(), view.canGoForward(), view.url)
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel()
            onToast("证书校验失败，已阻断该请求")
        }
    }

    webView.webChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            vm.onProgress(tabId, newProgress / 100f)
            if (tabId == vm.state.value.activeTabId) {
                vm.setNavigationState(view.canGoBack(), view.canGoForward(), null)
            }
        }

        override fun onReceivedTitle(view: WebView, title: String) {
            vm.onTitle(tabId, title)
        }
    }

    webView.setDownloadListener(
        DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val queued = enqueueDownload(context, url, userAgent, mimeType, name)
            onToast(if (queued) "已加入系统下载：$name" else "下载任务创建失败")
        },
    )
    return webView
}

/** 把页面偏好真实写入 WebSettings（每次状态变化都会重新应用）。 */
@SuppressLint("SetJavaScriptEnabled")
private fun applyPageSettings(webView: WebView, settings: WebPageSettings) {
    webView.settings.apply {
        javaScriptEnabled = settings.javaScriptEnabled
        domStorageEnabled = true
        databaseEnabled = true
        loadsImagesAutomatically = !settings.blockImages
        blockNetworkImage = settings.blockImages
        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        useWideViewPort = true
        loadWithOverviewMode = true
        builtInZoomControls = true
        displayZoomControls = false
        userAgentString = when (settings.userAgentMode) {
            UserAgentMode.MOBILE -> UA_MOBILE
            UserAgentMode.DESKTOP -> UA_DESKTOP
            UserAgentMode.CUSTOM -> settings.customUserAgent.ifBlank { UA_MOBILE }
        }
    }
}

/** 地址归一化：URL 直连，其余走搜索引擎。 */
fun normalizeUrl(input: String): String? {
    val text = input.trim()
    if (text.isEmpty()) return null
    if (text.startsWith("http://") || text.startsWith("https://") || text.startsWith("file://")) return text
    val looksLikeHost = !text.contains(" ") && text.contains(".")
    if (looksLikeHost) {
        return if (text.startsWith("localhost")) "http://$text" else "https://$text"
    }
    return "https://www.bing.com/search?q=" + Uri.encode(text)
}

/** 正文抽取：优先 article/main，剔除脚本、导航、表单等噪音节点。 */
private fun extractArticle(webView: WebView, onResult: (String, String) -> Unit) {
    val script = """
        (function(){
          var root = document.querySelector('article') || document.querySelector('main') || document.body;
          if (!root) return JSON.stringify({t:'',x:''});
          var clone = root.cloneNode(true);
          var noise = clone.querySelectorAll('script,style,nav,header,footer,aside,form,iframe,button,svg');
          for (var i = 0; i < noise.length; i++) { noise[i].remove(); }
          var text = (clone.innerText || clone.textContent || '')
            .replace(/\n{3,}/g, '\n\n')
            .replace(/[ \t]{2,}/g, ' ')
            .trim();
          return JSON.stringify({ t: document.title || '', x: text });
        })();
    """.trimIndent()
    webView.evaluateJavascript(script) { raw ->
        val decoded = decodeJsString(raw)
        val payload = runCatching { JSONObject(decoded) }.getOrNull()
        onResult(payload?.optString("t").orEmpty(), payload?.optString("x").orEmpty())
    }
}

/** evaluateJavascript 回传的是 JSON 字符串字面量，这里安全还原。 */
private fun decodeJsString(raw: String?): String {
    if (raw.isNullOrBlank() || raw == "null") return ""
    return runCatching { JSONObject("{\"v\":$raw}").optString("v") }.getOrElse { raw.trim('"') }
}

/** 真实交给系统下载管理器（支持断点续传、通知栏进度）。 */
private fun enqueueDownload(
    context: Context,
    url: String,
    userAgent: String,
    mimeType: String,
    fileName: String,
): Boolean = runCatching {
    val request = DownloadManager.Request(Uri.parse(url))
        .setMimeType(mimeType)
        .setTitle(fileName)
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        .setAllowedOverMetered(true)
    if (userAgent.isNotBlank()) request.addRequestHeader("User-Agent", userAgent)
    val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    manager.enqueue(request)
    true
}.getOrDefault(false)

data class DownloadRecord(
    val title: String,
    val statusText: String,
    val sizeText: String,
    val time: Long,
)

/** 从系统下载管理器读取真实下载记录（最近 30 条）。 */
private suspend fun loadSystemDownloads(context: Context): List<DownloadRecord> = withContext(Dispatchers.IO) {
    val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    val query = DownloadManager.Query()
    val records = mutableListOf<DownloadRecord>()
    runCatching {
        manager.query(query)?.use { cursor ->
            val titleIdx = cursor.getColumnIndex(DownloadManager.COLUMN_TITLE)
            val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
            val sizeIdx = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val bytesIdx = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val timeIdx = cursor.getColumnIndex(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP)
            var count = 0
            while (cursor.moveToNext() && count < 30) {
                count++
                val status = if (statusIdx >= 0) cursor.getInt(statusIdx) else 0
                val total = if (sizeIdx >= 0) cursor.getLong(sizeIdx) else -1L
                val done = if (bytesIdx >= 0) cursor.getLong(bytesIdx) else 0L
                records += DownloadRecord(
                    title = if (titleIdx >= 0) cursor.getString(titleIdx).orEmpty() else "下载任务",
                    statusText = when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> "已完成"
                        DownloadManager.STATUS_FAILED -> "失败"
                        DownloadManager.STATUS_PAUSED -> "已暂停"
                        DownloadManager.STATUS_RUNNING -> "下载中"
                        DownloadManager.STATUS_PENDING -> "排队中"
                        else -> "未知"
                    },
                    sizeText = if (total > 0) formatBytesShort(if (status == DownloadManager.STATUS_SUCCESSFUL) total else done) + " / " + formatBytesShort(total)
                    else formatBytesShort(done),
                    time = if (timeIdx >= 0) cursor.getLong(timeIdx) else System.currentTimeMillis(),
                )
            }
        }
    }
    records.sortedByDescending { it.time }
}

private fun formatBytesShort(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f GB", bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024)
    bytes >= 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

private fun formatTime(millis: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))

private fun shareUrl(context: Context, url: String) {
    if (url.isBlank()) return
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, url)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, "分享链接")) }
}

private fun openExternally(context: Context, url: String) {
    if (url.isBlank()) return
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** 等待 WebView 创建的最多帧数（60 帧 ≈ 1 秒，足够覆盖首帧组合）。 */
private const val WEBVIEW_WAIT_FRAMES = 60
