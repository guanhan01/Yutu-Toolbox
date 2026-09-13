package com.mcp.toolbox.feature.capture

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mcp.toolbox.core.common.PrivilegeBackend
import com.mcp.toolbox.core.common.PrivilegeManager
import com.mcp.toolbox.core.design.component.MiuixBottomSheet
import com.mcp.toolbox.core.design.component.MiuixButton
import com.mcp.toolbox.core.design.component.MiuixButtonVariant
import com.mcp.toolbox.core.design.component.MiuixCheckbox
import com.mcp.toolbox.core.design.component.MiuixIconButton
import com.mcp.toolbox.core.design.component.MiuixSegmentedButton
import com.mcp.toolbox.core.design.component.MiuixSwitch
import com.mcp.toolbox.core.design.component.MiuixText
import com.mcp.toolbox.core.design.component.MiuixTextField
import com.mcp.toolbox.core.design.theme.MiuixTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 单条记录详情：概览 / 请求 / 响应 / 时序 / 重放。 */
@Composable
fun CaptureDetailSheet(
    visible: Boolean = true,
    record: CaptureRecord,
    onDismiss: () -> Unit,
    onToast: (String) -> Unit = {},
) {

    val context = LocalContext.current

    val colors = MiuixTheme.colors

    val spacing = MiuixTheme.dimens.spacing

    val scope = rememberCoroutineScope()

    val state by CaptureStore.state.collectAsState()

    var latest by remember { mutableStateOf(record) }

    var tab by remember { mutableStateOf("概览") }

    var expanded by remember { mutableStateOf(true) }

    LaunchedEffect(state.records.size, state.running) {
        state.records.firstOrNull { it.id == record.id }?.let { latest = it }
    }

    MiuixBottomSheet(visible = visible, onDismiss = onDismiss) {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .heightIn(max = 620.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = spacing.lg, vertical = spacing.md),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val appIcon =
                    remember(latest.appPackage) { CaptureAppIcons.icon(context, latest.appPackage) }

                if (appIcon != null) {

                    Image(
                        bitmap = appIcon.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.width(18.dp).height(18.dp),
                    )

                    Spacer(Modifier.width(spacing.sm))
                }

                Column(Modifier.weight(1f)) {
                    MiuixText(
                        text = latest.host ?: latest.sni ?: latest.dstIp,
                        style = MiuixTheme.typography.titleSmall,
                        maxLines = 1,
                    )

                    Spacer(Modifier.height(spacing.xs))

                    MiuixText(
                        text =
                            buildString {
                                append(latest.kind)
                                append(" · ")
                                append(latest.protocol)
                                append(" · ")
                                append(latest.dstIp)
                                append(":")
                                append(latest.dstPort)
                                val owner = latest.appLabel ?: latest.appPackage
                                if (owner != null) append(" · 应用：").append(owner)
                            },
                        style = MiuixTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 2,
                    )
                }

                Spacer(Modifier.width(spacing.sm))
                MiuixIconButton(
                    if (expanded) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess,
                    if (expanded) "收起详情" else "展开详情",
                    onClick = { expanded = !expanded },
                )
            }

            if (expanded) {

                Spacer(Modifier.height(spacing.md))

                MiuixSegmentedButton(
                    options = listOf("概览", "请求", "响应", "时序", "重放"),
                    selected = tab,
                    onSelect = { tab = it },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(spacing.md))
                when (tab) {
                    "概览" -> DetailOverview(latest)

                    "请求" ->
                        DetailMessage(
                            head = latest.requestHead,
                            body = latest.requestBody,
                            emptyHint =
                                if (latest.kind == CaptureKind.HTTPS &&
                                    latest.requestHead == null) {
                                    "这条 HTTPS 没有解密，看不到请求内容。原因：" +
                                        (latest.error ?: "客户端不信任用户 CA 或启用了证书固定") +
                                        "。已知域名 ${latest.sni ?: latest.host ?: "-"}。请到证书页点「装到系统证书区」，再强制停止并重开这个 App 后重新抓包；微信、QQ 等自带根证书或私有协议，装上系统证书也无法解密。"
                                } else {
                                    "该连接没有可解析的明文请求头（HTTPS 正文不可见）"
                                },
                        )
                    "响应" ->
                        DetailMessage(
                            head = latest.responseHead,
                            body = latest.responseBody,
                            emptyHint =
                                if (latest.kind == CaptureKind.HTTPS &&
                                    latest.responseHead == null) {
                                    "这条 HTTPS 没有解密，看不到响应内容。原因：" +
                                        (latest.error ?: "客户端不信任用户 CA 或启用了证书固定") +
                                        "。如需明文，请在证书页装到系统证书区，并强制停止后重开该 App 再抓包；自带根证书或私有协议的 App 无法解密。"
                                } else {
                                    "没有可解析的明文响应体"
                                },
                        )
                    "时序" -> DetailTiming(latest)
                    else -> ReplaySection(record = latest, onToast = onToast)
                }

                Spacer(Modifier.height(spacing.md))
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    MiuixButton(
                        text = "复制 cURL",
                        onClick = {
                            copyToClipboard(context, CaptureExport.toCurl(latest))
                            onToast("cURL 已复制到剪贴板")
                        },
                        modifier = Modifier.weight(1f),
                        variant = MiuixButtonVariant.OUTLINED,
                    )
                    MiuixButton(
                        text = "保存为 MCP 资源",
                        onClick = {
                            scope.launch {
                                val json = CaptureExport.toJson(listOf(latest))
                                val name =
                                    CaptureExport.timestampName(
                                        "capture-record-${latest.id}", "json")
                                val saved =
                                    withContext(Dispatchers.IO) {
                                        writeMcpResource(context, name, json)
                                            ?: CaptureExport.saveText(
                                                context, name, json, "application/json")
                                    }
                                onToast(if (saved != null) "已保存：$saved" else "保存失败：缺少可写目录")
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }

                Spacer(Modifier.height(spacing.md))
            }
        }
    }
}

@Composable
private fun DetailOverview(record: CaptureRecord) {

    val formatter = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }

    Column {
        DetailRow("类型", "${record.kind} / ${record.protocol}")

        DetailRow("主机", record.host ?: "—")

        DetailRow("路径", record.path ?: "—")

        DetailRow("SNI", record.sni ?: "—")

        DetailRow("TLS", record.tlsVersion ?: "—")

        DetailRow("ALPN", record.alpn ?: "—")

        DetailRow("应用", record.appLabel ?: record.appPackage ?: "未知（需 API 29+ 且可归属）")

        DetailRow("源", "${record.srcIp}:${record.srcPort}")

        DetailRow("目的", "${record.dstIp}:${record.dstPort}")

        DetailRow("状态", stateLabel(record))

        DetailRow("状态码", record.status?.let { "$it ${record.reason ?: ""}" } ?: "—")
        DetailRow(
            "上行 / 下行", "${formatBytes(record.requestBytes)} / ${formatBytes(record.responseBytes)}")
        DetailRow("耗时", "${record.durationMs} ms（TTFB ${record.ttfbMs ?: -1} ms）")
        DetailRow("开始", formatter.format(Date(record.startedAt)))
        DetailRow("结束", record.endedAt?.let { formatter.format(Date(it)) } ?: "进行中")
        record.error?.let { DetailRow("错误", it) }
    }
}

private fun stateLabel(record: CaptureRecord): String =
    when (record.state) {
        FlowState.CONNECTING -> "握手中"

        FlowState.OPEN -> "已连接"

        FlowState.CLOSED -> "已结束"

        FlowState.FAILED -> "失败"
    }

@Composable
private fun DetailRow(label: String, value: String) {

    val colors = MiuixTheme.colors

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        MiuixText(
            text = label,
            modifier = Modifier.width(96.dp),
            style = MiuixTheme.typography.labelMedium,
            color = colors.onSurfaceVariant,
        )

        MiuixText(
            text = value,
            modifier = Modifier.weight(1f),
            style = MiuixTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun DetailMessage(head: String?, body: String?, emptyHint: String) {

    val colors = MiuixTheme.colors

    val spacing = MiuixTheme.dimens.spacing

    Column {
        if (head == null && body == null) {

            MiuixText(
                text = emptyHint,
                style = MiuixTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )

            return@Column
        }

        head?.let { text ->
            MiuixText(
                text = text,
                modifier =
                    Modifier.fillMaxWidth()
                        .background(colors.codeBackground, RoundedCornerShape(MiuixTheme.radius.sm))
                        .padding(spacing.sm),
                style = MiuixTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
        }

        body?.let { text ->
            Spacer(Modifier.height(spacing.sm))

            MiuixText(
                text = text.take(8000),
                modifier =
                    Modifier.fillMaxWidth()
                        .background(
                            colors.surfaceContainerLow, RoundedCornerShape(MiuixTheme.radius.sm))
                        .padding(spacing.sm),
                style = MiuixTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
        }
    }
}

@Composable
private fun DetailTiming(record: CaptureRecord) {

    val total = record.durationMs.coerceAtLeast(1)

    val connect = record.tlsHandshakeMs ?: 0

    val ttfb = record.ttfbMs ?: 0

    val receive = (total - connect - ttfb).coerceAtLeast(0)

    Column {
        TimingBar("建立 / TLS 握手", connect, total)

        TimingBar("等待响应 TTFB", ttfb, total)

        TimingBar("接收正文", receive, total)

        TimingBar("总耗时", total, total)

        Spacer(Modifier.height(8.dp))

        MiuixText(
            text = "时间来自 tun 报文与中继套接字的真实时刻；HTTPS 的 ssl 段为 ClientHello → 应用数据的时间差。",
            style = MiuixTheme.typography.labelSmall,
            color = MiuixTheme.colors.onSurfaceVariant,
        )
    }
}

@Composable
private fun TimingBar(label: String, value: Long, total: Long) {

    val colors = MiuixTheme.colors

    val fraction = if (total <= 0) 0f else (value.toFloat() / total).coerceIn(0f, 1f)

    Column(Modifier.padding(vertical = 4.dp)) {
        Row {
            MiuixText(
                text = label,
                modifier = Modifier.weight(1f),
                style = MiuixTheme.typography.labelMedium)

            MiuixText(
                text = "$value ms",
                style = MiuixTheme.typography.labelMedium,
                color = colors.primary)
        }

        Spacer(Modifier.height(4.dp))

        Box(
            modifier =
                Modifier.fillMaxWidth()
                    .height(6.dp)
                    .background(colors.surfaceContainerLow, RoundedCornerShape(50)),
        ) {
            Box(
                modifier =
                    Modifier.fillMaxWidth(fraction.coerceAtLeast(0.02f))
                        .height(6.dp)
                        .background(colors.primary, RoundedCornerShape(50)),
            )
        }
    }
}

/** Repeater：把捕获到的明文请求（可编辑）重新发出，展示真实响应。 */
@Composable
private fun ReplaySection(record: CaptureRecord, onToast: (String) -> Unit) {

    val colors = MiuixTheme.colors

    val spacing = MiuixTheme.dimens.spacing

    val scope = rememberCoroutineScope()

    var method by remember(record.id) { mutableStateOf(record.method ?: "GET") }

    var url by remember(record.id) { mutableStateOf(record.url ?: "") }

    var headers by
        remember(record.id) {
            mutableStateOf(record.requestHeaders.joinToString("\n") { "${it.first}: ${it.second}" })
        }

    var body by remember(record.id) { mutableStateOf(record.requestBody ?: "") }

    var result by remember(record.id) { mutableStateOf<HttpRepeater.Result?>(null) }

    var sending by remember(record.id) { mutableStateOf(false) }

    Column {
        if (record.kind != CaptureKind.HTTP) {

            MiuixText(
                text = "只有明文 HTTP 记录可以原样重放；HTTPS 记录没有可用明文请求（未做中间人解密）。",
                style = MiuixTheme.typography.bodySmall,
                color = colors.warning,
            )

            Spacer(Modifier.height(spacing.sm))
        }

        MiuixTextField(
            value = method,
            onValueChange = { method = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = "方法",
        )
        Spacer(Modifier.height(spacing.sm))
        MiuixTextField(
            value = url,
            onValueChange = { url = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = "URL",
        )
        Spacer(Modifier.height(spacing.sm))
        MiuixTextField(
            value = headers,
            onValueChange = { headers = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = "请求头（每行 Name: Value）",
            singleLine = false,
            minLines = 3,
        )
        Spacer(Modifier.height(spacing.sm))
        MiuixTextField(
            value = body,
            onValueChange = { body = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = "请求体",
            singleLine = false,
            minLines = 2,
        )
        Spacer(Modifier.height(spacing.sm))
        MiuixButton(
            text = if (sending) "发送中…" else "重放请求",
            onClick = {
                if (url.isBlank()) {
                    onToast("URL 为空，无法重放")
                } else {
                    sending = true
                    scope.launch {
                        val response =
                            HttpRepeater.send(
                                method = method,
                                url = url,
                                headersText = headers,
                                body = body.ifBlank { null },
                            )
                        result = response
                        sending = false
                        onToast(
                            if (response.ok) "重放完成：HTTP ${response.status}"
                            else "重放失败：${response.error}",
                        )
                    }
                }
            },
            enabled = !sending,
            modifier = Modifier.fillMaxWidth(),
            loading = sending,
        )
        result?.let { response ->
            Spacer(Modifier.height(spacing.md))

            MiuixText(
                text =
                    if (response.ok) {
                        "HTTP ${response.status} ${response.statusText} · ${response.durationMs} ms"
                    } else {
                        "失败：${response.error}"
                    },
                style = MiuixTheme.typography.labelLarge,
                color = if (response.ok) colors.success else colors.error,
            )
            Spacer(Modifier.height(spacing.xs))
            MiuixText(
                text =
                    response.headers.joinToString("\n") { "${it.first}: ${it.second}" }.take(2000),
                style = MiuixTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = colors.onSurfaceVariant,
            )
            if (response.body.isNotBlank()) {

                Spacer(Modifier.height(spacing.xs))

                MiuixText(
                    text = response.body.take(4000),
                    modifier =
                        Modifier.fillMaxWidth()
                            .background(
                                colors.surfaceContainerLow,
                                RoundedCornerShape(MiuixTheme.radius.sm))
                            .padding(spacing.sm),
                    style = MiuixTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {

    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    manager.setPrimaryClip(ClipData.newPlainText("MCP Toolbox Capture", text))
}

/** 写入应用内 MCP 产物目录，供内置 Server / AI 继续分析。 */
private fun writeMcpResource(context: Context, fileName: String, content: String): String? {

    val dir = File(context.filesDir, "mcp-output/capture")

    if (!dir.exists() && !dir.mkdirs()) return null

    val file = File(dir, fileName)

    return runCatching {
            file.writeText(content)

            "应用私有目录 mcp-output/capture/$fileName"
        }
        .getOrNull()
}

@Composable
fun CaptureConfigSheet(
    visible: Boolean = true,
    config: CaptureConfig,
    onDismiss: () -> Unit,
    onApply: (CaptureConfig) -> Unit,
) {

    val colors = MiuixTheme.colors

    val spacing = MiuixTheme.dimens.spacing

    var mode by remember { mutableStateOf(config.mode) }

    var dnsOnly by remember { mutableStateOf(config.dnsOnly) }

    var bodyLimit by remember { mutableStateOf(config.bodyLimitBytes) }

    MiuixBottomSheet(visible = visible, onDismiss = onDismiss) {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = spacing.lg, vertical = spacing.md),
        ) {
            MiuixText(text = "抓包配置", style = MiuixTheme.typography.titleSmall)

            Spacer(Modifier.height(spacing.md))

            MiuixText(text = "接管范围", style = MiuixTheme.typography.labelLarge)

            Spacer(Modifier.height(spacing.xs))

            MiuixText(
                text =
                    "全部应用 = 0.0.0.0/0 进 tun；仅选中 / 排除选中 = 按 App 过滤；仅 DNS 观察只接管 DNS 服务器地址，不影响其它流量。",
                style = MiuixTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )

            Spacer(Modifier.height(spacing.sm))

            MiuixSegmentedButton(
                options =
                    listOf(
                        CaptureMode.ALL, CaptureMode.ONLY_SELECTED, CaptureMode.EXCLUDE_SELECTED),
                selected = mode,
                onSelect = { mode = it },
                label = { value ->
                    when (value) {
                        CaptureMode.ALL -> "全部"
                        CaptureMode.ONLY_SELECTED -> "仅选中"
                        CaptureMode.EXCLUDE_SELECTED -> "排除选中"
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(spacing.md))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    MiuixText(text = "仅 DNS 观察（安全模式）", style = MiuixTheme.typography.labelLarge)

                    MiuixText(
                        text = "只把当前网络的 DNS 服务器地址路由进 tun，其余流量完全不受影响。",
                        style = MiuixTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }

                MiuixSwitch(checked = dnsOnly, onCheckedChange = { dnsOnly = it })
            }

            Spacer(Modifier.height(spacing.md))
            MiuixText(text = "单条记录正文上限", style = MiuixTheme.typography.labelLarge)
            Spacer(Modifier.height(spacing.xs))
            MiuixSegmentedButton(
                options = listOf(16 * 1024, 64 * 1024, 256 * 1024),
                selected = bodyLimit,
                onSelect = { bodyLimit = it },
                label = { "${it / 1024} KB" },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(spacing.md))
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                MiuixButton(
                    text = "取消",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    variant = MiuixButtonVariant.OUTLINED,
                )

                MiuixButton(
                    text = "保存",
                    onClick = {
                        onApply(
                            config.copy(mode = mode, dnsOnly = dnsOnly, bodyLimitBytes = bodyLimit))
                    },
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(spacing.sm))
            MiuixText(
                text =
                    "当前生效：${modeLabel(config)}${if (config.dnsOnly) " · 仅 DNS" else ""} · 正文上限 ${config.bodyLimitBytes / 1024} KB",
                style = MiuixTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

internal fun modeLabel(config: CaptureConfig): String =
    when (config.mode) {
        CaptureMode.ALL -> "全部应用"

        CaptureMode.ONLY_SELECTED -> "仅选中 ${config.packages.size} 个应用"

        CaptureMode.EXCLUDE_SELECTED -> "排除选中 ${config.packages.size} 个应用"
    }

/** 按 App 选择接管范围：真实读取已安装应用列表。 */
@Composable
fun AppFilterSheet(
    visible: Boolean = true,
    config: CaptureConfig,
    onDismiss: () -> Unit,
    onApply: (CaptureConfig) -> Unit,
) {

    val colors = MiuixTheme.colors

    val spacing = MiuixTheme.dimens.spacing

    val context = LocalContext.current

    var selected by remember { mutableStateOf(config.packages) }

    var query by remember { mutableStateOf("") }

    var apps by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { loadLaunchableApps(context) }

        loading = false
    }

    val filteredApps =
        apps.filter {
            query.isBlank() || it.first.contains(query, true) || it.second.contains(query, true)
        }

    MiuixBottomSheet(visible = visible, onDismiss = onDismiss) {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .heightIn(max = 620.dp)
                    .padding(horizontal = spacing.lg, vertical = spacing.md),
        ) {
            MiuixText(
                text = "应用过滤 · ${modeLabel(config)}", style = MiuixTheme.typography.titleSmall)

            Spacer(Modifier.height(spacing.xs))

            MiuixText(
                text = "选中的应用会在「仅选中 / 排除选中」模式下交给系统按 UID 过滤；当前选择 ${selected.size} 个。",
                style = MiuixTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )

            Spacer(Modifier.height(spacing.sm))

            MiuixTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = "搜索应用名或包名",
            )

            Spacer(Modifier.height(spacing.sm))

            if (loading) {

                MiuixText(text = "读取应用列表…", style = MiuixTheme.typography.bodySmall)
            } else {

                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(filteredApps, key = { it.second }) { app ->
                        val checked = selected.contains(app.second)

                        Row(
                            modifier =
                                Modifier.fillMaxWidth()
                                    .clickable {
                                        selected =
                                            if (checked) selected - app.second
                                            else selected + app.second
                                    }
                                    .padding(vertical = spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            MiuixCheckbox(
                                checked = checked,
                                onCheckedChange = { value ->
                                    selected =
                                        if (value) selected + app.second else selected - app.second
                                })

                            Spacer(Modifier.width(spacing.sm))

                            Column(Modifier.weight(1f)) {
                                MiuixText(
                                    text = app.first,
                                    style = MiuixTheme.typography.bodyMedium,
                                    maxLines = 1)

                                MiuixText(
                                    text = app.second,
                                    style = MiuixTheme.typography.labelSmall,
                                    color = colors.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(spacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                MiuixButton(
                    text = "取消",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    variant = MiuixButtonVariant.OUTLINED,
                )

                MiuixButton(
                    text = "保存选择",
                    onClick = { onApply(config.copy(packages = selected)) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private fun loadLaunchableApps(context: Context): List<Pair<String, String>> {

    val manager = context.packageManager

    val intent =
        android.content
            .Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER)

    val resolved =
        runCatching { manager.queryIntentActivities(intent, PackageManager.MATCH_ALL) }.getOrNull()
            ?: return emptyList()
    return resolved
        .map { it.activityInfo.applicationInfo.packageName }
        .distinct()
        .map { pkg ->
            val label =
                runCatching {
                        manager.getApplicationLabel(manager.getApplicationInfo(pkg, 0)).toString()
                    }
                    .getOrDefault(pkg)
            label to pkg
        }
        .sortedBy { it.first }
}

/** 用户 CA：真实生成 + 导出 + 安装引导。 */
@Composable
fun CertificateSheet(
    visible: Boolean = true,
    onDismiss: () -> Unit,
    onToast: (String) -> Unit = {}
) {

    val colors = MiuixTheme.colors

    val spacing = MiuixTheme.dimens.spacing

    val context = LocalContext.current

    val scope = rememberCoroutineScope()

    var info by remember { mutableStateOf<CertificateAuthority.CaInfo?>(null) }

    var error by remember { mutableStateOf<String?>(null) }

    var busy by remember { mutableStateOf(true) }

    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        val result = withContext(Dispatchers.IO) { CertificateAuthority.ensure(context) }

        busy = false
        result.onSuccess { info = it }.onFailure { error = it.message ?: it.javaClass.simpleName }
    }

    MiuixBottomSheet(visible = visible, onDismiss = onDismiss) {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .heightIn(max = 620.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = spacing.lg, vertical = spacing.md),
        ) {
            MiuixText(text = "用户 CA 证书", style = MiuixTheme.typography.titleSmall)

            Spacer(Modifier.height(spacing.sm))

            when {
                busy ->
                    MiuixText(
                        text = "正在生成 RSA 2048 自签 CA…", style = MiuixTheme.typography.bodySmall)

                error != null ->
                    MiuixText(
                        text = "生成失败：$error",
                        style = MiuixTheme.typography.bodySmall,
                        color = colors.error,
                    )

                else -> info?.let { ca -> CaDetails(ca, context, scope, onToast) }
            }
        }
    }
}

@Composable
private fun CaDetails(
    ca: CertificateAuthority.CaInfo,
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
    onToast: (String) -> Unit,
) {

    val colors = MiuixTheme.colors

    val spacing = MiuixTheme.dimens.spacing

    // 保证探测脚本里的证书文件名哈希来自证书本体（否则会退回手写 ASN.1 的旧哈希）。
    remember(ca) { MitmCa.rememberCa(ca.certificate) }

    var probe by remember { mutableStateOf<List<Pair<String, String>>?>(null) }

    var probeError by remember { mutableStateOf<String?>(null) }

    var systemStatus by remember { mutableStateOf("") }

    var busy by remember { mutableStateOf(false) }

    // 检测按钮自己的忙态：原先和「装到系统证书区」共用 busy，点检测时系统区按钮也跟着显示「处理中…」。
    var detecting by remember { mutableStateOf(false) }

    var skipped by remember { mutableStateOf(CaptureFlags.skippedPackages()) }

    suspend fun refreshProbe() {

        val result =
            withContext(Dispatchers.IO) {
                PrivilegeManager.exec(
                    MitmCa.trustProbeCommand() + MitmCa.systemStoreStatusCommand(),
                    12_000L,
                    PrivilegeBackend.ROOT,
                )
            }

        if (result.ok) {

            probe =
                result.stdout
                    .lines()
                    .filter { it.isNotBlank() }
                    .map { raw ->
                        val line =
                            raw.trim()
                                .replace(MitmCa.USER_CA_DIR, "用户证书区")
                                .replace(MitmCa.SYSTEM_CA_DIR, "系统证书区")

                        when {
                            line.contains("=") ->
                                line.substringBefore("=") to line.substringAfter("=")

                            line.endsWith(" 有") -> line.removeSuffix(" 有") to "已安装"

                            line.endsWith(" 无") -> line.removeSuffix(" 无") to "未安装"

                            line.contains(" ") ->
                                line.substringBefore(" ") to line.substringAfter(" ")

                            else -> line to ""
                        }
                    }

            probeError = null
        } else {

            probeError = result.failure ?: "检测失败（需要 root）"
        }
    }

    LaunchedEffect(Unit) { refreshProbe() }

    LaunchedEffect(Unit) {
        while (true) {

            skipped = CaptureFlags.skippedPackages()

            delay(3000)
        }
    }

    CaSectionTitle("证书信息")
    DetailRow("主体", ca.subject)
    DetailRow("有效期至", SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ca.notAfter)))
    DetailRow("自签名校验", if (ca.selfVerified) "通过" else "未通过")
    DetailRow("指纹", MitmCa.fingerprint(ca.certificate))
    CaSectionTitle("信任状态")
    val rows = probe
    when {
        probeError != null ->
            MiuixText(
                text = probeError ?: "",
                style = MiuixTheme.typography.bodySmall,
                color = colors.error,
            )

        rows == null ->
            MiuixText(
                text = "正在检测…",
                style = MiuixTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )

        else -> rows.forEach { (label, value) -> CaStatusRow(label, value) }
    }

    // 只有探测成功返回的行才算数：超时/失败时 probe 可能还停在上一次的结果，不能据此判定「未安装」。
    val detected = rows != null && probeError == null

    val caInstalled =
        detected &&
            (rows?.any { (label, value) ->
                (label.startsWith("用户证书区") || label.startsWith("系统证书区")) && value == "已安装"
            } ?: false)

    Spacer(Modifier.height(spacing.xs))
    MiuixButton(
        text = if (detecting) "检测中…" else "重新检测 CA 根证书",
        onClick = {
            detecting = true
            scope.launch {
                refreshProbe()
                val now = probe
                val ok =
                    now?.any { (label, value) ->
                        (label.startsWith("用户证书区") || label.startsWith("系统证书区")) && value == "已安装"
                    } ?: false
                detecting = false
                onToast(
                    when {
                        probeError != null -> "检测失败：" + probeError
                        ok -> "检测完成：已找到已安装的根证书"
                        else -> "检测完成：未检测到已安装的根证书"
                    })
            }
        },
        enabled = !detecting,
        modifier = Modifier.fillMaxWidth(),
        variant = MiuixButtonVariant.OUTLINED,
    )

    CaSectionTitle("安装到设备")
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        MiuixButton(
            text = "装到用户证书区",
            onClick = {
                scope.launch {
                    val result =
                        withContext(Dispatchers.IO) {
                            PrivilegeManager.exec(
                                MitmCa.userStoreInstallCommand(ca.pem),
                                20_000L,
                                PrivilegeBackend.ROOT)
                        }
                    onToast(
                        if (result.ok) "已写入用户证书区，重启目标 App 后生效"
                        else "安装失败：" + (result.failure ?: result.stderr.take(120)),
                    )
                    refreshProbe()
                }
            },
            modifier = Modifier.weight(1f),
        )
        MiuixButton(
            text = "移除用户证书",
            onClick = {
                scope.launch {
                    val result =
                        withContext(Dispatchers.IO) {
                            PrivilegeManager.exec(
                                MitmCa.userStoreRemoveCommand(), 20_000L, PrivilegeBackend.ROOT)
                        }
                    onToast(
                        if (result.ok) "已移除用户证书区的这把 CA"
                        else "移除失败：" + (result.failure ?: result.stderr.take(120)),
                    )
                    refreshProbe()
                }
            },
            modifier = Modifier.weight(1f),
            variant = MiuixButtonVariant.OUTLINED,
        )
    }

    Spacer(Modifier.height(spacing.sm))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        MiuixButton(
            text = if (busy) "处理中…" else "装到系统证书区",
            onClick = {
                busy = true
                scope.launch {
                    val result =
                        withContext(Dispatchers.IO) {
                            PrivilegeManager.exec(
                                MitmCa.systemStoreInstallCommand(ca.pem),
                                90_000L,
                                PrivilegeBackend.ROOT)
                        }
                    busy = false
                    systemStatus =
                        if (result.ok) {
                            result.stdout.lines().filter { it.isNotBlank() }.joinToString("\n")
                        } else {
                            "失败：" + (result.failure ?: result.stderr.take(200))
                        }
                    refreshProbe()
                }
            },
            modifier = Modifier.weight(1f),
        )
        MiuixButton(
            text = "还原系统证书区",
            onClick = {
                scope.launch {
                    val result =
                        withContext(Dispatchers.IO) {
                            PrivilegeManager.exec(
                                MitmCa.systemStoreRemoveCommand(), 60_000L, PrivilegeBackend.ROOT)
                        }
                    systemStatus =
                        if (result.ok) {
                            result.stdout.lines().filter { it.isNotBlank() }.joinToString("\n")
                        } else {
                            "失败：" + (result.failure ?: result.stderr.take(200))
                        }
                    refreshProbe()
                }
            },
            modifier = Modifier.weight(1f),
            variant = MiuixButtonVariant.OUTLINED,
        )
    }

    if (systemStatus.isNotBlank()) {

        Spacer(Modifier.height(spacing.sm))

        systemStatus
            .lines()
            .filter { it.isNotBlank() }
            .forEach { line -> CaStatusRow("系统证书区", line) }
    }

    Spacer(Modifier.height(spacing.sm))
    MiuixText(
        text = "系统证书区用 tmpfs 覆盖证书目录，重启设备自动还原；已启动的 App 需强制停止后重开才会读到这把 CA。",
        style = MiuixTheme.typography.bodySmall,
        color = colors.onSurfaceVariant,
    )
    CaSectionTitle("跳过解密的应用")
    if (skipped.isEmpty()) {

        MiuixText(
            text = "暂无。出现「客户端不信任 CA」的应用会列在这里。",
            style = MiuixTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
    } else {

        skipped.forEach { pkg ->
            MiuixText(text = "· $pkg", style = MiuixTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(spacing.sm))
        MiuixButton(
            text = "为这些 App 补铺证书并重新尝试解密",
            onClick = {
                busy = true
                scope.launch {
                    val result =
                        withContext(Dispatchers.IO) {
                            PrivilegeManager.exec(
                                MitmCa.systemStoreCoverCommand(skipped),
                                90_000L,
                                PrivilegeBackend.ROOT)
                        }
                    CaptureFlags.clearSkipped()
                    skipped = CaptureFlags.skippedPackages()
                    busy = false
                    systemStatus =
                        if (result.ok) {
                            result.stdout.lines().filter { it.isNotBlank() }.joinToString("\n")
                        } else {
                            "失败：" + (result.failure ?: result.stderr.take(200))
                        }
                    refreshProbe()
                }
            },
            modifier = Modifier.fillMaxWidth(),
            variant = MiuixButtonVariant.OUTLINED,
        )
    }

    CaSectionTitle("导出与系统设置")
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        MiuixButton(
            text = "导出 PEM",
            onClick = {
                scope.launch {
                    val name = CaptureExport.timestampName("mcp-capture-ca", "pem")
                    val saved =
                        withContext(Dispatchers.IO) {
                            CaptureExport.saveText(context, name, ca.pem, "application/x-pem-file")
                        }
                    onToast(if (saved != null) "已导出：$saved" else "导出失败：无法写入下载目录")
                }
            },
            modifier = Modifier.weight(1f),
            variant = MiuixButtonVariant.OUTLINED,
        )
        MiuixButton(
            text = "复制 PEM",
            onClick = {
                copyToClipboard(context, ca.pem)
                onToast("CA PEM 已复制")
            },
            modifier = Modifier.weight(1f),
            variant = MiuixButtonVariant.OUTLINED,
        )
    }

    Spacer(Modifier.height(spacing.sm))
    MiuixButton(
        text = "打开系统安全设置",
        onClick = {
            val intent =
                android.content
                    .Intent("android.settings.SECURITY_SETTINGS")
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            val ok = runCatching { context.startActivity(intent) }.isSuccess
            onToast(if (ok) "已打开系统安全设置" else "无法打开安全设置")
        },
        modifier = Modifier.fillMaxWidth(),
        variant = MiuixButtonVariant.OUTLINED,
    )
    val others = remember { MitmCa.installedMitmCas() }

    if (others.isNotEmpty()) {

        CaSectionTitle("系统里已有的 MITM CA")

        others.forEach { CaStatusRow(it, "已存在") }
    }

    Spacer(Modifier.height(spacing.md))
    CaSectionTitle("下载 CA 证书")
    MiuixText(
        text =
            "保存到「下载」根目录：.pem 通用，可给电脑或浏览器导入；.crt（DER）用于安卓「从存储安装 CA 证书」；.zip 是 Magisk / KernelSU 模块，刷入后系统信任区自动生效（Android 14+ 由模块的 service.sh 覆盖 Conscrypt APEX）。",
        style = MiuixTheme.typography.labelSmall,
        color = MiuixTheme.colors.onSurfaceVariant,
    )
    Spacer(Modifier.height(spacing.sm))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        MiuixButton(
            text = "下载 CA 证书",
            onClick = {
                scope.launch {
                    val name = CaptureExport.timestampName("mcp-capture-ca", "pem")
                    val saved =
                        withContext(Dispatchers.IO) {
                            CaptureExport.saveText(context, name, ca.pem, "application/x-pem-file")
                        }
                    onToast(if (saved != null) "已下载：" + saved else "下载失败：无法写入下载目录")
                }
            },
            modifier = Modifier.weight(1f),
        )
        MiuixButton(
            text = "下载 .crt",
            onClick = {
                scope.launch {
                    val name = CaptureExport.timestampName("mcp-capture-ca", "crt")
                    val saved =
                        withContext(Dispatchers.IO) {
                            CaptureExport.saveBytes(
                                context, name, ca.der, "application/x-x509-ca-cert")
                        }
                    onToast(if (saved != null) "已下载：" + saved else "下载失败：无法写入下载目录")
                }
            },
            modifier = Modifier.weight(1f),
            variant = MiuixButtonVariant.OUTLINED,
        )
    }
    Spacer(Modifier.height(spacing.sm))
    MiuixButton(
        text = "下载 CA 根证书模块 (.zip)",
        onClick = {
            scope.launch {
                val name = CaptureExport.timestampName("mcp-capture-ca-module", "zip")
                val bytes =
                    withContext(Dispatchers.IO) {
                        CaModuleZip.build(
                            pem = ca.pem,
                            hashFileName = MitmCa.systemFileName(),
                            serviceScript = MitmCa.moduleServiceScript(ca.pem),
                        )
                    }
                val saved =
                    withContext(Dispatchers.IO) {
                        CaptureExport.saveBytes(context, name, bytes, "application/zip")
                    }
                onToast(if (saved != null) "已下载模块：" + saved else "下载失败：无法写入下载目录")
            }
        },
        modifier = Modifier.fillMaxWidth(),
        variant = MiuixButtonVariant.OUTLINED,
    )

    Spacer(Modifier.height(spacing.md))
    CaSectionTitle("说明")
    val unknown = rows == null && probeError == null
    val bannerTitle =
        when {
            probeError != null -> "检测失败：暂时无法确认是否已安装"
            unknown -> "正在检测根证书…"
            caInstalled -> "已安装根证书：其他说明"
            else -> "未安装根证书：功能运作有缺"
        }
    val bannerBg =
        when {
            probeError != null || unknown -> Color(0xFFE7E0EC)
            caInstalled -> Color(0xFFFFF1C2)
            else -> Color(0xFFFFDAD6)
        }
    val bannerFg =
        when {
            probeError != null || unknown -> Color(0xFF49454F)
            caInstalled -> Color(0xFF7A4F01)
            else -> Color(0xFF8C1D18)
        }
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .background(bannerBg, RoundedCornerShape(12.dp))
                .padding(spacing.sm),
    ) {
        MiuixText(text = bannerTitle, style = MiuixTheme.typography.labelMedium, color = bannerFg)

        Spacer(Modifier.height(4.dp))

        if (probeError != null) {

            MiuixText(
                text = "探测脚本没有返回可用的结果（可能是 root 授权排队或执行超时）：" + probeError + "。点上方「重新检测 CA 根证书」再试一次。",
                style = MiuixTheme.typography.bodySmall,
                color = bannerFg,
            )
        } else if (unknown) {

            MiuixText(
                text = "正在读取用户证书区与系统证书区，稍候即可看到结论。",
                style = MiuixTheme.typography.bodySmall,
                color = bannerFg,
            )
        } else if (caInstalled) {

            CertificateAuthority.installGuide().forEach { line ->
                MiuixText(
                    text = "• $line",
                    style = MiuixTheme.typography.bodySmall,
                    color = bannerFg,
                )

                Spacer(Modifier.height(4.dp))
            }
        } else {

            MiuixText(
                text =
                    "HTTPS 仍只能看到域名、SNI 与流量统计；打开「HTTPS 解密」后应用校验失败会直接断连。" +
                        "请先点「装到用户证书区」（普通 App 够用），或点「装到系统证书区」覆盖系统信任目录；" +
                        "已启动的应用需强制停止后重开才会读到这把 CA。",
                style = MiuixTheme.typography.bodySmall,
                color = bannerFg,
            )
        }
    }
}

@Composable
private fun CaSectionTitle(text: String) {

    Spacer(Modifier.height(MiuixTheme.dimens.spacing.md))

    MiuixText(text = text, style = MiuixTheme.typography.titleSmall)

    Spacer(Modifier.height(4.dp))
}

@Composable
private fun CaStatusRow(label: String, value: String) {

    val colors = MiuixTheme.colors

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MiuixText(
            text = label,
            modifier = Modifier.weight(1f),
            style = MiuixTheme.typography.bodySmall,
        )

        MiuixText(
            text = value,
            style = MiuixTheme.typography.labelMedium,
            color = if (value == "未安装") colors.error else colors.onSurfaceVariant,
        )
    }
}

/** 导出：HAR / JSON，写入系统下载目录。 */
@Composable
fun ExportSheet(
    visible: Boolean = true,
    records: List<CaptureRecord>,
    onDismiss: () -> Unit,
    onToast: (String) -> Unit = {},
) {

    val colors = MiuixTheme.colors

    val spacing = MiuixTheme.dimens.spacing

    val context = LocalContext.current

    val scope = rememberCoroutineScope()

    var busy by remember { mutableStateOf(false) }

    MiuixBottomSheet(visible = visible, onDismiss = onDismiss) {
        Column(
            modifier =
                Modifier.fillMaxWidth().padding(horizontal = spacing.lg, vertical = spacing.md),
        ) {
            MiuixText(text = "导出抓包结果", style = MiuixTheme.typography.titleSmall)

            Spacer(Modifier.height(spacing.xs))

            MiuixText(
                text = "共 ${records.size} 条记录；HAR 含请求/响应头与可见正文，HTTPS 记录标注为未解密。写入「下载」根目录。",
                style = MiuixTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )

            Spacer(Modifier.height(spacing.md))

            MiuixButton(
                text = if (busy) "导出中…" else "导出 HAR",
                onClick = {
                    busy = true
                    scope.launch {
                        val name = CaptureExport.timestampName("capture", "har")
                        val saved =
                            withContext(Dispatchers.IO) {
                                CaptureExport.saveText(
                                    context, name, CaptureExport.toHar(records), "application/json")
                            }
                        busy = false
                        onToast(if (saved != null) "已导出：$saved" else "导出失败：无法写入下载目录")
                    }
                },
                enabled = !busy && records.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                loading = busy,
            )
            Spacer(Modifier.height(spacing.sm))
            MiuixButton(
                text = "导出 JSON",
                onClick = {
                    busy = true
                    scope.launch {
                        val name = CaptureExport.timestampName("capture", "json")
                        val saved =
                            withContext(Dispatchers.IO) {
                                CaptureExport.saveText(
                                    context,
                                    name,
                                    CaptureExport.toJson(records),
                                    "application/json")
                            }
                        busy = false
                        onToast(if (saved != null) "已导出：$saved" else "导出失败：无法写入下载目录")
                    }
                },
                enabled = !busy && records.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                variant = MiuixButtonVariant.OUTLINED,
            )
            Spacer(Modifier.height(spacing.sm))
            MiuixButton(
                text = "保存为 MCP 资源",
                onClick = {
                    scope.launch {
                        val json = CaptureExport.toJson(records)
                        val name = CaptureExport.timestampName("capture-session", "json")
                        val saved =
                            withContext(Dispatchers.IO) {
                                writeMcpResource(context, name, json)
                                    ?: CaptureExport.saveText(
                                        context, name, json, "application/json")
                            }
                        onToast(if (saved != null) "已保存：$saved" else "保存失败：没有可写目录")
                    }
                },
                enabled = records.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                variant = MiuixButtonVariant.OUTLINED,
            )
        }
    }
}

/** DNS 观测记录。 */
@Composable
fun DnsLogSheet(
    visible: Boolean = true,
    observations: List<DnsObservation>,
    onDismiss: () -> Unit
) {

    val colors = MiuixTheme.colors

    val spacing = MiuixTheme.dimens.spacing

    val formatter = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }

    MiuixBottomSheet(visible = visible, onDismiss = onDismiss) {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .heightIn(max = 600.dp)
                    .padding(horizontal = spacing.lg, vertical = spacing.md),
        ) {
            MiuixText(
                text = "DNS 观测（${observations.size}）", style = MiuixTheme.typography.titleSmall)

            Spacer(Modifier.height(spacing.xs))

            MiuixText(
                text = "来自真实 DNS 响应报文，用于把 IP 还原成域名。",
                style = MiuixTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )

            Spacer(Modifier.height(spacing.sm))

            if (observations.isEmpty()) {

                MiuixText(text = "暂无记录", style = MiuixTheme.typography.bodySmall)
            } else {

                LazyColumn(modifier = Modifier.heightIn(max = 460.dp)) {
                    items(observations) { item ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Column(Modifier.weight(1f)) {
                                MiuixText(
                                    text = item.name,
                                    style =
                                        MiuixTheme.typography.bodyMedium.copy(
                                            fontFamily = FontFamily.Monospace),
                                    maxLines = 1,
                                )

                                MiuixText(
                                    text =
                                        "${item.ip ?: "-"} · ${item.qType ?: "-"} · ${item.fromApp ?: "未知应用"}",
                                    style = MiuixTheme.typography.labelSmall,
                                    color = colors.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }

                            MiuixText(
                                text = formatter.format(Date(item.at)),
                                style = MiuixTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
