package com.mcp.toolbox.feature.home.agent

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay

/**
 * 回答区的**演示脚本**（仅 debug 构建、仅显式触发）。
 *
 * ## 为什么需要它
 * 真实流式需要服务商 API Key，而回答区最容易出错的地方恰恰是
 * 「思考 → 工具 → 再思考 → 正文」这条时间线、以及各种异常分支的视觉表现。
 * 没有 Key 就只能靠读代码猜，这不叫验证。
 *
 * 这里用**真实的事件类型**喂**真实的状态机与真实组件**，唯一被替换掉的
 * 是网络来源。所以它验证的是渲染与状态逻辑，不验证网络解析
 * （后者由 `:feature:home:testDebugUnitTest` 的单测覆盖）。
 *
 * ⚠️ 入口由 [DemoRunner] 控制：只在 debug 构建可见，且**必须由人点一下**才播。
 * release 构建里入口不存在，R8 会把这条路径整段裁掉。
 */
object AgentDemoScript {

    /**
     * 跑一遍「完整一轮」：思考两段、工具两枚（成功 + 成功）、若干正文、
     * 中途一次自动收起。
     */
    suspend fun play(state: AgentChatState, brief: Boolean = false) {
        fun ev(block: (AgentStreamSink) -> Unit) = block(state)

        // 用户刚点完发送：乐观占位
        ev { it.onStarted(round = 1) }
        delay(if (brief) 40 else 120)

        // ── 第 1 轮：思考 → 工具 ──
        ev { it.onThinking(1, "先看看用户问的是什么。") }
        delay(260)
        ev { it.onThinking(1, "需要先拿到设备信息，看看是不是 Miuix 环境。") }
        delay(if (brief) 80 else 420)

        ev { it.onToolCall(1, 0, "device.info", """{"fields":["model","sdk","abi"]}""") }
        delay(if (brief) 150 else 700)
        ev {
            it.onToolResult(
                1, 0, "device.info",
                "model=2510DRK44C\nsdk=36\nabi=arm64-v8a\n品牌=Xiaomi\nHyperOS=3",
                true,
            )
        }
        delay(if (brief) 60 else 200)

        // ── 第 2 轮：继续思考 → 第二个工具 ──
        ev { it.onThinking(2, "确认是 HyperOS，可以读系统字体目录来验证 MiSans 是否存在。") }
        delay(if (brief) 90 else 500)
        ev { it.onToolCall(2, 1, "file.list", """{"path":"/system/fonts","filter":"MiSans"}""") }
        delay(if (brief) 150 else 650)
        ev {
            it.onToolResult(
                2, 1, "file.list",
                "MiSansVF.ttf\nMiSansLatinVF.ttf\nMiSansTCVF.ttf\nMiSansJapaneseVF.ttf\n" +
                    "MiSansArabicVF.ttf\nMiSansKoreanVF.ttf",
                true,
            )
        }
        delay(if (brief) 60 else 220)

        // ── 正文：逐字流入（经 TokenBuffer 节流后落屏） ──
        val answer = buildString {
            append("环境已确认：这台设备是 **HyperOS 3**（SDK 36），系统字体目录里存在 ")
            append("`MiSansVF.ttf` 等可变字体文件。\n\n")
            append("所以「用 MiSans 作为界面字体」**不需要把字体打包进 APK**，")
            append("直接走系统默认字体即可，字重通过可变轴切。\n\n")
            append("### 需要注意的两点\n\n")
            append("1. 打包字体只会让安装包变大，观感与系统一致；\n")
            append("2. 非小米机型上 `FontFamily.Default` 不是 MiSans，需要有回退预期。\n\n")
            append("```kotlin\n")
            append("val family = FontFamily.Default // HyperOS 上即 MiSans\n")
            append("```\n\n")
            append("需要我把这条结论写进工程文档吗？")
        }
        // 每 3 个字符一"token"，模拟真实 SSE 的碎片粒度
        answer.chunked(3).forEach { chunk ->
            ev { it.onDelta(chunk) }
            delay(if (brief) 4 else 14)
        }
        delay(120)
        ev { it.onFinished() }
    }

    /**
     * 异常矩阵的视觉走查：把 Spec 第 8.3 节的每张卡片都摆一遍。
     * 只用于人工看图，不参与业务流程。
     */
    suspend fun playErrors(state: AgentChatState) {
        state.onStarted(round = 1)
        delay(80)
        state.onFailed(SystemKind.Offline, "当前没有可用网络")
        delay(120)

        state.onStarted(round = 1)
        state.onThinking(1, "先试一次请求。")
        state.onDelta("已生成一部分内容，然后连接断了。")
        delay(80)
        state.onFailed(SystemKind.NetworkLost, "连接被重置")
        delay(120)

        state.onStarted(round = 1)
        state.onFailed(SystemKind.RateLimited, "请求过于频繁", retryAfterSeconds = 12)
        delay(120)

        state.onStarted(round = 1)
        state.onFailed(SystemKind.ServerError, "上游 502")
        delay(120)

        state.onStarted(round = 1)
        state.onFailed(SystemKind.AuthRequired)
        delay(120)

        state.onStarted(round = 1)
        state.onFailed(SystemKind.Blocked, "涉及个人隐私")
        delay(120)

        state.onStarted(round = 1)
        state.onDelta("这段回答很长，长到触发了长度上限……")
        state.onAnomaly(SystemKind.Truncated)
        delay(120)

        state.onStarted(round = 1)
        state.onAnomaly(SystemKind.EmptyAnswer)
        delay(120)

        state.onStarted(round = 1)
        state.onToolCall(1, 0, "linux.shell", """{"cmd":"uname -a"}""")
        state.onToolResult(1, 0, "linux.shell", "Linux localhost 6.6.30-android15", true)
        state.onAnomaly(SystemKind.ToolOnly)
    }

    /**
     * 工具卡六态 + 审批卡的走查。
     */
    suspend fun playTools(state: AgentChatState) {
        state.onStarted(round = 1)

        // Running
        state.onToolCall(1, 0, "web.fetch", """{"url":"https://yukonga.top"}""")
        delay(600)

        // Success + 来源
        state.onToolResult(
            1, 0, "web.fetch",
            "找到 8 条相关结果，3 条来自官方文档",
            true,
        )
        delay(120)

        // Failed
        state.onToolCall(1, 1, "db.query", """{"sql":"select * from nope"}""")
        state.onToolResult(1, 1, "db.query", "错误：no such table: nope", false)
        delay(120)

        // Timeout（前端 45s 兜底的样子）
        state.onToolCall(1, 2, "linux.shell", """{"cmd":"sleep 999"}""")
        delay(120)

        // 审批卡
        state.onToolNeedsApproval(
            1, 3, "file.delete",
            """{"path":"/sdcard/重要文件.txt"}""",
            "将永久删除 /sdcard/重要文件.txt，此操作不可撤销",
        )
        delay(120)

        // 并行提示
        state.onToolCall(1, 4, "file.grep", """{"pattern":"TODO"}""")
        state.onToolCall(1, 5, "system.memory", "{}")
        state.onToolCall(1, 6, "apk.info", """{"path":"/sdcard/a.apk"}""")
        delay(400)
        state.onFinished()
    }

    /**
     * **长流**演示：连续推 ~20 秒的正文，用来验证「流式期间能不能自由滚动」。
     *
     * 为什么需要它：真实长流才会持续触发自动跟随，而自动跟随正是把用户
     * 拽回底部的元凶。短脚本几秒就跑完，测不出这个问题。
     */
    suspend fun playLongStream(state: AgentChatState) {
        state.onStarted(round = 1)
        state.onThinking(1, "先读一遍需求，再逐段写。")
        delay(600)
        state.onToolCall(1, 0, "file.read", """{"path":"/sdcard/notes.md"}""")
        delay(500)
        state.onToolResult(1, 0, "file.read", "notes: 12 行", true)
        delay(300)

        val paragraphs = (1..14).map { i ->
            "第 $i 段：这是一段用来撑开列表高度的正文，目的是让流式期间内容持续增长，" +
                "从而检验自动跟随是否会与用户的滚动操作打架。"
        }
        // 每段分片推送，整体约 20 秒
        paragraphs.forEach { para ->
            para.chunked(6).forEach { piece ->
                state.onDelta(piece)
                delay(90)
            }
            state.onDelta("\n\n")
            delay(120)
        }
        state.onFinished()
    }

    /** 空转。 */
    suspend fun idle(state: AgentChatState) = Unit
}
