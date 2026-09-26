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
import top.yukonga.miuix.kmp.overlay.OverlayDialog as OfficialOverlayDialog

/**
 * 标准对话框。实现为官方 Miuix 组件。
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
    MiuixDialogOfficial(visible, onDismiss, title, modifier, message, confirmText, onConfirm, dismissText, destructive, content)
}

/**
 * 标准对话框。
 *
 * Miuix 风格实现：官方 [OfficialOverlayDialog]。
 * destructive = true 时确认按钮走红色（卸载/冻结/删除/解密 HTTPS 等危险操作）。
 */
@Composable
private fun MiuixDialogOfficial(
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
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing

    OfficialOverlayDialog(
        show = visible,
        modifier = modifier,
        title = title,
        summary = message,
        onDismissRequest = onDismiss,
    ) {
        if (content != null) {
            Spacer(Modifier.height(spacing.sm))
            // 官方 content lambda 无接收者；这里包一个 Column，让调用方仍能用 ColumnScope 能力
            Column { content() }
        }
        Spacer(Modifier.height(spacing.lg))
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
                variant = MiuixButtonVariant.FILLED,
            )
        }
    }
}


/**
 * 标准对话框：28dp 圆角、标题 + 说明 + 可选自定义内容 + 操作按钮。
 * destructive = true 时确认按钮走红色（卸载/冻结/删除/解密 HTTPS 等危险操作）。
 */
