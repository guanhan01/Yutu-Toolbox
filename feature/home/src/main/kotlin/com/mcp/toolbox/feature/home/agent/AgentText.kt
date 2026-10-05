package com.mcp.toolbox.feature.home.agent

/**
 * 回答区的全部文案，集中一处。
 *
 * 项目其余部分走 `strings.xml`（en + zh）。这一块先集中在这里，是为了
 * 让后续「抽到资源文件」变成一次机械搬移，而不是在十几个文件里翻字符串。
 * 每一条都对应 Spec 第 11 节 46 项状态清单里某个分支的文案要求。
 */
object AgentText {

    // ---- 等待与思考 ----
    const val THINKING = "思考中"

    // ---- 工具卡 ----
    const val TOOL_ARGS = "参数"
    const val TOOL_RESULT = "结果"
    const val TOOL_RETRY = "重试"
    const val TOOL_IGNORE = "忽略"
    const val TOOL_ALLOW = "允许"
    const val TOOL_DENY = "拒绝"
    const val TOOL_NEEDS_APPROVAL = "需要你确认"
    const val TOOL_APPROVED = "已允许"
    const val TOOL_DENIED = "已拒绝"
    const val TOOL_TIMEOUT = "已超时"
    const val TOOL_INTERRUPTED = "已中断"
    const val TOOL_FAILED = "执行失败"
    const val TOOL_DENIED_HINT = "已拒绝，模型会继续回答"
    const val TOOL_TIMEOUT_HINT = "长时间没有返回，已停止等待"
    const val TOOL_INTERRUPTED_HINT = "你停止了这一轮，这次调用没有跑完"
    const val TOOL_MORE_STEPS = "显示前面 %1\$d 个步骤"
    const val TOOL_PARALLEL = "%1\$d 个任务进行中"
    const val SNIPPET_MORE = "查看全部 %1\$d 条"
    const val SNIPPET_TITLE = "来源"
    const val SNIPPET_DOMAIN_FALLBACK = "未知来源"

    // ---- 网页搜索骨架 ----
    const val SEARCH_SKELETON = "正在检索网页…"

    // ---- 完成态操作条 ----
    const val ACTION_COPY = "复制全文"
    const val ACTION_REGENERATE = "重新生成"
    const val ACTION_DELETE = "删除"
    const val ACTION_MORE = "更多"
    const val COPIED = "已复制到剪贴板"

    // ---- 中断与断点 ----
    const val STOPPED = "已停止"
    const val STOPPED_CONTINUE = "继续生成"
    const val STOPPED_COPY_PARTIAL = "复制已生成部分"
    const val CHECKPOINT_TITLE = "上次回答被中断"
    const val CHECKPOINT_PRESERVED = "已保留 %1\$d 字"
    const val CHECKPOINT_OFFLINE = "网络不可用，恢复后可继续"

    // ---- 异常矩阵（Spec 第 8.3 节） ----
    const val ERR_OFFLINE = "网络不可用"
    const val ERR_NETWORK_LOST = "连接中断"
    const val ERR_STALL = "响应超时"
    const val ERR_RETRY = "重试"
    const val ERR_AUTH = "需要登录后才能继续"
    const val ERR_AUTH_CTA = "去登录"
    const val ERR_RATE_LIMIT = "请求过于频繁"
    const val ERR_RATE_LIMIT_WAIT = "%1\$d 秒后重试"
    const val ERR_SERVER = "服务暂时不可用"
    const val ERR_BLOCKED = "该内容无法生成"
    const val ERR_BLOCKED_HINT = "可以换个说法，或去掉敏感信息后重试"
    const val ERR_TRUNCATED = "已达长度上限"
    const val ERR_TRUNCATED_ACTION = "继续生成"
    const val ERR_EMPTY = "未生成内容"
    const val ERR_EMPTY_ACTION = "重新生成"
    const val ERR_TOOL_ONLY = "未生成总结"
    const val ERR_TOOL_ONLY_ACTION = "生成总结"

    // ---- 自动滚动 ----
    const val JUMP_TO_BOTTOM = "回到底部"
    const val NEW_CONTENT = "有新内容"

    // ---- 状态栏 ----
    const val SUMMARY_THINKING = "已思考 %1\$s"
    const val SUMMARY_TOOLS = "%1\$d 次工具调用"
    const val SUMMARY_SEP = " · "
    const val PARTIAL = "内容不完整"
}

/** 把工具耗时渲染成 `1.2s` / `540ms`。 */
fun formatToolDuration(ms: Long?): String = when {
    ms == null -> ""
    ms < 1000 -> "${ms}ms"
    else -> "%.1fs".format(ms / 1000.0)
}
