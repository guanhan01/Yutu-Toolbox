package com.mcp.toolbox.feature.capture

/** 抓包会话配置。 */
data class CaptureConfig(
    val mode: CaptureMode = CaptureMode.ALL,
    val packages: Set<String> = emptySet(),
    val dnsOnly: Boolean = false,
    val hostFilter: String = "",
    val protocolFilter: String = "",
    val bodyLimitBytes: Int = 64 * 1024,
    val httpsDecrypt: Boolean = false,
)

enum class CaptureMode { ALL, ONLY_SELECTED, EXCLUDE_SELECTED }

enum class CaptureKind { HTTP, HTTPS, TCP, UDP, DNS, OTHER }

enum class FlowState { CONNECTING, OPEN, CLOSED, FAILED }

/** 单条抓包记录。字段全部来自真实报文解析。 */
data class CaptureRecord(
    val id: Long,
    val kind: CaptureKind,
    val protocol: String,
    val srcIp: String = "",
    val srcPort: Int = 0,
    val dstIp: String = "",
    val dstPort: Int = 0,
    val host: String? = null,
    val scheme: String = "",
    val method: String? = null,
    val path: String? = null,
    val status: Int? = null,
    val reason: String? = null,
    val requestHeaders: List<Pair<String, String>> = emptyList(),
    val responseHeaders: List<Pair<String, String>> = emptyList(),
    val requestHead: String? = null,
    val responseHead: String? = null,
    val requestBody: String? = null,
    val responseBody: String? = null,
    val requestBytes: Long = 0,
    val responseBytes: Long = 0,
    val startedAt: Long = System.currentTimeMillis(),
    val connectedAt: Long? = null,
    val requestAt: Long? = null,
    val responseAt: Long? = null,
    val endedAt: Long? = null,
    val state: FlowState = FlowState.CONNECTING,
    val error: String? = null,
    val appPackage: String? = null,
    val appLabel: String? = null,
    val uid: Int = -1,
    val sni: String? = null,
    val tlsVersion: String? = null,
    val alpn: String? = null,
    val transparent: Boolean = false,
    val mitm: Boolean = false,
) {
    val durationMs: Long get() = ((endedAt ?: System.currentTimeMillis()) - startedAt).coerceAtLeast(0)
    val tlsHandshakeMs: Long? get() = if (connectedAt != null && requestAt != null) requestAt - connectedAt else null
    val ttfbMs: Long? get() = if (requestAt != null && responseAt != null) responseAt - requestAt else null
    val totalBytes: Long get() = requestBytes + responseBytes
    val endpoint: String get() = if (dstIp.isEmpty()) "${host ?: "—"}" else "$dstIp:$dstPort"
    val name: String get() = host ?: sni ?: "—"
    val url: String? get() {
        val h = host ?: sni ?: return null
        val schemeText = if (scheme.isNotEmpty()) scheme else if (kind == CaptureKind.HTTPS) "https" else "http"
        val portPart = when {
            schemeText == "http" && dstPort != 80 -> ":$dstPort"
            schemeText == "https" && dstPort != 443 -> ":$dstPort"
            else -> ""
        }
        return "$schemeText://$h$portPart${path ?: "/"}"
    }
}

data class CaptureStats(
    val total: Long = 0,
    val upBytes: Long = 0,
    val downBytes: Long = 0,
    val startedAt: Long? = null,
    val droppedPackets: Long = 0,
)

data class DnsObservation(val name: String, val ip: String?, val qType: String?, val at: Long, val fromApp: String?)

data class CaptureUiState(
    val running: Boolean = false,
    val paused: Boolean = false,
    val dnsOnly: Boolean = false,
    val mode: CaptureMode = CaptureMode.ALL,
    val packages: Set<String> = emptySet(),
    val records: List<CaptureRecord> = emptyList(),
    val dnsLog: List<DnsObservation> = emptyList(),
    val stats: CaptureStats = CaptureStats(),
    val statusText: String = "未启动",
    val error: String? = null,
    val captureIp: String = "",
    val foreground: Boolean = false,
)
