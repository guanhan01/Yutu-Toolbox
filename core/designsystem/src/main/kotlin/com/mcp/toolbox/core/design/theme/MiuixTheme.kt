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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.fillMaxSize
import com.mcp.toolbox.core.design.token.MiuixDimens
import com.mcp.toolbox.core.design.token.MiuixTypography
import com.mcp.toolbox.core.design.token.MotionTokens
import com.mcp.toolbox.core.design.token.RadiusTokens
import top.yukonga.miuix.kmp.theme.MiuixTheme as OfficialMiuixTheme
import top.yukonga.miuix.kmp.basic.Scaffold as OfficialScaffold
import top.yukonga.miuix.kmp.theme.defaultTextStyles

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
fun ThemeConfig.resolveDark(systemDark: Boolean, moduleId: String? = null): Boolean {
    // 纯白模式下强制浅色：整个页面是白底，混入深色分支会前后矛盾
    if (pureWhite) return false
    return when (darkMode) {
        DarkModeSetting.FOLLOW_SYSTEM -> systemDark
        DarkModeSetting.LIGHT -> false
        DarkModeSetting.DARK -> true
        DarkModeSetting.PER_MODULE -> if (moduleId != null) moduleId in perModuleDark else systemDark
    }
}

/**
 * 解析最终配色。
 *
 * 来源只有两条：开了动态取色就跟随系统（Monet），否则用 [ThemeConfig.customSeedArgb]。
 * 色彩风格固定活力、对比度固定标准 —— 这两个档位已从界面下线，避免同一颗 Seed 出多种观感。
 */
fun resolveColors(context: Context, config: ThemeConfig, isDark: Boolean): MiuixColors {
    val seed = config.customSeedArgb.toInt()
    val fixed = FIXED_PALETTE_STYLE
    // 纯白下页面是白底，主色若沿用原饱和度会显得发灰发淡：
    // 把 chroma 推高一档，滑杆/按钮才是「鲜艳」的。
    val saturation = if (config.pureWhite) {
        (config.saturation * PureWhiteSaturationBoost).coerceAtMost(MaxPureWhiteSaturation)
    } else {
        config.saturation
    }
    // 纯白 = 白底 + 浅色，因此这两条路径都按浅色、非 AMOLED 生成
    val dark = if (config.pureWhite) false else isDark
    val amoled = if (config.pureWhite) false else config.amoled
    fun fromSeed() = MiuixColorSchemeFactory.fromSeed(
        seed, dark, fixed,
        ThemeConfig.FixedContrastLevel, saturation, amoled,
    )
    val resolved = when {
        config.dynamicEnabled ->
            MiuixDynamicColors.fromSystem(context, dark, amoled) ?: fromSeed()
        else -> fromSeed()
    }
    // 纯白是叠在配色之上的最后一层：它本身就意味着「整个页面进入纯白」，
    // 所以不再要求当前已是浅色 —— 选中纯白即强制浅色底。
    return if (config.pureWhite) resolved.toPureWhite() else resolved
}

/** 纯白模式下主色饱和度的额外倍率（白底上要更艳才看得出颜色）。 */
private const val PureWhiteSaturationBoost = 1.45f
private const val MaxPureWhiteSaturation = 2.0f

/**
 * 把子树强制成「纯白浅色」表面。
 *
 * 用途：某些控件在深色下需要保持浅色（例如聊天输入栏——白底更容易看清正在输入什么），
 * 但**不能只改背景色**：底色变白而文字/图标仍是深色主题的浅色值，会直接看不清。
 * 因此这里整块换掉 [LocalMiuixColors]，让子树里所有 `MiuixTheme.colors.*` 一致地取浅色。
 *
 * 只在 [enabled] 为 true 时生效，不做任何事时零开销（不入 CompositionLocalProvider）。
 */
@Composable
fun MiuixForceLightSurface(
    enabled: Boolean,
    content: @Composable (MiuixColors) -> Unit,
) {
    val context = LocalContext.current
    val config = MiuixTheme.config
    // 用同一颗 Seed 重新解析出一套「纯白 + 浅色」配色：
    // pureWhite 会同时把 surface 压白、把主色 chroma 提高，白底上依然鲜艳。
    val lightColors = remember(config) {
        resolveColors(
            context,
            config.copy(
                pureWhite = true,
                darkMode = DarkModeSetting.LIGHT,
                amoled = false,
            ),
            isDark = false,
        )
    }
    val effective = if (enabled) lightColors else MiuixTheme.colors
    CompositionLocalProvider(LocalMiuixColors provides effective) {
        // 关键：必须把配色「传出去」而不是让调用方在包装外读 MiuixTheme.colors。
        // 包装外读到的仍是外层（深色）配色，那样换了 Local 也白换 —— 调用方手里的
        // val colors 早已是旧值，modifier / 图标 tint 全都继续用深色。
        content(effective)
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
        config.customSeedArgb,
        config.dynamicEnabled,
        config.saturation,
        config.amoled,
        config.pureWhite,
        config.darkMode,
        isDark,
    ) { resolveColors(context, config, isDark) }
    val radius = remember(config.radiusScaleDp) { RadiusTokens(config.radiusScaleDp.dp) }
    // dimensions 不能依赖 radius：否则拖圆角滑杆会让整个 dimens 换实例，
    // 连带所有只读 dimens.spacing / elevation 的组件（底栏、顶栏、抽屉）一起重组。
    // 行高倍率固定 1.0×：该档位已从界面下线。
    val dimens = remember(config.fontScale) {
        MiuixDimens(fontScale = config.fontScale, lineHeightScale = 1f)
    }
    val typography = remember(config.fontScale) {
        MiuixTypography(scale = config.fontScale, lineHeightScale = 1f)
    }
    val motion = remember(config.motionScale) {
        MotionTokens(scale = config.motionScale)
    }

    val spec: AnimationSpec<Color> = remember(motion.scale) { tween(durationMillis = motion.medium) }
    val colors = animateKeyColors(target, spec)

    // 官方配色：用同一颗 Seed 生成，供官方组件（Button/Slider/Dialog/BottomSheet…）读取，
    // 保证官方组件与自绘组件同屏颜色一致。
    val officialColors = remember(target) { target.toOfficial() }
    // 官方 slot 的字号语义（0.9.2 实测基准）：
    //   main 17 / paragraph 17 / body1 16 / body2 14 / button 17
    //   footnote1 13 / footnote2 11 / headline1 17 / headline2 16 / subtitle 14 Bold
    //   title1 32 / title2 24 / title3 20 / title4 18
    // 必须按「字号」对齐而不是按名字对齐：我们把 title 系当「层级」用，而官方 title1 是 32sp；
    // 错配会让 BasicComponent 的列表标题（取 headline1）渲染成巨字。
    val officialTextStyles = remember(dimens, typography) {
        defaultTextStyles().copy(
            main = typography.bodyMedium,
            paragraph = typography.bodyMedium,
            body1 = typography.bodyLarge,
            body2 = typography.bodyMedium,
            button = typography.labelLarge,
            footnote1 = typography.labelLarge,
            footnote2 = typography.labelSmall,
            headline1 = typography.titleSmall,
            headline2 = typography.titleSmall,
            subtitle = typography.labelMedium,
            title1 = typography.displaySmall,
            title2 = typography.headlineSmall,
            title3 = typography.titleLarge,
            title4 = typography.titleMedium,
        )
    }

    CompositionLocalProvider(
        LocalMiuixColors provides colors,
        LocalMiuixRadius provides radius,
        LocalMiuixDimens provides dimens,
        LocalMiuixTypography provides typography,
        LocalMiuixMotion provides motion,
        LocalMiuixThemeConfig provides config,
    ) {
        OfficialMiuixTheme(officialColors, officialTextStyles) {
            // 官方弹层（ListPopup / Dialog / BottomSheet）靠官方 Scaffold 提供的
            // LocalPopupStates / LocalDialogStates 渲染。项目没有官方 Scaffold，
            // 这里补一个只当「弹层宿主」的 Scaffold：popupHost 默认就是 MiuixPopupHost()，
            // 负责把所有官方弹层画到内容之上。容器色设为透明，不影响现有界面底色。
            OfficialScaffold(
                // 不透明背景 + 裁边：避免页面在转场滑动时透视到相邻页内容
                modifier = Modifier.fillMaxSize().clipToBounds(),
                containerColor = colors.background,
                contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
            ) { _ ->
                content()
            }
        }
    }
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
