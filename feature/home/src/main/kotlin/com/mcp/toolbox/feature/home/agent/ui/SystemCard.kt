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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.delay


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
    // 429 的等待倒计时（Spec 第 8.3 节：429 要展示倒计时）
    val waitSeconds = rememberRateLimitRemaining(segment)

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
            waitSeconds = waitSeconds,
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
    waitSeconds: Int,
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
        // 倒计时期间按钮本身显示剩余秒数并置灰：Spec 要求 429 展示倒计时，
        // 而「立刻可点的重试」会把用户直接送进下一次 429。
        SystemKind.RateLimited -> listOf(
            (if (waitSeconds > 0) AgentText.ERR_RATE_LIMIT_WAIT.format(waitSeconds) else AgentText.ERR_RETRY) to onRetry,
        )
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

    val waiting = kind == SystemKind.RateLimited && waitSeconds > 0

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        actions.forEach { (label, action) ->
            SystemTextAction(label = label, enabled = !waiting, onClick = action)
        }
    }
}

@Composable
private fun SystemTextAction(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = MiuixTheme.colors
    val press = rememberMiuixPressState()
    Box(
        modifier = Modifier
            .heightIn(min = 32.dp)
            .sqSurface(colors.primary.copy(alpha = if (enabled) 0.10f else 0.05f), SquircleRadii.bubbleTail)
            .miuixClickable(press, enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        MiuixText(
            text = label,
            style = MiuixTheme.typography.labelMedium,
            color = if (enabled) colors.primary else colors.primary.copy(alpha = 0.4f),
        )
    }
}

/**
 * 429 的剩余等待秒数；不适用时为 0。
 *
 * 计时起点从 [SystemSegment.createdAt] 与已落盘的 `retryAfterSeconds` 推算，
 * **不是**从「进入组合」开始计 —— 卡片会随列表滚动反复进出组合，那样一滚就跳回满值。
 * 该时间点已过（切页回来、或从磁盘读出的旧记录）时直接返 0，界面不再显示倒计时。
 */
@Composable
private fun rememberRateLimitRemaining(segment: SystemSegment): Int {
    val total = segment.retryAfterSeconds
    val deadline = remember(segment.id, segment.createdAt, total) {
        if (total > 0) segment.createdAt + total * 1000L else 0L
    }
    var remaining by remember(segment.id, deadline) {
        mutableIntStateOf(remainingSeconds(deadline))
    }
    LaunchedEffect(deadline) {
        if (deadline <= 0L) return@LaunchedEffect
        while (true) {
            remaining = remainingSeconds(deadline)
            if (remaining <= 0) break
            delay(1_000L)
        }
    }
    return remaining
}

/** 距离 [deadline] 还有几秒（不足一秒进一，归零后不再为负）。 */
private fun remainingSeconds(deadline: Long): Int {
    if (deadline <= 0L) return 0
    val left = deadline - System.currentTimeMillis()
    if (left <= 0L) return 0
    return ((left + 999L) / 1000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
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
