package com.mcp.toolbox.ui

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import com.mcp.toolbox.ui.linux.LinuxScreen
import com.mcp.toolbox.ui.linux.LinuxTerminalScreen
import com.mcp.toolbox.ui.linux.LinuxDistro
import com.mcp.toolbox.ui.linux.LinuxCheckScreen
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import com.mcp.toolbox.feature.home.ChatMemoryStore
import com.mcp.toolbox.feature.home.ChatStore
import com.mcp.toolbox.feature.home.ChatUsageStore
import com.mcp.toolbox.feature.home.CompressionStore
import com.mcp.toolbox.feature.home.PlanStore
import com.mcp.toolbox.ui.ai.AiCompressor
import com.mcp.toolbox.feature.home.ChatMessage
import com.mcp.toolbox.ui.ai.AiMemoryScreen
import com.mcp.toolbox.ui.ai.ChatRunner
import com.mcp.toolbox.ui.ai.ReasoningEffort
import com.mcp.toolbox.ui.ai.AiChatClient
import com.mcp.toolbox.feature.mcp.ArtifactsScreen
import com.mcp.toolbox.feature.mcp.BuiltInMcpServer
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
import com.mcp.toolbox.feature.home.RunningStep

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

    var drawerOpen by remember { mutableStateOf(false) }
    val panelWidth = 300.dp
    val density = LocalDensity.current
    val edgeDragAccumulator = remember { mutableFloatStateOf(0f) }
    val panelDragAccumulator = remember { mutableFloatStateOf(0f) }
    val edgeThresholdPx = with(density) { 56.dp.toPx() }

    fun navigate(route: String) {
        if (route == currentRoute) {
            drawerOpen = false
            return
        }
        navController.navigate(route) { launchSingleTop = true }
        drawerOpen = false
    }

    Box(modifier = modifier.fillMaxSize().background(colors.background)) {
        val wideScreen = LocalConfiguration.current.screenWidthDp >= 600
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.weight(1f)) {
                if (wideScreen) {
                    MiuixNavRail(currentRoute = currentRoute, onNavigate = { navigate(it) })
                }
                Box(
                    Modifier
                        .weight(1f)
                        // 二级页面圆角：始终圆左侧两角。
                        // 静止时圆角处露出的是父级 colors.background，与页面自身同色，看不见；
                        // 而二级页从右侧滑入的那 500ms 里，上一页还在下面，
                        // 这两处圆角就把新页读成「一张推上来的卡片」。
                        // 只在抽屉打开时圆角是错的 —— 转场发生时抽屉本来就是关的。
                        .clip(
                            RoundedCornerShape(
                                topStart = MiuixTheme.radius.dialog,
                                bottomStart = MiuixTheme.radius.dialog,
                            ),
                        )
                        .background(colors.background)
                        // 裁切：转场滑动时页面内容（尤其顶栏 actions）不会溢出到相邻页
                        .clipToBounds(),
                ) {
                    ToolboxNavHost(
                        navController = navController,
                        config = config,
                        onConfigChange = onConfigChange,
                        toastState = toastState,
                        onOpenDrawer = { drawerOpen = true },
                        onNavigate = { navigate(it) },
                    )
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

        // 左侧边缘不再自绘「滑动打开抽屉」手势：它与系统返回手势热区完全重叠，
        // 会抢掉边缘滑动，导致返回上一级目录失效。抽屉统一走左上角按钮打开。
        LaunchedEffect(Unit) { edgeDragAccumulator.value = 0f }

        MiuixToastHost(state = toastState)

    }
}

@Composable
private fun ToolboxNavHost(
    navController: NavHostController,
    config: ThemeConfig,
    onConfigChange: ((ThemeConfig) -> ThemeConfig) -> Unit,
    toastState: MiuixToastState,
    onOpenDrawer: () -> Unit,
    onNavigate: (String) -> Unit,
) {
    val notConfigured = stringResource(R.string.ai_not_configured)
    val errNetwork = stringResource(R.string.ai_err_network)
    val chatRunning by ChatRunner.running.collectAsState()
    val planEnabled by PlanStore.enabled.collectAsState()
    val compressStates by CompressionStore.states.collectAsState()
    // 未配置时把提示写进会话用的应用级作用域
    val scopeForNotice = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    var compressing by remember { mutableStateOf(false) }
    val compressDone = stringResource(R.string.chat_usage_compress_done)
    val compressFail = stringResource(R.string.chat_usage_compress_fail)
    val aiConfig by AiConfigStore.config.collectAsState()

    // 权限探测放在 NavHost 外，首页与设置页读同一份结果，避免各自重复起进程。
    val shellContext = LocalContext.current
    // 配置只在这里加载一次：聊天页与其他入口读同一份，避免出现「设置页已配置、聊天页说没配」
    LaunchedEffect(Unit) {
        AiConfigStore.load(shellContext)
        ChatUsageStore.load(shellContext)
        CompressionStore.load(shellContext)
    }

    val activeProvider = aiConfig.current
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
            slideOutHorizontally(
                targetOffsetX = { -it / 4 },
                animationSpec = tween(500, easing = NavTransitionEasing.Default),
            )
        },
        popEnterTransition = {
            slideInHorizontally(
                initialOffsetX = { -it / 4 },
                animationSpec = tween(500, easing = NavTransitionEasing.Default),
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
                Box(Modifier.fillMaxSize().background(MiuixTheme.colors.background)) {
                val homeContext = LocalContext.current
                val mcpStates by McpRegistry.states.collectAsState()
                val mcpCalls by McpRegistry.calls.collectAsState()
                val builtInRunning by BuiltInMcpServer.running.collectAsState()
                LaunchedEffect(Unit) {
                    BuiltInMcpServer.load(homeContext)
                    McpRegistry.load(homeContext)
                }
                HomeScreen(
                    onOpenDrawer = onOpenDrawer,
                    onOpenTerminal = { onNavigate(Routes.LINUX_TERMINAL) },
                    onStart = { sessionId, history ->
                        val cfg = AiConfigStore.config.value
                        if (!cfg.ready) {
                            // 未配置：直接把提示写进会话，不占用请求通道
                            scopeForNotice.launch {
                                ChatStore.append(
                                    shellContext, sessionId,
                                    ChatMessage(
                                        role = ChatMessage.Role.ASSISTANT,
                                        content = notConfigured,
                                    ),
                                )
                            }
                        } else {
                            ChatRunner.start(
                                context = shellContext,
                                config = cfg,
                                sessionId = sessionId,
                                history = history,
                                onFallbackReply = errNetwork,
                            )
                        }
                    },
                    // 跨服务商的可选模型：只列出已拉取过模型列表的服务商
                    modelOptions = aiConfig.perProvider.values
                        .filter { it.models.isNotEmpty() }
                        .flatMap { cfg ->
                            cfg.models.map { entry ->
                                com.mcp.toolbox.feature.home.ModelOption(
                                    providerName = cfg.provider.name,
                                    providerTitle = cfg.provider.title,
                                    providerIconRes = cfg.provider.iconRes,
                                    modelId = entry.id,
                                    isCurrent = cfg.provider == aiConfig.current &&
                                        entry.id == cfg.selectedModel,
                                )
                            }
                        },
                    onSelectModelOption = { option ->
                        val target = runCatching {
                            com.mcp.toolbox.ui.ai.AiProvider.valueOf(option.providerName)
                        }.getOrNull()
                        if (target != null) {
                            AiConfigStore.selectProvider(shellContext, target)
                            AiConfigStore.selectModel(shellContext, option.modelId)
                        }
                    },
                    providerIconRes = aiConfig.current.iconRes,
                    runningText = chatRunning?.streamed,
                    // 直接映射时间线，保持思考与工具调用的先后关系
                    runningTimeline = chatRunning?.timeline?.map { step ->
                        when (step) {
                            is ChatRunner.Step.Thinking -> RunningStep.Thinking(
                                text = step.text,
                                live = step.live,
                                elapsedMs = step.elapsedMs,
                            )
                            is ChatRunner.Step.Narration ->
                                RunningStep.Narration(text = step.text)
                            is ChatRunner.Step.Tool -> RunningStep.Tool(
                                name = step.name,
                                arguments = step.arguments,
                                result = step.result,
                                elapsedMs = step.elapsedMs,
                            )
                        }
                    }.orEmpty(),
                    running = chatRunning != null,
                    onStop = { ChatRunner.stop(shellContext) },
                    contextWindow = aiConfig.active.contextWindowOrNull(),
                    planEnabled = planEnabled,
                    onTogglePlan = { enabled ->
                        scopeForNotice.launch { PlanStore.setEnabled(shellContext, enabled) }
                    },
                    compressing = compressing,
                    onCompress = {
                        val cfg = AiConfigStore.config.value
                        val active = ChatStore.current()
                        if (active == null) {
                            compressing = false
                        } else if (!cfg.ready) {
                            compressing = false
                            scopeForNotice.launch {
                                ChatStore.append(
                                    shellContext, active.id,
                                    ChatMessage(
                                        role = ChatMessage.Role.ASSISTANT,
                                        content = notConfigured,
                                    ),
                                )
                            }
                        } else {
                            compressing = true
                            scopeForNotice.launch {
                                val result = AiCompressor.compress(shellContext, cfg, active)
                                compressing = false
                                ChatStore.append(
                                    shellContext, active.id,
                                    ChatMessage(
                                        role = ChatMessage.Role.SYSTEM,
                                        content = result.fold(
                                            onSuccess = {
                                                compressDone.format(it.messageCount)
                                            },
                                            onFailure = { compressFail.format(it.message ?: "") },
                                        ),
                                    ),
                                )
                            }
                        }
                    },
                    currentModel = aiConfig.model,
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
                Box(Modifier.fillMaxSize().background(MiuixTheme.colors.background)) {
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
                Box(Modifier.fillMaxSize().background(MiuixTheme.colors.background)) {
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
                Box(Modifier.fillMaxSize().background(MiuixTheme.colors.background)) {
                Column(Modifier.fillMaxSize()) {
                    MiuixTopBarPlaceholder(
                        title = stringResource(R.string.app_screen_settings),
                        onOpenDrawer = onOpenDrawer)
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
                Box(Modifier.fillMaxSize().background(MiuixTheme.colors.background)) {
                Column(Modifier.fillMaxSize()) {
                    MiuixTopBarPlaceholder(
                        title = stringResource(R.string.app_screen_ai),
                        onOpenDrawer = onOpenDrawer)
                    AiSettingsScreen(
                        onOpenProviders = { onNavigate(Routes.AI_PROVIDERS) },
                        onOpenModels = {
                            AiHub.pendingProvider = AiConfigStore.config.value.current.name
                            onNavigate(Routes.AI_MODELS)
                        },
                        onOpenLinux = { onNavigate(Routes.LINUX) },
                    )
                }
                }
            }
            composable(Routes.AI_PROVIDERS) {
                Box(Modifier.fillMaxSize().background(MiuixTheme.colors.background)) {
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
                Box(Modifier.fillMaxSize().background(MiuixTheme.colors.background)) {
                Column(Modifier.fillMaxSize()) {
                    MiuixTopBarPlaceholder(
                        title = stringResource(R.string.ai_provider_title),
                        onOpenDrawer = onOpenDrawer,
                        onBack = { navController.popBackStack() })
                    AiProviderDetailScreen(
                        providerName = AiHub.pendingProvider
                            ?: AiConfigStore.config.value.current.name,
                        onOpenModels = { onNavigate(Routes.AI_MODELS) },
                    )
                }
                }
            }
            composable(Routes.LINUX) {
                Box(Modifier.fillMaxSize().background(MiuixTheme.colors.background)) {
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
                Box(Modifier.fillMaxSize().background(MiuixTheme.colors.background)) {
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
                Box(Modifier.fillMaxSize().background(MiuixTheme.colors.background)) {
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
                Box(Modifier.fillMaxSize().background(MiuixTheme.colors.background)) {
                Column(Modifier.fillMaxSize()) {
                    MiuixTopBarPlaceholder(
                        title = stringResource(R.string.linux_open_terminal),
                        onOpenDrawer = onOpenDrawer,
                        onBack = { navController.popBackStack() })
                    LinuxTerminalScreen(distro = LinuxPrefs.distro(LocalContext.current))
                }
                }
            }
            composable(Routes.LINUX_CHECK) {
                Box(Modifier.fillMaxSize().background(MiuixTheme.colors.background)) {
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
                Box(Modifier.fillMaxSize().background(MiuixTheme.colors.background)) {
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
                Box(Modifier.fillMaxSize().background(MiuixTheme.colors.background)) {
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
                Box(Modifier.fillMaxSize().background(MiuixTheme.colors.background)) {
                Column(Modifier.fillMaxSize()) {
                    MiuixTopBarPlaceholder(
                        title = stringResource(R.string.app_screen_about),
                        onOpenDrawer = onOpenDrawer)
                    AboutScreen(toastState = toastState)
                }
                }
            }
            composable(Routes.TOOLS) {
                Box(Modifier.fillMaxSize().background(MiuixTheme.colors.background)) {
                Column(Modifier.fillMaxSize()) {
                    MiuixTopBarPlaceholder(
                        title = stringResource(R.string.app_drawer_group_tools),
                        onOpenDrawer = onOpenDrawer)
                    ToolsScreen(onOpenTool = onNavigate)
                }
                }
            }
            composable(Routes.APPS) {
                Box(Modifier.fillMaxSize().background(MiuixTheme.colors.background)) {
                AppsScreen(onBack = { navController.popBackStack() }, onToast = { toastState.show(it) })
                }
            }
            composable(Routes.CAPTURE) {
                Box(Modifier.fillMaxSize().background(MiuixTheme.colors.background)) {
                CaptureScreen(onBack = { navController.popBackStack() }, onToast = { toastState.show(it) })
                }
            }
            composable(Routes.DECOMPILE) {
                Box(Modifier.fillMaxSize().background(MiuixTheme.colors.background)) {
                DecompileScreen(onBack = { navController.popBackStack() }, onToast = { toastState.show(it) })
                }
            }
            composable(Routes.DATABASE) {
                Box(Modifier.fillMaxSize().background(MiuixTheme.colors.background)) {
                DatabaseScreen(onBack = { navController.popBackStack() }, onToast = { toastState.show(it) })
                }
            }
            composable(Routes.NETWORK) {
                Box(Modifier.fillMaxSize().background(MiuixTheme.colors.background)) {
                NetworkHubScreen(
                    onBack = { navController.popBackStack() }, onOpenRoute = onNavigate)
                }
            }
            composable(Routes.NETWORK_HTTP) {
                Box(Modifier.fillMaxSize().background(MiuixTheme.colors.background)) {
                HttpRequestScreen(
                    onBack = { navController.popBackStack() }, onToast = { toastState.show(it) })
                }
            }
            composable(Routes.NETWORK_PING) {
                Box(Modifier.fillMaxSize().background(MiuixTheme.colors.background)) {
                PingScreen(onBack = { navController.popBackStack() })
                }
            }
            composable(Routes.NETWORK_DNS) {
                Box(Modifier.fillMaxSize().background(MiuixTheme.colors.background)) { DnsScreen(onBack = { navController.popBackStack() })                 }}
            composable(Routes.WEB) {
                Box(Modifier.fillMaxSize().background(MiuixTheme.colors.background)) {
                WebScreen(onBack = { navController.popBackStack() }, onToast = { toastState.show(it) })
                }
            }
            composable(Routes.MCP) {
                Box(Modifier.fillMaxSize().background(MiuixTheme.colors.background)) {
                McpScreen(
                    onBack = { navController.popBackStack() },
                    onToast = { toastState.show(it) },
                    onOpenArtifacts = { navController.navigate(Routes.ARTIFACTS) },
                )
                }
            }
            composable(Routes.ARTIFACTS) {
                Box(Modifier.fillMaxSize().background(MiuixTheme.colors.background)) {
                ArtifactsScreen(
                    onBack = { navController.popBackStack() }, onToast = { toastState.show(it) })
                }
            }
            composable(Routes.NETWORK_PORT_SCAN) {
                Box(Modifier.fillMaxSize().background(MiuixTheme.colors.background)) {
                PortScanScreen(onBack = { navController.popBackStack() })
                }
            }
            composable(Routes.NETWORK_WHOIS) {
                Box(Modifier.fillMaxSize().background(MiuixTheme.colors.background)) {
                WhoisScreen(
                    onBack = { navController.popBackStack() }, onToast = { toastState.show(it) })
                }
            }
            composable(Routes.NETWORK_ENV) {
                Box(Modifier.fillMaxSize().background(MiuixTheme.colors.background)) {
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
                    Column(Modifier.fillMaxSize()) {
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
) {
    val back = onBack != null
    Row(
        modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
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
                if (back) Icons.AutoMirrored.Outlined.ArrowBack else Icons.Outlined.Menu,
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
                    icon = destination.icon,
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
