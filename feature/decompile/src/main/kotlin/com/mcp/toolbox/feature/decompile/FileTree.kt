package com.mcp.toolbox.feature.decompile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mcp.toolbox.core.design.component.MiuixIcon
import com.mcp.toolbox.core.design.component.MiuixSearchField
import com.mcp.toolbox.core.design.component.MiuixText
import com.mcp.toolbox.core.design.component.miuixClickable
import com.mcp.toolbox.core.design.component.rememberMiuixPressState
import com.mcp.toolbox.core.design.theme.MiuixTheme

/** 文件树节点：目录带子节点，文件是叶子。 */
class TreeItem(
    val name: String,
    /** 相对根目录的路径；目录也带路径，用来记录展开状态。 */
    val path: String,
    val isDirectory: Boolean,
) {
    val children: MutableList<TreeItem> = mutableListOf()
}

/** 展平后的一行：节点 + 缩进层级。 */
data class TreeRow(val node: TreeItem, val depth: Int)

/** 把相对路径列表折成一棵树：目录优先、同级按名字排序。 */
fun buildFileTree(paths: List<String>): List<TreeItem> {
    val root = TreeItem("", "", true)
    paths.forEach { raw ->
        val parts = raw.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return@forEach
        var cursor = root
        parts.forEachIndexed { index, part ->
            val leaf = index == parts.lastIndex
            val hit = cursor.children.firstOrNull { it.name == part && it.isDirectory != leaf }
            cursor = hit ?: TreeItem(
                name = part,
                path = parts.take(index + 1).joinToString("/"),
                isDirectory = !leaf,
            ).also { cursor.children.add(it) }
        }
    }
    sortTree(root)
    return root.children
}

private fun sortTree(node: TreeItem) {
    node.children.sortWith(compareByDescending<TreeItem> { it.isDirectory }.thenBy { it.name.lowercase() })
    node.children.forEach(::sortTree)
}

/** 收集全部目录路径，供「搜索时整棵树展开」使用。 */
fun allDirectoryPaths(nodes: List<TreeItem>, out: MutableSet<String> = mutableSetOf()): Set<String> {
    nodes.forEach { node ->
        if (node.isDirectory) {
            out.add(node.path)
            allDirectoryPaths(node.children, out)
        }
    }
    return out
}

/**
 * 按展开状态把树展平成可见行。
 *
 * 只把「当前可见」的那些节点交给 LazyColumn，
 * 所以哪怕有上万个类文件，实际渲染的也只有屏幕上那几十行。
 */
fun flattenFileTree(
    nodes: List<TreeItem>,
    expanded: Set<String>,
    depth: Int = 0,
    out: MutableList<TreeRow> = mutableListOf(),
): List<TreeRow> {
    nodes.forEach { node ->
        out.add(TreeRow(node, depth))
        if (node.isDirectory && node.path in expanded) {
            flattenFileTree(node.children, expanded, depth + 1, out)
        }
    }
    return out
}

/**
 * 左侧文件树面板：搜索框 + 可展开折叠的目录树。
 *
 * 点目录只切换展开状态，点文件才交给 [onSelect]，
 * 这样主从结构不会因为误点一个目录就跳走右侧内容。
 */
@Composable
fun FileTreePanel(
    rows: List<TreeRow>,
    expanded: Set<String>,
    query: String,
    onQuery: (String) -> Unit,
    selected: String?,
    onToggle: (String) -> Unit,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    Column(modifier.background(colors.surfaceContainerLow)) {
        Box(Modifier.padding(horizontal = spacing.sm, vertical = spacing.sm)) {
            MiuixSearchField(value = query, onValueChange = onQuery, placeholder = "筛选")
        }
        if (rows.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(spacing.md)) {
                MiuixText(
                    text = if (query.isBlank()) "没有可显示的源文件" else "没有匹配的文件",
                    style = MiuixTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
            }
            return
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(rows, key = { it.node.path }) { row ->
                FileTreeRow(
                    row = row,
                    isSelected = row.node.path == selected,
                    isExpanded = row.node.path in expanded,
                    onToggle = onToggle,
                    onSelect = onSelect,
                )
            }
        }
    }
}

@Composable
private fun FileTreeRow(
    row: TreeRow,
    isSelected: Boolean,
    isExpanded: Boolean,
    onToggle: (String) -> Unit,
    onSelect: (String) -> Unit,
) {
    val colors = MiuixTheme.colors
    val node = row.node
    val press = rememberMiuixPressState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 1.dp)
            .background(
                if (isSelected) colors.primaryContainer.copy(alpha = 0.45f) else Color.Transparent,
                RoundedCornerShape(MiuixTheme.radius.sm),
            )
            .miuixClickable(press, true) {
                if (node.isDirectory) onToggle(node.path) else onSelect(node.path)
            }
            .padding(start = (4 + row.depth * 12).dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MiuixIcon(
            icon = when {
                !node.isDirectory -> Icons.Outlined.Code
                isExpanded -> Icons.Outlined.FolderOpen
                else -> Icons.Outlined.Folder
            },
            contentDescription = null,
            tint = if (isSelected) colors.primary else colors.onSurfaceVariant,
            size = 15.dp,
        )
        Spacer(Modifier.width(6.dp))
        MiuixText(
            text = node.name,
            modifier = Modifier.weight(1f),
            style = MiuixTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = if (isSelected) colors.primary else colors.onSurface,
            maxLines = 1,
        )
    }
}
