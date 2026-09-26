package com.mcp.toolbox.core.design.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 本项目自己的颜色方案，角色命名对齐 Material3 ColorScheme，
 * 但完全由本工程控制构造过程（HCT/莫奈取色），避免跟随 material3 版本漂移。
 */
@Immutable
data class MiuixColors(
    val isDark: Boolean,
    val amoled: Boolean = false,
    /** 纯白模式：surface 系压到白色/灰白，主色保持鲜艳。 */
    val pureWhite: Boolean = false,

    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val primaryFixed: Color,
    val primaryFixedDim: Color,
    val onPrimaryFixed: Color,
    val onPrimaryFixedVariant: Color,

    val secondary: Color,
    val onSecondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,

    val tertiary: Color,
    val onTertiary: Color,
    val tertiaryContainer: Color,
    val onTertiaryContainer: Color,

    val background: Color,
    val onBackground: Color,
    val surface: Color,
    val onSurface: Color,
    val surfaceDim: Color,
    val surfaceBright: Color,
    val surfaceContainerLowest: Color,
    val surfaceContainerLow: Color,
    val surfaceContainer: Color,
    val surfaceContainerHigh: Color,
    val surfaceContainerHighest: Color,
    val surfaceVariant: Color,
    val onSurfaceVariant: Color,
    val inverseSurface: Color,
    val inverseOnSurface: Color,
    val inversePrimary: Color,
    val outline: Color,
    val outlineVariant: Color,
    val scrim: Color,

    val error: Color,
    val onError: Color,
    val errorContainer: Color,
    val onErrorContainer: Color,
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,

    /** 代码 / SQL 编辑器配色：底色比内容区更暗，语法高亮固定三色。 */
    val codeBackground: Color,
    val codeKeyword: Color,
    val codeString: Color,
    val codeType: Color,
    val codeComment: Color,
) {
    /** 按压态：表面轻微变暗，不使用 Material 水波纹。 */
    val pressedOverlay: Color get() = if (isDark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.05f)

    /** 危险操作文字色（删除 / 卸载 / 解密 HTTPS）。 */
    val danger: Color get() = error

    fun containerFor(role: ColorRole): Color = when (role) {
        ColorRole.Primary -> primaryContainer
        ColorRole.Secondary -> secondaryContainer
        ColorRole.Tertiary -> tertiaryContainer
        ColorRole.Surface -> surfaceContainer
        ColorRole.Error -> errorContainer
        ColorRole.Success -> successContainer
        ColorRole.Warning -> warningContainer
    }

    fun onContainerFor(role: ColorRole): Color = when (role) {
        ColorRole.Primary -> onPrimaryContainer
        ColorRole.Secondary -> onSecondaryContainer
        ColorRole.Tertiary -> onTertiaryContainer
        ColorRole.Surface -> onSurface
        ColorRole.Error -> onErrorContainer
        ColorRole.Success -> onSuccessContainer
        ColorRole.Warning -> onWarningContainer
    }
}

enum class ColorRole { Primary, Secondary, Tertiary, Surface, Error, Success, Warning }

/** 主题色通过 CompositionLocal 下发，重组粒度保持在颜色对象级别。 */
val LocalMiuixColors = staticCompositionLocalOf<MiuixColors> {
    error("MiuixColors 未提供：请用 MiuixTheme { } 包裹内容")
}
