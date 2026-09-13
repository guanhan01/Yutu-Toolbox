package com.mcp.toolbox.feature.decompile

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.mcp.toolbox.core.design.component.CodeLanguage
import com.mcp.toolbox.core.design.component.MiuixText
import com.mcp.toolbox.core.design.component.highlightCodeLine
import com.mcp.toolbox.core.design.theme.MiuixTheme

/**
 * 惰性代码视图：只渲染可见行。
 *
 * 缩放走真实字号（不做视觉缩放），所以放大后内容会正常撑开布局、交给滚动容器接管，
 * 不会被裁剪在原来的框里。为了扛住捏合时的高频重排，这里做了三件事：
 *
 * 1. 内容宽度用最长一行实测，不再按 0.6 字宽估算。估算误差会随行宽累积，
 *    长行要么被裁掉、要么折行，缩放时就显得"被框住 / 割裂"。
 * 2. 每行都 softWrap=false：一行代码永远是一行，行号和正文不会错位。
 * 3. pointerInput 的 key 不含字号，排版结果存在非 state 的持有器里。
 *    否则每改一次字号都要重建所有可见行的手势协程并触发二次重组，这才是真正卡爆的原因。
 */
@Composable
fun LazyCodeView(
    code: String,
    language: CodeLanguage,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    onTapOffset: (Int) -> Unit,
    modifier: Modifier = Modifier,
    gutterWidth: Dp = 34.dp,
) {
    val colors = MiuixTheme.colors
    val density = LocalDensity.current
    val lines = remember(code) { code.split('\n') }
    // 高亮跟着 code / 语言 / 配色缓存：缩放只改字号，不重跑解析。
    val highlighted = remember(lines, language, colors) {
        lines.map { highlightCodeLine(it, language, colors) }
    }
    // 每行起始偏移，点击时把「行内列」换算成全文偏移。
    val lineStarts = remember(lines) {
        IntArray(lines.size).also { starts ->
            var pos = 0
            lines.indices.forEach { index ->
                starts[index] = pos
                pos += lines[index].length + 1
            }
        }
    }
    // typography 是 @Composable 取值，必须先取出来，不能塞进 remember 的 lambda 里。
    val typography = MiuixTheme.typography
    val style = remember(fontSize, lineHeight, typography) {
        typography.bodySmall.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = fontSize,
            lineHeight = lineHeight,
        )
    }
    val measurer = rememberTextMeasurer()
    val longestLine = remember(lines) { lines.maxByOrNull { it.length }.orEmpty() }
    // 等宽字体下最长的一行就是最宽的一行，实测一次即可，跟文件多大无关。
    val textWidthPx = remember(longestLine, style, measurer) {
        measurer.measure(
            text = AnnotatedString(longestLine.ifEmpty { "M" }),
            style = style,
            softWrap = false,
            maxLines = 1,
        ).size.width
    }
    val contentWidth = with(density) { textWidthPx.toDp() } + gutterWidth + 28.dp
    val horizontal = rememberScrollState()
    val listState = rememberLazyListState()
    val latestTap by rememberUpdatedState(onTapOffset)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.codeBackground, RoundedCornerShape(MiuixTheme.radius.sm))
            .horizontalScroll(horizontal),
    ) {
        LazyColumn(state = listState, modifier = Modifier.width(contentWidth).padding(vertical = 12.dp)) {
            items(highlighted.size, key = { it }) { index ->
                // 排版结果放在普通对象里：写进 state 会让每行在每次排版后都再重组一次。
                val holder = remember { LayoutHolder() }
                val startOffset = lineStarts[index]
                Row(Modifier.padding(horizontal = 12.dp)) {
                    MiuixText(
                        text = (index + 1).toString(),
                        modifier = Modifier.width(gutterWidth),
                        style = style,
                        color = colors.codeComment,
                    )
                    BasicText(
                        text = highlighted[index],
                        style = style,
                        color = { colors.onSurface },
                        // 一行就是一行，不折行：折行会让行号跟正文错位，看起来就是"割裂"。
                        softWrap = false,
                        maxLines = 1,
                        onTextLayout = { holder.value = it },
                        modifier = Modifier.pointerInput(index) {
                            detectTapGestures { position ->
                                // 用真实排版结果命中字符，不再按字宽估算：点哪就是哪。
                                val result = holder.value ?: return@detectTapGestures
                                val column = result.getOffsetForPosition(position)
                                latestTap(startOffset + column)
                            }
                        },
                    )
                }
            }
        }
    }
}

/** 保存最近一次排版结果，避免用 state 造成额外重组。 */
private class LayoutHolder {
    var value: TextLayoutResult? = null
}
