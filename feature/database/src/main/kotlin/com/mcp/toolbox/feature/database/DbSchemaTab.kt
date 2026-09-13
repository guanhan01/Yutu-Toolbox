package com.mcp.toolbox.feature.database

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcp.toolbox.core.design.component.CodeLanguage
import com.mcp.toolbox.core.design.component.MiuixCodeText
import com.mcp.toolbox.core.design.component.MiuixEmptyState
import com.mcp.toolbox.core.design.component.MiuixListItem
import com.mcp.toolbox.core.design.component.MiuixSectionCard
import com.mcp.toolbox.core.design.component.MiuixTag
import com.mcp.toolbox.core.design.component.MiuixText
import com.mcp.toolbox.core.design.component.miuixClickable
import com.mcp.toolbox.core.design.component.rememberMiuixPressState
import com.mcp.toolbox.core.design.theme.MiuixTheme
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState

/** 结构浏览：表 / 视图 + 列、索引、触发器与建表 SQL。整页用 LazyColumn 承载，保证能滚到底。 */
@Composable
fun DbSchemaTab(
    schemas: List<TableSchema>,
    selected: String?,
    onSelect: (String) -> Unit,
    onOpenData: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing

    if (schemas.isEmpty()) {
        MiuixEmptyState(
            title = "没有可浏览的对象",
            modifier = modifier,
            description = "当前数据库里没有用户表或视图（系统表已隐藏）",
            icon = Icons.Outlined.TableChart,
        )
        return
    }

    LazyColumn(
        modifier = modifier,
        contentPadding =
            PaddingValues(
                start = spacing.pageHorizontal,
                end = spacing.pageHorizontal,
                top = spacing.sm,
                bottom = spacing.xxl,
            ),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MiuixTag(
                    text = "${schemas.count { it.type == "table" }} 张表",
                    color = colors.primary,
                    filled = true,
                )
                MiuixTag(
                    text = "${schemas.count { it.type == "view" }} 个视图",
                    color = colors.secondary,
                    filled = true,
                )
                MiuixTag(
                    text = "共 ${schemas.sumOf { if (it.rowCount > 0) it.rowCount else 0 }} 行",
                    color = colors.onSurfaceVariant,
                )
            }
        }

        items(schemas, key = { it.name }) { schema ->
            val expanded = schema.name == selected
            MiuixSectionCard(title = schema.name, subtitle = schemaSubtitle(schema)) {
                Row(
                    modifier =
                        Modifier.fillMaxWidth()
                            .miuixClickable(rememberMiuixPressState(), true) {
                                onSelect(if (expanded) "" else schema.name)
                            }
                            .padding(horizontal = spacing.pageHorizontal, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MiuixText(
                        text = if (expanded) "收起结构与索引" else "展开结构与索引",
                        style = MiuixTheme.typography.bodySmall,
                        color = colors.primary,
                        modifier = Modifier.weight(1f),
                    )
                    MiuixText(
                        text = "浏览数据 →",
                        style = MiuixTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        modifier =
                            Modifier.miuixClickable(rememberMiuixPressState(), true) {
                                onOpenData(schema.name)
                            }
                                .padding(vertical = 2.dp),
                    )
                }
                if (expanded) {
                    Column(Modifier.padding(horizontal = spacing.pageHorizontal)) {
                        schema.columns.forEach { column -> DbColumnRow(column) }
                        if (schema.indexes.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            MiuixText(
                                text = "索引",
                                style = MiuixTheme.typography.labelMedium,
                                color = colors.onSurfaceVariant,
                            )
                            schema.indexes.forEach { index ->
                                MiuixListItem(
                                    title = index.name,
                                    subtitle =
                                        (if (index.unique) "唯一索引 · " else "") +
                                            index.columns.joinToString(", "),
                                    leadingIcon = Icons.Outlined.Key,
                                )
                            }
                        }
                        if (schema.triggers.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            MiuixText(
                                text = "触发器",
                                style = MiuixTheme.typography.labelMedium,
                                color = colors.onSurfaceVariant,
                            )
                            schema.triggers.forEach { trigger ->
                                MiuixListItem(
                                    title = trigger,
                                    leadingIcon = Icons.Outlined.PlayArrow,
                                )
                            }
                        }
                        schema.sql?.let { sql ->
                            Spacer(Modifier.height(6.dp))
                            MiuixCodeText(
                                code = sql,
                                language = CodeLanguage.SQL,
                                showLineNumbers = false,
                                fontSize = 12.sp,
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }
        }
    }
}

private fun schemaSubtitle(schema: TableSchema): String = buildString {
    append(if (schema.type == "view") "视图" else "表")
    append(" · ${schema.columns.size} 列")
    if (schema.rowCount >= 0) append(" · ${schema.rowCount} 行")
    if (schema.indexes.isNotEmpty()) append(" · ${schema.indexes.size} 索引")
}

@Composable
private fun DbColumnRow(column: ColumnInfo) {
    val colors = MiuixTheme.colors
    var showMore by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().miuixClickable(rememberMiuixPressState(), true) {
            showMore = !showMore
        }
    ) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .background(
                        if (column.primaryKey) colors.primaryContainer
                        else colors.surfaceContainerHighest,
                        RoundedCornerShape(6.dp),
                    )
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            ) {
                MiuixText(
                    text = column.type.ifBlank { "ANY" },
                    style =
                        MiuixTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color =
                        if (column.primaryKey) colors.onPrimaryContainer
                        else colors.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(10.dp))
            MiuixText(text = column.name, style = MiuixTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            if (column.primaryKey) {
                MiuixTag(text = "PK", color = colors.primary, filled = true)
            } else if (column.notNull) {
                MiuixTag(text = "NOT NULL", color = colors.onSurfaceVariant)
            }
        }
        if (showMore) {
            MiuixText(
                text =
                    "默认值：${column.defaultValue ?: "无"} · 可空：${if (column.notNull) "否" else "是"}",
                style = MiuixTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
    }
}
