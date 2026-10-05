package com.mcp.toolbox.ui.linux.term

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 隐藏输入框的「按键识别」逻辑。
 *
 * 这段逻辑不依赖 Compose，抽出来单独测：退格识别是踩过的坑——
 * 最初实现每次都把输入框清空，输入法缓冲一空就再不产生回调，退格彻底失效。
 */
class TerminalInputReducerTest {

    /**
     * 输入框文本变化的收敛逻辑。与 [com.mcp.toolbox.ui.linux.term.HiddenInput] 里一致。
     */
    private class Reducer {
        var buffer = ""
            private set
        val sent = StringBuilder()
        var deletes = 0
            private set
        var enters = 0
            private set

        fun onValueChange(new: String) {
            val old = buffer
            when {
                new.length > old.length -> {
                    val added = new.substring(old.length)
                    if (added.contains('\n')) {
                        sent.append(added.substringBefore('\n'))
                        enters++
                        buffer = ""
                    } else {
                        sent.append(added)
                        buffer = new
                    }
                }
                new.length < old.length -> {
                    deletes += old.length - new.length
                    buffer = new
                }
                else -> buffer = new
            }
        }
    }

    @Test
    fun typingAppendsToBufferAndEmits() {
        val r = Reducer()
        r.onValueChange("l")
        r.onValueChange("ls")
        assertEquals("ls", r.buffer)
        assertEquals("ls", r.sent.toString())
        assertEquals(0, r.deletes)
    }

    @Test
    fun backspaceEmitsDelAndShrinksBuffer() {
        val r = Reducer()
        r.onValueChange("abc")
        r.onValueChange("ab")
        assertEquals(1, r.deletes)
        // 缓冲必须跟着变短，否则下一次退格输入法又认为「无可删」
        assertEquals("ab", r.buffer)

        r.onValueChange("a")
        assertEquals(2, r.deletes)
        assertEquals("a", r.buffer)
    }

    @Test
    fun backspaceWithEmptyBufferIsNoop() {
        val r = Reducer()
        r.onValueChange("")
        assertEquals(0, r.deletes)
    }

    @Test
    fun deletingWholeLineSendsMatchingDelCount() {
        val r = Reducer()
        r.onValueChange("echo hi")
        r.onValueChange("")   // 输入法整行清空
        assertEquals(7, r.deletes)
        assertEquals("", r.buffer)
    }

    @Test
    fun enterSubmitsAndResetsBuffer() {
        val r = Reducer()
        r.onValueChange("pwd")
        r.onValueChange("pwd\n")
        assertEquals("pwd", r.sent.toString())
        assertEquals(1, r.enters)
        // 回车后缓冲清空：下一行重新开始，退格不会串到上一行
        assertEquals("", r.buffer)
    }

    @Test
    fun pasteWithNewlinesSubmitsFirstLine() {
        val r = Reducer()
        r.onValueChange("a\nb")
        assertEquals("a", r.sent.toString())
        assertEquals(1, r.enters)
        assertEquals("", r.buffer)
    }

    @Test
    fun selectionReplacementCountsAsDeleteThenInsert() {
        val r = Reducer()
        r.onValueChange("abcdef")
        // 选中后直接打新字符：长度可能不变或变短，都要能识别
        r.onValueChange("abcXef")
        assertEquals(0, r.deletes)
        assertEquals("abcXef", r.buffer)
    }
}
