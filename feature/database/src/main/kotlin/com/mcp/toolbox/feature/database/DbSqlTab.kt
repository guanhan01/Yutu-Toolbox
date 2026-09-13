package com.mcp.toolbox.feature.database

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mcp.toolbox.core.design.component.CodeEditor
import com.mcp.toolbox.core.design.component.MiuixButton
import com.mcp.toolbox.core.design.component.MiuixButtonSize
import com.mcp.toolbox.core.design.component.MiuixButtonVariant
import com.mcp.toolbox.core.design.component.MiuixIconButton
import com.mcp.toolbox.core.design.component.MiuixListItem
import com.mcp.toolbox.core.design.component.MiuixSectionCard
import com.mcp.toolbox.core.design.component.MiuixSuggestionChip
import com.mcp.toolbox.core.design.component.MiuixTag
import com.mcp.toolbox.core.design.component.MiuixText
import com.mcp.toolbox.core.design.component.miuixClickable
import com.mcp.toolbox.core.design.component.rememberMiuixPressState
import com.mcp.toolbox.core.design.theme.MiuixTheme
import kotlinx.coroutines.launch
import org.json.JSONArray

private const val PREFS = "db_sql"
private const val KEY_HISTORY = "history"
private const val KEY_FAVORITE = "favorites"

/** SQL 编辑器：真实执行、只读拦截、关键字/表列自动补全、历史与收藏持久化。 */
@Composable
fun DbSqlTab(
    handle: DbHandle,
    tables: List<String>,
    columnsByTable: Map<String, List<String>>,
    onToast: (String) -> Unit,
    onExport: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE) }

    var script by remember { mutableStateOf("SELECT * FROM \"${tables.firstOrNull().orEmpty()}\" LIMIT 20;") }
    var result by remember { mutableStateOf<QueryResult?>(null) }
    var running by remember { mutableStateOf(false) }
    var templateTable by remember { mutableStateOf(tables.firstOrNull().orEmpty()) }
    var history by remember { mutableStateOf(loadList(prefs.getString(KEY_HISTORY, null))) }
    var favorites by remember { mutableStateOf(loadList(prefs.getString(KEY_FAVORITE, null))) }
    var panel by remember { mutableStateOf(SqlPanel.NONE) }
    val suggestions = remember(script, tables, columnsByTable) {
        SqlSuggestions.suggest(script, tables, columnsByTable)
    }

    fun persist(key: String, values: List<String>) {
        prefs.edit().putString(key, JSONArray(values).toString()).apply()
    }

    fun run() {
        val statements = SqliteEngine.splitStatements(script)
        if (statements.isEmpty()) {
            onToast("没有可执行的语句")
            return
        }
        val writeKeywords = setOf("insert", "update", "delete", "drop", "alter", "create", "replace", "vacuum", "attach")
        val hasWrite = statements.any { sql ->
            sql.trimStart().substringBefore(' ').substringBefore('\n').lowercase() in writeKeywords
        }
        if (handle.readOnly && hasWrite) {
            onToast("只读模式：已拦下写语句（${statements.size} 条中检测到 DDL/DML）")
            return
        }
        running = true
        scope.launch {
            val outcome = SqliteEngine.execScript(handle.db, script)
            result = outcome
            running = false
            onToast(if (outcome.isError) outcome.message else "${outcome.message} · ${outcome.elapsedMs} ms")
            val entry = script.trim().take(500)
            if (entry.isNotEmpty()) {
                history = (listOf(entry) + history.filterNot { it == entry }).take(30)
                persist(KEY_HISTORY, history)
            }
        }
    }

    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = spacing.pageHorizontal)) {
        Spacer(Modifier.height(spacing.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            MiuixTag(
                text = if (handle.readOnly) "只读模式" else "可写模式",
                color = if (handle.readOnly) colors.success else colors.primary,
                filled = true,
            )
            Spacer(Modifier.width(8.dp))
            MiuixTag(text = "${tables.size} 张表", color = colors.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            MiuixIconButton(
                icon = Icons.Outlined.History,
                contentDescription = "执行历史",
                onClick = { panel = if (panel == SqlPanel.HISTORY) SqlPanel.NONE else SqlPanel.HISTORY },
            )
            MiuixIconButton(
                icon = Icons.Outlined.StarBorder,
                contentDescription = "收藏语句",
                onClick = { panel = if (panel == SqlPanel.FAVORITE) SqlPanel.NONE else SqlPanel.FAVORITE },
            )
        }
        Spacer(Modifier.height(spacing.sm))

        CodeEditor(
            value = script,
            onValueChange = { script = it },
            minHeight = 150.dp,
        )

        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width(spacing.pageHorizontal))
            suggestions.forEach { suggestion ->
                MiuixSuggestionChip(label = suggestion, onClick = {
                    script = if (script.endsWith(" ") || script.isEmpty()) script + suggestion else "$script $suggestion"
                })
            }
            if (suggestions.isEmpty()) {
                MiuixText(
                    text = "输入时会依据真实表名与列名给出补全",
                    style = MiuixTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            MiuixButton(
                text = "执行",
                onClick = { run() },
                leadingIcon = Icons.Outlined.PlayArrow,
                loading = running,
            )
            Spacer(Modifier.width(8.dp))
            MiuixButton(
                text = "收藏当前",
                variant = MiuixButtonVariant.OUTLINED,
                size = MiuixButtonSize.SMALL,
                onClick = {
                    val entry = script.trim()
                    if (entry.isEmpty()) {
                        onToast("编辑器为空")
                    } else {
                        favorites = (listOf(entry) + favorites.filterNot { it == entry }).take(30)
                        persist(KEY_FAVORITE, favorites)
                        onToast("已收藏")
                    }
                },
            )
            Spacer(Modifier.width(8.dp))
            MiuixButton(
                text = "清空",
                variant = MiuixButtonVariant.TEXT,
                size = MiuixButtonSize.SMALL,
                onClick = { script = "" },
            )
        }

        Spacer(Modifier.height(spacing.md))
        MiuixSectionCard(title = "常用语句", subtitle = templateTable) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = spacing.pageHorizontal, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                tables.forEach { name ->
                    MiuixFilterLikeChip(label = name, selected = name == templateTable) { templateTable = name }
                }
            }
            SqlSuggestions.templates(templateTable, columnsByTable[templateTable].orEmpty()).forEach { (label, sql) ->
                MiuixListItem(
                    title = label,
                    subtitle = sql.take(64),
                    onClick = { script = sql },
                    showDivider = true,
                )
            }
        }

        if (panel == SqlPanel.HISTORY) {
            Spacer(Modifier.height(spacing.md))
            MiuixSectionCard(title = "执行历史", subtitle = "点击可载入编辑器") {
                if (history.isEmpty()) {
                    MiuixListItem(title = "还没有执行记录", subtitle = "执行一条语句后会自动记下来")
                }
                history.forEach { entry ->
                    MiuixListItem(
                        title = entry.take(60),
                        subtitle = "${entry.length} 字符",
                        onClick = { script = entry },
                        trailing = {
                            MiuixIconButton(
                                icon = Icons.Outlined.Delete,
                                contentDescription = "从历史移除",
                                buttonSize = 32.dp,
                                iconSize = 16.dp,
                                onClick = {
                                    history = history.filterNot { it == entry }
                                    persist(KEY_HISTORY, history)
                                },
                            )
                        },
                    )
                }
            }
        }

        if (panel == SqlPanel.FAVORITE) {
            Spacer(Modifier.height(spacing.md))
            MiuixSectionCard(title = "收藏语句", subtitle = "本地持久化，可跨会话复用") {
                if (favorites.isEmpty()) {
                    MiuixListItem(title = "还没有收藏", subtitle = "先在编辑器里写下语句再点「收藏当前」")
                }
                favorites.forEach { entry ->
                    MiuixListItem(
                        title = entry.take(60),
                        subtitle = "${entry.length} 字符",
                        onClick = { script = entry },
                        showDivider = true,
                        trailing = {
                            MiuixIconButton(
                                icon = Icons.Outlined.Delete,
                                contentDescription = "取消收藏",
                                buttonSize = 32.dp,
                                iconSize = 16.dp,
                                onClick = {
                                    favorites = favorites.filterNot { it == entry }
                                    persist(KEY_FAVORITE, favorites)
                                },
                            )
                        },
                    )
                }
            }
        }

        result?.let { outcome ->
            Spacer(Modifier.height(spacing.md))
            MiuixSectionCard(
                title = if (outcome.isError) "执行出错" else "执行结果",
                subtitle = "${outcome.statements} 条语句 · ${outcome.elapsedMs} ms",
            ) {
                Column(Modifier.padding(horizontal = spacing.pageHorizontal, vertical = 8.dp)) {
                    MiuixText(
                        text = outcome.message,
                        style = MiuixTheme.typography.bodyMedium,
                        color = if (outcome.isError) colors.error else colors.onSurface,
                    )
                }
                if (outcome.isGrid) {
                    Row(Modifier.padding(horizontal = spacing.pageHorizontal, vertical = 4.dp)) {
                        MiuixButton(
                            text = "导出 CSV",
                            variant = MiuixButtonVariant.TONAL,
                            size = MiuixButtonSize.SMALL,
                            leadingIcon = Icons.Outlined.FileDownload,
                            onClick = { onExport("query_result.csv", SqliteEngine.toCsv(outcome)) },
                        )
                        Spacer(Modifier.width(8.dp))
                        MiuixButton(
                            text = "导出 JSON",
                            variant = MiuixButtonVariant.OUTLINED,
                            size = MiuixButtonSize.SMALL,
                            onClick = { onExport("query_result.json", SqliteEngine.toJson(outcome)) },
                        )
                    }
                    DbResultTable(outcome)
                }
            }
        }
        Spacer(Modifier.height(spacing.xxl))
    }
}

private enum class SqlPanel { NONE, HISTORY, FAVORITE }

private fun loadList(json: String?): List<String> = runCatching {
    val array = JSONArray(json ?: "[]")
    buildList { for (i in 0 until array.length()) add(array.getString(i)) }
}.getOrDefault(emptyList())

/** 查询结果表格：横向滚动 + 首行固定，用于 SQL 结果与 MCP 返回的结构化数据。 */
@Composable
fun DbResultTable(result: QueryResult, modifier: Modifier = Modifier) {
    val colors = MiuixTheme.colors
    Column(modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Row(
            modifier = Modifier
                .background(colors.surfaceContainerHighest)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            result.columns.forEach { column ->
                MiuixText(
                    text = column,
                    modifier = Modifier.width(150.dp).padding(horizontal = 8.dp),
                    style = MiuixTheme.typography.labelMedium,
                    color = colors.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        result.rows.take(200).forEachIndexed { index, row ->
            Row(
                modifier = Modifier
                    .background(if (index % 2 == 0) colors.surface else colors.surfaceContainerLow)
                    .padding(vertical = 8.dp),
            ) {
                result.columns.indices.forEach { columnIndex ->
                    MiuixText(
                        text = DbFormat.cell(row.getOrNull(columnIndex)),
                        modifier = Modifier.width(150.dp).padding(horizontal = 8.dp),
                        style = MiuixTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Box(
                Modifier
                    .width(150.dp * result.columns.size.coerceAtLeast(1))
                    .height(1.dp)
                    .background(colors.outlineVariant.copy(alpha = 0.35f)),
            )
        }
        if (result.rows.size > 200) {
            MiuixText(
                text = "仅显示前 200 行，共 ${result.rows.size} 行；导出后可看到完整结果。",
                style = MiuixTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(8.dp),
            )
        }
    }
}

@Composable
private fun MiuixFilterLikeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    com.mcp.toolbox.core.design.component.MiuixFilterChip(label = label, selected = selected, onClick = onClick)
}
