package com.mcp.toolbox.core.design.component

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
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
/**
 * 「打开链接」的实现。
 *
 * 默认走 Compose 的 `LocalUriHandler`（系统浏览器）。宿主可以覆写它，
 * 把链接交给应用内的浏览器——用户明确要求「用内置浏览器」打开，
 * 但 `:core:designsystem` 不该依赖 `:feature:web`，所以用 CompositionLocal
 * 把实现倒过来注入。
 */
val LocalLinkOpener = androidx.compose.runtime.staticCompositionLocalOf<((String) -> Unit)?> { null }

@Composable
private fun rememberLinkListener(): LinkInteractionListener? {
    val custom = LocalLinkOpener.current
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    return androidx.compose.runtime.remember(custom, uriHandler) {
        LinkInteractionListener { annotation ->
            val url = (annotation as? LinkAnnotation.Url)?.url ?: return@LinkInteractionListener
            if (custom != null) custom(url) else runCatching { uriHandler.openUri(url) }
            Unit
        }
    }
}

fun inlineMarkdown(
    text: String,
    codeBackground: Color,
    linkColor: Color,
    codeFontFamily: FontFamily = FontFamily.Monospace,
    /**
     * 链接的点击监听。为 null 时链接**只有样式、不响应点击**。
     *
     * 之所以要求显式传入而不是在这里直接取 `LocalUriHandler`：
     * 这个函数是普通函数（非 @Composable），拿不到 CompositionLocal。
     */
    linkListener: LinkInteractionListener? = null,
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

            /**
             * `[文本](url)`。
             *
             * 旧实现只把**显示文本**写进 AnnotatedString，`(url)` 整段被跳过——
             * 于是链接有颜色有下划线，却完全点不动，也拿不到目标地址
             * （用户实测反馈「AI 给的链接可以点击」）。
             *
             * 现在用 [LinkAnnotation.Url] 把地址一起带上，由渲染层决定怎么打开。
             */
            rest.startsWith("[") -> {
                val close = text.indexOf(']', i + 1)
                val open = if (close > 0 && close + 1 < text.length && text[close + 1] == '(') {
                    text.indexOf(')', close + 2)
                } else {
                    -1
                }
                if (close > i && open > close) {
                    val label = text.substring(i + 1, close)
                    val url = text.substring(close + 2, open).trim()
                    if (url.isEmpty()) {
                        appendStyled(
                            label,
                            SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
                        )
                    } else {
                        pushLink(
                            LinkAnnotation.Url(
                                url = url,
                                styles = TextLinkStyles(
                                    SpanStyle(
                                        color = linkColor,
                                        textDecoration = TextDecoration.Underline,
                                    ),
                                ),
                                linkInteractionListener = linkListener,
                            ),
                        )
                        append(label)
                        pop()
                    }
                    i = open + 1
                } else {
                    append(text[i]); i++
                }
            }

            /**
             * 裸链接（`https://…`）。
             *
             * 模型经常直接把地址写出来而不写成 Markdown 链接；不识别的话
             * 用户只能长按选中再复制，很别扭。
             */
            rest.startsWith("http://") || rest.startsWith("https://") -> {
                val end = rest.indexOfFirst { it.isWhitespace() || it == '）' || it == ')' || it == '，' || it == '。' }
                    .let { if (it < 0) rest.length else it }
                val url = rest.substring(0, end)
                pushLink(
                    LinkAnnotation.Url(
                        url = url,
                        styles = TextLinkStyles(
                            SpanStyle(
                                color = linkColor,
                                textDecoration = TextDecoration.Underline,
                            ),
                        ),
                        linkInteractionListener = linkListener,
                    ),
                )
                append(url)
                pop()
                i += end
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
    // 链接点击：默认交给系统浏览器；宿主可用 LocalLinkOpener 换成内置浏览器
    val linkListener = rememberLinkListener()
    val textColor = if (color != Color.Unspecified) color else colors.onSurface
    val style = if (compact) MiuixTheme.typography.bodySmall else MiuixTheme.typography.bodyMedium

    when (block) {
        is MdBlock.Paragraph -> MiuixText(
            text = inlineMarkdown(
                block.text,
                codeBackground = colors.surfaceContainerHigh,
                linkColor = colors.primary,
                linkListener = linkListener,
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
                    linkListener = linkListener,
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
                            linkListener = linkListener,
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

        is MdBlock.Table -> MarkdownTableView(block, modifier, compact, linkListener)

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
                    linkListener = linkListener,
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
    /**
     * 与表格外**同一个**链接监听器。
     *
     * 复用它而不是另建一套：点击行为全部由注入的 `LocalLinkOpener` 决定
     * （内置浏览器 + nonce + force 那套），表格里外走同一条路，
     * 就不会出现「表格里能点、表格外点不动」这种两边不一致的问题。
     */
    linkListener: LinkInteractionListener?,
) {
    val colors = MiuixTheme.colors
    val style = if (compact) MiuixTheme.typography.bodySmall else MiuixTheme.typography.bodyMedium
    val columnCount = maxOf(table.header.size, table.rows.maxOfOrNull { it.size } ?: 0)
    if (columnCount == 0) return

    val border = colors.outline.copy(alpha = 0.22f)
    val shape = RoundedCornerShape(MiuixTheme.radius.sm)
    val weights = remember(table.header, table.rows) {
        computeColumnWeights(table.header, table.rows, columnCount)
    }

    /**
     * 每列的最小可读宽。
     *
     * 这一段历史值得留个记录，因为**两个极端都试过、都出过观感问题**：
     * - 最早：按内容估算硬定列宽 + 横向滚动 → 两列表格也会撑出屏幕，
     *   表头色条只铺到列宽之和，右边留一条白边；
     * - 后来：纯权重归一（列宽总和恒等于屏宽）→ 白边没了，但**长内容再也撑不开**，
     *   只能挤在屏幕里折行，`minSdk / targetsdk`、`APK 路径` 被压成每行两三个字。
     *
     * 现在两者兼顾：`列宽 = max(权重份额, 最小可读宽)`。
     * 屏幕够宽时每列份额都 ≥ 最小宽 → 总和正好铺满，色条不留白；
     * 内容多/列多时总和超出视口 → 交给横向滚动，滑着看全而不是把字挤扁。
     */
    val minColumnWidth = if (compact) 72.dp else 88.dp

    // 量视口宽：用 onSizeChanged，不用 BoxWithConstraints。
    // 后者会把手约束的 maxWidth（视口宽）钉死传给子树，而 horizontalScroll
    // 需要父级给「无限宽」才能撑开内容 —— 混在一起就会裁掉超出部分。
    var viewportPx by remember(table.header, compact) { mutableFloatStateOf(0f) }

    val minPx = with(LocalDensity.current) { minColumnWidth.toPx() }
    val widthsPx = remember(weights, columnCount, viewportPx, minPx) {
        columnWidths(weights, columnCount, viewportPx, minPx)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { viewportPx = it.width.toFloat() }
            // 这里**不放** clip(shape)/border：它们会落在「固定尺寸的外层盒子」上，
            // 于是滚动时外框不动、只有里面的内容在跑（用户反馈「外边框也得跟着移动，
            // 整个表格一起动」）。裁剪由 horizontalScroll 自己负责，描边交给内容层。
            .horizontalScroll(rememberScrollState()),
    ) {
        // clip + border 放在**内容**这一层：外框的宽度等于整张表（widthsPx 之和），
        // 横向滚动时与单元格一起平移，整张表是一个整体。
        Column(
            modifier = Modifier
                .clip(shape)
                .border(1.dp, border, shape),
        ) {
        // 各行**都不设宽度**：
        // 行宽 = 该行单元格宽度之和 = widthsPx.sum()，由子项自然决定。
        //
        // 这一步是整件事的关键。之前给 Row 设 requiredWidth(totalWidth)，
        // Row 就会在「自己算出的宽度」之外再裁剪子项，表头灰底于是按 Row 的边界
        // 截断，而数据行的文字因为 measure 更宽溢出到边界外 —— 表现就是
        // 左滑后表格中间裂开、一半内容留在原位。
        // 不设宽度就没有这个第二基准，表头与数据行必然等宽、一起平移。
        Column {
            // 表头
            Row(Modifier.background(colors.surfaceContainerHigh)) {
                widthCells(widthsPx) { index ->
                    CellText(
                        text = table.header.getOrNull(index).orEmpty(),
                        style = style.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.onSurface,
                        background = colors.surfaceContainerHigh,
                        linkListener = linkListener,
                    )
                }
            }

            table.rows.forEach { row ->
                // 行分隔线：宽度同样由内容决定，这里用一个与表格等宽的 Box。
                Box(
                    Modifier
                        .width(with(LocalDensity.current) { widthsPx.sum().toDp() })
                        .height(1.dp)
                        .background(border),
                )
                Row {
                    widthCells(widthsPx) { col ->
                        CellText(
                            text = row.getOrNull(col).orEmpty(),
                            style = style,
                            color = colors.onSurface,
                            background = colors.surfaceContainerHigh,
                            linkListener = linkListener,
                        )
                    }
                }
            }
        }
        }
    }
}

/**
 * 按 [widthsPx] 逐列摆放单元格。
 *
 * 宽度用**显式像素**而不是 `weight`：`weight` 会把总宽锁死在父宽，
 * 那就回到了「长内容只能挤在屏幕里」的老问题。
 */
@Composable
private fun RowScope.widthCells(
    widthsPx: List<Float>,
    content: @Composable (Int) -> Unit,
) {
    val density = LocalDensity.current
    widthsPx.forEachIndexed { index, px ->
        Box(
            modifier = Modifier
                .width(with(density) { px.toDp() })
                .padding(horizontal = 10.dp, vertical = 7.dp),
        ) {
            content(index)
        }
    }
}

/**
 * 各列像素宽（纯函数，可单测）。
 *
 * `max(按权重分到的份额, 最小可读宽)`：份额确保「够宽时铺满、不留白边」，
 * 最小宽确保「不够宽时宁可撑开去滚动，也不把字挤扁」。
 *
 * 入参必须来自**已确定的视口宽**；若传进无限大（横向滚动容器给的约束），
 * 结果会溢出成非法值 —— 调用方务必在滚动容器**之外**取约束。
 */
internal fun columnWidths(
    weights: List<Float>,
    columnCount: Int,
    viewportPx: Float,
    minColumnWidthPx: Float,
): List<Float> {
    if (columnCount <= 0) return emptyList()
    // 兜底：视口宽不可信（无限 / 非有限）时退回「只按最小可读宽」，
    // 宁可撑开去滚动，也不能算出非法约束把界面打崩。
    val viewport = if (viewportPx.isFinite() && viewportPx > 0f) viewportPx else 0f
    val safe = (0 until columnCount).map { weights.getOrElse(it) { 1f }.coerceAtLeast(0.01f) }
    val total = safe.sum()
    return safe.map { w -> maxOf(viewport * w / total, minColumnWidthPx) }
}

/**
 * 一行里的各个单元格，按 [weights] 分配宽度。
 *
 * 用 `weight` 而不是 `width(dp)`：权重之和归一，一行必然铺满父宽度 ——
 * 这是「表头色条不留白边」的关键。`fillMaxWidth()` 已由调用方加在 Row 上。
 */
@Composable
private fun RowScope.TableCells(
    count: Int,
    weights: List<Float>,
    horizontalPadding: androidx.compose.ui.unit.Dp,
    verticalPadding: androidx.compose.ui.unit.Dp,
    content: @Composable (Int) -> Unit,
) {
    for (index in 0 until count) {
        Row(
            modifier = Modifier
                .weight(weights.getOrElse(index) { 1f })
                .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        ) {
            content(index)
        }
    }
}

/**
 * 单元格内容。
 *
 * 刻意**不设 `maxLines`**：列宽是按权重分的，窄列里的长文本要能折行看全
 * （早先硬定列宽 + 横向滚动，长内容被截断，用户截图里那行 Python/Node/OpenJDK
 * 就是断在右边）。内容过长仍由外层卡片的整体布局兜住，不会无限高。
 */
@Composable
private fun CellText(
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    color: Color,
    /** 内联代码的底色要与所在行一致，否则代码片会透出另一种灰。 */
    background: Color,
    linkListener: LinkInteractionListener?,
) {
    MiuixText(
        text = inlineMarkdown(text, background, MiuixTheme.colors.primary, linkListener = linkListener),
        style = style,
        color = color,
    )
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

/**
 * 按内容长度给各列分配**相对权重**（纯函数，可单测）。
 *
 * 与早先「按估算宽度硬定列宽」的差别：这里只产出比例，不产生绝对宽度，
 * 因此列宽总和恒等于可用宽度 —— 既不撑出屏幕，表头色条也不会右侧留白。
 *
 * 两步：
 * 1. 以每列**最长内容的估算宽度**为初始比例（等权会把「项 | 值」这种两列
 *    表格压成五五开，读起来很怪）
 * 2. 把低于下限的列抬到下限，多出来的份额在其余列之间按内容比例再分
 *
 * **不能「先夹紧再整体归一」**：归一会把刚抬到下限的列又压回去
 * （实测：短列拿到 0.138 而不是 0.16）。
 *
 * 刻意**不设上限**：与下限可能互相矛盾 —— 列数多时，(n-1) 列的下限之和
 * 加上一列的上限会超过 1，约束无法同时满足。下限本身已防住「一列独吞」。
 */
internal fun computeColumnWeights(
    header: List<String>,
    rows: List<List<String>>,
    columnCount: Int,
): List<Float> {
    if (columnCount <= 0) return emptyList()
    if (columnCount == 1) return listOf(1f)

    // 内容比例：+1 避免整列为空时权重为 0，导致该列宽度为 0、内容不可见
    val prop = (0 until columnCount).map { col ->
        val longest = buildList {
            header.getOrNull(col)?.let { add(cellWidthDp(it)) }
            rows.forEach { row -> row.getOrNull(col)?.let { add(cellWidthDp(it)) } }
        }.maxOrNull() ?: 0f
        (longest + 1f).toDouble()
    }
    val totalProp = prop.sum().takeIf { it > 0.0 } ?: return List(columnCount) { 1f / columnCount }

    // 列数多到「所有列都拿下限」都超过 1 时，约束本身不可满足，退化为均分。
    // 取 0.16 与 1/n 的较小者，保证任何列数下都可行。
    val minShare = minOf(MIN_COLUMN_SHARE, 1f / columnCount)

    val share = MutableList(columnCount) { 0f }
    val locked = BooleanArray(columnCount)
    var remaining = 1f

    // 每轮至少锁定一列，最多 n 轮收敛
    for (round in 0 until columnCount) {
        val free = (0 until columnCount).filter { !locked[it] }
        if (free.isEmpty()) break
        val freeProp = free.sumOf { prop[it] }
        var raised = false
        free.forEach { i ->
            val v = if (freeProp <= 0.0) remaining / free.size
            else (prop[i] / freeProp * remaining).toFloat()
            if (v < minShare) {
                share[i] = minShare
                locked[i] = true
                raised = true
            } else {
                share[i] = v
            }
        }
        if (!raised) break
        val used = (0 until columnCount).filter { locked[it] }.sumOf { share[it].toDouble() }.toFloat()
        remaining = (1f - used).coerceAtLeast(0f)
    }

    // 极端情况下（remaining 归零后又锁列）可能出现总和略偏；兜一次归一，
    // 此时各列都已不低于 minShare 的量级，归一不会把某列压没。
    val sum = share.sum()
    return if (sum <= 0f) List(columnCount) { 1f / columnCount } else share.map { it / sum }
}

/** 每列的最小宽度占比。低于它，窄列每行只放得下一两个字。 */
private const val MIN_COLUMN_SHARE = 0.16f

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
