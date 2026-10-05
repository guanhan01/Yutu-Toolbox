package com.mcp.toolbox.ui.linux.term

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回放历史的有界策略。
 *
 * 这段逻辑决定了「重新挂载终端时是白屏还是恢复内容」——边界（裁剪、对齐行首、
 * 游标失效）没法靠真机目视确认，所以抽成纯 Kotlin 单独测。
 */
class OutputHistoryTest {

    private fun bytes(text: String) = text.toByteArray(Charsets.UTF_8)

    private fun textOf(history: OutputHistory) = history.readFrom(-1L).second.toString(Charsets.UTF_8)

    private fun since(history: OutputHistory, cursor: Long) =
        history.readFrom(cursor).let { it.first to it.second.toString(Charsets.UTF_8) }

    @Test
    fun keepsEverythingWhileUnderLimit() {
        val h = OutputHistory(64)
        h.append(bytes("abc\n"))
        assertEquals("abc\n", textOf(h))
        assertEquals(4L, h.totalWritten)
        assertEquals(0L, h.dropped)
    }

    @Test
    fun readFromReturnsOnlyNewBytes() {
        val h = OutputHistory(64)
        h.append(bytes("one\n"))
        h.append(bytes("two\n"))
        val (cursor, chunk) = since(h, 4L)
        assertEquals("two\n", chunk)
        assertEquals(8L, cursor)
        assertEquals("", since(h, 8L).second)
    }

    @Test
    fun trimsOldestAndAlignsToLineStart() {
        val h = OutputHistory(12)
        h.append(bytes("aaaa\n"))
        h.append(bytes("bbbb\n"))
        h.append(bytes("cccc\n"))
        val out = textOf(h)
        assertEquals(h.totalWritten, h.dropped + out.length)
        assertTrue("不应超过上限，实际 ${out.length}", out.length <= 12)
        assertTrue("应从整行开头开始，实际 $out", out.startsWith("b"))
        assertTrue("最新内容必须保留，实际 $out", out.endsWith("cccc\n"))
    }

    @Test
    fun staleCursorFallsBackToFullSnapshot() {
        val h = OutputHistory(12)
        h.append(bytes("aaaa\n"))
        h.append(bytes("bbbb\n"))
        h.append(bytes("cccc\n"))
        // 游标比 dropped 还旧 -> 调用方必须整屏重放
        assertEquals(textOf(h), since(h, 0L).second)
        assertEquals("cccc\n", since(h, 10L).second)
    }

    @Test
    fun oversizedChunkKeepsTail() {
        val h = OutputHistory(8)
        h.append(bytes("0123456789\n"))
        assertEquals(11L, h.totalWritten)
        assertEquals(3L, h.dropped)
        assertEquals("3456789\n", textOf(h))
    }

    @Test
    fun splitAppendsMatchSingleAppend() {
        val one = OutputHistory(32)
        one.append(bytes("line1\nline2\nline3\n"))
        val many = OutputHistory(32)
        "line1\nline2\nline3\n".forEach { many.append(bytes(it.toString())) }
        assertEquals(textOf(one), textOf(many))
        assertEquals(one.totalWritten, many.totalWritten)
    }

    @Test
    fun chunkOffsetsAreRespected() {
        val h = OutputHistory(32)
        val buf = bytes("#ls -la#")
        h.append(buf, 1, 6)
        assertEquals("ls -la", textOf(h))
    }
}
