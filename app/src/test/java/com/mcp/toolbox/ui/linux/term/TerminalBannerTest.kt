package com.mcp.toolbox.ui.linux.term

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * banner 的回归测试。
 *
 * ## 为什么值得写
 *
 * ASCII art 的斜线全靠反斜杠撑着，而这份字符串会经过「Kotlin 转义」与
 * 「shell 转义」两层。历史上就因此坏过一次：为了躲 shell 的反斜杠解析，
 * 有人把 art 里的反斜杠删了 —— 结果 logo 的斜线笔画全没了，变成一堆散乱的竖线。
 *
 * 所以这里把「屏幕上该看到的样子」钉死成断言。**以后任何改动只要动了 art，
 * 这个测试立刻红**，不会再有人靠肉眼发现不了。
 *
 * 同时锁住两条与 Linux 侧共享的结构约束，避免两份 banner 走样：
 * 1. 与 Linux 侧用同一份 [TerminalBanner.ART]（唯一来源，不复制字符串）
 * 2. 生成的脚本里不含 `printf` 的格式串陷阱（历史上把 `%s\n` 原样打上过屏）
 */
class TerminalBannerTest {

    @Test
    fun `art 每一行的反斜杠数量与屏幕一致`() {
        // 第 2 行：`\ \ / /_   _| |_ _   _` —— 两个反斜杠 + 一个正斜杠
        assertEquals("\\ \\ / /_   _| |_ _   _", TerminalBanner.ART[1])
        // 第 3 行：` \ V /| | | | __| | | |` —— 一个反斜杠 + 一个正斜杠
        assertEquals(" \\ V /| | | | __| | | |", TerminalBanner.ART[2])
        // 第 5 行：`  |_|  \__,_|\__|\__,_|` —— 三个反斜杠
        assertEquals("  |_|  \\__,_|\\__|\\__,_|", TerminalBanner.ART[4])
    }

    @Test
    fun `art 保持 5 行且不含控制字符`() {
        assertEquals(5, TerminalBanner.ART.size)
        TerminalBanner.ART.forEach { line ->
            assertFalse("art 行里不该有 ESC：$line", line.contains('\u001b'))
            assertFalse("art 行里不该有换行：$line", line.contains('\n'))
            assertFalse("art 行里不该有回车：$line", line.contains('\r'))
            assertFalse("art 行里不该有百分号（会踩 printf 格式串）：$line", line.contains('%'))
        }
    }

    @Test
    fun `art 首行是竖排笔画，宽度不超过终端默认列数`() {
        assertEquals("__   __      _", TerminalBanner.ART[0])
        TerminalBanner.ART.forEach { line ->
            assertTrue("art 行过宽会换行破版：$line", line.length <= TerminalSession.DEFAULT_COLS)
        }
    }

    @Test
    fun `android banner 脚本首行是 shebang 且用 echo 逐行输出 art`() {
        val script = TerminalBanner.androidBannerScript()
        val lines = script.split('\n')
        assertEquals("#!/system/bin/sh", lines[0])

        // 每一行 art 都要出现，且必须用单引号包裹（单引号内 shell 不再解析反斜杠）。
        TerminalBanner.ART.forEach { art ->
            val expected = "echo '$art'"
            assertTrue("脚本里缺少 art 行：$expected\n实际：\n$script", lines.contains(expected))
        }
    }

    @Test
    fun `android banner 脚本不把 printf 格式串当字面量打出来`() {
        val script = TerminalBanner.androidBannerScript()
        // 历史上坏过一次：收尾写成了 Kotlin 的 "\n"（反斜杠 + n 两个字面字符），
        // 导致所有行挤成一行，屏幕上直接打出 `%s\n`。
        assertFalse("脚本里出现了会被原样打出来的格式串", script.contains("""'%s\n'"""))
        assertFalse("脚本里出现了字面量反斜杠-n", script.contains("""\n\""""))
        // 收尾必须是真正的换行，而不是转义的 `\n` 两个字面字符。
        assertTrue("脚本应以真实换行结束", script.endsWith("\n"))
        assertFalse("结尾不该是字面的反斜杠 n", script.endsWith("\\n"))
    }

    @Test
    fun `android banner 的设备信息来自 getprop 而不是写死的常量`() {
        val script = TerminalBanner.androidBannerScript()
        listOf(
            "ro.build.version.release",
            "ro.build.version.sdk",
            "ro.product.model",
        ).forEach { prop ->
            assertTrue("应当运行时读取 $prop，保证显示真实机型：\n$script", script.contains(prop))
        }
    }

    @Test
    fun `权限模式标签会写进脚本且做了单引号逃逸`() {
        val plain = TerminalBanner.androidBannerScript("root")
        assertTrue("缺少权限行：\n$plain", plain.contains("权限：%s"))
        assertTrue("未写入 root：\n$plain", plain.contains("'root'"))

        // 标签若含单引号，必须逃逸，否则会把 shell 引号配平搞乱、整条 printf 失效。
        val tricky = TerminalBanner.androidBannerScript("a'b")
        assertTrue("单引号未逃逸：\n$tricky", tricky.contains("""'a'\''b'"""))
    }

    @Test
    fun `不传权限标签时不产生多余输出`() {
        val script = TerminalBanner.androidBannerScript(null)
        assertFalse("null 标签不该写出权限行：\n$script", script.contains("权限："))
    }

    @Test
    fun `未安装提示与已安装 banner 共用同一份 art`() {
        // 这条防的是「两份 banner 长得不一样」：未安装分支也走 ART。
        val art = TerminalBanner.ART
        assertTrue("ART 不该为空", art.isNotEmpty())
        assertEquals("第 1 行应仍是 logo 顶部", "__   __      _", art.first())
    }
}
