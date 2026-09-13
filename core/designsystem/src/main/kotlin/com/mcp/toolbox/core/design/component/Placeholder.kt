package com.mcp.toolbox.core.design.component

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mcp.toolbox.core.design.theme.MiuixTheme

/** 骨架屏占位块：加载中的列表/详情统一使用，避免空白闪烁。 */
@Composable
fun MiuixSkeleton(
    modifier: Modifier = Modifier,
    height: Dp = 16.dp,
    shape: RoundedCornerShape = RoundedCornerShape(8.dp),
) {
    val colors = MiuixTheme.colors
    val transition = rememberInfiniteTransition(label = "miuix-skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(tween(MiuixTheme.motion.shimmer), RepeatMode.Reverse),
        label = "alpha",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .alpha(alpha)
            .clip(shape)
            .background(colors.surfaceContainerHighest),
    )
}

/** 空状态：图标位 + 标题 + 说明 + 可选操作。 */
@Composable
fun MiuixEmptyState(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    icon: ImageVector? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val colors = MiuixTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(colors.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                MiuixIcon(icon, null, tint = colors.onSurfaceVariant, size = 32.dp)
            }
            Spacer(Modifier.height(16.dp))
        }
        MiuixText(text = title, style = MiuixTheme.typography.titleMedium)
        if (description != null) {
            Spacer(Modifier.height(6.dp))
            MiuixText(
                text = description,
                style = MiuixTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}

/** 红点 / 未读数角标（抽屉条目与底部导航共用）。 */
@Composable
fun MiuixBadge(
    modifier: Modifier = Modifier,
    count: Int = 0,
    dot: Boolean = false,
) {
    val colors = MiuixTheme.colors
    if (dot) {
        Box(
            modifier = modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(colors.error),
        )
        return
    }
    val label = if (count > 99) "99+" else count.toString()
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(colors.error)
            .padding(horizontal = 5.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        MiuixText(
            text = label,
            style = MiuixTheme.typography.labelSmall,
            color = colors.onError,
            maxLines = 1,
        )
    }
}
