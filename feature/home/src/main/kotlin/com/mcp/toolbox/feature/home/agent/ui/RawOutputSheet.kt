package com.mcp.toolbox.feature.home.agent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mcp.toolbox.core.design.component.MiuixBottomSheet
import com.mcp.toolbox.core.design.component.MiuixButton
import com.mcp.toolbox.core.design.component.MiuixButtonVariant
import com.mcp.toolbox.core.design.component.MiuixIconRes
import com.mcp.toolbox.core.design.component.MiuixText
import com.mcp.toolbox.core.design.component.MiuixTextField
import com.mcp.toolbox.core.design.icon.AgentIcons
import com.mcp.toolbox.core.design.shape.SquircleRadii
import com.mcp.toolbox.core.design.shape.sqSurface
import com.mcp.toolbox.core.design.theme.MiuixTheme

/**
 * 原始输出的完整面板（Spec 第 7.2 节 L4）。
 *
 * ## 为什么需要它
 *
 * 工具结果在卡片里只给 400 字预览——完整输出动辄几百行 JSON，
 * 直接铺进消息流会把一条回答撑成上千 dp 的滚动噩梦。
 * 但用户有时**确实需要原文**（拷给同事、贴进 issue），所以给一个独立出口。
 *
 * ## 为什么带搜索
 *
 * 真实场景里找的是**某个字段**（"sizeBytes 是多少"），而不是从头读一遍。
 * 有搜索框就能一步到位；没有的话用户只能用眼睛在几百行里扫。
 *
 * ## 有意不做的事
 *
 * 不做语法高亮：这里的正文是**任意文本**（JSON、日志、表格输出都可能），
 * 猜错语法比不高亮更让人困惑。等宽字体 + 保留缩进已经够读。
 */
@Composable
fun RawOutputSheet(
    title: String,
    raw: String,
    onDismiss: () -> Unit,
) {
    val colors = MiuixTheme.colors
    var query by remember { mutableStateOf("") }
    var copied by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    // 过滤是纯字符串包含，不解析结构：用户输入的多半是字段名或关键词
    val shown = remember(raw, query) {
        val q = query.trim()
        if (q.isEmpty()) {
            raw
        } else {
            raw.lineSequence()
                .filter { it.contains(q, ignoreCase = true) }
                .joinToString("\n")
        }
    }
    val lineCount = remember(raw) { raw.count { it == '\n' } + 1 }
    val shownLines = remember(shown) { if (shown.isEmpty()) 0 else shown.count { it == '\n' } + 1 }

    MiuixBottomSheet(visible = true, onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MiuixTheme.dimens.spacing.pageHorizontal, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // ── 标题行：工具名 + 行数，右侧复制 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    MiuixText(
                        text = title,
                        style = MiuixTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    MiuixText(
                        text = if (query.isBlank()) "$lineCount 行" else "匹配 $shownLines / $lineCount 行",
                        style = MiuixTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                MiuixButton(
                    text = if (copied) "已复制" else "复制",
                    variant = MiuixButtonVariant.OUTLINED,
                    // 复制的是**完整原文**，不是过滤后的——过滤只是为了看
                    onClick = {
                        clipboard.setText(AnnotatedString(raw))
                        copied = true
                    },
                )
            }

            // ── 搜索 ──
            MiuixTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = "在输出里查找…",
                leadingIconRes = AgentIcons.ic_search,
            )

            // ── 正文：可选中（走系统选择菜单，不自建复制浮层）──
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 420.dp)
                    .verticalScroll(rememberScrollState())
                    .sqSurface(colors.surfaceContainerHigh, SquircleRadii.inner)
                    .padding(10.dp),
            ) {
                if (shown.isEmpty()) {
                    MiuixText(
                        text = if (query.isBlank()) "（无输出）" else "没有匹配「$query」的行",
                        style = MiuixTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                } else {
                    SelectionContainer {
                        MiuixText(
                            text = shown,
                            style = MiuixTheme.typography.code,
                            color = colors.onSurface,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 来源清单面板（Spec 第 7.4 节）。
 *
 * 卡片里最多放 5 个 chip，超出时「查看全部 N 条」打开这里。
 * 每条给 favicon 位、标题、域名与摘要，点击交给宿主决定怎么打开
 * （应用侧有内嵌浏览器，这里不直接持有它）。
 */
@Composable
fun SourcesSheet(
    sources: List<com.mcp.toolbox.feature.home.agent.WebSource>,
    onOpen: (url: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MiuixTheme.colors
    MiuixBottomSheet(visible = true, onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MiuixTheme.dimens.spacing.pageHorizontal, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MiuixText(
                text = "来源（${sources.size}）",
                style = MiuixTheme.typography.titleMedium,
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                sources.forEach { source ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .sqSurface(colors.surfaceContainerHigh, SquircleRadii.inner)
                            .padding(10.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MiuixIconRes(
                            iconRes = AgentIcons.ic_globe,
                            contentDescription = null,
                            tint = colors.onSurfaceVariant,
                            size = 16.dp,
                        )
                        Column(Modifier.weight(1f)) {
                            MiuixText(
                                text = source.title.ifBlank { source.url },
                                style = MiuixTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            MiuixText(
                                text = source.domain.ifBlank { source.url },
                                style = MiuixTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (source.snippet.isNotBlank()) {
                                MiuixText(
                                    text = source.snippet,
                                    style = MiuixTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        MiuixButton(
                            text = "打开",
                            variant = MiuixButtonVariant.OUTLINED,
                            onClick = { onOpen(source.url) },
                        )
                    }
                }
            }
        }
    }
}
