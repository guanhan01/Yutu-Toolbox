package com.mcp.toolbox.feature.home.agent.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
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
import com.mcp.toolbox.feature.home.agent.Segment
import com.mcp.toolbox.feature.home.agent.ThinkingSegment
import com.mcp.toolbox.feature.home.agent.ToolCallSegment
import com.mcp.toolbox.feature.home.agent.ToolStatus

/**
 * 过程组：把连续的「思考 / 工具」归成一栏。
 *
 * ```
 *  ▼ 已思考 2 次 · 调用 2 个工具 · 成功 2 次     ← 组头（下括号）
 *  │
 *  ├─ ◯ 思考  先看看用户问的是什么…      00:00
 *  ├─ ▣ 设备信息                     703ms ⌄
 *  └─ ◯ 思考 · 第 2 轮  确认是 Hyper…    00:00
 * ───────────────────────────────────────────
 *  环境已确认：这台设备是 HyperOS 3 …
 * ```
 *
 * ## 树形连接线
 *
 * 用户明确要求：**从组头下括号开始、可转弯、连到图标 / 工具卡的边框**。
 *
 * 实现上不是「左侧一列固定高度的竖线」，而是**每一行绘制自己的那一段**：
 * - 竖线从该行顶端画到「末项则止于转弯处，否则画满整行高度」
 * - 转弯是一条横枝，从竖线顶到该行内容的左边缘——也就是图标方块 / 卡片边框
 *
 * 这么分行的好处是**高度天然正确**：工具卡展开后可能有 100dp 以上，
 * 用固定高度的连接格会在中间留出断口。每行各自画满，相邻行的竖线自然接续。
 *
 * ## 默认展开策略
 *
 * - 组内还有**正在运行**的块 → 默认展开
 * - 全部结束 → 默认折叠
 * - 用户手动操作过 → 永远听用户的
 */
@Composable
fun ProcessGroupCard(
    groupId: String,
    children: List<Segment>,
    expanded: Boolean,
    onToggle: () -> Unit,
    renderChild: @Composable (Segment) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colors
    val motion = rememberAgentMotion()
    val press = rememberMiuixPressState()
    val chevron by animateFloatAsState(
        targetValue = if (expanded) 0f else -90f,
        animationSpec = motion.cardEnter(),
        label = "group-chevron",
    )

    val thinkCount = children.count { it is ThinkingSegment }
    val tools = children.filterIsInstance<ToolCallSegment>()
    val okCount = tools.count { it.status == ToolStatus.Success }
    val running = children.any { seg ->
        (seg is ThinkingSegment && seg.isRunning) ||
            (seg is ToolCallSegment && seg.isRunning)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // ── 组头：一个统计胶囊 ──
        //
        // 底色用 surface（浅色主题下即纯白）而不是 surfaceContainerHigh（灰）：
        // 组头是这一栏的「标题」，压一层灰会显得和下面的内容一样重（用户要求背景换白）。
        // 描边改成 1.5dp + 略深一档的 outline，比 1dp outlineVariant 更有轮廓感，
        // 又不至于变成一块厚重的黑框。外圈再叠一层极淡的主色，做出「简洁但有点质感」。
        Row(
            modifier = Modifier
                .height(GROUP_HEADER_HEIGHT)
                .miuixTouchTarget(GROUP_HEADER_HEIGHT)
                .miuixClickable(press, true, onClick = onToggle)
                .sqSurface(colors.surface, SquircleRadii.card)
                .sqBorder(HEADER_BORDER_WIDTH, colors.outline.copy(alpha = 0.45f), SquircleRadii.card)
                .padding(horizontal = GROUP_HEADER_PADDING)
                .semantics {
                    contentDescription =
                        if (expanded) "过程已展开，双击收起" else "过程已折叠，双击展开"
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            MiuixIconRes(
                iconRes = AgentIcons.ic_chevron_down,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                size = GROUP_HEADER_CHEVRON,
                modifier = Modifier.rotate(chevron),
            )
            MiuixText(
                text = buildSummary(thinkCount, tools.size, okCount),
                style = MiuixTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (running) {
                Spacer(Modifier.width(2.dp))
                MiuixText(
                    text = "进行中",
                    style = MiuixTheme.typography.labelSmall,
                    color = colors.primary,
                )
            }
        }

        // 组头与组内容之间留出间距。
        // 之前两者直接相邻，组头的下边框和第一张工具卡的上边框贴在一起，
        // 看起来像共用一条线（用户实测反馈「边框重合」）。
        // 连接线仍会向上补画 GAP_ABOVE_FIRST，所以视觉上依旧连成一体。
        Spacer(Modifier.height(HEADER_CONTENT_GAP))

        // ── 组内：树形连接线 + 各块 ──
        AnimatedVisibility(
            visible = expanded,
            // clip = false：第一行的竖线要向组头方向**画出容器之外**（补到箭头尖端），
            // 而 expandVertically 默认在动画期间做裁剪，会把这段补画的部分切掉 ——
            // 用户看到的「刚展开时没接上、过一会儿才接上」正是这个（实测反馈）。
            enter = expandVertically(motion.cardEnter(), expandFrom = Alignment.Top, clip = false) +
                fadeIn(animationSpec = motion.fade(180)),
            exit = shrinkVertically(motion.cardEnter(), shrinkTowards = Alignment.Top, clip = false) +
                fadeOut(animationSpec = motion.fade(140)),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                children.forEachIndexed { index, child ->
                    TreeRow(
                        isLast = index == children.lastIndex,
                        isFirst = index == 0,
                        elbowY = elbowFor(child),
                        lineColor = colors.outlineVariant,
                    ) {
                        renderChild(child)
                    }
                }
            }
        }

        // ── 分隔线：把「过程」与「结论」在视觉上断开 ──
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .height(1.dp)
                .sqSurface(colors.outlineVariant, 0.dp),
        )
    }
}

/**
 * 一行「连接线 + 内容」。
 *
 * 连接线用 [Canvas] 铺满整行（`matchParentSize`），所以它的高度**等于内容的实际高度**，
 * 不需要任何高度估算——这一点很关键：工具卡展开后的高度无法预估。
 */
@Composable
private fun TreeRow(
    isLast: Boolean,
    /** 是否组内第一行：第一行的竖线要向上补到组头下方。 */
    isFirst: Boolean,
    elbowY: androidx.compose.ui.unit.Dp,
    lineColor: Color,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    Box(modifier = Modifier.fillMaxWidth()) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val stroke = with(density) { CONNECTOR_STROKE.toPx() }
            val lineX = with(density) { LINE_X.toPx() }
            val elbowX = with(density) { CONTENT_START.toPx() }
            val ey = with(density) { elbowY.toPx() }.coerceAtMost(size.height)

            // 竖线：末项止于转弯处（形成 └），其余画满整行。
            //
            // 第一行要把竖线向上补到**箭头尖端**（不是组头底边）。
            //
            // 用 maxOf 兜底：若将来有人调大 CHILD_GAP 等间距，也不会把这根线缩回到
            // 尖端以下 —— 少接一截看起来就是「没接上」（用户实测反馈过两次）。
            val vStart = if (isFirst) {
                -with(density) { maxOf(GAP_ABOVE_FIRST, LINE_REACH_ABOVE_CONTENT).toPx() }
            } else {
                0f
            }
            val vEnd = if (isLast) ey else size.height
            if (vEnd > 0f) {
                drawLine(
                    color = lineColor,
                    start = Offset(lineX, vStart),
                    end = Offset(lineX, vEnd),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }
            // 转弯：横枝顶到内容左边缘（图标方块 / 卡片边框）
            drawLine(
                color = lineColor,
                start = Offset(lineX, ey),
                end = Offset(elbowX, ey),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
        Box(
            modifier = Modifier
                .padding(start = CONTENT_START)
                // 间隙做成底边距而不是 Column 的 spacedBy：
                // 这样间隙算在行高之内，竖线才不会在两项之间断开
                .padding(bottom = CHILD_GAP),
        ) {
            content()
        }
    }
}

/**
 * 横枝的竖直位置。
 *
 * 要对准「图标方块的竖直中线」，而不同块的头部高度不同：
 * 思考行 32dp（中线 16dp）、工具卡头部 44dp（图标 tile 32dp 居中 → 中线 22dp）。
 * 所以按类型给值，而不是取一个折中的常数——差 6dp 肉眼就能看出没对齐。
 */
private fun elbowFor(segment: Segment) = when (segment) {
    is ToolCallSegment -> 22.dp
    else -> 16.dp
}

/** 「已思考 2 次 · 调用 3 个工具 · 成功 2 次」，只包含实际发生过的项。 */
private fun buildSummary(thinkCount: Int, toolCount: Int, okCount: Int): String {
    val parts = buildList {
        if (thinkCount > 0) add("已思考 $thinkCount 次")
        if (toolCount > 0) add("调用 $toolCount 个工具")
        if (okCount > 0) add("成功 $okCount 次")
    }
    return if (parts.isEmpty()) "过程" else parts.joinToString(" · ")
}

private val GROUP_HEADER_HEIGHT = 36.dp

/** 组头描边宽度。1dp 太细、2dp 太重，1.5 在这种小尺寸上最有轮廓感。 */
private val HEADER_BORDER_WIDTH = 1.5.dp

/** 组头与组内容之间的竖直间距；连接线会补画这一段，视觉上仍相连。 */
private val HEADER_CONTENT_GAP = 8.dp

/**
 * 组头的水平内边距。
 *
 * 连接线的 x 必须由它推导，不能另写一个常数——早先组头是 `padding(horizontal = 10.dp)`
 * 而竖线画在 `8.dp`，两者差 10dp，竖线落在箭头左侧、与下括号的尖端对不上
 * （用户实测反馈）。
 */
private val GROUP_HEADER_PADDING = 10.dp

/** 组头 chevron 的尺寸。竖线的 x 取它的**中心**，才与「下括号的尖端」对齐。 */
private val GROUP_HEADER_CHEVRON = 16.dp

/**
 * 竖线的 x 位置（相对组内容左边）。
 *
 * = 组头内边距 + chevron 半径，即 chevron 的竖直中线。
 */
private val LINE_X = GROUP_HEADER_PADDING + GROUP_HEADER_CHEVRON * 0.5f

/** 内容左起点：也是横枝的终点，正好落在图标方块 / 卡片边框上。 */
private val CONTENT_START = LINE_X + 12.dp

private val CONNECTOR_STROKE = 1.5.dp

/**
 * 组头与第一项之间的空隙：第一行的竖线要向上补这一段，才与下括号尖端相连。
 *
 * **必须等于 [HEADER_CONTENT_GAP]** —— 它俩说的是同一段距离（组头底边到第一项顶边）。
 * 早先这里写 6dp、间距用 8dp，差 2dp，竖线就永远差一小截接不上（用户实测反馈）。
 * 现在从同一个值派生，改一处即可。
 */
private val GAP_ABOVE_FIRST = HEADER_CONTENT_GAP

/**
 * 第一行竖线要向上伸到「箭头尖端」所需的距离（相对第一行顶边的负偏移）。
 *
 * 箭头是 [AgentIcons.ic_chevron_down]，其尖端在 24 网格的 y=16；缩到
 * [GROUP_HEADER_CHEVRON] 大小、并在 36dp 高的组头里居中对齐后，
 * 尖端距组头顶边 = (36 − 16)/2 + 16/24 × 16 ≈ 20.67dp。
 * 第一行顶边距组头顶边 = 组头高 + 间距，因此需要向上伸
 *   36 + HEADER_CONTENT_GAP − 20.67。
 *
 * 写成算式而不是写死一个数：组头高、图标尺寸、间距任一变了都还成立。
 */
private val LINE_REACH_ABOVE_CONTENT =
    GROUP_HEADER_HEIGHT + HEADER_CONTENT_GAP - (GROUP_HEADER_HEIGHT - GROUP_HEADER_CHEVRON) / 2f -
        GROUP_HEADER_CHEVRON * (16f / 24f)

/** 组内各项之间的间距。 */
private val CHILD_GAP = 6.dp
