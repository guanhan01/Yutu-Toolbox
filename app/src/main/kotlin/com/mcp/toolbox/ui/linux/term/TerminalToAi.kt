package com.mcp.toolbox.ui.linux.term

import android.content.Context
import com.mcp.toolbox.feature.home.ChatMessage
import com.mcp.toolbox.feature.home.ChatStore
import com.mcp.toolbox.ui.linux.LinuxDistro
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 把终端"接入 AI"。
 *
 * ## 它做什么
 *
 * 取当前终端窗口里**已经出现过的输出**，连同用户的一句提示，作为一条消息
 * 发给 AI，然后跳到对话页。
 *
 * 典型用法：跑了一串命令、屏幕上有一堆报错，懒得复制粘贴，直接让 AI 看。
 *
 * ## 为什么截断
 *
 * 终端回滚缓冲有 256 KB，整段塞进请求既贵又没必要——真正要看的是**结尾**
 * （最近发生了什么）。所以只取尾部一段，并把截断事实写进上下文，
 * 让模型知道前面还有内容，不要假装看全了。
 *
 * ## 为什么走 ChatStore 而不是直接调模型
 *
 * 走"插入一条用户消息 + 打开对话页"这条路，AI 的回答会**正常落进会话**，
 * 之后可以继续追问、可以回看。若在这里直接发起一次调用，答完就散了。
 */
object TerminalToAi {

    /** 送去分析的尾部字符数。约等于一个屏幕往上若干屏，够定位报错。 */
    private const val TAIL_CHARS = 6000

    /**
     * 把终端内容作为一条消息投给 AI。
     *
     * @param prompt 用户在输入框里写的那句话；空则用默认提示。
     * @return 是否成功投出（失败原因会通过 [onError] 报出，不静默吞掉）。
     */
    fun send(
        context: Context,
        tab: TermSessionStore.Tab,
        distro: LinuxDistro,
        prompt: String,
        onError: (String) -> Unit,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            val raw = runCatching { readTerminal(tab) }.getOrElse { "" }
            val text = stripAnsi(raw).trimEnd()
            if (text.isBlank()) {
                onError("终端还没有输出可发送")
                return@launch
            }

            val envName = if (tab.env == TermSessionStore.Env.LOCAL) {
                "Android 环境"
            } else {
                "${distro.title}（Linux 环境）"
            }
            val truncated = text.length > TAIL_CHARS
            val body = if (truncated) text.takeLast(TAIL_CHARS) else text

            val content = buildString {
                append(prompt.trim().ifBlank { "帮我看看终端里发生了什么。" })
                append("\n\n—— 以下是终端输出")
                if (truncated) append("（已截断，只保留最后 ${TAIL_CHARS} 个字符）")
                append(" ——\n")
                append("```\n").append(body).append("\n```")
            }

            // 复用同一条会话，别每次新开一个：用户往往会接着追问
            val session = ChatStore.current()
                ?: ChatStore.newSession(context, "终端 · $envName")
            ChatStore.append(
                context,
                session.id,
                ChatMessage(
                    role = ChatMessage.Role.USER,
                    content = content,
                ),
            )
        }
    }

    /**
     * 读当前窗口的全部输出。
     *
     * `readSince(0)` 是终端自己维护的回滚缓冲，与 WebView 是否挂载无关 ——
     * 所以即使用户刚切进这个窗口、xterm 还没重放完，也能拿到完整内容。
     */
    private fun readTerminal(tab: TermSessionStore.Tab): String {
        val (_, bytes) = tab.session.readSince(0)
        return String(bytes, Charsets.UTF_8)
    }

    /**
     * 去掉 ANSI 转义序列。
     *
     * 终端输出里到处是颜色与光标控制（`\u001b[1;32m`、`\u001b[2K`…）。
     * 直接发给模型会白烧 token，而且那些字节本身会干扰它读内容。
     */
    private fun stripAnsi(input: String): String {
        // CSI / OSC / 单字符转义，三类覆盖终端实际会吐出的全部控制序列
        val csi = Regex("\u001b\\[[0-9;?]*[ -/]*[@-~]")
        val osc = Regex("\u001b\\][^\u0007\u001b]*(\u0007|\u001b\\\\)")
        val single = Regex("\u001b[@-Z\\\\-_]")
        return input.replace(osc, "")
            .replace(csi, "")
            .replace(single, "")
            // script 会把 \n 变成 \r\n，统一回 \n，否则发出去的代码块里全是 ^M
            .replace("\r\n", "\n")
            .replace("\r", "")
    }
}
