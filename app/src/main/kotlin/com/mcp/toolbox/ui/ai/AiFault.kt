package com.mcp.toolbox.ui.ai

import com.mcp.toolbox.feature.home.agent.SystemKind
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 带状态码的 HTTP 失败。
 *
 * 上游各条请求路径此前把状态码拍平成 `"HTTP 401：…"` 一个字符串，码本身随之丢失，
 * 下游只能一律当 [SystemKind.ServerError] 显示 —— 于是「Key 填错」和「服务商抖了一下」
 * 长得一模一样，都只给一个对 401 毫无意义的「重试」。这里把码保留下来供 [FaultMapper] 分类。
 */
class AiHttpException(
    val code: Int,
    val detail: String,
    /** 服务端 `Retry-After` 头给出的等待秒数；没有该头时为 0。 */
    val retryAfterSeconds: Int = 0,
) : IOException("HTTP $code：$detail")

/**
 * 把请求失败翻译成回答区该显示的那一类系统卡（Spec 第 8.3 节矩阵）。
 *
 * 刻意做成**纯函数**：不碰网络、不碰 Android API，因此可以直接单测。
 * 其中「根本没有可用网络」（[SystemKind.Offline]）需要 ConnectivityManager 才能判定，
 * 由调用方在拿到分类结果后再据此区分，见 [ChatRunner]。
 *
 * 尚未覆盖的两类，等有服务商实测样本再补：
 * - [SystemKind.Blocked]：各家内容安全拦截的标记不同（OpenAI 走 `finish_reason=content_filter`，
 *   也有服务商放在 `error.code` 里），无统一判据。
 * - [SystemKind.Truncated]：需要解析流式 `finish_reason == "length"`，属于响应体解析层。
 */
object FaultMapper {

    /** 429 未带 `Retry-After` 时的兜底等待秒数。 */
    const val DEFAULT_RETRY_AFTER_SECONDS = 30

    /**
     * 分类。注意判断顺序：`SocketTimeoutException` 与 `UnknownHostException` 都是
     * [IOException] 的子类，必须先于它命中；[AiHttpException] 同样是 [IOException] 子类，
     * 也要排在前面。
     */
    fun classify(t: Throwable?): SystemKind = when (t) {
        is AiHttpException -> when (t.code) {
            // 401 未授权、403 无权：都该引导去检查密钥，而不是让他反复点重试
            401, 403 -> SystemKind.AuthRequired
            429 -> SystemKind.RateLimited
            in 500..599 -> SystemKind.ServerError
            // 其余 4xx（400 参数错、404 路径错等）不是服务端故障，仍归 ServerError 兜底，
            // 因为它同样不适合「无脑重试」，与 5xx 的卡片行为一致。
            else -> SystemKind.ServerError
        }

        is SocketTimeoutException -> SystemKind.Stall
        is UnknownHostException -> SystemKind.NetworkLost
        is IOException -> SystemKind.NetworkLost
        else -> SystemKind.ServerError
    }

    /**
     * 429 的等待秒数。没有 `Retry-After` 头时给 [DEFAULT_RETRY_AFTER_SECONDS]，
     * 而不是 0 —— 0 在界面上等于「立刻可以重试」，会把用户直接送进下一次 429。
     */
    fun retryAfterSeconds(t: Throwable?): Int {
        val e = t as? AiHttpException ?: return 0
        return if (e.code == 429 && e.retryAfterSeconds <= 0) {
            DEFAULT_RETRY_AFTER_SECONDS
        } else {
            e.retryAfterSeconds
        }
    }
}
