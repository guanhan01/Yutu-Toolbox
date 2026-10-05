package com.mcp.toolbox.ui.linux

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.FixMethodOrder
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File

/**
 * 持久 shell 会话的真机集成测试。
 *
 * 为什么必须是设备测试：这条链路是 `su` 提权 + `unshare` 私有挂载 + `chroot` +
 * 一条长期存活的 stdin/stdout 管道，全部是系统行为，桩不出来。而且它最核心的
 * 承诺——「cwd / 环境变量 / 后台任务跨调用保留」——只有在真机上连续发多条命令
 * 才能验证。
 *
 * 设备没有 Root 或没装 Linux 环境时自动跳过，不让它变成假失败。
 */
@RunWith(AndroidJUnit4::class)
// 固定顺序：这类测试会真的起会话、跑后台任务，出问题时必须能从「第几个用例」
// 直接定位到是哪一个。默认顺序是按方法名的哈希，肉眼对不上。
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class AiShellSessionDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val distro = LinuxPrefs.distro(context)
    private lateinit var session: AiShellSession

    private fun hasRoot(): Boolean = runCatching {
        val p = ProcessBuilder("/system/bin/sh", "-c", "su -c id")
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        out.contains("uid=0")
    }.getOrDefault(false)

    @Before
    fun setUp() {
        // 把门槛判断的结果打出来：被 assume 跳过时从输出看与被通过一模一样
        // （都是 "OK (N tests)"），只有耗时不同，很难查。
        val root = hasRoot()
        val installed = LinuxEnvStore.isInstalled(context, distro)
        android.util.Log.i(
            "AiShellTest",
            "setUp root=$root installed=$installed distro=${distro.name} " +
                "rootfs=${LinuxEnvStore.rootfs(context, distro).absolutePath}",
        )
        assumeTrue("设备没有 Root", root)
        assumeTrue("Linux 环境未安装", installed)
        session = AiShellSession(context, distro)
    }

    @After
    fun tearDown() {
        if (::session.isInitialized) runBlocking { session.kill() }
    }

    @Test
    fun aaaFirstCommandStartsInRoot() {
        // 回归：chroot 不会改工作目录，而宿主视角的 /root 在新根里不存在，
        // 之前只在外层 cd，导致会话一起来 pwd 就是 /（真机测试逮到过）。
        // 现在 cd 放在 chroot 内部执行。
        val r = runBlocking { session.run("pwd") }
        assertEquals(0, r.exitCode)
        assertTrue("会话起始目录应是 /root，实际「${r.output}」", r.output.trim().endsWith("/root"))
    }

    @Test
    fun keepsWorkingDirectoryAcrossCalls() {
        val a = runBlocking { session.run("cd /opt && pwd") }
        assertEquals(0, a.exitCode)
        assertTrue("cd 后应输出 /opt，实际「${a.output}」", a.output.contains("/opt"))

        // 关键：下一条命令仍在 /opt —— 这正是 linux.exec 做不到的
        val b = runBlocking { session.run("pwd") }
        assertEquals(0, b.exitCode)
        assertTrue("cwd 应跨调用保留，实际「${b.output}」", b.output.trim().endsWith("/opt"))
    }

    @Test
    fun keepsEnvironmentVariablesAcrossCalls() {
        runBlocking { session.run("export ETA_DEV_TEST=hello42") }
        val out = runBlocking { session.run("echo \"V=\$ETA_DEV_TEST\"") }
        assertEquals(0, out.exitCode)
        assertTrue("环境变量应跨调用保留，实际「${out.output}」", out.output.contains("V=hello42"))
    }

    @Test
    fun reportsExitCodeFaithfully() {
        assertEquals(0, runBlocking { session.run("true") }.exitCode)
        assertEquals(1, runBlocking { session.run("false") }.exitCode)
        assertEquals(9, runBlocking { session.run("(exit 9)") }.exitCode)
        assertEquals(127, runBlocking { session.run("nosuchcmd_eta_xyz 2>/dev/null") }.exitCode)
    }

    @Test
    fun separatesStdoutFromStderrAndCapturesBoth() {
        val r = runBlocking { session.run("echo to_stdout; echo to_stderr 1>&2") }
        // 两份都要给模型：只看 stdout 会让失败原因凭空消失
        assertTrue(r.output.contains("to_stdout"))
        assertTrue(r.output.contains("to_stderr"))
    }

    @Test
    fun stdinReadingCommandDoesNotPoisonSession() {
        // cat 会读 stdin。没有 </dev/null 隔离时它会吃掉后续命令，整条会话报废。
        runBlocking { session.run("cat", timeoutMs = 8_000) }
        val after = runBlocking { session.run("echo AFTER_CAT_OK") }
        assertEquals("cat 之后会话必须仍然可用", 0, after.exitCode)
        assertTrue(after.output.contains("AFTER_CAT_OK"))
    }

    @Test
    fun backgroundJobSurvivesAcrossCalls() {
        val spawn = runBlocking { session.run("nohup sleep 25 >/dev/null 2>&1 & echo started") }
        assertEquals(0, spawn.exitCode)
        // 同一会话里再查一次：进程还在，说明后台任务没有被上一次调用带走
        val jobs = runBlocking { session.run("jobs") }
        assertTrue("后台任务应存活，实际「${jobs.output}」", jobs.output.contains("Running"))
    }

    @Test
    fun bareExitIsRejectedWithoutKillingSession() {
        val guard = runBlocking { session.run("exit") }
        assertTrue("应给出可读的拒绝说明", guard.output.contains("exit"))
        assertTrue("应提示替代写法", guard.output.contains("(exit N)"))
        // 会话必须还在：拦下来就是为了不让它被 exit 干掉
        val after = runBlocking { session.run("echo STILL_ALIVE") }
        assertEquals(0, after.exitCode)
        assertTrue(after.output.contains("STILL_ALIVE"))
    }

    @Test
    fun workdirParameterAppliesBeforeCommand() {
        val r = runBlocking { session.run("pwd", workdir = "/tmp") }
        assertEquals(0, r.exitCode)
        assertTrue("应先在 /tmp 里执行，实际「${r.output}」", r.output.trim().endsWith("/tmp"))
    }

    @Test
    fun sharedStorageIsMounted() {
        val r = runBlocking { session.run("ls /sdcard >/dev/null 2>&1 && echo SDCARD_OK") }
        assertTrue("共享存储应可读，实际「${r.output}」", r.output.contains("SDCARD_OK"))
    }

    @Test
    fun killTerminatesSessionAndItsBackgroundJobs() {
        runBlocking { session.run("nohup sleep 40 >/dev/null 2>&1 & echo started") }
        runBlocking { session.kill() }
        assertFalse("kill 后不应仍在运行", session.running)

        // 再调用会重建会话：cwd 回到 /root，环境变量清空
        val fresh = runBlocking { session.run("pwd") }
        assertEquals(0, fresh.exitCode)
        assertTrue("新会话应回到 /root，实际「${fresh.output}」", fresh.output.trim().endsWith("/root"))
    }

    @Test
    fun dnsResolvesInsideSession() {
        // 回归：resolv.conf 曾经因为多转义一层而写成「一行、含字面量 \n」，
        // glibc 解析不了 -> 会话内 DNS 全废。表现是模型想装包/克隆仓库全部失败，
        // 而错误只有 "Temporary failure resolving"，极难追到根因。
        val r = runBlocking { session.run("getent hosts deb.debian.org >/dev/null 2>&1 && echo DNS_OK || echo DNS_FAIL") }
        assertTrue("会话内 DNS 应可用，实际「${r.output}」", r.output.contains("DNS_OK"))
    }

    @Test
    fun resolvConfHasRealNewlines() {
        // 只测「能解析」还不够：解析在别的机器上可能被公共 DNS 兜住。
        // 直接看文件结构，确认是多个换行分隔的 nameserver 行，而不是一行字面量。
        val r = runBlocking { session.run("wc -l < /etc/resolv.conf; grep -c '^nameserver' /etc/resolv.conf") }
        val lines = r.output.trim().lines().map { it.trim() }.filter { it.toIntOrNull() != null }
        assertTrue("resolv.conf 应是多行，实际输出「${r.output}」", lines.isNotEmpty() && lines.first().toInt() >= 2)
        assertTrue("应有多条 nameserver 行，实际输出「${r.output}」", lines.size >= 2 && lines[1].toInt() >= 2)
    }

    @Test
    fun tmpdirPointsInsideRootfsAndIsWritable() {
        // 回归：不设 TMPDIR 会继承宿主的（指向应用缓存目录，chroot 里不存在）。
        // apt 的 postinst 用 mktemp 时直接失败，包停在半配置状态（iF），
        // 实测 openssh-server 就是这样装不完整的。
        val r = runBlocking {
            session.run("echo T=\$TMPDIR; touch \"\$TMPDIR/eta-probe\" && echo WRITABLE && rm -f \"\$TMPDIR/eta-probe\"")
        }
        assertTrue("TMPDIR 应指向 rootfs 内的 /tmp，实际「${r.output}」", r.output.contains("T=/tmp"))
        assertTrue("TMPDIR 应可写，实际「${r.output}」", r.output.contains("WRITABLE"))
    }

    @Test
    fun aptCanInstallPackageEndToEnd() {
        // 端到端：模型在会话里装包必须真的能装完。
        // 这条覆盖 DNS + TMPDIR + DEBIAN_FRONTEND 三件事的组合结果——
        // 缺任何一项，装包都会失败或停半配置，而这正是模型最常见的用法之一。
        // 用一个极小、无依赖、在任意镜像都有的包，避免测试依赖网络质量。
        val r = runBlocking {
            session.run(
                "apt-get install -y -qq --no-install-recommends tree >/dev/null 2>&1; " +
                    "dpkg -l tree 2>/dev/null | tail -1 | cut -c1-2",
                180_000L,
            )
        }
        val state = r.output.trim().lines().lastOrNull()?.trim().orEmpty()
        assertTrue("tree 应安装成功（dpkg 状态 ii），实际状态「$state」，输出：${r.output}", state == "ii")
    }

    @Test
    fun leavesNoLeftoverMountsAfterKill() {
        runBlocking { session.run("echo warmup") }
        val rootfs = LinuxEnvStore.rootfs(context, distro).absolutePath
        runBlocking { session.kill() }
        Thread.sleep(1500)

        // 私有 mount namespace 的意义就在这里：会话结束，挂载必须一并消失。
        // 残留挂载会让系统的存储统计穿透到整个外部存储（LinuxRuntime 里记过）。
        val mounts = File("/proc/mounts").readText()
        assertFalse(
            "会话结束后不应残留 rootfs 挂载",
            mounts.contains(rootfs),
        )
    }
}
