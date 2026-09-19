package com.mcp.toolbox.feature.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.mcp.toolbox.feature.capture.net.FlowKey
import com.mcp.toolbox.feature.capture.net.ipToBytes
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * 抓包前台服务：建立 tun、启动用户态转发引擎、维护常驻通知。
 * 全部转发都在应用进程内完成，未使用外部代理或内核模块。
 */
class CaptureVpnService : VpnService() {
    companion object {
        // action 用运行时 packageName（applicationId）拼，stable 与 beta 两个包名才能各自独立
        fun startAction(pkg: String) = "$pkg.capture.START"
        fun stopAction(pkg: String) = "$pkg.capture.STOP"
        fun pauseAction(pkg: String) = "$pkg.capture.PAUSE"
        fun resumeAction(pkg: String) = "$pkg.capture.RESUME"
        const val EXTRA_MODE = "mode"
        const val EXTRA_PACKAGES = "packages"
        const val EXTRA_DNS_ONLY = "dns_only"
        const val EXTRA_BODY_LIMIT = "body_limit"
        private const val CHANNEL_ID = "capture-session"
        private const val NOTIFICATION_ID = 4711
        private const val CAPTURE_ADDRESS = "10.111.222.2"
        private const val FALLBACK_DNS = "1.1.1.1"

        fun start(context: Context, config: CaptureConfig) {
            val intent = Intent(context, CaptureVpnService::class.java).apply {
                action = startAction(context.packageName)
                putExtra(EXTRA_MODE, config.mode.name)
                putExtra(EXTRA_PACKAGES, config.packages.toTypedArray())
                putExtra(EXTRA_DNS_ONLY, config.dnsOnly)
                putExtra(EXTRA_BODY_LIMIT, config.bodyLimitBytes)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, CaptureVpnService::class.java).setAction(stopAction(context.packageName)),
            )
        }

        fun setPaused(context: Context, paused: Boolean) {
            context.startService(
                Intent(context, CaptureVpnService::class.java)
                    .setAction(if (paused) pauseAction(context.packageName) else resumeAction(context.packageName)),
            )
        }
    }

    private var engine: CaptureEngine? = null
    private var descriptor: ParcelFileDescriptor? = null
    private var paused = false
    private var dnsOnly = false
    private val handler = Handler(Looper.getMainLooper())
    private val labelCache = HashMap<String, String?>()
    private val notificationTick = object : Runnable {
        override fun run() {
            if (CaptureStore.state.value.running) {
                updateNotification()
                handler.postDelayed(this, 3000)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            stopAction(packageName) -> {
                shutdown("已停止")
                stopSelf()
                return START_NOT_STICKY
            }
            pauseAction(packageName) -> {
                paused = true
                CaptureStore.session(paused = true, statusText = "已暂停记录（转发继续）")
                updateNotification()
                return START_STICKY
            }
            resumeAction(packageName) -> {
                paused = false
                CaptureStore.session(paused = false, statusText = "抓包运行中")
                updateNotification()
                return START_STICKY
            }
            else -> {
                val config = CaptureConfig(
                    mode = runCatching {
                        CaptureMode.valueOf(intent?.getStringExtra(EXTRA_MODE) ?: CaptureMode.ALL.name)
                    }.getOrDefault(CaptureMode.ALL),
                    packages = intent?.getStringArrayExtra(EXTRA_PACKAGES)?.toSet() ?: emptySet(),
                    dnsOnly = intent?.getBooleanExtra(EXTRA_DNS_ONLY, false) ?: false,
                    bodyLimitBytes = intent?.getIntExtra(EXTRA_BODY_LIMIT, 64 * 1024) ?: 64 * 1024,
                )
                startCapture(config)
            }
        }
        return START_STICKY
    }

    override fun onRevoke() {
        shutdown("VPN 授权已被系统或用户撤销")
        super.onRevoke()
    }

    override fun onDestroy() {
        if (engine != null) shutdown("服务已销毁")
        super.onDestroy()
    }

    private fun startCapture(config: CaptureConfig) {
        if (engine != null) {
            CaptureStore.session(statusText = "抓包已在运行")
            return
        }
        paused = false
        dnsOnly = config.dnsOnly
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        CaptureStore.resetSessionStats()
        CaptureStore.session(
            running = true,
            paused = false,
            dnsOnly = config.dnsOnly,
            mode = config.mode,
            packages = config.packages,
            statusText = "正在建立 tun…",
            error = null,
            captureIp = CAPTURE_ADDRESS,
            foreground = true,
        )
        val builder = Builder()
            .setSession("MCP Toolbox 抓包")
            .setMtu(1500)
            .addAddress(CAPTURE_ADDRESS, 32)
            .setBlocking(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { builder.setMetered(false) }
        }
        val dnsServers = dnsServersOf()
        if (config.dnsOnly) {
            val routes = dnsServers.mapNotNull { com.mcp.toolbox.feature.capture.net.ipFromText(it) }
            if (routes.isEmpty()) {
                CaptureStore.session(
                    running = false,
                    statusText = "无法开始仅 DNS 观察",
                    error = "当前网络没有可用的 DNS 服务器地址，无法只接管 DNS 流量",
                )
                stopForegroundNow()
                stopSelf()
                return
            }
            routes.forEach { address -> builder.addRoute(com.mcp.toolbox.feature.capture.net.ipToText(address), 32) }
        } else {
            builder.addRoute("0.0.0.0", 0)
            dnsServers.take(2).ifEmpty { listOf(FALLBACK_DNS) }.forEach {
                runCatching { builder.addDnsServer(it) }
            }
        }
        when (config.mode) {
            CaptureMode.ONLY_SELECTED ->
                config.packages.forEach { pkg -> runCatching { builder.addAllowedApplication(pkg) } }
            CaptureMode.EXCLUDE_SELECTED ->
                config.packages.forEach { pkg -> runCatching { builder.addDisallowedApplication(pkg) } }
            CaptureMode.ALL -> Unit
        }
        val pfd = runCatching { builder.establish() }.getOrNull()
        if (pfd == null) {
            CaptureStore.session(
                running = false,
                statusText = "tun 建立失败",
                error = "无法建立 VPN 接口：请确认已授权 VPN，且没有其它 VPN 正在运行",
            )
            stopForegroundNow()
            stopSelf()
            return
        }
        descriptor = pfd
        // 溢出菜单里的开关只落盘，这里以持久化为准：intent 重建 config 时不会带新字段
        CaptureFlags.httpsDecrypt = config.httpsDecrypt || CapturePrefs.load(this).httpsDecrypt
        val newEngine = CaptureEngine(config, ::protectTcpSocket, ::protectUdpSocket) {
            MitmProxy.ensureStarted(applicationContext, ::protectTcpSocket, config.bodyLimitBytes)
        }
        newEngine.appResolver = CaptureAppResolver(this, ::connectionOwnerUid)::resolve
        newEngine.attach(
            FileInputStream(pfd.fileDescriptor),
            FileOutputStream(pfd.fileDescriptor),
            CAPTURE_ADDRESS,
        )
        engine = newEngine
        newEngine.start()
        CaptureStore.session(
            statusText = if (config.dnsOnly) "仅 DNS 观察中（不影响其它流量）" else "抓包运行中",
        )
        updateNotification()
        handler.removeCallbacks(notificationTick)
        handler.postDelayed(notificationTick, 3000)
    }

    private fun protectTcpSocket(socket: java.net.Socket): Boolean =
        runCatching { protect(socket) }.getOrDefault(false)

    private fun protectUdpSocket(socket: java.net.DatagramSocket): Boolean =
        runCatching { protect(socket) }.getOrDefault(false)

    private fun shutdown(status: String) {
        handler.removeCallbacks(notificationTick)
        runCatching { engine?.stop()
        MitmProxy.stop() }
        engine = null
        runCatching { descriptor?.close() }
        descriptor = null
        CaptureStore.session(running = false, paused = false, statusText = status, foreground = false)
        stopForegroundNow()
    }

    private fun stopForegroundNow() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
    }

    private fun dnsServersOf(): List<String> {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        val network = manager.activeNetwork ?: return emptyList()
        val properties = manager.getLinkProperties(network) ?: return emptyList()
        return properties.dnsServers.mapNotNull { it.hostAddress }
    }

    /** 通过 VpnService.getConnectionOwnerUid（API 29+）把连接归属到具体 App。 */
    private fun resolveApp(key: FlowKey): Pair<String?, String?> {
        val uid = runCatching { connectionOwnerUid(key) }.getOrNull() ?: return null to null
        if (uid <= 0) return null to null
        val packages = runCatching { packageManager.getPackagesForUid(uid) }.getOrNull() ?: return null to null
        val pkg = packages.firstOrNull() ?: return null to null
        val label = if (labelCache.containsKey(pkg)) {
            labelCache[pkg]
        } else {
            runCatching {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
            }.getOrNull().also { labelCache[pkg] = it }
        }
        return pkg to label
    }

    private fun connectionOwnerUid(key: FlowKey): Int? {
        val method = VpnService::class.java.methods.firstOrNull {
            it.name == "getConnectionOwnerUid" && it.parameterTypes.size == 3
        } ?: return null
        val local = InetSocketAddress(InetAddress.getByAddress(ipToBytes(key.srcIp)), key.srcPort)
        val remote = InetSocketAddress(InetAddress.getByAddress(ipToBytes(key.dstIp)), key.dstPort)
        val result = method.invoke(null, key.protocol, local, remote) as? Int ?: return null
        return if (result <= 0) null else result
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(CHANNEL_ID, "抓包会话", NotificationManager.IMPORTANCE_LOW)
            channel.description = "显示抓包运行状态与实时流量"
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val state = CaptureStore.state.value
        val text = if (paused) "已暂停记录，仍在中继流量" else "运行中 · ${state.records.size} 条记录"
        val detail = "↑ ${formatBytes(state.stats.upBytes)} · ↓ ${formatBytes(state.stats.downBytes)}"
        val pauseIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, CaptureVpnService::class.java)
                .setAction(if (paused) resumeAction(packageName) else pauseAction(packageName)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, CaptureVpnService::class.java).setAction(stopAction(packageName)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val openIntent = PendingIntent.getActivity(
            this,
            3,
            Intent().setClassName(packageName, "com.mcp.toolbox.MainActivity"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("MCP Toolbox 抓包")
            .setContentText(text)
            .setSubText(detail)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(openIntent)
            .addAction(0, if (paused) "继续" else "暂停", pauseIntent)
            .addAction(0, "停止", stopIntent)
            .build()
    }

    private fun updateNotification() {
        runCatching {
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun formatBytes(value: Long): String = when {
        value < 1024 -> "$value B"
        value < 1024 * 1024 -> String.format("%.1f KB", value / 1024.0)
        else -> String.format("%.2f MB", value / (1024.0 * 1024.0))
    }
}
