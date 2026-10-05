package com.mcp.toolbox.feature.home.agent

import androidx.annotation.DrawableRes
import org.json.JSONArray
import org.json.JSONObject

/**
 * Segment 的持久化编解码。
 *
 * ## 为什么必须落盘
 *
 * 过程时间线（思考 / 工具 / 正文 / 系统卡）是**消息本身的一部分**，不是「界面临时状态」。
 * 早先它只活在 `AgentChatState` 的内存里，而 `ChatStore` 只存「角色 + 纯文本」，
 * 于是产生一串连带 bug：
 *
 * - 切后台被回收 → 时间线随 Activity 一起消失，再进来只剩一行纯文本（记录丢失）
 * - 点停止 → 只有正文被写回，思考与工具调用从未落盘
 * - 第二轮的工具事件按 `(round, seq)` 定位，`round 2 / seq 0` 会撞上 `round 1 / seq 0`，
 *   于是只更新旧卡、不新建卡片（「第二个问题没有工具界面」）
 *
 * 所以时间线必须和消息一起持久化，成为**唯一真相**。
 *
 * ## 编解码取向
 *
 * 手写 JSON 而不是反射/序列化框架：
 * - 这些字段是**磁盘格式**，加字段必须能向后兼容（旧记录没有新字段 → 取默认值）
 * - 未知的 `kind` 直接跳过该段而不是抛异常（版本回退时旧版本读到新段不会崩）
 * - 枚举写名字不写下标（下标会随枚举顺序变动而错位）
 */
object AgentSegmentCodec {

    private const val KEY_KIND = "kind"
    private const val KEY_ID = "id"
    private const val KEY_AT = "at"

    // ---- kind ----
    private const val KIND_THINKING = "thinking"
    private const val KIND_TOOL = "tool"
    private const val KIND_NARRATION = "narration"
    private const val KIND_ANSWER = "answer"
    private const val KIND_SYSTEM = "system"

    fun encode(segments: List<Segment>): String {
        val arr = JSONArray()
        segments.forEach { seg -> arr.put(encodeOne(seg)) }
        return arr.toString()
    }

    /**
     * 解析。任何一段解析失败都**跳过该段**而不是整条消息失败——
     * 一条坏记录不该让整个对话页打不开（与 `ChatStore.parse` 的处理一致）。
     */
    fun decode(text: String?): List<Segment> {
        if (text.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(text)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { decodeOne(it) }
            }
        }.getOrDefault(emptyList())
    }

    private fun encodeOne(seg: Segment): JSONObject {
        val base = JSONObject()
            .put(KEY_ID, seg.id)
            .put(KEY_AT, seg.createdAt)
        return when (seg) {
            is ThinkingSegment -> base
                .put(KEY_KIND, KIND_THINKING)
                .put("text", seg.text)
                .put("status", seg.status.name)
                .put("elapsed", seg.elapsedMs)
                .put("round", seg.round)
                .put("expanded", seg.isExpanded)
                .put("toggled", seg.userToggled)

            is ToolCallSegment -> base
                .put(KEY_KIND, KIND_TOOL)
                .put("name", seg.toolName)
                .put("display", seg.displayName)
                .put("icon", seg.iconRes)
                .put("round", seg.round)
                .put("seq", seg.seq)
                .put("args", seg.argsJson)
                .put("status", seg.status.name)
                .put("startedAt", seg.startedAt)
                .put("endedAt", seg.endedAt ?: JSONObject.NULL)
                .put("expanded", seg.isDetailExpanded)
                .put("toggled", seg.userToggled)
                .put("awaiting", seg.awaitingApproval)
                .put("risk", seg.risk ?: JSONObject.NULL)
                .put("result", seg.result?.let(::encodeResult) ?: JSONObject.NULL)

            is NarrationSegment -> base
                .put(KEY_KIND, KIND_NARRATION)
                .put("text", seg.text)
                .put("round", seg.round)

            is AnswerSegment -> base
                .put(KEY_KIND, KIND_ANSWER)
                .put("markdown", seg.markdown)
                .put("status", seg.status.name)

            is SystemSegment -> base
                .put(KEY_KIND, KIND_SYSTEM)
                .put("systemKind", seg.kind.name)
                .put("payload", seg.payload)
                .put("retryAfter", seg.retryAfterSeconds)
                .put("preserved", seg.preservedChars)
        }
    }

    private fun encodeResult(result: ToolResult): JSONObject = JSONObject()
        .put("summary", result.summary)
        .put("raw", result.rawText ?: JSONObject.NULL)
        .put(
            "sources",
            result.sources?.let { list ->
                JSONArray().also { arr ->
                    list.forEach { s ->
                        arr.put(
                            JSONObject()
                                .put("title", s.title)
                                .put("url", s.url),
                        )
                    }
                }
            } ?: JSONObject.NULL,
        )

    private fun decodeOne(o: JSONObject): Segment? {
        val id = o.optString(KEY_ID).takeIf { it.isNotBlank() } ?: return null
        val at = o.optLong(KEY_AT)
        return when (o.optString(KEY_KIND)) {
            KIND_THINKING -> ThinkingSegment(
                id = id,
                createdAt = at,
                text = o.optString("text"),
                status = enumOr(o.optString("status"), RunStatus.Done),
                elapsedMs = o.optLong("elapsed"),
                isExpanded = o.optBoolean("expanded"),
                userToggled = o.optBoolean("toggled"),
                round = o.optInt("round", 1),
            )

            KIND_TOOL -> ToolCallSegment(
                id = id,
                createdAt = at,
                toolName = o.optString("name"),
                displayName = o.optString("display"),
                iconRes = o.optInt("icon"),
                round = o.optInt("round"),
                seq = o.optInt("seq"),
                argsJson = o.optString("args").takeIf { it.isNotBlank() && it != "null" },
                status = enumOr(o.optString("status"), ToolStatus.Success),
                startedAt = o.optLong("startedAt", at),
                endedAt = o.opt("endedAt")?.takeIf { it != JSONObject.NULL }?.let {
                    (it as? Number)?.toLong()
                },
                isDetailExpanded = o.optBoolean("expanded"),
                userToggled = o.optBoolean("toggled"),
                awaitingApproval = o.optBoolean("awaiting"),
                risk = o.optString("risk").takeIf { it.isNotBlank() && it != "null" },
                result = o.optJSONObject("result")?.let(::decodeResult),
            )

            KIND_NARRATION -> NarrationSegment(
                id = id,
                createdAt = at,
                text = o.optString("text"),
                round = o.optInt("round"),
            )

            KIND_ANSWER -> AnswerSegment(
                id = id,
                createdAt = at,
                markdown = o.optString("markdown"),
                status = enumOr(o.optString("status"), RunStatus.Done),
            )

            KIND_SYSTEM -> SystemSegment(
                id = id,
                createdAt = at,
                kind = enumOr(o.optString("systemKind"), SystemKind.ServerError),
                payload = o.optString("payload"),
                retryAfterSeconds = o.optInt("retryAfter"),
                preservedChars = o.optInt("preserved"),
            )

            // 未知 kind（例如降级安装后读到新版本写的记录）：跳过这一段，别炸整条消息
            else -> null
        }
    }

    private fun decodeResult(o: JSONObject): ToolResult = ToolResult(
        summary = o.optString("summary"),
        sources = o.optJSONArray("sources")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { s ->
                    WebSource(
                        title = s.optString("title"),
                        url = s.optString("url"),
                    )
                }
            }.takeIf { it.isNotEmpty() }
        },
        rawText = o.optString("raw").takeIf { it.isNotBlank() && it != "null" },
    )

    /** 枚举按名字解析，认不出就退回 [fallback]——不因为多了个新态就整段丢弃。 */
    private inline fun <reified T : Enum<T>> enumOr(name: String, fallback: T): T =
        runCatching { enumValueOf<T>(name) }.getOrDefault(fallback)

    /**
     * 把时间线里仍在 `Running` 的段冻结。
     *
     * 冷启动读到 `turnState == Running` 的消息时调用：那轮已经不可能还在跑，
     * 不冻结的话界面会把思考和正文当成「正在流式」——光标一直闪、计时从零重来。
     *
     * 只改状态字段、不动 JSON 结构，所以旧记录也能处理。
     */
    fun sealRunning(json: String): String = runCatching {
        val arr = JSONArray(json)
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            when (o.optString(KEY_KIND)) {
                KIND_THINKING, KIND_ANSWER ->
                    if (o.optString("status") == "Running") o.put("status", "Interrupted")

                KIND_TOOL ->
                    // 工具停在 Pending/Running 说明没收到结果：按前端超时兜底，
                    // 与「不允许无限转圈」是同一条规则。
                    // 审批中的卡不动——它本来就该停在那里等用户。
                    if (!o.optBoolean("awaiting") &&
                        (o.optString("status") == "Running" || o.optString("status") == "Pending")
                    ) {
                        o.put("status", "Timeout")
                        if (!o.has("endedAt") || o.opt("endedAt") == JSONObject.NULL) {
                            o.put("endedAt", System.currentTimeMillis())
                        }
                    }
            }
            out.put(o)
        }
        out.toString()
    }.getOrDefault(json)
}
