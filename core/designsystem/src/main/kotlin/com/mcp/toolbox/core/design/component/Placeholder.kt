package com.mcp.toolbox.core.design.component

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.Badge as M3Badge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mcp.toolbox.core.design.theme.MiuixTheme
import top.yukonga.miuix.kmp.anim.SinOutEasing
import top.yukonga.miuix.kmp.basic.Card as OfficialCard
import top.yukonga.miuix.kmp.basic.Surface as OfficialSurface

/**
 * 占位与角标。
 *
 * 官方 Miuix 没有 EmptyState / Badge / Skeleton 这三种组件，因此：
 * - Badge 用 Material3 的 [M3Badge]（Google 的实现，不是自绘）
 * - EmptyState 用官方 [OfficialSurface] + 官方图标/文字「拼装」，不自己画背景
 * - Skeleton 形状无法由任何厂商组件表达，保留绘制，但呼吸动画改用官方 [SinOutEasing]
 */

/**
 * 骨架屏占位块。
 * 形状本身没有对应厂商组件，保留绘制；动画曲线对齐官方（[SinOutEasing] 呼吸）。
 */
@Composable
fun MiuixSkeleton(
    modifier: Modifier = Modifier,
    height: Dp = 16.dp,
    shape: RoundedCornerShape = RoundedCornerShape(8.dp),
) {
    val colors = MiuixTheme.colors
    val transition = rememberInfiniteTransition(label = "miuix-skeleton")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(MiuixTheme.motion.shimmer, easing = SinOutEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "alpha",
    )
    OfficialSurface(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .alpha(0.35f + 0.4f * progress),
        shape = shape,
        color = colors.surfaceContainerHighest,
        content = {},
    )
}

/** 空状态：官方 Surface 承载 + 官方图标与文字排版。 */
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
            OfficialSurface(
                modifier = Modifier.size(72.dp),
                shape = CircleShape,
                color = colors.surfaceContainerHigh,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    MiuixIcon(icon, null, tint = colors.onSurfaceVariant, size = 32.dp)
                }
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

/**
 * 红点 / 未读数角标。
 * 实现走 Material3 [M3Badge]（其他厂商实现，非自绘），配色接项目主题。
 */
@Composable
fun MiuixBadge(
    modifier: Modifier = Modifier,
    count: Int = 0,
    dot: Boolean = false,
) {
    val colors = MiuixTheme.colors
    if (dot) {
        // M3 的红点形态就是 Badge(content = null)：空内容即自动收成小圆点
        M3Badge(
            modifier = modifier,
            containerColor = colors.error,
            contentColor = colors.onError,
        )
        return
    }
    val label = if (count > 99) "99+" else count.toString()
    M3Badge(
        containerColor = colors.error,
        contentColor = colors.onError,
    ) {
        MiuixText(text = label, style = MiuixTheme.typography.labelSmall, maxLines = 1)
    }
}

/** 官方 Card 承载的空状态卡片（用于卡片式空态容器）。 */
@Composable
fun MiuixEmptyCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    OfficialCard(modifier = modifier, cornerRadius = MiuixTheme.radius.card) {
        content()
    }
}
