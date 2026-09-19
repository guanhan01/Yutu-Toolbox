package com.mcp.toolbox.feature.mcp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * 内置 MCP Server 的前台服务：让服务端在应用退到后台后继续监听，
 * 通知栏常驻显示端点、工具数与请求量。
 */
class McpServerService : Service() {

    private lateinit var notifications: NotificationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifications = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "MCP 内置 Server", NotificationManager.IMPORTANCE_LOW).apply {
                description = "内置 MCP Server 运行状态与请求计数"
                setShowBadge(false)
            }
            notifications.createNotificationChannel(channel)
        }
        BuiltInMcpServer.load(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == stopAction(packageName)) {
            BuiltInMcpServer.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        BuiltInMcpServer.load(this)
        if (!BuiltInMcpServer.running.value) BuiltInMcpServer.start(this)
        startForeground(NOTIFICATION_ID, buildNotification())
        refreshLater()
        return START_STICKY
    }

    private fun refreshLater() {
        Thread {
            repeat(30) {
                Thread.sleep(20000)
                if (!BuiltInMcpServer.running.value) return@Thread
                runCatching { notifications.notify(NOTIFICATION_ID, buildNotification()) }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun buildNotification(): Notification {
        val cfg = BuiltInMcpServer.config.value
        val endpoint = if (cfg.localOnly) {
            BuiltInMcpServer.httpEndpoint
        } else {
            "http://${BuiltInMcpServer.lanAddress() ?: "0.0.0.0"}:${cfg.port}/mcp"
        }
        val content = "端点 $endpoint · 工具 ${BuiltInMcpServer.tools().size} 个 · 请求 ${BuiltInMcpServer.requestCount.value}"
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("内置 MCP Server 运行中")
            .setContentText(content)
            .setSmallIcon(applicationInfo.icon)
            .setOngoing(true)
            .setShowWhen(false)
        packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            builder.setContentIntent(
                PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        }
        val stopIntent = Intent(this, McpServerService::class.java).setAction(stopAction(packageName))
        builder.addAction(
            Notification.Action.Builder(
                null,
                "停止",
                PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            ).build(),
        )
        return builder.build()
    }

    companion object {
        fun startAction(pkg: String) = "$pkg.action.MCP_SERVER_START"
        fun stopAction(pkg: String) = "$pkg.action.MCP_SERVER_STOP"
        private const val CHANNEL_ID = "mcp-server"
        private const val NOTIFICATION_ID = 4713

        fun start(context: Context) {
            val intent = Intent(context, McpServerService::class.java).setAction(startAction(context.packageName))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, McpServerService::class.java).setAction(stopAction(context.packageName)))
        }
    }
}
