package com.mcp.toolbox.ui.linux.term

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「丢弃 shell 重画痕迹」闸门的单测。
 *
 * ## 为什么值得测
 *
 * 这段规则的作用是：mksh 在尺寸变化时重画提示符会吐出一串「空格 + 退格」，
 * 宽度变化的那次它算不平，会在屏幕上多留一行并带偏移；累积起来就是一屏
 * 重复提示符。规则靠肉眼在真机上验不出来（要反复缩放、还要数提示符），
 * 所以把判据抽成 [RepaintDropGate] 在这里钉死。
 *
 * 测的重点是**不能误伤正常输出** —— 丢错东西比多留几个提示符严重得多。
 */
class RepaintDropGateTest {

    /** 真实形态：mksh 的重画痕迹 = 一串空格 + 一串退格。 */
    private val repaintChunk = "A# " + " ".repeat(56) + "\b".repeat(39)

    @Test
    fun `未武装时原样放行`() {
        val gate = RepaintDropGate()
        assertEquals(repaintChunk, gate.filter(repaintChunk))
    }

    @Test
    fun `武装后含退格的块被丢弃`() {
        val gate = RepaintDropGate()
        gate.arm()
        assertNull(gate.filter(repaintChunk))
    }

    @Test
    fun `丢弃后保持武装，痕迹分块到达也能续上`() {
        val gate = RepaintDropGate()
        gate.arm()
        // 痕迹可能被 pty 分成几块送达；每一块都含退格，都应丢弃。
        assertNull(gate.filter("A# " + " ".repeat(20) + "\b".repeat(19)))
        assertNull(gate.filter(" ".repeat(10) + "\b".repeat(10)))
    }

    @Test
    fun `遇到正常输出即解除武装并放行`() {
        val gate = RepaintDropGate()
        gate.arm()
        assertNull(gate.filter(repaintChunk))

        // 紧接着的正常输出必须完好保留
        val normal = "hello\r\n"
        assertEquals(normal, gate.filter(normal))

        // 解除之后，再有含退格的内容也不该被丢（那是用户程序自己的输出）
        val progress = "50%\b\b\b51%"
        assertEquals(progress, gate.filter(progress))
    }

    @Test
    fun `武装状态下第一块就是正常输出时不会被误丢`() {
        val gate = RepaintDropGate()
        gate.arm()
        // 尺寸变化后 shell 若什么都没重画，下一条正常输出必须放行
        assertEquals("ls\r\n", gate.filter("ls\r\n"))
    }

    @Test
    fun `不含退格的正常输出在武装状态下只放行一次并解除`() {
        val gate = RepaintDropGate()
        gate.arm()
        assertEquals("abc", gate.filter("abc"))
        // 已解除，后续照常
        assertEquals("def", gate.filter("def"))
    }

    @Test
    fun `disarm 可撤销武装`() {
        val gate = RepaintDropGate()
        gate.arm()
        gate.disarm()
        assertEquals(repaintChunk, gate.filter(repaintChunk))
    }

    @Test
    fun `空串在武装状态下视为正常输出并解除武装`() {
        val gate = RepaintDropGate()
        gate.arm()
        assertEquals("", gate.filter(""))
        // 已解除：后续含退格的块不再被丢
        assertEquals(repaintChunk, gate.filter(repaintChunk))
    }

    @Test
    fun `只含退格不含内容的块也被丢弃`() {
        val gate = RepaintDropGate()
        gate.arm()
        assertNull(gate.filter("\b\b\b"))
    }

    @Test
    fun `正常输出里的退格不会在未武装时被丢`() {
        val gate = RepaintDropGate()
        val progress = "9%\b\b10%"
        assertTrue(gate.filter(progress)!!.contains("10%"))
    }
}
