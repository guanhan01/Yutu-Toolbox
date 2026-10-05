package com.mcp.toolbox.ui

import androidx.compose.ui.draw.drawWithContent
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.RoundedCorner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.unit.Dp
import androidx.navigation.NavController
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import com.mcp.toolbox.core.design.theme.NavTransitionEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.mcp.toolbox.R
import com.mcp.toolbox.core.common.PrivilegeManager
import com.mcp.toolbox.core.common.PrivilegeStatus
import com.mcp.toolbox.core.design.icon.AgentIcons
import com.mcp.toolbox.core.design.component.MiuixDialog
import com.mcp.toolbox.core.design.component.MiuixIcon
import com.mcp.toolbox.core.design.component.MiuixIconButton
import com.mcp.toolbox.core.design.component.MiuixText
import com.mcp.toolbox.core.design.component.MiuixToastHost
import com.mcp.toolbox.core.design.component.MiuixToastState
import com.mcp.toolbox.core.design.component.miuixClickable
import com.mcp.toolbox.core.design.component.rememberMiuixPressState
import com.mcp.toolbox.core.design.theme.MiuixTheme
import com.mcp.toolbox.core.design.theme.ThemeConfig
import com.mcp.toolbox.feature.apps.AppsScreen
import com.mcp.toolbox.feature.capture.CaptureScreen
import com.mcp.toolbox.feature.database.DatabaseScreen
import com.mcp.toolbox.feature.decompile.DecompileScreen
import com.mcp.toolbox.feature.home.HomeScreen
import com.mcp.toolbox.ui.ai.AiSettingsScreen
import com.mcp.toolbox.ui.ai.AiProviderListScreen
import com.mcp.toolbox.ui.ai.AiModelScreen
import com.mcp.toolbox.ui.ai.AiProviderDetailScreen
import com.mcp.toolbox.ui.ai.AiHub
import com.mcp.toolbox.ui.ai.AiConfigStore
import com.mcp.toolbox.ui.linux.LinuxChoiceSheet
import com.mcp.toolbox.ui.linux.LinuxScreen
import com.mcp.toolbox.ui.linux.LinuxTerminalScreen
import com.mcp.toolbox.ui.linux.LinuxDistro
import com.mcp.toolbox.ui.linux.term.LinuxTermMode
import com.mcp.toolbox.ui.linux.term.LinuxTerminalPanel
import com.mcp.toolbox.ui.linux.term.TermSessionStore
import com.mcp.toolbox.core.design.component.MiuixTextField
import com.mcp.toolbox.ui.linux.term.TerminalToAi
import com.mcp.toolbox.ui.linux.term.TerminalTabStrip
import com.mcp.toolbox.ui.linux.LinuxCheckScreen
import android.widget.Toast
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import com.mcp.toolbox.feature.home.ChatMemoryStore
import com.mcp.toolbox.feature.home.ChatStore
import com.mcp.toolbox.feature.home.agent.AgentStreamBus
import com.mcp.toolbox.ui.ai.AiToolBridge
import com.mcp.toolbox.feature.home.ChatUsageStore
import com.mcp.toolbox.feature.home.ChatMessage
import com.mcp.toolbox.ui.ai.AiMemoryScreen
import com.mcp.toolbox.ui.ai.ChatRunner
import com.mcp.toolbox.ui.ai.ReasoningEffort
import com.mcp.toolbox.ui.ai.AiChatClient
import com.mcp.toolbox.feature.mcp.SkillScreen
import com.mcp.toolbox.feature.mcp.ArtifactsScreen
import com.mcp.toolbox.feature.mcp.McpConnectionState
import com.mcp.toolbox.feature.mcp.McpRegistry
import com.mcp.toolbox.feature.mcp.McpScreen
import com.mcp.toolbox.feature.network.DnsScreen
import com.mcp.toolbox.feature.network.HttpRequestScreen
import com.mcp.toolbox.feature.network.NetworkEnvScreen
import com.mcp.toolbox.feature.network.NetworkHubScreen
import com.mcp.toolbox.feature.network.PingScreen
import com.mcp.toolbox.feature.network.PortScanScreen
import com.mcp.toolbox.feature.network.WhoisScreen
import com.mcp.toolbox.feature.settings.ThemeSettingsScreen
import com.mcp.toolbox.feature.web.WebScreen
import com.mcp.toolbox.navigation.Destination
import com.mcp.toolbox.navigation.DrawerFooter
import com.mcp.toolbox.navigation.DrawerPrimary
import com.mcp.toolbox.navigation.Routes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.mcp.toolbox.feature.decompile.engine.DecompileHub
import com.mcp.toolbox.ui.linux.LinuxPendingInstall
import com.mcp.toolbox.ui.linux.LinuxPrefs
import com.mcp.toolbox.ui.linux.LinuxBrowseTarget
import com.mcp.toolbox.ui.linux.LinuxFilesScreen
import com.mcp.toolbox.ui.linux.LinuxSharedScreen

/** 压暗纱的不透明度，与抽屉遮罩的 0.35 取同一量级。 */
private const val ScrimAlpha = 0.45f

/** 旧页淡出后保留的不透明度：透出下方的压暗纱，读成「上一层被压暗」。 */
private const val OldPageAlpha = 0.42f

/**
 * 该页是否需要裁圆角。
 *
 * 只在导航转场的那一小段非 0：二级页滑入（或被滑出）时按设备屏幕圆角裁成卡片，
 * 转场结束归为直角。静止时必须为直角 —— 页面铺满全屏，圆角缺口会透出下层。
 */
/** 设备屏幕圆角半径，由 AppShell 提供。 */
private val LocalDeviceCorner = staticCompositionLocalOf { 0.dp }

/** 预测性返回手势是否进行中。 */
private val LocalBackGesture = compositionLocalOf { false }

/**
 * 设备屏幕的物理圆角半径。
 *
 * 用系统 [RoundedCorner]（API 31+）取真实值，使页面圆角与手机那四个圆角一致；
 * 取不到（老系统或圆角为 0）时返回 0，即不加圆角。
 */
private fun deviceCornerRadius(context: android.content.Context): androidx.compose.ui.unit.Dp {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return 0.dp
    val px =
        runCatching {
            context.getSystemService(DisplayManager::class.java)
                ?.getDisplay(Display.DEFAULT_DISPLAY)
                ?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)
                ?.radius
        }.getOrNull() ?: 0
    if (px <= 0) return 0.dp
    return (px / context.resources.displayMetrics.density).dp
}

/**
 * 页面自裁圆角。
 *
 * 必须裁在**页面自己**身上：裁在 NavHost 外面那个容器上时，容器是静止的，
 * 页面从它内部滑过，圆角只作用在屏幕四角，跟不上页面 —— 手感和观感都不对。
 *
 * 触发条件取「本页正在进场/退场」或「返回手势进行中」：
 * - 转场用 [AnimatedContentScope.transition] 的真实状态判断，不用定时器猜时间
 *   （定时器从导航回调起算，比动画早，会让圆角在盖满前就消失）。
 * - 手势期间框架不驱动 transition（navigation 2.8.5 无 seekable 支持），
 *   所以另由 [LocalBackGesture] 补上。
 */
@Composable
private fun Modifier.pageCorners(scope: AnimatedContentScope): Modifier {
    val r = LocalDeviceCorner.current
    if (r.value <= 0f) return this
    val t = scope.transition
    val running = t.currentState != t.targetState
    if (!running && !LocalBackGesture.current) return this
    return clip(RoundedCornerShape(r))
}

/** 转场时长，与 NavHost 的转场配置保持一致。 */
private const val NavTransitionMs = 500L

/** 应用外壳：自绘抽屉 + 底栏 + 顶部 Toast 宿主。 抽屉支持汉堡按钮打开、左侧边缘滑动打开、面板左滑关闭、点遮罩关闭。 */
@Composable
fun AppShell(
    config: ThemeConfig,
    onConfigChange: ((ThemeConfig) -> ThemeConfig) -> Unit,
    toastState: MiuixToastState,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colors
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: Routes.HOME


    // 设备屏幕圆角：二级页在转场中按它裁切，与手机物理圆角对齐。
    val appContext = LocalContext.current
    val pageCorner = remember(appContext) { deviceCornerRadius(appContext) }

    // 预测性返回手势是否进行中。
    //
    // 只有这一个状态需要自己维护：常规转场的圆角由页面自己的 transition 驱动
    // （见 [pageCorners]），不依赖这里。
    //
    // 为什么必须自己监听手势：navigation-compose 2.8.5 没有预测返回支持
    // （jar 里 seekable / predictive 符号数为 0），拖动期间框架不驱动 transition，
    // 页面被卡在屏幕中间时就是个直角矩形。
    var backGesture by remember { mutableStateOf(false) }
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    DisposableEffect(backDispatcher, navController) {
        val callback =
            object : OnBackPressedCallback(false) {
                override fun handleOnBackStarted(backEvent: androidx.activity.BackEventCompat) {
                    if (navController.previousBackStackEntry != null) backGesture = true
                }

                override fun handleOnBackProgressed(backEvent: androidx.activity.BackEventCompat) {
                    if (navController.previousBackStackEntry != null) backGesture = true
                }

                override fun handleOnBackCancelled() {
                    // 手势取消：页面弹回，圆角随之收起
                    backGesture = false
                }

                override fun handleOnBackPressed() {
                    // 手势结束：后续交给框架的 pop 转场（它自己的 transition 会带动圆角）
                    backGesture = false
                    isEnabled = false
                    backDispatcher?.onBackPressed()
                }
            }
        backDispatcher?.addCallback(callback)
        callback.isEnabled = true
        onDispose { callback.remove() }
    }

    var drawerOpen by remember { mutableStateOf(false) }
    val panelWidth = 300.dp
    val density = LocalDensity.current
    val edgeDragAccumulator = remember { mutableFloatStateOf(0f) }
    val panelDragAccumulator = remember { mutableFloatStateOf(0f) }
    val edgeThresholdPx = with(density) { 56.dp.toPx() }

    /**
     * 导航到一个路由。
     *
     * [force] 用于「目的地相同但载荷不同」的场景：点 AI 回答里的链接时，
     * 如果用户上一次已经进过浏览器，`currentRoute` 就是 Web ——
     * 这个守卫会直接 return，地址永远递不进去，表现为
     * 「第一次能点开，第二次点了没反应」（实测反馈）。
     */
    fun navigate(route: String, force: Boolean = false) {
        if (!force && route == currentRoute) {
            drawerOpen = false
            return
        }
        navController.navigate(route) { launchSingleTop = true }
        drawerOpen = false
    }

    Box(modifier = modifier.fillMaxSize().background(colors.background)) {
        val wideScreen = LocalConfiguration.current.screenWidthDp >= 600
        // 压暗纱：位于所有页面**之下**，铺满整屏（含状态栏）。
        // 静止时被不透明的页面盖住、看不见；转场时旧页部分淡出，纱从它背后透出来，
        // 于是只有「上一层」被压暗，新推入的页面不受影响。
        // 反过来把纱放在页面之上会连新页面一起压暗 —— 那是错的。
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = ScrimAlpha)),
        )

        // 页面铺满整屏（含状态栏），留白改由各页顶栏自己负责；
        // 否则状态栏那条没有页面覆盖，转场时会单独发灰、和内容对不上。
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.weight(1f)) {
                if (wideScreen) {
                    MiuixNavRail(currentRoute = currentRoute, onNavigate = { navigate(it) })
                }
                Box(Modifier.weight(1f)) {
                    // 只做溢出裁切：转场滑动时页面内容不会溢出到相邻页
                    Box(Modifier.fillMaxSize().clipToBounds()) {
                    CompositionLocalProvider(
                        LocalDeviceCorner provides pageCorner,
                        LocalBackGesture provides backGesture,
                    ) {
                    ToolboxNavHost(
                        navController = navController,
                        config = config,
                        onConfigChange = onConfigChange,
                        toastState = toastState,
                        onOpenDrawer = { drawerOpen = true },
                        onNavigate = { navigate(it) },
                        onNavigateForce = { navigate(it, force = true) },
                    )
                    }
                    }
                }
            }
            if (!wideScreen) {
            }
        }

        // 遮罩
        AnimatedVisibility(visible = drawerOpen, enter = fadeIn(), exit = fadeOut()) {
            val press = rememberMiuixPressState()
            Box(
                modifier =
                    Modifier.fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.35f))
                        .miuixClickable(press, true) { drawerOpen = false },
            )
        }

        // 抽屉面板
        AnimatedVisibility(
            visible = drawerOpen,
            enter = slideInHorizontally { -it },
            exit = slideOutHorizontally { -it },
        ) {
            Box(
                modifier =
                    Modifier.fillMaxHeight().width(panelWidth).pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                if (panelDragAccumulator.value < -edgeThresholdPx)
                                    drawerOpen = false
                                panelDragAccumulator.value = 0f
                            },
                        ) { _, dragAmount ->
                            panelDragAccumulator.value += dragAmount
                        }
                    },
            ) {
                DrawerContent(
                    currentRoute = currentRoute,
                    onNavigate = { navigate(it) },
                    onClose = { drawerOpen = false },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // 抽屉打开时消费返回键/返回手势：只收起抽屉，不再往下透传。
        //
        // 本页那个常驻 enabled 的 OnBackPressedCallback 只做「重新分发」
        // （见上面 DisposableEffect），并不消费事件；抽屉此前也没人拦，
        // 于是事件一路落到 NavHost。首页是唯一栈底，NavHost 无处可 pop，
        // 最终走 Activity finish —— 表现就是「点开侧边菜单再返回，应用直接退出」。
        //
        // BackHandler 在本组合中晚于上面那个 callback 注册；OnBackPressedDispatcher
        // 按“后注册的先检查”（descendingIterator）分发，所以抽屉开着时由这里先消费。
        BackHandler(enabled = drawerOpen) {
            // 手势路径下，上面那个 callback 的 handleOnBackStarted 可能已经把
            // backGesture 置为 true（二级页开抽屉时 previousBackStackEntry 非空）。
            // 那条清尾逻辑挂在 handleOnBackCancelled 上，而这里走的是正常完成路径，
            // 不主动清的话页面圆角会一直挂着。
            backGesture = false
            drawerOpen = false
        }

        // 左侧边缘不再自绘「滑动打开抽屉」手势：它与系统返回手势热区完全重叠，
        // 会抢掉边缘滑动，导致返回上一级目录失效。抽屉统一走左上角按钮打开。
        LaunchedEffect(Unit) { edgeDragAccumulator.value = 0f }

        MiuixToastHost(state = toastState)

    }
}

/**
 * 一次「用内置浏览器打开链接」的请求。
 *
 * [nonce] 让重复打开同一地址也能被识别为新请求。
 */
private data class WebRequest(val url: String, val nonce: Int)

@Composable
private fun ToolboxNavHost(
    navController: NavHostController,
    config: ThemeConfig,
    onConfigChange: ((ThemeConfig) -> ThemeConfig) -> Unit,
    toastState: MiuixToastState,
    onOpenDrawer: () -> Unit,
    onNavigate: (String) -> Unit,
    /**
     * 同 [onNavigate]，但**即使已经在该路由也会重新导航**。
     *
     * 单独一个参数而不是给 `onNavigate` 加默认值：Kotlin 的函数类型没有默认参数，
     * 硬加会让改动波及本文件里十几处现有调用点。
     */
    onNavigateForce: (String) -> Unit = onNavigate,
) {
    /**
     * 待打开的网页请求。HomeScreen 里点到链接时写入，Web 页加载成功后清空。
     *
     * [nonce] 是必需的：两次点**同一个链接**时地址没变，
     * 光比 URL 无法让 Web 页分辨出「这是新的一次请求」——那样第二次点击就没反应。
     *
     * 声明在这里而不是外层 AppShell：HomeScreen 与 Web 两条路由都在本函数内，
     * 状态放在共同祖先才既能写又能读。
     */
    var pendingWeb by remember { mutableStateOf<WebRequest?>(null) }
    /** 递增的请求序号：让「重复打开同一地址」也能被识别为新请求。 */
    var nextWebNonce by remember { mutableIntStateOf(0) }
    val notConfigured = stringResource(R.string.ai_not_configured)
    val errNetwork = stringResource(R.string.ai_err_network)
    val chatRunning by ChatRunner.running.collectAsState()
    // 未配置时把提示写进会话用的应用级作用域
    val scopeForNotice = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    val aiConfig by AiConfigStore.config.collectAsState()

    // 权限探测放在 NavHost 外，首页与设置页读同一份结果，避免各自重复起进程。
    val shellContext = LocalContext.current
    // 配置只在这里加载一次：聊天页与其他入口读同一份，避免出现「设置页已配置、聊天页说没配」
    LaunchedEffect(Unit) {
        AiConfigStore.load(shellContext)
        ChatUsageStore.load(shellContext)
    }

    // 当前选中项可能是自定义供应商，展示名要从配置里取
    val activeProvider = aiConfig.currentCustom ?: aiConfig.current
    val activeModel = aiConfig.model
    val activeWindow = aiConfig.active.contextWindowOrNull()

    // 把真实窗口同步给记忆预算。
    //
    // 不能只在发请求时同步（原先就是这样）：那样打开记忆页看到的还是「最多 0 token」，
    // 因为一轮请求都还没跑过。这里跟着配置变化同步，进页面就是对的。
    LaunchedEffect(activeProvider, activeModel, activeWindow) {
        ChatMemoryStore.syncModelWindow(shellContext, activeWindow ?: 0)
    }

    // 主动补齐当前模型的真实上下文窗口。
    //
    // 拉取模型时很多网关只返回 id，列表里拿不到窗口；但单模型详情接口通常有。
    // 只在「已配置且该模型确实没有窗口」时查一次，查到就落进配置，之后不再请求。
    LaunchedEffect(activeProvider, activeModel, activeWindow) {
        val cfg = AiConfigStore.config.value
        if (!cfg.ready || activeModel.isBlank() || activeWindow != null) return@LaunchedEffect
        val fetched = withContext(Dispatchers.IO) {
            AiChatClient.fetchContextWindow(cfg).getOrNull()
        } ?: return@LaunchedEffect
        AiConfigStore.updateActive(shellContext) { active ->
            active.copy(
                models = active.models.map {
                    if (it.id == activeModel) it.copy(contextWindow = fetched) else it
                },
            )
        }
    }
    var privilege by remember { mutableStateOf<PrivilegeStatus?>(null) }
    LaunchedEffect(Unit) {
        privilege = withContext(Dispatchers.IO) { PrivilegeManager.status(shellContext) }
    }

    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        modifier = Modifier.fillMaxSize(),
        // 转场：完全对齐官方 NavDisplay 默认 ——
        //   NavTransitionEasing(0.8, 0.95) + 500ms；进入从右侧整屏滑入，旧页向左退 1/4，返回镜像。
        enterTransition = {
            slideInHorizontally(
                initialOffsetX = { it },
                animationSpec = tween(500, easing = NavTransitionEasing.Default),
            )
        },
        exitTransition = {
            // 旧页淡到 OldPageAlpha（不是淡到 0）：透出下方那层压暗纱，
            // 读成「上一层被压暗」。新页面不吃纱，所以只有旧页变暗。
            slideOutHorizontally(
                targetOffsetX = { -it / 4 },
                animationSpec = tween(500, easing = NavTransitionEasing.Default),
            ) + fadeOut(
                tween(500, easing = NavTransitionEasing.Default),
                targetAlpha = OldPageAlpha,
            )
        },
        popEnterTransition = {
            // 被重新露出的那一页由暗转亮
            slideInHorizontally(
                initialOffsetX = { -it / 4 },
                animationSpec = tween(500, easing = NavTransitionEasing.Default),
            ) + fadeIn(
                tween(500, easing = NavTransitionEasing.Default),
                initialAlpha = OldPageAlpha,
            )
        },
        popExitTransition = {
            slideOutHorizontally(
                targetOffsetX = { it },
                animationSpec = tween(500, easing = NavTransitionEasing.Default),
            )
        },
    ) {
            composable(Routes.HOME) {
                Box(Modifier.fillMaxSize().pageCorners(this).background(MiuixTheme.colors.background)) {
                val homeContext = LocalContext.current
                val mcpStates by McpRegistry.states.collectAsState()
                val mcpCalls by McpRegistry.calls.collectAsState()
                LaunchedEffect(Unit) {
                    McpRegistry.load(homeContext)
                }
                HomeScreen(
                    onOpenDrawer = onOpenDrawer,
                    onOpenTerminal = { onNavigate(Routes.LINUX_TERMINAL) },
                    onOpenWeb = { url ->
                        // 用独立的 nonce 记一次「这一轮请求」，而不是让 Web 页去比对 URL。
                        //
                        // 比对 URL 的写法有个死角：两次点同一个链接时地址没变，
                        // Web 页那边看不出「这是新的一次请求」，于是第二次不加载。
                        pendingWeb = WebRequest(url = url, nonce = nextWebNonce++)
                        // force：即使已经停在浏览器页，也必须把新地址递进去
                        onNavigateForce(Routes.WEB)
                    },
                    onStart = { sessionId, assistantMessageId, history ->
                        val cfg = AiConfigStore.config.value
                        if (!cfg.ready) {
                            // 未配置：**不**往会话里塞一条纯文本提示，而是让回答区渲染
                            // 一张「需要配置模型」的 CTA 卡（Spec 第 8.3 节：认证类问题
                            // 给 CTA 卡而不是报错条）。这样用户看到的是可点的下一步，
                            // 不是一段看上去像 AI 在推脱的话。
                            AgentStreamBus.emit { sink ->
                                (sink as? com.mcp.toolbox.feature.home.agent.AgentChatState)
                                    ?.onNotConfigured()
                            }
                        } else {
                            ChatRunner.start(
                                context = shellContext,
                                config = cfg,
                                sessionId = sessionId,
                                assistantMessageId = assistantMessageId,
                                history = history,
                                onFallbackReply = errNetwork,
                            )
                        }
                    },
                    onRetryTool = { messageId, round, seq, toolName, argsJson ->
                        // 返回 null 表示已启动，非 null 是要展示给用户的原因。
                        if (ChatRunner.isBusy()) {
                            "正在生成中，稍后再重试这个工具"
                        } else {
                            // 「这条消息是不是当前正在跑的那一轮」决定结果写到哪里：
                            // 当前轮改内存时间线，历史轮次改写该消息自己的过程快照。
                            var liveState: com.mcp.toolbox.feature.home.agent.AgentChatState? = null
                            AgentStreamBus.emit { sink ->
                                val st = sink as? com.mcp.toolbox.feature.home.agent.AgentChatState
                                if (st?.ownsMessage(messageId) == true) liveState = st
                            }
                            val live = liveState
                            val sid = live?.sessionId
                            if (live != null && sid != null) {
                                live.markToolRetried(round, seq, status = null)
                                val started = ChatRunner.runToolOnce(
                                    context = shellContext,
                                    functionName = AiToolBridge.toFunctionName(toolName),
                                    argsJson = argsJson,
                                    onResult = { ok, raw ->
                                        live.markToolRetried(round, seq, retryStatusOf(ok, raw), raw)
                                    },
                                )
                                if (started) {
                                    null
                                } else {
                                    // 竞态：isBusy 在这两行之间变真。把卡片收回去，
                                    // 别留一个永远转圈的。
                                    live.markToolRetried(
                                        round, seq,
                                        com.mcp.toolbox.feature.home.agent.ToolStatus.Timeout, "",
                                    )
                                    "正在生成中，稍后再重试这个工具"
                                }
                            } else {
                                // 历史轮次：内存时间线里没有这一段，改写该消息自己的
                                // 过程快照。会话 id 理论上必然存在（消息就挂在它下面），
                                // 真取不到就明说，不静默失败。
                                val historySid = ChatStore.currentId.value
                                if (historySid == null) {
                                    "找不到所属会话，无法重试"
                                } else {
                                    retryToolInHistory(
                                        context = shellContext,
                                        sessionId = historySid,
                                        messageId = messageId,
                                        round = round,
                                        seq = seq,
                                        toolName = toolName,
                                        argsJson = argsJson,
                                    )
                                    null
                                }
                            }
                        }
                    },
                    // 跨服务商的可选模型：只列出已拉取过模型列表的服务商
                    modelOptions = buildList {
                        aiConfig.perProvider.values
                            .filter { it.models.isNotEmpty() }
                            .forEach { cfg ->
                                cfg.models.forEach { entry ->
                                    add(
                                        com.mcp.toolbox.feature.home.ModelOption(
                                            providerName = cfg.provider.name,
                                            providerTitle = cfg.provider.title,
                                            providerIconRes = cfg.provider.iconRes,
                                            modelId = entry.id,
                                            isCurrent = aiConfig.currentCustomId == null &&
                                                cfg.provider == aiConfig.current &&
                                                entry.id == cfg.selectedModel,
                                        ),
                                    )
                                }
                            }
                        // 自定义供应商的模型同样要出现在跨服务商列表里
                        aiConfig.customProviders
                            .filter { it.models.isNotEmpty() }
                            .forEach { cp ->
                                cp.models.forEach { entry ->
                                    add(
                                        com.mcp.toolbox.feature.home.ModelOption(
                                            providerName = com.mcp.toolbox.ui.ai.AiProvider.CUSTOM.name,
                                            providerTitle = cp.name,
                                            providerIconRes = com.mcp.toolbox.ui.ai.AiProvider.CUSTOM.iconRes,
                                            modelId = entry.id,
                                            isCurrent = aiConfig.currentCustomId == cp.id &&
                                                entry.id == cp.selectedModel,
                                            customId = cp.id,
                                        ),
                                    )
                                }
                            }
                    },
                    onSelectModelOption = { option ->
                        val customId = option.customId
                        if (customId != null) {
                            AiConfigStore.selectCustom(shellContext, customId)
                        } else {
                            val target = runCatching {
                                com.mcp.toolbox.ui.ai.AiProvider.valueOf(option.providerName)
                            }.getOrNull()
                            if (target != null) AiConfigStore.selectProvider(shellContext, target)
                        }
                        AiConfigStore.selectModel(shellContext, option.modelId)
                    },
                    providerIconRes = aiConfig.currentCustom?.let { com.mcp.toolbox.ui.ai.AiProvider.CUSTOM.iconRes }
                        ?: aiConfig.current.iconRes,
                    running = chatRunning != null,
                    onStop = { ChatRunner.stop(shellContext) },
                    currentModel = aiConfig.model,
                    // 上下文上限必须显式传下去。
                    // 早先算了 `activeWindow` 却只用在记忆预算上，没传给 HomeScreen，
                    // 于是输入栏永远显示「服务端未提供该模型的上下文上限」——
                    // 明明自定义供应商的模型里已经填了 ctx（实测反馈）。
                    contextWindow = activeWindow,
                    currentReasoning = aiConfig.reasoning.label,
                    availableModels = aiConfig.cachedModels.ifEmpty {
                        listOfNotNull(aiConfig.model.takeIf { it.isNotBlank() })
                    },
                    availableReasoning = ReasoningEffort.entries.map { it.label },
                    onSelectModel = { AiConfigStore.selectModel(shellContext, it) },
                    onSelectReasoning = { label ->
                        ReasoningEffort.entries
                            .firstOrNull { it.label == label }
                            ?.let { AiConfigStore.selectReasoning(shellContext, it) }
                    },
                )
                }
            }
            composable(Routes.MEMORY) {
                Box(Modifier.fillMaxSize().pageCorners(this).background(MiuixTheme.colors.background)) {
                    Column(Modifier.fillMaxSize()) {
                        MiuixTopBarPlaceholder(
                            title = stringResource(R.string.memory_title),
                            onBack = { navController.popBackStack() },
                        )
                        AiMemoryScreen()
                    }
                }
            }
            composable(Routes.SETTINGS_THEME) {
                Box(Modifier.fillMaxSize().pageCorners(this).background(MiuixTheme.colors.background)) {
                Column(Modifier.fillMaxSize()) {
                    MiuixTopBarPlaceholder(
                        title = stringResource(R.string.app_screen_theme),
                        onOpenDrawer = onOpenDrawer,
                        onBack = { navController.popBackStack() })
                    ThemeSettingsScreen(
                        config = config,
                        onConfigChange = onConfigChange,
                        toastState = toastState,
                    )
                }
                }
            }
            composable(Routes.SETTINGS) {
                Box(Modifier.fillMaxSize().pageCorners(this).background(MiuixTheme.colors.background)) {
                Column(Modifier.fillMaxSize()) {
                    CompactBackRow(onBack = { navController.popBackStack() })
                    SettingsOverview(
                        onOpenTheme = { onNavigate(Routes.SETTINGS_THEME) },
                        onOpenPrivilege = { onNavigate(Routes.SETTINGS_PRIVILEGE) },
                        privilegeSummary =
                            privilege?.let { "${it.summary}｜${it.detail}" }
                                ?: "正在探测 root / Shizuku…",
                        privilegeUsable = privilege?.usable == true,
                    )
                }
                }
            }
            composable(Routes.AI_SETTINGS) {
                Box(Modifier.fillMaxSize().pageCorners(this).background(MiuixTheme.colors.background)) {
                Column(Modifier.fillMaxSize()) {
                    CompactBackRow(onBack = { navController.popBackStack() })
                    AiSettingsScreen(
                        onOpenProviders = { onNavigate(Routes.AI_PROVIDERS) },
                        onOpenModels = {
                            val snapshot = AiConfigStore.config.value
                            AiHub.pendingProvider = snapshot.current.name
                            // 当前是自定义供应商时，模型页要改的是那一条
                            AiHub.pendingCustomId = snapshot.currentCustomId
                            onNavigate(Routes.AI_MODELS)
                        },
                        onOpenLinux = { onNavigate(Routes.LINUX) },
                    )
                }
                }
            }
            composable(Routes.AI_PROVIDERS) {
                Box(Modifier.fillMaxSize().pageCorners(this).background(MiuixTheme.colors.background)) {
                Column(Modifier.fillMaxSize()) {
                    MiuixTopBarPlaceholder(
                        title = stringResource(R.string.ai_provider_pick),
                        onOpenDrawer = onOpenDrawer,
                        onBack = { navController.popBackStack() })
                    AiProviderListScreen(
                        onOpenProvider = { provider ->
                            AiHub.pendingProvider = provider.name
                            onNavigate(Routes.AI_PROVIDER_DETAIL)
                        },
                    )
                }
                }
            }
            composable(Routes.AI_PROVIDER_DETAIL) {
                Box(Modifier.fillMaxSize().pageCorners(this).background(MiuixTheme.colors.background)) {
                Column(Modifier.fillMaxSize()) {
                    MiuixTopBarPlaceholder(
                        title = stringResource(R.string.ai_provider_title),
                        onOpenDrawer = onOpenDrawer,
                        onBack = { navController.popBackStack() })
                    AiProviderDetailScreen(
                        providerName = AiHub.pendingProvider
                            ?: AiConfigStore.config.value.current.name,
                        onOpenModels = { onNavigate(Routes.AI_MODELS) },
                        onBack = { navController.popBackStack() },
                    )
                }
                }
            }
            composable(Routes.LINUX) {
                Box(Modifier.fillMaxSize().pageCorners(this).background(MiuixTheme.colors.background)) {
                Column(Modifier.fillMaxSize()) {
                    MiuixTopBarPlaceholder(
                        title = stringResource(R.string.app_nav_linux),
                        onOpenDrawer = onOpenDrawer,
                        onBack = { navController.popBackStack() })
                    LinuxScreen(
                        onOpenChecker = { onNavigate(Routes.LINUX_CHECK) },
                        onInstallTool = { component ->
                            LinuxPendingInstall.component = component
                            onNavigate(Routes.LINUX_CHECK)
                        },
                        onOpenTerminal = { onNavigate(Routes.LINUX_TERMINAL) },
                        onOpenWorkspace = {
                            LinuxBrowseTarget.initialPath = "root"
                            onNavigate(Routes.LINUX_FILES)
                        },
                        onOpenShared = { onNavigate(Routes.LINUX_SHARED) },
                        onOpenFiles = {
                            LinuxBrowseTarget.initialPath = ""
                            onNavigate(Routes.LINUX_FILES)
                        },
                    )
                }
                }
            }
            composable(Routes.LINUX_FILES) {
                Box(Modifier.fillMaxSize().pageCorners(this).background(MiuixTheme.colors.background)) {
                Column(Modifier.fillMaxSize()) {
                    MiuixTopBarPlaceholder(
                        title = stringResource(R.string.linux_browse),
                        onOpenDrawer = onOpenDrawer,
                        onBack = { navController.popBackStack() })
                    LinuxFilesScreen(distro = LinuxPrefs.distro(LocalContext.current))
                }
                }
            }
            composable(Routes.LINUX_SHARED) {
                Box(Modifier.fillMaxSize().pageCorners(this).background(MiuixTheme.colors.background)) {
                Column(Modifier.fillMaxSize()) {
                    MiuixTopBarPlaceholder(
                        title = stringResource(R.string.linux_shared),
                        onOpenDrawer = onOpenDrawer,
                        onBack = { navController.popBackStack() })
                    LinuxSharedScreen(distro = LinuxPrefs.distro(LocalContext.current))
                }
                }
            }
            composable(Routes.LINUX_TERMINAL) {
                val termContext = LocalContext.current
                val appContext = termContext.applicationContext
                val termDistro = remember { LinuxPrefs.distro(termContext) }
                var termMode by remember { mutableStateOf(LinuxTermMode.load(termContext)) }
                var termFontSize by remember { mutableFloatStateOf(13.5f) }
                // 窗口列表与会话都放在 TermSessionStore：退出这个页面时进程继续跑，
                // 再进来（或切窗口）靠重放输出恢复画面，而不是重开 shell。
                val tabs by TermSessionStore.tabs
                val currentTab by TermSessionStore.current
                val termux = termMode == LinuxTermMode.TERMUX
                // 待确认关闭的窗口。× 就在标题旁边，误触代价是「正在跑编译的窗口
                // 连同它的进程一起没了」，所以经一次确认再关。
                var pendingClose by remember { mutableStateOf<TermSessionStore.Tab?>(null) }
                /**
                 * 点加号后先问「开哪种终端」。
                 *
                 * 两种环境差别很大（Android 免 Root、Linux 需要已安装的环境），
                 * 直接开一个再让用户切换，等于替他做了选择——而且 Linux 环境
                 * 没装时那个窗口必然是空的。
                 */
                var pickEnv by remember { mutableStateOf(false) }
                /**
                 * 接入 AI 的提示语输入。
                 *
                 * 让用户先写一句「想问什么」再发：直接把一屏终端丢过去，
                 * 模型只能泛泛而谈；带上意图（"这个报错怎么修"）才有用。
                 */
                var termToAi by remember { mutableStateOf(false) }
                var termToAiPrompt by remember { mutableStateOf("") }

                // 只在 Termux 模式需要窗口；简单模式没有常驻会话，不该凭空建一个。
                // 注意这些写入必须放在 LaunchedEffect 里：在组合期间改状态会让
                // Compose 立即重组，形成「写 -> 重组 -> 再写」的循环。
                LaunchedEffect(termux, tabs) {
                    if (!termux) return@LaunchedEffect
                    if (TermSessionStore.tabs.value.isEmpty()) {
                        TermSessionStore.open(appContext, termDistro)
                    } else if (TermSessionStore.current.value == null) {
                        TermSessionStore.select(TermSessionStore.tabs.value.last())
                    }
                }

                Box(Modifier.fillMaxSize().pageCorners(this).background(MiuixTheme.colors.background)) {
                Column(Modifier.fillMaxSize()) {
                    val current = currentTab
                    val tabTitle = current?.let { t -> val name by t.title; name }
                        ?: stringResource(R.string.linux_open_terminal)
                    MiuixTopBarPlaceholder(
                        // 标题跟随当前窗口：跑起来后 shell 会发 OSC 标题（比如 vim 的文件名、
                        // 正在跑的目录），一眼能看出这是哪个窗口
                        title = tabTitle,
                        onOpenDrawer = onOpenDrawer,
                        onBack = { navController.popBackStack() },
                        trailing = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(0.dp),
                            ) {
                                // 模式切换：当前处于 Termux 模式时**只把图标染成主色**。
                                // 不用 filled=true —— 那会把容器画成一个实心圆，看起来像
                                // 一直在转的加载圈（用户明确不要）。变色更轻，也仍是
                                // Material 里「已选中」的常规表达。
                                MiuixIconButton(
                                    iconRes = AgentIcons.ic_svc_pipeline_app,
                                    contentDescription = stringResource(R.string.linux_term_mode_switch),
                                    onClick = {
                                        termMode = LinuxTermMode.toggle(termContext, termMode)
                                    },
                                    tint = if (termux) {
                                        MiuixTheme.colors.primary
                                    } else {
                                        MiuixTheme.colors.onSurfaceVariant
                                    },
                                    buttonSize = 40.dp,
                                    iconSize = 20.dp,
                                )
                                // 接入 AI：把当前终端的输出交给对话页。
                                // 与「新开窗口」并列，因为它俩同属「拿这个终端做什么」。
                                if (termux && currentTab != null) {
                                    MiuixIconButton(
                                        iconRes = AgentIcons.ic_ai_assistant,
                                        contentDescription = stringResource(R.string.linux_term_to_ai),
                                        onClick = { termToAi = true },
                                        tint = MiuixTheme.colors.onSurfaceVariant,
                                        buttonSize = 40.dp,
                                        iconSize = 20.dp,
                                    )
                                }
                                if (termux) {
                                    MiuixIconButton(
                                        iconRes = AgentIcons.ic_plus,
                                        contentDescription = stringResource(R.string.linux_term_new_window),
                                        onClick = {
                                            if (TermSessionStore.tabs.value.size >= TermSessionStore.MAX_TABS) {
                                                Toast.makeText(
                                                    termContext,
                                                    termContext.getString(
                                                        R.string.linux_term_max_windows,
                                                        TermSessionStore.MAX_TABS,
                                                    ),
                                                    Toast.LENGTH_SHORT,
                                                ).show()
                                            } else {
                                                // 先选环境，再开窗口
                                                pickEnv = true
                                            }
                                        },
                                        tint = MiuixTheme.colors.onSurfaceVariant,
                                        buttonSize = 40.dp,
                                        iconSize = 20.dp,
                                    )
                                }
                            }
                        },
                    )
                    if (termux) {
                        TerminalTabStrip(
                            tabs = tabs,
                            currentId = currentTab?.id,
                            onSelect = { TermSessionStore.select(it) },
                            onRequestClose = { pendingClose = it },
                        )
                    }
                    when {
                        !termux -> LinuxTerminalScreen(
                            distro = termDistro,
                            modifier = Modifier.weight(1f),
                        )
                        currentTab == null -> Box(Modifier.weight(1f))
                        else -> key(currentTab!!.id) {
                            LinuxTerminalPanel(
                                tab = currentTab!!,
                                fontSize = termFontSize,
                                modifier = Modifier.weight(1f),
                                onClosed = { navController.popBackStack() },
                            )
                        }
                    }
                }

                if (pickEnv) {
                    LinuxChoiceSheet(
                        title = stringResource(R.string.linux_term_pick_env),
                        options = listOf(
                            stringResource(R.string.linux_term_env_local) to
                                stringResource(R.string.linux_term_env_local_desc),
                            stringResource(R.string.linux_term_env_linux) to
                                stringResource(R.string.linux_term_env_linux_desc),
                        ),
                        selectedIndex = -1,
                        onPick = { idx ->
                            pickEnv = false
                            val env = if (idx == 0) TermSessionStore.Env.LOCAL
                            else TermSessionStore.Env.LINUX
                            TermSessionStore.open(appContext, termDistro, env)
                        },
                        onDismiss = { pickEnv = false },
                    )
                }

                if (termToAi) {
                    val target = currentTab
                    if (target == null) {
                        termToAi = false
                    } else {
                        MiuixDialog(
                            visible = true,
                            onDismiss = { termToAi = false; termToAiPrompt = "" },
                            title = stringResource(R.string.linux_term_to_ai),
                            message = stringResource(R.string.linux_term_to_ai_hint),
                            confirmText = stringResource(R.string.linux_term_to_ai_send),
                            onConfirm = {
                                val text = termToAiPrompt
                                termToAi = false
                                termToAiPrompt = ""
                                TerminalToAi.send(
                                    context = appContext,
                                    tab = target,
                                    distro = termDistro,
                                    prompt = text,
                                    onError = { msg ->
                                        Toast.makeText(appContext, msg, Toast.LENGTH_SHORT).show()
                                    },
                                )
                                onNavigate(Routes.HOME)
                            },
                            dismissText = stringResource(R.string.app_action_close),
                        ) {
                            MiuixTextField(
                                value = termToAiPrompt,
                                onValueChange = { termToAiPrompt = it },
                                placeholder = stringResource(R.string.linux_term_to_ai_placeholder),
                                singleLine = false,
                                minLines = 2,
                            )
                        }
                    }
                }

                pendingClose?.let { target ->
                    val name by target.title
                    // 还剩别的窗口才是「关掉」；只剩它一个时 close() 是就地重开新 shell
                    // （否则会留下一个没有任何窗口的页面）。文案必须跟着这个区别走，
                    // 不能按「是否在跑命令」判断，否则等于骗用户说会关掉。
                    val willClose = tabs.size > 1
                    MiuixDialog(
                        visible = true,
                        onDismiss = { pendingClose = null },
                        title = stringResource(
                            if (willClose) R.string.linux_term_close_title
                            else R.string.linux_term_restart_title,
                            name,
                        ),
                        message = stringResource(
                            if (willClose) R.string.linux_term_close_message
                            else R.string.linux_term_restart_message,
                        ),
                        confirmText = stringResource(
                            if (willClose) R.string.linux_term_close_confirm
                            else R.string.linux_term_restart_confirm,
                        ),
                        destructive = willClose,
                        onConfirm = {
                            val tab = pendingClose ?: return@MiuixDialog
                            pendingClose = null
                            // 真被关掉（还有别的窗口）才需要退回上一个窗口
                            if (TermSessionStore.close(tab)) {
                                TermSessionStore.tabs.value.lastOrNull()?.let(TermSessionStore::select)
                            }
                        },
                    )
                }
                }
            }
            composable(Routes.LINUX_CHECK) {
                Box(Modifier.fillMaxSize().pageCorners(this).background(MiuixTheme.colors.background)) {
                Column(Modifier.fillMaxSize()) {
                    MiuixTopBarPlaceholder(
                        title = stringResource(R.string.linux_check_title),
                        onOpenDrawer = onOpenDrawer,
                        onBack = { navController.popBackStack() })
                    LinuxCheckScreen(distro = LinuxPrefs.distro(LocalContext.current))
                }
                }
            }
            composable(Routes.AI_MODELS) {
                Box(Modifier.fillMaxSize().pageCorners(this).background(MiuixTheme.colors.background)) {
                Column(Modifier.fillMaxSize()) {
                    MiuixTopBarPlaceholder(
                        title = stringResource(R.string.ai_model_manage),
                        onOpenDrawer = onOpenDrawer,
                        onBack = { navController.popBackStack() })
                    AiModelScreen(
                        providerName = AiHub.pendingProvider
                            ?: AiConfigStore.config.value.current.name,
                    )
                }
                }
            }
            composable(Routes.SETTINGS_PRIVILEGE) {
                Box(Modifier.fillMaxSize().pageCorners(this).background(MiuixTheme.colors.background)) {
                Column(Modifier.fillMaxSize()) {
                    MiuixTopBarPlaceholder(
                        title = "权限检测与申请",
                        onOpenDrawer = onOpenDrawer,
                        onBack = { navController.popBackStack() })
                    PrivilegeScreen(onToast = { toastState.show(it) })
                }
                }
            }
            composable(Routes.ABOUT) {
                Box(Modifier.fillMaxSize().pageCorners(this).background(MiuixTheme.colors.background)) {
                Column(Modifier.fillMaxSize()) {
                    CompactBackRow(onBack = { navController.popBackStack() })
                    AboutScreen(toastState = toastState)
                }
                }
            }
            composable(Routes.TOOLS) {
                Box(Modifier.fillMaxSize().pageCorners(this).background(MiuixTheme.colors.background)) {
                Column(Modifier.fillMaxSize()) {
                    CompactBackRow(onBack = { navController.popBackStack() })
                    ToolsScreen(onOpenTool = onNavigate)
                }
                }
            }
            composable(Routes.APPS) {
                Box(Modifier.fillMaxSize().pageCorners(this).background(MiuixTheme.colors.background)) {
                AppsScreen(onBack = { navController.popBackStack() }, onToast = { toastState.show(it) })
                }
            }
            composable(Routes.CAPTURE) {
                Box(Modifier.fillMaxSize().pageCorners(this).background(MiuixTheme.colors.background)) {
                CaptureScreen(onBack = { navController.popBackStack() }, onToast = { toastState.show(it) })
                }
            }
            composable(Routes.DECOMPILE) {
                Box(Modifier.fillMaxSize().pageCorners(this).background(MiuixTheme.colors.background)) {
                DecompileScreen(onBack = { navController.popBackStack() }, onToast = { toastState.show(it) })
                }
            }
            composable(Routes.DATABASE) {
                Box(Modifier.fillMaxSize().pageCorners(this).background(MiuixTheme.colors.background)) {
                DatabaseScreen(onBack = { navController.popBackStack() }, onToast = { toastState.show(it) })
                }
            }
            composable(Routes.NETWORK) {
                Box(Modifier.fillMaxSize().pageCorners(this).background(MiuixTheme.colors.background)) {
                NetworkHubScreen(
                    onBack = { navController.popBackStack() }, onOpenRoute = onNavigate)
                }
            }
            composable(Routes.NETWORK_HTTP) {
                Box(Modifier.fillMaxSize().pageCorners(this).background(MiuixTheme.colors.background)) {
                HttpRequestScreen(
                    onBack = { navController.popBackStack() }, onToast = { toastState.show(it) })
                }
            }
            composable(Routes.NETWORK_PING) {
                Box(Modifier.fillMaxSize().pageCorners(this).background(MiuixTheme.colors.background)) {
                PingScreen(onBack = { navController.popBackStack() })
                }
            }
            composable(Routes.NETWORK_DNS) {
                Box(Modifier.fillMaxSize().pageCorners(this).background(MiuixTheme.colors.background)) { DnsScreen(onBack = { navController.popBackStack() })                 }}
            composable(Routes.WEB) {
                Box(Modifier.fillMaxSize().pageCorners(this).background(MiuixTheme.colors.background)) {
                WebScreen(
                    onBack = { navController.popBackStack() },
                    onToast = { toastState.show(it) },
                    initialUrl = pendingWeb?.url,
                    // nonce 一变就重新加载（同一地址也要重新加载）
                    initialUrlKey = pendingWeb?.nonce,
                    // 加载成功后再清空。
                    // 早先写成「传进去就立刻清空」，那会让 WebScreen 里等待 WebView 的
                    // effect 因 key 变化而重启、加载被取消——点了链接什么都不会发生。
                    onInitialUrlLoaded = { pendingWeb = null },
                )
                }
            }
            composable(Routes.MCP) {
                Box(Modifier.fillMaxSize().pageCorners(this).background(MiuixTheme.colors.background)) {
                McpScreen(
                    onBack = { navController.popBackStack() },
                    onToast = { toastState.show(it) },
                    onOpenArtifacts = { navController.navigate(Routes.ARTIFACTS) },
                )
                }
            }
            composable(Routes.SKILLS) {
                Box(Modifier.fillMaxSize().pageCorners(this).background(MiuixTheme.colors.background)) {
                    SkillScreen(
                        onBack = { navController.popBackStack() },
                        onToast = { toastState.show(it) },
                    )
                }
            }
            composable(Routes.ARTIFACTS) {
                Box(Modifier.fillMaxSize().pageCorners(this).background(MiuixTheme.colors.background)) {
                ArtifactsScreen(
                    onBack = { navController.popBackStack() }, onToast = { toastState.show(it) })
                }
            }
            composable(Routes.NETWORK_PORT_SCAN) {
                Box(Modifier.fillMaxSize().pageCorners(this).background(MiuixTheme.colors.background)) {
                PortScanScreen(onBack = { navController.popBackStack() })
                }
            }
            composable(Routes.NETWORK_WHOIS) {
                Box(Modifier.fillMaxSize().pageCorners(this).background(MiuixTheme.colors.background)) {
                WhoisScreen(
                    onBack = { navController.popBackStack() }, onToast = { toastState.show(it) })
                }
            }
            composable(Routes.NETWORK_ENV) {
                Box(Modifier.fillMaxSize().pageCorners(this).background(MiuixTheme.colors.background)) {
                NetworkEnvScreen(
                    onBack = { navController.popBackStack() }, onToast = { toastState.show(it) })
                }
            }
            // 尚未进入实现阶段的模块统一落到占位页
            val placeholders =
                DrawerPrimary.filter {
                    it.route !in
                        setOf(
                            Routes.HOME,
                            Routes.APPS,
                            Routes.WEB,
                            Routes.NETWORK,
                            Routes.CAPTURE,
                            Routes.DECOMPILE,
                            Routes.DATABASE,
                            Routes.MCP,
                            Routes.ARTIFACTS)
                }
            placeholders.forEach { destination ->
                composable(route(destination)) {
                    // 与其余页面一致：自带根背景，否则会透出上层容器
                    Column(Modifier.fillMaxSize().pageCorners(this).background(MiuixTheme.colors.background)) {
                        MiuixTopBarPlaceholder(
                            title = stringResource(destination.labelRes),
                            onOpenDrawer = onOpenDrawer,
                            onBack = { navController.popBackStack() })
                        FeaturePlaceholderScreen(destination = destination)
                    }
                }
            }
        }
}

private fun route(destination: Destination): String = destination.route

/**
 * 紧凑返回行：只有一个返回箭头，没有标题。
 *
 * 用于页面自身已经写出标题的场景（如「应用工具」「设置」）：这些页从抽屉直接进入，
 * 原本再压一条带标题的顶栏就是重复；完全去掉又没地方返回，
 * 所以保留一个不占视觉重量的返回入口。
 */
@Composable
private fun CompactBackRow(onBack: () -> Unit) {
    val press = rememberMiuixPressState()
    Box(
        modifier =
            Modifier.statusBarsPadding()
                .height(44.dp)
                .width(44.dp)
                .miuixClickable(press, true, onClick = onBack),
        contentAlignment = Alignment.Center,
    ) {
        MiuixIcon(
            AgentIcons.ic_chevron_left,
            stringResource(R.string.app_action_back),
            tint = MiuixTheme.colors.onSurface,
            size = 22.dp,
        )
    }
}

/**
 * 简易顶栏：前置按钮 + 标题（各页在后续阶段会替换为各自的 AppBar）。
 *
 * 一级页用抽屉按钮（[onOpenDrawer]），二级页用返回箭头（[onBack]）。
 * 二级页给返回键而不是汉堡：抽屉里只有一级入口与底部固定项，二级页再挂汉堡
 * 会和系统返回手势给出两套方向相反的退出手势。
 */
@Composable
private fun MiuixTopBarPlaceholder(
    title: String,
    onOpenDrawer: () -> Unit = {},
    onBack: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val back = onBack != null
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .statusBarsPadding()
                .height(56.dp)
                .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val press = rememberMiuixPressState()
        Box(
            modifier =
                Modifier.width(44.dp)
                    .height(44.dp)
                    .miuixClickable(press, true, onClick = onBack ?: onOpenDrawer),
            contentAlignment = Alignment.Center,
        ) {
            MiuixIcon(
                if (back) AgentIcons.ic_chevron_left else AgentIcons.ic_menu,
                stringResource(
                    if (back) R.string.app_action_back else R.string.app_action_open_drawer,
                ),
                tint = MiuixTheme.colors.onSurface,
                size = 22.dp,
            )
        }
        MiuixText(
            text = title,
            style = MiuixTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 8.dp).weight(1f),
            maxLines = 1,
        )
        // 右侧动作槽：目前只有终端页用它放「模式切换」。
        // 不给各页单独开 AppBar 是因为这个顶栏是统一形态，
        // 多一个可选槽位比让每个页面自己重写一遍更省事。
        trailing?.invoke()
    }
}

/** 顶栏右侧的溢出菜单：目前承载语言切换，后续可继续追加全局操作。 */
/** 宽屏（≥600dp）侧边导航栏：与底栏共用入口，避免大屏长期留白。 */
@Composable
private fun MiuixNavRail(currentRoute: String, onNavigate: (String) -> Unit) {
    val colors = MiuixTheme.colors
    val entries = DrawerPrimary + DrawerFooter
    Column(
        modifier =
            Modifier.width(88.dp)
                .fillMaxHeight()
                .background(colors.surface)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        entries.forEach { destination ->
            val selected = destination.route == currentRoute
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                MiuixIconButton(
                    iconRes = destination.icon,
                    contentDescription = stringResource(destination.labelRes),
                    onClick = { onNavigate(destination.route) },
                    filled = selected,
                )
                MiuixText(
                    text = stringResource(destination.labelRes),
                    style = MiuixTheme.typography.labelSmall,
                    color = if (selected) colors.primary else colors.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}


/**
 * 由一次工具执行结果决定卡片终态。
 *
 * `AiToolBridge.invoke` 把执行异常折叠成以「错误：」开头的普通字符串返回，
 * 因此这里按该前缀区分「工具真的报错」与「没有产出（超时）」。不区分的话
 * 超时会显示成「执行失败」，与 Spec 第 7.1 节的文案不符。
 */
private fun retryStatusOf(
    success: Boolean,
    raw: String,
): com.mcp.toolbox.feature.home.agent.ToolStatus = when {
    success -> com.mcp.toolbox.feature.home.agent.ToolStatus.Success
    raw.startsWith("错误：") -> com.mcp.toolbox.feature.home.agent.ToolStatus.Failed
    else -> com.mcp.toolbox.feature.home.agent.ToolStatus.Timeout
}

/**
 * 在**历史轮次**上重试一个工具。
 *
 * 那一段只存在于该消息的 `segmentsJson` 里（工具调用不进消息历史，因此不在内存
 * 时间线中；好在 `args` 一直有落盘，所以参数拿得到）。先标记为 Running 并落盘
 * 让界面立刻有反馈，跑完再写回结果 —— 界面靠会话流更新自动重渲染。
 */
private fun retryToolInHistory(
    context: android.content.Context,
    sessionId: String,
    messageId: String,
    round: Int,
    seq: Int,
    toolName: String,
    argsJson: String?,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    scope.launch {
        val message = ChatStore.sessions.value
            .firstOrNull { it.id == sessionId }?.messages
            ?.firstOrNull { it.id == messageId } ?: return@launch

        suspend fun write(status: com.mcp.toolbox.feature.home.agent.ToolStatus?, result: String) {
            val updated = com.mcp.toolbox.feature.home.agent.AgentSegmentCodec.patchToolSegment(
                json = message.segmentsJson,
                round = round,
                seq = seq,
                status = status,
                result = result,
            ) ?: return
            ChatStore.upsertAssistant(
                context = context,
                sessionId = sessionId,
                messageId = messageId,
                content = message.content,
                segmentsJson = updated,
                turnState = message.turnState,
                time = message.time,
            )
        }

        write(null, "")
        val outcome = runCatching {
            AiToolBridge.invoke(context, AiToolBridge.toFunctionName(toolName), argsJson)
        }
        val raw = outcome.getOrElse { "错误：${it.message ?: it::class.simpleName}" }
        write(retryStatusOf(outcome.isSuccess, raw), raw)
    }
}
