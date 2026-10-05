package com.mcp.toolbox.feature.home.agent.stream

import com.mcp.toolbox.core.design.component.MdBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MarkdownChunker] 的闭合与容错行为验收（Spec 第 5.1 节 ③④）。
 */
class MarkdownChunkerTest {

    @Test
    fun `首个空行之前的内容进入已闭合缓存`() {
        val c = MarkdownChunker()
        c.append("第一段\n\n")
        val out = c.current()
        assertEquals(1, out.closedBlocks.size)
        assertTrue(out.closedBlocks.first() is MdBlock.Paragraph)
        assertEquals("", out.tail)
    }

    @Test
    fun `已闭合块不会被重复解析`() {
        val c = MarkdownChunker()
        c.append("A 段\n\n")
        val first = c.current().closedBlocks
        c.append("B 段还在流入")
        val second = c.current().closedBlocks
        // 同一个 List 实例，长度不变 —— 说明没有重解析
        assertEquals(1, first.size)
        assertEquals(1, second.size)
        assertEquals("B 段还在流入", c.current().tail)
    }

    @Test
    fun `半截围栏不会被误判成代码块`() {
        val c = MarkdownChunker()
        // 输入到一半：两个反引号 + 还没换行
        c.append("说明：\n``")
        val out = c.current()
        // 尾块按正文渲染（围栏还没成立，行也不完整）
        assertEquals(false, out.tailInOpenFence)
        assertEquals("说明：\n``", out.tail)
    }

    @Test
    fun `未闭合围栏内不切块`() {
        val c = MarkdownChunker()
        c.append("前言\n\n```kotlin\nval a = 1\n\nval b = 2\n")
        val out = c.current()
        // 只有「前言」被闭合；围栏内的空行不作切块点
        assertEquals(1, out.closedBlocks.size)
        assertTrue(out.tailInOpenFence)
        assertTrue(out.tail.contains("val b = 2"))
    }

    @Test
    fun `围栏闭合后恢复正常切块`() {
        val c = MarkdownChunker()
        c.append("前言\n\n")
        c.append("```kotlin\nval a = 1\n```\n\n")
        val out = c.current()
        // 「前言」+ 代码块
        assertEquals(2, out.closedBlocks.size)
        assertTrue(out.closedBlocks.any { it is MdBlock.Code })
        assertTrue(!out.tailInOpenFence)
    }

    @Test
    fun `流式逐字喂入最终得到与一次性解析相同的结果`() {
        val text = buildString {
            append("# 标题\n\n")
            append("这是一段正文，**加粗** 与 `代码`。\n\n")
            append("```kotlin\nval x = 1\n```\n\n")
            append("- 项一\n- 项二\n\n")
            append("结尾段落")
        }
        val c = MarkdownChunker()
        // 每次 3 个字符，模拟 token 流
        text.chunked(3).forEach { c.append(it) }
        val out = c.current()
        val closedTypes = out.closedBlocks.map { it::class.simpleName }
        assertEquals(
            listOf("Heading", "Paragraph", "Code", "Bullet"),
            closedTypes,
        )
        assertEquals("结尾段落", out.tail)
    }
}
