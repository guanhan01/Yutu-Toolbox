package com.mcp.toolbox.core.design.theme

import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.Colors as OfficialColors

/**
 * 官方 Miuix 配色桥。
 *
 * 本工程的颜色方案 [MiuixColors] 是全项目 `MiuixTheme.colors.*` 的读取源；
 * 官方 Miuix 组件读的是 `top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme`。
 * 这里把我方配色逐一映射成官方 [OfficialColors]，随 [MiuixTheme] 一并下发，
 * 保证「官方组件」与「自绘组件」在同一屏里颜色完全一致。
 */
fun MiuixColors.toOfficial(): OfficialColors = OfficialColors(
    primary = primary,
    onPrimary = onPrimary,
    primaryVariant = primaryContainer,
    onPrimaryVariant = onPrimaryContainer,
    error = error,
    onError = onError,
    errorContainer = errorContainer,
    onErrorContainer = onErrorContainer,
    disabledPrimary = onSurface.copy(alpha = 0.12f),
    disabledOnPrimary = onSurface.copy(alpha = 0.38f),
    disabledPrimaryButton = surfaceContainerHigh,
    disabledOnPrimaryButton = onSurface.copy(alpha = 0.38f),
    disabledPrimarySlider = surfaceContainerHighest,
    primaryContainer = primaryContainer,
    onPrimaryContainer = onPrimaryContainer,
    secondary = secondary,
    onSecondary = onSecondary,
    secondaryVariant = secondaryContainer,
    onSecondaryVariant = onSecondaryContainer,
    disabledSecondary = onSurface.copy(alpha = 0.12f),
    disabledOnSecondary = onSurface.copy(alpha = 0.38f),
    disabledSecondaryVariant = surfaceContainerHigh,
    disabledOnSecondaryVariant = onSurface.copy(alpha = 0.38f),
    secondaryContainer = secondaryContainer,
    onSecondaryContainer = onSecondaryContainer,
    secondaryContainerVariant = tertiaryContainer,
    onSecondaryContainerVariant = onTertiaryContainer,
    tertiaryContainer = tertiaryContainer,
    onTertiaryContainer = onTertiaryContainer,
    tertiaryContainerVariant = secondaryContainer,
    background = background,
    onBackground = onBackground,
    onBackgroundVariant = onSurfaceVariant,
    surface = surface,
    onSurface = onSurface,
    surfaceVariant = surfaceVariant,
    onSurfaceSecondary = onSurfaceVariant,
    onSurfaceVariantSummary = onSurfaceVariant,
    onSurfaceVariantActions = onSurfaceVariant,
    disabledOnSurface = onSurface.copy(alpha = 0.38f),
    surfaceContainer = surfaceContainer,
    onSurfaceContainer = onSurface,
    onSurfaceContainerVariant = onSurfaceVariant,
    surfaceContainerHigh = surfaceContainerHigh,
    onSurfaceContainerHigh = onSurface,
    surfaceContainerHighest = surfaceContainerHighest,
    onSurfaceContainerHighest = onSurface,
    outline = outline,
    dividerLine = outlineVariant,
    // 官方 windowDimming 是带 alpha 的遮罩（浅色 0.3 / 深色 0.6）；
    // scrim 本身不透明，直接用会把整屏盖黑。
    windowDimming = Color.Black.copy(alpha = if (isDark) 0.6f else 0.3f),
    sliderKeyPoint = primary,
    sliderKeyPointForeground = onPrimary,
    sliderBackground = surfaceContainerHighest,
)
