package com.mcp.toolbox.core.design.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mcp.toolbox.core.design.theme.MiuixTheme

/** 环形进度：既是独立进度指示器，也用于按钮内嵌加载态。 */
@Composable
fun MiuixCircularProgress(
    size: Dp = 24.dp,
    color: Color = Color.Unspecified,
    strokeWidth: Dp = 3.dp,
    trackColor: Color = Color.Unspecified,
) {
    val resolved = if (color == Color.Unspecified) MiuixTheme.colors.primary else color
    val resolvedTrack = if (trackColor == Color.Unspecified) Color.Transparent else trackColor
    val transition = rememberInfiniteTransition(label = "miuix-circular-progress")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = MiuixTheme.motion.loop, easing = LinearEasing), RepeatMode.Restart),
        label = "rotation",
    )
    Canvas(modifier = Modifier.size(size)) {
        val stroke = strokeWidth.toPx()
        val inset = stroke / 2f
        val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
        if (resolvedTrack != Color.Transparent) {
            drawArc(
                color = resolvedTrack,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        drawArc(
            color = resolved,
            startAngle = rotation,
            sweepAngle = 270f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

/** 线性进度：轨道全圆角，符合设计稿 Sliders/Feedback 区形态。 */
@Composable
fun MiuixLinearProgress(
    progress: Float,
    modifier: Modifier = Modifier,
    height: Dp = 6.dp,
    color: Color = Color.Unspecified,
    trackColor: Color = Color.Unspecified,
) {
    val colors = MiuixTheme.colors
    val resolved = if (color == Color.Unspecified) colors.primary else color
    val resolvedTrack = if (trackColor == Color.Unspecified) colors.surfaceContainerHighest else trackColor
    val shape = RoundedCornerShape(percent = 50)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(shape)
            .background(resolvedTrack),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(height)
                .clip(shape)
                .background(resolved),
        )
    }
}
