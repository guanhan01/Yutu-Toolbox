package com.mcp.toolbox.ui.linux.term

import com.mcp.toolbox.ui.linux.LinuxDistro

/**
 * 终端启动横幅。
 *
 * ## 为什么要有「本地渲染」这一路
 *
 * 已安装环境时 banner 由 rootfs 内的脚本打印——它是**终端的真实输出**，
 * 可以滚进回滚缓冲、可以长按选中复制，比画在 Compose 层上强得多。
 *
 * 但环境**没装**时根本没有 rootfs：脚本无处安放、shell 也起不来，
 * 于是终端是一片空白（用户实测反馈：Termux 在未配置时仍显示 banner）。
 * 空白会让人以为「终端坏了」，而真正的问题是「还没装环境」。
 *
 * 所以这里保留同一份 ASCII art，供未安装时由应用侧直接写进 xterm。
 * 两处共用一份字符串，避免两套 banner 长得不一样。
 *
 * ## 转义（这里踩过坑，写清楚）
 *
 * 这份 art 有两个用途，容易在转义上出问题：
 * 1. 直接写进 xterm（应用侧）
 * 2. 嵌进 rootfs 里的 `printf '...'` 脚本（shell 侧）
 *
 * 早先我把反斜杠按「最终要显示的字符」来写，结果 Kotlin 解析一遍、
 * shell 的 `printf` 又解析一遍 —— 屏幕上是 `\\\\ \\\\ / /_` 这种东西，
 * 而且因为 art 里含 `\` 又用了 `printf '%s\n'` 的**单引号包裹**，
 * 格式串 `%s\n` 被当成字面量原样打了出来（截图里那几行 `%s\n`）。
 *
 * 现在的规矩：**[ART] 里写的是「最终想看到的字符」，且不含任何需要 shell
 * 二次转义的字符**。生成脚本时也不再用 printf 的格式串，改为直接 echo 一行，
 * 从根本上消除二次解析。
 */
object TerminalBanner {

    /**
     * ASCII art。**这里写的就是屏幕上要出现的样子**，不做任何额外转义。
     *
     * 这里的 `\\` 在 Kotlin 里解析成**一个** `\` —— 也就是屏幕上要看到的那个。
     * 会不会再被 shell 解析一遍，取决于输出路径：
     * - 应用侧直写 xterm：到此为止，看到一个 `\`（正确）
     * - 经 rootfs 脚本：用 `echo '...'`（单引号）包裹，shell 不再解析里面的 `\`
     *
     * 早先我把这两条路径混在一起考虑，一度把反斜杠全删掉「躲坑」，
     * 结果 logo 的斜线笔画没了、变成一堆散乱的竖线（实测截图）。
     */
    val ART = listOf(
        "__   __      _",
        "\\ \\ / /_   _| |_ _   _",
        " \\ V /| | | | __| | | |",
        "  | | | |_| | |_| |_| |",
        "  |_|  \\__,_|\\__|\\__,_|",
    )

    /** ANSI 颜色：绿标题 + 暗色副标题，和已安装时的观感一致。 */
    private const val GREEN = "\u001b[1;32m"
    private const val DIM = "\u001b[2m"
    private const val RESET = "\u001b[0m"
    private const val YELLOW = "\u001b[1;33m"

    /**
     * 未安装环境时写进终端的文本。
     *
     * 结构刻意与已安装时对齐（art → Yutu Agt 终端 → 系统名 → 空行 → 提示），
     * 只把「系统名」那行换成状态说明，用户一眼能看出差别在哪。
     */
    fun notInstalledText(context: android.content.Context, distro: LinuxDistro): String =
        buildString {
            append("\r\n")
            ART.forEach { append(it).append("\r\n") }
            append("\r\n")
            append("  ").append(GREEN).append("Yutu").append(RESET)
            append(" ").append(DIM).append("Agt 终端").append(RESET).append("\r\n")
            append("  ").append(DIM).append(distro.title)
                .append(" · 环境未安装").append(RESET).append("\r\n")
            append("\r\n")
            append("  ").append(YELLOW).append("环境未配置，暂时无法使用。").append(RESET).append("\r\n")
            append("  请前往「AI 配置」界面完成环境安装。").append("\r\n")
            append("\r\n")
        }

    /**
     * 「Android 环境」终端启动时由 shell 打印的 banner 脚本。
     *
     * ## 与 Linux 侧的关系
     *
     * **art 与配色完全照搬**：同一个 [ART]、同一个 `Yutu Agt 终端` 标题行，
     * 只把「系统名」那一行换成宿主自己的信息。这样两个窗口的观感一致，
     * 且 art 只有一处定义，不会出现两套 banner 长得不一样。
     *
     * ## 为什么要落成脚本而不是由应用直接 write
     *
     * 与 Linux 侧同一个理由：由 shell 打印出来的是**终端的真实输出**，
     * 能滚进回滚缓冲、能长按选中复制；画在 Compose 层上做不到这两点。
     *
     * ## 转义（与 Linux 侧同样的规矩，别改）
     *
     * [ART] 里写的就是屏幕上要看到的字符，且不含需要 shell 二次转义的字符；
     * 每行用 `echo '...'` 单引号包裹，shell 不再解析里面的反斜杠。
     * 唯一的例外是下面那行带颜色的 `printf`：它用 `\\033` 出 ESC，
     * 这是 Linux 侧已经验证过的写法，照搬即可。
     *
     * 设备信息在**运行时**用 `getprop` 读，保证显示的是真实机型与系统版本，
     * 不是写死的常量（不臆造内容：任一属性取不到就退化，不编一个补上）。
     *
     * @param modeLabel 本次窗口拿到的权限，如 `root` / `Shizuku` / `应用自身`。
     *   附在系统名后面，用户一眼能看出这个窗口有多大权限。null 表示不显示。
     */
    fun androidBannerScript(modeLabel: String? = null): String = buildString {
        append("#!/system/bin/sh\n")
        append("printf '\\n'\n")
        // 每行单独一条 echo：art 里若含反斜杠，单引号包裹可免去二次解析。
        ART.forEach { line ->
            append("echo '").append(line).append("'\n")
        }
        append("printf '\\n'\n")
        append("printf '  \\033[1;32mYutu\\033[0m \\033[2mAgt 终端\\033[0m\\n'\n")
        // 真实设备信息：release / API / 品牌 + 型号，全部来自 getprop。
        // 任一取不到就退化成只显示 Android，不臆造内容。
        append("rel=${'$'}(getprop ro.build.version.release 2>/dev/null)\n")
        append("sdk=${'$'}(getprop ro.build.version.sdk 2>/dev/null)\n")
        append("brand=${'$'}(getprop ro.product.brand 2>/dev/null)\n")
        append("model=${'$'}(getprop ro.product.model 2>/dev/null)\n")
        append("name=\"Android ${'$'}rel\"\n")
        append("[ -n \"${'$'}sdk\" ] && name=\"${'$'}name (API ${'$'}sdk)\"\n")
        append("[ -n \"${'$'}model\" ] && name=\"${'$'}name · ${'$'}brand ${'$'}model\"\n")
        append("printf '  \\033[2m%s\\033[0m\\n' \"${'$'}name\"\n")
        // 权限模式单独一行，与系统名同色，位置固定在 banner 尾部上方。
        if (modeLabel != null) {
            append("printf '  \\033[2m权限：%s\\033[0m\\n' '")
                .append(modeLabel.replace("'", "'\\''")).append("'\n")
        }
        append("printf '\\n'\n")
    }
}
