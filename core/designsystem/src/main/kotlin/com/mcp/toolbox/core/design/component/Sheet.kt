package com.mcp.toolbox.core.design.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mcp.toolbox.core.design.theme.MiuixTheme
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet as OfficialOverlayBottomSheet

/**
 * 底部 Sheet。
 *
 * 内部改用官方 Miuix [OfficialOverlayBottomSheet]：官方上滑进场 / 下滑退场动画、
 * 拖拽把手、下拉与返回关闭、遮罩同步淡出全部由官方处理；外层 API 与内容排版保持兼容。
 */
@Composable
fun MiuixBottomSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val spacing = MiuixTheme.dimens.spacing

    OfficialOverlayBottomSheet(
        show = visible,
        modifier = modifier,
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
