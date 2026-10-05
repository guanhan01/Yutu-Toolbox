package com.mcp.toolbox.ui.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mcp.toolbox.feature.mcp.WriteGuard
import com.mcp.toolbox.ui.linux.LinuxDistro
import com.mcp.toolbox.ui.linux.LinuxEnvStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 「AI 到底能不能动用 Linux 环境」的端到端验证。
 *
 * 前面的 [com.mcp.toolbox.ui.linux.AiShellSessionDeviceTest] 测的是会话本身，
 * 这里测的是**模型真正走的路径**：[AiToolBridge.toolSpecs] 把工具交给模型，
 * [AiToolBridge.invoke] 执行模型发起的调用。两段都对，才谈得上「能用」。
 *
 * 注意工具名在出站时会把点换成下划线（OpenAI 的约束）：`linux.shell` -> `linux_shell`。
 */
@RunWith(AndroidJUnit4::class)
class AiToolExposureDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun hasRoot(): Boolean = runCatching {
        val p = ProcessBuilder("/system/bin/sh", "-c", "su -c id")
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        out.contains("uid=0")
    }.getOrDefault(false)

    private var writeWasEnabled = false

    @Before
    fun setUp() {
        assumeTrue("设备没有 Root", hasRoot())
        assumeTrue("Linux 环境未安装", LinuxEnvStore.isInstalled(context, LinuxDistro.DEBIAN))
        writeWasEnabled = WriteGuard.allowedNow
    }

    @After
    fun tearDown() {
        // 放开写入只是本测试的需要，跑完恢复原状，不留下「被悄悄打开」的开关
        WriteGuard.set(context, writeWasEnabled)
        // 会话是进程级单例，不清掉会带着「cd 到 /opt」的状态留给下一个用例
        runBlocking { runCatching { AiToolBridge.invoke(context, "linux_shell_kill", "{}") } }
    }

    @Test
    fun linuxShellToolsAreOfferedToTheModel() {
        // 这是模型看到的工具清单：名字不在这里，模型就根本不知道有这个能力
        val names = AiToolBridge.toolSpecs(context).map { it.name }
        listOf("linux_shell", "linux_shell_status", "linux_shell_kill", "linux_exec").forEach { expected ->
            assertTrue("模型拿不到工具 $expected；实际含 linux 的有：${names.filter { it.startsWith("linux") }}",
                names.contains(expected))
        }
        // 描述要能让模型判断出「这个会话会保留状态」，否则它不会用来分步干活
        val shellSpec = AiToolBridge.toolSpecs(context).first { it.name == "linux_shell" }
        assertTrue("描述应说明会保留 cd/环境变量", shellSpec.description.contains("cd"))
        assertTrue("描述应提示不要写 exit", shellSpec.description.contains("exit"))
    }

    @Test
    fun invokingLinuxShellThroughAiBridgeActuallyRuns() {
        WriteGuard.set(context, true)
        // 走的就是模型发起 function call 的那条路径
        val out = runBlocking { AiToolBridge.invoke(context, "linux_shell", """{"command":"pwd"}""") }
        assertFalse("不应是「没有名为…的工具」，实际：$out", out.contains("没有名为"))
        assertTrue("应在 Linux 环境里执行并返回 /root，实际：$out", out.contains("/root"))
    }

    @Test
    fun statePersistsAcrossSeparateAiInvocations() {
        WriteGuard.set(context, true)
        // 两次独立的工具调用 = 模型的两轮 function call。
        // 状态保留是这套能力的全部意义所在，所以要在这一层验证，而不是只在会话层。
        runBlocking { AiToolBridge.invoke(context, "linux_shell", """{"command":"cd /opt"}""") }
        val out = runBlocking { AiToolBridge.invoke(context, "linux_shell", """{"command":"pwd"}""") }
        assertTrue("第二次调用应仍在 /opt，实际：$out", out.contains("/opt"))
    }

    @Test
    fun writeDisabledBlocksTheShell() {
        WriteGuard.set(context, false)
        val out = runBlocking { AiToolBridge.invoke(context, "linux_shell", """{"command":"pwd"}""") }
        // 默认关闭时必须是可读的拒绝，而不是静默执行
        assertTrue("应提示需要打开「允许写入」，实际：$out", out.contains("允许写入"))
    }
}
