package com.mcp.toolbox.ui.linux.term

import android.content.Context
import android.util.Log
import com.mcp.toolbox.core.common.PrivilegeBackend
import com.mcp.toolbox.core.common.PrivilegeManager
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 「Android 环境」终端窗口的 pty 载体管理。
 *
 * ## 它解决什么
 *
 * Android 上给 shell 分配伪终端只有 `forkpty(3)` 一条路，而系统没有任何现成程序
 * 能代劳：toybox 没有 `script` applet，busybox 的 `script` 在 root 独占目录里，
 * 免 root 时应用连看都看不到。没有 pty 的 `sh` 是非交互的 —— 不打印提示符、
 * 输入送不进去，整块区域等于死的（这正是本窗口此前标注「不可用」的原因）。
 *
 * 所以随 APK 带一个自编的静态 `ptyhelper`（源码见 `tools/ptyhelper/`）。
 * 它是 `forkpty` 的极薄封装，等价于 `script -qfc`，但不依赖 root、不依赖 rootfs、
 * 不依赖设备上任何第三方二进制。
 *
 * ## 三个后端，一套代码
 *
 * | 后端 | 怎么把 helper 跑起来 | 拿到的权限 |
 * |---|---|---|
 * | ROOT | `su -c` 执行 | uid 0，能读 `/data`、改系统设置 |
 * | SHIZUKU | `Shizuku.newProcess` | shell(2000)，比应用高，低于 root |
 * | LOCAL | 应用自己 exec | 应用自身 uid，命令就是 Android 自带那套 |
 *
 * 三者只有「怎么起进程」这一步不同，pty 读写、历史缓冲、尺寸同步全部共用
 * [TerminalSession] 里那套代码。**默认按 ROOT -> SHIZUKU -> LOCAL 选**，
 * 有 root 就用 root；都没有也能正常跑，只是权限少。
 *
 * ## 文件放在哪：只放 helper，其它一律走管道
 *
 * 三种身份能读写的目录**互不相同**，实测结论：
 *
 * | 位置 | 应用 | shell(2000) | root |
 * |---|---|---|---|
 * | 应用私有目录 `filesDir/pty` | 读写执行 | 进不去 | 可读 |
 * | `/data/local/tmp` | **只读**（SELinux 拒绝写入） | 读写 | 读写 |
 *
 * 因此：
 * - **LOCAL**：helper 打进 APK 的 assets，首次启动复制到应用私有目录，应用直接 exec。
 * - **ROOT / SHIZUKU**：helper 只能放 `/data/local/tmp/` 下，且**必须由该身份自己写**
 *   —— 应用写不进去（SELinux），所以内容经**管道**喂给 `cat >`，而不是让高权限进程
 *   去读 APK。读 APK 还要额外处理 zip 与 SELinux 上下文，没必要。
 * - **banner 不落文件**：它以脚本文本的形式直接作为 `sh -c` 的参数传进去
 *   （见 [TerminalBanner.androidBannerScript]）。三方身份的文件交换在某一方上
 *   必然失败，索性完全不做文件交换。
 *
 * helper 按大小校验，已是同版本就跳过重写，避免每次开窗口都传 65 KB。
 */
object PtyHelper {

    private const val TAG = "PtyHelper"

    /** assets 里的相对路径。 */
    private const val ASSET = "pty/ptyhelper-arm64"

    /** 高权限后端共用的落地点。`shell` 与 root 都能写，重启不丢。 */
    private const val SHARED_DIR = "/data/local/tmp/.yutu-agt"

    private const val BIN_NAME = "ptyhelper"

    /**
     * 高权限窗口的初始工作目录。
     *
     * 用 `/data/local/tmp`：三种身份里只有它 root 与 shell 都可写，
     * 做交互 shell 的起点最不容易撞权限。应用自身身份则用私有 `filesDir/home`。
     */
    private const val HOME_DIR = "/data/local/tmp"

    /** pty 从端路径的 stderr 标记，与 `ptyhelper.c` 的 `EPTY_PREFIX` 必须一致。 */
    private const val PTS_PREFIX = "EPTY="

    /** 一次可用的启动配置。 */
    data class Launcher(
        /** helper 的绝对路径。 */
        val binary: String,
        /** 本窗口最终使用的后端。 */
        val backend: PrivilegeBackend,
        /** 本窗口的初始工作目录。 */
        val home: String,
    ) {
        /** 是否跑在高权限下。 */
        val elevated: Boolean get() = backend != PrivilegeBackend.LOCAL
    }

    /**
     * 从 helper 的 stderr 里挑出 pty 从端路径。
     *
     * helper 用 `EPTY=/dev/pts/N` 这一行上报（见 ptyhelper.c 的注释），
     * 其余行才是真正的报错。返回 null 表示这行不是标记。
     */
    fun ptsFromStderr(line: String): String? =
        line.trim().takeIf { it.startsWith(PTS_PREFIX) }
            ?.removePrefix(PTS_PREFIX)?.trim()
            ?.takeIf { it.startsWith("/") }

    /**
     * 选择后端。**有 root 默认用 root**，其次 Shizuku，最后回落到应用自身身份。
     *
     * 走 [PrivilegeManager] 而不是自己探测：那套已经有缓存、有真实 `id` 校验
     * （只认 `uid=0`），并且和「高权限工具」用的是同一份判定，避免两处结论打架。
     */
    fun pickBackend(context: Context): PrivilegeBackend {
        val status = PrivilegeManager.status(context)
        return when {
            status.active == PrivilegeBackend.ROOT -> PrivilegeBackend.ROOT
            status.active == PrivilegeBackend.SHIZUKU -> PrivilegeBackend.SHIZUKU
            else -> PrivilegeBackend.LOCAL
        }
    }

    /**
     * 就绪一个可用的 [Launcher]。失败时返回可读原因，由界面显示，不抛异常。
     *
     * @param requested 调用方指定的后端；null 表示自动挑选（root 优先）。
     */
    fun prepare(
        context: Context,
        requested: PrivilegeBackend? = null,
    ): Result<Launcher> {
        val backend = requested ?: pickBackend(context)
        val assetBytes = readAsset(context)
            ?: return Result.failure(IllegalStateException("安装包内缺少终端组件（assets/$ASSET）"))

        return when (backend) {
            PrivilegeBackend.LOCAL -> prepareLocal(context, assetBytes)
            PrivilegeBackend.ROOT, PrivilegeBackend.SHIZUKU -> prepareElevated(backend, assetBytes)
            PrivilegeBackend.NONE -> Result.failure(IllegalStateException("没有可用的执行后端"))
        }
    }

    /** 应用自身身份：私有目录里的副本。 */
    private fun prepareLocal(context: Context, assetBytes: ByteArray): Result<Launcher> = runCatching {
        val dir = File(context.filesDir, "pty").apply { mkdirs() }
        val bin = File(dir, BIN_NAME)
        if (!isSame(bin, assetBytes)) {
            // 先写临时文件再改名：避免复制到一半被中断，留下一个半截的、
            // 一执行就报「格式错误」的文件（下次启动还会以为它是最新的）。
            val tmp = File(dir, "$BIN_NAME.tmp")
            tmp.writeBytes(assetBytes)
            tmp.setExecutable(true, false)
            if (!tmp.renameTo(bin)) {
                bin.delete()
                if (!tmp.renameTo(bin)) error("无法写入 ${bin.absolutePath}")
            }
        }
        bin.setExecutable(true, false)
        if (!bin.canExecute()) error("组件不可执行：${bin.absolutePath}")

        val home = File(context.filesDir, "home").apply { mkdirs() }
        Launcher(binary = bin.absolutePath, backend = PrivilegeBackend.LOCAL, home = home.absolutePath)
    }.recoverCatching { t ->
        Log.w(TAG, "prepareLocal failed", t)
        throw IllegalStateException("终端组件准备失败：${t.message}")
    }

    /**
     * 高权限身份：安装到 `/data/local/tmp/.yutu-agt/`。
     *
     * 内容经**管道**喂给 `cat >`，因为这个进程读不到应用私有目录，也读不了 APK 内部。
     * 装完立刻回来核对大小，与预期不符就报错 —— 半截文件会在 exec 时报
     * 莫名其妙的错，不如在这里拦住。
     */
    private fun prepareElevated(
        backend: PrivilegeBackend,
        assetBytes: ByteArray,
    ): Result<Launcher> = runCatching {
        val bin = "$SHARED_DIR/$BIN_NAME"

        // 已经是同版本就别重装：省一次 65 KB 的写入，也省掉一次没必要的覆盖。
        if (isSame(File(bin), assetBytes)) {
            return@runCatching Launcher(binary = bin, backend = backend, home = HOME_DIR)
        }

        val tmp = "$SHARED_DIR/.incoming"
        // 写到**临时名**再原子改名：`cat > 同名文件` 会撞 ETXTBSY；
        // 而 `mv` 替换是改目录项，允许覆盖正在运行的那个（旧进程继续用旧 inode）。
        val install = listOf(
            "/system/bin/sh", "-c",
            "mkdir -p '$SHARED_DIR' && " +
                "cat > '$tmp' && " +
                "chmod 700 '$tmp' && " +
                "mv -f '$tmp' '$bin'",
        )

        val process = PrivilegeManager.spawn(install, backend)
            ?: error("无法启动高权限进程（${backend.name}）")

        // 写完就把 stdin 关掉：cat 在等 EOF 才会往下走，留着会一直卡住。
        runCatching {
            process.outputStream.use { it.write(assetBytes); it.flush() }
        }.getOrElse { t ->
            process.destroyForcibly()
            error("写入终端组件失败：${t.message}")
        }

        val finished = process.waitFor(20, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            error("安装终端组件超时（${backend.name}）")
        }

        val size = verifySize(backend)
        if (size != assetBytes.size.toLong()) {
            error("终端组件安装不完整（$size / ${assetBytes.size} 字节）")
        }

        Launcher(binary = bin, backend = backend, home = HOME_DIR)
    }.recoverCatching { t ->
        Log.w(TAG, "prepareElevated failed", t)
        throw IllegalStateException("终端组件准备失败：${t.message}")
    }

    /** 高权限侧核对文件大小；拿不到就返回 -1，由调用方按不匹配处理。 */
    private fun verifySize(backend: PrivilegeBackend): Long =
        PrivilegeManager.exec("wc -c < '$SHARED_DIR/$BIN_NAME'", backend = backend)
            .stdout.trim().toLongOrNull() ?: -1L

    /**
     * 启动一个交互 shell 并接上 pty。
     *
     * 返回的 [Process] 的 stdin/stdout 就是 pty 的两端：写进去当作键盘输入，
     * 读出来当作屏幕输出。stderr 里会有 `EPTY=<从端>` 标记，见 [ptsFromStderr]。
     *
     * @param banner banner 脚本文本。由 [TerminalBanner.androidBannerScript] 生成，
     *   **作为 `sh -c` 的参数**传进去，不落任何文件（原因见类注释）。
     *   null 表示不打印 banner，直接进交互 shell。
     */
    fun startShell(
        launcher: Launcher,
        rows: Int,
        cols: Int,
        banner: String? = null,
        shell: String = "/system/bin/sh",
    ): Process? {
        val argv = mutableListOf(
            launcher.binary,
            "--rows", rows.toString(),
            "--cols", cols.toString(),
            "--",
        )
        // 无论有没有 banner，都先做一次**提示符规范化**再 exec 交互 shell。
        //
        // 为什么必须做：本机 shell 的交互配置会把**上一条命令的退出码**拼进
        // 提示符。于是敲一条不存在的命令之后，提示符会多出一段数字前缀，
        // 与终端里其他内容混在一起，看起来像终端出错。
        //
        // 处理方式是把提示符定死成与 Linux 窗口一致的简洁样式：
        //   · 关掉交互配置的加载（`ENV=/dev/null`），退出码前缀就不会再出现
        //   · 显式 export PS1，高权限身份用 `#`、普通身份用 `$`
        // 用 `exec` 接续，中间不留空壳进程。
        val prologue = buildString {
            if (!banner.isNullOrBlank()) append(banner.trimEnd('\n')).append('\n')
            append("export ENV=/dev/null\n")
            append("if [ \"\$(id -u 2>/dev/null)\" = 0 ]; then ")
            append("PS1=':\${PWD:-?} # '; else PS1=':\${PWD:-?} \$ '; fi\n")
            append("export PS1\n")
        }
        argv += listOf(shell, "-c", prologue + "exec " + quote(shell) + " -i")
        return PrivilegeManager.spawn(argv, launcher.backend, launcher.home)
    }

    /**
     * 改 pty 尺寸。
     *
     * 必须经**同一个后端**执行：`stty -F /dev/pts/N` 需要对该从端有权限，
     * 应用身份改不了 root/shell 建的 pts。走 [PrivilegeManager.exec] 统一处理。
     */
    fun resize(backend: PrivilegeBackend, pts: String, cols: Int, rows: Int) {
        PrivilegeManager.exec(
            "stty -F '$pts' rows $rows cols $cols 2>/dev/null",
            timeoutMs = 4_000L,
            backend = backend,
        )
    }

    /** 读 assets。 */
    private fun readAsset(context: Context): ByteArray? = runCatching {
        context.assets.open(ASSET).use { it.readBytes() }
    }.getOrNull()

    /** 单引号包裹，内部单引号按 POSIX 惯用写法逃逸。 */
    private fun quote(text: String): String = "'" + text.replace("'", "'\\''") + "'"

    /**
     * 内容一致就跳过复制。比逐次覆盖便宜，也少一次写盘。
     *
     * **读不到就退化成只比大小**：高权限侧落地的 helper 是 700、属主 root，
     * 应用进程读不动它的内容（EACCES），但 `stat` 取长度是可以的。
     * 这里不能让异常冒出去 —— 那会中断整个准备流程，表现成「终端起不来」，
     * 而这本来只是个「能不能省一次重装」的小判断。
     *
     * 只比大小是否够：内容由 APK 内的 asset 决定，版本一变大小几乎必变；
     * 万一同大小不同内容，最坏结果是这个窗口用着上一版 helper，重开一次即好。
     */
    private fun isSame(file: File, bytes: ByteArray): Boolean {
        if (!file.isFile || file.length() != bytes.size.toLong()) return false
        val local = runCatching { file.readBytes() }.getOrNull() ?: return true
        return local.contentEquals(bytes)
    }
}
