package com.mcp.toolbox.core.design.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mcp.toolbox.core.design.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Button as OfficialButton
import top.yukonga.miuix.kmp.basic.ButtonColors as OfficialButtonColors
import top.yukonga.miuix.kmp.basic.Text as OfficialText
import top.yukonga.miuix.kmp.theme.MiuixTheme as OfficialTheme

/** 按钮视觉层级，与设计稿 Buttons 区一致：Filled / Tonal / Outlined / Text / Elevated。 */
enum class MiuixButtonVariant { FILLED, TONAL, OUTLINED, TEXT, ELEVATED }

enum class MiuixButtonSize(val height: Dp, val horizontalPadding: Dp) {
    SMALL(32.dp, 14.dp),
    MEDIUM(40.dp, 20.dp),
    LARGE(48.dp, 24.dp),
}

/**
 * 标准按钮。
 *
 * 实现为官方 Miuix 组件（squircle 圆角 + 官方点击反馈），API 保持项目内统一。
 */
@Composable
fun MiuixButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: MiuixButtonVariant = MiuixButtonVariant.FILLED,
    size: MiuixButtonSize = MiuixButtonSize.MEDIUM,
    enabled: Boolean = true,
    loading: Boolean = false,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
) {
    MiuixButtonOfficial(
            text, onClick, modifier, variant, size, enabled, loading, leadingIcon, trailingIcon,
        )
}

// ---------------- Miuix（官方库） ----------------

@Composable
private fun MiuixButtonOfficial(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    variant: MiuixButtonVariant,
    size: MiuixButtonSize,
    enabled: Boolean,
    loading: Boolean,
    leadingIcon: ImageVector?,
    trailingIcon: ImageVector?,
) {
    val colors = MiuixTheme.colors
    val typography = MiuixTheme.typography
    val official = OfficialTheme.colorScheme
    val clickable = enabled && !loading

    val container = when (variant) {
        MiuixButtonVariant.FILLED -> colors.primary
        MiuixButtonVariant.TONAL -> colors.secondaryContainer
        MiuixButtonVariant.ELEVATED -> colors.surfaceContainerHigh
        MiuixButtonVariant.OUTLINED, MiuixButtonVariant.TEXT -> Color.Transparent
    }
    val contentColor = when (variant) {
        MiuixButtonVariant.FILLED -> colors.onPrimary
        MiuixButtonVariant.TONAL -> colors.onSecondaryContainer
        MiuixButtonVariant.ELEVATED -> colors.onSurface
        MiuixButtonVariant.OUTLINED, MiuixButtonVariant.TEXT -> colors.primary
    }
    val textStyle = when (size) {
        MiuixButtonSize.SMALL -> typography.labelMedium
        MiuixButtonSize.MEDIUM -> typography.labelLarge
        MiuixButtonSize.LARGE -> typography.bodyLarge
    }
    val iconSize = if (size == MiuixButtonSize.SMALL) 16.dp else 18.dp

    val buttonColors = OfficialButtonColors(
        color = container,
        disabledColor = if (variant == MiuixButtonVariant.FILLED) official.disabledPrimaryButton else container,
        contentColor = contentColor,
        disabledContentColor = if (variant == MiuixButtonVariant.FILLED) official.disabledOnPrimaryButton else contentColor,
    )

    // 按钮保持项目的胶囊形态：把半径显式传成「高度的一半」，
    // 而不是留给官方 ButtonDefaults.CornerRadius(16dp) —— 否则按钮圆角既不跟随项目
    // 视觉语言，也不跟随主题圆角滑杆，成了一个没人管的固定值。
    val cornerRadius = size.height / 2
    val pillShape = RoundedCornerShape(cornerRadius)

    OfficialButton(
        onClick = onClick,
        modifier = modifier
            .height(size.height)
            .then(
                when (variant) {
                    MiuixButtonVariant.OUTLINED -> Modifier.border(
                        BorderStroke(1.dp, colors.outlineVariant),
                        pillShape,
                    )
                    MiuixButtonVariant.ELEVATED -> Modifier.shadow(
                        MiuixTheme.dimens.elevation.level2,
                        pillShape,
                    )
                    else -> Modifier
                },
            ),
        cornerRadius = cornerRadius,
        enabled = clickable,
        minWidth = 0.dp,
        minHeight = size.height,
        colors = buttonColors,
        insideMargin = PaddingValues(horizontal = size.horizontalPadding, vertical = 0.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            when {
                loading -> MiuixCircularProgress(size = iconSize, color = contentColor, strokeWidth = 2.dp)
                leadingIcon != null -> MiuixIcon(leadingIcon, null, tint = contentColor, size = iconSize)
            }
            OfficialText(text = text, style = textStyle, color = contentColor, maxLines = 1)
            if (trailingIcon != null) MiuixIcon(trailingIcon, null, tint = contentColor, size = iconSize)
        }
    }
}

// ---------------- Classic（自绘） ----------------

@Composable
fun MiuixIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    filled: Boolean = false,
    tonal: Boolean = false,
    buttonSize: Dp = 40.dp,
    iconSize: Dp = 20.dp,
    tint: Color = Color.Unspecified,
) {
    val colors = MiuixTheme.colors
    val press = rememberMiuixPressState(enabled)
    val shape = RoundedCornerShape(percent = 50)
    val container = when {
        filled -> colors.primary
        tonal -> colors.secondaryContainer
        else -> Color.Transparent
    }
    val contentColor = when {
        tint != Color.Unspecified -> tint
        filled -> colors.onPrimary
        tonal -> colors.onSecondaryContainer
        else -> colors.onSurfaceVariant
    }
    Box(
        modifier = modifier
            .miuixClickable(press, enabled, onClick = onClick, role = Role.Button)
            .miuixTouchTarget()
            .size(buttonSize)
            .clip(shape)
            .background(container)
            .alpha(if (enabled) 1f else 0.4f),
        contentAlignment = Alignment.Center,
    ) {
        if (press.pressed) Box(Modifier.matchParentSize().background(colors.pressedOverlay))
        MiuixIcon(icon, contentDescription, tint = contentColor, size = iconSize)
    }
}

/** 悬浮操作按钮（含扩展形态，设计稿右上角 + / + Add）。 */
@Composable
fun MiuixFab(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = Color.Unspecified,
    contentColor: Color = Color.Unspecified,
) {
    val colors = MiuixTheme.colors
    val press = rememberMiuixPressState()
    val shape = RoundedCornerShape(percent = 50)
    val bg = if (containerColor == Color.Unspecified) colors.primaryContainer else containerColor
    val fg = if (contentColor == Color.Unspecified) colors.onPrimaryContainer else contentColor
    Box(
        modifier = modifier
            .size(56.dp)
            .clip(shape)
            .background(bg)
            .miuixClickable(press, true, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (press.pressed) Box(Modifier.matchParentSize().background(colors.pressedOverlay))
        MiuixIcon(icon, contentDescription, tint = fg, size = 24.dp)
    }
}

@Composable
fun MiuixExtendedFab(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colors
    val press = rememberMiuixPressState()
    val shape = RoundedCornerShape(percent = 50)
    Box(
        modifier = modifier
            .height(48.dp)
            .clip(shape)
            .background(colors.primaryContainer)
            .miuixClickable(press, true, onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (press.pressed) Box(Modifier.matchParentSize().background(colors.pressedOverlay))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MiuixIcon(icon, null, tint = colors.onPrimaryContainer, size = 18.dp)
            MiuixText(text = text, style = MiuixTheme.typography.labelLarge, color = colors.onPrimaryContainer)
        }
    }
}
