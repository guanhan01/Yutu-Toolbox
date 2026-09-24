package com.mcp.toolbox.core.design.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.foundation.layout.PaddingValues
import com.mcp.toolbox.core.design.theme.UiStyle
import top.yukonga.miuix.kmp.basic.ListPopupColumn as OfficialListPopupColumn
import top.yukonga.miuix.kmp.basic.PopupPositionProvider as OfficialPopupPositionProvider
import top.yukonga.miuix.kmp.overlay.OverlayListPopup as OfficialOverlayListPopup
import com.mcp.toolbox.core.design.theme.MiuixTheme
import top.yukonga.miuix.kmp.anim.folmeSpring
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalConfiguration

/** 菜单内容宽度与外框左右内边距。定位预判与渲染必须共用同一组值，否则方向会算错。 */
private val MenuContentWidth = 200.dp
private val MenuEdgePadding = 8.dp

/** 溢出菜单：三点菜单的标准实现。 支持图标、危险项（红字）、复选态、分组分隔线，且不依赖 material3 的 DropdownMenu。 */
@Composable
fun MiuixOverflowMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    offset: IntOffset = IntOffset(0, 0),
    anchor: Rect? = null,
    /** 强制从锚点左边缘向右展开；用在锚点本身靠左的场景（例如输入栏的加号）。 */
    alignStart: Boolean = false,
    /**
     * 菜单底边贴住屏幕底部（留 [gapPx]）。
     *
     * 用在锚点就在屏幕底部的场景（如聊天输入栏的三个入口）：此时无法依赖
     * 锚点矩形计算位置，直接贴底反而稳定准确。
     */
    stickToBottom: Boolean = false,
    /**
     * 菜单是否抢输入焦点。默认 true；聊天输入栏的附件菜单要传 false，
     * 否则弹出时焦点离开输入框、输入法会被收起。
     */
    focusable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (MiuixTheme.config.uiStyle == UiStyle.MIUIX) {
        MiuixOverflowMenuOfficial(expanded, onDismiss, modifier, anchor, alignStart, stickToBottom, offset, content)
        return
    }
    val colors = MiuixTheme.colors
    val radius = MiuixTheme.radius
    val motion = MiuixTheme.motion
    // 进场稍长（有展开感），退场更短（不拖沓）。
    val exitMillis = motion.duration(170)
    val gapPx = with(LocalDensity.current) { 6.dp.roundToPx() }
    // Popup 外框总宽 = 内容宽 + 左右内边距，用来预判「右对齐放不放得下」。
    val boxWidthPx = with(LocalDensity.current) { (MenuContentWidth + MenuEdgePadding * 2).roundToPx() }
    // 贴锚点右边缘会溢出到屏幕左侧时，改成从锚点左边缘向右展开；
    // 缩放原点同步从右上角切到左上角，动画方向才和菜单实际位置一致。
    // 锚点靠左、或显式要求时，从左边缘向右展开，避免菜单甩到屏幕另一侧
    val expandToEnd = alignStart ||
        (anchor != null && anchor.right.roundToInt() - boxWidthPx < 0)
    // 向上展开（锚点在屏幕下半部，菜单让到上方）时，进场位移改为从下往上
    val growUpward = stickToBottom || (anchor != null && run {
        val screenH = LocalConfiguration.current.screenHeightDp.dp
        val anchorBottomDp = with(LocalDensity.current) { anchor.bottom.toDp() }
        anchorBottomDp > screenH * 0.6f
    })
    val originX = if (expandToEnd) 0f else 1f
    val positionProvider =
        remember(anchor, offset, gapPx, expandToEnd, stickToBottom) {
            OverflowMenuPositionProvider(anchor, offset, gapPx, expandToEnd, stickToBottom)
        }

    // Popup 必须活到退出动画播完，所以另用一个「是否还挂在屏幕上」的标志，不能在 expanded 变 false 时直接 return。
    var mounted by remember { mutableStateOf(expanded) }
    // 0->1 的动画进度：进场用带过冲的 spring（回弹就是「灵动」的来源），退场用 tween 干脆收回。
    // 连续开关时 animateTo 会从当前进度直接折返，所以中途打断也是平滑的，不会跳变。
    val progress = remember { Animatable(if (expanded) 1f else 0f) }
    LaunchedEffect(expanded) {
        if (expanded) {
            mounted = true
            withFrameNanos {}
            // 官方 Miuix 的弹出曲线：folmeSpring(damping = 0.5, response = 0.28)，
            // 与官方 ListPopup 的「灵动回弹」一致
            progress.animateTo(
                1f,
                folmeSpring(damping = 0.5f, response = 0.28f),
            )
        } else {
            progress.animateTo(0f, tween(exitMillis, easing = FastOutSlowInEasing))
            mounted = false
        }
    }
    if (!mounted) return

    val maxMenuHeight = LocalConfiguration.current.screenHeightDp.dp * 0.4f

    Popup(
        onDismissRequest = onDismiss,
        popupPositionProvider = positionProvider,
        properties = PopupProperties(focusable = focusable),
    ) {
        val p = progress.value
        val clamped = p.coerceIn(0f, 1f)
        Column(
            modifier =
                modifier
                    .graphicsLayer {
                        // 缩放原点固定在右上角：像从三点按钮里「长」出来，收起时再吸回去。
                        scaleX = 0.55f + 0.45f * p
                        scaleY = 0.55f + 0.45f * p
                        transformOrigin = TransformOrigin(originX, if (growUpward) 1f else 0f)
                        alpha = 0.25f + 0.75f * clamped
                        translationY = (1f - clamped) *
                            (if (growUpward) 16.dp.toPx() else -16.dp.toPx())
                    }
                    .padding(8.dp)
                    .shadow(MiuixTheme.dimens.elevation.level3, RoundedCornerShape(radius.field))
                    .clip(RoundedCornerShape(radius.field))
                    .background(colors.surfaceContainerHigh)
                    .padding(vertical = 6.dp)
                    .width(MenuContentWidth)
                    // 长菜单（如数据库排序 11 项）限制最大高度并可滚动，避免被屏幕裁掉半截。
                    .heightIn(max = maxMenuHeight)
                    .verticalScroll(rememberScrollState()),
            content = content,
        )
    }
}

/** 菜单项：前导图标 + 文案，danger = 红字，checked 显示复选状态。 */
@Composable
fun MiuixMenuItem(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    danger: Boolean = false,
    checked: Boolean? = null,
    trailingText: String? = null,
    enabled: Boolean = true,
) {
    val colors = MiuixTheme.colors
    val press = rememberMiuixPressState(enabled)
    val contentColor =
        when {
            !enabled -> colors.onSurfaceVariant.copy(alpha = 0.5f)
            danger -> colors.error
            else -> colors.onSurface
        }

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 40.dp)
                .miuixClickable(press, enabled, onClick = onClick)
                .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (icon != null) {
            MiuixIcon(icon, null, tint = contentColor, size = 18.dp)
        }
        MiuixText(
            text = text,
            style = MiuixTheme.typography.bodyMedium,
            color = contentColor,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        if (trailingText != null) {
            MiuixText(
                text = trailingText,
                style = MiuixTheme.typography.labelSmall,
                color = colors.onSurfaceVariant)
        }
        if (checked != null) {
            if (checked) {
                MiuixIcon(
                    Icons.Filled.Check,
                    null,
                    tint = colors.primary,
                    size = 16.dp,
                )
            } else {
                Spacer(Modifier.size(16.dp))
            }
        }
    }
}

@Composable
fun MiuixMenuDivider(modifier: Modifier = Modifier) {
    MiuixDivider(modifier = modifier.padding(vertical = 4.dp))
}

@Composable
fun MiuixMenuGroupLabel(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.padding(start = 14.dp, top = 8.dp, bottom = 4.dp)) {
        MiuixText(
            text = text,
            style = MiuixTheme.typography.labelSmall,
            color = MiuixTheme.colors.onSurfaceVariant,
        )
    }
}

/**
 * 菜单定位：右边缘对齐锚点右边缘、顶边落在锚点下方，并夹在窗口内（不会跑出屏幕）。
 *
 * anchor 传 null 时退回 Popup 的父布局矩形：只要调用点写成 `Box { 图标按钮; MiuixOverflowMenu(...) }`，
 * 父布局就是那颗按钮，菜单自然贴着按钮右下展开。传 anchor（窗口坐标）则用于菜单挂在顶层、 父布局是整个页面的场景——那种情况下默认会跑到屏幕左上角。
 */
private class OverflowMenuPositionProvider(
    private val anchor: Rect?,
    private val offset: IntOffset,
    private val gapPx: Int,
    private val expandToEnd: Boolean,
    private val stickToBottom: Boolean,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val anchorRect = anchor
        val anchorBottom = anchorRect?.bottom?.roundToInt()
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val maxY = (windowSize.height - popupContentSize.height).coerceAtLeast(0)
        val rawX =
            when {
                // 没有锚点：退回 Popup 父布局，仍然右对齐它的右边缘。
                anchorRect == null ->
                    if (expandToEnd) anchorBounds.left + offset.x
                    else anchorBounds.right - popupContentSize.width + offset.x
                // 右对齐会溢出左边：贴锚点左边缘向右展开，菜单落在按钮右下侧。
                expandToEnd -> anchorRect.left.roundToInt() + offset.x
                // 常规情况：右边缘对齐锚点右边缘。
                else -> anchorRect.right.roundToInt() - popupContentSize.width + offset.x
            }
        // 垂直自适应：优先贴锚点下方展开；下方放不下就改成「覆盖锚点」向上生长，
        // 让菜单底边贴住锚点底边，保证整份菜单都留在屏幕内，而不是顶到屏幕顶后被截掉下半截。
        val below = (anchorBottom ?: anchorBounds.top) + gapPx + offset.y
        val above = anchorRect?.let {
            // 向上展开：菜单底边落在锚点顶边上方，留出 gap，避免盖住按钮
            it.top.roundToInt() - popupContentSize.height - gapPx + offset.y
        }
        val top =
            when {
                // 贴底模式：底边固定在屏幕底部上方 gapPx 处
                stickToBottom -> (windowSize.height - popupContentSize.height - gapPx + offset.y)
                    .coerceAtLeast(0)

                below + popupContentSize.height <= windowSize.height -> below
                above != null -> above.coerceAtLeast(0)
                else -> below
            }
        return IntOffset(rawX.coerceIn(0, maxX), top.coerceIn(0, maxY))
    }
}

// ---------------- Miuix（官方 OverlayListPopup） ----------------

/**
 * Miuix 风格实现：官方 [OfficialOverlayListPopup]。
 *
 * 动画完全交给官方：scale 0.15→1 + 按弹出方向展开的 clip-reveal + alpha 渐变，
 * 以及官方的遮罩与回弹曲线。锚点仍复用本文件既有的 [Rect]（窗口坐标）。
 */
@Composable
private fun MiuixOverlayMenuBody(
    anchor: Rect?,
    alignStart: Boolean,
    offset: IntOffset,
    onDismiss: () -> Unit,
    expanded: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    // 官方 content 无接收者；包一层 Column 承载调用点的 ColumnScope 内容
    Column { content() }
}

private fun officialPositionProvider(
    anchor: Rect?,
    alignStart: Boolean,
    stickToBottom: Boolean,
    offset: IntOffset,
): OfficialPopupPositionProvider = object : OfficialPopupPositionProvider {
    override fun getMargins(): PaddingValues = PaddingValues(0.dp)

    override fun calculatePosition(
        anchorBounds: IntRect,
        windowBounds: IntRect,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
        popupMargin: IntRect,
        alignment: OfficialPopupPositionProvider.Align,
    ): IntOffset {
        val anchorRect = anchor
        val gapPx = 6
        val gap = gapPx + popupMargin.bottom
        val maxX = (windowBounds.width - popupContentSize.width).coerceAtLeast(0)
        val maxY = (windowBounds.height - popupContentSize.height).coerceAtLeast(0)

        val rawX = when {
            anchorRect == null ->
                if (alignStart) anchorBounds.left + offset.x
                else anchorBounds.right - popupContentSize.width + offset.x
            alignStart -> anchorRect.left.roundToInt() + offset.x
            else -> anchorRect.right.roundToInt() - popupContentSize.width + offset.x
        }
        val anchorBottom = anchorRect?.bottom?.roundToInt() ?: anchorBounds.top
        val below = anchorBottom + gap + offset.y
        val above = anchorRect?.let {
            it.top.roundToInt() - popupContentSize.height - gap + offset.y
        }
        val top = when {
            // 贴底模式：底边固定在窗口底部上方
            stickToBottom -> windowBounds.height - popupContentSize.height
            below + popupContentSize.height <= windowBounds.height -> below
            above != null -> above.coerceAtLeast(0)
            else -> below
        }
        return IntOffset(rawX.coerceIn(0, maxX), top.coerceIn(0, maxY))
    }
}

@Composable
private fun MiuixOverflowMenuOfficial(
    expanded: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier,
    anchor: Rect?,
    alignStart: Boolean,
    stickToBottom: Boolean,
    offset: IntOffset,
    content: @Composable ColumnScope.() -> Unit,
) {
    val provider = remember(anchor, alignStart, stickToBottom, offset) {
        officialPositionProvider(anchor, alignStart, stickToBottom, offset)
    }
    OfficialOverlayListPopup(
        show = expanded,
        popupModifier = modifier,
        popupPositionProvider = provider,
        alignment = if (alignStart) OfficialPopupPositionProvider.Align.Start
        else OfficialPopupPositionProvider.Align.End,
        onDismissRequest = onDismiss,
        // 菜单不压暗整屏（默认 true 会让整屏蒙黑）
        enableWindowDim = false,
        minWidth = 0.dp,
    ) {
        OfficialListPopupColumn {
            Column { content() }
        }
    }
}
