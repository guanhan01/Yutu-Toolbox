package com.mcp.toolbox.feature.capture

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import com.mcp.toolbox.feature.capture.net.FlowKey
import com.mcp.toolbox.feature.capture.net.ipToBytes
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress

/** 一条连接归属到的应用。 */
data class CaptureApp(val uid: Int, val packageName: String?, val label: String?) {
    val display: String get() = label ?: packageName ?: "uid $uid"
}

/**
 * 把连接归属到具体 App：先试调用方给的钩子（VpnService.getConnectionOwnerUid），
 * 再试 ConnectivityManager.getConnectionOwnerUid，最后回退 /proc/net/tcp{,6} 按本地端口查 uid；
 * 再经 PackageManager 换成包名与应用名。结果按 uid 缓存，图标按包名缓存。
 */
class CaptureAppResolver(
    context: Context,
    private val ownerUidHook: ((FlowKey) -> Int?)? = null,
) {
    private val appContext = context.applicationContext
    private val pm: PackageManager = appContext.packageManager
    private val uidCache = HashMap<Int, CaptureApp?>()

    fun resolve(key: FlowKey): CaptureApp? {
        val uid = ownerUidHook?.invoke(key) ?: connectivityOwnerUid(key) ?: procOwnerUid(key) ?: return null
        if (uid <= 0) return null
        if (uidCache.containsKey(uid)) return uidCache[uid]
        val app = describe(uid)
        uidCache[uid] = app
        return app
    }

    private fun describe(uid: Int): CaptureApp? {
        val pkg = runCatching { pm.getPackagesForUid(uid)?.firstOrNull() }.getOrNull()
        if (pkg == null) return CaptureApp(uid, null, null)
        val label = runCatching {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrNull()
        return CaptureApp(uid, pkg, label)
    }

    private fun connectivityOwnerUid(key: FlowKey): Int? {
        val manager = appContext.getSystemService(ConnectivityManager::class.java) ?: return null
        val method = ConnectivityManager::class.java.methods.firstOrNull {
            it.name == "getConnectionOwnerUid" && it.parameterTypes.size == 3
        } ?: return null
        val local = InetSocketAddress(InetAddress.getByAddress(ipToBytes(key.srcIp)), key.srcPort)
        val remote = InetSocketAddress(InetAddress.getByAddress(ipToBytes(key.dstIp)), key.dstPort)
        val raw = runCatching { method.invoke(manager, key.protocol, local, remote) as? Int }.getOrNull()
        return raw?.takeIf { it > 0 }
    }

    /** /proc/net/tcp 第 8 列是 uid；按本地端口匹配即可。 */
    private fun procOwnerUid(key: FlowKey): Int? {
        for (name in listOf("/proc/net/tcp6", "/proc/net/tcp")) {
            val file = File(name)
            if (!file.canRead()) continue
            val lines = runCatching { file.readLines() }.getOrNull() ?: continue
            for (line in lines.drop(1)) {
                val cols = line.trim().split(Regex("\\s+"))
                val port = cols.getOrNull(1)?.substringAfterLast(':')?.toIntOrNull(16) ?: continue
                if (port != key.srcPort) continue
                val owner = cols.getOrNull(7)?.toIntOrNull() ?: continue
                return owner
            }
        }
        return null
    }
}

/** 列表/详情里显示应用图标用的小缓存。 */
object CaptureAppIcons {
    private val cache = HashMap<String, Bitmap?>()

    fun icon(context: Context, packageName: String?): Bitmap? {
        if (packageName.isNullOrEmpty()) return null
        if (cache.containsKey(packageName)) return cache[packageName]
        val bitmap = runCatching {
            bitmapOf(context.packageManager.getApplicationIcon(packageName))
        }.getOrNull()
        cache[packageName] = bitmap
        return bitmap
    }

    private fun bitmapOf(drawable: Drawable): Bitmap {
        val width = drawable.intrinsicWidth.coerceIn(24, 192)
        val height = drawable.intrinsicHeight.coerceIn(24, 192)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, width, height)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }
}
