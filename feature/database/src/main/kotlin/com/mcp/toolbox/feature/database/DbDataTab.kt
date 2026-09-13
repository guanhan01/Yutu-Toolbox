package com.mcp.toolbox.feature.database

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.ViewColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mcp.toolbox.core.design.component.MiuixButton
import com.mcp.toolbox.core.design.component.MiuixButtonVariant
import com.mcp.toolbox.core.design.component.MiuixDialog
import com.mcp.toolbox.core.design.component.MiuixEmptyState
import com.mcp.toolbox.core.design.component.MiuixFilterChip
import com.mcp.toolbox.core.design.component.MiuixIconButton
import com.mcp.toolbox.core.design.component.MiuixMenuDivider
import com.mcp.toolbox.core.design.component.MiuixMenuItem
import com.mcp.toolbox.core.design.component.MiuixOverflowMenu
import com.mcp.toolbox.core.design.component.MiuixTag
import com.mcp.toolbox.core.design.component.MiuixText
import com.mcp.toolbox.core.design.component.MiuixTextField
import com.mcp.toolbox.core.design.component.miuixClickable
import com.mcp.toolbox.core.design.component.rememberMiuixPressState
import com.mcp.toolbox.core.design.theme.MiuixTheme
import kotlinx.coroutines.launch

private const val CELL_WIDTH = 150

/** 数据浏览：真实分页、排序、单元格编辑与行增删，写操作一律二次确认。 */
@Composable
fun DbDataTab(
    handle: DbHandle,
    tables: List<String>,
    initialTable: String?,
    schemas: List<TableSchema>,
    onToast: (String) -> Unit,
    onExport: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    val scope = rememberCoroutineScope()

    var table by
        remember(initialTable, tables) {
            mutableStateOf(initialTable ?: tables.firstOrNull().orEmpty())
        }
    var orderBy by remember(table) { mutableStateOf<String?>(null) }
    var descending by remember(table) { mutableStateOf(false) }
    var pageSize by remember { mutableStateOf(20) }
    var offset by remember(table) { mutableStateOf(0) }
    var page by remember { mutableStateOf(GridPage()) }
    var loading by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }

    // 菜单挂在页面顶层，得自己记下排序按钮的位置，菜单才会贴着它展开。
    var sortAnchor by remember { mutableStateOf<Rect?>(null) }
    var editTarget by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var editValue by remember { mutableStateOf("") }
    var confirmWrite by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var deleteTarget by remember { mutableStateOf<Pair<Long, String>?>(null) }
    var insertOpen by remember { mutableStateOf(false) }
    var insertValues by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val columns =
        remember(table, schemas) {
            schemas.firstOrNull { it.name == table }?.columns?.map { it.name }.orEmpty()
        }
    // 列宽按「列名 + 前 20 行内容」估算：固定 150dp 时一屏只露出两列半，右侧列被硬裁。
    val columnWidths =
        remember(page.columns, page.rows) {
            page.columns.mapIndexed { index, name ->
                val maxChars =
                    maxOf(
                        visualLength(name),
                        page.rows.take(20).maxOfOrNull {
                            visualLength(it.getOrNull(index).orEmpty())
                        } ?: 0,
                    )
                (maxChars.coerceIn(5, 26) * 9 + 20).dp
            }
        }


    fun reload() {
        if (table.isBlank()) return
        loading = true
        scope.launch {
            page = SqliteEngine.gridPage(handle.db, table, orderBy, descending, pageSize, offset)
            loading = false
        }
    }

    LaunchedEffect(table, orderBy, descending, pageSize, offset) { reload() }

    if (tables.isEmpty()) {
        MiuixEmptyState(
            title = "没有可浏览的数据表",
            modifier = modifier,
            description = "当前数据库没有用户表",
            icon = Icons.Outlined.TableChart,
        )
        return
    }

    Column(modifier) {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = spacing.pageHorizontal, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tables.forEach { name ->
                MiuixFilterChip(
                    label = name,
                    selected = name == table,
                    onClick = {
                        table = name
                        orderBy = null
                        descending = false
                        offset = 0
                    },
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.pageHorizontal),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                MiuixTag(
                    text =
                        buildString {
                            append(
                                if (orderBy == null) "默认顺序"
                                else "按 $orderBy ${if (descending) "降序" else "升序"}")
                            append(" · 每页 $pageSize")
                        },
                    filled = true,
                )
                // 排序入口覆盖在标签上，点击弹出真实列清单
                Box(
                    Modifier.matchParentSize().miuixClickable(rememberMiuixPressState(), true) {
                        sortMenu = true
                    },
                )
            }
            Spacer(Modifier.width(8.dp))
            MiuixIconButton(
                icon = Icons.Outlined.Sort,
                contentDescription = "排序设置",
                onClick = { sortMenu = true },
                modifier = Modifier.onGloballyPositioned { sortAnchor = it.boundsInWindow() },
            )
            Spacer(Modifier.weight(1f))
            MiuixIconButton(
                icon = Icons.Outlined.ArrowBack,
                contentDescription = "上一页",
                enabled = offset > 0 && !loading,
                onClick = { offset = (offset - pageSize).coerceAtLeast(0) },
            )
            MiuixIconButton(
                icon = Icons.Outlined.ArrowForward,
                contentDescription = "下一页",
                enabled = !loading && offset + pageSize < page.total,
                onClick = { offset += pageSize },
            )
        }

        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.pageHorizontal),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MiuixText(
                text =
                    buildString {
                        append("共 ${page.total} 行")
                        if (page.error != null) append(" · ${page.error}")
                        else
                            append(
                                " · 第 ${offset + 1}–${(offset + page.rows.size).coerceAtMost(page.total)} 行")
                        append(" · ${page.elapsedMs} ms")
                    },
                style = MiuixTheme.typography.bodySmall,
                color = if (page.error == null) colors.onSurfaceVariant else colors.error,
                modifier = Modifier.weight(1f),
            )
            MiuixButton(
                text = "新增行",
                onClick = {
                    insertValues = columns.associateWith { "" }
                    insertOpen = true
                },
                variant = MiuixButtonVariant.TONAL,
                size = com.mcp.toolbox.core.design.component.MiuixButtonSize.SMALL,
                enabled = handle.writable,
            )
            Spacer(Modifier.width(8.dp))
            MiuixIconButton(
                icon = Icons.Outlined.FileDownload,
                contentDescription = "导出本页",
                onClick = {
                    val result = QueryResult(columns = page.columns, rows = page.rows)
                    onExport(
                        "${table}_page${offset / pageSize + 1}.csv", SqliteEngine.toCsv(result))
                },
            )
        }

        Spacer(Modifier.height(8.dp))
        if (page.rows.isEmpty() && !loading) {
            MiuixEmptyState(
                title = "该表没有数据",
                description = "可以点「新增行」插入一条真实记录",
                icon = Icons.Outlined.ViewColumn,
                modifier = Modifier.weight(1f),
            )
        } else {
            Column(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                Row(
                    modifier =
                        Modifier.background(colors.surfaceContainerHighest)
                            .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MiuixText(
                        text = "rowid",
                        modifier = Modifier.width(56.dp).padding(start = 12.dp),
                        style =
                            MiuixTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace),
                        color = colors.onSurfaceVariant,
                    )
                    page.columns.forEachIndexed { columnIndex, column ->
                        MiuixText(
                            text = column,
                            modifier =
                                Modifier.width(
                                        columnWidths.getOrElse(columnIndex) { CELL_WIDTH.dp })
                                    .padding(horizontal = 8.dp),
                            style = MiuixTheme.typography.labelMedium,
                            color = colors.primary,
                            maxLines = 1,
                        )
                    }
                }
                LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(page.rows.size) { rowIndex ->
                        val row = page.rows[rowIndex]
                        val rowId = page.rowIds.getOrNull(rowIndex)
                        Row(
                            modifier =
                                Modifier.fillMaxWidth()
                                    .background(
                                        if (rowIndex % 2 == 0) colors.surface
                                        else colors.surfaceContainerLow)
                                    .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                modifier =
                                    Modifier.width(56.dp).padding(start = 12.dp).miuixClickable(
                                        rememberMiuixPressState(),
                                        rowId != null && handle.writable) {
                                            rowId?.let {
                                                deleteTarget = it to row.joinToString(" | ")
                                            }
                                        },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                MiuixText(
                                    text = rowId?.toString() ?: "—",
                                    style =
                                        MiuixTheme.typography.labelSmall.copy(
                                            fontFamily = FontFamily.Monospace),
                                    color = colors.onSurfaceVariant,
                                )
                            }
                            page.columns.forEachIndexed { columnIndex, _ ->
                                val isNull =
                                    page.nulls.getOrNull(rowIndex)?.getOrNull(columnIndex) == true
                                MiuixText(
                                    text = DbFormat.cell(row.getOrNull(columnIndex)),
                                    modifier =
                                        Modifier.width(
                                                columnWidths.getOrElse(columnIndex) { CELL_WIDTH.dp })
                                            .padding(horizontal = 8.dp)
                                            .miuixClickable(
                                                rememberMiuixPressState(),
                                                rowId != null && handle.writable && !isNull,
                                            ) {
                                                editTarget = rowIndex to columnIndex
                                                editValue = row.getOrNull(columnIndex).orEmpty()
                                            },
                                    style =
                                        MiuixTheme.typography.bodySmall.copy(
                                            fontFamily = FontFamily.Monospace),
                                    color =
                                        if (isNull) colors.onSurfaceVariant else colors.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Box(
                            Modifier.fillMaxWidth()
                                .height(1.dp)
                                .background(colors.outlineVariant.copy(alpha = 0.35f)),
                        )
                    }
                }
            }
        }
    }

    MiuixOverflowMenu(expanded = sortMenu, onDismiss = { sortMenu = false }, anchor = sortAnchor) {
        MiuixMenuItem(
            text = "默认顺序（rowid）",
            checked = orderBy == null,
            onClick = {
                orderBy = null
                descending = false
                offset = 0
                sortMenu = false
            })
        MiuixMenuDivider()
        MiuixMenuItem(
            text = "升序",
            checked = orderBy != null && !descending,
            enabled = orderBy != null,
            onClick = {
                descending = false
                offset = 0
                sortMenu = false
            },
        )
        MiuixMenuItem(
            text = "降序",
            checked = orderBy != null && descending,
            enabled = orderBy != null,
            onClick = {
                descending = true
                offset = 0
                sortMenu = false
            },
        )
        MiuixMenuDivider()
        MiuixMenuItem(
            text = "每页 20 行",
            checked = pageSize == 20,
            onClick = {
                pageSize = 20
                offset = 0
                sortMenu = false
            })
        MiuixMenuItem(
            text = "每页 50 行",
            checked = pageSize == 50,
            onClick = {
                pageSize = 50
                offset = 0
                sortMenu = false
            })
        MiuixMenuItem(
            text = "每页 100 行",
            checked = pageSize == 100,
            onClick = {
                pageSize = 100
                offset = 0
                sortMenu = false
            })
        MiuixMenuDivider()
        columns.forEach { column ->
            MiuixMenuItem(
                text = "按列排序：$column",
                checked = orderBy == column,
                onClick = {
                    orderBy = column
                    descending = false
                    offset = 0
                    sortMenu = false
                })
        }
    }

    // 单元格编辑 → 写库前二次确认
    val target = editTarget
    if (target != null) {
        val columnName = page.columns.getOrNull(target.second).orEmpty()
        MiuixDialog(
            visible = true,
            onDismiss = { editTarget = null },
            title = "编辑 $columnName",
            message = "表 $table · ${page.columns.joinToString(", ").take(120)}",
            confirmText = "确认写入",
            onConfirm = {
                val rowId = page.rowIds.getOrNull(target.first)
                editTarget = null
                if (rowId != null) {
                    confirmWrite =
                        "将 rowid=$rowId 的 $columnName 更新为「${DbFormat.cell(editValue, 60)}」？" to
                            {
                                scope.launch {
                                    val (ok, message) =
                                        SqliteEngine.updateCell(
                                            handle.db, table, rowId, columnName, editValue)
                                    onToast(message)
                                    if (ok) reload()
                                }
                            }
                }
            },
            content = {
                MiuixTextField(
                    value = editValue, onValueChange = { editValue = it }, placeholder = "新值")
                Spacer(Modifier.height(6.dp))
                MiuixText(
                    text = "留空表示写入空字符串；如需写入 NULL 请用 SQL 编辑器执行 UPDATE。",
                    style = MiuixTheme.typography.bodySmall,
                    color = MiuixTheme.colors.onSurfaceVariant,
                )
            },
        )
    }

    deleteTarget?.let { (rowId, preview) ->
        MiuixDialog(
            visible = true,
            onDismiss = { deleteTarget = null },
            title = "删除这一行？",
            message = "表 $table · rowid=$rowId\n$preview",
            confirmText = "删除",
            destructive = true,
            onConfirm = {
                deleteTarget = null
                scope.launch {
                    val (ok, message) = SqliteEngine.deleteRow(handle.db, table, rowId)
                    onToast(message)
                    if (ok) reload()
                }
            },
        )
    }

    confirmWrite?.let { (message, action) ->
        MiuixDialog(
            visible = true,
            onDismiss = { confirmWrite = null },
            title = "写操作确认",
            message = message,
            confirmText = "写入",
            destructive = true,
            onConfirm = {
                confirmWrite = null
                action()
            },
        )
    }

    if (insertOpen) {
        MiuixDialog(
            visible = true,
            onDismiss = { insertOpen = false },
            title = "新增一行",
            message = "表 $table · 留空列会写入空字符串",
            confirmText = "插入",
            onConfirm = {
                insertOpen = false
                scope.launch {
                    val (ok, message) =
                        SqliteEngine.insertRow(
                            handle.db,
                            table,
                            insertValues.filterValues { it.isNotEmpty() },
                        )
                    onToast(message)
                    if (ok) reload()
                }
            },
            content = {
                Column(Modifier.heightIn(max = 420.dp)) {
                    columns.take(8).forEach { column ->
                        MiuixTextField(
                            value = insertValues[column].orEmpty(),
                            onValueChange = { insertValues = insertValues + (column to it) },
                            placeholder = column,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    if (columns.size > 8) {
                        MiuixText(
                            text = "其余 ${columns.size - 8} 列未在此处编辑，可插入后用 SQL 补充。",
                            style = MiuixTheme.typography.bodySmall,
                            color = MiuixTheme.colors.onSurfaceVariant,
                        )
                    }
                }
            },
        )
    }
}

/** 近似视觉宽度：CJK 等全角字符按 2 个半角算，避免中文列宽被估窄。 */
private fun visualLength(text: String): Int = text.sumOf { if (it.code > 0x2E80) 2 else 1 }
