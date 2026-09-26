package com.mcp.toolbox.feature.capture

import android.Manifest
import android.net.VpnService
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mcp.toolbox.core.design.component.MiuixButton
import com.mcp.toolbox.core.design.component.MiuixButtonVariant
import com.mcp.toolbox.core.design.component.MiuixDialog
import com.mcp.toolbox.core.design.component.MiuixDivider
import com.mcp.toolbox.core.design.component.MiuixFilterChip
import com.mcp.toolbox.core.design.component.MiuixIcon
import com.mcp.toolbox.core.design.component.MiuixIconButton
import com.mcp.toolbox.core.design.component.MiuixMenuItem
import com.mcp.toolbox.core.design.component.MiuixOverflowMenu
import com.mcp.toolbox.core.design.component.MiuixSearchField
import com.mcp.toolbox.core.design.component.MiuixText
import com.mcp.toolbox.core.design.component.MiuixTopAppBar
import com.mcp.toolbox.core.design.component.StatusCodeText
import com.mcp.toolbox.core.design.component.miuixClickable
import com.mcp.toolbox.core.design.component.rememberMiuixPressState
import com.mcp.toolbox.core.design.theme.MiuixTheme
import kotlinx.coroutines.launch

/** 抓包页：真实 VpnService 会话 + 请求列表 + 详情 + 导出。 列表数据全部来自本机 tun 读到的真实报文（明文 HTTP / TLS 握手 / DNS）。 */
/** 抓包记录类型筛选的「全部」哨兵值，仅作数据键，不参与显示。 */
private const val FILTER_ALL = "ALL"

@Composable
fun CaptureScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onToast: (String) -> Unit = {},
) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by CaptureStore.state.collectAsState()

    var filter by remember { mutableStateOf(FILTER_ALL) }
    var query by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var bannerVisible by remember { mutableStateOf(true) }
    var configOpen by remember { mutableStateOf(false) }
    var caOpen by remember { mutableStateOf(false) }
    var appsOpen by remember { mutableStateOf(false) }
    var exportOpen by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<CaptureRecord?>(null) }
    var dnsLogOpen by remember { mutableStateOf(false) }
    var showGuide by remember { mutableStateOf(false) }
    var config by remember { mutableStateOf(CapturePrefs.load(context)) }
    var decryptOn by remember { mutableStateOf(CaptureFlags.httpsDecrypt) }
    val str = captureScreenStrings()

    val vpnLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                CaptureVpnService.start(context, config)
                onToast(str.toastVpnGranted)
            } else {
                onToast(str.toastVpnDenied)
            }
        }
    val notificationLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            if (!granted) onToast(str.toastNoNotification)
        }

    val filters = listOf(FILTER_ALL, "HTTP", "HTTPS", "TCP", "UDP", "DNS")
    val visible =
        state.records.filter { record ->
            val matchesKind =
                when (filter) {
                    FILTER_ALL -> true
                    else -> record.kind.name == filter
                }
            val text =
                "${record.host ?: ""}${record.path ?: ""}${record.dstIp}${record.appLabel ?: ""}"
            matchesKind && (query.isBlank() || text.contains(query, ignoreCase = true))
        }

    fun startCapture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val intent = VpnService.prepare(context)
        if (intent != null) {
            vpnLauncher.launch(intent)
        } else {
            CaptureVpnService.start(context, config)
            onToast(str.toastStarting)
        }
    }

    Column(modifier = modifier.fillMaxSize().background(colors.background)) {
        AnimatedVisibility(
            visible = bannerVisible,
            enter = expandVertically(tween(MiuixTheme.motion.fast)),
            exit = shrinkVertically(tween(MiuixTheme.motion.fast)),
        ) {
            ComplianceBanner(
                onDetail = { showGuide = true },
                onDismiss = { bannerVisible = false },
            )
        }

        MiuixTopAppBar(
            title = str.title,
            navigationIcon = Icons.AutoMirrored.Outlined.ArrowBack,
            onNavigationClick = onBack,
            actions = {
                MiuixIconButton(
                    Icons.Outlined.Settings, str.actionSettings, onClick = { configOpen = true })
                Box {
                    MiuixIconButton(
                        Icons.Outlined.MoreHoriz, str.actionMore, onClick = { menuOpen = true })
                    MiuixOverflowMenu(expanded = menuOpen, onDismiss = { menuOpen = false }) {
                        MiuixMenuItem(
                            str.menuScope,
                            {
                                menuOpen = false
                                appsOpen = true
                            },
                            icon = Icons.Outlined.FilterList,
                        )
                        MiuixMenuItem(
                            str.menuDnsLog,
                            {
                                menuOpen = false
                                dnsLogOpen = true
                            },
                            icon = Icons.Outlined.Bolt,
                            trailingText = state.dnsLog.size.toString(),
                        )
                        MiuixMenuItem(
                            str.menuCa,
                            {
                                menuOpen = false
                                caOpen = true
                            },
                            icon = Icons.Outlined.Security,
                        )
                        MiuixMenuItem(
                            str.menuDecrypt,
                            {
                                val next = !decryptOn
                                decryptOn = next
                                CaptureFlags.httpsDecrypt = next
                                config = config.copy(httpsDecrypt = next)
                                CapturePrefs.save(context, config)
                                onToast(if (next) str.toastDecryptOn else str.toastDecryptOff)
                                menuOpen = false
                            },
                            icon = Icons.Outlined.Lock,
                            trailingText = if (decryptOn) str.stateOn else str.stateOff,
                        )
                        MiuixMenuItem(
                            str.menuExport,
                            {
                                menuOpen = false
                                exportOpen = true
                            },
                            icon = Icons.Outlined.FileDownload,
                        )
                        MiuixMenuItem(
                            str.actionClear,
                            {
                                menuOpen = false
                                CaptureStore.clear()
                                onToast(str.toastCleared)
                            },
                            icon = Icons.Outlined.DeleteSweep,
                            danger = true,
                        )
                    }
                }
            },
        )

        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(horizontal = spacing.pageHorizontal)
                    .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            filters.forEach { item ->
                MiuixFilterChip(
                    label = if (item == FILTER_ALL) str.filterAll else item,
                    selected = filter == item,
                    onClick = { filter = item })
            }
        }

        Spacer(Modifier.height(spacing.sm))
        MiuixSearchField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.pageHorizontal),
            placeholder = str.searchHint,
        )
        Spacer(Modifier.height(spacing.sm))

        if (visible.isEmpty()) {
            Box(modifier = Modifier.weight(1f)) {
                EmptyCaptureHint(
                    running = state.running,
                    dnsOnly = state.dnsOnly,
                    statusText = state.statusText,
                    error = state.error,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                items(visible, key = { it.id }) { record ->
                    CaptureRow(record = record, onClick = { selected = record })
                    MiuixDivider(startIndent = 20.dp)
                }
            }
        }

        CaptureStatusPanel(
            state = state,
            onToggleRunning = {
                if (state.running) {
                    CaptureVpnService.stop(context)
                    onToast(str.toastStopped)
                } else {
                    startCapture()
                }
            },
            onTogglePause = {
                CaptureVpnService.setPaused(context, !state.paused)
                onToast(if (state.paused) str.toastResumed else str.toastPaused)
            },
            onClear = {
                CaptureStore.clear()
                onToast(str.toastCleared)
            },
            onExport = { exportOpen = true },
        )
    }

    // 关闭后仍要留在组合里把收回动画播完，所以记住最后一次选中的记录。
    var lastSelected by remember { mutableStateOf<CaptureRecord?>(null) }
    if (selected != null) lastSelected = selected
    lastSelected?.let { detail ->
        CaptureDetailSheet(
            visible = selected != null,
            record = detail,
            onDismiss = { selected = null },
            onToast = onToast,
        )
    }
    CaptureConfigSheet(
        visible = configOpen,
        config = config,
        onDismiss = { configOpen = false },
        onApply = { updated ->
            config = updated
            CapturePrefs.save(context, updated)
            configOpen = false
            onToast(str.toastConfigSaved)
        },
    )
    AppFilterSheet(
        visible = appsOpen,
        config = config,
        onDismiss = { appsOpen = false },
        onApply = { updated ->
            config = updated
            CapturePrefs.save(context, updated)
            appsOpen = false
            onToast(str.toastAppFilterSaved)
        },
    )
    CertificateSheet(
        visible = caOpen,
        onDismiss = { caOpen = false },
        onToast = onToast,
    )
    ExportSheet(
        visible = exportOpen,
        records = state.records,
        onDismiss = { exportOpen = false },
        onToast = onToast,
    )
    DnsLogSheet(
        visible = dnsLogOpen,
        observations = state.dnsLog,
        onDismiss = { dnsLogOpen = false },
    )
    MiuixDialog(
        visible = showGuide,
        onDismiss = { showGuide = false },
        title = str.noticeTitle,
        confirmText = str.noticeConfirm,
        dismissText = null,
        onConfirm = { showGuide = false },
        message = str.noticeBody,
    )
}

@Composable
private fun ComplianceBanner(onDetail: () -> Unit, onDismiss: () -> Unit) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .padding(horizontal = spacing.pageHorizontal, vertical = spacing.sm)
                .background(colors.warningContainer, RoundedCornerShape(MiuixTheme.radius.sm))
                .padding(horizontal = spacing.md, vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MiuixIcon(Icons.Outlined.ErrorOutline, null, tint = colors.onWarningContainer, size = 18.dp)
        Spacer(Modifier.width(spacing.sm))
        Column(Modifier.weight(1f)) {
            MiuixText(
                text = stringResource(R.string.capture_screen_banner_authorized),
                style = MiuixTheme.typography.labelLarge,
                color = colors.onWarningContainer,
            )
            MiuixText(
                text = stringResource(R.string.capture_screen_banner_tls),
                style = MiuixTheme.typography.bodySmall,
                color = colors.onWarningContainer,
            )
        }
        val press = rememberMiuixPressState()
        Box(
            modifier =
                Modifier.background(colors.warning, RoundedCornerShape(50))
                    .miuixClickable(press, true, onClick = onDetail)
                    .padding(horizontal = spacing.md, vertical = 6.dp),
        ) {
            MiuixText(
                text = stringResource(R.string.capture_screen_banner_detail),
                style = MiuixTheme.typography.labelMedium,
                color = colors.onWarning)
        }
        Spacer(Modifier.width(spacing.xs))
        MiuixIconButton(
            Icons.Outlined.DeleteSweep,
            stringResource(R.string.capture_screen_banner_dismiss),
            onClick = onDismiss)
    }
}

@Composable
private fun EmptyCaptureHint(
    running: Boolean,
    dnsOnly: Boolean,
    statusText: String,
    error: String?
) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        MiuixIcon(Icons.Outlined.VpnKey, null, tint = colors.primary, size = 36.dp)
        Spacer(Modifier.height(spacing.md))
        MiuixText(
            text =
                if (running) stringResource(R.string.capture_screen_empty_waiting)
                else stringResource(R.string.capture_screen_empty_idle),
            style = MiuixTheme.typography.titleSmall,
        )
        Spacer(Modifier.height(spacing.xs))
        MiuixText(
            text =
                when {
                    error != null -> error
                    !running -> stringResource(R.string.capture_screen_hint_start)
                    dnsOnly -> stringResource(R.string.capture_screen_hint_dns_only)
                    else -> stringResource(R.string.capture_screen_hint_status, statusText)
                },
            style = MiuixTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
    }
}

@Composable
private fun CaptureRow(record: CaptureRecord, onClick: () -> Unit) {
    val colors = MiuixTheme.colors
    val context = LocalContext.current
    val flowRunning = stringResource(R.string.capture_screen_flow_running)
    val flowFailed = stringResource(R.string.capture_screen_flow_failed)
    val spacing = MiuixTheme.dimens.spacing
    val press = rememberMiuixPressState()
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .miuixClickable(press, true, onClick = onClick)
                .padding(horizontal = spacing.pageHorizontal, vertical = spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KindBadge(record)
                Spacer(Modifier.width(spacing.sm))
                MiuixText(
                    text = record.host ?: record.sni ?: record.dstIp,
                    modifier = Modifier.weight(1f, fill = false),
                    style =
                        MiuixTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val appIcon =
                    remember(record.appPackage) { CaptureAppIcons.icon(context, record.appPackage) }
                if (appIcon != null) {
                    Image(
                        bitmap = appIcon.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.width(14.dp).height(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                MiuixText(
                    text =
                        buildString {
                            append(record.appLabel ?: record.appPackage ?: record.dstIp)
                            append(" · ")
                            append(formatBytes(record.totalBytes))
                            append(" · ")
                            append("${record.durationMs}ms")
                            if (record.state == FlowState.OPEN ||
                                record.state == FlowState.CONNECTING)
                                append(flowRunning)
                            if (record.state == FlowState.FAILED) append(flowFailed)
                            record.error?.let { append(" · $it") }
                        },
                    style = MiuixTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            record.path?.let { path ->
                MiuixText(
                    text = "${record.dstIp}:${record.dstPort}$path",
                    style = MiuixTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.width(spacing.sm))
        when {
            record.status != null -> StatusCodeText(record.status)
            record.kind == CaptureKind.HTTPS ->
                MiuixText(
                    text = if (record.mitm) "解密" else "TLS",
                    style = MiuixTheme.typography.labelSmall,
                    color = colors.warning,
                )
            else ->
                MiuixText(
                    text = record.protocol,
                    style = MiuixTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
        }
        MiuixIcon(Icons.Outlined.ChevronRight, null, tint = colors.onSurfaceVariant, size = 18.dp)
    }
}

@Composable
private fun KindBadge(record: CaptureRecord) {
    val colors = MiuixTheme.colors
    val label = record.method ?: record.kind.name
    val tint =
        when (record.kind) {
            CaptureKind.HTTPS -> colors.warning
            CaptureKind.HTTP -> colors.primary
            CaptureKind.DNS -> colors.tertiary
            else -> colors.onSurfaceVariant
        }
    Box(
        modifier =
            Modifier.background(tint.copy(alpha = 0.16f), RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        MiuixText(text = label, style = MiuixTheme.typography.labelSmall, color = tint)
    }
}

internal fun formatBytes(value: Long): String =
    when {
        value < 1024 -> "$value B"
        value < 1024 * 1024 -> String.format("%.1f KB", value / 1024.0)
        else -> String.format("%.2f MB", value / (1024.0 * 1024.0))
    }

@Composable
private fun CaptureStatusPanel(
    state: CaptureUiState,
    onToggleRunning: () -> Unit,
    onTogglePause: () -> Unit,
    onClear: () -> Unit,
    onExport: () -> Unit,
) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    var barExpanded by remember { mutableStateOf(true) }
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .padding(horizontal = spacing.pageHorizontal)
                .navigationBarsPadding()
                .padding(bottom = spacing.sm)
                // 展开/收起时高度平滑过渡，而不是瞬间跳变
                .animateContentSize(
                    animationSpec =
                        spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow),
                )
                .background(colors.surfaceContainerHigh, RoundedCornerShape(MiuixTheme.radius.lg))
                .padding(vertical = spacing.md),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 运行/停止切换时强调色平滑过渡
            val accent by
                animateColorAsState(
                    targetValue = if (state.running) colors.success else colors.primary,
                    animationSpec = tween(320),
                    label = "captureAccent",
                )
            Box(
                modifier =
                    Modifier.size(36.dp)
                        .background(accent.copy(alpha = 0.16f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                MiuixIcon(Icons.Outlined.Bolt, null, tint = accent, size = 20.dp)
            }
            Spacer(Modifier.width(spacing.md))
            Column(Modifier.weight(1f)) {
                AnimatedContent(
                    targetState =
                        when {
                            !state.running -> stringResource(R.string.capture_screen_status_idle)
                            state.paused -> stringResource(R.string.capture_screen_status_paused)
                            else -> stringResource(R.string.capture_screen_status_running)
                        },
                    transitionSpec = {
                        (fadeIn(tween(260)) + slideInVertically(tween(260)) { it / 3 })
                            .togetherWith(
                                fadeOut(tween(160)) + slideOutVertically(tween(160)) { -it / 3 })
                    },
                    label = "captureStatus",
                ) { statusLabel ->
                    MiuixText(text = statusLabel, style = MiuixTheme.typography.titleSmall)
                }
                MiuixText(
                    text = state.error ?: state.statusText,
                    style = MiuixTheme.typography.bodySmall,
                    color = if (state.error != null) colors.error else colors.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            Spacer(Modifier.width(spacing.sm))
            MiuixIconButton(
                if (barExpanded) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess,
                if (barExpanded) "收起底栏" else "展开底栏",
                onClick = { barExpanded = !barExpanded },
            )
        }
        if (barExpanded) {
            Spacer(Modifier.height(spacing.md))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.md),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                StatCell(
                    stringResource(R.string.capture_screen_stat_connections),
                    state.stats.total.toString(),
                    colors.primary,
                    Modifier.weight(1f))
                StatCell(
                    stringResource(R.string.capture_screen_stat_up),
                    formatBytes(state.stats.upBytes),
                    colors.tertiary,
                    Modifier.weight(1f))
                StatCell(
                    stringResource(R.string.capture_screen_stat_down),
                    formatBytes(state.stats.downBytes),
                    colors.success,
                    Modifier.weight(1f))
                StatCell(
                    stringResource(R.string.capture_screen_stat_dropped),
                    state.stats.droppedPackets.toString(),
                    colors.onSurfaceVariant,
                    Modifier.weight(1f))
            }
            Spacer(Modifier.height(spacing.md))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.md),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                MiuixButton(
                    text =
                        if (state.running) stringResource(R.string.capture_screen_action_stop)
                        else stringResource(R.string.capture_screen_action_start),
                    onClick = onToggleRunning,
                    modifier = Modifier.weight(1f),
                    variant =
                        if (state.running) MiuixButtonVariant.OUTLINED
                        else MiuixButtonVariant.FILLED,
                    leadingIcon =
                        if (state.running) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                )
                MiuixButton(
                    text =
                        if (state.paused) stringResource(R.string.capture_screen_action_resume)
                        else stringResource(R.string.capture_screen_action_pause),
                    onClick = onTogglePause,
                    modifier = Modifier.weight(1f),
                    variant = MiuixButtonVariant.OUTLINED,
                    enabled = state.running,
                )
            }
            Spacer(Modifier.height(spacing.sm))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.md),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                MiuixButton(
                    text = stringResource(R.string.capture_screen_action_clear),
                    onClick = onClear,
                    modifier = Modifier.weight(1f),
                    variant = MiuixButtonVariant.OUTLINED,
                    leadingIcon = Icons.Outlined.DeleteSweep,
                )
                MiuixButton(
                    text = stringResource(R.string.capture_screen_action_export_har),
                    onClick = onExport,
                    modifier = Modifier.weight(1f),
                    leadingIcon = Icons.Outlined.FileDownload,
                )
            }
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier =
            modifier
                .background(
                    MiuixTheme.colors.surfaceContainerLow, RoundedCornerShape(MiuixTheme.radius.sm))
                .padding(vertical = 8.dp, horizontal = 10.dp),
    ) {
        MiuixText(
            text = value, style = MiuixTheme.typography.titleMedium, color = color, maxLines = 1)
        MiuixText(
            text = label,
            style = MiuixTheme.typography.labelSmall,
            color = MiuixTheme.colors.onSurfaceVariant,
        )
    }
}

/** CaptureScreen 用到的文案：在 Composable 作用域一次取好，供普通 lambda 与局部函数复用。 */
private data class CaptureScreenStrings(
    val filterAll: String,
    val toastVpnGranted: String,
    val toastVpnDenied: String,
    val toastNoNotification: String,
    val toastStarting: String,
    val title: String,
    val actionSettings: String,
    val actionMore: String,
    val menuScope: String,
    val menuDnsLog: String,
    val menuCa: String,
    val menuDecrypt: String,
    val stateOn: String,
    val stateOff: String,
    val toastDecryptOn: String,
    val toastDecryptOff: String,
    val menuExport: String,
    val actionClear: String,
    val toastCleared: String,
    val searchHint: String,
    val toastStopped: String,
    val toastResumed: String,
    val toastPaused: String,
    val toastConfigSaved: String,
    val toastAppFilterSaved: String,
    val noticeTitle: String,
    val noticeConfirm: String,
    val noticeBody: String,
)

@Composable
private fun captureScreenStrings(): CaptureScreenStrings =
    CaptureScreenStrings(
        filterAll = stringResource(R.string.capture_screen_filter_all),
        toastVpnGranted = stringResource(R.string.capture_screen_toast_vpn_granted),
        toastVpnDenied = stringResource(R.string.capture_screen_toast_vpn_denied),
        toastNoNotification = stringResource(R.string.capture_screen_toast_no_notification),
        toastStarting = stringResource(R.string.capture_screen_toast_starting),
        title = stringResource(R.string.capture_screen_title),
        actionSettings = stringResource(R.string.capture_screen_action_settings),
        actionMore = stringResource(R.string.capture_screen_action_more),
        menuScope = stringResource(R.string.capture_screen_menu_scope),
        menuDnsLog = stringResource(R.string.capture_screen_menu_dns_log),
        menuCa = stringResource(R.string.capture_screen_menu_ca),
        menuDecrypt = stringResource(R.string.capture_screen_menu_decrypt),
        stateOn = stringResource(R.string.capture_screen_state_on),
        stateOff = stringResource(R.string.capture_screen_state_off),
        toastDecryptOn = stringResource(R.string.capture_screen_toast_decrypt_on),
        toastDecryptOff = stringResource(R.string.capture_screen_toast_decrypt_off),
        menuExport = stringResource(R.string.capture_screen_menu_export),
        actionClear = stringResource(R.string.capture_screen_action_clear),
        toastCleared = stringResource(R.string.capture_screen_toast_cleared),
        searchHint = stringResource(R.string.capture_screen_search_hint),
        toastStopped = stringResource(R.string.capture_screen_toast_stopped),
        toastResumed = stringResource(R.string.capture_screen_toast_resumed),
        toastPaused = stringResource(R.string.capture_screen_toast_paused),
        toastConfigSaved = stringResource(R.string.capture_screen_toast_config_saved),
        toastAppFilterSaved = stringResource(R.string.capture_screen_toast_app_filter_saved),
        noticeTitle = stringResource(R.string.capture_screen_notice_title),
        noticeConfirm = stringResource(R.string.capture_screen_notice_confirm),
        noticeBody = stringResource(R.string.capture_screen_notice_body),
    )
