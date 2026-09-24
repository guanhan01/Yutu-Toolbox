package com.mcp.toolbox.core.design.theme

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 界面风格：决定组件用哪一套实现渲染。
 *
 * - [CLASSIC]：本项目自绘的设计系统（原有观感与动画）
 * - [MIUIX]：官方 Miuix 库组件（官方形态、官方动画）
 *
 * 组件保留同一套 API，内部按本值分支，因此切换风格不影响任何调用点。
 */
enum class UiStyle(val labelZh: String, val labelEn: String) {
    CLASSIC("经典", "Classic"),
    MIUIX("Miuix", "Miuix"),
}

/** 当前界面风格，由 [MiuixTheme] 下发。 */
val LocalUiStyle = staticCompositionLocalOf { UiStyle.MIUIX }
