package com.mcp.toolbox.core.design.component

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.mcp.toolbox.core.design.shape.SquircleRadii
import com.mcp.toolbox.core.design.shape.sqBorder
import com.mcp.toolbox.core.design.shape.sqSurface
import com.mcp.toolbox.core.design.theme.MiuixTheme
import top.yukonga.miuix.kmp.anim.SinOutEasing
import top.yukonga.miuix.kmp.basic.SmallTopAppBar as OfficialSmallTopAppBar
import top.yukonga.miuix.kmp.basic.TopAppBar as OfficialTopAppBar

/**
 * 标准顶栏。
 *
 * 实现走官方 [OfficialSmallTopAppBar] / [OfficialTopAppBar]：
 * 高度、标题内边距、窗口内边距与滚动表现全部取自官方，
 * 因此**不要**在调用方再叠 `statusBarsPadding()`（会双重留白）。
 *
 * @param centeredTitle true 时用官方 [OfficialTopAppBar]（标题居中），false 用 [OfficialSmallTopAppBar]（标题左对齐）。
 * @param scrolled 滚动后底色由透明渐显为 surface，走官方 [SinOutEasing] 曲线。
 */
@Composable
fun MiuixTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    @DrawableRes navigationIconRes: Int? = null,
    onNavigationClick: (() -> Unit)? = null,
    scrolled: Boolean = false,
    centeredTitle: Boolean = false,
    subtitle: String = "",
    /**
     * 是否绘制标题文字。
     *
     * 传 false 时标题槽留空（导航图标仍在左、动作区仍在右），[title] 仅用于
     * 导航按钮的无障碍描述 —— 对话页顶栏就是这种形态：标题内容较长且与消息区
     * 重复，留着只会挤占空间。
     *
     * 官方 TopAppBar 本身没有这个开关（它的 title 是必填 String），
     * 所以在这一层用空串实现。
     */
    showTitle: Boolean = true,
    /**
     * 悬浮形态：顶栏叠在内容之上，滚动时内容从它下面滑过，而不是被它顶开。
     *
     * 开启后底色不再跟随 [scrolled] 动画，而是**常驻半透明**：
     * 内容从它下面经过时能透出一点，形成层次。
     *
     * 注意：这里**不做**真正的背景模糊（毛玻璃）。这台设备的 MIUI 在
     * `MiBackgroundBlurBlend::preUpdateInfo` 上有空指针缺陷 —— 只要给图层挂
     * `RenderEffect`（含 `Modifier.blur`）就会在 RenderThread 里 SIGSEGV 闪退，
     * 栈顶是 `RenderProperties::promotedToLayer()`。已实测复现两次，别再加。
     *
     * 默认 false，其它 17 处调用行为完全不变。
     */
    floating: Boolean = false,
    /**
     * 把动作图标包进一个圆角胶囊（只在 [floating] 时有意义）。
     *
     * 悬浮顶栏本身没有底色，图标直接浮在内容上会显得零散、也会被滚上来的
     * 文字干扰。包一层浅色胶囊后，视觉上收成一组，与内容分离。
     *
     * 导航图标（左侧汉堡）**不**包：它与内容区是两回事，单独放着更像系统顶栏。
     */
    actionsInCapsule: Boolean = false,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = MiuixTheme.colors
    val scrolledBackground by animateColorAsState(
        targetValue = if (scrolled) colors.surface else Color.Transparent,
        animationSpec = tween(MiuixTheme.motion.fast, easing = SinOutEasing),
        label = "topbar-bg",
    )
    // 悬浮形态**自己不画底色**，把这件事交给调用方。
    //
    // 原因：平铺一个半透明色会得到一条「硬边」——上边缘是实色、下边缘突然消失，
    // 看起来像一块盖上去的遮罩（用户反馈「只是遮着」）。要做成内容自然淡出的观感，
    // 需要一条**纵向渐变**（顶部实、底部透明），而这必须由调用方按自己的高度来画。
    val background = if (floating) Color.Transparent else scrolledBackground

    /**
     * 悬浮形态下的胶囊底色。
     *
     * 用**白色**（用户指定）：在浅色主题下比 `surfaceContainerHighest` 更亮、
     * 与页面背景拉开层次，胶囊边界一眼可见。
     *
     * 注意：暗色主题下白胶囊会显得很跳，届时应改用深色底（例如
     * `colors.surfaceContainerHighest`）。当前只在浅色主题下验收过。
     *
     * **不画描边**（用户指定）：白底本身已与背景拉开层次，再加一圈细线会显得
     * 边框感太重、像输入框。靠圆角与亮度差区分就够了。
     */
    val capsuleColor = Color.White

    /** 把内容包进一颗胶囊。只在悬浮形态下使用。 */
    @Composable
    fun capsule(content: @Composable RowScope.() -> Unit) {
        Row(
            modifier = Modifier
                .sqSurface(capsuleColor, SquircleRadii.card)
                .padding(horizontal = 2.dp, vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            content()
        }
    }

    val nav: @Composable () -> Unit = {
        if (navigationIconRes != null) {
            val icon: @Composable () -> Unit = {
                MiuixIconButton(
                    iconRes = navigationIconRes,
                    contentDescription = title,
                    onClick = onNavigationClick ?: {},
                    buttonSize = 44.dp,
                    iconSize = 22.dp,
                )
            }
            // 汉堡与右侧动作一样要收进胶囊：悬浮顶栏没有底色，图标直接浮在内容上
            // 会被滚上来的文字干扰（用户反馈「汉堡的没做」）。
            if (floating && actionsInCapsule) capsule { icon() } else icon()
        }
    }

    val shownTitle = if (showTitle) title else ""

    /**
     * 动作区。悬浮 + 要求胶囊时，所有动作图标收进**同一颗**胶囊
     * （终端与新建对话共用一个，不各占一颗）。
     */
    val actionsSlot: @Composable RowScope.() -> Unit = {
        val content: @Composable RowScope.() -> Unit = {
            actions?.invoke(this)
        }
        if (floating && actionsInCapsule) capsule(content) else content()
    }

    if (centeredTitle) {
        OfficialTopAppBar(
            title = shownTitle,
            modifier = modifier,
            color = background,
            titleColor = colors.onSurface,
            subtitle = subtitle,
            subtitleColor = colors.onSurfaceVariant,
            navigationIcon = nav,
            actions = actionsSlot,
        )
    } else {
        OfficialSmallTopAppBar(
            title = shownTitle,
            modifier = modifier,
            color = background,
            titleColor = colors.onSurface,
            subtitle = subtitle,
            subtitleColor = colors.onSurfaceVariant,
            navigationIcon = nav,
            actions = actionsSlot,
        )
    }
}

/** 顶栏动作区容器：官方 actions 槽位要求 RowScope，无动作时传空即可。 */
@Composable
fun MiuixTopAppBarActions(content: @Composable RowScope.() -> Unit) {
    Row { content() }
}
