package com.mcp.toolbox.ui.linux.term

/**
 * 终端输出的有界历史。
 *
 * 为什么需要它：会话（pty + bash）在离开页面后依然活着，但**屏幕缓冲在 WebView 的
 * xterm.js 里**，页面一销毁就没了。再次进入、或在多个窗口之间切换时，xterm 是一块
 * 全新的空屏，只能把「之前输出过的字节」重放一遍才能恢复内容，否则就是白屏
 * （v0.1.5 实测：第二次进终端什么都不显示，连提示符都没有）。
 *
 * 两个约定：
 * - 容量上限 [limit] 字节，写满从最旧处裁掉，并尽量对齐到**行首**，免得 ANSI
 *   序列被截成半截、重放出乱码。
 * - 游标用「累计写入字节数」而不是数组下标：裁剪后旧下标会失效，
 *   因此 [readFrom] 遇到过期游标时直接回退成全量。
 *
 * 方法自身加锁，允许 pty 读取线程与界面线程并发调用。
 */
internal class OutputHistory(private val limit: Int) {

    private var data = ByteArray(minOf(INITIAL_CAPACITY, limit))
    private var size = 0

    /** 累计写入的字节数（含已被裁掉的）。 */
    @Volatile
    var totalWritten = 0L
        private set

    /** 已被裁掉的字节数。 */
    @Volatile
    var dropped = 0L
        private set

    @Synchronized
    fun append(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        if (length <= 0) return
        totalWritten += length

        if (length >= limit) {
            // 单块就超过容量：只留最后 limit 字节
            data = bytes.copyOfRange(offset + length - limit, offset + length)
            size = limit
            dropped = totalWritten - limit
            alignToLineStart()
            return
        }

        val overflow = size + length - limit
        if (overflow > 0) {
            System.arraycopy(data, overflow, data, 0, size - overflow)
            size -= overflow
            dropped += overflow
            alignToLineStart()
        }

        if (data.size < size + length) {
            var capacity = if (data.isEmpty()) INITIAL_CAPACITY else data.size
            while (capacity < size + length) capacity *= 2
            data = data.copyOf(minOf(capacity, limit))
        }
        System.arraycopy(bytes, offset, data, size, length)
        size += length
    }

    /**
     * 读取 [cursor] 之后的输出，返回「新游标 + 数据」。
     *
     * `cursor < 0` 或游标已被裁掉（比 [dropped] 还旧）时返回全量，调用方据此
     * 整屏重放。读取与追加互斥，不会拿到「写了一半」的数据。
     */
    @Synchronized
    fun readFrom(cursor: Long): Pair<Long, ByteArray> {
        if (cursor < 0 || cursor < dropped) return totalWritten to data.copyOf(size)
        if (cursor >= totalWritten) return totalWritten to EMPTY
        return totalWritten to data.copyOfRange((cursor - dropped).toInt(), size)
    }

    /** 清空。重启壳层后旧内容不该再被重放。 */
    @Synchronized
    fun reset() {
        size = 0
        totalWritten = 0
        dropped = 0
    }

    /** 裁掉第一个换行及其之前的部分：重放从整行开头开始。 */
    private fun alignToLineStart() {
        var nl = -1
        for (i in 0 until size) {
            if (data[i] == NEWLINE) {
                nl = i
                break
            }
        }
        // 找不到换行（整屏程序在画界面），或换行已是最后一个字节（只剩残缺一行）
        // 时不调整，避免把缓冲清成空的
        if (nl < 0 || nl >= size - 1) return
        val drop = nl + 1
        System.arraycopy(data, drop, data, 0, size - drop)
        size -= drop
        dropped += drop
    }

    private companion object {
        const val INITIAL_CAPACITY = 16 * 1024
        val EMPTY = ByteArray(0)
        const val NEWLINE: Byte = '\n'.code.toByte()
    }
}
