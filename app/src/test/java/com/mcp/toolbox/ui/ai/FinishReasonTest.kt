package com.mcp.toolbox.ui.ai

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 终止原因的归一（[FaultMapper.finishReasonOf]）。
 *
 * 四家字段名与取值都不同，写错一个就会把「正常写完」误报成截断、或者漏报拦截。
 */
class FinishReasonTest {

    @Test
    fun `OpenAI 兼容的三种取值`() {
        assertEquals(FinishReason.Length, FaultMapper.finishReasonOf("length"))
        assertEquals(FinishReason.Blocked, FaultMapper.finishReasonOf("content_filter"))
        assertEquals(FinishReason.Normal, FaultMapper.finishReasonOf("stop"))
    }

    @Test
    fun `Anthropic 的取值`() {
        assertEquals(FinishReason.Length, FaultMapper.finishReasonOf("max_tokens"))
        assertEquals(FinishReason.Blocked, FaultMapper.finishReasonOf("refusal"))
        assertEquals(FinishReason.Normal, FaultMapper.finishReasonOf("end_turn"))
    }

    @Test
    fun `Gemini 的大写下划线写法同样命中`() {
        // 大小写与写法都不统一：max_tokens 与 MAX_TOKENS 必须落到同一类
        assertEquals(FinishReason.Length, FaultMapper.finishReasonOf("MAX_TOKENS"))
        assertEquals(FinishReason.Blocked, FaultMapper.finishReasonOf("SAFETY"))
        assertEquals(FinishReason.Normal, FaultMapper.finishReasonOf("STOP"))
    }

    @Test
    fun `缺失或不可识别一律按未知，不误报`() {
        // 自建网关用什么标记未知；宁可当作正常结束，也不要把好回答标成截断/拦截
        assertEquals(FinishReason.Unknown, FaultMapper.finishReasonOf(null))
        assertEquals(FinishReason.Unknown, FaultMapper.finishReasonOf(""))
        assertEquals(FinishReason.Unknown, FaultMapper.finishReasonOf("null"))
        assertEquals(FinishReason.Unknown, FaultMapper.finishReasonOf("   "))
        assertEquals(FinishReason.Unknown, FaultMapper.finishReasonOf("something_new"))
    }

    @Test
    fun `前后空白容错`() {
        assertEquals(FinishReason.Length, FaultMapper.finishReasonOf("  length "))
    }
}
