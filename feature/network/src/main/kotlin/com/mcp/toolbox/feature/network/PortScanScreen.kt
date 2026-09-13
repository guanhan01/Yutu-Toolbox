package com.mcp.toolbox.feature.network

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mcp.toolbox.core.design.component.MiuixButton
import com.mcp.toolbox.core.design.component.MiuixButtonSize
import com.mcp.toolbox.core.design.component.MiuixButtonVariant
import com.mcp.toolbox.core.design.component.MiuixCard
import com.mcp.toolbox.core.design.component.MiuixEmptyState
import com.mcp.toolbox.core.design.component.MiuixFilterChip
import com.mcp.toolbox.core.design.component.MiuixLinearProgress
import com.mcp.toolbox.core.design.component.MiuixTag
import com.mcp.toolbox.core.design.component.MiuixText
import com.mcp.toolbox.core.design.component.MiuixTextField
import com.mcp.toolbox.core.design.component.MiuixTopAppBar
import com.mcp.toolbox.core.design.theme.MiuixTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.Socket

/** 常见服务端口：扫描默认集合，命中后展示猜测的服务名。 */
private val COMMON_PORTS = listOf(
    21, 22, 23, 25, 53, 80, 110, 143, 443, 445,
    465, 587, 993, 995, 1080, 1433, 1521, 2049, 2375, 3000,
    3306, 3389, 5000, 5432, 5555, 5900, 6379, 7001, 8000, 8008,
    8080, 8081, 8443, 8888, 9000, 9090, 9200, 11211, 27017,
)

private val SERVICE_NAMES = mapOf(
    21 to "FTP", 22 to "SSH", 23 to "Telnet", 25 to "SMTP", 53 to "DNS",
    80 to "HTTP", 110 to "POP3", 143 to "IMAP", 443 to "HTTPS", 445 to "SMB",
    465 to "SMTPS", 587 to "SMTP 提交", 993 to "IMAPS", 995 to "POP3S", 1080 to "SOCKS",
    1433 to "SQL Server", 1521 to "Oracle", 2049 to "NFS", 2375 to "Docker", 3000 to "Web 服务",
    3306 to "MySQL", 3389 to "RDP", 5000 to "Web 服务", 5432 to "PostgreSQL", 5555 to "ADB",
    5900 to "VNC", 6379 to "Redis", 7001 to "WebLogic", 8000 to "Web 服务", 8008 to "HTTP 备选",
    8080 to "HTTP 代理", 8081 to "HTTP 备选", 8443 to "HTTPS 备选", 8888 to "HTTP 备选",
    9000 to "管理端口", 9090 to "Web 控制台", 9200 to "Elasticsearch", 11211 to "Memcached",
    27017 to "MongoDB",
)

data class PortScanHit(val port: Int, val service: String, val latencyMs: Long)

private fun resolveHost(host: String): String? = runCatching {
    java.net.InetAddress.getByName(host).hostAddress
}.getOrNull()

/** 单端口 TCP 连接探测：真连接，超时视为关闭。 */
private fun probe(ip: String, port: Int, timeoutMs: Int): PortScanHit? {
    val started = System.currentTimeMillis()
    val socket = Socket()
    return runCatching {
        socket.connect(InetSocketAddress(ip, port), timeoutMs)
        PortScanHit(port, SERVICE_NAMES[port] ?: "未知服务", System.currentTimeMillis() - started)
    }.getOrNull().also { runCatching { socket.close() } }
}

/**
 * 端口扫描：真实 TCP connect 扫描。
 * 支持常用端口集合与自定义区间，并发执行、可中途取消，结果为真实连接成功记录。
 */
@Composable
fun PortScanScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    val scope = rememberCoroutineScope()

    var host by remember { mutableStateOf("192.168.1.1") }
    var preset by remember { mutableStateOf("常用端口") }
    var customRange by remember { mutableStateOf("1-1024") }
    var running by remember { mutableStateOf(false) }
    var scanned by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(0) }
    var hits by remember { mutableStateOf(emptyList<PortScanHit>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var cancelToken by remember { mutableStateOf(0) }

    fun targetPorts(): List<Int> = when (preset) {
        "常用端口" -> COMMON_PORTS
        "1-1024" -> (1..1024).toList()
        else -> parseRange(customRange).ifEmpty { COMMON_PORTS }
    }

    fun start() {
        if (running) return
        error = null
        hits = emptyList()
        scanned = 0
        val hosts = host.trim()
        val ports = targetPorts()
        if (hosts.isEmpty()) {
            error = "请先填写目标主机或 IP"
            return
        }
        if (ports.isEmpty()) {
            error = "端口范围无法解析，示例：1-1024 或 80,443,8080"
            return
        }
        running = true
        total = ports.size
        val token = cancelToken + 1
        cancelToken = token
        scope.launch {
            val ip = resolveHost(hosts)
            if (ip == null) {
                error = "无法解析主机：$hosts"
                running = false
                return@launch
            }
            val collected = mutableListOf<PortScanHit>()
            ports.chunked(64).forEach { chunk ->
                val batch = chunk.map { port ->
                    async(Dispatchers.IO) { probe(ip, port, 700) }
                }.awaitAll()
                batch.filterNotNull().let { found ->
                    collected += found
                    hits = collected.sortedBy { it.port }
                }
                scanned += chunk.size
                if (token != cancelToken) return@launch
            }
            running = false
        }
    }

    Column(modifier = modifier.fillMaxSize().background(colors.background)) {
        MiuixTopAppBar(
            title = "端口扫描",
            navigationIcon = Icons.Outlined.ArrowBack,
            onNavigationClick = onBack,
            actions = {
                if (running) {
                    MiuixTag(text = "扫描中", color = colors.primary, filled = true)
                } else if (total > 0) {
                    MiuixTag(text = "完成", color = colors.success, filled = true)
                }
            },
        )
        Column(Modifier.padding(horizontal = spacing.pageHorizontal)) {
            Spacer(Modifier.height(spacing.sm))
            MiuixTextField(
                value = host,
                onValueChange = { host = it },
                placeholder = "主机或 IP，例如 192.168.1.1",
                prefix = "目标",
                enabled = !running,
            )
            Spacer(Modifier.height(spacing.md))
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                listOf("常用端口", "1-1024", "自定义").forEach { item ->
                    MiuixFilterChip(
                        label = item,
                        selected = preset == item,
                        onClick = { if (!running) preset = item },
                    )
                }
            }
            if (preset == "自定义") {
                Spacer(Modifier.height(spacing.sm))
                MiuixTextField(
                    value = customRange,
                    onValueChange = { customRange = it },
                    placeholder = "端口范围，例如 8000-9000 或 80,443",
                    enabled = !running,
                )
            }
            Spacer(Modifier.height(spacing.md))
            Row(verticalAlignment = Alignment.CenterVertically) {
                MiuixButton(
                    text = if (running) "扫描中…" else "开始扫描",
                    onClick = { start() },
                    variant = MiuixButtonVariant.FILLED,
                    size = MiuixButtonSize.MEDIUM,
                    enabled = !running,
                )
                Spacer(Modifier.width(spacing.sm))
                MiuixButton(
                    text = "取消",
                    onClick = {
                        cancelToken += 1
                        running = false
                    },
                    variant = MiuixButtonVariant.OUTLINED,
                    size = MiuixButtonSize.MEDIUM,
                    enabled = running,
                )
                Spacer(Modifier.weight(1f))
                if (total > 0) {
                    MiuixText(
                        text = "$scanned / $total",
                        style = MiuixTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
            if (running && total > 0) {
                Spacer(Modifier.height(spacing.sm))
                MiuixLinearProgress(progress = scanned.toFloat() / total.toFloat())
            }
            error?.let {
                Spacer(Modifier.height(spacing.sm))
                MiuixText(text = it, style = MiuixTheme.typography.bodySmall, color = colors.error)
            }
            Spacer(Modifier.height(spacing.md))
        }

        if (hits.isEmpty()) {
            MiuixEmptyState(
                icon = Icons.Outlined.Lan,
                title = if (running) "正在扫描…" else "尚未发现开放端口",
                description = if (running) {
                    "TCP connect 扫描进行中，结果会实时出现在这里"
                } else {
                    "填写目标后开始扫描；扫描只发起 TCP 连接探测，不做任何漏洞利用"
                },
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(hits, key = { it.port }) { hit ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = spacing.pageHorizontal, vertical = spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(MiuixTheme.radius.sm))
                                .background(colors.successContainer)
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            MiuixText(
                                text = hit.port.toString(),
                                style = MiuixTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace),
                                color = colors.onSuccessContainer,
                            )
                        }
                        Spacer(Modifier.width(spacing.md))
                        Column(Modifier.weight(1f)) {
                            MiuixText(
                                text = hit.service,
                                style = MiuixTheme.typography.bodyMedium,
                            )
                            MiuixText(
                                text = "${host.trim()}:${hit.port}",
                                style = MiuixTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = colors.onSurfaceVariant,
                            )
                        }
                        MiuixText(
                            text = "开放 · ${hit.latencyMs}ms",
                            style = MiuixTheme.typography.labelMedium,
                            color = colors.success,
                        )
                    }
                }
                item { Spacer(Modifier.height(spacing.xxl)) }
            }
        }
    }
}

/** 解析 "1-1024" / "80,443,8080" / 混合写法，返回去重后的端口列表。 */
internal fun parseRange(raw: String): List<Int> {
    val result = linkedSetOf<Int>()
    raw.split(',').forEach { part ->
        val token = part.trim()
        if (token.isEmpty()) return@forEach
        if (token.contains('-')) {
            val bounds = token.split('-')
            val start = bounds.getOrNull(0)?.trim()?.toIntOrNull()
            val end = bounds.getOrNull(1)?.trim()?.toIntOrNull()
            if (start != null && end != null && start in 1..65535 && end in 1..65535 && start <= end) {
                (start..end).take(4096).forEach { result += it }
            }
        } else {
            token.toIntOrNull()?.takeIf { it in 1..65535 }?.let { result += it }
        }
    }
    return result.toList()
}
