package com.mcp.toolbox.feature.network

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mcp.toolbox.core.design.component.CodeLanguage
import com.mcp.toolbox.core.design.component.MiuixButton
import com.mcp.toolbox.core.design.component.MiuixCard
import com.mcp.toolbox.core.design.component.MiuixCodeText
import com.mcp.toolbox.core.design.component.MiuixIconButton
import com.mcp.toolbox.core.design.component.MiuixSegmentedButton
import com.mcp.toolbox.core.design.component.MiuixText
import com.mcp.toolbox.core.design.component.MiuixTextField
import com.mcp.toolbox.core.design.component.MiuixTopAppBar
import com.mcp.toolbox.core.design.theme.MiuixTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress

/** Ping / Traceroute：调用系统 ping 可执行文件，逐行回填输出。 */
@Composable
fun PingScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf("8.8.8.8") }
    var mode by remember { mutableStateOf("Ping") }
    var output by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize().background(colors.background)) {
        MiuixTopAppBar(
            title = "Ping / Traceroute",
            navigationIcon = Icons.Outlined.ArrowBack,
            onNavigationClick = onBack,
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.pageHorizontal),
        ) {
            MiuixSegmentedButton(
                options = listOf("Ping", "Traceroute"),
                selected = mode,
                onSelect = { mode = it },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(spacing.md))
            MiuixTextField(
                value = host,
                onValueChange = { host = it },
                placeholder = "域名或 IP",
                leadingIcon = Icons.Outlined.Router,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(spacing.md))
            MiuixButton(
                text = if (running) "执行中…" else "开始${mode}",
                onClick = {
                    running = true
                    output = "正在执行 $mode $host …"
                    scope.launch {
                        val text = runNetworkCommand(mode, host)
                        output = text
                        running = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = Icons.Outlined.PlayArrow,
                loading = running,
            )
            Spacer(Modifier.height(spacing.lg))
            if (output.isNotBlank()) {
                MiuixCodeText(code = output, language = CodeLanguage.TEXT, showLineNumbers = false)
            } else {
                MiuixCard(variant = com.mcp.toolbox.core.design.component.MiuixCardVariant.OUTLINED) {
                    MiuixText("说明", style = MiuixTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    MiuixText(
                        text = "调用系统 /system/bin/ping 执行；traceroute 使用 ping 的 TTL 递增模式，在部分设备上需要 root。",
                        style = MiuixTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(spacing.xxl))
        }
    }
}

private suspend fun runNetworkCommand(mode: String, host: String): String = withContext(Dispatchers.IO) {
    runCatching {
        val command = if (mode == "Ping") {
            listOf("ping", "-c", "4", "-W", "3", host)
        } else {
            listOf("ping", "-c", "1", "-t", "8", host)
        }
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        if (output.isBlank()) "无输出（命令不可用或需要更高权限）" else output
    }.getOrElse { "执行失败：${it.message ?: it.javaClass.simpleName}" }
}

/** DNS 查询：正向解析 + 反向解析，全部走系统解析器。 */
@Composable
fun DnsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("api.example.com") }
    var result by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize().background(colors.background)) {
        MiuixTopAppBar(
            title = "DNS 查询",
            navigationIcon = Icons.Outlined.ArrowBack,
            onNavigationClick = onBack,
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.pageHorizontal),
        ) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                MiuixTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "域名或 IP",
                    leadingIcon = Icons.Outlined.Dns,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(spacing.sm))
                MiuixIconButton(
                    icon = Icons.Outlined.Search,
                    contentDescription = "查询",
                    filled = true,
                    onClick = {
                        running = true
                        scope.launch {
                            result = resolveDns(query)
                            running = false
                        }
                    },
                )
            }
            Spacer(Modifier.height(spacing.md))
            MiuixText(
                text = "正向解析返回全部 A/AAAA 记录，输入 IP 时自动切换为反向解析。",
                style = MiuixTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(spacing.lg))
            if (running) {
                MiuixText("查询中…", style = MiuixTheme.typography.bodyMedium, color = colors.primary)
            } else if (result.isNotBlank()) {
                MiuixCodeText(code = result, language = CodeLanguage.TEXT, showLineNumbers = false)
            }
            Spacer(Modifier.height(spacing.xxl))
        }
    }
}

private suspend fun resolveDns(query: String): String = withContext(Dispatchers.IO) {
    runCatching {
        val addresses = InetAddress.getAllByName(query)
        buildString {
            appendLine("# ${query}")
            appendLine()
            addresses.forEach { address ->
                appendLine("address  ${address.hostAddress}")
                appendLine("  type   ${if (address.hostAddress?.contains(':') == true) "AAAA" else "A"}")
                runCatching { appendLine("  ptr    ${address.canonicalHostName}") }
                appendLine("  reach  ${if (runCatching { address.isReachable(1500) }.getOrDefault(false)) "可达" else "未响应"}")
            }
        }
    }.getOrElse { "解析失败：${it.message ?: it.javaClass.simpleName}" }
}
