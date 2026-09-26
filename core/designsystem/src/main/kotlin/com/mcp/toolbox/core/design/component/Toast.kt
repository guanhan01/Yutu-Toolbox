package com.mcp.toolbox.core.design.component

import android.view.Gravity
import android.view.View
import android.view.ViewParent
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.mcp.toolbox.core.design.theme.MiuixTheme
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Snackbar as OfficialSnackbar
import top.yukonga.miuix.kmp.basic.SnackbarColors as OfficialSnackbarColors
import top.yukonga.miuix.kmp.basic.SnackbarData as OfficialSnackbarData
import top.yukonga.miuix.kmp.basic.SnackbarDuration as OfficialSnackbarDuration
import top.yukonga.miuix.kmp.basic.SnackbarVisuals as OfficialSnackbarVisuals

enum class MiuixToastTone {
    NEUTRAL,
    SUCCESS,
    ERROR,
    WARNING
}

data class MiuixToastMessage(val id: Long, val text: String, val tone: MiuixToastTone)

/**
 * 顶部胶囊提示。
 *
 * 视觉走官方 [OfficialSnackbar]（官方圆角、内边距、颜色角色），
 * 仅保留本站必需的一层「窗口处理」：把承载窗口做成无遮罩、不吃触摸、不抢焦点，
 * 否则系统给 Dialog 窗口的默认 dim 会把整屏压暗，且会挡住底部 Sheet。
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

/** 官方 Snackbar 需要的数据载体：本站只用来展示文案，动作一律为空。 */
private class MiuixToastSnackbarData(override val visuals: OfficialSnackbarVisuals) : OfficialSnackbarData {
    override suspend fun dismiss() = Unit
    override suspend fun performAction() = Unit
}

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

    // 语气色改由「底色」表达 —— 官方 Snackbar 没有前导圆点，用容器色区分语义更贴官方形态。
    val tint = when (lastTone) {
        MiuixToastTone.SUCCESS -> colors.success
        MiuixToastTone.ERROR -> colors.error
        MiuixToastTone.WARNING -> colors.warning
        MiuixToastTone.NEUTRAL -> colors.primary
    }
    val snackbarColors = OfficialSnackbarColors(
        containerColor = tint.copy(alpha = 0.18f).compositeOver(colors.surfaceContainerHigh),
        contentColor = colors.onSurface,
        actionContentColor = colors.onSurfaceVariant,
        dismissActionContentColor = colors.onSurfaceVariant,
    )
    val data = MiuixToastSnackbarData(
        OfficialSnackbarVisuals(
            message = lastText,
            actionLabel = null,
            withDismissAction = false,
            duration = OfficialSnackbarDuration.Short,
        ),
    )

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
            Box(
                modifier =
                    modifier
                        .padding(top = 14.dp)
                        .graphicsLayer {
                            // p: 0 = 收成一个点并飞出顶部，1 = 完整提示条
                            val scale = 0.06f + 0.94f * p
                            scaleX = scale
                            scaleY = scale
                            transformOrigin = TransformOrigin(0.5f, 0.5f)
                            translationY = -(1f - p) * 300.dp.toPx()
                            alpha = (p / 0.14f).coerceIn(0f, 1f)
                        },
            ) {
                OfficialSnackbar(
                    data = data,
                    cornerRadius = MiuixTheme.radius.composer,
                    colors = snackbarColors,
                )
            }
        }
    }
}

/** 让半透明语气色叠在不透明底色之上，避免穿透看到后面的内容。 */
private fun Color.compositeOver(background: Color): Color {
    val a = alpha
    return Color(
        red = red * a + background.red * (1 - a),
        green = green * a + background.green * (1 - a),
        blue = blue * a + background.blue * (1 - a),
        alpha = 1f,
    )
}
