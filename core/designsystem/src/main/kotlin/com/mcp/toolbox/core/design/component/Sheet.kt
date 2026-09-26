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
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet as OfficialOverlayBottomSheet
import kotlinx.coroutines.launch

/**
 * 底部 Sheet。实现为官方 Miuix 组件。
 */
@Composable
fun MiuixBottomSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    MiuixBottomSheetOfficial(visible, onDismiss, modifier, content)
}

/**
 * 底部 Sheet。
 * Miuix 风格实现：官方 [OfficialOverlayBottomSheet]。
 */
@Composable
private fun MiuixBottomSheetOfficial(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val spacing = MiuixTheme.dimens.spacing

    OfficialOverlayBottomSheet(
        show = visible,
        modifier = modifier,
        // 官方默认 28dp 固定值；这里接项目 token，跟随主题圆角滑杆
        cornerRadius = MiuixTheme.radius.sheet,
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
        ) {
            content()
        }
    }
}


/**
 * 底部 Sheet：顶部 28dp 圆角 + 拖拽把手，用于抓包统计、危险操作二次确认、MCP 工具运行参数等场景。
 *
 * 动效：进场是带一点过冲的弹性上滑，退场是下滑 + 淡出（遮罩同步淡出）。Dialog 一关就没机会播退场， 所以这里自己记一个「还挂在屏幕上」的状态：点遮罩或按返回先播完收回动画，再回调
 * onDismiss。
 */
