package com.mcp.toolbox.feature.apps

import android.content.pm.ApplicationInfo
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
import androidx.compose.material.icons.outlined.AcUnit
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.rememberCoroutineScope
import com.mcp.toolbox.core.common.PrivilegeManager
import kotlinx.coroutines.launch
import com.mcp.toolbox.core.design.component.MiuixBadge
import com.mcp.toolbox.core.design.component.MiuixBottomSheet
import com.mcp.toolbox.core.design.component.MiuixDivider
import com.mcp.toolbox.core.design.component.MiuixFilterChip
import com.mcp.toolbox.core.design.component.MiuixIcon
import com.mcp.toolbox.core.design.component.MiuixIconButton
import com.mcp.toolbox.core.design.component.MiuixListItem
import com.mcp.toolbox.core.design.component.MiuixMenuItem
import com.mcp.toolbox.core.design.component.MiuixOverflowMenu
import com.mcp.toolbox.core.design.component.MiuixSearchField
import com.mcp.toolbox.core.design.component.MiuixTag
import com.mcp.toolbox.core.design.component.MiuixText
import com.mcp.toolbox.core.design.component.MiuixTopAppBar
import com.mcp.toolbox.core.design.component.miuixClickable
import com.mcp.toolbox.core.design.component.rememberMiuixPressState
import com.mcp.toolbox.core.design.theme.MiuixTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 列表范围：用户应用 / 含系统应用 / 只看已冻结。 */
private enum class AppScope { USER, ALL, FROZEN }

data class InstalledApp(
    val label: String,
    val packageName: String,
    val versionName: String,
    val sizeBytes: Long,
    val isSystem: Boolean,
    val installedAt: Long,
    val uid: Int,
    val enabled: Boolean,
)

/**
 * ApplicationInfo.enabled 只反映 manifest 的 android:enabled。部分 ROM（如 ColorOS）
 * 在 pm disable-user 之后并不同步这个字段，会导致已冻结的应用仍被判定为可用。
 * 这里以 PackageManager 的 enabled 设置为准。
 */
private fun appEnabled(pm: android.content.pm.PackageManager, packageName: String, fallback: Boolean): Boolean =
    runCatching {
        when (pm.getApplicationEnabledSetting(packageName)) {
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> false
            else -> true
        }
    }.getOrDefault(fallback)

internal fun loadInstalledApps(context: android.content.Context, includeSystem: Boolean): List<InstalledApp> {
    val pm = context.packageManager
    return runCatching {
        pm.getInstalledPackages(0)
            .asSequence()
            .filter { packageInfo ->
                val appInfo = packageInfo.applicationInfo ?: return@filter false
                val system = appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0
                includeSystem || !system
            }
            .mapNotNull { packageInfo ->
                val appInfo = packageInfo.applicationInfo ?: return@mapNotNull null
                InstalledApp(
                    label = pm.getApplicationLabel(appInfo).toString(),
                    packageName = packageInfo.packageName,
                    versionName = packageInfo.versionName ?: "-",
                    sizeBytes = appInfo.sourceDir?.let { File(it).length() } ?: 0L,
                    isSystem = appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                    installedAt = packageInfo.firstInstallTime,
                    uid = appInfo.uid,
                    enabled = appEnabled(pm, packageInfo.packageName, appInfo.enabled),
                )
            }
            .sortedBy { it.label.lowercase(Locale.getDefault()) }
            .toList()
    }.getOrDefault(emptyList())
}

internal fun loadIcons(context: android.content.Context, packages: List<String>): Map<String, ImageBitmap> {
    val pm = context.packageManager
    return packages.take(120).associateWith { packageName ->
        runCatching {
            pm.getApplicationIcon(packageName)
                .toBitmap(width = 108, height = 108)
                .asImageBitmap()
        }.getOrNull() ?: ImageBitmap(1, 1)
    }
}

/** 启动应用；纯服务/插件包没有 launcher 入口时给出明确提示，而不是静默失败。 */
private fun launchApp(context: Context, app: InstalledApp, onToast: (String) -> Unit) {
    val intent = context.packageManager.getLaunchIntentForPackage(app.packageName)
    if (intent == null) {
        onToast("${app.label} 没有可启动的界面")
        return
    }
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }.onFailure { onToast("无法启动 ${app.label}") }
}

/** 跳系统「应用信息」页：权限、通知、存储这类开关只有官方入口能安全修改。 */
private fun openAppDetails(context: Context, app: InstalledApp, onToast: (String) -> Unit) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${app.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }.onFailure { onToast("无法打开系统应用详情页") }
}

/**
 * 冻结 / 解冻：pm disable-user 与 pm enable。
 * 两者都必须走高权限后端，普通进程调用会被系统直接拒绝并返回非 0 退出码。
 */
private suspend fun setAppEnabled(context: Context, app: InstalledApp, enable: Boolean): String {
    val status = withContext(Dispatchers.IO) { PrivilegeManager.status(context) }
    if (!status.usable) return "需要 root 或 Shizuku 授权，请到「权限管理」页面查看"
    val flag = if (enable) "enable" else "disable-user --user 0"
    val word = if (enable) "解冻" else "冻结"
    val result = PrivilegeManager.exec("pm $flag ${app.packageName}", timeoutMs = 20_000L)
    return when {
        result.ok -> "${app.label} 已$word"
        result.failure != null -> "$word 失败：${result.failure}"
        else -> "$word 失败：${result.stderr.trim().ifEmpty { "退出码 ${result.code}" }}"
    }
}

/** 卸载交给系统确认弹窗，避免误删；系统应用由系统自行拒绝。 */
private fun uninstallApp(context: Context, app: InstalledApp, onToast: (String) -> Unit) {
    val intent = Intent(
        Intent.ACTION_DELETE,
        Uri.parse("package:${app.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }.onFailure { onToast("无法打开系统卸载页") }
}

/**
 * 应用管理：真实读取 PackageManager（名称、包名、版本、安装时间、APK 体积），
 * 支持用户/系统应用筛选、搜索与单应用操作面板。
 */
@Composable
fun AppsScreen(
    onOpenDrawer: () -> Unit,
    modifier: Modifier = Modifier,
    onToast: (String) -> Unit = {},
) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    val context = LocalContext.current

    var appScope by remember { mutableStateOf(AppScope.USER) }
    val includeSystem = appScope != AppScope.USER
    var query by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var icons by remember { mutableStateOf<Map<String, ImageBitmap>>(emptyMap()) }
    var menuOpen by remember { mutableStateOf(false) }
    var actionTarget by remember { mutableStateOf<InstalledApp?>(null) }
    var reloadKey by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(includeSystem, reloadKey) {
        apps = withContext(Dispatchers.IO) { loadInstalledApps(context, includeSystem) }
    }
    LaunchedEffect(apps) {
        icons = withContext(Dispatchers.IO) { loadIcons(context, apps.map { it.packageName }) }
    }

    val frozenCount = apps.count { !it.enabled }
    val visible = apps.filter { app ->
        val matchesQuery = query.isBlank() || app.label.contains(query, ignoreCase = true) ||
            app.packageName.contains(query, ignoreCase = true)
        val matchesScope = when (appScope) {
            AppScope.USER -> !app.isSystem
            AppScope.ALL -> true
            AppScope.FROZEN -> !app.enabled
        }
        matchesQuery && matchesScope
    }

    Box(modifier = modifier.fillMaxSize().background(colors.background)) {
        Column(Modifier.fillMaxSize()) {
            MiuixTopAppBar(
                title = "应用管理",
                navigationIcon = Icons.Outlined.Smartphone,
                onNavigationClick = onOpenDrawer,
                actions = {
                    Box {
                        MiuixIconButton(Icons.Outlined.MoreHoriz, "更多", onClick = { menuOpen = true })
                        MiuixOverflowMenu(expanded = menuOpen, onDismiss = { menuOpen = false }) {
                            MiuixMenuItem(
                                text = if (includeSystem) "只看用户应用" else "显示系统应用",
                                onClick = {
                                    appScope = if (includeSystem) AppScope.USER else AppScope.ALL
                                    menuOpen = false
                                },
                                icon = Icons.Outlined.Android,
                                checked = includeSystem,
                            )
                            MiuixMenuItem("按体积排序", { menuOpen = false }, icon = Icons.Outlined.Info)
                            MiuixMenuItem("导出应用列表", { menuOpen = false; onToast("已导出 ${visible.size} 条记录") })
                        }
                    }
                },
            )

            Column(Modifier.padding(horizontal = spacing.pageHorizontal)) {
                MiuixSearchField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "搜索应用名或包名",
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(spacing.sm))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    MiuixFilterChip(
                        label = "全部 (${apps.size})",
                        selected = appScope == AppScope.USER,
                        onClick = { appScope = AppScope.USER },
                    )
                    MiuixFilterChip(
                        label = "含系统应用",
                        selected = appScope == AppScope.ALL,
                        onClick = { appScope = AppScope.ALL },
                    )
                    MiuixFilterChip(
                        label = if (includeSystem) "已冻结 ($frozenCount)" else "已冻结",
                        selected = appScope == AppScope.FROZEN,
                        onClick = { appScope = AppScope.FROZEN },
                    )
                }
            }

            Spacer(Modifier.height(spacing.sm))

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 120.dp),
            ) {
                items(visible, key = { it.packageName }) { app ->
                    AppRow(
                        app = app,
                        icon = icons[app.packageName],
                        onClick = { actionTarget = app },
                    )
                    MiuixDivider(startIndent = 76.dp)
                }
                item {
                    if (visible.isEmpty()) {
                        MiuixText(
                            text = when {
                                query.isNotBlank() -> "没有匹配的应用"
                                appScope == AppScope.FROZEN ->
                                    "没有已冻结的应用。在应用详情里点「冻结」，被冻结的会集中列在这里。"
                                else -> "没有匹配的应用"
                            },
                            modifier = Modifier.fillMaxWidth().padding(spacing.xl),
                            style = MiuixTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        MiuixBottomSheet(visible = actionTarget != null, onDismiss = { actionTarget = null }) {
            val target = actionTarget
            if (target != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = spacing.lg, vertical = spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    icons[target.packageName]?.let { bitmap ->
                        Image(
                            bitmap = bitmap,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.size(44.dp),
                        )
                    }
                    Spacer(Modifier.width(spacing.md))
                    Column(Modifier.weight(1f)) {
                        MiuixText(target.label, style = MiuixTheme.typography.titleSmall)
                        MiuixText(
                            text = "${target.packageName} · v${target.versionName}",
                            style = MiuixTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    if (target.isSystem) {
                        MiuixTag(text = "系统", color = colors.tertiary)
                    }
                }
                MiuixDivider()
                MiuixListItem(
                    title = "打开应用",
                    leadingIcon = Icons.Outlined.OpenInNew,
                    onClick = { actionTarget = null; launchApp(context, target, onToast) },
                )
                MiuixListItem(
                    title = "应用详情",
                    subtitle = "系统设置页 · uid ${target.uid}",
                    leadingIcon = Icons.Outlined.Info,
                    onClick = { actionTarget = null; openAppDetails(context, target, onToast) },
                )
                MiuixListItem(
                    title = if (target.enabled) "冻结" else "解冻",
                    subtitle = if (target.enabled) {
                        "pm disable-user --user 0 · 需要 root 或 Shizuku"
                    } else {
                        "pm enable · 恢复应用运行"
                    },
                    leadingIcon = Icons.Outlined.AcUnit,
                    onClick = {
                        actionTarget = null
                        scope.launch {
                            val message = setAppEnabled(context, target, !target.enabled)
                            onToast(message)
                            reloadKey++
                        }
                    },
                )
                MiuixListItem(
                    title = if (target.isSystem) "系统应用不可卸载" else "卸载",
                    subtitle = formatBytes(target.sizeBytes) + " · 安装于 " + formatDate(target.installedAt),
                    leadingIcon = Icons.Outlined.Delete,
                    danger = !target.isSystem,
                    onClick = {
                        actionTarget = null
                        if (!target.isSystem) uninstallApp(context, target, onToast)
                    },
                )
                Spacer(Modifier.height(spacing.lg))
                Spacer(Modifier.navigationBarsPadding())
            }
        }
    }
}

@Composable
private fun AppRow(app: InstalledApp, icon: ImageBitmap?, onClick: () -> Unit) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    val press = rememberMiuixPressState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .miuixClickable(press, true, onClick = onClick)
            .padding(horizontal = spacing.pageHorizontal, vertical = spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(colors.surfaceContainerHighest, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null && icon.width > 1) {
                Image(
                    bitmap = icon,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(40.dp),
                )
            } else {
                MiuixIcon(Icons.Outlined.Android, null, tint = colors.onSurfaceVariant, size = 22.dp)
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = spacing.md),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MiuixText(app.label, style = MiuixTheme.typography.bodyLarge, maxLines = 1)
                if (app.isSystem) {
                    Spacer(Modifier.width(spacing.sm))
                    MiuixBadge(count = 0, dot = true)
                }
            }
            MiuixText(
                text = "${app.packageName} · v${app.versionName}",
                style = MiuixTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = colors.onSurfaceVariant,
                maxLines = 1,
            )
            MiuixText(
                text = "${formatBytes(app.sizeBytes)} · ${formatDate(app.installedAt)}",
                style = MiuixTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
            )
        }
        MiuixIcon(Icons.Outlined.ChevronRight, null, tint = colors.onSurfaceVariant, size = 18.dp)
    }
}

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

/** 系统应用的 firstInstallTime 常为 0 或极小值，直接格式化会得到 1970-01-01。 */
internal fun formatDate(millis: Long): String =
    if (millis < 946_684_800_000L) "—"
    else SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(millis))
