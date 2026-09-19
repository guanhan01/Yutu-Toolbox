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
 * 全部走国内镜像，界面上不展示任何下载地址、也不做来源声明。
 */
object LinuxInstaller {

    /**
     * 镜像地址。
     *
     * 用国内镜像的原因：官方源在当前网络下经常连不上或极慢。
     * 地址只在代码里维护，界面不显示。
     */
    private object Mirrors {
        /** 清华 LXC 镜像的 Debian 目录（含 rootfs.tar.gz）。 */
        const val DEBIAN_INDEX =
            "https://mirrors.tuna.tsinghua.edu.cn/lxc-images/images/debian/bookworm/arm64/default/"

        /** 清华 Alpine 镜像（aarch64 minirootfs）。 */
        const val ALPINE_INDEX =
            "https://mirrors.tuna.tsinghua.edu.cn/alpine/latest-stable/releases/aarch64/"

        /**
         * PRoot 取自 Termux 软件源里的 deb 包。
         *
         * 上游 releases 上的静态二进制地址已失效，而 Termux 源长期稳定、
         * 且有国内镜像；包内是普通的 Linux 可执行文件，解开即可用。
         */
        const val PROOT_DEB_DIR =
            "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main/pool/main/p/proot/"

        /** 同一镜像站里 busybox 的 deb，用来解前一个包（xz/ar）。 */
        const val BUSYBOX_DEB_DIR =
            "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main/pool/main/b/busybox/"
    }

    private const val CONNECT_TIMEOUT = 20_000
    private const val READ_TIMEOUT = 120_000

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
     * 安装一个发行版：准备 PRoot（+ 解包工具）、下载 rootfs 并解压。
     */
    suspend fun install(
        context: Context,
        distro: LinuxDistro,
        onProgress: (Progress) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            ensureProot(context, onProgress)

            val indexUrl = when (distro) {
                LinuxDistro.DEBIAN -> Mirrors.DEBIAN_INDEX
                LinuxDistro.ALPINE -> Mirrors.ALPINE_INDEX
            }
            val archiveUrl = resolveArchive(indexUrl, distro)
            val archive = File(LinuxEnvStore.downloads(context), archiveUrl.substringAfterLast('/'))
            if (!archive.isFile || archive.length() == 0L) {
                downloadTo(archiveUrl, archive, archive.name, onProgress)
            }

            val rootfs = LinuxEnvStore.rootfs(context, distro)
            // 解压交给 root 下的 busybox tar：它同时支持 gz 与 xz，
            // 而 Java 侧只有 gzip，处理不了 Debian LXC 的 tar.xz。
            val busybox = findBusybox()
                ?: throw IllegalStateException("需要 Root 才能解压 rootfs")
            val script = buildString {
                append("mkdir -p ").append(shellArg(rootfs.absolutePath)).append(" || exit 1; ")
                append("$busybox tar -xf ").append(shellArg(archive.absolutePath))
                append(" -C ").append(shellArg(rootfs.absolutePath)).append(" || exit 2; ")
                append("[ -d ").append(shellArg(rootfs.absolutePath + "/bin"))
                append(" ] || [ -d ").append(shellArg(rootfs.absolutePath + "/usr"))
                append(" ] || exit 3; ")
                append("rm -f ").append(shellArg(archive.absolutePath)).append("; echo OK")
            }
            val result = shRaw(busybox, script)
            if (!result.first) {
                throw IllegalStateException(
                    "rootfs 解压失败（步骤 ${result.second}）：${result.third.take(200)}",
                )
            }
            onProgress(Progress(archive.name, archive.length(), archive.length(), done = true))
            Unit
        }
    }

    // ---------- PRoot ----------

    /**
     * 准备 PRoot 可执行文件。
     *
     * 流程：下载 deb → 用 busybox 的 ar 取出 data.tar.xz → xz -d → tar -x 拿 bin/proot。
     * busybox 取自 KernelSU/Magisk（已 root 才有），因此 PRoot 模式需要 root 权限。
     */
    private fun ensureProot(context: Context, onProgress: (Progress) -> Unit) {
        val target = LinuxEnvStore.prootBinary(context)
        if (target.isFile && target.length() > 100_000) return

        val busybox = findBusybox()
            ?: throw IllegalStateException("需要 Root 才能准备 PRoot（未找到可用的 busybox）")

        val downloads = LinuxEnvStore.downloads(context)
        val debUrl = resolveDeb(Mirrors.PROOT_DEB_DIR, "proot_")
        val deb = File(downloads, debUrl.substringAfterLast('/'))
        if (!deb.isFile || deb.length() == 0L) downloadTo(debUrl, deb, deb.name, onProgress)

        // 全部步骤放在同一个 root shell 里完成。
        // 不在 Java 侧列举 root 创建的中间目录：应用进程受 SELinux 与属主限制，
        // 看到的内容未必与 root 一致，逐目录判断很容易误判。
        val work = File(downloads, "proot-work-" + System.currentTimeMillis())
        val script = buildString {
            append("W=").append(shellArg(work.absolutePath)).append("; ")
            append("D=").append(shellArg(deb.absolutePath)).append("; ")
            append("T=").append(shellArg(target.absolutePath)).append("; ")
            append("rm -rf \"\$W\" && mkdir -p \"\$W\" && cd \"\$W\" || exit 1; ")
            // ar 不覆盖同名文件，所以目录先清空再解
            append("$busybox ar x \"\$D\" || exit 2; ")
            append("[ -f \"\$W/data.tar.xz\" ] || exit 3; ")
            append("$busybox xz -d -f \"\$W/data.tar.xz\" || exit 4; ")
            append("[ -f \"\$W/data.tar\" ] || exit 5; ")
            append("$busybox tar -xf \"\$W/data.tar\" || exit 6; ")
            append("P=\"\$W/data/data/com.termux/files/usr/bin/proot\"; ")
            append("[ -f \"\$P\" ] || exit 7; ")
            append("cp -f \"\$P\" \"\$T\" || exit 8; ")
            append("chmod 755 \"\$T\" || exit 9; ")
            append("rm -rf \"\$W\" \"\$D\" 2>/dev/null; echo INSTALLED")
        }
        val result = shRaw(busybox, script)
        if (!result.first) {
            throw IllegalStateException("PRoot 准备失败（步骤 ${result.second}）：${result.third.take(200)}")
        }
        if (!target.isFile || target.length() < 100_000) {
            throw IllegalStateException("PRoot 复制后校验失败")
        }
        target.setExecutable(true, false)
        // 清掉历史遗留的临时目录
        downloads.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("proot-work") }
            ?.forEach { runCatching { it.deleteRecursively() } }
    }

    /** 单引号包裹一个参数，供 shell 使用。 */
    private fun shellArg(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /**
     * 执行 root shell 片段，返回 (是否成功, 退出码, 输出)。
     *
     * 脚本里的 `exit N` 会作为退出码返回，便于定位是哪一步失败。
     */
    private fun shRaw(busybox: String, script: String): Triple<Boolean, Int, String> {
        val process = ProcessBuilder("su", "-c", script)
            .redirectErrorStream(true)
            .start()
        val out = process.inputStream.bufferedReader().use { it.readText() }
        val code = process.waitFor()
        return Triple(code == 0, code, out.trim())
    }

    /**
     * 在 Root shell 里找 busybox。
     *
     * 不能在 Java 侧用 File.canExecute 判断：/data/adb 是 root:root 700，
     * 应用进程访问不到，结果恒为 false。
     */
    private fun findBusybox(): String? {
        val script = buildString {
            append("for p in /data/adb/ksu/bin/busybox /data/adb/magisk/busybox ")
            append("/data/adb/apd/bin/busybox /data/adb/ap/bin/busybox ")
            append("/system/bin/busybox /system/xbin/busybox; do ")
            append("[ -x \"\$p\" ] && { echo \"\$p\"; exit 0; }; done; exit 1")
        }
        return runCatching {
            val process = ProcessBuilder("su", "-c", script)
                .redirectErrorStream(true)
                .start()
            val out = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            out.trim().lineSequence().firstOrNull { it.startsWith("/") && it.endsWith("busybox") }
        }.getOrNull()
    }

    /**
     * 以 root 执行 shell 片段。
     *
     * 脚本整体用单引号包住再交给 `su -c`，否则 `&&` 会被外层 shell 拆开，
     * 后面的命令就跑到 root 的 PATH 里去找了（那里没有 ar / xz）。
     */
    private fun sh(busybox: String, script: String) {
        val quoted = "'" + script.replace("'", "'\\''") + "'"
        val process = ProcessBuilder("su", "-c", "$busybox sh -c $quoted")
            .redirectErrorStream(true)
            .start()
        val out = process.inputStream.bufferedReader().use { it.readText() }
        val code = process.waitFor()
        if (code != 0) {
            throw IllegalStateException("解包失败：${out.trim().take(300)}")
        }
    }

    // ---------- 镜像目录解析 ----------

    private fun resolveDeb(dirUrl: String, prefix: String): String {
        val html = open(dirUrl).bufferedReader().use { it.readText() }
        val hit = Regex("""href="(${Regex.escape(prefix)}[^"]*aarch64\.deb)"""")
            .find(html)?.groupValues?.get(1)
            ?: Regex("""href="(${Regex.escape(prefix)}[^"]*_all\.deb)"""")
                .find(html)?.groupValues?.get(1)
            ?: throw IllegalStateException("镜像站上找不到所需的包")
        return if (hit.startsWith("http")) hit else dirUrl + hit
    }

    /**
     * 从镜像目录页解析出 rootfs 压缩包地址。
     *
     * 两种镜像的目录结构不同：
     * - Alpine：压缩包直接放在 releases/aarch64 下，`minirootfs-<版本>-aarch64.tar.gz`
     * - Debian（LXC）：先是一层时间戳目录（如 `20260918_05:24/`），
     *   其下才是 `rootfs.tar.xz`——所以要多进一层，且格式是 xz。
     */
    private fun resolveArchive(indexUrl: String, distro: LinuxDistro): String {
        val html = open(indexUrl).bufferedReader().use { it.readText() }
        return when (distro) {
            LinuxDistro.ALPINE -> {
                val hit = Regex("""href="([^"]*minirootfs[^"]*\.tar\.(?:gz|xz))"""")
                    .find(html)?.groupValues?.get(1)
                    ?: throw IllegalStateException("镜像站上找不到适配当前架构的 rootfs")
                if (hit.startsWith("http")) hit else indexUrl + hit.removePrefix("./")
            }

            LinuxDistro.DEBIAN -> {
                // 取最后一个时间戳目录（列表按时间升序，末尾最新）。
                // 注意 href 里的冒号被 URL 编码成 %3A，正则要两种都容忍，
                // 并直接把捕获到的原文拼进 URL。
                val sub = Regex("""href="(\d{8}_\d{2}(?:%3A|:)\d{2})/"""")
                    .findAll(html).lastOrNull()?.groupValues?.get(1)
                    ?: throw IllegalStateException("镜像站上没有可用的 Debian 构建")
                val subUrl = indexUrl + sub + "/"
                val subHtml = open(subUrl).bufferedReader().use { it.readText() }
                val file = Regex("""href="(rootfs\.tar\.(?:gz|xz))"""")
                    .find(subHtml)?.groupValues?.get(1)
                    ?: throw IllegalStateException("该构建里没有 rootfs 压缩包")
                subUrl + file
            }
        }
    }

    // ---------- 下载 ----------

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
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    received += n
                    onProgress(Progress(label, received, -1L))
                }
            }
        }
        if (tmp.length() == 0L) throw IllegalStateException("下载内容为空：$label")
        if (target.exists()) target.delete()
        tmp.renameTo(target)
        onProgress(Progress(label, received, received, done = true))
    }

    // ---------- 解压 ----------

    /**
     * 解压 tar.gz。
     *
     * 自己解 tar 而不用外部命令：rootfs 必须在 Linux 启动前就位，
     * 而 ustar 头是 512 字节定长结构，解析成本很低、也没有依赖。
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
                if (!target.canonicalPath.startsWith(dest.canonicalPath)) {
                    skipFully(input, size)
                    val pad0 = padded(size)
                    skipFully(input, pad0 - size)
                    consumed += pad0
                    continue
                }

                when (typeFlag) {
                    '5' -> target.mkdirs()
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
                    else -> skipFully(input, size)
                }

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
        val end = (offset until offset + length).firstOrNull { header[it] == 0.toByte() }
            ?: (offset + length)
        return String(header, offset, end - offset).trim()
    }
}
