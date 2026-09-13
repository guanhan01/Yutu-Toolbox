package com.mcp.toolbox.core.design.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mcp.toolbox.core.design.theme.MiuixTheme

/**
 * 标准对话框：28dp 圆角、标题 + 说明 + 可选自定义内容 + 操作按钮。
 * destructive = true 时确认按钮走红色（卸载/冻结/删除/解密 HTTPS 等危险操作）。
 */
@Composable
fun MiuixDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    confirmText: String = "确定",
    onConfirm: () -> Unit = {},
    dismissText: String? = "取消",
    destructive: Boolean = false,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    if (!visible) return
    val colors = MiuixTheme.colors
    val radius = MiuixTheme.radius

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(modifier = Modifier.padding(horizontal = 32.dp)) {
            Column(
                modifier = modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(radius.dialog))
                    .background(colors.surfaceContainerHigh)
                    .padding(24.dp),
            ) {
                MiuixText(text = title, style = MiuixTheme.typography.titleLarge)
                if (message != null) {
                    Spacer(Modifier.height(8.dp))
                    MiuixText(
                        text = message,
                        style = MiuixTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
                if (content != null) {
                    Spacer(Modifier.height(16.dp))
                    content()
                }
                Spacer(Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (dismissText != null) {
                        MiuixButton(
                            text = dismissText,
                            onClick = onDismiss,
                            variant = MiuixButtonVariant.TEXT,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    MiuixButton(
                        text = confirmText,
                        onClick = {
                            onConfirm()
                            onDismiss()
                        },
                        variant = if (destructive) MiuixButtonVariant.FILLED else MiuixButtonVariant.FILLED,
                    )
                }
            }
        }
    }
}
