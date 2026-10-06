package com.mcp.toolbox.feature.home.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 等待胶囊的文案与总时长解析。
 *
 * 用户要求卡片最简：只留一句「计时 x 秒」/「计时完成」。文案由纯函数决定，
 * 因此可以脱离 UI 测 —— 否则只能靠肉眼看卡片。
 *
 * ⚠️ **`waitTotalSeconds` 不在覆盖范围内**，原因与 `patchToolSegment` 同：
 * 它要解析 JSON，而 JVM 单测里的 `org.json` 是 Android 的空壳（构造即抛），
 * 调一次只会拿到兜底值 0 —— 写出来的断言会「通过」，但测的是空的。
 * 那两行解析的正确性只能靠代码审查 + 真机，别把它算进已测。
 */
class WaitCapsuleTextTest {

    @Test
    fun `进行中显示已计时与总量`() {
        assertEquals("计时 0s / 100s", ToolVisuals.waitCapsuleText(0, 100, running = true))
        assertEquals("计时 12s / 100s", ToolVisuals.waitCapsuleText(12, 100, running = true))
    }

    @Test
    fun `走完显示完成`() {
        assertEquals("计时完成（共 100 秒）", ToolVisuals.waitCapsuleText(100, 100, running = false))
        // 计时可能略微超过总量（调度误差），仍算完成
        assertEquals("计时完成（共 30 秒）", ToolVisuals.waitCapsuleText(31, 30, running = false))
    }

    @Test
    fun `没走完就收尾显示已中断而不是完成`() {
        // 用户点了停止却看到「计时完成」会以为真等够了 —— 必须区分开
        assertEquals("计时已中断（3s / 100s）", ToolVisuals.waitCapsuleText(3, 100, running = false))
    }

    @Test
    fun `总时长缺失或非法时不显示进度`() {
        // 模型没给 seconds、或给了负数时，宁可只显示「计时」，也不要显示 3s/0s 这种怪东西
        assertEquals("计时", ToolVisuals.waitCapsuleText(3, 0, running = false))
        assertEquals("计时", ToolVisuals.waitCapsuleText(3, 0, running = true))
    }

    @Test
    fun `负数已计时按零处理`() {
        assertEquals("计时 0s / 100s", ToolVisuals.waitCapsuleText(-5, 100, running = true))
    }

    @Test
    fun `倒计时显示剩余量从大到小`() {
        // 倒计时是 x → 0，显示剩余；计时是 0 → x，显示已走。两者不能混。
        assertEquals("倒计时 30s", ToolVisuals.waitCapsuleText(0, 30, running = true, countdown = true))
        assertEquals("倒计时 12s", ToolVisuals.waitCapsuleText(18, 30, running = true, countdown = true))
        assertEquals("倒计时 0s", ToolVisuals.waitCapsuleText(30, 30, running = true, countdown = true))
    }

    @Test
    fun `倒计时走完与中断的文案`() {
        assertEquals("倒计时完成（共 30 秒）", ToolVisuals.waitCapsuleText(30, 30, running = false, countdown = true))
        assertEquals("倒计时已中断（还剩 22s）", ToolVisuals.waitCapsuleText(8, 30, running = false, countdown = true))
    }

    @Test
    fun `工具名的点与下划线两种写法都认`() {
        // 时间线里存的是模型返回的函数名，出站时点会被换成下划线，
        // 所以实际拿到的是 system_wait。早先只认点形式，胶囊从未出现过。
        assertTrue(ToolVisuals.isWaitTool("system.wait"))
        assertTrue(ToolVisuals.isWaitTool("system_wait"))
        assertFalse(ToolVisuals.isWaitTool("linux_exec"))
        assertFalse(ToolVisuals.isWaitTool("linux.exec"))
    }

    @Test
    fun `沙漏两处停顿都在，且两段翻转对称`() {
        // 用户实测踩过两个坑，都由这个纯函数钉住：
        // ① 只在 180 停过、360 没停（每整圈只停一次）
        // ② 两段翻转快慢不一致
        val eps = 1e-4f
        fun angleAt(p: Float) = ToolVisuals.waitFlipAngle(p)

        // 周期两端都是 0° 姿态（0 与 360 视觉一致，才能安全 Restart 循环）
        assertEquals(0f, angleAt(0f), eps)
        assertEquals(360f, angleAt(1f), eps)

        // 第一处停顿：角度停在 180 不动
        assertEquals(180f, angleAt(0.30f), eps)
        assertEquals(180f, angleAt(0.45f), eps)
        // 第二处停顿：角度停在 360 不动
        assertEquals(360f, angleAt(0.80f), eps)
        assertEquals(360f, angleAt(0.95f), eps)

        // 两段翻转对称：各自走到一半时，转过的角度应当一样
        val firstHalf = angleAt(0.13f)          // 第一段的中点
        val secondHalf = angleAt(0.63f) - 180f  // 第二段的中点，减掉已转的 180
        assertEquals("两段翻转速度应一致", firstHalf, secondHalf, 0.5f)
    }

    @Test
    fun `进度越界不崩且夹在周期内`() {
        assertEquals(0f, ToolVisuals.waitFlipAngle(-1f), 1e-4f)
        assertEquals(360f, ToolVisuals.waitFlipAngle(2f), 1e-4f)
    }
}
