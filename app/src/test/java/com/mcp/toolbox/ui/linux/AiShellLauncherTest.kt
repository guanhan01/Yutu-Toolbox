package com.mcp.toolbox.ui.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 会话启动脚本的生成。
 *
 * 这段脚本只有真机跑起来才知道对不对，而它全是 `$` 开头的 shell 变量——写进
 * Kotlin 字符串里极易被当成模板插值（实测踩过：整段 `$R`/`$d`/`$BB` 编译报错，
 * 或者更糟，静默插成空串让脚本变成另一件东西）。用测试盯住它。
 */
class AiShellLauncherTest {

    private val rootfs = "/data/user/0/com.Yutu.Agent.beta/files/linux-env/debian/rootfs"
    private val script = buildLauncherScript(rootfs)

    @Test
    fun rootfsPathIsQuotedVerbatim() {
        assertTrue(script.contains("R='$rootfs'"))
    }

    @Test
    fun shellVariablesSurviveKotlinTemplates() {
        // 这些必须是字面量的 shell 变量，不能被 Kotlin 插值吃掉
        listOf("\$R", "\$d", "\$c", "\$BB", "\$D", "\$s").forEach { needle ->
            assertTrue("脚本里缺少 shell 变量 $needle", script.contains(needle))
        }
    }

    @Test
    fun noKotlinInterpolationLeftovers() {
        // Kotlin 插值失败时会把 `$name` 变成字面量 "null" 或空串。
        // 注意 /dev/null 是合法内容，先摘掉再查，否则会误报。
        val withoutDevNull = script.replace("/dev/null", "")
        assertFalse("脚本里出现 Kotlin 插值残留的 null", withoutDevNull.contains("null"))
        // `$` 后面直接跟空白 = 变量名被吃掉
        assertFalse(
            "出现空的变量引用（\$ 后无名字）",
            Regex("""\$\s""").containsMatchIn(withoutDevNull),
        )
    }

    @Test
    fun mountsDevWithRbind() {
        // /dev 必须 rbind，单层 bind 带不进 /dev/pts 与 /dev/ptmx
        assertTrue(script.contains("mount --rbind /dev"))
    }

    @Test
    fun exportsPathBeforeChroot() {
        // 实测漏掉 PATH 时 sleep/cat/head 全部 not found，会话形同废掉
        assertTrue(script.contains("export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"))
        assertTrue(script.contains("export HOME=/root"))
        assertTrue("应设 TERM=dumb，避免 ANSI 转义把输出读花", script.contains("TERM=dumb"))
    }

    @Test
    fun dnsLineUsesRealNewlinesNotLiteralBackslashN() {
        // 回归：这里曾经多转义一层，printf 拿到的是字面量反斜杠-n，
        // resolv.conf 变成「一行、内容含反斜杠-n」——glibc 解析不了，
        // 会话内 DNS 全废（apt / pip / git clone 全部失败）。
        //
        // 注意断言对象是**运行时**字符串：源码里的 \n 到这里已是真换行。
        // 不能用 script.lines()——真换行会把 printf 那一行切开。
        val realNewline = script.contains("printf 'nameserver %s\nnameserver 223.5.5.5")
        assertTrue("printf 的格式串里应是真换行（源码写成单反斜杠 + n）", realNewline)

        val literalBackslashN = script.contains("""nameserver %s\nnameserver""")
        assertFalse(
            "不应出现字面量反斜杠-n（源码多转义了一层，printf 会原样输出）",
            literalBackslashN,
        )
    }

    @Test
    fun exportsTmpdirInsideRootfs() {
        // 回归：不设 TMPDIR 会继承宿主的值（指向应用缓存目录，chroot 里不存在），
        // apt 装包时 postinst 的 mktemp 直接失败，包卡在半配置状态
        // （实测 openssh-server 就是这样：sshd_config 与 sshd 用户都没建出来）。
        assertTrue("必须把 TMPDIR 指到 rootfs 内的 /tmp", script.contains("export TMPDIR=/tmp"))
        // 没有终端，debconf 的前端探测会各报一次错，一起关掉
        assertTrue(script.contains("export DEBIAN_FRONTEND=noninteractive"))
    }

    @Test
    fun dnsFallsBackThroughBothProps() {
        // 本机 net.dns1 就是空的，只读它等于永远用公共 DNS
        assertTrue(script.contains("getprop net.dns1"))
        assertTrue("应再试 net.dns2", script.contains("getprop net.dns2"))
    }

    @Test
    fun setsUpDnsFromSystemProp() {
        assertTrue(script.contains("getprop net.dns1"))
        assertTrue("应写入 resolv.conf", script.contains("etc/resolv.conf"))
    }

    @Test
    fun endsByExecingChrootShell() {
        // exec 到 sh 而不是跑完退出：这是「持久」的关键
        val last = script.trimEnd().lines().last()
        assertTrue("最后一行应是 chroot 起 shell，实际「$last」", last.startsWith("exec /system/bin/chroot"))
        assertTrue("必须 exec 到 /bin/sh", last.contains("/bin/sh"))
    }

    @Test
    fun changesDirectoryInsideChrootNotOutside() {
        // 回归守卫：chroot 不会改工作目录，宿主侧的 /root 在新根里不存在。
        // 只在外层 cd 会让会话起来时 pwd=/，必须把 cd 放进 chroot 内部。
        val last = script.trimEnd().lines().last()
        assertTrue("chroot 命令里应包含 cd /root，实际「$last」", last.contains("cd /root"))
        // 外层不应再单独 cd：那样只会造成「已经 cd 过」的错觉
        val outerCd = script.lines().count { it.trim() == "cd /root 2>/dev/null || cd /" }
        assertEquals("外层不应再 cd（cd 要放在 chroot 内部）", 0, outerCd)
    }

    @Test
    fun umountsBeforeMountToAvoidStacking() {
        // /proc/mounts 里是解析后的真实路径，与 $R 对不上，
        // 「已挂载就跳过」永远失效 -> 必须无条件先卸
        val body = script.lines().filter { it.contains("mount --bind") }
        assertTrue("应先 umount 再 mount", script.contains("umount \"\$R/\$d\""))
        assertTrue("共享存储也要先卸", script.contains("umount \"\$R/\$s\""))
        assertTrue(body.isNotEmpty())
    }

    @Test
    fun scriptIsValidShellSyntax() {
        // 交给 /bin/sh -n 做语法检查：转义写错时这条会失败，
        // 比在真机上等到「会话起不来」早得多
        val tmp = File.createTempFile("eta-launcher", ".sh")
        try {
            tmp.writeText(script)
            val p = ProcessBuilder("/bin/sh", "-n", tmp.absolutePath)
                .redirectErrorStream(true)
                .start()
            val out = p.inputStream.bufferedReader().readText()
            val code = p.waitFor()
            assertEquals("脚本语法检查失败：$out", 0, code)
        } catch (t: java.io.IOException) {
            // 没有 /bin/sh 的环境（例如非 unix CI）跳过，不误报
            org.junit.Assume.assumeNoException(t)
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun singleQuoteEscapesInnerQuotes() {
        assertEquals("'a'\\''b'", shellSingleQuote("a'b"))
        assertEquals("'plain'", shellSingleQuote("plain"))
    }
}
