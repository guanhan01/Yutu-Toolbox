package com.mcp.toolbox.ui.linux

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mcp.toolbox.R
import com.mcp.toolbox.core.design.component.MiuixButton
import com.mcp.toolbox.core.design.component.MiuixIconButton
import com.mcp.toolbox.core.design.component.MiuixSectionCard
import com.mcp.toolbox.core.design.component.MiuixTag
import com.mcp.toolbox.core.design.component.MiuixText
import com.mcp.toolbox.core.design.theme.MiuixTheme
import kotlinx.coroutines.launch

/** 终端里的一行记录。 */
private data class TerminalLine(
    val kind: Kind,
    val text: String,
) {
    enum class Kind { COMMAND, STDOUT, STDERR, META }
}

/**
 * Linux 终端。
 *
 * 非交互式执行：一条命令跑完取回全部输出。真正的 PTY 交互（vim、top 之类）
 * 需要 native 支持，这里先保证命令可用、输出可读。
 */
@Composable
fun LinuxTerminalScreen(
    distro: LinuxDistro,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val lines = remember { mutableStateListOf<TerminalLine>() }
    var input by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val ready = remember { LinuxRuntime.isReady(context, distro) }

    LaunchedEffect(Unit) {
        if (!ready) {
            lines += TerminalLine(
                TerminalLine.Kind.META,
                "环境尚未就绪：请先在上一页完成下载安装。",
            )
        } else {
            lines += TerminalLine(TerminalLine.Kind.META, "已连接 ${distro.title} · PRoot")
            lines += TerminalLine(TerminalLine.Kind.META, "输入命令后回车执行（非交互模式）")
        }
    }

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
    }

    fun run(command: String) {
        val cmd = command.trim()
        if (cmd.isEmpty() || running) return
        input = ""
        lines += TerminalLine(TerminalLine.Kind.COMMAND, cmd)
        running = true
        scope.launch {
            val out = LinuxRuntime.exec(context, distro, cmd)
            if (out.stdout.isNotBlank()) {
                out.stdout.trimEnd().split('\n').forEach {
                    lines += TerminalLine(TerminalLine.Kind.STDOUT, it)
                }
            }
            if (out.stderr.isNotBlank()) {
                out.stderr.trimEnd().split('\n').forEach {
                    lines += TerminalLine(TerminalLine.Kind.STDERR, it)
                }
            }
            lines += TerminalLine(
                TerminalLine.Kind.META,
                "退出码 ${out.exitCode} · ${out.elapsedMs} ms",
            )
            running = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        // 常用命令
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = spacing.pageHorizontal, vertical = spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            listOf(
                "uname -a" to "uname -a",
                "cat /etc/os-release" to "cat /etc/os-release",
                "df -h" to "df -h",
                "free -m" to "free -m",
                "ls /sdcard" to "ls /sdcard | head",
            ).forEach { (label, cmd) ->
                MiuixTag(
                    text = label,
                    modifier = Modifier.clickable { run(cmd) },
                )
            }
        }

        // 输出区
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = spacing.pageHorizontal),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(MiuixTheme.radius.md))
                    .background(colors.surfaceContainerLow)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(lines) { line ->
                    val color = when (line.kind) {
                        TerminalLine.Kind.COMMAND -> colors.primary
                        TerminalLine.Kind.STDERR -> colors.error
                        TerminalLine.Kind.META -> colors.onSurfaceVariant
                        TerminalLine.Kind.STDOUT -> colors.onSurface
                    }
                    val prefix = if (line.kind == TerminalLine.Kind.COMMAND) "$ " else ""
                    MiuixText(
                        text = prefix + line.text,
                        style = MiuixTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        color = color,
                    )
                }
                if (running) {
                    item {
                        MiuixText(
                            text = "…",
                            style = MiuixTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // 输入区
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(
                    start = spacing.pageHorizontal,
                    end = spacing.pageHorizontal,
                    top = spacing.sm,
                    bottom = spacing.sm,
                ),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(MiuixTheme.radius.lg))
                    .background(colors.surface)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                if (input.isEmpty()) {
                    MiuixText(
                        text = stringResource(R.string.linux_cmd_hint),
                        style = MiuixTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        color = colors.onSurfaceVariant,
                    )
                }
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MiuixTheme.typography.bodySmall.copy(
                        color = colors.onSurface,
                        fontFamily = FontFamily.Monospace,
                    ),
                    cursorBrush = SolidColor(colors.primary),
                    maxLines = 4,
                )
            }
            MiuixIconButton(
                icon = Icons.Outlined.ArrowUpward,
                contentDescription = stringResource(R.string.linux_cmd_run),
                onClick = { run(input) },
                filled = input.isNotBlank() && !running,
                enabled = input.isNotBlank() && !running,
                buttonSize = 40.dp,
                iconSize = 20.dp,
            )
        }
    }
}
