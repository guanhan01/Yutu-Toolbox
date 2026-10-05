package com.mcp.toolbox.feature.home.agent.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mcp.toolbox.core.design.component.MarkdownBlockView
import com.mcp.toolbox.core.design.component.MdBlock
import com.mcp.toolbox.core.design.component.MiuixIconRes
import com.mcp.toolbox.core.design.component.MiuixText
import com.mcp.toolbox.core.design.component.miuixClickable
import com.mcp.toolbox.core.design.component.rememberMiuixPressState
import com.mcp.toolbox.core.design.icon.AgentIcons
import com.mcp.toolbox.core.design.shape.SquircleRadii
import com.mcp.toolbox.core.design.shape.sqSurface
import com.mcp.toolbox.core.design.theme.MiuixTheme
import com.mcp.toolbox.core.design.token.rememberAgentMotion
import com.mcp.toolbox.feature.home.agent.AgentText

/**
 * 回答正文（Spec 第 5.3 / 5.4 / 5.6 / 5.7 节）。
 *
 * ## 块级增量渲染
 * [closedBlocks] 是已解析过、**永不重排**的前缀；[tail] 是还在流入的尾块。
 * 只有尾块每次提交重解析——长回答不卡的关键（见 `MarkdownChunker`）。
 *
 * ## 文字选中走系统（Spec 硬规则 6）
 * 整段正文包在 [SelectionContainer] 里，用**系统**的选择手柄与工具栏。
 * **不自建任何复制浮层**。代码块内的复制按钮也只写系统剪贴板。
 */
@Composable
fun AnswerBlock(
    /** **已经解析好、且不重叠**的块序列。渲染路径只有这一条。 */
    blocks: List<MdBlock>,
    streaming: Boolean,
    text: String,
    pulseKey: Int,
    onCopy: () -> Unit,
    onRegenerate: () -> Unit,
    onDelete: () -> Unit,
    /**
     * 是否是最新的一轮。
     *
     * 「重新生成」只对最新一轮有意义：重跑一条历史回答会把它之后的话题全部作废，
     * 那不是用户点这个按钮时的预期。历史轮次直接**隐藏**它（而不是置灰）——
     * 置灰会让人反复去猜「为什么不能点」。
     */
    isLatestTurn: Boolean = true,
    /** 「朗读」——交给宿主（TTS）；未接入时菜单项置灰而不是隐藏，保持可发现性。 */
    onSpeak: () -> Unit = {},
    /**
     * 「分享为图片」。
     *
     * 图层由**整轮**（提问气泡 + 这条回答）统一录制，见 `TurnRow`——
     * 用户明确要求图片里要带上自己的问题，所以捕获范围必须比一条回答更大。
     */
    onShareImage: (androidx.compose.ui.graphics.layer.GraphicsLayer) -> Unit = {},
    /** 本轮可分享的图层；为空表示这一轮还没准备好（此时菜单项置灰）。 */
    shareLayer: androidx.compose.ui.graphics.layer.GraphicsLayer? = null,
    /**
     * 「反馈」——把这条回答当素材，带用户去提 issue / 发邮件。
     *
     * 传正文而不是什么都不传：反馈最常见的就是「模型这句话说错了」，
     * 让用户自己再复制一遍原文很不合理。截断由接收方负责。
     */
    onFeedback: (answerExcerpt: String) -> Unit = {},
    speakEnabled: Boolean = false,
    shareEnabled: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                blocks.forEach { block ->
                    MarkdownBlockView(block, modifier = Modifier.fillMaxWidth())
                }
            }
        }

        // 流式光标：独立 Composable，只有它自己随 pulseKey 重组
        if (streaming) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                StreamCursor(pulseKey = pulseKey)
            }
        }

        // 完成态操作条：非流式期间常驻
        AnswerActions(
            visible = !streaming && text.isNotBlank(),
            onCopy = onCopy,
            onRegenerate = onRegenerate,
            onDelete = onDelete,
            isLatestTurn = isLatestTurn,
            onSpeak = onSpeak,
            onShareImage = { shareLayer?.let(onShareImage) },
            canShare = shareLayer != null,
            onFeedback = { onFeedback(text) },
            speakEnabled = speakEnabled,
            shareEnabled = shareEnabled,
        )
    }
}

@Composable
private fun AnswerActions(
    visible: Boolean,
    onCopy: () -> Unit,
    onRegenerate: () -> Unit,
    onDelete: () -> Unit,
    isLatestTurn: Boolean,
    onSpeak: () -> Unit,
    onShareImage: () -> Unit,
    onFeedback: () -> Unit,
    speakEnabled: Boolean,
    shareEnabled: Boolean,
    /** 本轮能否分享（图层已就绪）。 */
    canShare: Boolean,
) {
    val motion = rememberAgentMotion()
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = motion.fade(240)) +
            slideInVertically(
                animationSpec = motion.cardEnter(),
                initialOffsetY = { it / 2 },
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnswerTextAction(AgentText.ACTION_COPY, AgentIcons.ic_copy, onCopy)
            // 历史轮次不显示「重新生成」：它会作废之后的话题
            if (isLatestTurn) {
                AnswerTextAction(AgentText.ACTION_REGENERATE, AgentIcons.ic_retry, onRegenerate)
            }

            // ── 更多 ──
            // 早先这个按钮**直接执行删除**，既没有菜单也没有确认（用户实测：
            // 「更多按钮的功能没做」）。现在按设计稿弹溢出菜单。
            var moreOpen by remember { mutableStateOf(false) }
            var anchor by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
            var confirmDelete by remember { mutableStateOf(false) }

            Box(
                modifier = Modifier.onGloballyPositioned { anchor = it.boundsInWindow() },
            ) {
                AnswerTextAction(
                    label = AgentText.ACTION_MORE,
                    iconRes = AgentIcons.ic_more_horiz,
                    onClick = { moreOpen = true },
                )
            }

            com.mcp.toolbox.core.design.component.MiuixOverflowMenu(
                expanded = moreOpen,
                onDismiss = { moreOpen = false },
                anchor = anchor,
                focusable = false,
            ) {
                com.mcp.toolbox.core.design.component.MiuixMenuItem(
                    text = "分享为图片",
                    iconRes = AgentIcons.ic_share,
                    enabled = canShare,
                    onClick = { moreOpen = false; onShareImage() },
                )
                com.mcp.toolbox.core.design.component.MiuixMenuItem(
                    text = "朗读",
                    iconRes = AgentIcons.ic_volume,
                    enabled = speakEnabled,
                    onClick = { moreOpen = false; onSpeak() },
                )
                com.mcp.toolbox.core.design.component.MiuixMenuDivider()
                com.mcp.toolbox.core.design.component.MiuixMenuItem(
                    text = "反馈",
                    iconRes = AgentIcons.ic_bug,
                    onClick = { moreOpen = false; onFeedback() },
                )
                com.mcp.toolbox.core.design.component.MiuixMenuItem(
                    text = AgentText.ACTION_DELETE,
                    iconRes = AgentIcons.ic_trash,
                    danger = true,
                    onClick = { moreOpen = false; confirmDelete = true },
                )
            }

            // 删除是破坏性的：先确认，再删
            com.mcp.toolbox.core.design.component.MiuixDialog(
                visible = confirmDelete,
                onDismiss = { confirmDelete = false },
                title = "删除这条回答？",
                message = "这条回答的正文与过程都会被删除，无法撤销。",
                confirmText = "删除",
                destructive = true,
                onConfirm = { confirmDelete = false; onDelete() },
            )
        }
    }
}

@Composable
private fun AnswerTextAction(label: String, iconRes: Int, onClick: () -> Unit) {
    val colors = MiuixTheme.colors
    val press = rememberMiuixPressState()
    Row(
        modifier = Modifier
            .height(32.dp)
            .sqSurface(colors.surfaceContainerHigh, SquircleRadii.bubbleTail)
            .miuixClickable(press, true, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        MiuixIconRes(iconRes, null, tint = colors.onSurfaceVariant, size = 14.dp)
        MiuixText(
            text = label,
            style = MiuixTheme.typography.labelMedium,
            color = colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 系统剪贴板写入器。
 *
 * 抽成函数是为了让调用点只关心「复制了什么」，
 * 而「用系统剪贴板」这个约定只在这里出现一次。
 */
@Composable
fun rememberClipboardWriter(): (String) -> Unit {
    val clipboard = LocalClipboardManager.current
    return remember(clipboard) {
        { text -> clipboard.setText(AnnotatedString(text)) }
    }
}
