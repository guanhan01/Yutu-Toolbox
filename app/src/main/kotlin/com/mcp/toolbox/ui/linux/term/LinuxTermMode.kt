package com.mcp.toolbox.ui.linux.term

import android.content.Context

/**
 * 终端显示模式的持久化。
 *
 * 两套模式共存，是为了兼顾两种用法：
 * - [SIMPLE]：一条命令跑完取回全文。适合「查一下 uname」「看下磁盘」，
 *   输出留在屏幕上可以慢慢看，不需要和 shell 交互。
 * - [TERMUX]：长驻 `bash -i`，真 pty。适合装包、跑编译、用 vim/htop。
 *
 * 选择要记住：用户切到 Termux 模式显然是有交互需求，下次进来不该又回到简单模式。
 */
object LinuxTermMode {

    const val SIMPLE = "simple"
    const val TERMUX = "termux"

    private const val PREFS = "linux-env-prefs"
    private const val KEY = "terminal-mode"

    fun load(context: Context): String {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        return if (raw == TERMUX) TERMUX else SIMPLE
    }

    fun save(context: Context, mode: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, if (mode == TERMUX) TERMUX else SIMPLE)
            .apply()
    }

    /** 切换并返回新模式。 */
    fun toggle(context: Context, current: String): String {
        val next = if (current == TERMUX) SIMPLE else TERMUX
        save(context, next)
        return next
    }
}
