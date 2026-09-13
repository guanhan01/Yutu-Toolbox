package com.mcp.toolbox.core.design.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mcp.toolbox.core.design.theme.MiuixTheme
import kotlinx.coroutines.launch

/**
 * 底部 Sheet：顶部 28dp 圆角 + 拖拽把手，用于抓包统计、危险操作二次确认、MCP 工具运行参数等场景。
 *
 * 动效：进场是带一点过冲的弹性上滑，退场是下滑 + 淡出（遮罩同步淡出）。Dialog 一关就没机会播退场， 所以这里自己记一个「还挂在屏幕上」的状态：点遮罩或按返回先播完收回动画，再回调
 * onDismiss。
 */
@Composable
fun MiuixBottomSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MiuixTheme.colors
    val radius = MiuixTheme.radius
    val scope = rememberCoroutineScope()

    var mounted by remember { mutableStateOf(visible) }
    val progress = remember { Animatable(if (visible) 1f else 0f) }
    LaunchedEffect(visible) {
        if (visible) {
            mounted = true
            // 等 Dialog 真正上屏一帧，避免窗口创建吃掉动画开头。
            withFrameNanos {}
            progress.animateTo(
                1f,
                spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
            )
        } else {
            progress.animateTo(0f, tween(200, easing = FastOutSlowInEasing))
            mounted = false
        }
    }
    if (!mounted) return

    // 关闭路径统一先播收回动画：否则点遮罩会瞬间消失，看上去像「没有动画」。
    val dismissWithMotion: () -> Unit = {
        scope.launch {
            progress.animateTo(0f, tween(200, easing = FastOutSlowInEasing))
            onDismiss()
        }
        Unit
    }

    Dialog(
        onDismissRequest = dismissWithMotion,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val p = progress.value.coerceIn(0f, 1f)
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f * p)),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                modifier =
                    modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            // 从屏幕下方滑上来；退场原路滑回去，并同步淡出。
                            translationY = (1f - p) * size.height
                            alpha = p
                        }
                        .clip(RoundedCornerShape(topStart = radius.sheet, topEnd = radius.sheet))
                        .background(colors.surfaceContainerLow)
                        .navigationBarsPadding()
                        .padding(bottom = 8.dp),
            ) {
                Spacer(Modifier.height(10.dp))
                Box(
                    modifier =
                        Modifier.align(Alignment.CenterHorizontally)
                            .size(width = 36.dp, height = 4.dp)
                            .clip(RoundedCornerShape(percent = 50))
                            .background(colors.outlineVariant),
                )
                Spacer(Modifier.height(12.dp))
                content()
            }
        }
    }
}
