package com.mcp.toolbox.core.design.component

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.text.BasicTextField
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcp.toolbox.core.design.theme.MiuixColors
import com.mcp.toolbox.core.design.theme.MiuixTheme

/** 代码语言：决定关键字集合与注释符号。 */
enum class CodeLanguage { TEXT, JSON, SQL, JAVA, KOTLIN, SMALI, HTTP }

private val SQL_KEYWORDS = setOf(
    "select", "from", "where", "order", "by", "limit", "offset", "group", "having", "join",
    "left", "right", "inner", "outer", "on", "insert", "into", "values", "update", "set",
    "delete", "create", "table", "index", "view", "drop", "alter", "and", "or", "not", "null",
    "as", "asc", "desc", "distinct", "count", "sum", "avg", "max", "min", "pragma", "explain",
)

private val JAVA_KEYWORDS = setOf(
    "public", "private", "protected", "class", "interface", "enum", "extends", "implements",
    "static", "final", "void", "return", "new", "package", "import", "if", "else", "for",
    "while", "do", "switch", "case", "break", "continue", "try", "catch", "finally", "throw",
    "throws", "this", "super", "null", "true", "false", "int", "long", "float", "double",
    "boolean", "char", "byte", "short", "abstract", "synchronized", "override", "protected",
)

private val SMALI_KEYWORDS = setOf(
    ".class", ".super", ".source", ".method", ".end", ".field", ".line", ".locals", ".param",
    ".annotation", ".registers", ".prologue", "invoke-virtual", "invoke-static", "invoke-direct",
    "return-void", "const-string", "move-result", "goto", "if-eqz", "iput", "iget",
)

/** 极简高亮器：切分关键字、字符串、注释与数字，足够支撑代码与 SQL 阅读。 */
fun highlightCodeLine(line: String, language: CodeLanguage, colors: MiuixColors): AnnotatedString {
    if (language == CodeLanguage.TEXT) return AnnotatedString(line)
    val commentStart = when (language) {
        CodeLanguage.SQL -> "--"
        CodeLanguage.HTTP -> "#"
        else -> "//"
    }
    val trimmed = line.trimStart()
    if (trimmed.startsWith(commentStart) || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
        return AnnotatedString(line, SpanStyle(color = colors.codeComment))
    }
    val keywords = when (language) {
        CodeLanguage.SQL -> SQL_KEYWORDS
        CodeLanguage.JAVA, CodeLanguage.KOTLIN -> JAVA_KEYWORDS
        CodeLanguage.SMALI -> SMALI_KEYWORDS
        else -> emptySet()
    }
    return buildAnnotatedString {
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' || c == '\'' -> {
                    val found = line.indexOf(c, i + 1)
                    val end = (if (found < 0) line.length - 1 else found) + 1
                    append(line.substring(i, end))
                    addStyle(SpanStyle(color = colors.codeString), start = i, end = end)
                    i = end
                }
                c.isDigit() -> {
                    var j = i
                    while (j < line.length && (line[j].isDigit() || line[j] == '.')) j++
                    append(line.substring(i, j))
                    addStyle(SpanStyle(color = colors.codeType), start = i, end = j)
                    i = j
                }
                c.isLetter() || c == '.' || c == '_' || c == '@' -> {
                    var j = i
                    while (j < line.length &&
                        (line[j].isLetterOrDigit() || line[j] == '.' || line[j] == '_' || line[j] == '@')
                    ) {
                        j++
                    }
                    val word = line.substring(i, j)
                    append(word)
                    when {
                        keywords.contains(word.lowercase()) ->
                            addStyle(
                                SpanStyle(color = colors.codeKeyword, fontWeight = FontWeight.Medium),
                                start = i,
                                end = j,
                            )
                        word.first().isUpperCase() ->
                            addStyle(SpanStyle(color = colors.codeType), start = i, end = j)
                    }
                    i = j
                }
                else -> {
                    append(c)
                    i++
                }
            }
        }
    }
}

/**
 * 代码块：等宽字体 + 可选行号 + 极简高亮，统一数据库、反编译与网络三处的代码呈现。
 */
@Composable
fun MiuixCodeText(
    code: String,
    modifier: Modifier = Modifier,
    language: CodeLanguage = CodeLanguage.TEXT,
    showLineNumbers: Boolean = true,
    fontSize: TextUnit = 13.sp,
    lineHeight: TextUnit = 21.sp,
    gutterWidth: Dp = 34.dp,
) {
    val colors = MiuixTheme.colors
    val lines = remember(code) { code.split('\n') }
    // 高亮结果跟着 code / 语言 / 配色缓存：缩放只改字号，不重跑整篇解析。
    val highlighted = remember(lines, language, colors) {
        lines.map { highlightCodeLine(it, language, colors) }
    }
    val horizontal: ScrollState = rememberScrollState()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.codeBackground, RoundedCornerShape(MiuixTheme.radius.sm))
            .padding(vertical = 12.dp),
    ) {
        Box(Modifier.horizontalScroll(horizontal)) {
            Column {
                lines.forEachIndexed { index, _ ->
                    Row(Modifier.padding(horizontal = 12.dp)) {
                        if (showLineNumbers) {
                            MiuixText(
                                text = (index + 1).toString(),
                                modifier = Modifier.width(gutterWidth),
                                style = MiuixTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = fontSize,
                                    lineHeight = lineHeight,
                                ),
                                color = colors.codeComment,
                            )
                        }
                        Text(
                            text = highlighted[index],
                            style = MiuixTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = fontSize,
                                lineHeight = lineHeight,
                            ),
                            color = colors.onSurface,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 整篇高亮：编辑态用它做 VisualTransformation。
 *
 * 只做长度不变的样式叠加，配 OffsetMapping.Identity 让光标位置和原文一一对应，
 * 输入时不会因为样式变换而错位。
 */
fun highlightCode(code: String, language: CodeLanguage, colors: MiuixColors): AnnotatedString {
    if (language == CodeLanguage.TEXT) return AnnotatedString(code)
    val lines = code.split('\n')
    return buildAnnotatedString {
        lines.forEachIndexed { index, line ->
            if (index > 0) append('\n')
            append(highlightCodeLine(line, language, colors))
        }
    }
}

private fun codeHighlightTransformation(language: CodeLanguage, colors: MiuixColors) =
    VisualTransformation { text -> TransformedText(highlightCode(text.text, language, colors), OffsetMapping.Identity) }

/** 行内代码片段：用于在正文里嵌入字段名、命令。 */
@Composable
fun MiuixInlineCode(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MiuixTheme.colors.primary,
) {
    MiuixText(
        text = text,
        modifier = modifier
            .background(MiuixTheme.colors.codeBackground, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        style = MiuixTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        color = color,
    )
}

/** 等宽代码编辑器：请求体、SQL 与代码片段共用同一视觉与行为。 */
@Composable
fun CodeEditor(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    minHeight: Dp = 220.dp,
    readOnly: Boolean = false,
    fontSize: TextUnit = 13.sp,
    lineHeight: TextUnit = 20.sp,
    autoFocus: Boolean = false,
    initialSelection: Int = -1,
    language: CodeLanguage = CodeLanguage.TEXT,
) {
    val colors = MiuixTheme.colors
    // 光标初始落点：点正文进来时落在手指按下的位置，而不是回到开头重新找。
    // -1 表示不指定，按原来的行为落在末尾。
    var caret by remember(initialSelection) { mutableIntStateOf(initialSelection) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(autoFocus) {
        if (autoFocus) {
            runCatching { focusRequester.requestFocus() }
            // 只请求焦点不会唤出软键盘，这里再显式要一次：点一下就能直接打字。
            delay(120)
            runCatching { keyboard?.show() }
        }
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .background(colors.codeBackground, RoundedCornerShape(MiuixTheme.radius.sm))
            .border(1.dp, colors.outlineVariant, RoundedCornerShape(MiuixTheme.radius.sm))
            .padding(12.dp),
    ) {
        BasicTextField(
            value = TextFieldValue(
                text = value,
                selection = if (caret < 0) TextRange(value.length) else TextRange(caret.coerceIn(0, value.length)),
            ),
            onValueChange = { next ->
                caret = next.selection.start
                onValueChange(next.text)
            },
            readOnly = readOnly,
            textStyle = androidx.compose.ui.text.TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = fontSize,
                lineHeight = lineHeight,
                color = colors.onSurface,
            ),
            cursorBrush = SolidColor(colors.primary),
            visualTransformation = remember(language, colors) { codeHighlightTransformation(language, colors) },
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
        )
    }
}
