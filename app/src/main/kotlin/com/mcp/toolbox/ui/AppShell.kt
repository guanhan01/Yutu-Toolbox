package com.mcp.toolbox.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import com.mcp.toolbox.core.design.theme.NavTransitionEasing
import com.mcp.toolbox.core.design.theme.UiStyle
import com.mcp.toolbox.core.design.theme.LocalUiStyle
import androidx.compose.foundation.background
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
import com.mcp.toolbox.feature.home.ChatStore
import com.mcp.toolbox.feature.home.ChatMessage
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
import com.mcp.toolbox.feature.home.RunningTool

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
                Box(Modifier.weight(1f)) {
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
    val uiStyle = LocalUiStyle.current
    val notConfigured = stringResource(R.string.ai_not_configured)
    val errNetwork = stringResource(R.string.ai_err_network)
    val chatRunning by ChatRunner.running.collectAsState()
    // 未配置时把提示写进会话用的应用级作用域
    val scopeForNotice = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    val aiConfig by AiConfigStore.config.collectAsState()

    // 权限探测放在 NavHost 外，首页与设置页读同一份结果，避免各自重复起进程。
    val shellContext = LocalContext.current
    // 配置只在这里加载一次：聊天页与其他入口读同一份，避免出现「设置页已配置、聊天页说没配」
    LaunchedEffect(Unit) { AiConfigStore.load(shellContext) }
    var privilege by remember { mutableStateOf<PrivilegeStatus?>(null) }
    LaunchedEffect(Unit) {
        privilege = withContext(Dispatchers.IO) { PrivilegeManager.status(shellContext) }
    }

    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        modifier = Modifier.fillMaxSize(),
        // 一级到二级的转场：轻微右进 + 淡入，返回时反向
        // 二三级界面进入动画。
        // Miuix 风格：完全对齐官方 NavDisplay 的默认转场——
        //   NavTransitionEasing(0.8, 0.95) + 500ms；
        //   进入从右侧整屏滑入，旧页向左退 1/4；返回镜像。
        // 经典风格：保留项目原有的轻量转场（右进 1/5 + 淡入，260ms）。
        enterTransition = {
            if (uiStyle == UiStyle.MIUIX) {
                slideInHorizontally(
                    initialOffsetX = { it },
                    animationSpec = tween(500, easing = NavTransitionEasing.Default),
                )
            } else {
                slideInHorizontally(
                    initialOffsetX = { it / 5 },
                    animationSpec = tween(260),
                ) + fadeIn(tween(220))
            }
        },
        exitTransition = {
            if (uiStyle == UiStyle.MIUIX) {
                slideOutHorizontally(
                    targetOffsetX = { -it / 4 },
                    animationSpec = tween(500, easing = NavTransitionEasing.Default),
                )
            } else {
                fadeOut(tween(140))
            }
        },
        popEnterTransition = {
            if (uiStyle == UiStyle.MIUIX) {
                slideInHorizontally(
                    initialOffsetX = { -it / 4 },
                    animationSpec = tween(500, easing = NavTransitionEasing.Default),
                )
            } else {
                fadeIn(tween(200))
            }
        },
        popExitTransition = {
            if (uiStyle == UiStyle.MIUIX) {
                slideOutHorizontally(
                    targetOffsetX = { it },
                    animationSpec = tween(500, easing = NavTransitionEasing.Default),
                )
            } else {
                slideOutHorizontally(
                    targetOffsetX = { it / 5 },
                    animationSpec = tween(260),
                ) + fadeOut(tween(220))
            }
        },
    ) {
            composable(Routes.HOME) {
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
                    runningReasoning = chatRunning?.reasoning,
                    runningTools = chatRunning?.tools?.map {
                        RunningTool(
                            name = it.name,
                            arguments = it.arguments,
                            result = it.result,
                        )
                    }.orEmpty(),
                    running = chatRunning != null,
                    onStop = { ChatRunner.stop() },
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
            composable(Routes.SETTINGS_THEME) {
                Column(Modifier.fillMaxSize()) {
                    MiuixTopBarPlaceholder(
                        title = stringResource(R.string.app_screen_theme),
                        onOpenDrawer = onOpenDrawer)
                    ThemeSettingsScreen(
                        config = config,
                        onConfigChange = onConfigChange,
                        toastState = toastState,
                    )
                }
            }
            composable(Routes.SETTINGS) {
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
            composable(Routes.AI_SETTINGS) {
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
            composable(Routes.AI_PROVIDERS) {
                Column(Modifier.fillMaxSize()) {
                    MiuixTopBarPlaceholder(
                        title = stringResource(R.string.ai_provider_pick),
                        onOpenDrawer = onOpenDrawer)
                    AiProviderListScreen(
                        onOpenProvider = { provider ->
                            AiHub.pendingProvider = provider.name
                            onNavigate(Routes.AI_PROVIDER_DETAIL)
                        },
                    )
                }
            }
            composable(Routes.AI_PROVIDER_DETAIL) {
                Column(Modifier.fillMaxSize()) {
                    MiuixTopBarPlaceholder(
                        title = stringResource(R.string.ai_provider_title),
                        onOpenDrawer = onOpenDrawer)
                    AiProviderDetailScreen(
                        providerName = AiHub.pendingProvider
                            ?: AiConfigStore.config.value.current.name,
                        onOpenModels = { onNavigate(Routes.AI_MODELS) },
                    )
                }
            }
            composable(Routes.LINUX) {
                Column(Modifier.fillMaxSize()) {
                    MiuixTopBarPlaceholder(
                        title = stringResource(R.string.app_nav_linux),
                        onOpenDrawer = onOpenDrawer)
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
            composable(Routes.LINUX_FILES) {
                Column(Modifier.fillMaxSize()) {
                    MiuixTopBarPlaceholder(
                        title = stringResource(R.string.linux_browse),
                        onOpenDrawer = onOpenDrawer)
                    LinuxFilesScreen(distro = LinuxPrefs.distro(LocalContext.current))
                }
            }
            composable(Routes.LINUX_SHARED) {
                Column(Modifier.fillMaxSize()) {
                    MiuixTopBarPlaceholder(
                        title = stringResource(R.string.linux_shared),
                        onOpenDrawer = onOpenDrawer)
                    LinuxSharedScreen(distro = LinuxPrefs.distro(LocalContext.current))
                }
            }
            composable(Routes.LINUX_TERMINAL) {
                Column(Modifier.fillMaxSize()) {
                    MiuixTopBarPlaceholder(
                        title = stringResource(R.string.linux_open_terminal),
                        onOpenDrawer = onOpenDrawer)
                    LinuxTerminalScreen(distro = LinuxPrefs.distro(LocalContext.current))
                }
            }
            composable(Routes.LINUX_CHECK) {
                Column(Modifier.fillMaxSize()) {
                    MiuixTopBarPlaceholder(
                        title = stringResource(R.string.linux_check_title),
                        onOpenDrawer = onOpenDrawer)
                    LinuxCheckScreen(distro = LinuxPrefs.distro(LocalContext.current))
                }
            }
            composable(Routes.AI_MODELS) {
                Column(Modifier.fillMaxSize()) {
                    MiuixTopBarPlaceholder(
                        title = stringResource(R.string.ai_model_manage),
                        onOpenDrawer = onOpenDrawer)
                    AiModelScreen(
                        providerName = AiHub.pendingProvider
                            ?: AiConfigStore.config.value.current.name,
                    )
                }
            }
            composable(Routes.SETTINGS_PRIVILEGE) {
                Column(Modifier.fillMaxSize()) {
                    MiuixTopBarPlaceholder(title = "权限检测与申请", onOpenDrawer = onOpenDrawer)
                    PrivilegeScreen(onToast = { toastState.show(it) })
                }
            }
            composable(Routes.ABOUT) {
                Column(Modifier.fillMaxSize()) {
                    MiuixTopBarPlaceholder(
                        title = stringResource(R.string.app_screen_about),
                        onOpenDrawer = onOpenDrawer)
                    AboutScreen(toastState = toastState)
                }
            }
            composable(Routes.TOOLS) {
                Column(Modifier.fillMaxSize()) {
                    MiuixTopBarPlaceholder(
                        title = stringResource(R.string.app_drawer_group_tools),
                        onOpenDrawer = onOpenDrawer)
                    ToolsScreen(onOpenTool = onNavigate)
                }
            }
            composable(Routes.APPS) {
                AppsScreen(onOpenDrawer = onOpenDrawer, onToast = { toastState.show(it) })
            }
            composable(Routes.CAPTURE) {
                CaptureScreen(onOpenDrawer = onOpenDrawer, onToast = { toastState.show(it) })
            }
            composable(Routes.DECOMPILE) {
                DecompileScreen(onOpenDrawer = onOpenDrawer, onToast = { toastState.show(it) })
            }
            composable(Routes.DATABASE) {
                DatabaseScreen(onBack = onOpenDrawer, onToast = { toastState.show(it) })
            }
            composable(Routes.NETWORK) {
                NetworkHubScreen(onOpenDrawer = onOpenDrawer, onOpenRoute = onNavigate)
            }
            composable(Routes.NETWORK_HTTP) {
                HttpRequestScreen(
                    onBack = { navController.popBackStack() }, onToast = { toastState.show(it) })
            }
            composable(Routes.NETWORK_PING) {
                PingScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.NETWORK_DNS) { DnsScreen(onBack = { navController.popBackStack() }) }
            composable(Routes.WEB) {
                WebScreen(onOpenDrawer = onOpenDrawer, onToast = { toastState.show(it) })
            }
            composable(Routes.MCP) {
                McpScreen(
                    onOpenDrawer = onOpenDrawer,
                    onToast = { toastState.show(it) },
                    onOpenArtifacts = { navController.navigate(Routes.ARTIFACTS) },
                )
            }
            composable(Routes.ARTIFACTS) {
                ArtifactsScreen(
                    onBack = { navController.popBackStack() }, onToast = { toastState.show(it) })
            }
            composable(Routes.NETWORK_PORT_SCAN) {
                PortScanScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.NETWORK_WHOIS) {
                WhoisScreen(
                    onBack = { navController.popBackStack() }, onToast = { toastState.show(it) })
            }
            composable(Routes.NETWORK_ENV) {
                NetworkEnvScreen(
                    onBack = { navController.popBackStack() }, onToast = { toastState.show(it) })
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
                            onOpenDrawer = onOpenDrawer)
                        FeaturePlaceholderScreen(destination = destination)
                    }
                }
            }
        }
}

private fun route(destination: Destination): String = destination.route

/** 简易顶栏：抽屉按钮 + 标题（各页在后续阶段会替换为各自的 AppBar）。 */
@Composable
private fun MiuixTopBarPlaceholder(title: String, onOpenDrawer: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val press = rememberMiuixPressState()
        Box(
            modifier =
                Modifier.width(44.dp)
                    .height(44.dp)
                    .miuixClickable(press, true, onClick = onOpenDrawer),
            contentAlignment = Alignment.Center,
        ) {
            MiuixIcon(
                Icons.Outlined.Menu,
                stringResource(R.string.app_action_open_drawer),
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
