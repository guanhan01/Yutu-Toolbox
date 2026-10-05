package com.mcp.toolbox.feature.home.agent.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TokenBuffer] 的节流行为验收。
 *
 * 对应 Spec 第 5.1 节 ① 的提交条件，以及第 13 节的验收项
 * 「200 token 回答实测重组次数 <50」。
 */
class TokenBufferTest {

    @Test
    fun `流的第一个 token 立即提交`() {
        val buffer = TokenBuffer()
        assertEquals("a", buffer.offer("a", nowMs = 0))
        assertEquals(1, buffer.commits)
    }

    @Test
    fun `第二个 token 不会因为凑巧是唯一待提交项就立刻提交`() {
        // 回归测试：曾经的实现用 `pendingTokens == 1` 当「首 token」判据，
        // 结果每个 token 都走立即提交分支，节流完全失效（200 次提交）。
        val buffer = TokenBuffer()
        buffer.offer("a", nowMs = 0)
        assertNull(buffer.offer("b", nowMs = 1))
        assertEquals(1, buffer.commits)
        assertEquals(1, buffer.bufferedChars)
    }

    @Test
    fun `token 数与时间都满足才提交`() {
        val buffer = TokenBuffer()
        buffer.offer("a", nowMs = 0)
        // 攒到 3 个 token 但只过了 3ms —— 时间不够，仍不提交
        assertNull(buffer.offer("b", nowMs = 1))
        assertNull(buffer.offer("c", nowMs = 2))
        assertNull(buffer.offer("d", nowMs = 3))
        // 时间到 80ms，同时满足 token 数与时间 → 提交（含本次的 e）
        assertEquals("bcde", buffer.offer("e", nowMs = 80))
        assertEquals(2, buffer.commits)
    }

    @Test
    fun `满 64 字符兜底提交`() {
        val buffer = TokenBuffer()
        buffer.offer("x", nowMs = 0)
        // 1 个 token、时间也没到，但字符数够 → 兜底提交
        assertEquals("y".repeat(64), buffer.offer("y".repeat(64), nowMs = 1))
    }

    @Test
    fun `稀疏 token 不会饿死——静默超时兜底`() {
        // 每 300ms 才来一个短 token：token 数永远到不了 3，
        // 若只有「与」逻辑就永远不刷新，画面一动不动。
        val buffer = TokenBuffer()
        buffer.offer("a", nowMs = 0)
        // 静默 300ms ≥ 250ms → 兜底提交，而不是继续攒着
        assertEquals("b", buffer.offer("b", nowMs = 300))
        assertEquals(2, buffer.commits)
        assertEquals("ab", buffer.committed)
        // 对照：静默未超时（150ms）时仍然不提交
        assertNull(buffer.offer("c", nowMs = 380))
    }

    @Test
    fun `流结束冲刷残缓冲不丢字`() {
        val buffer = TokenBuffer()
        buffer.offer("hello", nowMs = 0)
        buffer.offer(" world", nowMs = 1)
        assertEquals(1, buffer.commits)
        assertEquals(" world", buffer.flush())
        assertEquals("hello world", buffer.committed)
    }

    @Test
    fun `空缓冲的冲刷返回 null`() {
        assertNull(TokenBuffer().flush())
    }

    @Test
    fun `不可见时降频到 200ms`() {
        val buffer = TokenBuffer()
        buffer.offer("a", nowMs = 0)
        buffer.setVisible(false)
        // 130ms：token 已够 3 个，但不可见时阈值是 200ms → 不提交
        // （若可见，80ms 阈值早已满足，这里就会提交）
        assertNull(buffer.offer("b", nowMs = 60))
        assertNull(buffer.offer("c", nowMs = 130))
        // 210ms：超过不可见阈值 → 提交
        assertNotNull(buffer.offer("d", nowMs = 210))
    }

    @Test
    fun `200 token 的回答提交次数不超过 40`() {
        // 模拟真实 SSE：每个 token 约 15ms 到达、每个 4 字符
        val buffer = TokenBuffer()
        var now = 0L
        repeat(200) {
            now += 15
            buffer.offer("tok_", nowMs = now)
        }
        buffer.flush()
        assertTrue("提交次数应 < 40，实际 ${buffer.commits}", buffer.commits < 40)
        // 一个字符都不能丢
        assertEquals(200 * 4, buffer.committed.length)
    }

    @Test
    fun `超长缓冲触发背压而不是丢弃`() {
        val buffer = TokenBuffer()
        buffer.offer("a", nowMs = 0)
        val huge = "z".repeat(5000)
        val out = buffer.offer(huge, nowMs = 1)
        assertEquals(huge, out)
        assertEquals(2, buffer.commits)
    }

    @Test
    fun `reset 清空全部状态`() {
        val buffer = TokenBuffer()
        buffer.offer("abc", nowMs = 0)
        buffer.reset()
        assertEquals(0, buffer.commits)
        assertEquals("", buffer.committed)
        assertEquals(0, buffer.bufferedChars)
        // reset 后第一个 token 仍应走「立即提交」
        assertEquals("a", buffer.offer("a", nowMs = 0))
    }
}
