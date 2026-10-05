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
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mcp.toolbox.core.design.component.MiuixIconRes
import com.mcp.toolbox.core.design.component.MiuixText
import com.mcp.toolbox.core.design.component.miuixClickable
import com.mcp.toolbox.core.design.component.miuixTouchTarget
import com.mcp.toolbox.core.design.component.rememberMiuixPressState
import com.mcp.toolbox.core.design.icon.AgentIcons
import com.mcp.toolbox.core.design.shape.SquircleRadii
import com.mcp.toolbox.core.design.shape.sqBorder
import com.mcp.toolbox.core.design.shape.sqSurface
import com.mcp.toolbox.core.design.theme.MiuixTheme
import com.mcp.toolbox.core.design.token.rememberAgentMotion
import com.mcp.toolbox.feature.home.agent.ThinkingSegment

/** 折叠胶囊高度（Spec 第 6.1 节）。 */
private val CapsuleHeight = 40.dp

/** 胶囊里保留的字数上限。 */
private const val SUMMARY_CHARS = 28

/** 超过这个长度就只渲染尾部窗口（Spec 第 6.2 节的超长保护）。 */
private const val LONG_THRESHOLD = 3000
private const val WINDOW_CHARS = 2000

/**
 * 深度思考块（Spec 第 6 节）。
 *
 * ## 独立性
 * 展开态来自 [ThinkingSegment.isExpanded]，点击回调交给上层写回该 Segment 自身。
 * 这里**不持有**任何跨 Segment 的共享动画对象——所以「展开 A 时 B 的动画与流式
 * 完全不受影响」是结构上成立的，不是靠小心编码维持的。
 *
 * ## 默认折叠
 * 折叠时不是一行静态文字，而是一个「活着」的胶囊：呼吸圆点 + 文案轮播 + 计时 + 微光。
 * 它是「过程还在进行」的唯一视觉信号，不能省。
 */
@Composable
fun ThinkingBlock(
    segment: ThinkingSegment,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val running = segment.isRunning
    val motion = rememberAgentMotion()
    Column(modifier = modifier.fillMaxWidth()) {
        ThinkingCapsule(segment = segment, running = running, onToggle = onToggle)
        // AnimatedVisibility 的 enter/exit 不是组合上下文，规格必须在外面先算好
        val expand = motion.cardEnter<androidx.compose.ui.unit.IntSize>()
        val fadeInSpec = motion.fade<Float>(200)
        val fadeOutSpec = motion.fade<Float>(160)
        AnimatedVisibility(
            visible = segment.isExpanded,
            enter = expandVertically(animationSpec = expand, expandFrom = Alignment.Top) +
                fadeIn(animationSpec = fadeInSpec),
            exit = shrinkVertically(animationSpec = expand, shrinkTowards = Alignment.Top) +
                fadeOut(animationSpec = fadeOutSpec),
        ) {
            ThinkingBody(segment)
        }
    }
}

@Composable
private fun ThinkingCapsule(
    segment: ThinkingSegment,
    running: Boolean,
    onToggle: () -> Unit,
) {
    val colors = MiuixTheme.colors
    val motion = rememberAgentMotion()
    val press = rememberMiuixPressState()
    val chevron by animateFloatAsState(
        targetValue = if (segment.isExpanded) 180f else 0f,
        animationSpec = motion.cardEnter(),
        label = "chevron",
    )
    // 宽度随文案平滑变化，而不是跳变（Spec 第 6.1 节）
    val labelWidth by animateDpAsState(
        targetValue = capsuleTextWidth(segment.latestLine),
        animationSpec = motion.capsuleWidth(),
        label = "capsule-width",
    )
    val label = remember(segment.latestLine, segment.title) {
        segment.latestLine.take(SUMMARY_CHARS).ifEmpty { "${segment.title}…" }
    }

    Box(
        modifier = Modifier
            .miuixTouchTarget(CapsuleHeight)
            .semantics {
                contentDescription = if (segment.isExpanded) {
                    "${segment.title}，已展开，双击收起"
                } else {
                    "${segment.title}，已折叠，双击展开"
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box {
            Row(
                modifier = Modifier
                    .height(CapsuleHeight)
                    .miuixClickable(press, true, onClick = onToggle)
                    .sqSurface(colors.surfaceContainerHigh, SquircleRadii.card)
                    .sqBorder(1.dp, colors.outlineVariant, SquircleRadii.card)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ThinkingOrb(running)
                ThinkingLabel(label = label, running = running, width = labelWidth)
                MiuixText(
                    text = formatDuration(segment.elapsedMs),
                    style = MiuixTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                )
                MiuixIconRes(
                    iconRes = AgentIcons.ic_chevron_down,
                    contentDescription = null,
                    tint = colors.onSurfaceVariant,
                    size = 16.dp,
                    modifier = Modifier.rotate(chevron),
                )
            }
            // 微光只在运行中扫过；停止后整棵子树连同无限动画一起被回收
            ShimmerOverlay(running = running, modifier = Modifier.matchParentSize())
        }
    }
}

/**
 * 胶囊中段：文案轮播。
 *
 * 每来一句新的思考摘要，旧文字上滑退出、新文字上滑进入（260ms spring）。
 * 这是「思考在流动」的核心体感（Spec 第 6.1 节）。
 */
@Composable
private fun ThinkingLabel(label: String, running: Boolean, width: Dp) {
    val colors = MiuixTheme.colors
    val motion = rememberAgentMotion()
    val swap = motion.textSwap<androidx.compose.ui.unit.IntOffset>()
    val inFade = motion.fade<Float>(200)
    val outFade = motion.fade<Float>(160)
    AnimatedContent(
        targetState = label,
        transitionSpec = {
            (slideInVertically(animationSpec = swap, initialOffsetY = { it / 2 }) +
                fadeIn(animationSpec = inFade)) togetherWith
                (slideOutVertically(animationSpec = swap, targetOffsetY = { -it / 2 }) +
                    fadeOut(animationSpec = outFade))
        },
        label = "thinking-line",
        modifier = Modifier.width(width),
    ) { line ->
        MiuixText(
            text = line,
            style = MiuixTheme.typography.bodyMedium,
            color = if (running) colors.onSurface else colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 左侧呼吸圆点（Spec 第 6.1 节）：底色（primary 12%）+ 呼吸层（scale 1→1.6、alpha .5→0）
 * + 实心核。停止思考时呼吸层收敛为静态对勾。
 */
@Composable
private fun ThinkingOrb(running: Boolean) {
    val colors = MiuixTheme.colors
    Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(colors.primary.copy(alpha = 0.12f)),
        )
        if (running) {
            BreathingHalo()
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(colors.primary),
            )
        } else {
            MiuixIconRes(
                iconRes = AgentIcons.ic_check,
                contentDescription = null,
                tint = colors.primary,
                size = 10.dp,
            )
        }
    }
}

@Composable
private fun BreathingHalo() {
    val colors = MiuixTheme.colors
    val breathMs = rememberAgentMotion().orbBreath()
    val transition = rememberInfiniteTransition(label = "orb")
    val fraction by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = breathMs, easing = EaseOut),
            repeatMode = RepeatMode.Restart,
        ),
        label = "orb-breath",
    )
    Box(
        Modifier
            .size(14.dp)
            .scale(1f + 0.6f * fraction)
            .clip(CircleShape)
            .background(colors.primary.copy(alpha = 0.5f * (1f - fraction))),
    )
}

/**
 * 展开体（Spec 第 6.2 节）。
 *
 * - 正文 14sp / 60% 透明
 * - 超长保护：> 3000 字时只渲染**尾部 2000 字窗口**（真实踩坑点：全量塞进渲染树会卡）
 *
 * 左侧那条 2dp 竖线**已去掉**：用户明确指出它是多余的——
 * 过程组本身已经有一条连接线把各项串起来，思考正文里再来一条
 * 就成了「线里套线」，读起来只是噪声。
 */
@Composable
private fun ThinkingBody(segment: ThinkingSegment) {
    val colors = MiuixTheme.colors
    val scroll = rememberScrollState()
    val long = segment.text.length > LONG_THRESHOLD
    val shown = if (long) segment.text.takeLast(WINDOW_CHARS) else segment.text

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .heightIn(max = 360.dp)
            .verticalScroll(scroll),
    ) {
        if (long) {
            MiuixText(
                text = "已省略较早的 ${segment.text.length - WINDOW_CHARS} 字",
                style = MiuixTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        MiuixText(
            text = shown.ifBlank { "…" },
            style = MiuixTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------------------
// 小工具
// ---------------------------------------------------------------------------

/** 胶囊中段的宽度：随文案长度平滑变化，并受上下限约束。 */
private fun capsuleTextWidth(line: String): Dp {
    val chars = if (line.isEmpty()) 8 else line.take(SUMMARY_CHARS).length.coerceAtLeast(8)
    return (chars * 8).coerceIn(80, 200).dp
}

/** 思考计时：`00:12`。 */
internal fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%02d:%02d".format(total / 60, total % 60)
}

/**
 * 组内的思考块。
 *
 * ## 它自己是可展开的
 *
 * 组头那一层折叠管的是「要不要看过程」，**不代替**「这一段思考到底想了什么」。
 * 早先这里只画一行摘要且没有点击处理，于是用户点它没有任何反应
 * （实测反馈「思考内容无法展开」）。现在：
 * - 一行摘要（orb + 轮次 + 摘要 + 耗时）
 * - 点击整行展开完整思考正文（复用 [ThinkingBody]，超长窗口照旧）
 * - 展开态走 `isExpanded`，与组头互相独立：组折叠时块内展开态被保留，
 *   下次展开组还是原来的样子
 */
@Composable
fun ThinkingInline(
    segment: ThinkingSegment,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colors
    val motion = rememberAgentMotion()
    val press = rememberMiuixPressState()
    val chevron by animateFloatAsState(
        targetValue = if (segment.isExpanded) 180f else 0f,
        animationSpec = motion.cardEnter(),
        label = "inline-chevron",
    )

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .height(INLINE_ROW_HEIGHT)
                .miuixTouchTarget(INLINE_ROW_HEIGHT)
                .miuixClickable(press, true, onClick = onToggle)
                .semantics {
                    contentDescription = if (segment.isExpanded) {
                        "思考内容已展开，双击收起"
                    } else {
                        "思考内容已折叠，双击展开"
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ThinkingOrbInline(segment.isRunning, segment.isExpanded)
            MiuixText(
                text = if (segment.round <= 1) "思考" else "思考 · 第 ${segment.round} 轮",
                style = MiuixTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
                maxLines = 1,
            )
            MiuixText(
                text = segment.latestLine.take(SUMMARY_CHARS_INLINE),
                style = MiuixTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            MiuixText(
                text = formatDuration(segment.elapsedMs),
                style = MiuixTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
            )
            MiuixIconRes(
                iconRes = AgentIcons.ic_chevron_down,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                size = 14.dp,
                modifier = Modifier.rotate(chevron),
            )
        }

        AnimatedVisibility(
            visible = segment.isExpanded,
            enter = expandVertically(motion.cardEnter(), expandFrom = Alignment.Top) +
                fadeIn(animationSpec = motion.fade(180)),
            exit = shrinkVertically(motion.cardEnter(), shrinkTowards = Alignment.Top) +
                fadeOut(animationSpec = motion.fade(140)),
        ) {
            ThinkingBody(segment)
        }
    }
}

/** 组内的状态点：运行中显示呼吸环，完成显示对勾。 */
@Composable
private fun ThinkingOrbInline(running: Boolean, expanded: Boolean) {
    val colors = MiuixTheme.colors
    Box(Modifier.size(12.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(
                    if (expanded) colors.primary.copy(alpha = 0.2f)
                    else colors.primary.copy(alpha = 0.12f),
                ),
        )
        if (running) {
            BreathingHalo()
        } else {
            MiuixIconRes(
                iconRes = AgentIcons.ic_check,
                contentDescription = null,
                tint = colors.primary,
                size = 9.dp,
            )
        }
    }
}

/** 组内思考行的高度。 */
private val INLINE_ROW_HEIGHT = 32.dp

private const val SUMMARY_CHARS_INLINE = 40
