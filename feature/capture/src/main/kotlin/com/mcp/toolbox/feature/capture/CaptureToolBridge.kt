package com.mcp.toolbox.feature.capture

/**
 * 内置 MCP Server 使用的抓包桥接层。
 *
 * 说明：抓包记录仓 `CaptureStore` 里的 snapshot / clear / currentStats / resetSessionStats
 * 实际都是该 object 的成员（不是顶层函数），跨模块调用时容易写错，这里统一收敛成
 * 一个只读 + 清理的窄接口给 feature:mcp 使用。
 */
object CaptureToolBridge {

    fun list(): List<CaptureRecord> = CaptureStore.snapshot()

    fun count(): Int = CaptureStore.snapshot().size

    fun find(id: Long): CaptureRecord? = CaptureStore.find(id)

    fun statsText(): String = runCatching { CaptureStore.currentStats().toString() }
        .getOrElse { "读取统计失败：${it.message}" }

    fun clearAll(resetStats: Boolean) {
        CaptureStore.clear()
        if (resetStats) CaptureStore.resetSessionStats()
    }

    fun remove(id: Long) {
        CaptureStore.removeRecord(id)
    }
}
