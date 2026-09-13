package com.mcp.toolbox.feature.capture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 抓包记录仓：网络线程写入，Compose 侧读取。
 * 记录上限 500 条，超出后优先丢弃最旧的已结束记录。
 */
object CaptureStore {
    private const val MAX_RECORDS = 500
    private const val MAX_DNS_LOG = 200

    private val lock = Any()
    private val records = LinkedHashMap<Long, CaptureRecord>()
    private val dnsLog = ArrayDeque<DnsObservation>()
    private var nextId = 1L
    private var stats = CaptureStats()
    private var lastPublish = 0L

    private val _state = MutableStateFlow(CaptureUiState(records = emptyList(), statusText = "未启动"))
    val state: StateFlow<CaptureUiState> = _state.asStateFlow()

    fun openRecord(record: CaptureRecord): Long = synchronized(lock) {
        val id = nextId++
        records[id] = record.copy(id = id)
        stats = stats.copy(total = stats.total + 1)
        publish(force = true)
        id
    }

    fun updateRecord(id: Long, transform: (CaptureRecord) -> CaptureRecord) = synchronized(lock) {
        val current = records[id] ?: return@synchronized
        records[id] = transform(current)
        publish(force = false)
    }

    fun addTraffic(upBytes: Long, downBytes: Long) = synchronized(lock) {
        stats = stats.copy(upBytes = stats.upBytes + upBytes, downBytes = stats.downBytes + downBytes)
        publish(force = false)
    }

    fun addDropped(count: Long = 1) = synchronized(lock) {
        stats = stats.copy(droppedPackets = stats.droppedPackets + count)
        publish(force = false)
    }

    fun addDns(observation: DnsObservation) = synchronized(lock) {
        dnsLog.addFirst(observation)
        while (dnsLog.size > MAX_DNS_LOG) dnsLog.removeLast()
        publish(force = true)
    }

    fun find(id: Long): CaptureRecord? = synchronized(lock) { records[id] }

    /** 解密成功的连接会变成一条条真实 HTTP 记录，占位记录直接移除。 */
    fun removeRecord(id: Long) = synchronized(lock) {
        if (records.remove(id) != null) publish(force = false)
    }

    /** 用 DNS 解析结果给无 SNI 的连接补域名。 */
    fun resolveHost(ip: String): String? = synchronized(lock) {
        dnsLog.firstOrNull { it.ip == ip }?.name
    }
@Suppress("LongParameterList")
fun session(
    running: Boolean = _state.value.running,
    paused: Boolean = _state.value.paused,
    dnsOnly: Boolean = _state.value.dnsOnly,
    mode: CaptureMode = _state.value.mode,
    packages: Set<String> = _state.value.packages,
    statusText: String = _state.value.statusText,
    error: String? = _state.value.error,
    captureIp: String = _state.value.captureIp,
    foreground: Boolean = _state.value.foreground,
) = synchronized(lock) {
    _state.value = _state.value.copy(
        running = running,
        paused = paused,
        dnsOnly = dnsOnly,
        mode = mode,
        packages = packages,
        statusText = statusText,
        error = error,
        captureIp = captureIp,
        foreground = foreground,
        dnsLog = dnsLog.toList(),
        stats = stats,
    )
}

fun clear() = synchronized(lock) {
    records.clear()
    dnsLog.clear()
    stats = CaptureStats(startedAt = stats.startedAt)
    publish(force = true)
}

fun resetSessionStats() = synchronized(lock) {
    stats = CaptureStats(startedAt = System.currentTimeMillis())
    publish(force = true)
}

fun snapshot(): List<CaptureRecord> = synchronized(lock) { records.values.toList() }

fun currentStats(): CaptureStats = synchronized(lock) { stats }

private fun publish(force: Boolean) {
    val now = System.currentTimeMillis()
    if (!force && now - lastPublish < 250) return
    lastPublish = now
    if (records.size > MAX_RECORDS) {
        val overflow = records.size - MAX_RECORDS
        val iter = records.entries.iterator()
        var removed = 0
        while (iter.hasNext() && removed < overflow) {
            val entry = iter.next()
            if (entry.value.state == FlowState.CLOSED || entry.value.state == FlowState.FAILED) {
                iter.remove()
                removed++
            }
        }
    }
    val list = records.values.toList().asReversed()
    _state.value = _state.value.copy(records = list, dnsLog = dnsLog.toList(), stats = stats)
}
}
