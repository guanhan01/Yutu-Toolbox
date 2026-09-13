package com.mcp.toolbox.feature.capture

import com.mcp.toolbox.feature.capture.net.DnsInfo
import com.mcp.toolbox.feature.capture.net.DnsMessageParser
import com.mcp.toolbox.feature.capture.net.FlowKey
import com.mcp.toolbox.feature.capture.net.HttpHead
import com.mcp.toolbox.feature.capture.net.HttpParser
import com.mcp.toolbox.feature.capture.net.IpProto
import com.mcp.toolbox.feature.capture.net.Ipv4Packet
import com.mcp.toolbox.feature.capture.net.TCP_ACK
import com.mcp.toolbox.feature.capture.net.TCP_FIN
import com.mcp.toolbox.feature.capture.net.TCP_RST
import com.mcp.toolbox.feature.capture.net.TCP_PSH
import com.mcp.toolbox.feature.capture.net.TCP_SYN
import com.mcp.toolbox.feature.capture.net.TlsParser
import com.mcp.toolbox.feature.capture.net.buildIpv4Tcp
import com.mcp.toolbox.feature.capture.net.buildIpv4Udp
import com.mcp.toolbox.feature.capture.net.ipToText
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

private val MITM_PORTS = setOf(443, 8443, 9443)

/** 服务端先发数据的协议：不做“等 ClientHello”的嗅探，否则连接会被卡住。 */
private val MITM_DENY_PORTS = setOf(21, 22, 23, 25, 110, 143, 465, 587, 993, 995, 3306, 5432, 6379, 11211, 27017)

/** 解密模式下值得嗅探 TLS 的端口：常见 HTTPS 端口一律嗅探，其余靠首字节 0x16 判断。 */
private fun mitmEligible(port: Int): Boolean = port in MITM_PORTS || port !in MITM_DENY_PORTS

/**
 * 用户态 IPv4 转发与解析引擎：
 * 读 tun → 中继到真实网络（TCP 用户态中继、UDP 直通）→ 回包写回 tun，
 * 同时把可读信息（TLS SNI、明文 HTTP、DNS）落成抓包记录。
 */
class CaptureEngine(
    private val config: CaptureConfig,
    private val protectTcp: (Socket) -> Boolean,
    private val protectUdp: (DatagramSocket) -> Boolean,
    private val mitmPortProvider: () -> Int = { 0 },
) {
    private class Sink(val capacity: Int) {
        val data = ByteArray(capacity)
        var size = 0

        fun append(src: ByteArray, offset: Int, len: Int) {
            if (size >= capacity || len <= 0) return
            val n = minOf(capacity - size, len)
            System.arraycopy(src, offset, data, size, n)
            size += n
        }

        fun text(): String = String(data, 0, size, Charsets.ISO_8859_1)

        fun bytes(): ByteArray = data.copyOf(size)
    }

    private class Flow(val key: FlowKey, val recordId: Long, val startedAt: Long, bodyLimit: Int) {
        var channel: SocketChannel? = null
        var udp: DatagramChannel? = null
        var expectedSeq = 0L
        var sendSeq = 0L
        var deviceMss = 1380
        var synAckSent = false
        var connectStarted = false
        var connected = false
        var finFromDevice = false
        var finSent = false
        var closing = false
        val pending = ByteBuffer.allocate(256 * 1024)
        var pendingActive = false
        val requestSink = Sink(bodyLimit)
        val responseSink = Sink(bodyLimit)
        var requestBytes = 0L
        var responseBytes = 0L
        var connectedAt: Long? = null
        var requestAt: Long? = null
        var responseAt: Long? = null
        var lastActive = System.currentTimeMillis()
        var host: String? = null
        var sni: String? = null
        var tlsVersion: String? = null
        var alpn: String? = null
        var kind = CaptureKind.TCP
        var method: String? = null
        var path: String? = null
        var status: Int? = null
        var reason: String? = null
        var requestHeadLength = -1
        var responseHeadLength = -1
        var requestHeaders: List<Pair<String, String>> = emptyList()
        var responseHeaders: List<Pair<String, String>> = emptyList()
        var dnsParsed = false
        var mitm = false
        var mitmHost: String? = null
        var preamble: ByteArray? = null
        var appPackage: String? = null
        var appLabel: String? = null
    }

    private val flows = ConcurrentHashMap<FlowKey, Flow>()
    private val selector: Selector = Selector.open()
    private val dnsQueries = ConcurrentHashMap<Int, String>()
    private val running = AtomicBoolean(false)
    private val ipIdSeq = AtomicInteger(1)
    private val writeLock = Any()
    private val readBuffer = ByteArray(65535)
    private val outBuffer = ByteArray(65535)
    private val packet = Ipv4Packet(readBuffer)
    var appResolver: ((FlowKey) -> CaptureApp?)? = null
    private var tunIn: FileInputStream? = null
    private var tunOut: FileOutputStream? = null
    private var localIp = 0
    private var readerThread: Thread? = null
    private var relayThread: Thread? = null
    private val relayReadBuffer = ByteArray(32 * 1024)

    fun attach(input: FileInputStream, output: FileOutputStream, localAddress: String) {
        tunIn = input
        tunOut = output
        localIp = com.mcp.toolbox.feature.capture.net.ipFromText(localAddress) ?: 0
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        relayThread = Thread({ relayLoop() }, "capture-relay").apply { isDaemon = true; start() }
        readerThread = Thread({ readLoop() }, "capture-tun").apply { isDaemon = true; start() }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        selector.wakeup()
        flows.values.toList().forEach { finalizeFlow(it, FlowState.CLOSED, null) }
        flows.clear()
        // 兜底：flows 里已淘汰、但仍挂在列表上显示「进行中」的记录，一并收尾
        CaptureStore.state.value.records.forEach { record ->
            if (record.endedAt == null) {
                CaptureStore.updateRecord(record.id) { it.copy(state = FlowState.CLOSED, endedAt = System.currentTimeMillis()) }
            }
        }
        readerThread?.join(400)
        relayThread?.join(400)
    }

    private fun readLoop() {
        val input = tunIn ?: return
        try {
            while (running.get()) {
                val len = input.read(readBuffer)
                if (len <= 0) continue
                if (!packet.parse(len)) {
                    CaptureStore.addDropped()
                    continue
                }
                when (packet.protocol) {
                    IpProto.TCP -> handleTcp(packet)
                    IpProto.UDP -> handleUdp(packet)
                    else -> CaptureStore.addDropped()
                }
            }
        } catch (t: Throwable) {
            if (running.get()) {
                CaptureStore.session(statusText = "tun 读取已中断：${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    private inline fun sendPacket(build: (ByteArray) -> Int) {
        val out = tunOut ?: return
        synchronized(writeLock) {
            val len = build(outBuffer)
            if (len <= 0) return
            try {
                out.write(outBuffer, 0, len)
                out.flush()
            } catch (_: Throwable) {
            }
        }
    }

    private fun handleTcp(pkt: Ipv4Packet) {
        val key = FlowKey(pkt.srcIp, pkt.srcPort, pkt.dstIp, pkt.dstPort, IpProto.TCP)
        val existing = flows[key]
        if (existing == null) {
            if (pkt.isSyn && !pkt.isAck) onSyn(key, pkt) else sendRst(key, pkt)
            return
        }
        onSegment(existing, pkt)
    }

    private fun onSyn(key: FlowKey, pkt: Ipv4Packet) {
        val now = System.currentTimeMillis()
        val app = appResolver?.invoke(key)
        val recordId = CaptureStore.openRecord(
            CaptureRecord(
                id = 0,
                kind = CaptureKind.TCP,
                protocol = "TCP",
                srcIp = ipToText(key.srcIp),
                srcPort = key.srcPort,
                dstIp = ipToText(key.dstIp),
                dstPort = key.dstPort,
                host = CaptureStore.resolveHost(ipToText(key.dstIp)),
                startedAt = now,
                requestAt = now,
                state = FlowState.CONNECTING,
                appPackage = app?.packageName,
                appLabel = app?.label,
                uid = app?.uid ?: -1,
            ),
        )
        val flow = Flow(key, recordId, now, config.bodyLimitBytes)
        flow.expectedSeq = (pkt.seq + 1) and 0xFFFFFFFFL
        flow.sendSeq = (Random.nextInt().toLong() and 0x7FFFFFFFL) + 1
        flow.deviceMss = if (pkt.mss in 256..9000) pkt.mss else 1380
        flow.appPackage = app?.packageName
        flow.appLabel = app?.label
        flows[key] = flow
        sendTcp(flow, TCP_SYN or TCP_ACK, null, 0, 0)
        flow.synAckSent = true
        if (CaptureFlags.httpsDecrypt && mitmEligible(key.dstPort) &&
            !CaptureFlags.skipDecrypt(app?.packageName, app?.uid ?: -1)
        ) {
            // 解密模式：先等客户端 ClientHello 拿到 SNI，再决定上游怎么连
            flow.mitm = true
            flow.kind = CaptureKind.HTTPS
        } else {
            startConnect(flow)
        }
    }

    private fun onSegment(flow: Flow, pkt: Ipv4Packet) {
        flow.lastActive = System.currentTimeMillis()
        if (pkt.isRst) {
            finalizeFlow(flow, FlowState.FAILED, "连接被重置")
            return
        }
        if (pkt.payloadLength > 0) {
            if (pkt.seq != flow.expectedSeq) {
                CaptureStore.addDropped()
                return
            }
            flow.requestSink.append(readBuffer, pkt.payloadOffset, pkt.payloadLength)
            flow.requestBytes += pkt.payloadLength
            flow.expectedSeq = (flow.expectedSeq + pkt.payloadLength) and 0xFFFFFFFFL
            queueToNetwork(flow, pkt)
            inspectRequest(flow)
            maybeStartMitm(flow)
            sendAck(flow)
            // 进行中的连接也要实时刷新计数与已解析的 L7 信息
            CaptureStore.updateRecord(flow.recordId) {
                it.copy(
                    kind = flow.kind,
                    host = flow.host ?: it.host,
                    sni = flow.sni ?: it.sni,
                    tlsVersion = flow.tlsVersion ?: it.tlsVersion,
                    alpn = flow.alpn ?: it.alpn,
                    method = flow.method ?: it.method,
                    path = flow.path ?: it.path,
                    requestBytes = flow.requestBytes,
                    responseBytes = flow.responseBytes,
                    requestAt = flow.requestAt ?: it.requestAt,
                )
            }
        }
        if (pkt.isFin) {
            if (((pkt.seq + pkt.payloadLength) and 0xFFFFFFFFL) == flow.expectedSeq) {
                flow.expectedSeq = (flow.expectedSeq + 1) and 0xFFFFFFFFL
                flow.finFromDevice = true
                flow.channel?.let { runCatching { it.shutdownOutput() } }
                sendAck(flow)
            } else {
                CaptureStore.addDropped()
            }
        }
        if (flow.finSent && pkt.isAck && pkt.payloadLength == 0 && pkt.ack == flow.sendSeq) {
            finalizeFlow(flow, FlowState.CLOSED, null)
        }
    }

    /** 解密模式：等到 ClientHello 之后再建上游连接，拿不到 SNI 就退回直连。 */
    private fun maybeStartMitm(flow: Flow) {
        if (!flow.mitm || flow.connectStarted) return
        val sink = flow.requestSink
        if (sink.size < 6) return
        if (sink.data[0] != 0x16.toByte()) {
            flow.mitm = false
            startConnect(flow)
            return
        }
        startMitmConnect(flow, flow.sni ?: ipToText(flow.key.dstIp))
    }

    private fun startMitmConnect(flow: Flow, host: String) {
        if (flow.connectStarted) return
        flow.connectStarted = true
        val proxyPort = runCatching { mitmPortProvider() }.getOrDefault(0)
        if (proxyPort <= 0) {
            flow.mitm = false
            flow.connectStarted = false
            startConnect(flow)
            return
        }
        try {
            val channel = SocketChannel.open()
            channel.configureBlocking(false)
            runCatching { channel.socket().tcpNoDelay = true }
            protectTcp(channel.socket())
            val immediatelyConnected = channel.connect(
                InetSocketAddress(InetAddress.getLoopbackAddress(), proxyPort),
            )
            flow.channel = channel
            flow.mitmHost = host
            flow.host = host
            flow.kind = CaptureKind.HTTPS
            flow.preamble = ("$host ${ipToText(flow.key.dstIp)} ${flow.key.dstPort} ${flow.recordId}\n")
                .toByteArray(Charsets.US_ASCII)
            if (immediatelyConnected) {
                flow.connected = true
                flow.connectedAt = System.currentTimeMillis()
                writePreamble(flow)
            }
            channel.register(
                selector,
                if (immediatelyConnected) SelectionKey.OP_READ else SelectionKey.OP_CONNECT,
                flow,
            )
            selector.wakeup()
            if (immediatelyConnected) {
                markConnected(flow)
                flushPendingInterest(flow)
            }
        } catch (t: Throwable) {
            failFlow(flow, "解密通道建立失败：${t.message ?: t.javaClass.simpleName}")
        }
    }

    /** 前导行必须排在客户端字节之前，只有 relayLoop 会写这个 channel。 */
    private fun writePreamble(flow: Flow) {
        val bytes = flow.preamble ?: return
        val channel = flow.channel ?: return
        flow.preamble = null
        val buffer = ByteBuffer.wrap(bytes)
        var guard = 0
        while (buffer.hasRemaining() && guard++ < 64) {
            val written = runCatching { channel.write(buffer) }.getOrDefault(-1)
            if (written <= 0) break
        }
    }

    /** 建连前缓冲下来的客户端字节（ClientHello）要立刻触发一次写。 */
    private fun flushPendingInterest(flow: Flow) {
        val channel = flow.channel ?: return
        val hasPending = synchronized(flow.pending) { flow.pending.position() > 0 }
        if (!hasPending) return
        val key = channel.keyFor(selector) ?: return
        if (!key.isValid) return
        key.interestOps(key.interestOps() or SelectionKey.OP_WRITE)
        selector.wakeup()
    }

    private fun startConnect(flow: Flow) {
        if (flow.connectStarted) return
        flow.connectStarted = true
        try {
            val channel = SocketChannel.open()
            channel.configureBlocking(false)
            runCatching { channel.socket().tcpNoDelay = true }
            protectTcp(channel.socket())
            val address = com.mcp.toolbox.feature.capture.net.ipToBytes(flow.key.dstIp)
            val target = InetSocketAddress(InetAddress.getByAddress(address), flow.key.dstPort)
            val immediatelyConnected = channel.connect(target)
            flow.channel = channel
            if (immediatelyConnected) {
                flow.connected = true
                flow.connectedAt = System.currentTimeMillis()
            }
            channel.register(
                selector,
                if (immediatelyConnected) SelectionKey.OP_READ else SelectionKey.OP_CONNECT,
                flow,
            )
            selector.wakeup()
            if (immediatelyConnected) {
                markConnected(flow)
                flushPendingInterest(flow)
            }
        } catch (t: Throwable) {
            failFlow(flow, "建立连接失败：${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun markConnected(flow: Flow) {
        val at = flow.connectedAt ?: System.currentTimeMillis()
        CaptureStore.updateRecord(flow.recordId) {
            it.copy(state = FlowState.OPEN, connectedAt = at)
        }
    }

    private fun sendAck(flow: Flow) {
        sendTcp(flow, TCP_ACK, null, 0, 0)
    }

    private fun sendTcp(flow: Flow, flags: Int, payload: ByteArray?, offset: Int, length: Int) {
        val srcIp = flow.key.dstIp
        val dstIp = flow.key.srcIp
        val srcPort = flow.key.dstPort
        val dstPort = flow.key.srcPort
        val seq = flow.sendSeq
        val ack = flow.expectedSeq
        val id = ipIdSeq.getAndIncrement() and 0xFFFF
        sendPacket { buf ->
            buildIpv4Tcp(
                buf, srcIp, dstIp, srcPort, dstPort, seq, ack, flags, 65535,
                payload, offset, length, id, 64, 1400,
            )
        }
        var advance = length.toLong()
        if (flags and (TCP_SYN or TCP_FIN) != 0) advance += 1
        if (advance > 0) flow.sendSeq = (flow.sendSeq + advance) and 0xFFFFFFFFL
    }

    private fun sendRst(key: FlowKey, pkt: Ipv4Packet) {
        val id = ipIdSeq.getAndIncrement() and 0xFFFF
        val ack = (pkt.seq + pkt.payloadLength + if (pkt.isSyn) 1L else 0L) and 0xFFFFFFFFL
        val seq = pkt.ack
        sendPacket { buf ->
            buildIpv4Tcp(
                buf, key.dstIp, key.srcIp, key.dstPort, key.srcPort, seq, ack,
                TCP_RST or TCP_ACK, 0, null, 0, 0, id,
            )
        }
    }

    private fun sendRstFromFlow(flow: Flow) {
        val id = ipIdSeq.getAndIncrement() and 0xFFFF
        val seq = flow.sendSeq
        val ack = flow.expectedSeq
        sendPacket { buf ->
            buildIpv4Tcp(
                buf, flow.key.dstIp, flow.key.srcIp, flow.key.dstPort, flow.key.srcPort,
                seq, ack, TCP_RST or TCP_ACK, 0, null, 0, 0, id,
            )
        }
    }

    private fun failFlow(flow: Flow, message: String) {
        flow.channel?.let { runCatching { it.close() } }
        sendRstFromFlow(flow)
        finalizeFlow(flow, FlowState.FAILED, message)
    }

    private fun queueToNetwork(flow: Flow, pkt: Ipv4Packet) {
        val channel = flow.channel
        synchronized(flow.pending) {
            val buf = flow.pending
            if (buf.capacity() - buf.position() < pkt.payloadLength) {
                CaptureStore.addDropped()
                return
            }
            // 连接尚未建立（解密模式在等 ClientHello）时先缓冲，建连后按序发出
            buf.put(readBuffer, pkt.payloadOffset, pkt.payloadLength)
        }
        if (channel == null) return
        val key = channel.keyFor(selector)
        if (key != null && key.isValid) {
            key.interestOps(key.interestOps() or SelectionKey.OP_WRITE)
            selector.wakeup()
        }
    }

    private fun relayLoop() {
        while (running.get()) {
            try {
                val count = selector.select(400)
                if (count > 0) {
                    val iterator = selector.selectedKeys().iterator()
                    while (iterator.hasNext()) {
                        val key = iterator.next()
                        iterator.remove()
                        val flow = key.attachment() as? Flow ?: continue
                        if (!key.isValid) continue
                        if (key.isConnectable && flow.key.protocol == IpProto.TCP) handleConnectable(key, flow)
                        if (key.isValid && key.isReadable) {
                            if (flow.key.protocol == IpProto.TCP) handleTcpReadable(flow) else handleUdpReadable(flow)
                        }
                        if (key.isValid && key.isWritable && flow.key.protocol == IpProto.TCP) handleWritable(key, flow)
                    }
                }
            } catch (t: Throwable) {
                if (running.get()) CaptureStore.addDropped()
            }
            sweepIdle()
        }
    }

    private fun sweepIdle() {
        val now = System.currentTimeMillis()
        flows.values.toList().forEach { flow ->
            val timeout = if (flow.key.protocol == IpProto.TCP) 120_000L else 20_000L
            if (now - flow.lastActive <= timeout) return@forEach
            if (flow.key.protocol == IpProto.TCP && !flow.finSent) sendRstFromFlow(flow)
            finalizeFlow(
                flow,
                if (flow.connected) FlowState.CLOSED else FlowState.FAILED,
                if (flow.connected) null else "超时未完成握手",
            )
        }
    }

    private fun handleConnectable(key: SelectionKey, flow: Flow) {
        val channel = flow.channel ?: return
        try {
            if (channel.finishConnect()) {
                flow.connected = true
                flow.connectedAt = System.currentTimeMillis()
                key.interestOps(SelectionKey.OP_READ)
                markConnected(flow)
                writePreamble(flow)
                flushPendingInterest(flow)
            }
        } catch (t: Throwable) {
            failFlow(flow, "连接失败：${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun handleTcpReadable(flow: Flow) {
        val channel = flow.channel ?: return
        try {
            while (true) {
                val read = channel.read(ByteBuffer.wrap(relayReadBuffer))
                if (read <= 0) {
                    if (read < 0) handleRemoteEof(flow)
                    break
                }
                flow.lastActive = System.currentTimeMillis()
                sendRemoteData(flow, relayReadBuffer, read)
            }
        } catch (t: Throwable) {
            failFlow(flow, "读取响应失败：${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun handleWritable(key: SelectionKey, flow: Flow) {
        val channel = flow.channel ?: return
        synchronized(flow.pending) {
            val buf = flow.pending
            buf.flip()
            try {
                channel.write(buf)
            } catch (t: Throwable) {
                failFlow(flow, "发送请求失败：${t.message ?: t.javaClass.simpleName}")
                return
            }
            if (buf.hasRemaining()) {
                buf.compact()
            } else {
                buf.clear()
                runCatching { key.interestOps(key.interestOps() and SelectionKey.OP_WRITE.inv()) }
            }
        }
    }

    private fun sendRemoteData(flow: Flow, data: ByteArray, length: Int) {
        if (flow.responseAt == null) flow.responseAt = System.currentTimeMillis()
        flow.responseSink.append(data, 0, length)
        flow.responseBytes += length
        val mss = flow.deviceMss.coerceIn(512, 1400)
        var offset = 0
        var remaining = length
        while (remaining > 0) {
            val chunk = minOf(remaining, mss)
            sendTcp(flow, TCP_ACK or TCP_PSH, data, offset, chunk)
            offset += chunk
            remaining -= chunk
        }
        CaptureStore.addTraffic(0, length.toLong())
        inspectResponse(flow)
        CaptureStore.updateRecord(flow.recordId) {
            it.copy(
                responseBytes = flow.responseBytes,
                responseAt = flow.responseAt ?: it.responseAt,
                kind = flow.kind,
                host = flow.host ?: it.host,
                sni = flow.sni ?: it.sni,
                tlsVersion = flow.tlsVersion ?: it.tlsVersion,
                alpn = flow.alpn ?: it.alpn,
            )
        }
    }

    private fun handleRemoteEof(flow: Flow) {
        flow.channel?.let { runCatching { it.close() } }
        flow.channel?.keyFor(selector)?.let { runCatching { it.cancel() } }
        if (!flow.finSent) {
            sendTcp(flow, TCP_ACK or TCP_FIN, null, 0, 0)
            flow.finSent = true
        }
        flow.lastActive = System.currentTimeMillis()
        CaptureStore.updateRecord(flow.recordId) {
            it.copy(state = FlowState.CLOSED, endedAt = System.currentTimeMillis())
        }
    }

    private fun handleUdp(pkt: Ipv4Packet) {
        val key = FlowKey(pkt.srcIp, pkt.srcPort, pkt.dstIp, pkt.dstPort, IpProto.UDP)
        val flow = flows[key] ?: createUdpFlow(key) ?: return
        flow.lastActive = System.currentTimeMillis()
        val length = pkt.payloadLength
        try {
            synchronized(flow.pending) {
                val buf = flow.pending
                buf.clear()
                if (length > 0) buf.put(readBuffer, pkt.payloadOffset, length)
                buf.flip()
                flow.udp?.write(buf)
            }
        } catch (t: Throwable) {
            finalizeFlow(flow, FlowState.FAILED, "UDP 转发失败：${t.message ?: t.javaClass.simpleName}")
            return
        }
        if (length > 0) {
            flow.requestSink.append(readBuffer, pkt.payloadOffset, length)
            flow.requestBytes += length
            flow.requestAt = flow.requestAt ?: System.currentTimeMillis()
            inspectRequest(flow)
            CaptureStore.addTraffic(length.toLong(), 0)
        }
        CaptureStore.updateRecord(flow.recordId) {
            it.copy(
                requestBytes = flow.requestBytes,
                requestAt = flow.requestAt ?: it.requestAt,
                host = flow.host ?: it.host,
                kind = flow.kind,
            )
        }
    }

    private fun createUdpFlow(key: FlowKey): Flow? {
        val now = System.currentTimeMillis()
        val app = appResolver?.invoke(key)
        val isDns = key.dstPort == 53
        val recordId = CaptureStore.openRecord(
            CaptureRecord(
                id = 0,
                kind = if (isDns) CaptureKind.DNS else CaptureKind.UDP,
                protocol = "UDP",
                srcIp = ipToText(key.srcIp),
                srcPort = key.srcPort,
                dstIp = ipToText(key.dstIp),
                dstPort = key.dstPort,
                startedAt = now,
                state = FlowState.OPEN,
                connectedAt = now,
                appPackage = app?.packageName,
                appLabel = app?.label,
                uid = app?.uid ?: -1,
            ),
        )
        val flow = Flow(key, recordId, now, config.bodyLimitBytes)
        flow.kind = if (isDns) CaptureKind.DNS else CaptureKind.UDP
        flow.connected = true
        flow.appPackage = app?.packageName
        flow.appLabel = app?.label
        return try {
            val channel = DatagramChannel.open()
            channel.configureBlocking(false)
            protectUdp(channel.socket())
            val address = com.mcp.toolbox.feature.capture.net.ipToBytes(key.dstIp)
            channel.connect(InetSocketAddress(InetAddress.getByAddress(address), key.dstPort))
            channel.register(selector, SelectionKey.OP_READ, flow)
            selector.wakeup()
            flow.udp = channel
            flows[key] = flow
            flow
        } catch (t: Throwable) {
            CaptureStore.updateRecord(recordId) {
                it.copy(
                    state = FlowState.FAILED,
                    error = "UDP 通道创建失败：${t.message ?: t.javaClass.simpleName}",
                    endedAt = System.currentTimeMillis(),
                )
            }
            null
        }
    }

    private fun handleUdpReadable(flow: Flow) {
        val channel = flow.udp ?: return
        val buffer = ByteBuffer.wrap(relayReadBuffer)
        try {
            while (true) {
                buffer.clear()
                val read = channel.read(buffer)
                if (read <= 0) break
                flow.lastActive = System.currentTimeMillis()
                flow.responseSink.append(relayReadBuffer, 0, read)
                flow.responseBytes += read
                flow.responseAt = flow.responseAt ?: System.currentTimeMillis()
                val id = ipIdSeq.getAndIncrement() and 0xFFFF
                val srcIp = flow.key.dstIp
                val dstIp = flow.key.srcIp
                val srcPort = flow.key.dstPort
                val dstPort = flow.key.srcPort
                sendPacket { buf ->
                    buildIpv4Udp(buf, srcIp, dstIp, srcPort, dstPort, relayReadBuffer, 0, read, id)
                }
                CaptureStore.addTraffic(0, read.toLong())
            }
            inspectResponse(flow)
            CaptureStore.updateRecord(flow.recordId) {
                it.copy(
                    responseBytes = flow.responseBytes,
                    responseAt = flow.responseAt ?: it.responseAt,
                    host = flow.host ?: it.host,
                    kind = flow.kind,
                )
            }
        } catch (t: Throwable) {
            finalizeFlow(flow, FlowState.FAILED, "UDP 接收失败：${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun inspectRequest(flow: Flow) {
        val sink = flow.requestSink
        if (sink.size < 4) return
        val data = sink.data
        if (flow.key.protocol == IpProto.UDP) {
            if (flow.key.dstPort != 53) return
            val dns = DnsMessageParser.parse(data, 0, sink.size) ?: return
            flow.kind = CaptureKind.DNS
            val question = dns.question ?: return
            flow.host = question
            if (dns.isQuery) dnsQueries[dns.id] = question
            return
        }
        if (data[0] == 0x16.toByte()) {
            val hello = TlsParser.clientHello(data, 0, sink.size) ?: return
            flow.kind = CaptureKind.HTTPS
            flow.sni = hello.sni
            if (hello.sni != null) flow.host = hello.sni
            flow.tlsVersion = hello.legacyVersion
            flow.alpn = hello.alpn.takeIf { it.isNotEmpty() }?.joinToString(", ")
            return
        }
        val head = HttpParser.parseRequest(data, 0, sink.size) ?: return
        flow.kind = CaptureKind.HTTP
        flow.method = head.method
        flow.path = head.target
        flow.requestHeadLength = head.headerEnd
        flow.requestHeaders = head.headers
        head.header("Host")?.let { flow.host = it.substringBefore(':') }
    }

    private fun inspectResponse(flow: Flow) {
        val sink = flow.responseSink
        if (sink.size < 4) return
        if (flow.kind == CaptureKind.DNS && !flow.dnsParsed) {
            val dns = DnsMessageParser.parse(sink.data, 0, sink.size) ?: return
            flow.dnsParsed = true
            val question = dns.question ?: dnsQueries[dns.id]
            if (question != null) flow.host = question
            dns.answers.forEach { (_, value) ->
                if (value.startsWith("A ") || value.startsWith("AAAA ")) {
                    CaptureStore.addDns(
                        DnsObservation(
                            name = question ?: flow.host ?: "—",
                            ip = value.substringAfter(' ').trim(),
                            qType = dns.qType,
                            at = System.currentTimeMillis(),
                            fromApp = flow.appLabel ?: flow.appPackage,
                        ),
                    )
                }
            }
            return
        }
        if (flow.kind != CaptureKind.HTTP || flow.responseHeadLength >= 0) return
        val head = HttpParser.parseResponse(sink.data, 0, sink.size) ?: return
        flow.status = head.statusCode
        flow.reason = head.reason
        flow.responseHeadLength = head.headerEnd
        flow.responseHeaders = head.headers
    }

    private fun finalizeFlow(flow: Flow, state: FlowState, error: String?) {
        if (!flows.remove(flow.key, flow)) return
        flow.closing = true
        // 解密成功的连接会拆成真实 HTTP 记录，占位记录不再展示
        if (flow.mitm) CaptureStore.removeRecord(flow.recordId)
        runCatching { flow.channel?.keyFor(selector)?.cancel() }
        runCatching { flow.udp?.keyFor(selector)?.cancel() }
        runCatching { flow.channel?.close() }
        runCatching { flow.udp?.close() }
        inspectRequest(flow)
        inspectResponse(flow)
        val endedAt = System.currentTimeMillis()
        val requestBody = bodyText(flow.requestSink, flow.requestHeadLength)
        val responseBody = bodyText(flow.responseSink, flow.responseHeadLength)
        CaptureStore.updateRecord(flow.recordId) { current ->
            current.copy(
                kind = flow.kind,
                host = flow.host ?: current.host,
                sni = flow.sni,
                tlsVersion = flow.tlsVersion,
                alpn = flow.alpn,
                method = flow.method,
                path = flow.path,
                status = flow.status,
                reason = flow.reason,
                scheme = when (flow.kind) {
                    CaptureKind.HTTPS -> "https"
                    CaptureKind.HTTP -> "http"
                    else -> ""
                },
                requestHeaders = flow.requestHeaders,
                responseHeaders = flow.responseHeaders,
                requestHead = headText(flow.requestSink, flow.requestHeadLength),
                responseHead = headText(flow.responseSink, flow.responseHeadLength),
                requestBody = requestBody,
                responseBody = responseBody,
                requestBytes = flow.requestBytes,
                responseBytes = flow.responseBytes,
                connectedAt = flow.connectedAt,
                requestAt = flow.requestAt,
                responseAt = flow.responseAt,
                endedAt = endedAt,
                state = state,
                error = error,
            )
        }
    }

    private fun headText(sink: Sink, headLength: Int): String? {
        if (headLength <= 0 || sink.size == 0) return null
        return String(sink.data, 0, minOf(headLength, sink.size), Charsets.ISO_8859_1)
    }

    private fun bodyText(sink: Sink, headLength: Int): String? {
        if (headLength <= 0 || sink.size <= headLength) return null
        val length = sink.size - headLength
        val data = sink.data
        val limit = minOf(length, 4096)
        var printable = 0
        for (i in 0 until limit) {
            val c = data[headLength + i].toInt() and 0xFF
            if (c == 9 || c == 10 || c == 13 || c in 32..126 || c >= 0xC0) printable++
        }
        if (limit > 0 && printable < limit * 9 / 10) return null
        return String(data, headLength, length, Charsets.UTF_8)
    }
}
