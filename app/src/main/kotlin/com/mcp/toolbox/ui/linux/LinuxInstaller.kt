package com.mcp.toolbox.ui.linux

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * 下载与安装 Linux 环境。
 *
 * 全部走国内镜像，界面上不展示任何下载地址。
 */
object LinuxInstaller {

    /**
     * 各发行版的镜像地址。
     *
     * 用国内镜像的原因：官方源在当前网络下经常连不上或极慢。
     * 这些地址只在代码里维护，界面不显示，也不做任何来源声明。
     */
    private object Mirrors {
        /** 清华 LXC 镜像的 Debian 目录，取其中最新的 rootfs.tar.gz。 */
        const val DEBIAN_INDEX =
            "https://mirrors.tuna.tsinghua.edu.cn/lxc-images/images/debian/bookworm/arm64/default/"

        /** 清华 Alpine 镜像（aarch64 minirootfs）。 */
        const val ALPINE_INDEX =
            "https://mirrors.tuna.tsinghua.edu.cn/alpine/latest-stable/releases/aarch64/"

        /** PRoot 可执行文件（静态 aarch64），走 GitHub 国内加速。 */
        const val PROOT =
            "https://ghfast.top/https://github.com/termux/proot/releases/download/v5.1.107/proot-aarch64-static"
    }

    private const val CONNECT_TIMEOUT = 20_000
    private const val READ_TIMEOUT = 60_000

    /** 下载进度。 */
    data class Progress(
        val fileName: String,
        val received: Long,
        val total: Long,
        val done: Boolean = false,
    ) {
        val percent: Int
            get() = if (total <= 0) 0 else ((received * 100) / total).toInt().coerceIn(0, 100)
    }

    /**
     * 安装一个发行版：下载 rootfs（+ PRoot 二进制）并解压，返回是否成功。
     */
    suspend fun install(
        context: Context,
        distro: LinuxDistro,
        onProgress: (Progress) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            // 1) PRoot 二进制
            val proot = LinuxEnvStore.prootBinary(context)
            if (!proot.isFile || proot.length() < 100_000) {
                downloadTo(Mirrors.PROOT, proot, "proot", onProgress)
                proot.setExecutable(true, false)
            }

            // 2) rootfs 压缩包
            val indexUrl = when (distro) {
                LinuxDistro.DEBIAN -> Mirrors.DEBIAN_INDEX
                LinuxDistro.ALPINE -> Mirrors.ALPINE_INDEX
            }
            val archiveUrl = resolveArchive(indexUrl, distro)
            val archive = File(LinuxEnvStore.downloads(context), archiveUrl.substringAfterLast('/'))
            if (!archive.isFile || archive.length() == 0L) {
                downloadTo(archiveUrl, archive, archive.name, onProgress)
            }

            // 3) 解压到 rootfs
            val rootfs = LinuxEnvStore.rootfs(context, distro)
            rootfs.mkdirs()
            extractTarGz(archive, rootfs) { done, total ->
                onProgress(Progress(archive.name, done, total))
            }
            archive.delete()
            Unit
        }
    }

    /** 从镜像目录页里挑出适合当前架构的压缩包。 */
    private fun resolveArchive(indexUrl: String, distro: LinuxDistro): String {
        val html = open(indexUrl).bufferedReader().use { it.readText() }
        val pattern = when (distro) {
            LinuxDistro.DEBIAN -> Regex("""href="([^"]*rootfs\.tar\.gz)"""")
            LinuxDistro.ALPINE -> Regex("""href="([^"]*minirootfs[^"]*\.tar\.gz)"""")
        }
        val hit = pattern.find(html)?.groupValues?.get(1)
            ?: throw IllegalStateException("镜像站上找不到适配当前架构的 rootfs")
        return if (hit.startsWith("http")) hit else indexUrl + hit.removePrefix("./")
    }

    private fun open(url: String): InputStream {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            setRequestProperty("User-Agent", "Mozilla/5.0 yutu-toolbox")
        }
        if (conn.responseCode !in 200..299) {
            throw IllegalStateException("下载失败：HTTP ${conn.responseCode}")
        }
        return conn.inputStream
    }

    private fun downloadTo(
        url: String,
        target: File,
        label: String,
        onProgress: (Progress) -> Unit,
    ) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".part")
        var received = 0L
        open(url).use { input ->
            FileOutputStream(tmp).use { out ->
                val total = -1L
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    received += n
                    onProgress(Progress(label, received, total))
                }
            }
        }
        if (tmp.length() == 0L) throw IllegalStateException("下载内容为空：$label")
        if (target.exists()) target.delete()
        tmp.renameTo(target)
        onProgress(Progress(label, received, received, done = true))
    }

    /**
     * 解压 tar.gz。
     *
     * 自己解 tar 而不用外部命令：Android 上没有 tar，rootfs 又必须在 Linux 启动前就位。
     * tar 的 ustar 头是 512 字节定长结构，解析成本很低。
     */
    private fun extractTarGz(
        archive: File,
        dest: File,
        onProgress: (done: Long, total: Long) -> Unit,
    ) {
        val total = archive.length()
        var consumed = 0L
        BufferedInputStream(GZIPInputStream(archive.inputStream().buffered(64 * 1024))).use { input ->
            val header = ByteArray(512)
            while (true) {
                if (!readFully(input, header)) break
                if (header.all { it == 0.toByte() }) break
                consumed += 512

                val name = readString(header, 0, 100)
                if (name.isEmpty()) break
                val sizeField = readString(header, 124, 12).trim().trim('\u0000')
                val size = sizeField.toLongOrNull(8) ?: 0L
                val typeFlag = header[156].toInt().toChar()
                val prefix = readString(header, 345, 155)
                val path = if (prefix.isEmpty()) name else "$prefix/$name"

                val target = File(dest, path)
                // 防目录穿越
                if (!target.canonicalPath.startsWith(dest.canonicalPath)) {
                    skipFully(input, size)
                    consumed += padded(size)
                    continue
                }

                when (typeFlag) {
                    // 目录：没有内容
                    '5' -> target.mkdirs()
                    // 普通文件：读出 size 字节
                    '0', '\u0000', '7' -> {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { out ->
                            var left = size
                            val buf = ByteArray(64 * 1024)
                            while (left > 0) {
                                val n = input.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
                                if (n <= 0) break
                                out.write(buf, 0, n)
                                left -= n
                            }
                        }
                    }
                    // 其余类型（符号链接、设备节点等）跳过内容
                    else -> skipFully(input, size)
                }

                // 统一对齐到 512 字节边界
                val aligned = padded(size)
                skipFully(input, aligned - size)
                consumed += aligned
                onProgress(consumed.coerceAtMost(total), total)
            }
        }
    }

    private fun padded(size: Long): Long = ((size + 511) / 512) * 512

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n <= 0) return off == 0
            off += n
        }
        return true
    }

    private fun skipFully(input: InputStream, count: Long) {
        var left = count
        val buf = ByteArray(8 * 1024)
        while (left > 0) {
            val n = input.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
            if (n <= 0) return
            left -= n
        }
    }

    private fun readString(header: ByteArray, offset: Int, length: Int): String {
        val end = (offset until offset + length).firstOrNull { header[it] == 0.toByte() } ?: (offset + length)
        return String(header, offset, end - offset).trim()
    }
}
