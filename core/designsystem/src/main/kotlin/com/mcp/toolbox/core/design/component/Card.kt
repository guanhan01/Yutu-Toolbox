package com.mcp.toolbox.core.design.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mcp.toolbox.core.design.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Card as OfficialCard
import top.yukonga.miuix.kmp.basic.CardColors as OfficialCardColors
import top.yukonga.miuix.kmp.basic.CardDefaults as OfficialCardDefaults
import top.yukonga.miuix.kmp.utils.PressFeedbackType as OfficialPressFeedbackType

/** 卡片三种形态：filled / elevated / outlined（对应设计稿 Cards section）。 */
enum class MiuixCardVariant { FILLED, ELEVATED, OUTLINED }

/**
 * 卡片。
 *
 * 形状与按压反馈走官方 [OfficialCard]（squircle 圆角 + 官方 press 反馈）；
 * 圆角接项目 token（跟随主题圆角滑杆），不写死官方默认的 16dp。
 * 三种形态通过官方 [OfficialCardColors] + 描边/阴影表达，不再改形状。
 */
@Composable
fun MiuixCard(
    modifier: Modifier = Modifier,
    variant: MiuixCardVariant = MiuixCardVariant.FILLED,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    cornerRadius: Dp = MiuixTheme.radius.card,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MiuixTheme.colors

    val container = when (variant) {
        MiuixCardVariant.FILLED -> colors.surfaceContainer
        MiuixCardVariant.ELEVATED -> colors.surfaceContainerLow
        MiuixCardVariant.OUTLINED -> colors.surface
    }
    val cardColors = OfficialCardColors(
        color = container,
        contentColor = colors.onSurface,
    )

    val base = Modifier
        .fillMaxWidth()
        .then(
            if (variant == MiuixCardVariant.OUTLINED) {
                Modifier.border(
                    BorderStroke(1.dp, colors.outlineVariant),
                    androidx.compose.foundation.shape.RoundedCornerShape(cornerRadius),
                )
            } else {
                Modifier
            },
        )

    if (onClick != null) {
        OfficialCard(
            modifier = modifier.then(base),
            cornerRadius = cornerRadius,
            insideMargin = contentPadding,
            colors = cardColors,
            pressFeedbackType = OfficialPressFeedbackType.Sink,
            onClick = onClick,
            content = content,
        )
    } else {
        OfficialCard(
            modifier = modifier.then(base),
            cornerRadius = cornerRadius,
            insideMargin = contentPadding,
            colors = cardColors,
            content = content,
        )
    }
}

/** 分组卡片：小标题 + 副标题 + 圆角容器（设置页与文件页的标准分组形态）。 */
@Composable
fun MiuixSectionCard(
    title: String? = null,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (title != null) {
            MiuixText(
                text = title,
                style = MiuixTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 4.dp, bottom = if (subtitle == null) 8.dp else 2.dp),
            )
        }
        if (subtitle != null) {
            MiuixText(
                text = subtitle,
                style = MiuixTheme.typography.labelMedium,
                color = MiuixTheme.colors.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            )
        }
        MiuixCard(contentPadding = contentPadding, content = content)
    }
}

/** 危险操作区：红色描边容器（删除 / 清空类操作）。 */
@Composable
fun MiuixDangerCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MiuixTheme.colors
    val shape: Shape = androidx.compose.foundation.shape.RoundedCornerShape(MiuixTheme.radius.card)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(BorderStroke(1.dp, colors.error.copy(alpha = 0.5f)), shape),
        content = content,
    )
}
