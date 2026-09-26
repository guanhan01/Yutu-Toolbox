package com.mcp.toolbox.core.design.component

import androidx.compose.animation.core.Animatable
import kotlinx.coroutines.launch
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
import androidx.compose.runtime.DisposableEffect
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
    MiuixOverflowMenuOfficial(expanded, onDismiss, modifier, anchor, alignStart, stickToBottom, focusable, offset, content)
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

@Composable
private fun MiuixOverflowMenuOfficial(
    expanded: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier,
    anchor: Rect?,
    alignStart: Boolean,
    stickToBottom: Boolean,
    focusable: Boolean,
    offset: IntOffset,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MiuixTheme.colors
    val radius = MiuixTheme.radius
    val gapPx = with(LocalDensity.current) { 6.dp.roundToPx() }
    val boxWidthPx = with(LocalDensity.current) { (MenuContentWidth + MenuEdgePadding * 2).roundToPx() }

    val expandToEnd = alignStart ||
        (anchor != null && anchor.right.roundToInt() - boxWidthPx < 0)
    val growUpward = stickToBottom || (anchor != null && run {
        val screenH = LocalConfiguration.current.screenHeightDp.dp
        val anchorBottomDp = with(LocalDensity.current) { anchor.bottom.toDp() }
        anchorBottomDp > screenH * 0.6f
    })
    val originX = if (expandToEnd) 0f else 1f

    val positionProvider = remember(anchor, offset, gapPx, expandToEnd, stickToBottom) {
        OverflowMenuPositionProvider(anchor, offset, gapPx, expandToEnd, stickToBottom)
    }

    var mounted by remember { mutableStateOf(expanded) }
    val fraction = remember { Animatable(if (expanded) 1f else 0f) }
    val alphaAnim = remember { Animatable(if (expanded) 1f else 0f) }

    // 「再点一次按钮」时序问题：点击时 Popup 先把这次点击当作「点外部」触发 onDismiss，
    // 紧接着按钮的 onClick 又把 expanded 置回 true，于是菜单看起来是重新弹出而非收回。
    // 记下最近一次关闭时刻，短时间内到达的 true 视为同一次点击，直接忽略。
    val lastDismissAt = remember { longArrayOf(0L) }
    val suppressed = remember(expanded) {
        if (expanded) System.currentTimeMillis() - lastDismissAt[0] < 350L else false
    }
    val shown = expanded && !suppressed

    // 页面被销毁（例如侧滑返回）时，若菜单还开着就主动收回，
    // 否则弹层会跟着 Popup 残留在屏幕上。
    DisposableEffect(Unit) {
        onDispose { if (expanded) onDismiss() }
    }

    LaunchedEffect(expanded) {
        if (expanded) {
            mounted = true
            withFrameNanos {}
            launch { alphaAnim.animateTo(1f, tween(200)) }
            fraction.animateTo(
                1f,
                spring(dampingRatio = 0.82f, stiffness = 362.5f, visibilityThreshold = 0.0001f),
            )
        } else {
            if (expanded) lastDismissAt[0] = System.currentTimeMillis()
            launch { alphaAnim.animateTo(0f, tween(150)) }
            fraction.animateTo(0f, tween(170))
            mounted = false
        }
    }
    if (!mounted) return

    val maxMenuHeight = LocalConfiguration.current.screenHeightDp.dp * 0.4f

    Popup(
        onDismissRequest = { lastDismissAt[0] = System.currentTimeMillis(); onDismiss() },
        popupPositionProvider = positionProvider,
        properties = PopupProperties(focusable = focusable),
    ) {
        val f = fraction.value.coerceIn(0f, 1f)
        Column(
            modifier = modifier
                .graphicsLayer {
                    // 官方：scale = 0.15 + 0.85 * fraction
                    val sc = 0.15f + 0.85f * f
                    scaleX = sc
                    scaleY = sc
                    alpha = alphaAnim.value.coerceIn(0f, 1f)
                    // 缩放原点落在锚点那一角：菜单像从图标处长出来
                    transformOrigin = TransformOrigin(originX, if (growUpward) 1f else 0f)
                }
                .padding(8.dp)
                .shadow(MiuixTheme.dimens.elevation.level3, RoundedCornerShape(MiuixTheme.radius.menu))
                .clip(RoundedCornerShape(MiuixTheme.radius.menu))
                .background(colors.surfaceContainerHigh)
                .padding(vertical = 6.dp)
                .width(MenuContentWidth)
                .heightIn(max = maxMenuHeight)
                .verticalScroll(rememberScrollState()),
            content = content,
        )
    }
}
