package com.mcp.toolbox.feature.home.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mcp.toolbox.core.design.component.MiuixIconRes
import com.mcp.toolbox.core.design.component.MiuixText
import com.mcp.toolbox.core.design.component.miuixClickable
import com.mcp.toolbox.core.design.component.rememberMiuixPressState
import com.mcp.toolbox.core.design.icon.AgentIcons
import com.mcp.toolbox.core.design.shape.SquircleRadii
import com.mcp.toolbox.core.design.shape.sqBorder
import com.mcp.toolbox.core.design.shape.sqSurface
import com.mcp.toolbox.core.design.theme.MiuixTheme
import com.mcp.toolbox.feature.home.agent.AgentText
import com.mcp.toolbox.feature.home.agent.SystemKind
import com.mcp.toolbox.feature.home.agent.SystemSegment


/**
 * 系统卡片（Spec 第 8.3 节异常矩阵 + 第 8.2 节断点卡）。
 *
 * **不允许落到「什么都不显示」**——46 项状态清单里每一项都必须在这里有分支与文案。
 * 这也是为什么 [SystemKind] 是枚举而不是自由字符串：新情况必须显式加分支。
 */
@Composable
fun SystemCard(
    segment: SystemSegment,
    onRetry: () -> Unit = {},
    onContinue: () -> Unit = {},
    onRegenerate: () -> Unit = {},
    onDelete: () -> Unit = {},
    onOpenAuth: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colors
    val spec = SystemVisuals.of(segment.kind, colors, segment)

    // 中断 / 断点类给一个 1.5% 的错误色描边（Spec 第 8.2 节④）
    val bordered = segment.kind == SystemKind.Checkpoint ||
        segment.kind == SystemKind.UserStopped ||
        segment.kind == SystemKind.NetworkLost

    Column(
        modifier = modifier
            .fillMaxWidth()
            .sqSurface(
                if (spec.tinted) spec.accent.copy(alpha = 0.06f) else Color.Transparent,
                SquircleRadii.card,
            )
            .then(
                if (bordered) {
                    Modifier.sqBorder(1.dp, colors.error.copy(alpha = 0.35f), SquircleRadii.card)
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .sqSurface(spec.accent.copy(alpha = 0.12f), SquircleRadii.iconTile),
                contentAlignment = Alignment.Center,
            ) {
                MiuixIconRes(
                    iconRes = spec.icon,
                    contentDescription = null,
                    tint = spec.accent,
                    size = 14.dp,
                )
            }
            Column(Modifier.weight(1f)) {
                MiuixText(
                    text = spec.title,
                    style = MiuixTheme.typography.titleSmall,
                    color = colors.onSurface,
                )
                if (spec.detail.isNotEmpty()) {
                    MiuixText(
                        text = spec.detail,
                        style = MiuixTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        SystemActions(
            kind = segment.kind,
            onRetry = onRetry,
            onContinue = onContinue,
            onRegenerate = onRegenerate,
            onDelete = onDelete,
            onOpenAuth = onOpenAuth,
        )
    }
}

/**
 * 每种异常的操作区。
 *
 * 401 是**登录 CTA 卡**而不是报错红条（Spec 明文要求）；
 * 429 展示倒计时；断点卡给「继续生成 / 重新生成 / 删除」三件套。
 */
@Composable
private fun SystemActions(
    kind: SystemKind,
    onRetry: () -> Unit,
    onContinue: () -> Unit,
    onRegenerate: () -> Unit,
    onDelete: () -> Unit,
    onOpenAuth: () -> Unit,
) {
    val actions: List<Pair<String, () -> Unit>> = when (kind) {
        SystemKind.Offline -> listOf(AgentText.ERR_RETRY to onRetry)
        SystemKind.NetworkLost -> listOf(AgentText.ERR_RETRY to onRetry)
        SystemKind.Stall -> listOf(AgentText.ERR_RETRY to onRetry)
        SystemKind.ServerError -> listOf(AgentText.ERR_RETRY to onRetry)
        SystemKind.RateLimited -> listOf(AgentText.ERR_RETRY to onRetry)
        SystemKind.UserStopped -> listOf(
            AgentText.STOPPED_CONTINUE to onContinue,
            AgentText.ACTION_REGENERATE to onRegenerate,
            AgentText.ACTION_COPY to {},
        )

        SystemKind.Checkpoint -> listOf(
            AgentText.STOPPED_CONTINUE to onContinue,
            AgentText.ACTION_REGENERATE to onRegenerate,
            AgentText.ACTION_DELETE to onDelete,
        )

        SystemKind.AuthRequired -> listOf(AgentText.ERR_AUTH_CTA to onOpenAuth)
        SystemKind.Blocked -> listOf(AgentText.ERR_RETRY to onRetry)
        SystemKind.Truncated -> listOf(AgentText.ERR_TRUNCATED_ACTION to onContinue)
        SystemKind.EmptyAnswer -> listOf(AgentText.ERR_EMPTY_ACTION to onRegenerate)
        SystemKind.ToolOnly -> listOf(AgentText.ERR_TOOL_ONLY_ACTION to onRegenerate)
    }
    if (actions.isEmpty()) return

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        actions.forEach { (label, action) ->
            SystemTextAction(label = label, onClick = action)
        }
    }
}

@Composable
private fun SystemTextAction(label: String, onClick: () -> Unit) {
    val colors = MiuixTheme.colors
    val press = rememberMiuixPressState()
    Box(
        modifier = Modifier
            .heightIn(min = 32.dp)
            .sqSurface(colors.primary.copy(alpha = 0.10f), SquircleRadii.bubbleTail)
            .miuixClickable(press, true, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        MiuixText(
            text = label,
            style = MiuixTheme.typography.labelMedium,
            color = colors.primary,
        )
    }
}

/** 每种系统状态的图标 / 标题 / 细节 / 强调色。 */
private data class SystemVisual(
    val icon: Int,
    val title: String,
    val detail: String,
    val accent: Color,
    val tinted: Boolean = false,
)

private object SystemVisuals {

    @Composable
    fun of(
        kind: SystemKind,
        colors: com.mcp.toolbox.core.design.theme.MiuixColors,
        segment: SystemSegment,
    ): SystemVisual =
        when (kind) {
            SystemKind.Offline -> SystemVisual(
                AgentIcons.ic_wifi_off, AgentText.ERR_OFFLINE, "", colors.warning,
            )

            SystemKind.NetworkLost -> SystemVisual(
                AgentIcons.ic_offline_cloud, AgentText.ERR_NETWORK_LOST, "", colors.warning,
            )

            SystemKind.Stall -> SystemVisual(
                AgentIcons.ic_timeout, AgentText.ERR_STALL, "", colors.warning,
            )

            SystemKind.UserStopped -> SystemVisual(
                AgentIcons.ic_agent_stop, AgentText.STOPPED, "", colors.onSurfaceVariant,
            )

            SystemKind.Checkpoint -> SystemVisual(
                AgentIcons.ic_snapshot,
                AgentText.CHECKPOINT_TITLE,
                AgentText.CHECKPOINT_PRESERVED.format(segment.preservedChars),
                colors.warning,
            )

            SystemKind.AuthRequired -> SystemVisual(
                AgentIcons.ic_password, AgentText.ERR_AUTH, "", colors.primary,
            )

            SystemKind.RateLimited -> SystemVisual(
                AgentIcons.ic_rate_limit, AgentText.ERR_RATE_LIMIT, "", colors.warning,
            )

            SystemKind.ServerError -> SystemVisual(
                AgentIcons.ic_error, AgentText.ERR_SERVER, "", colors.error,
            )

            SystemKind.Blocked -> SystemVisual(
                AgentIcons.ic_blocked, AgentText.ERR_BLOCKED, AgentText.ERR_BLOCKED_HINT,
                colors.onSurfaceVariant, tinted = true,
            )

            SystemKind.Truncated -> SystemVisual(
                AgentIcons.ic_partial, AgentText.ERR_TRUNCATED, "", colors.warning,
            )

            SystemKind.EmptyAnswer -> SystemVisual(
                AgentIcons.ic_empty_state, AgentText.ERR_EMPTY, "", colors.onSurfaceVariant,
            )

            SystemKind.ToolOnly -> SystemVisual(
                AgentIcons.ic_ai_summary, AgentText.ERR_TOOL_ONLY, "", colors.onSurfaceVariant,
            )
        }
}
