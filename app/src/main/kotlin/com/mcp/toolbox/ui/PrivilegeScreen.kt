package com.mcp.toolbox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mcp.toolbox.core.common.PrivilegeBackend
import com.mcp.toolbox.core.common.ToolboxAccessibilityService
import com.mcp.toolbox.core.common.PrivilegeManager
import com.mcp.toolbox.core.common.PrivilegeStatus
import com.mcp.toolbox.core.design.component.MiuixButton
import com.mcp.toolbox.core.design.component.MiuixButtonSize
import com.mcp.toolbox.core.design.component.MiuixButtonVariant
import com.mcp.toolbox.core.design.component.MiuixCard
import com.mcp.toolbox.core.design.component.MiuixListItem
import com.mcp.toolbox.core.design.component.MiuixSectionCard
import com.mcp.toolbox.core.design.component.MiuixTag
import com.mcp.toolbox.core.design.component.MiuixText
import com.mcp.toolbox.core.design.theme.MiuixTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 高权限后端设置页：展示 root / Shizuku 的真实探测结果，并提供授权入口。
 *
 * 探测会真正拉起子进程（root 首次会弹系统授权框），所以全部放在 IO 线程，
 * 期间界面显示「检测中」而不是空状态。
 */
@Composable
fun PrivilegeScreen(modifier: Modifier = Modifier, onToast: (String) -> Unit = {}) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var status by remember { mutableStateOf(PrivilegeStatus()) }
    var probing by remember { mutableStateOf(true) }
    // 无障碍开关：既要看系统设置里是否启用，也要看服务是否真的连上了
    var accessibilityOn by remember {
        mutableStateOf(ToolboxAccessibilityService.enabledInSettings(context))
    }
    var accessibilityLive by remember { mutableStateOf(ToolboxAccessibilityService.connected) }

    // 从系统设置返回本页时重新读一次，否则开关状态会停在离页那一刻
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                accessibilityOn = ToolboxAccessibilityService.enabledInSettings(context)
                accessibilityLive = ToolboxAccessibilityService.connected
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        status = withContext(Dispatchers.IO) { PrivilegeManager.status(context, force = true) }
        probing = false
    }

    fun reprobe() {
        scope.launch {
            probing = true
            status = withContext(Dispatchers.IO) { PrivilegeManager.status(context, force = true) }
            probing = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.pageHorizontal),
    ) {
        Spacer(Modifier.height(spacing.sm))

        MiuixCard {
            Column(Modifier.padding(spacing.lg)) {
                MiuixText(
                    text = when {
                        probing -> "检测中…"
                        status.active == PrivilegeBackend.ROOT -> "root 可用"
                        status.active == PrivilegeBackend.SHIZUKU -> "Shizuku 可用"
                        else -> "无高权限后端"
                    },
                    style = MiuixTheme.typography.titleLarge,
                )
                Spacer(Modifier.height(6.dp))
                MiuixText(
                    text = if (probing) {
                        "正在探测 su 与 Shizuku，首次授权可能需要你在系统弹窗里确认。"
                    } else {
                        status.detail + "。高权限工具优先走 root，其次 Shizuku。"
                    },
                    style = MiuixTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(spacing.groupGap))

        MiuixSectionCard(title = "后端") {
            Column {
                MiuixListItem(
                    title = "root",
                    subtitle = when {
                        !status.rootBinaryFound -> "未发现 su 二进制"
                        status.rootGranted -> "已授权，可执行 su 命令"
                        else -> "发现 su，尚未授权"
                    },
                    leadingIcon = Icons.Outlined.Terminal,
                    trailing = {
                        MiuixTag(
                            text = if (status.rootGranted) "可用" else "不可用",
                            color = if (status.rootGranted) colors.primary else colors.onSurfaceVariant,
                        )
                    },
                    showDivider = true,
                )
                MiuixListItem(
                    title = "Shizuku",
                    subtitle = when {
                        !status.shizukuInstalled -> "未安装 Shizuku 应用"
                        !status.shizukuBinderAlive -> "已安装，但服务未运行"
                        !status.shizukuGranted -> "服务运行中，尚未授权"
                        else -> "已授权，可执行命令"
                    },
                    leadingIcon = Icons.Outlined.Security,
                    trailing = {
                        MiuixTag(
                            text = if (status.shizukuGranted) "可用" else "不可用",
                            color = if (status.shizukuGranted) colors.primary else colors.onSurfaceVariant,
                        )
                    },
                )
            }
        }

        Spacer(Modifier.height(spacing.groupGap))

        // 无障碍：免 root 操作手机的唯一途径，放在 root / Shizuku 之后单独成节
        MiuixSectionCard(
            title = "免 root 能力",
            subtitle = "开启后 AI 无需 root 或 Shizuku 即可点击、滑动、输入、读界面与截屏",
        ) {
            Column {
                MiuixListItem(
                    title = "无障碍服务",
                    subtitle = when {
                        accessibilityLive -> "已开启并已连接，AI 的界面操作与截屏走这条路"
                        accessibilityOn -> "系统设置里已开启，服务尚未连接（可在系统里关掉重开）"
                        else -> "未开启。不开也能用，但界面操作与截屏需要 root 或 Shizuku"
                    },
                    leadingIcon = Icons.Outlined.Accessibility,
                    trailing = {
                        MiuixTag(
                            text = if (accessibilityLive) "可用" else "不可用",
                            color = if (accessibilityLive) colors.primary else colors.onSurfaceVariant,
                        )
                    },
                    onClick = {
                        // 直接开无障碍设置列表。
                        //
                        // 曾想深链到本应用的详情页，但 `ACTION_ACCESSIBILITY_DETAILS_SETTINGS`
                        // 在公开 SDK 里并不存在（只有 ACTION_ACCESSIBILITY_SETTINGS），
                        // 用非公开字符串拼 Intent 属于赌 ROM 实现，因此改为最稳的列表页。
                        val intent = android.content.Intent(
                            android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS,
                        ).apply {
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        val opened = runCatching { context.startActivity(intent) }.isSuccess
                        onToast(
                            if (opened) "在「已下载的应用」里找到「Yutu Toolbox」并开启"
                            else "无法打开设置页，请手动进入 系统设置 → 无障碍",
                        )
                    },
                )
            }
        }

        Spacer(Modifier.height(spacing.groupGap))

        PermissionSection(onToast = onToast)

        Spacer(Modifier.height(spacing.groupGap))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MiuixButton(
                text = if (probing) "检测中…" else "重新检测",
                onClick = { reprobe() },
            )
            if (status.shizukuInstalled && !status.shizukuGranted) {
                MiuixButton(
                    text = "请求 Shizuku 授权",
                    onClick = {
                        PrivilegeManager.requestShizukuPermission()
                        onToast("已发起 Shizuku 授权请求，回来后点「重新检测」")
                    },
                    variant = MiuixButtonVariant.OUTLINED,
                )
            }
        }

        Spacer(Modifier.height(spacing.groupGap))

        MiuixCard {
            Column(Modifier.padding(spacing.lg)) {
                MiuixText(text = "关于授权", style = MiuixTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                MiuixText(
                    text = "root 授权由系统弹窗确认，本应用不会也不能绕过它；" +
                        "Shizuku 需要你自行安装并启动其服务。没有高权限后端时，" +
                        "只读工具与本地功能全部照常可用，只有高权限工具会返回明确错误。",
                    style = MiuixTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}
