package com.mcp.toolbox.core.design.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.palettes.TonalPalette
import top.yukonga.miuix.kmp.theme.darkColorScheme as officialDarkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme as officialLightColorScheme

/**
 * 官方 Miuix 的默认调色板桥。
 *
 * 【为什么需要它】
 * 本工程默认把整套配色接在「一颗 Seed + 色彩风格」的 HCT 派生链上（见
 * [MiuixColorSchemeFactory]），连默认值都是一颗紫 Seed 算出来的 —— 这与官方 Miuix
 * 自带的默认调色板并不是同一组颜色，观感有肉眼可见的差别。
 *
 * 「Customize 取色」关闭时要呈现的就是**真 Miuix**：直接取官方的
 * [officialLightColorScheme] / [officialDarkColorScheme]，逐角色映射回 [MiuixColors]。
 * 这样界面与官方示例完全一致，也不会出现「官方配色 + 某几项自定义」的混合态。
 *
 * 官方配色只覆盖 Material 角色，本工程额外的语义色（success / warning）与代码高亮
 * 官方没有对应项，按同样的色相自行派生 —— 这两类与「取色」无关，始终保持一致。
 */
object MiuixOfficialPalette {

    private val successPalette = TonalPalette.fromHueAndChroma(145.0, 40.0)
    private val warningPalette = TonalPalette.fromHueAndChroma(75.0, 62.0)

    fun colors(isDark: Boolean): MiuixColors {
        val o = if (isDark) officialDarkColorScheme() else officialLightColorScheme()
        val light = !isDark

        // 下面每个角色的取值都来自真机反射官方 lightColorScheme()/darkColorScheme() 的实测值，
        // 不是按字段名推测。官方默认（浅色）关键几项：
        //   surface #F7F7F7 / surfaceContainer #FFFFFF / surfaceContainerHigh #E8E8E8
        //   background #FFFFFF / surfaceVariant #FFFFFF / primary #3482FF
        // 官方 Scaffold 的 containerColor 默认是 surface（页面浅灰），卡片是
        // surfaceContainer（白）——这个明度差是卡片能「浮起来」的唯一依据。
        return MiuixColors(
            isDark = isDark,
            amoled = false,
            pureWhite = false,

            primary = o.primary,
            onPrimary = o.onPrimary,
            primaryContainer = o.primaryVariant,
            onPrimaryContainer = o.onPrimaryVariant,
            // 官方没有 M3 的 fixed 系列，用主色容器档位补齐同色相的两档
            primaryFixed = o.primaryContainer,
            primaryFixedDim = o.primaryVariant,
            onPrimaryFixed = o.onPrimaryContainer,
            onPrimaryFixedVariant = o.onPrimaryVariant,

            secondary = o.secondary,
            onSecondary = o.onBackground,
            secondaryContainer = o.secondaryVariant,
            onSecondaryContainer = o.onBackground,

            // 官方没有独立 tertiary 语义，容器档位取自 tertiaryContainer
            tertiary = o.tertiaryContainer,
            onTertiary = o.onTertiaryContainer,
            tertiaryContainer = o.tertiaryContainer,
            onTertiaryContainer = o.onTertiaryContainer,

            // 【关键】本工程把「页面底色」叫 background，官方把同一个东西叫 surface
            // （官方 Scaffold 的 containerColor 默认就是 surface）。这里必须交叉映射：
            // 直连官方 background 会让页面变纯白，与同样纯白的卡片 surfaceContainer
            // 同色 —— 卡片整块消失，图二那种「浅灰页面 + 白卡片」的分层也就没了。
            //
            // 官方实测（浅色）：surface #F7F7F7 页面浅灰、surfaceContainer #FFFFFF 卡片白。
            // 注意与 M3 方向相反：M3 是「页面最亮、卡片略暗」，官方 Miuix 是反过来，
            // 所以不能沿用本工程 M3 那套「越靠 container 越暗」的直觉。
            background = o.surface,
            onBackground = o.onBackground,
            surface = o.surface,
            onSurface = o.onSurface,
            surfaceDim = o.surfaceContainerHigh,
            surfaceBright = o.surfaceContainer,
            // 最亮档 = 卡片白
            surfaceContainerLowest = o.surfaceContainer,
            surfaceContainerLow = o.surfaceContainer,
            surfaceContainer = o.surfaceContainer,
            surfaceContainerHigh = o.surfaceContainerHigh,
            surfaceContainerHighest = o.surfaceContainerHighest,
            surfaceVariant = o.surfaceVariant,
            onSurfaceVariant = o.onSurfaceSecondary,
            inverseSurface = o.onSurface,
            inverseOnSurface = o.surface,
            inversePrimary = o.primaryContainer,
            outline = o.outline,
            outlineVariant = o.dividerLine,
            scrim = Color.Black,

            error = o.error,
            onError = o.onError,
            errorContainer = o.errorContainer,
            onErrorContainer = o.onErrorContainer,

            success = Color(successPalette.tone(if (light) 40 else 80)),
            onSuccess = Color(successPalette.tone(if (light) 100 else 20)),
            successContainer = Color(successPalette.tone(if (light) 90 else 30)),
            onSuccessContainer = Color(successPalette.tone(if (light) 10 else 90)),
            warning = Color(warningPalette.tone(if (light) 40 else 80)),
            onWarning = Color(warningPalette.tone(if (light) 100 else 20)),
            warningContainer = Color(warningPalette.tone(if (light) 90 else 30)),
            onWarningContainer = Color(warningPalette.tone(if (light) 10 else 90)),

            // 代码高亮是「编辑器」语义，官方没有对应角色，跟着主色相走
            codeBackground = if (isDark) Color(0xFF1A1A1A) else Color(0xFFF4F4F6),
            codeKeyword = o.primary,
            codeString = o.onTertiaryContainer,
            codeType = o.tertiaryContainer,
            codeComment = o.onSurfaceSecondary,
        )
    }

    /** 官方默认配色的主色，供设置页「是否与默认一致」之类的判断使用。 */
    fun primaryArgb(isDark: Boolean): Int = colors(isDark).primary.toArgb()
}
