package com.mcp.toolbox.feature.home.agent.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mcp.toolbox.core.design.component.MiuixButton
import com.mcp.toolbox.core.design.component.MiuixIconRes
import com.mcp.toolbox.core.design.component.MiuixText
import org.json.JSONObject
import com.mcp.toolbox.core.design.component.miuixClickable
import com.mcp.toolbox.core.design.component.miuixTouchTarget
import com.mcp.toolbox.core.design.component.rememberMiuixPressState
import com.mcp.toolbox.core.design.icon.AgentIcons
import com.mcp.toolbox.core.design.shape.SquircleRadii
import com.mcp.toolbox.core.design.shape.sqBorder
import com.mcp.toolbox.core.design.shape.sqSurface
import com.mcp.toolbox.core.design.theme.MiuixTheme
import com.mcp.toolbox.core.design.token.rememberAgentMotion
import com.mcp.toolbox.feature.home.agent.AgentText
import com.mcp.toolbox.feature.home.agent.ToolCallSegment
import com.mcp.toolbox.feature.home.agent.ToolStatus
import com.mcp.toolbox.feature.home.agent.formatToolDuration

private val HeaderHeight = 44.dp
private val IconTile = 32.dp

/**
 * 工具调用卡（Spec 第 7 节）。
 *
 * ## 默认折叠
 * 头部 44dp 是完整信息面（图标 / 名字 / 状态 / 耗时 / 箭头），详情体默认隐藏。
 *
 * ## 结果分层（Spec 第 7.2 节，这是「不把一条回答变成上千 dp 滚动噩梦」的关键）
 * - L1 摘要：头部下方一句话，**永远展示**
 * - L2 结构化：来源 chips（≤5 条，超出「查看全部 N 条」）
 * - L3 参数 argsJson：**默认折叠**
 * - L4 原始输出：**默认折叠**，超 500 字给独立出口
 *
 * ## 六态
 * Pending / Running / Success / Failed / Timeout / Denied，各自有明确视觉与文案，
 * 不留空分支。其中 **Timeout 是前端兜底**：绝不允许无限转圈。
 */
@Composable
fun ToolCard(
    segment: ToolCallSegment,
    onToggleDetail: () -> Unit,
    onApprove: () -> Unit = {},
    onDeny: () -> Unit = {},
    /**
     * 是否显示「重试」。由调用方判定，默认 false。
     *
     * 只在「这一轮是被异常打断的，且这张卡是最后一个段」时才给 —— 中途某张卡
     * 失败但后面还有内容（模型已经答下去或又调了别的工具），此时重试这一个
     * 工具并不会改变已经写好的后文，给按钮只会让人误以为有用。
     */
    canRetry: Boolean = false,
    onRetry: () -> Unit = {},
    onOpenRaw: () -> Unit = {},
    onOpenSources: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colors
    val motion = rememberAgentMotion()

    // 状态切换时整卡底色做 240ms 过渡（running = 3% primary 底，done = 透明）
    val container by androidx.compose.animation.animateColorAsState(
        targetValue = if (segment.isRunning) colors.primary.copy(alpha = 0.03f) else Color.Transparent,
        animationSpec = tween(motion.statusColor(), easing = EaseOut),
        label = "tool-container",
    )

    // 审批卡显式区分：危险操作用 destructive 语义色描边
    val borderColor = when {
        segment.awaitingApproval -> colors.error.copy(alpha = 0.5f)
        segment.status == ToolStatus.Failed || segment.status == ToolStatus.Timeout ->
            colors.warning.copy(alpha = 0.4f)

        else -> colors.outlineVariant
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .sqSurface(container, SquircleRadii.card)
            .sqBorder(1.dp, borderColor, SquircleRadii.card)
            .padding(vertical = 2.dp),
    ) {
        ToolHeader(
            segment = segment,
            onToggleDetail = onToggleDetail,
        )

        // L1 摘要：一句话结果，永远展示（Running 时显示动词文案）
        ToolSummaryLine(segment)

        AnimatedVisibility(
            visible = segment.isDetailExpanded || segment.awaitingApproval,
            enter = expandVertically(motion.cardEnter(), expandFrom = Alignment.Top) +
                fadeIn(animationSpec = motion.fade(200)),
            exit = shrinkVertically(motion.cardEnter(), shrinkTowards = Alignment.Top) +
                fadeOut(animationSpec = motion.fade(160)),
        ) {
            ToolDetail(
                segment = segment,
                canRetry = canRetry,
                onApprove = onApprove,
                onDeny = onDeny,
                onRetry = onRetry,
                onOpenRaw = onOpenRaw,
                onOpenSources = onOpenSources,
            )
        }
    }
}

@Composable
private fun ToolHeader(segment: ToolCallSegment, onToggleDetail: () -> Unit) {
    val colors = MiuixTheme.colors
    val motion = rememberAgentMotion()
    val press = rememberMiuixPressState()
    val chevron by animateFloatAsState(
        targetValue = if (segment.isDetailExpanded) 180f else 0f,
        animationSpec = motion.cardEnter(),
        label = "tool-chevron",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(HeaderHeight)
            .miuixTouchTarget(HeaderHeight)
            .miuixClickable(press, !segment.awaitingApproval, onClick = onToggleDetail)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 32dp Squircle 图标 tile，底色按工具类型分色，饱和度受控（≤45%）
        Box(
            modifier = Modifier
                .size(IconTile)
                .sqSurface(toolTileColor(segment.toolName), SquircleRadii.iconTile),
            contentAlignment = Alignment.Center,
        ) {
            MiuixIconRes(
                iconRes = segment.iconRes,
                contentDescription = null,
                tint = toolAccent(segment.toolName),
                size = 18.dp,
            )
        }

        Column(Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                MiuixText(
                    text = segment.displayName,
                    style = MiuixTheme.typography.titleSmall,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (segment.seq > 0) {
                    // 序号 badge：便于和正文里的引用对照
                    Box(
                        Modifier
                            .sqSurface(colors.surfaceContainerHighest, SquircleRadii.bubbleTail)
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    ) {
                        MiuixText(
                            text = "#${segment.seq}",
                            style = MiuixTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        ToolStatusIndicator(segment)

        MiuixIconRes(
            iconRes = AgentIcons.ic_chevron_down,
            contentDescription = null,
            tint = colors.onSurfaceVariant,
            size = 16.dp,
            modifier = Modifier.rotate(chevron),
        )
    }
}

/**
 * 状态区（Spec 第 7.1 节）。
 *
 * - Running：环形 indeterminate（1.2 圈/s）+ 动词文案 + 计时
 * - Success：对勾 pop-in（scale 0.6→1.05→1，240ms）+ 耗时
 * - Failed / Timeout：警示图标 + 明确文案（**不抖动**，Miuix 不做错误抖动）
 * - Denied：拒绝图标
 */
@Composable
private fun ToolStatusIndicator(segment: ToolCallSegment) {
    val colors = MiuixTheme.colors
    val motion = rememberAgentMotion()
    val status = segment.status

    AnimatedContent(
        targetState = status,
        transitionSpec = {
            fadeIn(animationSpec = motion.fade<Float>(200)) togetherWith
                fadeOut(animationSpec = motion.fade<Float>(160))
        },
        label = "tool-status",
    ) { current ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            when (current) {
                ToolStatus.Pending, ToolStatus.Running -> SpinningRing()
                ToolStatus.Success -> PopInCheck()
                ToolStatus.Failed -> MiuixIconRes(
                    iconRes = AgentIcons.ic_error,
                    contentDescription = null,
                    tint = colors.warning,
                    size = 14.dp,
                )

                ToolStatus.Timeout -> MiuixIconRes(
                    iconRes = AgentIcons.ic_timeout,
                    contentDescription = null,
                    tint = colors.warning,
                    size = 14.dp,
                )

                // 用户主动停止：用「停止」图标 + 中性色，而不是报错语义
                ToolStatus.Interrupted -> MiuixIconRes(
                    iconRes = AgentIcons.ic_agent_stop,
                    contentDescription = null,
                    tint = colors.onSurfaceVariant,
                    size = 14.dp,
                )

                ToolStatus.Denied -> MiuixIconRes(
                    iconRes = AgentIcons.ic_blocked,
                    contentDescription = null,
                    tint = colors.onSurfaceVariant,
                    size = 14.dp,
                )
            }

            val label = when (current) {
                ToolStatus.Pending -> if (segment.awaitingApproval) AgentText.TOOL_NEEDS_APPROVAL else "等待中"
                ToolStatus.Running -> com.mcp.toolbox.feature.home.agent.ToolVisuals.runningLabel(segment.toolName)
                ToolStatus.Success -> formatToolDuration(segment.durationMs)
                ToolStatus.Failed -> AgentText.TOOL_FAILED
                ToolStatus.Timeout -> AgentText.TOOL_TIMEOUT
                ToolStatus.Interrupted -> AgentText.TOOL_INTERRUPTED
                ToolStatus.Denied -> AgentText.TOOL_DENIED
            }
            if (label.isNotEmpty()) {
                MiuixText(
                    text = label,
                    style = MiuixTheme.typography.labelMedium,
                    color = if (current == ToolStatus.Success) colors.onSurfaceVariant else colors.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 环形 indeterminate 进度（1.2 圈/s）。 */
@Composable
private fun SpinningRing() {
    val colors = MiuixTheme.colors
    val period = (1000 / 1.2f).toInt()
    val transition = rememberInfiniteTransition(label = "tool-spin")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = period, easing = androidx.compose.animation.core.LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "spin",
    )
    Box(
        Modifier
            .size(14.dp)
            .rotate(angle)
            .border(1.5.dp, colors.primary, CircleShape)
            .clip(CircleShape)
            .background(colors.primary.copy(alpha = 0.12f)),
    )
}

/** 对勾 pop-in：scale 0.6→1.05→1，240ms（Spec 第 7.1 节）。 */
@Composable
private fun PopInCheck() {
    val colors = MiuixTheme.colors
    val motion = rememberAgentMotion()
    val scale = remember { androidx.compose.animation.core.Animatable(0.6f) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        scale.animateTo(1.05f, tween(motion.ms(160), easing = EaseOut))
        scale.animateTo(1f, tween(motion.ms(80), easing = EaseOut))
    }
    MiuixIconRes(
        iconRes = AgentIcons.ic_success,
        contentDescription = null,
        tint = colors.success,
        size = 14.dp,
        modifier = Modifier.scale(scale.value),
    )
}

/**
 * L1 摘要行。Running 时显示动词文案，完成时显示结果摘要。
 * 这是「不全部铺开」策略的第一层，永远可见。
 */
@Composable
private fun ToolSummaryLine(segment: ToolCallSegment) {
    val colors = MiuixTheme.colors
    val raw = segment.result?.rawText.orEmpty()
    val expanded = segment.isDetailExpanded || segment.awaitingApproval

    /**
     * 展开后只留计数。
     *
     * 展开时「结果」块已经把同样的内容完整铺在下面，摘要再念一遍首行就是重复
     * （用户实测反馈）。但**折叠时不能省**——那时摘要是卡片上唯一的信息，
     * 收成「共 6 行」等于什么都不说。
     *
     * 所以按展开态取不同的文案：去重只发生在重复确实存在的时候。
     */
    val summary = when {
        segment.awaitingApproval -> segment.risk.orEmpty()
        segment.status == ToolStatus.Timeout -> AgentText.TOOL_TIMEOUT_HINT
        segment.status == ToolStatus.Interrupted -> AgentText.TOOL_INTERRUPTED_HINT
        segment.status == ToolStatus.Denied -> AgentText.TOOL_DENIED_HINT
        segment.status == ToolStatus.Failed ->
            segment.result?.summary ?: AgentText.TOOL_FAILED

        // 展开且结果有正文：正文就在下面，这里只报行数
        expanded && raw.isNotBlank() -> {
            val lines = raw.lines().count { it.isNotBlank() }
            "共 $lines 行"
        }

        else -> segment.result?.summary.orEmpty()
    }
    if (summary.isEmpty()) return

    MiuixText(
        text = summary,
        style = MiuixTheme.typography.bodyMedium,
        color = colors.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 54.dp, end = 12.dp, bottom = 8.dp),
    )
}

/**
 * 参数是否有实际内容。
 *
 * 只认「真正的空对象」：`{}`、`null`、纯空白。像 `{"path":"/sdcard"}` 这类
 * 一眼能读到信息的参数照常显示。
 */
private fun String.hasMeaningfulArgs(): Boolean {
    val t = trim()
    if (t.isEmpty() || t == "{}" || t == "null") return false
    return true
}

/**
 * 从工具参数里取出要执行的命令。
 *
 * 命令类工具（`shell.exec` / `linux.exec` / `linux.shell`）的参数形状统一是
 * `{"command": "..."}`，所以直接读这个键即可。返回 null 表示这不是命令类工具，
 * 界面据此决定要不要显示命令栏。
 */
private fun commandOf(argsJson: String?): String? {
    val raw = argsJson?.takeIf { it.hasMeaningfulArgs() } ?: return null
    return runCatching { JSONObject(raw).optString("command") }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?.trim()
}

/**
 * 终端样式的命令栏。
 *
 * 视觉刻意做成「终端窗口」而不是又一个信息块：深色底 + 等宽字 + 行首 `$`，
 * 一眼能认出「这是要执行的命令」，与下面的「结果」「参数」两块拉开区分。
 * 没有引入依赖，纯 Compose 画。
 */
@Composable
private fun ShellCommandBar(command: String) {
    val colors = MiuixTheme.colors
    // 白底 + 深字（浅色主题下 surface 就是 0xFFFFFFFF，onSurface 是近黑）。
    //
    // 必须补一圈描边：页面底色本身也接近白，纯白块会"糊"在背景里分不出来。
    val bg = colors.surface
    val fg = colors.onSurface

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .sqSurface(bg, SquircleRadii.inner)
            .sqBorder(1.dp, colors.outlineVariant, SquircleRadii.inner)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MiuixText(
            text = "$",
            style = MiuixTheme.typography.code,
            color = colors.onSurfaceVariant,
        )
        MiuixText(
            text = command,
            style = MiuixTheme.typography.code,
            color = fg,
            maxLines = 6,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 详情体（L2 / L3 / L4）+ 审批按钮。
 */
@Composable
private fun ToolDetail(
    segment: ToolCallSegment,
    canRetry: Boolean,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    onRetry: () -> Unit,
    onOpenRaw: () -> Unit,
    onOpenSources: () -> Unit,
) {
    val colors = MiuixTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 54.dp, end = 12.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 审批卡优先：这是唯一不参与自动收起的段（Spec 第 7.5 节）
        if (segment.awaitingApproval) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MiuixButton(
                    text = AgentText.TOOL_ALLOW,
                    onClick = onApprove,
                )
                MiuixButton(
                    text = AgentText.TOOL_DENY,
                    onClick = onDeny,
                )
            }
            return@Column
        }

        // L1 结果正文：展开后才出现，且**只给预览**。
        //
        // 早先这里完全没有渲染 `rawText`——短结果（≤500 字）既不进详情体、
        // 也不满足「查看原始输出」的门槛，于是点开箭头后什么都没有，
        // 用户看到的就是「工具结果无法展开」（实测反馈）。
        //
        // 按用户要求「展开后不必展示所有内容」：只放一段预览，
        // 完整输出仍然走「查看原始输出」的独立面板。
        // 命令栏：这一类工具真正执行的就是一条命令，把它单独放出来，
        // 而不是埋在下面「参数」那块 JSON 里（截图里的 {"command": "echo ..."}）。
        //
        // 只在「参数里确实有非空 command」时出现，所以普通工具（file.* / apk.* /
        // web.fetch 等）不受影响，仍只显示原有各块。
        val command = commandOf(segment.argsJson)
        command?.let { ShellCommandBar(command = it) }

        segment.result?.rawText?.takeIf { it.isNotBlank() }?.let { raw ->
            LabeledBlock(
                label = AgentText.TOOL_RESULT,
                body = if (raw.length > RESULT_PREVIEW_CHARS) {
                    raw.take(RESULT_PREVIEW_CHARS) + "…"
                } else {
                    raw
                },
                mono = true,
                /**
                 * 点「结果」块本身打开完整输出。
                 *
                 * 以前是在卡片底部另放一行「查看原始输出」按钮，而且**只在
                 * 结果超过 500 字时**才出现——短结果既没有完整展示的入口，
                 * 也没法复制原文（用户实测反馈「查看原始输出点结果 UI 就好了」）。
                 *
                 * 改成整块可点后：每个工具的结果都有统一入口，
                 * 不再依赖长度门槛，也少一行按钮。
                 */
                onClick = onOpenRaw,
            )
        }

        // L3 参数：展开后按等宽小字展示。
        //
        // 空参数不渲染：`{}` / `null` / 纯空白占一整块，却什么信息都没有，
        // 只会把「结果」挤下去（用户实测反馈）。
        //
        // 命令类工具**整块不渲染**：它的参数就是那条命令，上方命令栏已经用可读的
        // 形式表达了，再贴一遍原始 JSON（含未转义的 \n、\"）只是重复噪音。
        if (command == null) {
            segment.argsJson?.takeIf { it.hasMeaningfulArgs() }?.let { args ->
                LabeledBlock(label = AgentText.TOOL_ARGS, body = args, mono = true)
            }
        }

        // L2 来源 chips（网页搜索）
        segment.result?.sources?.takeIf { it.isNotEmpty() }?.let { sources ->
            SourceChips(sources = sources, onOpenAll = onOpenSources)
        }

        // 结构化块（表格 / 代码 / 列表）
        segment.result?.structuredBlocks?.takeIf { it.isNotEmpty() }?.forEach { block ->
            when (block) {
                is com.mcp.toolbox.feature.home.agent.ResultBlock.Code ->
                    LabeledBlock(label = block.language.ifBlank { "code" }, body = block.code, mono = true)

                is com.mcp.toolbox.feature.home.agent.ResultBlock.Bullets ->
                    LabeledBlock(label = null, body = block.items.joinToString("\n") { "• $it" }, mono = false)

                is com.mcp.toolbox.feature.home.agent.ResultBlock.Table -> Unit
                is com.mcp.toolbox.feature.home.agent.ResultBlock.Image -> Unit
            }
        }

        // Failed / Timeout 的重试与忽略（重试还要满足 canRetry 的上下文条件）
        if (canRetry && (segment.status == ToolStatus.Failed || segment.status == ToolStatus.Timeout)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextAction(text = AgentText.TOOL_RETRY, iconRes = AgentIcons.ic_retry, onClick = onRetry)
                TextAction(text = AgentText.TOOL_IGNORE, iconRes = AgentIcons.ic_close, onClick = {})
            }
        }
    }
}

/** 小标题 + 等宽正文的折叠块。 */
@Composable
private fun LabeledBlock(
    label: String?,
    body: String,
    mono: Boolean,
    /**
     * 点击整块的回调。非空时整块可点，并补一句「点击查看完整内容」的提示——
     * 否则用户不知道这里能点。
     */
    onClick: (() -> Unit)? = null,
) {
    val colors = MiuixTheme.colors
    val press = rememberMiuixPressState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .sqSurface(colors.surfaceContainerHigh, SquircleRadii.inner)
            .then(
                if (onClick != null) {
                    Modifier.miuixClickable(press, true, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (label != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                MiuixText(
                    text = label,
                    style = MiuixTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (onClick != null) {
                    MiuixIconRes(
                        iconRes = AgentIcons.ic_expand_panel,
                        contentDescription = null,
                        tint = colors.primary,
                        size = 14.dp,
                    )
                }
            }
        }
        MiuixText(
            text = body,
            style = if (mono) {
                MiuixTheme.typography.code
            } else {
                MiuixTheme.typography.bodySmall
            },
            color = colors.onSurface,
            maxLines = 8,
            overflow = TextOverflow.Ellipsis,
        )
        if (onClick != null) {
            MiuixText(
                text = "点击查看完整内容",
                style = MiuixTheme.typography.labelSmall,
                color = colors.primary,
            )
        }
    }
}

/** 来源 chips：favicon + 域名，最多 5 条，超出「查看全部 N 条」（Spec 第 7.4 节）。 */
@Composable
private fun SourceChips(
    sources: List<com.mcp.toolbox.feature.home.agent.WebSource>,
    onOpenAll: () -> Unit,
) {
    val colors = MiuixTheme.colors
    val shown = sources.take(SOURCE_CHIP_LIMIT)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        MiuixText(
            text = AgentText.SNIPPET_TITLE,
            style = MiuixTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            shown.forEach { source ->
                Box(
                    Modifier
                        .sqSurface(colors.surfaceContainerHighest, SquircleRadii.bubbleTail)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        MiuixIconRes(
                            iconRes = AgentIcons.ic_globe,
                            contentDescription = null,
                            tint = colors.onSurfaceVariant,
                            size = 12.dp,
                        )
                        MiuixText(
                            text = source.domain.ifBlank { AgentText.SNIPPET_DOMAIN_FALLBACK },
                            style = MiuixTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        if (sources.size > SOURCE_CHIP_LIMIT) {
            TextAction(
                text = AgentText.SNIPPET_MORE.format(sources.size),
                iconRes = AgentIcons.ic_chevron_right,
                onClick = onOpenAll,
            )
        }
    }
}

@Composable
private fun TextAction(
    text: String,
    iconRes: Int,
    onClick: () -> Unit,
) {
    val colors = MiuixTheme.colors
    val press = rememberMiuixPressState()
    Row(
        modifier = Modifier
            .miuixTouchTarget(32.dp)
            .miuixClickable(press, true, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        MiuixIconRes(iconRes, null, tint = colors.primary, size = 14.dp)
        MiuixText(
            text = text,
            style = MiuixTheme.typography.labelMedium,
            color = colors.primary,
        )
    }
}

// ---------------------------------------------------------------------------
// 工具配色：按工具类型分色，色相取自 Monet 同族色，饱和度受控
// ---------------------------------------------------------------------------

private const val SOURCE_CHIP_LIMIT = 5

/**
 * 工具结果预览的字符上限。
 *
 * 取 400：够看清「返回了什么」，又不至于把一条回答撑成上千 dp 的滚动噩梦。
 * 超过这个长度时由「查看原始输出」提供完整内容与搜索。
 */
private const val RESULT_PREVIEW_CHARS = 400

private enum class ToolFamily { Search, Code, File, System }

private fun familyOf(toolName: String): ToolFamily = when (toolName.substringBefore('.')) {
    "web", "http", "net" -> ToolFamily.Search
    "shell", "linux", "db", "sql", "dex", "decode", "build", "apk" -> ToolFamily.Code
    "file", "app", "artifact", "skill", "keystore" -> ToolFamily.File
    else -> ToolFamily.System
}

/** tile 底色：8% 主色系，饱和度低，避免大面积高饱和。 */
@Composable
private fun toolTileColor(toolName: String): Color {
    val colors = MiuixTheme.colors
    val accent = toolAccent(toolName)
    return accent.copy(alpha = 0.10f).let { it }.takeIf { true } ?: colors.primaryContainer
}

@Composable
private fun toolAccent(toolName: String): Color {
    val colors = MiuixTheme.colors
    return when (familyOf(toolName)) {
        ToolFamily.Search -> colors.primary
        ToolFamily.Code -> colors.tertiary
        ToolFamily.File -> colors.onSurfaceVariant
        ToolFamily.System -> colors.success
    }
}
