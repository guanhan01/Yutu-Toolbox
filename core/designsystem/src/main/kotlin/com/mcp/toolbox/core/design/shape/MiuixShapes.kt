package com.mcp.toolbox.core.design.shape

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.squircle.SquircleDefaults
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.squircle.squircleSurface

/**
 * 真 Miuix 的「形状」语义常量。
 *
 * 真 Miuix 不提供 `SmoothRoundedCornerShape` 这类 Shape 对象：连续超椭圆是由
 * `miuix-squircle` 的 **Modifier** 实现的（[squircleSurface] / [squircleBackground] /
 * [squircleClip] / [squircleBorder]），底层是 SDF 着色器；API < 33 无运行时着色器时
 * 库内部自动退回 `RoundedCornerShape`。
 *
 * 因此本项目里「给块加圆角」不再写 `.clip(RoundedCornerShape(20.dp)).background(c)`，
 * 而是写 `.squircleSurface(c, SquircleRadii.card)`。
 *
 * 数值取自 UI/动效 Spec 第 2.1 节。G2 连续超椭圆正是「真 Miuix 味」的第一识别点，
 * 普通圆角会立刻显得像 Material，所以这里不给 RoundedCornerShape 留任何出口。
 */
object SquircleRadii {
    /** 全屏容器 / 侧边栏：仅抽屉侧。 */
    val panel = 28.dp

    /** AI 回答卡片（Segment 容器）。思考块、工具卡统一用这一档，保持「柔性框架」观感。 */
    val card = 20.dp

    /** 用户 / AI 气泡：与卡片同档，靠收口角体现方向差异。 */
    val bubble = 20.dp

    /** 气泡语义性收口角（用户右下 / AI 左下）。 */
    val bubbleTail = 6.dp

    /** 卡片内嵌容器（代码块内层、工具图标 tile 等）。 */
    val inner = 12.dp

    /** 代码块：比卡片更「硬」，视觉上区分于正文容器。 */
    val codeBlock = 14.dp

    /** 工具图标 tile。 */
    val iconTile = 10.dp

    /** 底栏图标按钮的按压光效区域。 */
    val iconButton = 16.dp

    /** 缩略图。 */
    val thumbnail = 12.dp
}

/**
 * 连续超椭圆（G2）的角延伸系数，`1.0` 是正圆弧、`1.1` 是真 Miuix 默认的连续角。
 * 不要改这个默认值——它是「真 Miuix 圆角」与「普通圆角」的分界。
 */
val SquircleExtension: Float = SquircleDefaults.Extension

// ---------------------------------------------------------------------------
// 便捷别名：省略 extension 参数，统一走真 Miuix 默认连续角
// ---------------------------------------------------------------------------

/** 填充 + 裁剪子树（内容也会被裁成超椭圆）。容器用这个。 */
@Composable
fun Modifier.sqSurface(color: Color, radius: Dp): Modifier =
    squircleSurface(color = color, cornerRadius = radius, extension = SquircleExtension)

/** 四角不同半径的 [sqSurface]。顺序与 `RoundedCornerShape` 一致。 */
@Composable
fun Modifier.sqSurface(
    color: Color,
    topStart: Dp,
    topEnd: Dp,
    bottomEnd: Dp,
    bottomStart: Dp,
): Modifier = squircleSurface(
    color = color,
    topStart = topStart,
    topEnd = topEnd,
    bottomEnd = bottomEnd,
    bottomStart = bottomStart,
    extension = SquircleExtension,
)

/** 只画底色，不裁剪子树（描边、分隔层用这个，避免多一层离屏合成）。 */
@Composable
fun Modifier.sqBackground(color: Color, radius: Dp): Modifier =
    squircleBackground(color = color, cornerRadius = radius, extension = SquircleExtension)

/** 裁剪子树到超椭圆轮廓，不画底色。 */
@Composable
fun Modifier.sqClip(radius: Dp): Modifier =
    squircleClip(cornerRadius = radius, extension = SquircleExtension)

/** 超椭圆描边。与 [sqBackground] 叠加即为「有描边的卡片」。 */
@Composable
fun Modifier.sqBorder(width: Dp, color: Color, radius: Dp): Modifier =
    squircleBorder(width = width, color = color, cornerRadius = radius, extension = SquircleExtension)
