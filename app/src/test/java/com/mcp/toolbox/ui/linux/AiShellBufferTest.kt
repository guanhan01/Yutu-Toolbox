package com.mcp.toolbox.ui.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 持久会话的输出缓冲与哨兵匹配。
 *
 * 这段逻辑没法靠真机目视确认：哨兵跨越分块、后台任务在两次调用之间插话、
 * 缓冲被裁剪——三种情况叠加时的行为必须能静态验证，否则「模型拿到别人的输出」
 * 这种错误只会在偶发时机出现。
 */
class AiShellBufferTest {

    /** shell 变量前缀。在 Kotlin 字符串里要转义，集中放这里避免踩模板坑。 */
    private val DOLLAR = "$"

    /**
     * 与 [AiShellSession] 里 Live 的缓冲语义一致：
     * 接住分块、按起点找哨兵、只丢弃已消费的部分。
     */
    private class Buffer {
        private val sb = StringBuilder()
        private var closed = false

        fun append(chunk: String) {
            sb.append(chunk)
        }

        fun close() {
            closed = true
        }

        /** 与实现一致：起点恒为 0，缓冲里只留未消费的字节。 */
        fun mark(): Int = 0

        sealed interface Outcome
        data class Done(val text: String, val exitCode: Int) : Outcome
        data object Ended : Outcome
        data class Timed(val text: String) : Outcome

        fun await(marker: String, from: Int): Outcome {
            val idx = sb.indexOf(marker, from)
            if (idx >= 0) {
                val after = sb.substring(idx + marker.length)
                val nl = after.indexOf('\n')
                if (nl >= 0) {
                    val code = after.take(nl).trim().toIntOrNull()
                    if (code != null) {
                        val text = sb.substring(from, idx)
                        sb.delete(0, idx + marker.length + nl + 1)
                        return Done(text.trimEnd(), code)
                    }
                }
            }
            if (closed) return Ended
            return Timed(if (from < sb.length) sb.substring(from).trimEnd() else "")
        }
    }

    private val marker = "__ETA_END_e1__"

    /**
     * 按真实协议产出哨兵：`printf '\n__ETA_END_<id>__%d\n' $?`（Kotlin 里 $ 要转义）。
     * 退出码紧接标记、之后才是换行——写成 marker + 换行 + code 是错的，
     * 解析时会把空串当退出码（这个夹具最初就这么错过了，导致 4 个用例误报失败）。
     */
    private fun emit(code: Int, m: String = marker): String = m + code + "\n"

    @Test
    fun plainOutputAndExitCode() {
        val b = Buffer()
        b.append("hello\nworld\n")
        b.append(emit(0))
        val out = b.await(marker, 0)
        assertTrue(out is Buffer.Done)
        out as Buffer.Done
        assertEquals("hello\nworld", out.text)
        assertEquals(0, out.exitCode)
    }

    @Test
    fun sentinelSplitAcrossChunks() {
        val b = Buffer()
        val from = b.mark()
        // 哨兵被切成三段：真实网络/IO 分块下必然发生
        b.append("partial out\n__ETA_END_")
        b.append("e1__")
        b.append("3\n")
        val out = b.await(marker, from) as Buffer.Done
        assertEquals("partial out", out.text)
        assertEquals(3, out.exitCode)
    }

    @Test
    fun exitCodeSplitFromSentinel() {
        val b = Buffer()
        val from = b.mark()
        b.append(marker)
        // 只有标记、还没有退出码：不能误判为完成
        assertTrue(b.await(marker, from) is Buffer.Timed)
        b.append("12")
        assertTrue(b.await(marker, from) is Buffer.Timed)
        b.append("7\n")
        assertEquals(127, (b.await(marker, from) as Buffer.Done).exitCode)
    }

    @Test
    fun outputAfterSentinelIsKeptForNextCall() {
        val b = Buffer()
        val from = b.mark()
        b.append("cmd out\n" + emit(0))
        // 哨兵之后是后台任务的新输出：必须留给下一次调用，丢了模型就看到不到后台进展
        b.append("bg line 1\nbg line 2\n")
        val first = b.await(marker, from) as Buffer.Done
        assertEquals("cmd out", first.text)

        val next = b.mark()
        val marker2 = "__ETA_END_e2__"
        b.append(emit(0, marker2))
        val second = b.await(marker2, next) as Buffer.Done
        // 关键：这两行是在上一次调用返回之后才到的，它们不属于任何一次调用，
        // 但必须让模型看到（否则后台任务的日志就永久丢了）
        assertTrue("两次调用之间的后台输出必须保留，实际「${second.text}」", second.text.contains("bg line 1"))
        assertTrue(second.text.contains("bg line 2"))
        // 上一次已经返回过的内容不能重复出现
        assertFalse("已消费的输出不应重复返回，实际「${second.text}」", second.text.contains("cmd out"))
    }

    @Test
    fun eofReportsEndedNotHang() {
        val b = Buffer()
        val from = b.mark()
        b.append("about to die\n")
        b.close()
        // exit 杀掉会话：必须明确返回 Ended，否则调用方会一直等到超时
        assertTrue(b.await(marker, from) is Buffer.Ended)
    }

    @Test
    fun staleSentinelFromPreviousCallIsIgnored() {
        val b = Buffer()
        // 场景：上一次调用超时先返回了，命令之后才跑完并打出了它自己的哨兵。
        b.append("late output\n" + emit(0))
        val from = b.mark()

        // 本次调用还没结束（marker2 未出现）——不能因为缓冲里有个哨兵就以为完成了
        val pending = b.await("__ETA_END_e2__", from)
        assertTrue("残留哨兵不得被当作本次结果，实际 $pending", pending is Buffer.Timed)

        // 本次调用的哨兵到达后，才能正常拿到结果与退出码
        b.append("fresh\n" + emit(2, "__ETA_END_e2__"))
        val out = b.await("__ETA_END_e2__", from) as Buffer.Done
        assertEquals(2, out.exitCode)
        assertTrue("本次输出应包含 fresh，实际「${out.text}」", out.text.contains("fresh"))
    }

    @Test
    fun timeoutReturnsPartialOutput() {
        val b = Buffer()
        val from = b.mark()
        b.append("still running…\n")
        val out = b.await(marker, from) as Buffer.Timed
        assertEquals("still running…", out.text)
    }

    // ---------- 命令守卫 ----------

    private val guardRegex = Regex("""(^|[;&|]\s*|\n\s*)(exit|logout)\s*;?\s*$""")

    private fun guarded(cmd: String): Boolean {
        val t = cmd.trim()
        return t == "exit" || t == "logout" || guardRegex.containsMatchIn(t)
    }

    @Test
    fun bareExitIsBlocked() {
        assertTrue(guarded("exit"))
        assertTrue(guarded("logout"))
        assertTrue(guarded("cd /root; exit"))
        assertTrue(guarded("echo hi && exit"))
        assertTrue(guarded("true\nexit"))
        assertTrue(guarded("exit;"))
    }

    @Test
    fun safeExitFormsAreAllowed() {
        // 子 shell 里 exit 是安全的，正是我们给模型的替代写法
        assertFalse(guarded("(exit 0)"))
        assertFalse(guarded("sh -c 'exit 3'"))
        assertFalse(guarded("echo 'exit'"))
        // 不该把正常命令当成 exit
        assertFalse(guarded("echo exited"))
        assertFalse(guarded("exit_code=5; echo " + DOLLAR + "exit_code"))
        assertFalse(guarded("ls -la"))
    }
}
