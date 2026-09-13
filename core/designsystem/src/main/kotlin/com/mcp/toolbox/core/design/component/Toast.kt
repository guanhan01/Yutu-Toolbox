package com.mcp.toolbox.core.design.component

import android.view.Gravity
import android.view.View
import android.view.ViewParent
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.mcp.toolbox.core.design.theme.MiuixTheme
import kotlinx.coroutines.delay

enum class MiuixToastTone {
    NEUTRAL,
    SUCCESS,
    ERROR,
    WARNING
}

data class MiuixToastMessage(val id: Long, val text: String, val tone: MiuixToastTone)

/**
 * 顶部胶囊 Toast：设计稿反编译 / 数据库 / 抓包页统一的提示形态。 由 Shell 顶层放置 [MiuixToastHost]，页面通过 [MiuixToastState.show]
 * 触发。
 */
@Stable
class MiuixToastState {

    private val current = mutableStateOf<MiuixToastMessage?>(null)

    val message: MiuixToastMessage?
        get() = current.value

    fun show(text: String, tone: MiuixToastTone = MiuixToastTone.NEUTRAL) {
        current.value = MiuixToastMessage(System.currentTimeMillis(), text, tone)
    }

    fun dismiss() {
        current.value = null
    }

    internal fun dismissIf(id: Long) {
        if (current.value?.id == id) current.value = null
    }
}

@Composable fun rememberMiuixToastState(): MiuixToastState = remember { MiuixToastState() }

/**
 * 把提示条所在 Dialog 窗口调成“无遮罩、全屏、不吃触摸”的状态。
 *
 * 关键点是系统给 Dialog 窗口默认带一层 dim（半透明黑，铺满整屏连状态栏一起压暗），
 * 必须显式清掉 FLAG_DIM_BEHIND，否则提示显示期间整屏发灰。
 */
private fun applyToastWindowParams(view: View) {
    var parent: ViewParent? = view.parent
    while (parent != null) {
        if (parent is DialogWindowProvider) {
            val w = parent.window
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.setDimAmount(0f)
            w.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
            )
            w.setGravity(Gravity.TOP or Gravity.START)
            w.addFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            )
            return
        }
        parent = (parent as? View)?.parent
    }
}

/**
 * 提示条宿主（灵动岛形态）。
 *
 * 之前用 `Popup` 挂：Popup 是依附主窗口的子窗口，层级比底部 Sheet（独立 Dialog 窗口）低，
 * 所以弹窗打开时提示条会被 Sheet 的半透明遮罩压暗。现在改成：宿主本身是一个独立 Dialog 窗口，
 * 并且只在需要显示时才创建 —— 后创建的窗口一定盖在已经打开的 Sheet 之上，遮罩压不到它。
 *
 * 窗口参数必须在 Dialog 的 View 真正 attach 之后才拿得到，而 Composable 的 `LaunchedEffect`
 * 首次执行时 View 往往还没 attach（跟着帧回调重试也只有很小的时间窗），这会让 dim 时有时无。
 * 因此这里以 `OnAttachStateChangeListener` 为准（attach 发生在窗口首帧绘制之前，不会闪），
 * 另加若干帧的兜底重试。
 *
 * 动效：出现时从屏幕顶部一个小圆点弹落并展开成胶囊（带回弹），收起时整体收缩成一个圆点、同时
 * 向上飞出屏幕顶部。
 */
@Composable
fun MiuixToastHost(state: MiuixToastState, modifier: Modifier = Modifier) {
    val colors = MiuixTheme.colors
    val current = state.message

    LaunchedEffect(current?.id) {
        val id = current?.id ?: return@LaunchedEffect
        delay(2200)
        state.dismissIf(id)
    }

    // 退场时 message 已经变成 null，所以要自己记住最后一次要显示的内容。
    var lastText by remember { mutableStateOf("") }
    var lastTone by remember { mutableStateOf(MiuixToastTone.NEUTRAL) }
    if (current != null) {
        lastText = current.text
        lastTone = current.tone
    }

    var mounted by remember { mutableStateOf(current != null) }
    val progress = remember { Animatable(if (current != null) 1f else 0f) }
    LaunchedEffect(current != null) {
        if (current != null) {
            mounted = true
            withFrameNanos { }
            progress.animateTo(
                1f,
                spring(dampingRatio = 0.52f, stiffness = Spring.StiffnessMedium),
            )
        } else {
            progress.animateTo(
                0f,
                spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow),
            )
            mounted = false
        }
    }
    if (!mounted) return

    val tint =
        when (lastTone) {
            MiuixToastTone.SUCCESS -> colors.success
            MiuixToastTone.ERROR -> colors.error
            MiuixToastTone.WARNING -> colors.warning
            MiuixToastTone.NEUTRAL -> colors.primary
        }

    Dialog(
        onDismissRequest = {},
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
            ),
    ) {
        val dialogView = LocalView.current
        DisposableEffect(dialogView) {
            val listener =
                object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {
                        applyToastWindowParams(v)
                    }

                    override fun onViewDetachedFromWindow(v: View) {}
                }
            dialogView.addOnAttachStateChangeListener(listener)
            if (dialogView.isAttachedToWindow) {
                applyToastWindowParams(dialogView)
            }
            onDispose { dialogView.removeOnAttachStateChangeListener(listener) }
        }
        LaunchedEffect(dialogView) {
            repeat(10) {
                applyToastWindowParams(dialogView)
                withFrameNanos { }
            }
        }

        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            val p = progress.value.coerceIn(0f, 1f)
            Row(
                modifier =
                    modifier
                        .padding(top = 14.dp)
                        .graphicsLayer {
                            // p: 0 = 收成一个圆点并飞出顶部，1 = 完整胶囊
                            val scale = 0.06f + 0.94f * p
                            scaleX = scale
                            scaleY = scale
                            transformOrigin = TransformOrigin(0.5f, 0.5f)
                            translationY = -(1f - p) * 300.dp.toPx()
                            alpha = (p / 0.14f).coerceIn(0f, 1f)
                        }
                        .shadow(MiuixTheme.dimens.elevation.level3, CircleShape)
                        .clip(CircleShape)
                        .background(colors.surfaceContainerHigh)
                        .border(1.dp, tint.copy(alpha = 0.6f), CircleShape)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(tint))
                Spacer(Modifier.width(10.dp))
                MiuixText(
                    text = lastText,
                    style = MiuixTheme.typography.labelLarge,
                    color = colors.onSurface,
                    maxLines = 2,
                )
            }
        }
    }
}
