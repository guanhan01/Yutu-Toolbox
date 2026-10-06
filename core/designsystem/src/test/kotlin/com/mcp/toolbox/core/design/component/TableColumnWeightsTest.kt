package com.mcp.toolbox.core.design.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 表格列宽权重分配（[computeColumnWeights]）。
 *
 * 背景：早先按「各列最长内容的估算宽度」硬定列宽且不设上限，于是两列表格也会
 * 撑出屏幕，而表头的色条只覆盖到「列宽之和」，右侧留一条白边（用户截图里那圈
 * 空白）。改成按权重分配后，权重之和恒为 1，列宽总和就等于可用宽度。
 */
class TableColumnWeightsTest {

    /** 纯 ASCII 的宽度估算：每字符 7.5 → 用于构造已知长短的用例。 */
    private fun ascii(n: Int) = "x".repeat(n)

    @Test
    fun `权重之和恒为一，列宽才会铺满可用宽度`() {
        val cases = listOf(
            listOf("项目", "结果") to listOf(listOf("发行版", "Debian 13 (trixie), aarch64")),
            listOf("项", "值") to listOf(listOf("内核", "6.6.77"), listOf("CPU", "8 核")),
            listOf("a", "b", "c", "d") to listOf(listOf("1", "2", "3", "4")),
        )
        cases.forEach { (header, rows) ->
            val w = computeColumnWeights(header, rows, header.size)
            assertEquals("列数应一致", header.size, w.size)
            assertEquals("权重之和必须为 1（否则表头色条会留白）", 1.0f, w.sum(), 0.001f)
        }
    }

    @Test
    fun `长内容列拿到更大权重`() {
        val w = computeColumnWeights(
            header = listOf("项", "结果"),
            rows = listOf(listOf("发行版", ascii(120))),
            columnCount = 2,
        )
        assertTrue("内容长的列应更宽：$w", w[1] > w[0])
    }

    @Test
    fun `窄列不会被挤成竖排单字`() {
        // 一列极长、一列极短：短列必须有下限，否则每行只放得下一两个字
        val w = computeColumnWeights(
            header = listOf("k", ascii(400)),
            rows = emptyList(),
            columnCount = 2,
        )
        // 下限是 0.16（两列时）。早先「先夹紧再归一」的实现会把它压到 0.138。
        assertTrue("短列不应低于下限 0.16：$w", w[0] >= 0.16f - 0.001f)
    }

    @Test
    fun `列为空时不会得到零宽`() {
        // 只有分隔符、没有表头内容的情况
        val w = computeColumnWeights(
            header = listOf(""),
            rows = listOf(listOf("")),
            columnCount = 1,
        )
        assertEquals(1, w.size)
        assertTrue("空列的权重必须为正，否则内容不可见：$w", w[0] > 0f)
    }

    @Test
    fun `单列表格直接占满`() {
        val w = computeColumnWeights(header = listOf("只有一列"), rows = emptyList(), columnCount = 1)
        assertEquals(listOf(1f), w)
    }

    @Test
    fun `零列不崩`() {
        assertTrue(computeColumnWeights(emptyList(), emptyList(), 0).isEmpty())
    }

    @Test
    fun `行比表头长时按更宽的列数算`() {
        // 有些模型输出的表格行会比表头多一格；columnCount 取两者最大，
        // 权重数组也必须按这个数量给，否则渲染时取不到权重会退回默认值。
        val w = computeColumnWeights(header = listOf("a"), rows = listOf(listOf("1", "2", "3")), columnCount = 3)
        assertEquals(3, w.size)
        assertEquals(1.0f, w.sum(), 0.001f)
    }
}
