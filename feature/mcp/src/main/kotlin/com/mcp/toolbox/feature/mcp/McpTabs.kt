package com.mcp.toolbox.feature.mcp

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mcp.toolbox.core.design.component.MiuixButton
import com.mcp.toolbox.core.design.component.MiuixCard
import com.mcp.toolbox.core.design.component.MiuixFilterChip
import com.mcp.toolbox.core.design.component.MiuixButtonSize
import com.mcp.toolbox.core.design.component.MiuixButtonVariant
import com.mcp.toolbox.core.design.component.MiuixDivider
import com.mcp.toolbox.core.design.component.MiuixEmptyState
import com.mcp.toolbox.core.design.component.MiuixListItem
import com.mcp.toolbox.core.design.component.MiuixSearchField
import com.mcp.toolbox.core.design.component.MiuixSectionCard
import com.mcp.toolbox.core.design.component.MiuixSuperSwitch
import com.mcp.toolbox.core.design.component.MiuixTag
import com.mcp.toolbox.core.design.component.MiuixText
import com.mcp.toolbox.core.design.theme.MiuixColors
import com.mcp.toolbox.core.design.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

internal fun stateColor(colors: MiuixColors, state: McpConnectionState) = when (state) {
    McpConnectionState.READY -> colors.success
    McpConnectionState.CONNECTING -> colors.warning
    McpConnectionState.ERROR -> colors.error
    McpConnectionState.DISCONNECTED -> colors.onSurfaceVariant
}

internal fun formatAt(at: Long): String = timeFormat.format(Date(at))

@Composable
internal fun ServersTab(
    servers: List<McpServerConfig>,
    states: Map<String, McpServerState>,
    toolsMap: Map<String, List<McpToolInfo>>,
    busyServer: String?,
    aiEnabledIds: Set<String>,
    onToggleAi: (McpServerConfig, Boolean) -> Unit,
    onConnect: (McpServerConfig) -> Unit,
    onEdit: (McpServerConfig) -> Unit,
    onDelete: (McpServerConfig) -> Unit,
    onAdd: () -> Unit,
    onCopyText: (String) -> Unit,
    onOpenArtifacts: () -> Unit,
    artifactEnabled: Boolean,
    onMessage: (String) -> Unit,
) {
    val spacing = MiuixTheme.dimens.spacing

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = spacing.pageHorizontal,
            end = spacing.pageHorizontal,
            top = spacing.xs,
            bottom = spacing.xxl,
        ),
        verticalArrangement = Arrangement.spacedBy(spacing.groupGap),
    ) {
        item {
            ServerSectionHeader(count = servers.size, toolCount = toolsMap.values.sumOf { it.size }, onAdd = onAdd)
        }
        if (servers.isEmpty()) {
            item {
                MiuixSectionCard(title = "外部 Server") {
                    MiuixEmptyState(
                        title = "还没有外部 MCP Server",
                        description = "支持 streamable HTTP 与 legacy SSE 两种传输；stdio 需要 root / Shizuku 桥接进程。",
                    )
                }
            }
        }
        items(servers, key = { it.id }) { config ->
            ServerCard(
                config = config,
                state = states[config.id] ?: McpServerState(config.id),
                toolCount = toolsMap[config.id]?.size ?: 0,
                busy = busyServer == config.id,
                aiEnabled = config.id in aiEnabledIds,
                // 没连上就没有可用工具，接入开关先禁用，避免勾了却注入不了
                aiToggleEnabled = states[config.id]?.state == McpConnectionState.READY,
                onToggleAi = { onToggleAi(config, it) },
                onConnect = { onConnect(config) },
                onEdit = { onEdit(config) },
                onDelete = { onDelete(config) },
            )
        }
    }
}

@Composable
private fun ServerSectionHeader(count: Int, toolCount: Int, onAdd: () -> Unit) {
    val colors = MiuixTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            MiuixText(text = "外部 Server（$count）", style = MiuixTheme.typography.titleSmall)
            MiuixText(
                text = "已发现 $toolCount 个可用工具",
                style = MiuixTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
        MiuixButton(
            text = "添加",
            onClick = onAdd,
            size = MiuixButtonSize.SMALL,
            leadingIcon = Icons.Outlined.Add,
        )
    }
}

@Composable
private fun ServerCard(
    config: McpServerConfig,
    state: McpServerState,
    toolCount: Int,
    busy: Boolean,
    aiEnabled: Boolean,
    aiToggleEnabled: Boolean,
    onToggleAi: (Boolean) -> Unit,
    onConnect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    val color = stateColor(colors, state.state)
    MiuixSectionCard(
        title = config.name,
        subtitle = "${config.transport.label} · ${config.url.ifBlank { config.command.ifBlank { "未配置地址" } }}",
        contentPadding = PaddingValues(0.dp),
    ) {
        Column(Modifier.padding(spacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MiuixTag(text = state.state.label, color = color)
                Spacer(Modifier.width(spacing.sm))
                if (state.latencyMs > 0) {
                    MiuixTag(text = "${state.latencyMs}ms")
                }
                Spacer(Modifier.width(spacing.sm))
                MiuixTag(text = "$toolCount 工具")
                if (config.token.isNotBlank()) {
                    Spacer(Modifier.width(spacing.sm))
                    MiuixTag(text = "token")
                }
            }
            if (state.message.isNotBlank()) {
                Spacer(Modifier.height(spacing.sm))
                MiuixText(
                    text = if (state.state == McpConnectionState.READY) {
                        "${state.serverName} ${state.serverVersion} · 协议 ${state.protocolVersion}"
                    } else {
                        state.message
                    },
                    style = MiuixTheme.typography.bodySmall,
                    color = if (state.state == McpConnectionState.ERROR) colors.error else colors.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(spacing.md))
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                MiuixButton(
                    text = if (state.state == McpConnectionState.READY) "重新连接" else "连接",
                    onClick = onConnect,
                    size = MiuixButtonSize.SMALL,
                    loading = busy,
                    leadingIcon = if (state.state == McpConnectionState.READY) Icons.Outlined.Refresh else Icons.Outlined.PlayArrow,
                )
                MiuixButton(
                    text = "编辑",
                    onClick = onEdit,
                    size = MiuixButtonSize.SMALL,
                    variant = MiuixButtonVariant.OUTLINED,
                    leadingIcon = Icons.Outlined.Edit,
                )
                MiuixButton(
                    text = "删除",
                    onClick = onDelete,
                    size = MiuixButtonSize.SMALL,
                    variant = MiuixButtonVariant.TEXT,
                    leadingIcon = Icons.Outlined.Delete,
                )
            }
            Spacer(Modifier.height(spacing.sm))
            MiuixDivider()
            MiuixSuperSwitch(
                title = "接入 AI 对话",
                subtitle = when {
                    !aiToggleEnabled -> "需要先连接该 Server 才能接入"
                    aiEnabled -> "该 Server 的 $toolCount 个工具已暴露给模型，调用会在这里产生记录"
                    else -> "开启后模型可调用该 Server 的工具（默认关闭）"
                },
                checked = aiEnabled,
                onCheckedChange = onToggleAi,
                enabled = aiToggleEnabled,
            )
        }
    }
}

@Composable
internal fun ToolsTab(
    servers: List<McpServerConfig>,
    toolsMap: Map<String, List<McpToolInfo>>,
    states: Map<String, McpServerState>,
    query: String,
    onQueryChange: (String) -> Unit,
    selectedServerId: String?,
    onSelectServer: (String) -> Unit,
    onConnect: (McpServerConfig) -> Unit,
    onInvoke: (McpServerConfig, McpToolInfo) -> Unit,
) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    val activeServer = servers.firstOrNull { it.id == selectedServerId }
        ?: servers.firstOrNull { states[it.id]?.state == McpConnectionState.READY }
    val tools = (activeServer?.let { toolsMap[it.id] } ?: toolsMap.values.flatten())
        .filter {
            query.isBlank() ||
                it.name.contains(query, ignoreCase = true) ||
                it.title.contains(query, ignoreCase = true) ||
                it.description.contains(query, ignoreCase = true)
        }
        .distinctBy { it.name }

    Column(Modifier.fillMaxSize().padding(horizontal = spacing.pageHorizontal)) {
        MiuixSearchField(value = query, onValueChange = onQueryChange, placeholder = "搜索工具名 / 说明")
        if (servers.size > 1) {
            Spacer(Modifier.height(spacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                servers.take(3).forEach { server ->
                    MiuixFilterChip(
                        label = server.name.take(10),
                        selected = server.id == activeServer?.id,
                        onClick = { onSelectServer(server.id) },
                    )
                }
            }
            MiuixText(
                text = "当前 Server：${activeServer?.name ?: "未选择"}",
                style = MiuixTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(spacing.sm))
        if (tools.isEmpty()) {
            MiuixEmptyState(
                title = "没有可用工具",
                description = if (activeServer == null) {
                    "先在「服务器」页连接一个 Server，或启动内置 Server 后连接「本机内置 Server」。"
                } else {
                    "该 Server 未返回工具，或搜索无匹配。"
                },
                action = activeServer?.takeIf { states[it.id]?.state != McpConnectionState.READY }?.let { server ->
                    { MiuixButton(text = "立即连接", onClick = { onConnect(server) }) }
                },
            )
            return@Column
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            items(tools, key = { it.name + (activeServer?.id ?: "") }) { tool ->
                MiuixCard(onClick = { activeServer?.let { onInvoke(it, tool) } }) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            MiuixText(text = tool.displayName, style = MiuixTheme.typography.titleSmall)
                            Spacer(Modifier.width(spacing.sm))
                            if (tool.isDangerous) MiuixTag(text = "需确认", color = colors.error)
                            else if (tool.readOnly) MiuixTag(text = "只读", color = colors.success)
                            Spacer(Modifier.weight(1f))
                            MiuixText(
                                text = "${tool.paramNames.size} 参数",
                                style = MiuixTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                        MiuixText(text = tool.name, style = MiuixTheme.typography.labelSmall, color = colors.primary)
                        if (tool.description.isNotBlank()) {
                            Spacer(Modifier.height(2.dp))
                            MiuixText(
                                text = tool.description,
                                style = MiuixTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                                maxLines = 3,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun LogsTab(
    calls: List<McpCallRecord>,
    query: String,
    onQueryChange: (String) -> Unit,
    onSelect: (McpCallRecord) -> Unit,
) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    val filtered = calls.filter {
        query.isBlank() ||
            it.toolName.contains(query, ignoreCase = true) ||
            it.serverName.contains(query, ignoreCase = true) ||
            it.resultText.contains(query, ignoreCase = true)
    }
    Column(Modifier.fillMaxSize().padding(horizontal = spacing.pageHorizontal)) {
        MiuixSearchField(value = query, onValueChange = onQueryChange, placeholder = "搜索工具 / Server / 结果")
        Spacer(Modifier.height(spacing.sm))
        if (filtered.isEmpty()) {
            MiuixEmptyState(
                title = "暂无调用记录",
                description = "在「工具」页调用一次工具，这里会记录入参、结果、耗时与产物路径。",
            )
            return@Column
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            items(filtered, key = { it.id + it.startedAt }) { record ->
                MiuixCard(onClick = { onSelect(record) }) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            MiuixTag(
                                text = when {
                                    record.ok -> "成功"
                                    record.isError -> "工具报错"
                                    else -> "失败"
                                },
                                color = when {
                                    record.ok -> colors.success
                                    record.isError -> colors.warning
                                    else -> colors.error
                                },
                            )
                            Spacer(Modifier.width(spacing.sm))
                            MiuixText(text = record.toolName, style = MiuixTheme.typography.titleSmall)
                            Spacer(Modifier.weight(1f))
                            MiuixText(
                                text = "${record.elapsedMs}ms",
                                style = MiuixTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                        MiuixText(
                            text = "${record.serverName} · ${formatAt(record.startedAt)} · ${record.bytes}B" +
                                if (record.artifactDir != null) " · 已落盘 ${record.artifactFiles} 文件" else "",
                            style = MiuixTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(2.dp))
                        MiuixText(
                            text = record.resultText.lineSequence().firstOrNull().orEmpty().take(120),
                            style = MiuixTheme.typography.bodySmall,
                            maxLines = 2,
                        )
                    }
                }
            }
        }
    }
}
