package com.mcp.toolbox.feature.home.agent

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [ToolVisuals.formatEta] 的格式化边界。
 *
 * 它决定等待类工具卡片上「还需等待 N」怎么显示，负数与时序抖动都必须有确定结果。
 */
class FormatEtaTest {

    @Test
    fun `不足一分钟按秒显示`() {
        assertEquals("0s", ToolVisuals.formatEta(0))
        assertEquals("1s", ToolVisuals.formatEta(1_000))
        assertEquals("59s", ToolVisuals.formatEta(59_000))
    }

    @Test
    fun `不足一秒向下取整而不是进一`() {
        // 900ms 应显示 0s：对「还要等多久」来说，宁可显示得短一点，
        // 也不要显示 1s 却马上归零
        assertEquals("0s", ToolVisuals.formatEta(900))
        assertEquals("1s", ToolVisuals.formatEta(1_900))
    }

    @Test
    fun `超过一分钟显示分秒`() {
        assertEquals("1:00", ToolVisuals.formatEta(60_000))
        assertEquals("1:05", ToolVisuals.formatEta(65_000))
        assertEquals("10:00", ToolVisuals.formatEta(600_000))
    }

    @Test
    fun `负数按零处理`() {
        // 调用方可能因时序拿到刚过期的 deadline，不该显示成 -1s
        assertEquals("0s", ToolVisuals.formatEta(-1))
        assertEquals("0s", ToolVisuals.formatEta(-5_000))
    }
}
