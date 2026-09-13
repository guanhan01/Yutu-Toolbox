package com.mcp.toolbox.core.design.theme

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mcp.toolbox.core.design.token.MiuixDimens
import com.mcp.toolbox.core.design.token.MiuixTypography
import com.mcp.toolbox.core.design.token.MotionTokens
import com.mcp.toolbox.core.design.token.RadiusTokens

val LocalMiuixRadius = staticCompositionLocalOf { RadiusTokens() }
val LocalMiuixDimens = staticCompositionLocalOf { MiuixDimens() }
val LocalMiuixTypography = staticCompositionLocalOf { MiuixTypography() }
val LocalMiuixMotion = staticCompositionLocalOf { MotionTokens() }
val LocalMiuixThemeConfig = staticCompositionLocalOf { ThemeConfig() }

/** 统一入口：MiuixTheme.colors / dimens / typography / motion / config。 */
object MiuixTheme {
    val colors: MiuixColors
        @Composable @ReadOnlyComposable get() = LocalMiuixColors.current

    val dimens: MiuixDimens
        @Composable @ReadOnlyComposable get() = LocalMiuixDimens.current

    /** 圆角单独占一个 Local：它每帧都可能随滑杆变，拆开后「不读圆角的子树」不必跟着重组。 */
    val radius: RadiusTokens
        @Composable @ReadOnlyComposable get() = LocalMiuixRadius.current

    val typography: MiuixTypography
        @Composable @ReadOnlyComposable get() = LocalMiuixTypography.current

    val motion: MotionTokens
        @Composable @ReadOnlyComposable get() = LocalMiuixMotion.current

    val config: ThemeConfig
        @Composable @ReadOnlyComposable get() = LocalMiuixThemeConfig.current
}

/** 计算当前深浅模式（支持按模块强制深色）。 */
fun ThemeConfig.resolveDark(systemDark: Boolean, moduleId: String? = null): Boolean = when (darkMode) {
    DarkModeSetting.FOLLOW_SYSTEM -> systemDark
    DarkModeSetting.LIGHT -> false
    DarkModeSetting.DARK -> true
    DarkModeSetting.PER_MODULE -> if (moduleId != null) moduleId in perModuleDark else systemDark
}

/** 依据主题来源解析最终配色。 */
fun resolveColors(context: Context, config: ThemeConfig, isDark: Boolean): MiuixColors {
    val preset = presetById(config.presetId)
    fun presetColors() = MiuixColorSchemeFactory.fromSeed(
        preset.seed.toArgb(), isDark, preset.style, config.contrast.value, config.saturation, config.amoled,
    )
    return when (config.source) {
        ThemeSource.BRAND -> MiuixColorSchemeFactory.fromSeed(
            ThemeConfig.BrandSeedArgb.toInt(), isDark, config.paletteStyle,
            config.contrast.value, config.saturation, config.amoled,
        )
        ThemeSource.DYNAMIC -> MiuixDynamicColors.fromSystem(context, isDark, config.amoled) ?: presetColors()
        ThemeSource.CUSTOM_SEED -> MiuixColorSchemeFactory.fromSeed(
            config.customSeedArgb.toInt(), isDark, config.paletteStyle,
            config.contrast.value, config.saturation, config.amoled,
        )
        ThemeSource.PRESET -> presetColors()
    }
}

/**
 * 主题壳：把配色、尺寸、字体、动效全部下发。
 * 颜色变化走 300-450ms 插值，配合调用方的 circular reveal 实现「改完即时生效、不重启」。
 */
@Composable
fun MiuixTheme(
    config: ThemeConfig = ThemeConfig(),
    moduleId: String? = null,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val isDark = config.resolveDark(systemDark, moduleId)

    // 必须逐个列出「影响配色」的字段，不能拿整个 config 当 key。
    // config 是 data class：拖「圆角尺度」滑杆时 radiusScaleDp 每帧都在变，
    // 用它当 key 会让 resolveColors 每帧重跑（HCT 从 seed 生成整套配色，几毫秒起）—— 这就是拖动掉帧的主因。
    // 下面 dimens / typography / motion 本来就是按字段 remember 的，只有配色这行漏了。
    val target = remember(
        config.source,
        config.presetId,
        config.customSeedArgb,
        config.paletteStyle,
        config.contrast,
        config.saturation,
        config.amoled,
        config.darkMode,
        isDark,
    ) { resolveColors(context, config, isDark) }
    val radius = remember(config.radiusScaleDp) { RadiusTokens(config.radiusScaleDp.dp) }
    // dimensions 不能依赖 radius：否则拖圆角滑杆会让整个 dimens 换实例，
    // 连带所有只读 dimens.spacing / elevation 的组件（底栏、顶栏、抽屉）一起重组。
    val dimens = remember(config.fontScale, config.lineHeightScale) {
        MiuixDimens(fontScale = config.fontScale, lineHeightScale = config.lineHeightScale)
    }
    val typography = remember(config.fontScale, config.lineHeightScale) {
        MiuixTypography(scale = config.fontScale, lineHeightScale = config.lineHeightScale)
    }
    val motion = remember(config.motionScale) {
        MotionTokens(scale = config.motionScale)
    }

    val spec: AnimationSpec<Color> = remember(motion.scale) { tween(durationMillis = motion.medium) }
    val colors = animateKeyColors(target, spec)

    CompositionLocalProvider(
        LocalMiuixColors provides colors,
        LocalMiuixRadius provides radius,
        LocalMiuixDimens provides dimens,
        LocalMiuixTypography provides typography,
        LocalMiuixMotion provides motion,
        LocalMiuixThemeConfig provides config,
        content = content,
    )
}

/** 只对高频可见的颜色角色做插值：避免生硬跳变，也不因 40+ 个动画拖慢首帧。 */
@Composable
private fun animateKeyColors(target: MiuixColors, spec: AnimationSpec<Color>): MiuixColors {
    val primary by animateColorAsState(target.primary, spec)
    val onPrimary by animateColorAsState(target.onPrimary, spec)
    val primaryContainer by animateColorAsState(target.primaryContainer, spec)
    val secondaryContainer by animateColorAsState(target.secondaryContainer, spec)
    val tertiaryContainer by animateColorAsState(target.tertiaryContainer, spec)
    val background by animateColorAsState(target.background, spec)
    val surface by animateColorAsState(target.surface, spec)
    val surfaceContainer by animateColorAsState(target.surfaceContainer, spec)
    val surfaceContainerHigh by animateColorAsState(target.surfaceContainerHigh, spec)
    val onSurface by animateColorAsState(target.onSurface, spec)
    val onSurfaceVariant by animateColorAsState(target.onSurfaceVariant, spec)
    val outline by animateColorAsState(target.outline, spec)
    val outlineVariant by animateColorAsState(target.outlineVariant, spec)
    val error by animateColorAsState(target.error, spec)

    return target.copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        secondaryContainer = secondaryContainer,
        tertiaryContainer = tertiaryContainer,
        background = background,
        surface = surface,
        surfaceContainer = surfaceContainer,
        surfaceContainerHigh = surfaceContainerHigh,
        onSurface = onSurface,
        onSurfaceVariant = onSurfaceVariant,
        outline = outline,
        outlineVariant = outlineVariant,
        error = error,
    )
}
