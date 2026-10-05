package com.mcp.toolbox.ui.ai

import com.mcp.toolbox.feature.home.agent.SystemKind
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * [FaultMapper] 的分类验收。
 *
 * 背景：改动前失败路径一律写死 `SystemKind.ServerError`，导致「Key 填错」和
 * 「服务商抖了一下」显示成同一张卡片 —— 401 本该引导去设置密钥，却给了对 401
 * 毫无意义的「重试」按钮。这里把每条分支钉住。
 */
class FaultMapperTest {

    @Test
    fun `401 与 403 都归到需要检查密钥`() {
        assertEquals(SystemKind.AuthRequired, FaultMapper.classify(AiHttpException(401, "invalid api key")))
        assertEquals(SystemKind.AuthRequired, FaultMapper.classify(AiHttpException(403, "forbidden")))
    }

    @Test
    fun `429 归到限流`() {
        assertEquals(SystemKind.RateLimited, FaultMapper.classify(AiHttpException(429, "rate limited")))
    }

    @Test
    fun `5xx 归到服务端错误`() {
        listOf(500, 502, 503, 504).forEach { code ->
            assertEquals("HTTP $code 应归 ServerError", SystemKind.ServerError, FaultMapper.classify(AiHttpException(code, "boom")))
        }
    }

    @Test
    fun `其余 4xx 走 ServerError 兜底而不是误判成鉴权问题`() {
        listOf(400, 404, 422).forEach { code ->
            assertEquals("HTTP $code 不应归 AuthRequired", SystemKind.ServerError, FaultMapper.classify(AiHttpException(code, "bad")))
        }
    }

    @Test
    fun `超时归到 Stall`() {
        assertEquals(SystemKind.Stall, FaultMapper.classify(SocketTimeoutException("read timed out")))
    }

    @Test
    fun `解析失败与一般 IO 失败归到连接中断`() {
        assertEquals(SystemKind.NetworkLost, FaultMapper.classify(UnknownHostException("api.example.com")))
        assertEquals(SystemKind.NetworkLost, FaultMapper.classify(IOException("connection reset")))
    }

    @Test
    fun `未知异常与空值不让界面失去卡片`() {
        assertEquals(SystemKind.ServerError, FaultMapper.classify(null))
        assertEquals(SystemKind.ServerError, FaultMapper.classify(IllegalStateException("unexpected")))
    }

    @Test
    fun `子类判定顺序正确：超时不被当成一般 IO`() {
        // SocketTimeoutException / UnknownHostException / AiHttpException 都是 IOException 子类，
        // 若 IOException 分支排到前面，这两个会全部塌成 NetworkLost。
        assertEquals(SystemKind.Stall, FaultMapper.classify(SocketTimeoutException()))
        assertEquals(SystemKind.AuthRequired, FaultMapper.classify(AiHttpException(401, "")))
    }

    @Test
    fun `429 没有 Retry-After 头时给兜底秒数而不是 0`() {
        // 0 在界面上等于「立刻可以重试」，会把用户直接送进下一次 429。
        assertEquals(
            FaultMapper.DEFAULT_RETRY_AFTER_SECONDS,
            FaultMapper.retryAfterSeconds(AiHttpException(429, "rate limited")),
        )
    }

    @Test
    fun `429 带 Retry-After 头时用服务端给的值`() {
        assertEquals(12, FaultMapper.retryAfterSeconds(AiHttpException(429, "rate limited", retryAfterSeconds = 12)))
    }

    @Test
    fun `非 429 不产生等待秒数`() {
        assertEquals(0, FaultMapper.retryAfterSeconds(AiHttpException(500, "boom")))
        assertEquals(0, FaultMapper.retryAfterSeconds(null))
    }
}
