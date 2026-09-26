package com.mcp.toolbox.ui.linux

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 下载与安装 Linux 环境。
 *
 * 安装分成四步：选源 → 下载 → 解压 → 修补，每一步都会把进度写进
 * [InstallState]。中途退出（切后台被回收、断网、手动返回）后再次进入时，
 * 会从记录的那一步继续，而不是从头再来。
 *
 * 界面上不展示任何下载地址。
 */
object LinuxInstaller {

    private const val CONNECT_TIMEOUT = 20_000
    private const val READ_TIMEOUT = 120_000
    /**
     * 请求头里的 UA。
     *
     * 必须避开 Mozilla 开头：南大镜像对浏览器 UA 会回一个带 Set-Cookie 的
     * 302 自指跳转，而 HttpURLConnection 不处理 Cookie，会一路 302 到
     * 「redirected too many times」直接失败；换用普通客户端标识即返回 200。
     */
    private const val USER_AGENT = "yutu-toolbox/1.0"

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
     * 安装一个发行版：选源 → 下载 → 解压 → 修补。
     *
     * [sourceId] 为用户选定的源（[LinuxSources.AUTO] 表示自动）。无论哪种选择，
     * 某个源失败都会自动换下一个，尽量避免因单点故障装不上。
     */
    suspend fun install(
        context: Context,
        distro: LinuxDistro,
        sourceId: String = LinuxSources.AUTO,
        onProgress: (Progress) -> Unit,
        onStatus: (String) -> Unit = {},
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val busybox = findBusybox()
                ?: throw IllegalStateException("需要 Root 才能安装 Linux 环境（未找到可用的 busybox）")

            LinuxEnvStore.prepare(context, distro)
            val archive = downloadPhase(context, distro, sourceId, onProgress, onStatus)
            extractPhase(context, distro, busybox, archive, onStatus)
            repairPhase(context, distro, onStatus)

            InstallState.clear(context, distro)
            // 包有 90 MB 上下，装完就删；留着既占空间，也容易让人误以为还要再用
            runCatching { archive.delete() }
            cleanupDownloads(context)
            onProgress(Progress(archive.name, archive.length(), archive.length(), done = true))
            Unit
        }
    }


    /**
     * 卸载一个发行版：删除它的 rootfs、下载缓存与续装状态。
     *
     * 这里最大的风险不是删不掉，而是删过头：执行 Linux 命令时会把宿主的
     * `/dev`、`/proc`、`/sys`、`/storage/emulated/0` 以及自定义目录 bind 进
     * rootfs，而且执行结束后并不卸载。此时对 rootfs 直接 `rm -rf`，rm 会顺着
     * 挂载点递归进去，把外部存储与系统目录一并删掉。所以必须先解除本环境下的
     * 全部挂载，确认一点不剩才动手；只要还有挂载残留就中止，宁可卸载失败。
     *
     * 另外 rootfs 由 root 解压产生、属主可能是 root，删除必须走 root shell，
     * 否则应用进程删不动，清完还留一堆 root 所有的残渣。
     */
    suspend fun uninstall(
        context: Context,
        distro: LinuxDistro,
        onStatus: (String) -> Unit = {},
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            onStatus("正在卸载 ${distro.displayName} 环境")
            val envRoot = LinuxEnvStore.root(context)
            val target = File(envRoot, distro.name.lowercase())
            val downloads = LinuxEnvStore.downloads(context)
            val envPath = envRoot.absolutePath
            // 挂载点在 /proc/mounts 里记的是解析后的真实路径（/data/user/0/...），
            // 与 /data/data/... 不是字符串前缀关系，所以按「包名 + files/linux-env」
            // 匹配，两种写法都能命中
            val key = "/" + context.packageName + "/files/linux-env"
            // 用 buildString + \$ 显式转义拼脚本：raw string 里写 ${'$'}{d} 这类占位
            // 极易把 ${'$'}{shellArg(...)} 当成普通文本送进 shell（实测报 bad substitution），
            // 而 shell 变量只应转义成 \$ 交给运行时展开。
            val script = buildString {
                append("ENV=").append(shellArg(envPath)).append("; ")
                append("KEY=").append(shellArg(key)).append("; ")
                // 反复清点并解除：umount 是惰性的，一层解开后可能露出下一层
                append("for pass in 1 2 3 4 5 6; do ")
                append("M=\$(grep -F \"\$KEY\" /proc/mounts 2>/dev/null | cut -d' ' -f2); ")
                append("[ -z \"\$M\" ] && break; ")
                append("for m in \$M; do umount -l \"\$m\" 2>/dev/null; done; ")
                append("done; ")
                append("LEFT=\$(grep -F \"\$KEY\" /proc/mounts 2>/dev/null | wc -l); ")
                append("if [ \"\$LEFT\" -gt 0 ]; then ")
                append("echo \"仍有 \$LEFT 处挂载未解除，已中止以免误删宿主目录\"; exit 7; fi; ")
                append("rm -rf ").append(shellArg(target.absolutePath)).append(" 2>/dev/null; ")
                append("rm -rf ").append(shellArg(downloads.absolutePath)).append(" 2>/dev/null; ")
                append("mkdir -p \"\$ENV\" 2>/dev/null; ")
                append("chown -R ").append(context.applicationInfo.uid).append(":")
                append(context.applicationInfo.uid).append(" ").append(shellArg(envPath)).append(" 2>/dev/null; ")
                append("echo OK")
            }
            val result = shRaw(script)
            if (!result.first) {
                throw IllegalStateException(
                    "卸载失败（步骤 ${result.second}）：${result.third.take(200)}",
                )
            }
            // 续装状态文件在 filesDir 根下、不在 linux-env 里，需要单独清
            InstallState.clear(context, distro)
            onStatus("")
            Unit
        }
    }

    // ---------- 阶段一：选源 ----------

    /**
     * 候选源顺序。
     *
     * 自动：并发解析各源目录并计时，能解析的按快慢排序，解析失败的排到最后。
     * 指定：先试用户选的那个，再按声明顺序兜底，尽量不让单点故障卡住安装。
     */
    private suspend fun candidateOrder(
        distro: LinuxDistro,
        sourceId: String,
        onStatus: (String) -> Unit,
    ): List<LinuxSource> {
        val all = LinuxSources.list(distro)
        if (all.isEmpty()) return emptyList()

        if (sourceId != LinuxSources.AUTO) {
            val chosen = LinuxSources.byId(distro, sourceId)
            return if (chosen == null) all else listOf(chosen) + all.filter { it.id != chosen.id }
        }

        onStatus("正在测速…")
        val ranked = coroutineScope {
            all.map { source ->
                async(Dispatchers.IO) {
                    val startedAt = System.currentTimeMillis()
                    val ok = runCatching { resolveArchive(source) }.isSuccess
                    source to if (ok) System.currentTimeMillis() - startedAt else Long.MAX_VALUE
                }
            }.awaitAll()
        }
        val usable = ranked.filter { it.second != Long.MAX_VALUE }.sortedBy { it.second }
        val ordered = usable.map { it.first } + all.filter { src -> usable.none { it.first.id == src.id } }
        val fastest = usable.firstOrNull()?.first
        if (fastest != null) onStatus("已选 ${fastest.name}")
        return ordered
    }

    // ---------- 阶段二：下载 ----------

    /**
     * 下载阶段：按候选顺序逐个下载，成功即返回压缩包文件。
     *
     * 有未完成的续装状态时优先沿用记录里的地址与已下载的部分——
     * 此时重新解析目录可能得到另一个文件，会让已下载的进度作废。
     */
    private suspend fun downloadPhase(
        context: Context,
        distro: LinuxDistro,
        sourceId: String,
        onProgress: (Progress) -> Unit,
        onStatus: (String) -> Unit,
    ): File {
        val downloads = LinuxEnvStore.downloads(context)
        val saved = InstallState.load(context, distro)

        // 上一轮已经下载完、只是没走到解压：直接复用，不再重新下载
        if (saved != null && saved.stage != InstallState.STAGE_DOWNLOAD) {
            val done = File(downloads, saved.fileName)
            if (done.isFile && done.length() > 0) {
                onStatus("压缩包已下载，继续解压")
                return done
            }
        }

        // 有断点记录且本地确有进度时，先只试记录里的地址，把续传做完；
        // 免得为了换源白跑一轮测速，把「继续安装」拖慢。
        val resuming = saved != null && (
            partFile(File(downloads, saved.fileName), saved.url).isFile ||
                File(downloads, saved.fileName).isFile
            )
        val candidates = mutableListOf<Pair<String, String>>()
        if (saved != null) candidates += saved.url to saved.fileName
        val fallbacks = if (resuming) {
            LinuxSources.list(distro)
        } else {
            candidateOrder(distro, sourceId, onStatus)
        }
        for (source in fallbacks) {
            val url = runCatching { resolveArchive(source) }.getOrNull() ?: continue
            val name = url.substringAfterLast('/')
            // 按地址去重：Debian 各源的压缩包同名（rootfs.tar.xz），按名字去重
            // 会把备用源全部丢掉，一旦首选源不可用就没有兜底。
            if (candidates.none { it.first == url }) candidates += url to name
        }
        if (candidates.isEmpty()) throw IllegalStateException("没有可用的下载源，请检查网络后重试")

        // 与 downloadAny 同样先测速：rootfs 也有 90 MB 上下，
        // 选中慢源会让「正在测速」之后仍要等很久。
        val names = candidates.toMap()
        val orderedUrls = rankBySpeed(File(downloads, candidates.first().second), candidates.map { it.first })

        var lastError: Throwable? = null
        for (url in orderedUrls) {
            val name = names.getValue(url)
            val target = File(downloads, name)
            try {
                val state = InstallState(sourceId, url, name, target.length(), InstallState.STAGE_DOWNLOAD)
                state.save(context, distro)
                downloadResumable(
                    url = url,
                    target = target,
                    label = name,
                    onProgress = onProgress,
                    onTotal = { total -> state.copy(total = total).save(context, distro) },
                )
                InstallState(sourceId, url, name, target.length(), InstallState.STAGE_EXTRACT)
                    .save(context, distro)
                return target
            } catch (t: Throwable) {
                lastError = t
                onStatus("该源不可用（${t.message?.take(60)}），正在换源…")
            }
        }
        throw lastError ?: IllegalStateException("下载失败")
    }

    /**
     * 解压阶段：把 rootfs 解到私有目录。
     *
     * 交给 root 下的 busybox tar：它同时支持 gz 与 xz，而 Java 侧只有 gzip，
     * 处理不了 Debian LXC 的 tar.xz。tar 会原地覆盖，所以解压被打断后重跑
     * 同一个脚本即可补齐，不需要先清空目录。
     */
    private fun extractPhase(
        context: Context,
        distro: LinuxDistro,
        busybox: String,
        archive: File,
        onStatus: (String) -> Unit,
    ) {
        if (!archive.isFile || archive.length() == 0L) {
            throw IllegalStateException("安装包不存在，请重新下载")
        }
        onStatus("正在解压 ${archive.name}")
        val rootfs = LinuxEnvStore.rootfs(context, distro)
        val envRoot = LinuxEnvStore.root(context)
        val downloads = LinuxEnvStore.downloads(context)
        val script = buildString {
            append("mkdir -p ").append(shellArg(rootfs.absolutePath)).append(" || exit 1; ")
            append("$busybox tar -xf ").append(shellArg(archive.absolutePath))
            append(" -C ").append(shellArg(rootfs.absolutePath)).append(" || exit 2; ")
            append("[ -d ").append(shellArg(rootfs.absolutePath + "/bin"))
            append(" ] || [ -d ").append(shellArg(rootfs.absolutePath + "/usr"))
            append(" ] || exit 3; ")
            // 解压由 root 执行，会把 linux-env 及子目录留成 root 所有；应用进程
            // 之后既写不了续装状态、也建不了下载目录，必须交还应用属主并刷新标签。
            append("chown -R ").append(context.applicationInfo.uid)
            append(":").append(context.applicationInfo.uid).append(" ")
            append(shellArg(envRoot.absolutePath)).append(" ").append(shellArg(downloads.absolutePath))
            append(" 2>/dev/null; ")
            append("restorecon -R ").append(shellArg(envRoot.absolutePath)).append(" 2>/dev/null; ")
            append("echo OK")
        }
        val result = shRaw(script)
        if (!result.first) {
            throw IllegalStateException(
                "rootfs 解压失败（步骤 ${result.second}）：${result.third.take(200)}",
            )
        }
    }

    /**
     * 修补阶段：补齐解压后仍缺失的部分。
     *
     * 解压中断过一次的 rootfs 常在细节上有缺口：缺挂载点、缺 CA 证书的
     * hash 软链（https 镜像会整链校验失败）、缺运行目录。这里逐项补上，
     * 全部是幂等操作，重复执行不会破坏已有内容。
     *
     * 修补失败不判定安装失败：主体已经就位，细节留给组件安装时再补。
     */
    private fun repairPhase(
        context: Context,
        distro: LinuxDistro,
        onStatus: (String) -> Unit,
    ) {
        onStatus("正在修补环境")
        val rootfs = LinuxEnvStore.rootfs(context, distro)
        if (!File(rootfs, "bin").isDirectory && !File(rootfs, "usr").isDirectory) {
            throw IllegalStateException("rootfs 结构不完整，请重新安装")
        }

        // 用 raw string 拼：脚本里到处是 $ 与引号，按普通字符串转义极易写错。
        val d = '$'
        val script = """
            R=${d}{shellArg(rootfs.absolutePath)}
            for dir in dev proc sys tmp run root etc; do mkdir -p "${d}R/${d}dir" 2>/dev/null; done
            chmod 1777 "${d}R/tmp" 2>/dev/null
            chmod 700 "${d}R/root" 2>/dev/null
            # Debian 13 的 LXC rootfs 里 ca-certificates 没在构建阶段跑完：
            # /etc/ssl/certs 缺部分根证书的 PEM 与 hash 软链，CApath 校验找不到
            # 签发者就会整链 certificate verify failed，https 中文镜像全部不可用。
            if [ -d "${d}R/etc/ssl/certs" ]; then
              chroot "${d}R" update-ca-certificates --fresh >/dev/null 2>&1 || true
            fi
            echo OK
        """.trimIndent()
        val result = shRaw(script)
        if (!result.first) {
            onStatus("环境修补未完成（步骤 ${result.second}），可继续使用")
        }
    }

    /** 安装成功后清掉断点临时文件与历史遗留的临时目录。 */
    private fun cleanupDownloads(context: Context) {
        val downloads = LinuxEnvStore.downloads(context)
        downloads.listFiles()?.forEach { file ->
            when {
                file.isDirectory && file.name.startsWith("proot-work") ->
                    runCatching { file.deleteRecursively() }

                file.isFile && file.name.endsWith(".part") ->
                    runCatching { file.delete() }
            }
        }
    }

    /** Range 起点超出文件长度（HTTP 416），需要丢掉 .part 重下。 */
    private class RangeNotSatisfiable : Exception("断点已失效，重新下载")

    /**
     * 把目录页里的 href 拼成绝对地址。
     *
     * 各镜像站的写法不统一：有的给相对文件名，有的给以 / 开头的站内绝对路径，
     * 有的直接给完整 URL。
     */
    private fun absoluteUrl(base: String, href: String): String = when {
        href.startsWith("http") -> href
        href.startsWith("/") -> run {
            val b = URL(base)
            val port = if (b.port > 0) ":${b.port}" else ""
            "${b.protocol}://${b.host}$port$href"
        }

        else -> base + href.removePrefix("./")
    }

    // ---------- 镜像目录解析 ----------

    /** 目录页里某类文件的候选相对路径。 */
    private fun hrefs(html: String, pattern: String): List<String> =
        Regex(pattern).findAll(html).map { it.groupValues[1] }.toList()

    /**
     * 探测一个地址是否真能下载到内容。
     *
     * 各镜像站同步进度不一：目录索引里列着某个构建，文件却还没落盘（或已被清理），
     * 只看目录就会挑到一个必然失败的地址。这里用 Range 只取 1 字节，代价极小。
     */
    private fun reachable(url: String): Boolean = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = CONNECT_TIMEOUT
            setRequestProperty("User-Agent", USER_AGENT)
            instanceFollowRedirects = true
            setRequestProperty("Range", "bytes=0-0")
        }
        conn.responseCode in 200..299
    }.getOrDefault(false)

    /**
     * 从镜像目录页解析出 rootfs 压缩包地址。
     *
     * 两种镜像的目录结构不同：
     * - Alpine：压缩包直接放在 releases/aarch64 下，`alpine-minirootfs-<版本>-aarch64.tar.gz`
     * - Debian（LXC）：先是一层时间戳目录（如 `20260918_05:24/`），
     *   其下才是 `rootfs.tar.xz`——所以要多进一层，且格式是 xz。
     */
    private fun resolveArchive(source: LinuxSource): String {
        val indexUrl = source.indexUrl
        val html = open(indexUrl).stream.bufferedReader().use { it.readText() }
        return when (source.distro) {
            LinuxDistro.ALPINE -> resolveAlpine(indexUrl, html)
            LinuxDistro.DEBIAN -> resolveDebian(indexUrl, html)
        }
    }

    /**
     * Alpine：取正式版压缩包（排除 rc / 预览版）。
     *
     * 目录页里 `3.24.0` 与 `3.24.0_rc1`、`3.24.0_rc2` 混排，且版本号不保证递增
     * （镜像可能同时留着旧版），所以先按版本号排序再取最高，而不是取第一个。
     */
    private fun resolveAlpine(indexUrl: String, html: String): String {
        val pattern = """href="([^"]*alpine-minirootfs-(\d+)\.(\d+)\.(\d+)-aarch64\.tar\.gz)""""
        val best = Regex(pattern).findAll(html).maxByOrNull { hit ->
            val g = hit.groupValues
            listOf(g[2].toInt(), g[3].toInt(), g[4].toInt())
                .fold(0L) { acc, part -> acc * 1000 + part }
        } ?: throw IllegalStateException("镜像站上找不到适配当前架构的 rootfs")
        return absoluteUrl(indexUrl, best.groupValues[1])
    }

    /**
     * Debian（LXC）：时间戳目录从新到旧找第一个真正能下的压缩包。
     *
     * 不能只取最新目录：镜像同步有延迟，最新的那层目录常常已经列出但文件还是 404，
     * 越靠前的站越明显。因此逐个回退（最多 8 层），并用 [reachable] 实测。
     */
    private fun resolveDebian(indexUrl: String, html: String): String {
        val subdirs = hrefs(html, """href="(\d{8}_\d{2}(?:%3A|:)\d{2})/"""")
            .reversed()
            .take(8)
        if (subdirs.isEmpty()) throw IllegalStateException("镜像站上没有可用的 Debian 构建")

        var lastError: String? = null
        for (sub in subdirs) {
            val subUrl = indexUrl + sub + "/"
            val subHtml = runCatching {
                open(subUrl).stream.bufferedReader().use { it.readText() }
            }.getOrElse { continue }
            val file = hrefs(subHtml, """href="(rootfs\.tar\.(?:xz|gz))"""")
                .firstOrNull() ?: continue
            val url = subUrl + file
            if (reachable(url)) return url
            lastError = "该构建尚未同步完成"
        }
        throw IllegalStateException(lastError ?: "该镜像上没有可下载的 rootfs")
    }

    // ---------- 下载 ----------

    /** 一次 HTTP 响应：流、最终总长度（含已下载部分），以及服务端是否接受了 Range。 */
    private class Response(val stream: InputStream, val length: Long, val resuming: Boolean)

    private fun open(url: String, rangeFrom: Long = 0L): Response {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            setRequestProperty("User-Agent", USER_AGENT)
            // 官方源对 rootfs.tar.xz 会 302 跳到 CDN，必须跟随
            instanceFollowRedirects = true
            if (rangeFrom > 0) setRequestProperty("Range", "bytes=$rangeFrom-")
        }
        val code = conn.responseCode
        // 416：Range 起点超出了文件长度（镜像更新后新包比 .part 短）。
        // 交给调用方丢掉 .part 重下，而不是当作源不可用。
        if (code == 416) {
            throw RangeNotSatisfiable()
        }
        if (code !in 200..299) {
            throw IllegalStateException("下载失败：HTTP $code")
        }
        // 服务器忽略 Range 时返回 200 与完整内容，此时不能接着旧文件写
        val resuming = rangeFrom > 0 && code == HttpURLConnection.HTTP_PARTIAL

        // 解析 `Content-Range: bytes 3192897-116173954/116173955`。
        //
        // 两件事都必须看这里，不能只看状态码与 Content-Length：
        // - 起点：个别 CDN 回了 206，内容却是从 0 开始的。这时按「续传」追加，
        //   文件会变成「已下载部分 + 整个包」，比真值多出一截；长度校验（只查
        //   不够长）照样通过，直到解压才炸，界面只剩一句「退出码 1」。起点对不
        //   上就当作续传失效，丢掉 .part 重新下。
        // - 总长：206 缺 Content-Length 时 contentLengthLong 是 -1，
        //   rangeFrom + (-1) 是个废值，之后的完整性校验会全部失效。
        val contentRange = conn.getHeaderField("Content-Range")
        val rangeParts = contentRange
            ?.removePrefix("bytes ")
            ?.trim()
            ?.split('/', '-')
        val rangeStart = rangeParts?.getOrNull(0)?.trim()?.toLongOrNull()
        val rangeTotal = rangeParts?.getOrNull(2)?.trim()?.toLongOrNull()
        if (resuming && rangeStart != null && rangeStart != rangeFrom) {
            throw RangeNotSatisfiable()
        }
        val body = conn.contentLengthLong
        val length = rangeTotal ?: if (resuming && body > 0) rangeFrom + body else body
        return Response(conn.inputStream, length, resuming)
    }

    /**
     * 下载到 `<名称>.part`，再从 `.part` 改名到目标。
     *
     * 支持断点续传：.part 已存在的部分会带 Range 请求补齐。这样中断一次
     * 不必把几十 MB 重新下一遍——镜像站普遍支持 Range，只有个别 CDN 会忽略，
     * 那种情况下按提示从 0 重来。
     */
    private fun downloadResumable(
        url: String,
        target: File,
        label: String,
        onProgress: (Progress) -> Unit,
        onTotal: (Long) -> Unit = {},
    ) {
        target.parentFile?.mkdirs()
        val tmp = partFile(target, url)
        val already = if (tmp.isFile) tmp.length() else 0L
        val resp = try {
            open(url, already)
        } catch (e: RangeNotSatisfiable) {
            if (tmp.isFile) tmp.delete()
            open(url, 0L)
        }
        onTotal(resp.length)
        val start = if (resp.resuming) already else 0L
        if (start == 0L && tmp.isFile) tmp.delete()
        var received = start
        val total = resp.length
        resp.stream.use { input ->
            FileOutputStream(tmp, start > 0L).use { out ->
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
        val actual = tmp.length()
        if (actual == 0L) throw IllegalStateException("下载内容为空：$label")
        // 必须严格等于服务端声明的总长。只查「不够长」会放过被写坏的超长文件：
        // 续传时若服务端多给了内容，文件会变成「已下载部分 + 整个包」，长度
        // 校验照样通过，直到解压才失败，界面上只剩一句「退出码 1」。
        if (total > 0 && actual != total) {
            tmp.delete()
            throw IllegalStateException("下载内容与声明大小不符（$actual / $total），已丢弃，请重试")
        }
        if (target.exists()) target.delete()
        tmp.renameTo(target)
        onProgress(Progress(label, received, received, done = true))
    }

    /**
     * 通用多源断点续传下载，供工具安装复用。
     *
     * [candidates] 按顺序尝试，成功即把文件落到 [target] 并返回。中断留下的
     * `.part` 会被复用（按地址区分），因此下次接着下而不是从头来。
     */
    suspend fun downloadAny(
        candidates: List<String>,
        target: File,
        onProgress: (Progress) -> Unit,
        onTotal: (Long) -> Unit = {},
    ): File {
        target.parentFile?.mkdirs()
        // 上百 MB 的包选中慢源要多等半小时以上，先实测吞吐再排序：
        // 快的先下，已有可观进度的地址优先沿用，连不通的留作兜底。
        val ordered = rankBySpeed(target, candidates)
        var lastError: Throwable? = null
        for (url in ordered) {
            try {
                downloadResumable(
                    url = url,
                    target = target,
                    label = target.name,
                    onProgress = onProgress,
                    onTotal = onTotal,
                )
                return target
            } catch (t: Throwable) {
                lastError = t
            }
        }
        throw lastError ?: IllegalStateException("所有下载源均不可用")
    }

    /** 某个下载目标已存在的断点字节数（用于界面提示「可继续下载」）。 */
    fun partialBytes(target: File, candidates: List<String>): Long =
        candidates.maxOfOrNull { url ->
            val part = partFile(target, url)
            if (part.isFile) part.length() else 0L
        } ?: 0L

    /**
     * 断点临时文件名。
     *
     * 必须带上地址：Debian 各源的压缩包都叫 `rootfs.tar.xz`，若共用一个
     * `.part`，换源后就会拿 A 源的半个文件去向 B 源要 Range，续出错误的内容。
     * 按地址区分后，只有同一个地址才会续传。
     */
    fun partFile(target: File, url: String): File =
        File(target.parentFile, "${target.name}.${Integer.toHexString(url.hashCode())}.part")

    /**
     * 测速探测的字节数。
     *
     * 太小会被连接建立时间掩盖，太大则白费流量；128 KB 在几 MB/s 的链路上
     * 约 0.05 秒即可测完，在慢链路上也不会等太久。
     */
    private const val PROBE_BYTES = 128 * 1024

    /** 单次测速的超时。测速只作参考，不值得一提等太久。 */
    private const val PROBE_TIMEOUT = 8_000

    /**
     * 小于这个大小的断点不值得为它保留一个慢源：重新下更快，也不心疼。
     */
    private const val MIN_RESUME_BYTES = 1L * 1024 * 1024

    /**
     * 给候选地址并发测速，返回按实测吞吐从快到慢排列的列表。
     *
     * 各镜像与加速器的速度差异可以到十倍以上，而调用方原先只是「按声明顺序取
     * 第一个能连通的地址」。上百 MB 的压缩包一旦选中慢源，就要多等半小时以上，
     * 所以先让每个地址真实拉一小段比吞吐，再让快的先下。
     *
     * 已经下过可观进度的地址排在最前：换源等于把已下载的部分作废。
     */
    suspend fun rankBySpeed(
        target: File,
        candidates: List<String>,
        probeBytes: Int = PROBE_BYTES,
    ): List<String> {
        if (candidates.size < 2) return candidates
        val partial = candidates
            .filter { url ->
                val part = partFile(target, url)
                part.isFile && part.length() >= MIN_RESUME_BYTES
            }
            .sortedByDescending { url -> partFile(target, url).length() }
        val rest = candidates.filterNot { it in partial }
        if (rest.isEmpty()) return partial
        val measured = coroutineScope {
            rest.map { url ->
                async(Dispatchers.IO) {
                    url to runCatching { measure(url, probeBytes) }.getOrDefault(0L)
                }
            }.awaitAll()
        }
        val (usable, dead) = measured.partition { it.second > 0L }
        return partial +
            usable.sortedByDescending { it.second }.map { it.first } +
            dead.map { it.first }
    }

    /**
     * 拉一小段真实数据测吞吐（字节/秒）。
     *
     * 返回 0 表示这个地址当前连不上或状态码异常，调用方会把它排到最后当兜底，
     * 但仍保留在候选里——测速失败不代表稍后真的下不动。
     */
    private fun measure(url: String, probeBytes: Int): Long {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = PROBE_TIMEOUT
            setRequestProperty("User-Agent", USER_AGENT)
            instanceFollowRedirects = true
            setRequestProperty("Range", "bytes=0-" + (probeBytes - 1))
        }
        if (conn.responseCode !in 200..299) return 0L
        val started = System.currentTimeMillis()
        var read = 0L
        conn.inputStream.use { input ->
            val buf = ByteArray(16 * 1024)
            while (read < probeBytes) {
                val n = input.read(buf)
                if (n <= 0) break
                read += n
            }
        }
        val elapsed = (System.currentTimeMillis() - started).coerceAtLeast(1L)
        return read * 1000L / elapsed
    }
    // ---------- Root shell ----------

    /** 单引号包裹一个参数，供 shell 使用。 */
    private fun shellArg(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /**
     * 执行 root shell 片段，返回 (是否成功, 退出码, 输出)。
     *
     * 脚本里的 `exit N` 会作为退出码返回，便于定位是哪一步失败。
     */
    private fun shRaw(script: String): Triple<Boolean, Int, String> {
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
}
