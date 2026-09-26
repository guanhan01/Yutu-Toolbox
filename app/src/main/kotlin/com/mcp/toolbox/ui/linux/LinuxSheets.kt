package com.mcp.toolbox.ui.linux

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mcp.toolbox.R
import com.mcp.toolbox.core.design.component.MiuixButton
import com.mcp.toolbox.core.design.component.MiuixIcon
import com.mcp.toolbox.core.design.component.MiuixSectionCard
import com.mcp.toolbox.core.design.component.MiuixTag
import com.mcp.toolbox.core.design.component.MiuixText
import com.mcp.toolbox.core.design.theme.MiuixTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import android.content.Context

/** 二选一弹层：发行版 / 运行方式。 */
@Composable
fun LinuxChoiceSheet(
    title: String,
    options: List<Pair<String, String>>,
    selectedIndex: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MiuixTheme.colors
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp)
                    .clip(RoundedCornerShape(MiuixTheme.radius.dialog))
                    .background(colors.surface)
                    .padding(vertical = 16.dp),
            ) {
                MiuixText(
                    text = title,
                    style = MiuixTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
                Spacer(Modifier.height(8.dp))
                options.forEachIndexed { index, (name, desc) ->
                    val active = index == selectedIndex
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 2.dp)
                            .clip(RoundedCornerShape(MiuixTheme.radius.field))
                            .background(if (active) colors.primary.copy(alpha = 0.10f) else Color.Transparent)
                            .clickable { onPick(index) }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            MiuixText(
                                text = name,
                                style = MiuixTheme.typography.bodyLarge,
                                color = if (active) colors.primary else colors.onSurface,
                            )
                            MiuixText(
                                text = desc,
                                style = MiuixTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                        if (active) {
                            MiuixIcon(Icons.Outlined.Check, null, tint = colors.primary, size = 18.dp)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 环境检测页：列出组件、状态与版本。
 *
 * 检测在 rootfs 内查可执行文件，瞬时完成；缺失的组件给出安装入口。
 */
/**
 * 一次环境扫描：是否就绪、各组件状态、rootfs 体积。务必在 IO 线程调用。
 *
 * [probe] 为 true 时真实执行各工具取版本号（比读缓存准得多）。
 */
private suspend fun scanEnv(
    context: Context,
    distro: LinuxDistro,
    probe: Boolean = false,
): Triple<Boolean, List<ComponentStatus>, String> {
    val ready = LinuxEnvStore.isInstalled(context, distro)
    var statuses = LinuxChecker.check(context, distro)
    if (probe && ready) {
        val versions = probeVersions(context, distro)
        statuses = statuses.map { status ->
            val found = versions[status.component]
            // probe 是在 chroot 内真跑出来的：拿到版本就说明确实装好了。
            // 不能要求 status.installed 先为 true——rootfs 内的软链接在应用
            // 进程里 stat 不到，会被误判成未安装（npm 全局包就是这种）。
            if (!found.isNullOrBlank()) {
                status.copy(installed = true, version = found)
            } else {
                status
            }
        }
    }
    val size = if (ready) LinuxChecker.humanSize(LinuxEnvStore.sizeOf(context, distro)) else "未安装"
    return Triple(ready, statuses, size)
}

/**
 * 真实跑一遍各工具，取回版本号。
 *
 * 合成一条命令一次拿全：每跑一次 chroot 都要重挂 dev/proc/sys，四次会明显变慢。
 */
internal suspend fun probeVersions(
    context: Context,
    distro: LinuxDistro,
): Map<LinuxComponent, String> {
    val script = """
        echo "PYTHON=${'$'}(uv --version 2>&1 | head -n 1)"
        echo "NODE=${'$'}(node --version 2>&1 | head -n 1)"
        echo "SSH=${'$'}(ssh -V 2>&1 | head -n 1)"
        echo "JAVA=${'$'}(java -version 2>&1 | head -n 1)"
        echo "APK=${'$'}(jadx --version 2>&1 | head -n 1)"
        echo "GIT=${'$'}(git --version 2>&1 | head -n 1)"
        echo "CODEX=${'$'}(codex --version 2>&1 | head -n 1)"
        echo "CLAUDE=${'$'}(claude --version 2>&1 | head -n 1)"
    """.trimIndent()
    val out = LinuxRuntime.exec(context, distro, script, timeoutMs = 30_000)
    val versions = mutableMapOf<LinuxComponent, String>()
    out.combined.lineSequence().forEach { line ->
        val key = line.substringBefore('=', "").trim()
        val value = line.substringAfter('=', "").trim()
        val component = when (key) {
            "PYTHON" -> LinuxComponent.PYTHON
            "NODE" -> LinuxComponent.NODE
            "SSH" -> LinuxComponent.SSH
            "JAVA" -> LinuxComponent.JAVA
            "APK" -> LinuxComponent.APK
            "GIT" -> LinuxComponent.GIT
            "CODEX" -> LinuxComponent.CODEX
            "CLAUDE" -> LinuxComponent.CLAUDE
            else -> null
        }
        if (component != null && value.isNotBlank() && !value.contains("not found")) {
            versions[component] = value.take(60)
        }
    }
    return versions
}

@Composable
fun LinuxCheckScreen(
    distro: LinuxDistro,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var statuses by remember { mutableStateOf<List<ComponentStatus>>(emptyList()) }
    var scanning by remember { mutableStateOf(true) }
    var installing by remember { mutableStateOf<LinuxComponent?>(null) }
    var envReady by remember { mutableStateOf(LinuxEnvStore.isInstalled(context, distro)) }
    var sizeText by remember { mutableStateOf("") }
    val installLog = remember { mutableStateListOf<String>() }
    // 各工具的断点大小（已下载未解包），用于提示「继续下载」
    var partials by remember { mutableStateOf<Map<LinuxComponent, Long>>(emptyMap()) }
    // 当前下载进度文案，装的时候显示在组件行上
    var downloadNote by remember { mutableStateOf("") }

    suspend fun rescan() {
        scanning = true
        // rootfs 有几万个文件，体积统计必须放 IO 线程：这个 Composable 会因为
        // 安装日志每追加一行而重组，放在渲染表达式里等于反复遍历全盘，直接 ANR
        val snapshot = withContext(Dispatchers.IO) { scanEnv(context, distro, probe = true) }
        envReady = snapshot.first
        statuses = snapshot.second
        sizeText = snapshot.third
        partials = withContext(Dispatchers.IO) {
            if (!snapshot.first) {
                emptyMap()
            } else {
                LinuxComponent.entries
                    .associateWith { LinuxToolchain.pendingBytes(context, distro, it) }
                    .filterValues { it > 0L }
            }
        }
        scanning = false
    }

    fun startInstall(component: LinuxComponent) {
        if (installing != null) return
        installing = component
        downloadNote = ""
        installLog.clear()
        installLog.add("→ 开始安装 ${component.title}，请稍候")
        scope.launch {
            val result = LinuxToolchain.install(
                context = context,
                distro = distro,
                component = component,
                onProgress = { p ->
                    downloadNote = if (p.done) {
                        ""
                    } else {
                        "下载 ${p.fileName} ${p.percent}%（可中断，重试会续传）"
                    }
                },
                onLine = { line ->
                    installLog.add(line)
                    if (installLog.size > 500) installLog.removeAt(0)
                },
            )
            installLog.add(
                if (result.isFailure) {
                    "✗ ${result.exceptionOrNull()?.message ?: "安装失败"}"
                } else {
                    "✓ ${component.title} 安装完成"
                },
            )
            installing = null
            downloadNote = ""
            rescan()
        }
    }

    LaunchedEffect(distro) {
        rescan()
        // 从主页「安装」跳进来时，自动开装目标组件
        val pending = LinuxPendingInstall.component
        if (pending != null) {
            LinuxPendingInstall.component = null
            startInstall(pending)
        }
    }

    val readyCount = statuses.count { it.installed }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.pageHorizontal),
    ) {
        Spacer(Modifier.height(spacing.sm))
        MiuixSectionCard(
            title = stringResource(R.string.linux_check_title),
            subtitle = stringResource(R.string.linux_check_hint),
        ) {
            Column(Modifier.padding(spacing.lg)) {
                MiuixText(
                    text = if (scanning) {
                        stringResource(R.string.linux_checking)
                    } else {
                        stringResource(R.string.linux_check_summary, readyCount, statuses.size)
                    },
                    style = MiuixTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(4.dp))
                MiuixText(
                    text = "${distro.title} · $sizeText",
                    style = MiuixTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(spacing.groupGap))

        MiuixSectionCard(title = stringResource(R.string.linux_section_tools)) {
            Column {
                statuses.forEachIndexed { index, status ->
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(
                                        if (status.installed) colors.primary else colors.surfaceContainerHighest,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (status.installed) {
                                    MiuixIcon(
                                        Icons.Outlined.Check,
                                        null,
                                        tint = colors.onPrimary,
                                        size = 14.dp,
                                    )
                                }
                            }
                            Column(Modifier.weight(1f)) {
                                MiuixText(
                                    text = status.component.title,
                                    style = MiuixTheme.typography.bodyLarge,
                                )
                                MiuixText(
                                    text = when {
                                        installing == status.component && downloadNote.isNotBlank() ->
                                            downloadNote
                                        status.version.isNotBlank() -> status.version
                                        (partials[status.component] ?: 0L) > 0L ->
                                            "已下载 ${LinuxChecker.humanSize(partials.getValue(status.component))}，可继续"
                                        else -> status.component.subtitle
                                    },
                                    style = MiuixTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant,
                                )
                            }
                            when {
                                installing == status.component -> MiuixTag(
                                    text = if (downloadNote.isNotBlank()) "下载中…" else "安装中…",
                                    color = colors.primary,
                                )
                                status.installed -> MiuixTag(
                                    text = stringResource(R.string.linux_status_ready),
                                    color = colors.success,
                                )
                                !envReady -> MiuixTag(
                                    text = "需先安装环境",
                                    color = colors.onSurfaceVariant,
                                )
                                else -> MiuixButton(
                                    text = if ((partials[status.component] ?: 0L) > 0L) {
                                        "继续下载"
                                    } else {
                                        stringResource(R.string.linux_install)
                                    },
                                    onClick = { startInstall(status.component) },
                                    variant = com.mcp.toolbox.core.design.component.MiuixButtonVariant.TONAL,
                                )
                            }
                        }
                        if (index != statuses.lastIndex) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp)
                                    .height(1.dp)
                                    .background(colors.outlineVariant),
                            )
                        }
                    }
                }
                if (statuses.isEmpty() && !scanning) {
                    MiuixText(
                        text = stringResource(R.string.linux_check_empty),
                        style = MiuixTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
            }
        }

        if (installLog.isNotEmpty()) {
            Spacer(Modifier.height(spacing.groupGap))
            MiuixSectionCard(title = "安装日志") {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                    installLog.takeLast(80).forEach { line ->
                        MiuixText(
                            text = line,
                            style = MiuixTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}
