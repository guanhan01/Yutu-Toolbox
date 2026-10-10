package com.mcp.toolbox.ui.linux.term

import com.mcp.toolbox.ui.linux.LinuxEnvStore

import com.mcp.toolbox.core.design.component.MiuixIconRes
import androidx.annotation.DrawableRes
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.Context
import android.graphics.Color as AndroidColor
import android.util.Log
import android.view.ViewGroup
import android.view.ActionMode
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mcp.toolbox.R
import com.mcp.toolbox.core.design.icon.AgentIcons
import com.mcp.toolbox.core.design.component.MiuixDialog
import com.mcp.toolbox.core.design.component.MiuixIcon
import com.mcp.toolbox.core.design.component.MiuixText
import com.mcp.toolbox.core.design.theme.MiuixTheme
import kotlinx.coroutines.launch

/**
 * Termux 模式：完整交互终端（一个窗口 = 一个 [TermSessionStore.Tab]）。
 *
 * **仿真交给 xterm.js（MIT）**，不再自研。自研的 VT 解析在尺寸重排、
 * 宽字符、全屏程序上反复出错（丢内容、错位、滚动指示器不齐），
 * 而 xterm.js 是 VS Code / Hyper 在用的工业级实现，这些细节都已打磨过。
 *
 * 职责划分：
 * - xterm.js：VT 解析、屏幕缓冲、重排、选择复制、滚动、光标
 * - 本层：pty 字节 -> xterm.js；键盘输入 -> pty；尺寸变化 -> pty
 * - [TerminalSession]：真 PTY + `bash -i`（已验证可靠，保留）
 *
 * 字节桥接的关键：**不能先 UTF-8 解码再交给 xterm.js**。它要的是原始字节流，
 * 先解码再编码会让中文被二次编码而乱码。所以两个方向都用
 * 「每字节 <-> 一个 Char 的 Latin-1 映射」。
 *
 * 屏幕内容不保存在这里：面板随时可能因为切窗口被销毁重建，而会话不会。
 * 所以输出由 [TerminalSession.readSince] 按游标补齐——挂载时游标为 -1，
 * 一读就是整屏重放，这正是「第二次打开终端是白屏」的修法。
 */
/** 字号上下限。必须与 assets/term/index.html 里的 MIN_FONT / MAX_FONT 一致。 */
private const val MIN_FONT_PX = 8f
private const val MAX_FONT_PX = 32f

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LinuxTerminalPanel(
    tab: TermSessionStore.Tab,
    fontSize: Float,
    modifier: Modifier = Modifier,
    /** 用户在菜单里关掉这个窗口时的回调（最后一个窗口不会走到这里）。 */
    onClosed: () -> Unit = {},
) {
    val context = LocalContext.current
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    val scope = rememberCoroutineScope()

    val session = tab.session
    val running by tab.running
    val error by tab.error

    var ready by remember { mutableStateOf(false) }
    /**
     * 终端**当前实际**字号（pt）。
     *
     * 只保留这一个来源，不要「基础字号 × zoom」与「手势字号」两套并存 ——
     * 那样两者会互相覆盖：手势捏合设了新值之后，A- / A+ 再改 zoom 就不再生效
     * （下发时优先取手势值），按钮表现为失灵。这里统一成：
     *
     * - 进入页面：base = [fontSize]（跟随设置里的字号）
     * - A- / A+：在 base 上按档位增减
     * - 双指捏合：JS 回写实际字号
     * - 主题/深浅色变化：不重置，保持用户当前字号（只重新下发配色）
     */
    var baseFont by remember(tab) { mutableFloatStateOf(fontSize) }
    var zoomStep by remember(tab) { mutableFloatStateOf(1f) }
    var pinchFont by remember(tab) { mutableStateOf<Float?>(null) }

    /** 终端里当前选中的文本（长按选词 / 拖手柄时由 JS 回传）。 */
    var selection by remember { mutableStateOf("") }

    /** 屏幕上的实际字号：手势值优先，否则是基础字号乘上按钮缩放档位。 */
    val effectiveFont: Float = (pinchFont
        ?: (baseFont * zoomStep)).coerceIn(MIN_FONT_PX, MAX_FONT_PX)
    var showKeys by remember { mutableStateOf(false) }
    var ctrl by remember { mutableStateOf(false) }
    var alt by remember { mutableStateOf(false) }
    var shortcuts by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var helper by remember { mutableStateOf("") }
    val webRef = remember { mutableStateOf<WebView?>(null) }

    val ctrlHint = stringResource(R.string.linux_term_ctrl)
    val copiedHint = stringResource(R.string.linux_term_copied)
    val pasteEmptyHint = stringResource(R.string.linux_term_paste_empty)
    val startingHint = stringResource(R.string.linux_term_starting)

    /**
     * 环境是否已安装。
     *
     * 未安装时**不去启动 shell** —— 那必然失败，而且失败信息（挂载 rootfs 出错）
     * 对用户没有任何指导意义。改为直接把 banner 与「去哪儿配置」写进终端，
     * 这样界面结构与已安装时完全一致，用户看到的不是一块空白。
     */
    val envReady = remember(tab.distro, tab.env) {
        // Android 环境不需要 rootfs：它永远「就绪」，否则会被下面的引导语拦住，
        // 明明是能用的终端却显示「环境未配置」。
        tab.env == TermSessionStore.Env.LOCAL ||
            LinuxEnvStore.isInstalled(context, tab.distro)
    }

    LaunchedEffect(tab, envReady) {
        if (envReady) TermSessionStore.ensureStarted(tab)
    }

    // 未就绪时把 banner + 引导语直接写进 xterm。
    // 只在挂载且未就绪时写一次；环境装好后 envReady 变 true，这段自动让位给真 shell。
    LaunchedEffect(ready, envReady) {
        if (!ready || envReady) return@LaunchedEffect
        val wv = webRef.value ?: return@LaunchedEffect
        // 只有 Linux 环境会走到这里（Android 环境在 envReady 里恒为 true）
        val text = TerminalBanner.notInstalledText(context, tab.distro)
        wv.post {
            runCatching {
                wv.evaluateJavascript("window.termWrite(" + quoteJs(text) + ")", null)
                wv.evaluateJavascript("window.termScrollBottom && window.termScrollBottom()", null)
            }
        }
    }

    // ---------------- 输出回放 ----------------
    //
    // 屏幕内容的唯一数据源是 [TerminalSession] 的输出历史，而不是「页面恰好活着时
    // 收到的那些字节」：WebView 一销毁，xterm 的屏幕缓冲就没了，会话却还在跑
    // （见 [TermSessionStore]）。所以新挂载的面板必须按游标把错过的一切补齐，
    // 否则就是白屏——第二次打开终端、以及切窗口切回来都会撞上。
    val pump = remember(tab) { OutputPump(session) }

    /** 下一次写入是否为「整屏重放」（挂载/重启后的第一笔）。 */
    var replayWrite by remember(tab) { mutableStateOf(true) }

    // 唤醒 -> 按游标取增量攒进缓冲。游标只在这里推进，与 WebView 是否就绪无关：
    // 未就绪期间照常推进，内容留在缓冲里，就绪后一次性写出去。
    LaunchedEffect(session) {
        session.wake.collect { pump.pull() }
    }

    // 缓冲有内容且页面已就绪 -> 写进 xterm.js。
    //
    // 这里必须读 `.intValue` 拿**值**，不能把 pump.seq 这个 State 对象当 key：
    // 对象身份永不改变，LaunchedEffect 就不会重启，输出会一直堆在缓冲里没人写
    // （首屏空白、打字不回显都是这个原因）。读值会在 pump 追加时触发重组，
    // key 随之更新，effect 重启并取走缓冲。
    val pumpTick = pump.seq.intValue
    LaunchedEffect(pumpTick, ready) {
        if (!ready) return@LaunchedEffect
        val text = pump.drainAll() ?: return@LaunchedEffect
        val wv = webRef.value ?: return@LaunchedEffect
        wv.post {
            runCatching {
                wv.evaluateJavascript("window.termWrite(" + quoteJs(text) + ")", null)
                // 整屏重放写完后要显式回到底部。
                // xterm 只在「原本就在底部」时自动跟随；一次写入几百行时，
                // 它可能先把视口留在上面，表现就是「有内容却看到空白」（滚动条
                // 滑块可见、画面全白）。之后不再强制，避免打断用户往上翻。
                if (replayWrite) {
                    replayWrite = false
                    wv.evaluateJavascript("window.termScrollBottom && window.termScrollBottom()", null)
                }
            }
        }
    }

    // 重启壳层：历史已清空，屏幕也要清，并把游标退回到「整屏重放」状态。
    // 必须清屏——新 banner 是追加到旧画面后面的，不清会看到两份 banner 叠着。
    LaunchedEffect(tab.restarts.value) {
        if (tab.restarts.value == 0) return@LaunchedEffect
        pump.reset()
        replayWrite = true
        webRef.value?.evaluateJavascript("window.termReset && window.termReset()", null)
    }

    // 字号与配色同步
    LaunchedEffect(ready, zoomStep, colors.isDark, pinchFont) {
        if (!ready) return@LaunchedEffect
        val wv = webRef.value ?: return@LaunchedEffect
        val px = effectiveFont
        val fg = colors.onSurface.toArgb() and 0xFFFFFF
        val sel = colors.primary.toArgb() and 0xFFFFFF
        // 配色跟随应用主题：背景交给 Compose 画（透明），16 色用应用调色板推导。
        // 不传 16 色时 xterm 会用自带的高饱和默认值，与整体观感不符（用户反馈「不好看」）。
        // 手工拼 JSON 要同时处理 Kotlin 字符串转义与 JSON 引号，层数太多易错；
        // 改成构造 JSONObject 让库负责转义。
        val themeJson = org.json.JSONObject().apply {
            // 背景必须是**真实不透明色**，不能给 #00000000。
            // 视觉上背景确实由 Compose 画，但 xterm 内部会拿 background 参与
            // blend() 计算（各种叠加色），给透明会算出很深的颜色 ——
            // 曾导致选中文本被深色盖住。这里给同色即可，看不出差别。
            put("background", "#" + hex(colors.codeBackground.toArgb() and 0xFFFFFF))
            put("foreground", "#" + hex(fg))
            put("cursor", "#" + hex(sel))
            put("cursorAccent", "#" + hex(colors.codeBackground.toArgb() and 0xFFFFFF))
            // 选区底色用半透明。
            // 必须写 **#RRGGBBAA** 这种 hex：xterm 的解析是
            // parseInt(e[1]||e[4]||e[7]||e[10],16)，只认 #RGB/#RGBA/#RRGGBB/#RRGGBBAA，
            // **不认 rgba() 函数式写法**（实测确认）。写错会退回自带的**不透明深蓝**，
            // 把文字整片盖住。alpha=0x4D（约 30%）既能看出范围又不遮字。
            put("selectionBackground", "#" + hex(sel) + "4D")
            // 聚焦与失焦两套都要给：长按选词时终端可能失焦，
            // 只设前者会退回 xterm 默认的失焦色（深蓝）。
            put("selectionInactiveBackground", "#" + hex(sel) + "4D")
            // 16 色基础盘：暗色系取面级色，彩度色取语义色，避免 xterm 默认那套高饱和配色
            val names = listOf(
                "black", "red", "green", "yellow", "blue", "magenta", "cyan", "white",
            )
            val base = listOf(
                colors.surfaceContainerHighest, colors.error, colors.success, colors.warning,
                colors.primary, colors.tertiary, colors.secondary, colors.onSurface,
            )
            names.forEachIndexed { i, name ->
                put(name, "#" + hex(base[i].toArgb() and 0xFFFFFF))
            }
            // 亮版：往白色方向提亮，保持色相一致
            names.forEachIndexed { i, name ->
                put("bright" + name.replaceFirstChar { it.uppercase() },
                    "#" + hex(lighten(base[i].toArgb() and 0xFFFFFF)))
            }
        }.toString()
        // 诊断：把实际下发的配色打出来。字看不见（白底白字）时，
        // 要看的是 foreground 与 Compose 画的 codeBackground 是否撞色，
        // 而不是靠肉眼猜主题。
        Log.i(
            "TermWeb",
            "theme fg=#${"%06x".format(fg)} sel=#${"%06x".format(sel)} " +
                "codeBg=#${"%06x".format(colors.codeBackground.toArgb() and 0xFFFFFF)} " +
                "onSurface=#${"%06x".format(colors.onSurface.toArgb() and 0xFFFFFF)} isDark=${colors.isDark}",
        )
        wv.post {
            runCatching {
                // 先主题后字号：主题决定首帧配色，反了会闪一下默认色
                wv.evaluateJavascript("window.termTheme($themeJson)", null)
                wv.evaluateJavascript("window.termSetFont($px)", null)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.codeBackground),
    ) {
        val notice = when {
            error != null -> error
            !running -> startingHint
            else -> null
        }
        if (notice != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.pageHorizontal, vertical = spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                MiuixText(
                    text = notice,
                    style = MiuixTheme.typography.bodySmall,
                    color = if (error != null) colors.error else colors.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (error != null) {
                    TermIconChip(AgentIcons.ic_refresh) {
                        TermSessionStore.restart(tab)
                    }
                }
            }
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                // 终端内容再宽也不该把整页撑出横向滚动
                .clipToBounds(),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    NoActionModeWebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        setBackgroundColor(AndroidColor.TRANSPARENT)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = true
                        settings.loadWithOverviewMode = false
                        settings.useWideViewPort = false
                        settings.builtInZoomControls = false
                        settings.displayZoomControls = false
                        // 让 WebView 能拿到焦点，否则 xterm 的隐藏 textarea 收不到按键，
                        // 表现为「怎么打都没反应」。
                        isFocusable = true
                        isFocusableInTouchMode = true
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                // 页面加载完探测脚本是否可用；失败要能看见原因
                                view?.evaluateJavascript(
                                    "typeof window.termWrite + '|' + typeof Terminal",
                                    { r -> Log.i("TermWeb", "probe=$r url=$url") },
                                )
                                // 主动聚焦并唤起软键盘：xterm 的隐藏 textarea 必须已聚焦，
                                // 用户点屏幕才能直接打字。
                                // 注意：这里**不**置 ready。
                                // 页面加载完（onPageFinished）不等于渲染尺寸稳定：
                                // xterm 的 fit() 在 0/60/250/600ms 各重试一次，
                                // 在那之前写入会落在一个还在变的尺寸上，渲染成
                                // 「有内容但看不见」的空白屏（滚动条滑块可见）。
                                // 就绪信号由 JS 侧最后一次 doFit 之后回调 onReady 发出。
                                view?.requestFocus()
                                view?.postDelayed({
                                    runCatching {
                                        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE)
                                            as? android.view.inputmethod.InputMethodManager
                                        @Suppress("DEPRECATION")
                                        imm?.showSoftInput(view, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                                    }
                                }, 300)
                            }
                        }
                        // 必须挂 WebChromeClient 才能收到 console 输出与 JS 异常，
                        // 否则页面白屏时没有任何线索。
                        webChromeClient = object : android.webkit.WebChromeClient() {
                            override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                                Log.i("TermWeb", "console[${m.messageLevel()}]: ${m.message()} @${m.lineNumber()}")
                                return true
                            }
                        }
                        addJavascriptInterface(
                            Bridge(
                                session = session,
                                resizeAction = { c, r ->
                                    // 必须**先**武装再改尺寸：改尺寸会触发 SIGWINCH，
                                    // shell 的重画痕迹紧接着就到，顺序反了就漏掉。
                                    pump.armRepaintDrop()
                                    scope.launch { session.resize(c, r) }
                                },
                                titleAction = { title -> tab.title.value = title },
                                // 手势回写的实际字号：记下来并清掉 zoomStep 的贡献，
                                // 否则 A- / A+ 会在「捏合前的基础字号」上继续加减，
                                // 与屏幕上的字号对不上。
                                fontSizeAction = { px ->
                                    pinchFont = px.coerceIn(MIN_FONT_PX, MAX_FONT_PX)
                                    zoomStep = 1f
                                },
                                selectionAction = { selection = it },
                                copyAction = { text ->
                                    if (text.isNotEmpty()) {
                                        copyToClipboard(context, text)
                                        helper = copiedHint
                                    }
                                },
                                readyAction = { ready = true },
                            ),
                            "Android",
                        )
                        loadUrl("file:///android_asset/term/index.html")
                        webRef.value = this
                    }
                },
            )

            // 不在这里盖一层 Compose 手势层。
            //
            // 原来盖了个 fillMaxSize + clickable 的 Box（点一下聚焦并唤起输入法），
            // 但它会**消费掉所有触摸**：WebView 收不到事件，于是
            //   ① 浏览器原生的长按选词、拖动选字、复制/粘贴工具条全都不可用；
            //   ② 双指捏合也到不了页面里的 JS。
            // 现在聚焦交给 WebView 自己：触摸屏上点 WebView 会走「点在可聚焦节点
            // 上」的分支并聚焦，页面内 xterm 的隐藏 textarea 随之拿到焦点，
            // 长按与拖动则由浏览器按原生行为处理（选取、菜单都在）。
            // 注意别在这里 onChange 里 set 状态：WebView 不是 Compose 管的焦点目标，
            // 抢焦点会打断浏览器自己的选取状态。

            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TermIconChip(AgentIcons.ic_chevron_up) {
                    webRef.value?.evaluateJavascript("window.termScrollPage && window.termScrollPage(-1)", null)
                }
                TermIconChip(AgentIcons.ic_chevron_down) {
                    webRef.value?.evaluateJavascript("window.termScrollPage && window.termScrollPage(1)", null)
                }
            }
        }

        if (helper.isNotBlank()) {
            MiuixText(
                text = helper,
                style = MiuixTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = spacing.pageHorizontal, vertical = 4.dp),
            )
        }

        if (showKeys) {
            ExtendedKeys(
                ctrl = ctrl,
                alt = alt,
                onCtrl = { ctrl = !ctrl; helper = if (ctrl) "" else ctrlHint },
                onAlt = { alt = !alt },
                onRaw = { bytes -> session.write(bytes) },
                onTyped = { text ->
                    session.write(applyModifiers(text, ctrl, alt))
                    ctrl = false
                    alt = false
                },
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = spacing.pageHorizontal, vertical = spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            TermIconChip(AgentIcons.ic_keyboard, active = showKeys) {
                showKeys = !showKeys
                // 键盘本身由 xterm.js 的隐藏 textarea 驱动，点终端画面即可打字；
                // 这里只切换扩展键行。
            }
            Spacer(Modifier.weight(1f))
            // 按「当前实际字号」增减，而不是在一个独立的缩放系数上加减：
            // 手势捏合之后按钮仍要按屏幕上的真实大小继续调整。
            TermIconChip(AgentIcons.ic_font_size) {
                pinchFont = (effectiveFont / 1.12f).coerceIn(MIN_FONT_PX, MAX_FONT_PX)
                zoomStep = 1f
            }
            TermIconChip(AgentIcons.ic_font_size) {
                pinchFont = (effectiveFont * 1.12f).coerceIn(MIN_FONT_PX, MAX_FONT_PX)
                zoomStep = 1f
            }
            Box {
                TermIconChip(AgentIcons.ic_more_vert) { menu = true }
                if (menu) {
                    MiuixDialog(
                        visible = true,
                        onDismiss = { menu = false },
                        title = stringResource(R.string.linux_term_shortcuts),
                        confirmText = stringResource(R.string.app_action_back),
                        onConfirm = { menu = false },
                        dismissText = null,
                        content = {
                            ActionRow(stringResource(R.string.linux_term_shortcuts)) {
                                menu = false
                                shortcuts = true
                            }
                            ActionRow(stringResource(R.string.linux_term_exit)) {
                                menu = false
                                // 真被关掉才需要退回上一个窗口；只剩一个窗口时这里是
                                // 「就地重开」，留在原页面即可（清屏与整屏重放交给
                                // OutputPump 与 tab.restarts 那两处）。
                                if (TermSessionStore.close(tab)) onClosed()
                            }
                        },
                    )
                }
            }
        }
    }

    if (shortcuts) {
        ShortcutSheet(
            onDismiss = { shortcuts = false },
            onRun = { cmd ->
                session.write((cmd + "\r").toByteArray(Charsets.UTF_8))
                shortcuts = false
            },
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            // WebView 是重量级对象：离开页面必须销毁，否则每次进出都漏一份
            runCatching {
                webRef.value?.let { wv ->
                    (wv.parent as? ViewGroup)?.removeView(wv)
                    wv.destroy()
                }
            }
            webRef.value = null
            ready = false
        }
    }
}

/** JS 桥。方法与 index.html 里的 `window.Android.xxx` 一一对应。 */
private class Bridge(
    private val session: TerminalSession,
    /** 回调属性统一带 *Action 后缀。旧实现里属性与 `@JavascriptInterface` 函数同名，
     *  Kotlin 重载解析会优先选中同名**函数**，于是每个回调都在调自己 -> 无限递归，
     *  被 WebView 吞成一行 "Error invoking onResize"：resize 静默失效，
     *  pty 尺寸永远停在 bootstrap 写死的那个。 */
    private val resizeAction: (Int, Int) -> Unit,
    private val titleAction: (String) -> Unit,
    private val fontSizeAction: (Float) -> Unit,
    private val selectionAction: (String) -> Unit,
    private val copyAction: (String) -> Unit,
    private val readyAction: () -> Unit,
) {
    /** 用户按键。JS 侧给的是「一字符一字节」的字符串，逐字符取低位还原字节。 */
    @JavascriptInterface
    fun onInput(text: String) {
        if (text.isEmpty()) return
        session.write(ByteArray(text.length) { i -> text[i].code.toByte() })
    }

    @JavascriptInterface
    fun onResize(cols: Int, rows: Int) {
        resizeAction(cols, rows)
    }

    /** shell 发的 OSC 标题（`printf '\\033]0;...\\007'`）：拿来当窗口名。 */
    @JavascriptInterface
    fun onTitle(title: String) {
        titleAction(title)
    }

    /** 选区文本（长按选词与拖手柄都会触发）。 */
    @JavascriptInterface
    fun onSelection(text: String) {
        selectionAction(text)
    }

    /**
     * 自绘的「复制」按钮。
     *
     * 走 Kotlin 写系统剪贴板，而不是让 JS 调 navigator.clipboard ——
     * 后者在 WebView 里常因缺少用户手势授权而静默失败。
     */
    @JavascriptInterface
    fun onCopy(text: String) {
        copyAction(text)
    }

    /** 双指缩放后的字号，回写 Kotlin 侧，避免被下一次主题刷新覆盖。 */
    @JavascriptInterface
    fun onFontSize(px: Float) {
        fontSizeAction(px)
    }


    /** 页面侧渲染尺寸稳定了（最后一次 doFit 之后）。此刻才允许写入。 */
    @JavascriptInterface
    fun onReady() {
        readyAction()
    }
}

/**
 * 面板侧的取数泵：按游标从会话历史取增量，攒在缓冲里等 WebView 消费。
 *
 * 为什么要有缓冲而不是「每来一块就写一次」：WebView 还没就绪时（首次加载要几百
 * 毫秒）写进去没人接收；而 Compose 的重组是合并的，两次重组之间可能来过好几块输出，
 * 只保留最后一块会**永久丢字节**——终端是流式的，丢一段 ANSI 序列整屏就乱了。
 *
 * 为什么 [seq] 是 Compose 状态：追加发生在 pty 读取协程里（非 Compose 线程），
 * 靠它通知界面「该写一次了」。
 */
private class OutputPump(private val session: TerminalSession) {

    /** 两次重组之间最多攒这么多字节；再多说明界面长时间没消费，丢掉最旧的。 */
    private val limit = 1024 * 1024

    private var cursor = -1L
    private var buffer = StringBuilder()

    var seq = mutableIntStateOf(0)
        private set

    /**
     * 丢弃 shell 重画痕迹的闸门，见 [RepaintDropGate]。
     */
    private val dropGate = RepaintDropGate()

    /**
     * 窗口尺寸即将变化，通知本缓冲把紧随其后的重画痕迹丢掉。
     *
     * 必须**先**调它再改尺寸：改尺寸会触发 SIGWINCH，shell 的重画痕迹
     * 紧接着就到，顺序反了就漏掉。
     */
    fun armRepaintDrop() {
        dropGate.arm()
    }

    /** 把游标之后的新输出取回来。 */
    fun pull() {
        val (next, bytes) = session.readSince(cursor)
        cursor = next
        if (bytes.isEmpty()) return
        val text = latin1(bytes)
        val keep = synchronized(this) { dropGate.filter(text) } ?: return
        synchronized(this) {
            buffer.append(keep)
            if (buffer.length > limit) buffer.delete(0, buffer.length - limit)
        }
        seq.intValue += 1
    }

    /** 取走缓冲里当前累积的全部内容；空则返回 null。 */
    fun drainAll(): String? = synchronized(this) {
        if (buffer.isEmpty()) return null
        val text = buffer.toString()
        buffer = StringBuilder()
        text
    }

    /** 重启壳层：退回整屏重放，并丢掉旧画面对应的待写内容。 */
    fun reset() {
        synchronized(this) {
            buffer = StringBuilder()
            cursor = -1L
            dropGate.disarm()
        }
    }
}

/**
 * 丢弃 shell「重画痕迹」的闸门。
 *
 * ## 它解决什么
 *
 * Android 自带的 `/system/bin/sh` 是 mksh。收到 SIGWINCH 时它会**重画提示符**：
 * 先补一串空格抹掉旧的，再用退格（`\b`）把光标退回行首。宽度**变了**的那一次，
 * 补的空格数按旧宽度算、退格数按提示符长度算，两者对不上 —— 光标回不到行首，
 * 于是多留一行还带偏移。每次宽度变化留一份，累积就是一屏重复提示符。
 *
 * 这些字节是**纯视觉噪声**：终端最终画面并不需要它们（重画成功时是原地覆盖，
 * 看不出差别）。所以在进入缓冲前就丢掉，既不改 shell、也不用碰 xterm。
 *
 * ## 判据
 *
 * 重画痕迹必然含退格；正常输出里退格极少见。规则：
 * - [filter] 在武装状态下遇到含退格的块 → 返回 null（丢弃），且**保持武装**
 * - 遇到不含退格的块 → 解除武装并原样返回（窗口自然收敛，不需要计时器）
 * - 未武装时 → 原样返回
 *
 * 已知取舍：若某条命令恰好在尺寸变化那一瞬输出带退格的内容（如进度条），
 * 那一小块会被丢掉。缩放多发生在终端空闲时，实际影响很小。
 *
 * 抽成独立类是为了能直接写单测 —— 这段规则靠肉眼在真机上验不出来。
 */
internal class RepaintDropGate {

    @Volatile
    private var armed = false

    fun arm() {
        armed = true
    }

    fun disarm() {
        armed = false
    }

    /** 返回该保留的内容；返回 null 表示这一块应当整体丢弃。 */
    fun filter(text: String): String? {
        if (!armed) return text
        if (text.indexOf('\b') >= 0) return null
        armed = false
        return text
    }
}

/** 字节 -> Latin-1 字符串（每字节一个 Char）。见文件头对字节桥接的说明。 */
private fun latin1(bytes: ByteArray): String {
    val sb = StringBuilder(bytes.size)
    for (b in bytes) sb.append((b.toInt() and 0xFF).toChar())
    return sb.toString()
}

/** 写系统剪贴板。由 JS 的「复制」按钮触发。 */
private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        ?: return
    cm.setPrimaryClip(android.content.ClipData.newPlainText("terminal", text))
}

/** 字符串 -> 安全的 JS 字面量（按 JSON 规则转义）。 */
private fun quoteJs(text: String): String {
    val sb = StringBuilder(text.length + 2)
    sb.append('"')
    for (c in text) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\u0008' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            else -> if (c.code < 0x20) {
                sb.append("\\u").append(String.format("%04x", c.code))
            } else {
                sb.append(c)
            }
        }
    }
    sb.append('"')
    return sb.toString()
}

private fun hex(argb: Int): String = String.format("%06x", argb and 0xFFFFFF)

/** 应用 CTRL/ALT 修饰：CTRL+A -> 0x01，ALT+x -> ESC x。 */
private fun applyModifiers(text: String, ctrl: Boolean, alt: Boolean): ByteArray {
    if (!ctrl && !alt) return text.toByteArray(Charsets.UTF_8)
    val out = ArrayList<Byte>(text.length + 2)
    text.forEach { ch ->
        val lower = ch.lowercaseChar()
        when {
            ctrl && lower in 'a'..'z' -> out.add((lower.code - 'a'.code + 1).toByte())
            ctrl && ch == '[' -> out.add(0x1B)
            ctrl -> out.add(ch.code.toByte())
            else -> {
                out.add(0x1B)
                out.add(ch.code.toByte())
            }
        }
    }
    return out.toByteArray()
}

@Composable
private fun TermIconChip(
    @DrawableRes iconRes: Int,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = MiuixTheme.colors
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (active) colors.primary.copy(alpha = 0.16f)
                else colors.surface.copy(alpha = 0.80f),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        MiuixIconRes(
iconRes,
            null,
            tint = if (active) colors.primary else colors.onSurfaceVariant,
            size = 20.dp,
        )
    }
}

@Composable
private fun ActionRow(label: String, onClick: () -> Unit) {
    val colors = MiuixTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MiuixText(label, style = MiuixTheme.typography.bodyMedium, color = colors.onSurface)
    }
}

/** 扩展键行：输入法打不出来、但终端必须要有的键。 */
@Composable
private fun ExtendedKeys(
    ctrl: Boolean,
    alt: Boolean,
    onCtrl: () -> Unit,
    onAlt: () -> Unit,
    onRaw: (ByteArray) -> Unit,
    onTyped: (String) -> Unit,
) {
    val colors = MiuixTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        KeyRow(
            listOf(
                "ESC" to { onRaw(byteArrayOf(0x1B)) },
                "TAB" to { onRaw(byteArrayOf(0x09)) },
                "CTRL" to onCtrl,
                "ALT" to onAlt,
                "←" to { onRaw("\u001B[D".toByteArray()) },
                "↑" to { onRaw("\u001B[A".toByteArray()) },
                "↓" to { onRaw("\u001B[B".toByteArray()) },
                "→" to { onRaw("\u001B[C".toByteArray()) },
                "^C" to { onRaw(byteArrayOf(0x03)) },
                "^D" to { onRaw(byteArrayOf(0x04)) },
                "^Z" to { onRaw(byteArrayOf(0x1A)) },
                "^L" to { onRaw(byteArrayOf(0x0C)) },
            ),
            highlight = mapOf("CTRL" to ctrl, "ALT" to alt),
        )
        KeyRow(
            listOf(
                "|" to { onTyped("|") },
                "/" to { onTyped("/") },
                "-" to { onTyped("-") },
                "_" to { onTyped("_") },
                "~" to { onTyped("~") },
                "\$" to { onTyped("$") },
                "*" to { onTyped("*") },
                "'" to { onTyped("'") },
                "\"" to { onTyped("\"") },
                "`" to { onTyped("`") },
                ";" to { onTyped(";") },
                "&" to { onTyped("&") },
            ),
            highlight = emptyMap(),
        )
        KeyRow(
            listOf(
                "HOME" to { onRaw("\u001B[H".toByteArray()) },
                "END" to { onRaw("\u001B[F".toByteArray()) },
                "PGUP" to { onRaw("\u001B[5~".toByteArray()) },
                "PGDN" to { onRaw("\u001B[6~".toByteArray()) },
            ),
            highlight = emptyMap(),
        )
    }
}

@Composable
private fun KeyRow(keys: List<Pair<String, () -> Unit>>, highlight: Map<String, Boolean>) {
    val colors = MiuixTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        keys.forEach { (label, action) ->
            val active = highlight[label] == true
            Box(
                modifier = Modifier
                    .heightIn(min = 38.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (active) colors.primary else colors.surfaceContainerHigh)
                    .clickable(onClick = action)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                MiuixText(
                    text = label,
                    style = MiuixTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = if (active) colors.onPrimary else colors.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 常用命令快捷表。 */
@Composable
private fun ShortcutSheet(onDismiss: () -> Unit, onRun: (String) -> Unit) {
    val colors = MiuixTheme.colors
    val items = remember {
        listOf(
            "ls -la", "pwd", "cd /root", "cd /sdcard", "df -h", "free -m", "uname -a",
            "top -n 1 | head -20", "apt update", "apt upgrade -y", "apt install -y ",
            "python3 --version", "node --version", "git --version", "htop", "vim ",
            "clear", "exit",
        )
    }
    MiuixDialog(
        visible = true,
        onDismiss = onDismiss,
        title = stringResource(R.string.linux_term_shortcuts),
        confirmText = stringResource(R.string.app_action_back),
        onConfirm = onDismiss,
        dismissText = null,
        content = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                items(items) { cmd ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onRun(cmd) }
                            .padding(horizontal = 4.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MiuixText(
                            text = cmd,
                            style = MiuixTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                            color = colors.onSurface,
                        )
                    }
                }
            }
        },
    )
}

/** 把颜色朝白色提亮一档，用于生成 16 色的亮版。 */
private fun lighten(argb: Int, amount: Float = 0.35f): Int {
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    fun up(v: Int) = (v + (255 - v) * amount).toInt().coerceIn(0, 255)
    return (up(r) shl 16) or (up(g) shl 8) or up(b)
}

/**
 * 禁掉 WebView 自带的 ActionMode。
 *
 * 为什么必须禁：终端的文字是 xterm 渲染的，而 xterm 的隐藏 TEXTAREA 必须保持焦点
 * 才能收键盘；Android 一旦看到焦点落在可编辑元素上，长按就弹「粘贴 / 自动填充」
 * 那套输入框菜单 —— 而且它还会往触摸流里塞一个 ACTION_CANCEL，把页面里的长按
 * 逻辑直接打断（实测：选区与手柄都不出现，只弹那个菜单）。
 *
 * 做法：WebView 弹菜单最终都要走 startActionMode，override 后返回 null 即可。
 *
 * 注意：setOnLongClickListener 对 WebView **无效** —— WebView 在自己的 onTouchEvent
 * 里直接走原生触摸处理，不调用 View 那套长按检测（试过，无作用）。
 */
private class NoActionModeWebView(context: android.content.Context) : WebView(context) {
    override fun startActionMode(callback: ActionMode.Callback?): ActionMode? = null

    override fun startActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? = null
}
