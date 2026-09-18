package com.mcp.toolbox.feature.capture

import android.content.Context
import com.mcp.toolbox.feature.capture.net.HttpHead
import com.mcp.toolbox.feature.capture.net.HttpParser
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

/**
 * 真实 TLS 中间人：本机环回端口上监听，按引擎给的 “host ip port flowId” 前导行
 * 现签 host 证书与客户端握手，再用真实 SNI 连上游，双向解密 HTTP/1.1 并落成抓包记录。
 *
 * 只在环回地址监听；上游 socket 一律走 VpnService.protect 绕开自身 tun，避免回环。
 */
object MitmProxy {
    private const val MAX_HEAD_BYTES = 64 * 1024
    private const val STREAM_CAPTURE_BYTES = 512 * 1024
    private const val CONNECT_TIMEOUT_MS = 12_000
    private const val SOCKET_TIMEOUT_MS = 30_000

    private val running = AtomicBoolean(false)
    private val contexts = ConcurrentHashMap<String, SSLContext>()

    @Volatile private var pool: ExecutorService? = null
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var appContext: Context? = null
    @Volatile private var protectSocket: ((Socket) -> Boolean)? = null
    @Volatile private var bodyLimit = 64 * 1024
    @Volatile private var clientContext: SSLContext? = null

    @Volatile var port: Int = 0
        private set

    fun ensureStarted(context: Context, protect: (Socket) -> Boolean, limit: Int): Int {
        appContext = context.applicationContext
        protectSocket = protect
        bodyLimit = limit
        if (running.get() && port > 0) return port
        synchronized(this) {
            if (running.get() && port > 0) return port
            val socket = runCatching {
                ServerSocket(0, 64, InetAddress.getLoopbackAddress())
            }.getOrNull() ?: return 0
            serverSocket = socket
            port = socket.localPort
            pool = Executors.newCachedThreadPool { runnable ->
                Thread(runnable, "mitm-worker").apply { isDaemon = true }
            }
            running.set(true)
            Thread({ acceptLoop(socket) }, "mitm-accept").apply { isDaemon = true }.start()
            return port
        }
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        port = 0
        pool?.shutdownNow()
        pool = null
        contexts.clear()
    }

    fun isRunning(): Boolean = running.get()

    fun contextCount(): Int = contexts.size

    private fun addressOf(text: String): InetAddress? {
        val parts = text.substringBefore('%').split('.')
        if (parts.size == 4) {
            val bytes = ByteArray(4)
            var valid = true
            for (index in 0 until 4) {
                val value = parts[index].toIntOrNull()
                if (value == null || value !in 0..255) {
                    valid = false
                    break
                }
                bytes[index] = value.toByte()
            }
            if (valid) return InetAddress.getByAddress(bytes)
        }
        return runCatching { InetAddress.getByName(text) }.getOrNull()
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            val client = runCatching { socket.accept() }.getOrNull() ?: break
            val executor = pool ?: break
            runCatching { executor.execute { handle(client) } }
        }
    }

    private fun handle(client: Socket) {
        var upstreamRaw: Socket? = null
        var host: String? = null
        var failIp: String? = null
        var failPort = 443
        var stage = "初始化"
        var seedId: Long? = null
        val started = System.currentTimeMillis()
        try {
            client.tcpNoDelay = true
            client.soTimeout = SOCKET_TIMEOUT_MS
            val preamble = readLine(client.getInputStream(), 256) ?: return
            val parts = preamble.trim().split(' ')
            val name = parts.getOrNull(0)?.takeIf { it.isNotEmpty() } ?: return
            host = name
            val ip = parts.getOrNull(1) ?: name
            failIp = ip
            val dstPort = parts.getOrNull(2)?.toIntOrNull() ?: 443
            failPort = dstPort
            seedId = parts.getOrNull(3)?.toLongOrNull()
            val seed = seedId?.let { CaptureStore.find(it) }
            if (!running.get()) return

            val sslContext = contextFor(name) ?: throw IllegalStateException(
                buildString {
                    append("无法为 ").append(name).append(" 生成解密证书")
                    MitmCa.lastLeafError?.let { append("｜").append(it) }
                },
            )
            stage = "连接上游"
            val rawUpstream = Socket()
            protectSocket?.invoke(rawUpstream)
            rawUpstream.tcpNoDelay = true
            rawUpstream.soTimeout = SOCKET_TIMEOUT_MS
            val targetAddress = addressOf(ip) ?: throw IllegalStateException("无法解析目标地址 $ip")
            rawUpstream.connect(InetSocketAddress(targetAddress, dstPort), CONNECT_TIMEOUT_MS)
            upstreamRaw = rawUpstream
            stage = "上游 TLS"
            val upstream = tlsClient(rawUpstream, name, dstPort)
            stage = "客户端 TLS"
            val downstream = tlsServer(client, sslContext)
            relay(name, ip, dstPort, seed, started, downstream, upstream)
        } catch (t: Throwable) {
            val seed = seedId?.let { CaptureStore.find(it) }
            val clientSide = stage == "客户端 TLS"
            if (clientSide) CaptureFlags.markDecryptFailed(seed?.appPackage, seed?.uid ?: -1)
            CaptureStore.openRecord(
                CaptureRecord(
                    id = 0,
                    kind = CaptureKind.HTTPS,
                    protocol = "HTTP(S) 解密",
                    dstIp = failIp ?: "",
                    dstPort = failPort,
                    host = host,
                    scheme = "https",
                    sni = host,
                    startedAt = started,
                    endedAt = System.currentTimeMillis(),
                    state = if (clientSide) FlowState.CLOSED else FlowState.FAILED,
                    mitm = false,
                    transparent = true,
                    appPackage = seed?.appPackage,
                    appLabel = seed?.appLabel,
                    uid = seed?.uid ?: -1,
                    error = if (clientSide) {
                        "未解密：${seed?.appLabel ?: "该应用"}不信任用户 CA 或启用了证书固定，已跳过后续解密"
                    } else {
                        "未解密（${stage}）：${t.message ?: t.javaClass.simpleName}"
                    },
                ),
            )
        } finally {
            runCatching { upstreamRaw?.close() }
            runCatching { client.close() }
        }
    }

    private fun relay(
        host: String,
        ip: String,
        dstPort: Int,
        seed: CaptureRecord?,
        started: Long,
        downstream: SSLSocket,
        upstream: SSLSocket,
    ) {
        val clientIn = Buf(downstream.getInputStream())
        val clientOut = downstream.getOutputStream()
        val upIn = Buf(upstream.getInputStream())
        val upOut = upstream.getOutputStream()
        var first = true
        while (running.get() && !downstream.isClosed && !upstream.isClosed) {
            val headBytes = readHead(clientIn) ?: break
            val request = HttpParser.parseRequest(headBytes, 0, headBytes.size) ?: break
            val headText = String(headBytes, Charsets.ISO_8859_1)
            val expectContinue = request.header("Expect")?.contains("100-continue", true) == true
            var pendingRequestBody = expectContinue
            val requestBody = if (expectContinue) {
                BodyChunk(null, 0)
            } else {
                forwardAndCapture(clientIn, upOut, request, bodyLimit)
            }
            val at = System.currentTimeMillis()
            val recordId = CaptureStore.openRecord(
                CaptureRecord(
                    id = 0,
                    kind = CaptureKind.HTTPS,
                    protocol = "HTTP/1.1 · 解密",
                    srcIp = seed?.srcIp ?: "",
                    srcPort = seed?.srcPort ?: 0,
                    dstIp = ip,
                    dstPort = dstPort,
                    host = host,
                    scheme = "https",
                    method = request.method,
                    path = request.target,
                    requestHeaders = request.headers,
                    requestHead = headText,
                    requestBody = preview(requestBody.captured, request, bodyLimit),
                    requestBytes = headBytes.size + requestBody.total,
                    startedAt = if (first) started else at,
                    connectedAt = if (first) started else null,
                    requestAt = at,
                    state = FlowState.OPEN,
                    appPackage = seed?.appPackage,
                    appLabel = seed?.appLabel,
                    sni = host,
                    tlsVersion = runCatching { downstream.session.protocol }.getOrNull(),
                    alpn = "http/1.1",
                    mitm = true,
                ),
            )
            first = false
            upOut.write(rewriteRequestHead(headText).toByteArray(Charsets.ISO_8859_1))
            upOut.flush()

            var status = 0
            var responseHead: HttpHead? = null
            var responseHeadBytes = 0
            var responseBody: BodyChunk? = null
            while (true) {
                val head = readHead(upIn) ?: break
                val parsed = HttpParser.parseResponse(head, 0, head.size) ?: break
                status = parsed.statusCode ?: 0
                clientOut.write(head)
                if (status == 100 && pendingRequestBody) {
                    pendingRequestBody = false
                    val late = forwardAndCapture(clientIn, upOut, request, bodyLimit)
                    CaptureStore.updateRecord(recordId) {
                        it.copy(
                            requestBody = preview(late.captured, request, bodyLimit),
                            requestBytes = headBytes.size + late.total,
                        )
                    }
                }
                val bodyless = status in 100..199 || status == 204 || status == 304 ||
                    request.method.equals("HEAD", ignoreCase = true)
                val body = if (bodyless) BodyChunk(null, 0) else forwardAndCapture(upIn, clientOut, parsed, bodyLimit)
                clientOut.flush()
                if (status !in 100..199) {
                    responseHead = parsed
                    responseHeadBytes = head.size
                    responseBody = body
                    break
                }
            }
            val ended = System.currentTimeMillis()
            CaptureStore.updateRecord(recordId) {
                it.copy(
                    status = status.takeIf { value -> value > 0 },
                    reason = responseHead?.reason,
                    responseHeaders = responseHead?.headers ?: emptyList(),
                    responseHead = responseHead?.let { head -> "HTTP/1.1 $status ${head.reason ?: ""}" },
                    responseBody = preview(responseBody?.captured, responseHead, bodyLimit),
                    responseBytes = responseHeadBytes + (responseBody?.total ?: 0L),
                    responseAt = ended,
                    endedAt = ended,
                    state = if (status > 0 || responseHead != null) FlowState.CLOSED else FlowState.FAILED,
                    error = if (responseHead == null) "上游未返回响应" else null,
                )
            }
            if (responseHead?.header("Upgrade") != null) {
                tunnel(clientIn, clientOut, upIn, upOut, downstream, upstream)
                break
            }
            if (closesConnection(request, responseHead)) break
        }
    }

    private fun tunnel(
        clientIn: Buf,
        clientOut: OutputStream,
        upIn: Buf,
        upOut: OutputStream,
        downstream: SSLSocket,
        upstream: SSLSocket,
    ) {
        val toClient = thread(isDaemon = true, name = "mitm-tunnel-up") {
            runCatching { copyAll(upIn, clientOut) }
            runCatching { downstream.shutdownOutput() }
        }
        val toUpstream = thread(isDaemon = true, name = "mitm-tunnel-down") {
            runCatching { copyAll(clientIn, upOut) }
            runCatching { upstream.shutdownOutput() }
        }
        toClient.join(300_000)
        toUpstream.join(300_000)
    }

    private fun copyAll(reader: Buf, out: OutputStream) {
        val buffer = ByteArray(16 * 1024)
        while (running.get()) {
            val read = reader.read(buffer, 0, buffer.size)
            if (read <= 0) break
            out.write(buffer, 0, read)
            out.flush()
        }
    }

    private data class BodyChunk(val captured: ByteArray?, val total: Long)

    /** 转发报文体到对端，同时保留前 [captureLimit] 字节用于展示（chunked 会顺带解码）。 */
    private fun forwardAndCapture(reader: Buf, out: OutputStream, head: HttpHead, captureLimit: Int): BodyChunk {
        val captured = ByteArrayOutputStream(16 * 1024)
        var total = 0L
        if (head.chunked) {
            while (true) {
                val sizeLine = reader.readLine(64) ?: break
                out.write(sizeLine.toByteArray(Charsets.ISO_8859_1))
                val size = sizeLine.trim().substringBefore(';').toIntOrNull(16) ?: break
                if (size <= 0) {
                    while (true) {
                        val trailer = reader.readLine(512) ?: break
                        out.write(trailer.toByteArray(Charsets.ISO_8859_1))
                        if (trailer.isBlank()) break
                    }
                    break
                }
                total += copyBounded(reader, out, size.toLong(), captured, captureLimit)
                val crlf = reader.readN(2)
                if (crlf != null) out.write(crlf)
            }
            out.flush()
            return BodyChunk(captured.toByteArray(), total)
        }
        val length = head.contentLength ?: return BodyChunk(null, 0)
        if (length <= 0) return BodyChunk(null, 0)
        total = copyBounded(reader, out, length.toLong(), captured, captureLimit)
        out.flush()
        return BodyChunk(captured.toByteArray(), total)
    }

    private fun copyBounded(
        reader: Buf,
        out: OutputStream,
        length: Long,
        captured: ByteArrayOutputStream,
        captureLimit: Int,
    ): Long {
        val buffer = ByteArray(16 * 1024)
        var remaining = length
        var total = 0L
        while (remaining > 0) {
            val want = minOf(remaining, buffer.size.toLong()).toInt()
            val read = reader.read(buffer, 0, want)
            if (read <= 0) break
            out.write(buffer, 0, read)
            if (captured.size() < captureLimit) {
                captured.write(buffer, 0, minOf(read, captureLimit - captured.size()))
            }
            remaining -= read
            total += read
        }
        out.flush()
        return total
    }

    private fun preview(raw: ByteArray?, head: HttpHead?, limit: Int): String? {
        if (raw == null || raw.isEmpty()) return null
        val encoding = head?.header("Content-Encoding")?.lowercase()?.trim().orEmpty()
        if (encoding.contains("br") || encoding.contains("zstd")) {
            return "<" + encoding + " 压缩，已捕获 " + raw.size + " 字节（未解压）>"
        }
        val decoded = decodeBody(raw, head)
        val take = minOf(decoded.size, limit)
        val slice = decoded.copyOf(take)
        val text = runCatching { String(slice, Charsets.UTF_8) }.getOrNull() ?: return null
        val looksText = head?.header("Content-Type")?.let { type ->
            listOf("text", "json", "xml", "javascript", "urlencoded", "html", "csv").any {
                type.contains(it, ignoreCase = true)
            }
        } ?: false
        if (!looksText) {
            val printable = slice.count { byte ->
                val value = byte.toInt() and 0xFF
                value == 9 || value == 10 || value == 13 || value in 32..126 || value >= 0xC0
            }
            if (slice.isNotEmpty() && printable < slice.size * 9 / 10) {
                return "<二进制 ${decoded.size} 字节>"
            }
        }
        return if (decoded.size > take) "$text\n…（已截断，共 ${decoded.size} 字节）" else text
    }

    private fun decodeBody(raw: ByteArray, head: HttpHead?): ByteArray {
        val encoding = head?.header("Content-Encoding")?.lowercase()?.trim() ?: return raw
        if (encoding.contains("br") || encoding.contains("zstd")) return raw
        return runCatching {
            when {
                encoding.contains("gzip") -> GZIPInputStream(raw.inputStream()).readBytes()
                encoding.contains("deflate") -> InflaterInputStream(raw.inputStream()).readBytes()
                else -> raw
            }
        }.getOrDefault(raw)
    }

    private fun closesConnection(request: HttpHead?, response: HttpHead?): Boolean {
        fun headerOf(name: String): String? = response?.header(name) ?: request?.header(name)
        val connection = headerOf("Connection")?.lowercase() ?: ""
        if (connection.contains("close")) return true
        val version = request?.version ?: "HTTP/1.1"
        return version == "HTTP/1.0" && !connection.contains("keep-alive")
    }

    /** 转发前改写请求头：去掉 br/zstd 这类解不开的压缩声明，服务端才可能回 gzip/deflate。 */
    private fun rewriteRequestHead(head: String): String {
        if (!head.contains("ccept-Encoding", ignoreCase = true)) return head
        return head.split("\r\n").mapIndexed { index, line ->
            if (index == 0 || !line.startsWith("Accept-Encoding:", ignoreCase = true)) {
                line
            } else {
                val kept = line.substringAfter(':').split(',').map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("br", true) && !it.contains("zstd", true) }
                "Accept-Encoding: " + (if (kept.isEmpty()) listOf("gzip", "deflate") else kept).joinToString(", ")
            }
        }.joinToString("\r\n")
    }

    private fun readHead(reader: Buf): ByteArray? {
        val out = ByteArrayOutputStream(1024)
        var state = 0
        while (out.size() < MAX_HEAD_BYTES) {
            val byte = reader.readByte()
            if (byte < 0) return null
            out.write(byte)
            state = when {
                byte == '\r'.code && state == 0 -> 1
                byte == '\n'.code && state == 1 -> 2
                byte == '\r'.code && state == 2 -> 3
                byte == '\n'.code && state == 3 -> return out.toByteArray()
                else -> 0
            }
        }
        return null
    }

    private fun readLine(stream: InputStream, max: Int): String? {
        val out = ByteArrayOutputStream(64)
        while (out.size() <= max) {
            val byte = stream.read()
            if (byte < 0) return if (out.size() == 0) null else String(out.toByteArray(), Charsets.ISO_8859_1)
            if (byte == '\n'.code) return String(out.toByteArray(), Charsets.ISO_8859_1)
            out.write(byte)
        }
        return null
    }

    private fun contextFor(host: String): SSLContext? {
        contexts[host]?.let { return it }
        val context = appContext ?: return null
        val pair = MitmCa.leaf(context, host) ?: return null
        val ssl = SSLContext.getInstance("TLS")
        ssl.init(arrayOf(MitmCa.keyManager(host, pair)), null, SecureRandom())
        contexts[host] = ssl
        if (contexts.size > 300) contexts.clear()
        return ssl
    }

    private fun trustAllContext(): SSLContext {
        clientContext?.let { return it }
        synchronized(this) {
            clientContext?.let { return it }
            val manager = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), SecureRandom()) }
            clientContext = context
            return context
        }
    }

    private fun tlsClient(raw: Socket, host: String, port: Int): SSLSocket {
        val ssl = trustAllContext().socketFactory.createSocket(raw, host, port, true) as SSLSocket
        ssl.useClientMode = true
        val params = ssl.sslParameters
        params.applicationProtocols = arrayOf("http/1.1")
        if (host.isNotEmpty() && !host[0].isDigit()) {
            runCatching { params.serverNames = listOf(SNIHostName(host)) }
        }
        ssl.sslParameters = params
        ssl.soTimeout = SOCKET_TIMEOUT_MS
        ssl.startHandshake()
        return ssl
    }

    private fun tlsServer(client: Socket, context: SSLContext): SSLSocket {
        val ssl = runCatching {
            context.socketFactory.createSocket(client, null, 0, false) as SSLSocket
        }.getOrElse {
            context.socketFactory.createSocket(client, "mitm", 443, false) as SSLSocket
        }
        ssl.useClientMode = false
        val params = ssl.sslParameters
        params.applicationProtocols = arrayOf("http/1.1")
        ssl.sslParameters = params
        ssl.startHandshake()
        return ssl
    }

    /** 带缓冲的读取器：先吃缓冲再读流，保证向上包装 TLS 时不丢已读字节。 */
    private class Buf(private val stream: InputStream) {
        private val buffer = ByteArray(32 * 1024)
        private var position = 0
        private var limit = 0

        fun readByte(): Int {
            if (position >= limit) {
                limit = stream.read(buffer)
                position = 0
                if (limit <= 0) return -1
            }
            return buffer[position++].toInt() and 0xFF
        }

        fun read(dst: ByteArray, offset: Int, length: Int): Int {
            if (position < limit) {
                val count = minOf(length, limit - position)
                System.arraycopy(buffer, position, dst, offset, count)
                position += count
                return count
            }
            return stream.read(dst, offset, length)
        }

        fun readLine(max: Int): String? {
            val out = ByteArrayOutputStream(32)
            while (out.size() <= max) {
                val byte = readByte()
                if (byte < 0) return if (out.size() == 0) null else String(out.toByteArray(), Charsets.ISO_8859_1)
                out.write(byte)
                if (byte == '\n'.code) return String(out.toByteArray(), Charsets.ISO_8859_1)
            }
            return null
        }

        fun readN(count: Int): ByteArray? {
            val out = ByteArray(count)
            var filled = 0
            while (filled < count) {
                val read = read(out, filled, count - filled)
                if (read <= 0) return null
                filled += read
            }
            return out
        }
    }
}
