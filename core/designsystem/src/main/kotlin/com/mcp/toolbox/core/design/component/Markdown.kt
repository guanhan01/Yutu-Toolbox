package com.mcp.toolbox.core.design.component

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcp.toolbox.core.design.theme.MiuixTheme

/**
 * 极简 Markdown 解析结果。
 *
 * 只覆盖对话里真正会出现的结构：段落、标题、列表、代码块、表格、引用、分隔线。
 * 不引入完整 CommonMark 实现——本工程没有 Markdown 依赖，而模型输出的表格/代码
 * 是最需要被正确渲染的两类，其余按纯文本走体验也够。
 */
sealed interface MdBlock {
    data class Paragraph(val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    /** [ordered] 为 true 时是 1. 2. 3.，否则是 - * +。 */
    data class Bullet(val items: List<String>, val ordered: Boolean = false) : MdBlock
    data class Code(val code: String, val language: CodeLanguage) : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
    data class Quote(val text: String) : MdBlock
    data object Divider : MdBlock
}

/**
 * 把 Markdown 文本切成块。
 *
 * 表格判定要求「本行含 | 且下一行是分隔行（|---|:--:|）」，只靠单行含 | 会把
 * 普通正文里的竖线误判成表格。
 */
fun parseMarkdown(text: String): List<MdBlock> {
    val lines = text.replace("\r\n", "\n").split('\n')
    val blocks = mutableListOf<MdBlock>()
    val paragraph = StringBuilder()
    var i = 0

    fun flushParagraph() {
        val p = paragraph.toString().trim()
        if (p.isNotEmpty()) blocks += MdBlock.Paragraph(p)
        paragraph.clear()
    }

    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()

        when {
            // 代码围栏：直到配对的 ``` 为止，内部原样保留（含空行与缩进）
            trimmed.startsWith("```") -> {
                flushParagraph()
                val lang = trimmed.removePrefix("```").trim()
                val body = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    body.append(lines[i]).append('\n')
                    i++
                }
                i++ // 跳过收尾的 ```
                blocks += MdBlock.Code(body.toString().trimEnd('\n'), codeLanguageOf(lang))
                continue
            }

            // 表格：当前行是表头，下一行是分隔行
            trimmed.contains('|') && i + 1 < lines.size && isTableSeparator(lines[i + 1]) -> {
                flushParagraph()
                val header = splitTableRow(trimmed)
                val rows = mutableListOf<List<String>>()
                i += 2
                while (i < lines.size && lines[i].trim().contains('|') &&
                    lines[i].trim().isNotEmpty()
                ) {
                    rows += splitTableRow(lines[i].trim())
                    i++
                }
                blocks += MdBlock.Table(header, rows)
                continue
            }

            trimmed.isEmpty() -> flushParagraph()

            // 分隔线：至少三个 - * _，且不是列表项（- 后面要有空格才是列表）
            trimmed.length >= 3 && (
                trimmed.all { it == '-' } || trimmed.all { it == '*' } || trimmed.all { it == '_' }
                ) -> {
                flushParagraph()
                blocks += MdBlock.Divider
            }

            trimmed.startsWith("#") -> {
                flushParagraph()
                val level = trimmed.takeWhile { it == '#' }.length.coerceAtMost(6)
                blocks += MdBlock.Heading(level, trimmed.drop(level).trim())
            }

            trimmed.startsWith(">") -> {
                flushParagraph()
                val quote = StringBuilder()
                while (i < lines.size && lines[i].trim().startsWith(">")) {
                    if (quote.isNotEmpty()) quote.append('\n')
                    quote.append(lines[i].trim().removePrefix(">").trim())
                    i++
                }
                blocks += MdBlock.Quote(quote.toString())
                continue
            }

            isBullet(trimmed) || isOrdered(trimmed) -> {
                flushParagraph()
                val ordered = isOrdered(trimmed)
                val items = mutableListOf<String>()
                while (i < lines.size) {
                    val cur = lines[i].trim()
                    if (!isBullet(cur) && !isOrdered(cur)) break
                    items += cur.dropWhile { it == '-' || it == '*' || it == '+' || it.isDigit() || it == '.' }
                        .trim()
                    i++
                }
                blocks += MdBlock.Bullet(items, ordered)
                continue
            }

            else -> {
                if (paragraph.isNotEmpty()) paragraph.append('\n')
                paragraph.append(trimmed)
            }
        }
        i++
    }
    flushParagraph()
    return blocks
}

private fun isBullet(line: String): Boolean =
    line.length > 1 && line[0] in listOf('-', '*', '+') && line[1] == ' '

private fun isOrdered(line: String): Boolean {
    val digits = line.takeWhile { it.isDigit() }
    return digits.isNotEmpty() && line.length > digits.length + 1 &&
        line[digits.length] == '.' && line[digits.length + 1] == ' '
}

private fun isTableSeparator(line: String): Boolean {
    val t = line.trim()
    if (!t.contains('-') || !t.contains('|')) return false
    return t.all { it == '|' || it == '-' || it == ':' || it == ' ' }
}

private fun splitTableRow(line: String): List<String> =
    line.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

private fun codeLanguageOf(lang: String): CodeLanguage = when (lang.lowercase()) {
    "json" -> CodeLanguage.JSON
    "sql" -> CodeLanguage.SQL
    "java" -> CodeLanguage.JAVA
    "kotlin", "kt" -> CodeLanguage.KOTLIN
    "smali" -> CodeLanguage.SMALI
    "http" -> CodeLanguage.HTTP
    else -> CodeLanguage.TEXT
}

/**
 * 行内格式：`**粗**`、`*斜*`、`` `代码` ``、`~~删除~~`、`[文本](链接)`。
 *
 * 用一次性扫描而不是多轮 replace：多轮 replace 会在「代码里含 **」这类情况下
 * 反复改写同一段文本，导致样式互相覆盖。
 */
fun inlineMarkdown(
    text: String,
    codeBackground: Color,
    linkColor: Color,
    codeFontFamily: FontFamily = FontFamily.Monospace,
): AnnotatedString = buildAnnotatedString {
    var i = 0
    fun appendStyled(content: String, style: SpanStyle) {
        withStyle(style) { append(content) }
    }
    while (i < text.length) {
        val rest = text.substring(i)
        when {
            rest.startsWith("**") -> {
                val end = text.indexOf("**", i + 2)
                if (end > i) {
                    appendStyled(text.substring(i + 2, end), SpanStyle(fontWeight = FontWeight.SemiBold))
                    i = end + 2
                } else {
                    append(text[i]); i++
                }
            }

            rest.startsWith("~~") -> {
                val end = text.indexOf("~~", i + 2)
                if (end > i) {
                    appendStyled(
                        text.substring(i + 2, end),
                        SpanStyle(textDecoration = TextDecoration.LineThrough),
                    )
                    i = end + 2
                } else {
                    append(text[i]); i++
                }
            }

            rest.startsWith("`") -> {
                val end = text.indexOf('`', i + 1)
                if (end > i) {
                    appendStyled(
                        text.substring(i + 1, end),
                        SpanStyle(
                            fontFamily = codeFontFamily,
                            background = codeBackground,
                        ),
                    )
                    i = end + 1
                } else {
                    append(text[i]); i++
                }
            }

            rest.startsWith("[") -> {
                val close = text.indexOf(']', i + 1)
                val open = if (close > 0 && close + 1 < text.length && text[close + 1] == '(') {
                    text.indexOf(')', close + 2)
                } else {
                    -1
                }
                if (close > i && open > close) {
                    appendStyled(
                        text.substring(i + 1, close),
                        SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
                    )
                    i = open + 1
                } else {
                    append(text[i]); i++
                }
            }

            rest.startsWith("*") || rest.startsWith("_") -> {
                val marker = text[i]
                val end = text.indexOf(marker, i + 1)
                // 单字符标记不当作斜体，避免 "3*4 与 5*6" 这类文本被吃掉
                if (end > i + 1) {
                    appendStyled(text.substring(i + 1, end), SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic))
                    i = end + 1
                } else {
                    append(text[i]); i++
                }
            }

            else -> {
                append(text[i]); i++
            }
        }
    }
}

/**
 * 把一个 Markdown 块渲染给用户看。
 *
 * [compact] 为 true 时用在折叠的思考区：字号更小、代码块不带行号，
 * 避免思考过程占掉整屏。
 */
@Composable
fun MarkdownBlockView(
    block: MdBlock,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    compact: Boolean = false,
) {
    val colors = MiuixTheme.colors
    val textColor = if (color != Color.Unspecified) color else colors.onSurface
    val style = if (compact) MiuixTheme.typography.bodySmall else MiuixTheme.typography.bodyMedium

    when (block) {
        is MdBlock.Paragraph -> MiuixText(
            text = inlineMarkdown(
                block.text,
                codeBackground = colors.surfaceContainerHigh,
                linkColor = colors.primary,
            ),
            modifier = modifier.fillMaxWidth(),
            style = style,
            color = textColor,
        )

        is MdBlock.Heading -> {
            val headingStyle = when (block.level) {
                1 -> MiuixTheme.typography.titleLarge
                2 -> MiuixTheme.typography.titleMedium
                3 -> MiuixTheme.typography.titleSmall
                else -> style.copy(fontWeight = FontWeight.SemiBold)
            }
            MiuixText(
                text = inlineMarkdown(
                    block.text,
                    codeBackground = colors.surfaceContainerHigh,
                    linkColor = colors.primary,
                ),
                modifier = modifier.fillMaxWidth(),
                style = if (compact) style.copy(fontWeight = FontWeight.SemiBold) else headingStyle,
                color = textColor,
            )
        }

        is MdBlock.Bullet -> Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            block.items.forEachIndexed { index, item ->
                Row(Modifier.fillMaxWidth()) {
                    MiuixText(
                        text = if (block.ordered) "${index + 1}." else "•",
                        style = style,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.width(if (block.ordered) 22.dp else 14.dp),
                    )
                    MiuixText(
                        text = inlineMarkdown(
                            item,
                            codeBackground = colors.surfaceContainerHigh,
                            linkColor = colors.primary,
                        ),
                        modifier = Modifier.weight(1f),
                        style = style,
                        color = textColor,
                    )
                }
            }
        }

        is MdBlock.Code -> MiuixCodeText(
            code = block.code,
            modifier = modifier.fillMaxWidth(),
            language = block.language,
            showLineNumbers = !compact,
            fontSize = if (compact) 12.sp else 13.sp,
            lineHeight = if (compact) 18.sp else 20.sp,
        )

        is MdBlock.Table -> MarkdownTableView(block, modifier, compact)

        is MdBlock.Quote -> Row(modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .width(3.dp)
                    .heightIn(min = 16.dp)
                    .fillMaxHeight()
                    .background(colors.onSurfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(2.dp)),
            )
            Spacer(Modifier.width(8.dp))
            MiuixText(
                text = inlineMarkdown(
                    block.text,
                    codeBackground = colors.surfaceContainerHigh,
                    linkColor = colors.primary,
                ),
                modifier = Modifier.weight(1f),
                style = style,
                color = colors.onSurfaceVariant,
            )
        }

        MdBlock.Divider -> Box(
            modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .height(1.dp)
                .background(colors.outline.copy(alpha = 0.4f)),
        )
    }
}

/**
 * 表格渲染。
 *
 * 列宽按内容长度加权分配，窄屏也不会让某一列被挤成竖排单字；
 * 横向可滚动，列特别多时仍然能读全。
 */
@Composable
private fun MarkdownTableView(
    table: MdBlock.Table,
    modifier: Modifier = Modifier,
    compact: Boolean,
) {
    val colors = MiuixTheme.colors
    val style = if (compact) MiuixTheme.typography.bodySmall else MiuixTheme.typography.bodyMedium
    val columnCount = maxOf(table.header.size, table.rows.maxOfOrNull { it.size } ?: 0)
    if (columnCount == 0) return

    // 按各列最长内容的**实际渲染宽度**分配列宽，不设上限：
    // 列宽不够时单元格会折行，一列数据被拆成好几行反而更难纵向对照。
    // 整表放不下就交给外层 horizontalScroll，横向滚动看全比压缩列宽可靠。
    val widths = (0 until columnCount).map { col ->
        val longest = buildList {
            table.header.getOrNull(col)?.let { add(cellWidthDp(it)) }
            table.rows.forEach { row -> row.getOrNull(col)?.let { add(cellWidthDp(it)) } }
        }.maxOrNull() ?: 0f
        // +16 是单元格左右各 8dp 内边距
        maxOf(TABLE_MIN_COLUMN.value, longest + 16f).dp
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MiuixTheme.radius.sm))
            .border(1.dp, colors.outline.copy(alpha = 0.3f), RoundedCornerShape(MiuixTheme.radius.sm))
            .horizontalScroll(rememberScrollState()),
    ) {
        Row(Modifier.background(colors.surfaceContainerHigh)) {
            table.header.forEachIndexed { index, cell ->
                MiuixText(
                    text = inlineMarkdown(cell, colors.surfaceContainerHigh, colors.primary),
                    modifier = Modifier
                        .width(widths[index])
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    style = style.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.onSurface,
                )
            }
        }
        table.rows.forEachIndexed { rowIndex, row ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(
                        if (rowIndex % 2 == 0) Color.Transparent
                        else colors.surfaceContainerLow,
                    )
                    .height(1.dp)
                    .background(colors.outline.copy(alpha = 0.2f)),
            )
            Row {
                (0 until columnCount).forEach { col ->
                    MiuixText(
                        text = inlineMarkdown(
                            row.getOrNull(col).orEmpty(),
                            colors.surfaceContainerHigh,
                            colors.primary,
                        ),
                        modifier = Modifier
                            .width(widths[col])
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        style = style,
                        color = colors.onSurface,
                    )
                }
            }
        }
    }
}

/**
 * 整段 Markdown 渲染（块之间留统一间距）。
 *
 * 空文本返回 null 由调用方决定占位，这里不画任何东西。
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    compact: Boolean = false,
) {
    val blocks = remember(text) { parseMarkdown(text) }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp),
    ) {
        blocks.forEach { block -> MarkdownBlockView(block, color = color, compact = compact) }
    }
}

/** 表格最小列宽（dp）：再窄就该整表横向滚动了。 */
private val TABLE_MIN_COLUMN = 68.dp

/**
 * 估算一段文字的渲染宽度（dp）。
 *
 * 用真实排版测量最准，但表格只是对话里的辅助信息，为它引入 TextMeasurer
 * 会让渲染路径复杂不少。这里按字符类别估算：CJK／全角字符占一个字号宽，
 * 其余按半宽。误差在可接受范围内，关键是列宽能随内容自然伸展。
 */
private fun cellWidthDp(text: String): Float {
    var width = 0f
    text.codePoints().forEach { cp ->
        width += if (cp > 0x2E7F) 14f else 7.5f
    }
    return width
}
