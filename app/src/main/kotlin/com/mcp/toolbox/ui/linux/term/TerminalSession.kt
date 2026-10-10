package com.mcp.toolbox.ui.linux.term

import android.content.Context
import android.util.Log
import com.mcp.toolbox.core.common.PrivilegeBackend
import com.mcp.toolbox.ui.linux.LinuxDistro
import com.mcp.toolbox.ui.linux.LinuxEnvStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * 终端会话：把 rootfs 里一个真正的 PTY 交互 shell（`bash -i`）接到界面。
 *
 * 进程结构（真机实测出的最小可靠组合）：
 *
 * ```
 * su -c
 *  └─ sh eta-term-launch.sh        # 以 root 解析 busybox、做挂载
 *      └─ unshare -m               # 私有挂载空间，退出即自动清掉所有 bind mount
 *           └─ setsid busybox script -qfc <bootstrap> /dev/null   # forkpty() 分配 pty
 *                └─ sh bootstrap.sh  # 趁 pty 刚建好先定尺寸
 *                     └─ chroot <rootfs> /bin/bash -i
 * ```
 *
 * 五个坑，每个都踩过：
 *
 * 1. **busybox 必须在 root shell 里找，不能在应用里探测**。`/data/adb` 下是
 *    root-only，应用进程 `File.canExecute()` 恒为 false（`LinuxRuntime` 里也记过
 *    同一件事）。所以候选路径的循环写在 launch 脚本里执行。
 * 2. **`/dev` 要 `--rbind`**（不是 `--bind`）：单层 bind 不会带进 `/dev/pts`、
 *    `/dev/ptmx`，到 `/dev/tty` 就没了。
 * 3. **尺寸要在 pty 建好之后、bash 起来之前设**：`script` 用的是「分配 pty 那一刻」
 *    的终端大小，`-c` 命令里再设已经晚了。之后由 [resize] 用宿主 `stty -F` 现改，
 *    终端会把 SIGWINCH 发给前台进程组（实测 bash 的 `$COLUMNS` 会跟着变）。
 * 4. **pty 从端由 chroot 内的 bash 直接继承**（已打开的 fd），不需要 rootfs 里
 *    存在 `/dev/pts/N` 节点。
 * 5. **收尾只 kill `script` 进程**：它是 pty 的唯一持有者，一死内核就挂断 pty，
 *    bash 收到 EOF/SIGHUP 自己退出，连它拉起的后台任务一起带走（已实测）。
 *    不要按进程组杀——交互式 bash 有自己的进程组，`script` 反而不在里面。
 *
 * 权限：整条链路走 `su -c`，与 [com.mcp.toolbox.ui.linux.LinuxRuntime] 一致。
 * 已知缺口：只支持 Root，Shizuku 后端尚未接入（LinuxRuntime 同样如此）。
 */
class TerminalSession(
    private val context: Context,
    private val distro: LinuxDistro,
    /**
     * 本会话跑在宿主还是 Linux 环境里。
     *
     * 两种模式的差别只在「怎么把交互 shell 拉起来」这一步：
     * - LOCAL：工程自带的 `ptyhelper`（见 [PtyHelper]）分配 pty 后接 `/system/bin/sh`。
     *   **不需要 su、不需要 rootfs**；有 root / Shizuku 时会自动用它们跑，
     *   拿到的权限更高，没有也能正常工作。
     * - LINUX：`su -c unshare -m sh launch.sh` → chroot → bash
     * 之后的 pty 读写、历史缓冲、尺寸同步全部共用同一套代码。
     */
    private val env: TermSessionStore.Env = TermSessionStore.Env.LINUX,
    /**
     * 本窗口**指定**的后端；null 表示自动挑选（root 优先，其次 Shizuku，最后应用自身）。
     * 只对 [TermSessionStore.Env.LOCAL] 有意义，Linux 窗口忽略它。
     */
    private val requestedBackend: PrivilegeBackend? = null,
) {

    companion object {
        private const val TAG = "TerminalSession"

        /** 输出历史的容量上限。够放下 xterm 的 5000 行回滚，又不会把内存撑爆。 */
        private const val HISTORY_BYTES = 256 * 1024

        const val DEFAULT_COLS = 80
        const val DEFAULT_ROWS = 24

        /**
         * 会话序号，用来给缓存文件与 rootfs 里的 banner 脚本起唯一名字。
         *
         * 多窗口并存时这点是必需的：两个会话若共用 `eta-term.pts` /
         * `eta-term-launch.sh`，会互相覆盖脚本、抢同一个 pts 文件——
         * 后启动的那个把前一个写下的从端路径盖掉，界面拿到的是别人的 pty。
         */
        private val counter = java.util.concurrent.atomic.AtomicInteger()

        /** 启动阶段最多等多久拿到 pts。挂载 + forkpty 实测 1 秒内完成，留足余量。 */
        private const val READY_TIMEOUT_MS = 6000L
    }

    /**
     * 输出历史：**屏幕内容的唯一数据源**。
     *
     * 会话比页面活得久——离开终端页、在窗口之间切换时 WebView 会被销毁，
     * 而会话（pty + bash）还在跑。一次性事件流（`replay = 0`）重放不出
     * 已经吐过的 banner 与提示符，界面重挂载后就是一块白屏；历史缓冲允许
     * 后来者从头补齐，见 [readSince]。
     */
    private val history = OutputHistory(HISTORY_BYTES)

    /**
     * 「有新输出」的唤醒信号。
     *
     * 只做通知、不带载荷：载荷由 [readSince] 按游标去历史里取。用一次性事件流
     * 直接送数据的话，界面还没挂载时发的那几帧就永久丢了；按游标取是累计的，
     * 晚一步也能补齐。`replay = 1` 让新挂载的界面立刻拉一次。
     */
    private val _wake = MutableSharedFlow<Unit>(
        replay = 1,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val wake: SharedFlow<Unit> = _wake

    /** 会话结束；参数是 `script` 的退出码。replay=1 让晚订阅的 UI 也能收到。 */
    private val _exited = MutableSharedFlow<Int>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val exited: SharedFlow<Int> = _exited

    private var scope: CoroutineScope? = null
    private var process: Process? = null
    private var stdin: OutputStream? = null
    private var pumps: List<Job> = emptyList()

    /** root shell 的 stderr 尾巴：启动失败时用来给出可读原因。 */
    private val stderrTail = StringBuilder()

    /**
     * 启动互斥。
     *
     * 兜底用：调用方 [com.mcp.toolbox.ui.linux.term.TermSessionStore] 已经防了重入，
     * 但一次误启就是两个 shell 抢同一份脚本与 pts 文件，现场很难看，这里再拦一道。
     */
    private val launchGuard = java.util.concurrent.atomic.AtomicBoolean(false)

    private var cols = DEFAULT_COLS
    private var rows = DEFAULT_ROWS

    /** 上一次**真正应用**到 pty 上的尺寸。用于跳过无变化的 stty。 */
    private var appliedCols = -1
    private var appliedRows = -1

    /** 待下发的最新尺寸（见 [resize] 的串行化说明）。 */
    @Volatile private var pendingCols = -1
    @Volatile private var pendingRows = -1

    /** 保证同一时刻只有一次 stty 在下发。 */
    private val resizeMutex = Mutex()

    /**
     * Android 窗口（[TermSessionStore.Env.LOCAL]）就绪后的启动配置。
     *
     * resize 与 kill 都要用到它：改尺寸得知道 pty 从端路径 **且要经同一个后端**
     * 去执行 `stty`（应用身份改不了 root 建的 pts），收尾时也要知道临时文件在哪。
     */
    @Volatile
    private var localLauncher: PtyHelper.Launcher? = null

    /**
     * helper 经 stderr 上报的 pty 从端路径（`EPTY=/dev/pts/N`）。
     *
     * 不走共享文件：三个身份能写的目录互不相同（应用写不了 /data/local/tmp，
     * root/shell 进不去应用私有目录），文件交换必然在某一种身份下失败。
     */
    @Volatile
    private var reportedPty: String? = null

    /** 本会话的短名，用于生成互不冲突的临时文件名。 */
    private val tag = "s" + counter.incrementAndGet()

    /** pty 从端路径（如 `/dev/pts/3`），bootstrap 写出后可见。 */
    @Volatile
    var ptyPath: String? = null
        private set

    @Volatile
    var running = false
        private set

    /** 退出码；-1 表示异常收场。 */
    @Volatile
    var lastExit = -1
        private set

    private val ptyFile: File get() = File(context.cacheDir, "eta-term-$tag.pts")
    private val bootstrapFile: File get() = File(context.cacheDir, "eta-term-$tag-bootstrap.sh")
    private val launchFile: File get() = File(context.cacheDir, "eta-term-$tag-launch.sh")

    /** banner 脚本在 rootfs 内的路径：既用于判断存在，也用于执行。 */
    private val bannerPath = "/tmp/.eta-banner-$tag.sh"

    /**
     * 启动会话。失败时返回可读原因（未安装 / 没有 Root / 无法创建 pty）。
     */
    suspend fun start(workingDir: String = "/root"): Result<Unit> = withContext(Dispatchers.IO) {
        if (running || !launchGuard.compareAndSet(false, true)) {
            return@withContext Result.success(Unit)
        }
        // Android 环境不需要 rootfs，也不该因为「Linux 环境没装」而被拦住——
        // 它用的是 Android 自己的 shell。
        if (env == TermSessionStore.Env.LOCAL) {
            return@withContext startLocal()
        }
        if (!LinuxEnvStore.isInstalled(context, distro)) {
            return@withContext Result.failure(IllegalStateException("环境尚未安装，请先下载安装 Linux 环境"))
        }
        val rootfs = LinuxEnvStore.rootfs(context, distro)
        val busyboxCandidates = listOf(
            "/data/adb/ksu/bin/busybox",
            "/data/adb/magisk/busybox",
            "/data/adb/ap/bin/busybox",
            "/system/xbin/busybox",
            "/system/bin/busybox",
        )
        // 新的一次启动就是新会话：旧输出（上一个 shell 的 banner、报错）不该再被重放
        history.reset()
        val bootstrap = writeBootstrap(workingDir)
        val launch = writeLauncher(rootfs.absolutePath, busyboxCandidates, bootstrap.absolutePath)
        ptyFile.delete()
        stderrTail.setLength(0)

        runCatching {
            val p = ProcessBuilder("/system/bin/sh", "-c", "su -c " + sq("/system/bin/sh ${launch.absolutePath}"))
                .redirectErrorStream(false)
                .start()

            process = p
            stdin = p.outputStream
            running = true

            val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            scope = s
            pumps = listOf(
                s.launch { pump(p.inputStream) },
                // root shell 的 stderr 不进终端画面：挂载失败的报错混进 ANSI 流里
                // 可能被当成控制序列解析，反而把画面弄乱。留尾巴用于报错文案。
                s.launch { pumpStderr(p.errorStream) },
            )
            s.launch {
                val code = runCatching { p.waitFor() }.getOrDefault(-1)
                running = false
                lastExit = code
                _exited.tryEmit(code)
                releaseScope()
            }

            val pts = awaitPty(ptyFile, READY_TIMEOUT_MS)
            if (pts == null) {
                val why = stderrTail.toString().trim().lineSequence()
                    .lastOrNull { it.isNotBlank() }
                    ?.take(160)
                kill()
                Result.failure(
                    IllegalStateException(
                        when {
                            why.isNullOrBlank() -> "终端启动失败：无法创建伪终端（需要 Root 权限）"
                            else -> "终端启动失败：$why"
                        },
                    ),
                )
            } else {
                ptyPath = pts
                Result.success(Unit)
            }
        }.getOrElse { t ->
            Log.w(TAG, "start failed", t)
            releaseScope()
            launchGuard.set(false)
            Result.failure(t)
        }
    }

    /**
     * 启动**宿主 Android 环境**的交互 shell（不需要 Root）。
     *
     * ## 不能依赖 `script`
     *
     * 最早我用 `script -qfc` 拿 pty（它能 forkpty）。**那是错的**：Android 上
     * `script` 不是系统命令，而是 busybox 的 applet（在 `/data/adb/ksu/bin/busybox`），
     * 系统 toybox 里没有它。root shell 的 PATH 里有 busybox 所以能跑，
     * 而**应用进程无权访问那个目录**（实测 Permission denied）——
     * 于是终端一片空白（用户截图）。
     *
     * 教训：验证「无 root 能否工作」必须在**应用进程的身份**下测，
     * 在 root shell 里测会把 root 才有的能力算到应用头上。
     *
     * ## 现在的做法
     *
     * 直接 `ProcessBuilder("/system/bin/sh")`。这样**没有 pty**——
     * 后果是非交互体验会差一些（`ls` 不给颜色、`top` 报 not a tty），
     * 但：
     * - shell 本身是真交互的（能连续执行、`cd`/`export` 都保持）
     * - 命令一定能跑起来，不会像之前那样整块空白
     * - 读/写/历史缓冲/尺寸同步全部复用与 Linux 模式相同的那套代码
     *
     * 这也正是用户要的「普通模式：不用 root 就能跑的终端」——
     * 它给的是**可用的宿主 shell**，不是 PRoot 那种完整 Linux。
     */
    private fun startLocal(): Result<Unit> {
        history.reset()
        ptyFile.delete()
        stderrTail.setLength(0)

        val launcher = PtyHelper.prepare(context, requestedBackend).getOrElse { t ->
            launchGuard.set(false)
            return@startLocal Result.failure(t)
        }
        localLauncher = launcher

        reportedPty = null

        return runCatching {
            // banner 与 shell 走同一个 pty：由 shell 打印出来，才是终端的真实输出
            // （可滚动、可选中复制），与 Linux 窗口的 banner 来源一致。
            // 内容直接作为参数传入，不落文件（三方身份没有共同可写的目录）。
            val banner = TerminalBanner.androidBannerScript(modeLabel(launcher.backend))

            val p = PtyHelper.startShell(launcher, rows, cols, banner = banner)
                ?: error("无法启动终端组件（${launcher.backend.name}）")

            process = p
            stdin = p.outputStream
            running = true

            val sc = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            scope = sc
            pumps = listOf(
                sc.launch { pump(p.inputStream) },
                // helper 的 stderr 不进终端画面（与 Linux 模式同样的取舍），留尾巴报错用。
                sc.launch { pumpStderr(p.errorStream) },
            )
            sc.launch {
                val code = runCatching { p.waitFor() }.getOrDefault(-1)
                running = false
                lastExit = code
                _exited.tryEmit(code)
                releaseScope()
            }

            // 从 helper 的 stderr 标记行拿 pty 从端路径，之后 resize 靠它。
            // 拿不到不算致命（尺寸改不动而已），但要留痕便于排查。
            val pts = awaitReportedPty(READY_TIMEOUT_MS)
            if (pts == null) {
                Log.w(TAG, "local pty path not reported; resize will be skipped")
            } else {
                ptyPath = pts
            }

            Result.success(Unit)
        }.getOrElse { t ->
            Log.w(TAG, "startLocal failed", t)
            releaseScope()
            launchGuard.set(false)
            Result.failure(t)
        }
    }

    /** 等 helper 通过 stderr 上报 pty 从端路径。 */
    private fun awaitReportedPty(timeoutMs: Long): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            reportedPty?.let { return it }
            if (process?.isAlive != true) break
            Thread.sleep(40)
        }
        return reportedPty
    }

    /** 后端在 banner 上的显示名。用户看的是「拿到了多大权限」，不是枚举名。 */
    private fun modeLabel(backend: PrivilegeBackend): String = when (backend) {
        PrivilegeBackend.ROOT -> "root"
        PrivilegeBackend.SHIZUKU -> "Shizuku"
        PrivilegeBackend.LOCAL -> "应用自身"
        PrivilegeBackend.NONE -> "无"
    }

    /**
     * 等 helper / bootstrap 把 pty 从端路径写出来。
     *
     * 两种模式落点不同：Linux 模式写在应用 cacheDir（bootstrap 脚本里的 `tty >`），
     * Android 模式写在 [PtyHelper.Launcher.ptsFile]（helper 的 `--pts`）。
     * 所以由调用方把文件路径传进来，不写死。
     */
    private fun awaitPty(file: File, timeoutMs: Long): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val text = runCatching { file.readText().trim() }.getOrNull()
            if (!text.isNullOrBlank() && text.startsWith("/")) return text
            // 注意：isAlive 是方法不是属性，`process?.isAlive != true` 才是「已退出」
            if (process?.isAlive != true) break
            Thread.sleep(40)
        }
        return null
    }

    private suspend fun pump(stream: InputStream) {
        val buf = ByteArray(8192)
        try {
            while (true) {
                val n = stream.read(buf)
                if (n <= 0) break
                // 顺序不能反：先落历史再唤醒，界面醒来时数据一定已经在了
                history.append(buf, 0, n)
                _wake.tryEmit(Unit)
            }
        } catch (t: Throwable) {
            Log.d(TAG, "pump ended: ${t.message}")
        }
    }

    /** 只收 root shell 的 stderr，不往终端画面里塞。 */
    private suspend fun pumpStderr(stream: InputStream) {
        try {
            val reader = stream.bufferedReader()
            while (true) {
                val line = reader.readLine() ?: break
                // pty 从端上报（只来自 Android 模式的 helper）；不算错误，不进 stderrTail。
                val pts = PtyHelper.ptsFromStderr(line)
                if (pts != null) {
                    reportedPty = pts
                    continue
                }
                synchronized(stderrTail) {
                    stderrTail.append(line).append('\n')
                    if (stderrTail.length > 2000) stderrTail.delete(0, stderrTail.length - 2000)
                }
            }
        } catch (t: Throwable) {
            Log.d(TAG, "stderr pump ended: ${t.message}")
        }
    }

    /** 把按键 / 粘贴文本写进 pty。 */
    fun write(bytes: ByteArray) {
        val out = stdin ?: return
        runCatching {
            out.write(bytes)
            out.flush()
        }.onFailure { Log.d(TAG, "write failed: ${it.message}") }
    }


    fun write(text: String) = write(text.toByteArray(Charsets.UTF_8))

    /**
     * 取 [cursor] 之后的输出，返回「新游标 + 数据」。
     *
     * `cursor < 0`、或游标已被裁掉时返回全量（调用方整屏重放）。读取与追加在
     * 同一把锁里完成，不会读到「追加到一半」的错位数据。
     */
    fun readSince(cursor: Long): Pair<Long, ByteArray> = history.readFrom(cursor)

    /**
     * 窗口尺寸变化。
     *
     * 走宿主 `stty -F <pts>` 直接改从端，比往 shell 输入里塞 `stty` 命令干净：
     * 后者会把命令内容留在屏幕上，还会打乱用户正在输入的那一行。
     *
     * ## 为什么**不能**用时间去抖
     *
     * 曾经在这里加过 120ms 去抖，是错的。原因在 shell 那边：
     * 收到 SIGWINCH 时 mksh 会**原地重画**提示符 —— 先补一串空格抹掉旧的，
     * 再用退格退回行首，而串长按**当时的终端宽度**算。只要 pty 的列数和 xterm
     * 的列数错开一格，这串空格就会超宽折行、退格回不到行首，提示符于是掉到
     * 下一行并带偏移（用户报的「放大字体就自动换行、然后新建一行」「提示符
     * 逐个右移」）。去抖期间 pty 恰好停在旧宽度，正是在制造这种错配。
     *
     * ## 现在：串行化 + 只认最新值
     *
     * 尺寸立刻发出去，但不让请求堆成十几个进程：
     * - [resizeMutex] 保证同一时刻只在下发一次 `stty`；
     * - 若期间又来了新尺寸，只记下来，等当前这次结束后**用最新值**再下发一次。
     *
     * 效果：稳态下尺寸与 xterm 同步（消除错配），快速缩放时也不会每帧起一个进程。
     */
    suspend fun resize(newCols: Int, newRows: Int) {
        val c = newCols.coerceIn(10, 500)
        val r = newRows.coerceIn(4, 300)
        if (c == cols && r == rows) return
        cols = c
        rows = r

        // 只保留最新一档：等锁期间多次调用会被后面那次覆盖。
        pendingCols = c
        pendingRows = r

        resizeMutex.withLock {
            // 拿到锁后取走当前最新值；期间若又更新过，也一并按最新值下（见下面 while）。
            while (true) {
                val targetC = pendingCols
                val targetR = pendingRows
                if (targetC == appliedCols && targetR == appliedRows) return@withLock

                val pts = ptyPath ?: return@withLock
                withContext(Dispatchers.IO) {
                    val launcher = localLauncher
                    if (env == TermSessionStore.Env.LOCAL && launcher != null) {
                        // Android 窗口必须经**同一个后端**改：pts 是那个后端建的，
                        // 应用身份 `stty -F` 会被拒。
                        runCatching { PtyHelper.resize(launcher.backend, pts, targetC, targetR) }
                            .onFailure { Log.d(TAG, "resize failed: ${it.message}") }
                    } else {
                        runCatching {
                            ProcessBuilder("/system/bin/sh", "-c", "stty -F $pts rows $targetR cols $targetC")
                                .redirectErrorStream(true)
                                .start()
                                .waitFor(2, TimeUnit.SECONDS)
                        }.onFailure { Log.d(TAG, "resize failed: ${it.message}") }
                    }
                }
                appliedCols = targetC
                appliedRows = targetR

                // 下发期间尺寸又变了 -> 再走一轮，直到追平最新值。
                if (pendingCols == appliedCols && pendingRows == appliedRows) return@withLock
            }
        }
    }

    /** 结束会话。杀掉持有 pty 的 `script`，整棵子树随 pty 挂断退出。 */
    suspend fun kill() = withContext(Dispatchers.IO) {
        process?.let { p ->
            runCatching { p.destroy() }
            val ended = runCatching { p.waitFor(1500, TimeUnit.MILLISECONDS) }.getOrDefault(false)
            if (!ended) runCatching { p.destroyForcibly() }
        }
        releaseScope()
        // 会话已结束，解锁以便在同一份会话上重新启动（restart 走的就是这条路）
        launchGuard.set(false)
        // Android 窗口不再有临时文件（banner 走参数、pts 走 stderr），
        // 只需清掉指向 helper 的引用；helper 本身保留（65 KB，重装没必要）。
        localLauncher = null
        reportedPty = null
        appliedCols = -1
        appliedRows = -1
        // 每个会话一套临时文件，窗口开开关关会一直堆积，这里顺手清掉。
        // banner 在 rootfs 内，路径由本会话生成，一并删除（下次启动会重写）。
        runCatching { ptyFile.delete() }
        runCatching { bootstrapFile.delete() }
        runCatching { launchFile.delete() }
        runCatching {
            File(LinuxEnvStore.rootfs(context, distro), bannerPath.trimStart('/')).delete()
        }
    }

    private fun releaseScope() {
        runCatching { stdin?.close() }
        stdin = null
        process = null
        running = false
        pumps.forEach { it.cancel() }
        pumps = emptyList()
        scope?.cancel()
        scope = null
    }

    /**
     * launch 脚本（root 身份执行）。
     *
     * 只做三件事：找 busybox、把需要的东西挂进 rootfs、把 pty 交给 bootstrap。
     * 之所以要单独一个文件而不是把这一串塞进 `su -c "..."`：命令层层嵌套
     * （su → sh → unshare → sh → script → sh）之后引号会失控，脚本文件最稳。
     */
    private fun writeLauncher(
        rootfs: String,
        busyboxCandidates: List<String>,
        bootstrapPath: String,
    ): File {
        val candidates = busyboxCandidates.joinToString(" ") { sq(it) }
        val text = """
            #!/system/bin/sh
            # 以 root 运行：解析 busybox、准备挂载，然后把 pty 交给 bootstrap。
            R=${sq(rootfs)}
            BB=""
            for c in $candidates; do
              if [ -x "${'$'}c" ]; then BB="${'$'}c"; break; fi
            done
            if [ -z "${'$'}BB" ]; then BB=$(command -v busybox 2>/dev/null); fi
            if [ -z "${'$'}BB" ]; then
              echo "no-busybox" >&2
              exit 127
            fi

            # 挂载与 pty 必须在私有 mount namespace 里做：这些 bind 若留在宿主
            # 命名空间，命令结束也不会消失（LinuxRuntime 里记过同一个坑：残留挂载
            # 会让系统的存储统计一路穿透到整个外部存储）。
            exec ${'$'}BB unshare -m --propagation private ${'$'}BB sh -c '
              R="$1"; BB="$2"; BS="$3"
              ${'$'}BB mount -t proc proc "${'$'}R/proc" 2>/dev/null
              ${'$'}BB umount "${'$'}R/dev" 2>/dev/null
              # 必须 rbind：单层 bind 不会带进 /dev/pts 与 /dev/ptmx
              ${'$'}BB mount --rbind /dev "${'$'}R/dev" 2>/dev/null
              ${'$'}BB umount "${'$'}R/sys" 2>/dev/null
              ${'$'}BB mount --rbind /sys "${'$'}R/sys" 2>/dev/null
              if [ -d /storage/emulated/0 ]; then
                ${'$'}BB mkdir -p "${'$'}R/storage/emulated/0" 2>/dev/null
                ${'$'}BB umount "${'$'}R/storage/emulated/0" 2>/dev/null
                ${'$'}BB mount --bind /storage/emulated/0 "${'$'}R/storage/emulated/0" 2>/dev/null
              fi
              # setsid 让整条链路独立成会话：这样 kill script 时不会被 su 的
              # 进程组关系牵连，也避免它自己成为控制终端的前台组
              exec ${'$'}BB setsid ${'$'}BB script -qfc "${'$'}BS" /dev/null
            ' sh "${'$'}R" "${'$'}BB" $bootstrapPath
        """.trimIndent() + "\n"
        launchFile.writeText(text)
        chmodExecutable(launchFile)
        return launchFile
    }

    /**
     * bootstrap 脚本（由 `script` 在自己的 pty 上执行，此时已 chroot 之前）。
     *
     * 注意此刻仍是**宿主** shell：所以只用 Android 自带命令，真正需要 rootfs
     * 的只有最后一行 chroot。`SHELL` 必须显式给 `/bin/bash`——`script` 会
     * `exec $SHELL`，默认取的是宿主的 `/system/bin/sh`，在 chroot 里不存在，
     * 会报 "failed to execute /system/bin/sh"（实测踩过）。
     */
    /**
     * 写一份登录 banner 到 rootfs，返回它在 rootfs 内的路径。
     *
     * 为什么落成文件而不是把命令拼进 bootstrap：后者要在
     * `su -c` → `unshare` → `script -qfc` 三层嵌套里做引号转义，
     * 容易整条失效且难以排查；落成文件后转义只发生一次，用户也能自己改。
     *
     * 内容是 ASCII art 加一行环境信息，交给 rootfs 内的 sh 打印，
     * 因此属于**终端的真实输出**——可以滚进回滚缓冲、可以长按选中复制，
     * 这与把文字画在 Compose 层上完全不同（那样不可选中，也会被画面盖住）。
     */
    private fun writeBanner(): String {
        val file = File(LinuxEnvStore.rootfs(context, distro), bannerPath.trimStart('/'))
        runCatching { file.parentFile?.mkdirs() }
        // 逐行 append，避免把整段脚本塞进一个字符串后转义难读。
        // 这里有两层转义：Kotlin 字符串一层、printf 的格式串一层。
        val text = buildString {
            append("#!/bin/sh\n")
            append("printf '\\n'\n")
            // 每行单独一条 echo。
            //
            // 两个坑都在这里：
            // ① 早先用 `printf '%s\\n' '...'` 拼行，收尾写的是 Kotlin 的
            //    "'\\n" —— 那是**反斜杠 + n 两个字面字符**，不是换行。
            //    于是所有行被挤成一行，shell 把后面的 `printf %s\n` 当成参数，
            //    屏幕上直接打出 `%s\n`（实测截图）。
            // ② art 里带 `\\` 时还要过 shell 一遍解析，容易与 Kotlin 的转义叠错。
            // 改用 echo 一行一条，art 里也不再用反斜杠，彻底消除二次解析。
            TerminalBanner.ART.forEach { line ->
                append("echo '").append(line).append("'\n")
            }
            append("printf '\\n'\n")
            append("printf '  \\033[1;32mYutu\\033[0m \\033[2mAgt 终端\\033[0m\\n'\n")
            // 只用 rootfs 里一定存在的 sed：部分极简 rootfs 没有 tr 或 head，
            // 用它们会让这一行静默消失（实测）。
            append("name=${'$'}(sed -n 's/^PRETTY_NAME=\"\\(.*\\)\"${'$'}/\\1/p' /etc/os-release 2>/dev/null)\n")
            append("[ -n \"${'$'}name\" ] && printf '  \\033[2m%s\\033[0m\\n' \"${'$'}name\"\n")
            append("printf '\\n'\n")
        }
        file.writeText(text)
        // 必须给执行权限：bootstrap 里用 `sh <path>` 调用，但下次可能改成直接执行；
        // 更重要的是 chroot 内以 root 运行时，无执行位的脚本在某些挂载下会被拒。
        chmodExecutable(file)
        return bannerPath
    }

    /**
     * 每行输出前面插入的空格数。
     *
     * ## 为什么需要它
     *
     * xterm 按 `clientWidth / cellWidth` 算列数，**没有余量**。而图标字体等
     * 原生渲染的字形可能比等宽单元格略宽，边界上的字符于是被裁掉半截
     * （实测：终端最左侧的字有轻微截断感）。
     *
     * 在源头上补几个空格，等于把整块文本整体右移一点点：既避开裁切，
     * 也不会真的「移很多」——默认 1 列，视觉上几乎只是让首字符离开边缘。
     * 用户要求「往右移一点点，不要非常多」，就是它。
     */
    private fun writeBootstrap(workingDir: String): File {
        val bannerPath = writeBanner()
        val rootfs = LinuxEnvStore.rootfs(context, distro).absolutePath
        val inner = rootfs.trimEnd('/') + "/" + workingDir.trim('/')
        val text = """
            #!/system/bin/sh
            # 已经在 script 分配的 pty 上：先把尺寸定下来（script 用的是分配那一刻的大小），
            # 之后浏览器/界面改变尺寸时由宿主 stty -F 现改。
            stty rows $rows cols $cols 2>/dev/null
            tty > ${sq(ptyFile.absolutePath)} 2>/dev/null
            export SHELL=/bin/bash
            export HOME=/root
            export TERM=xterm-256color
            export LANG=C.UTF-8
            export LC_ALL=C.UTF-8
            export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
            cd ${sq(inner)} 2>/dev/null || cd ${sq(rootfs)} 2>/dev/null
            # 先进 rootfs 打 banner，再起交互 shell。
            # 注意：这是 Kotlin 的模板字符串，shell 变量必须写成 \${'$'}var，
            # 否则会被当成 Kotlin 插值（注释里也一样，同样会被解析）。
            R=${sq(rootfs)}
            # 先把 banner 路径拼出来：Kotlin 侧只插值 $bannerPath，R 交给 shell，
            # 这样目录路径带引号、文件名保持字面量，两种转义互不干扰。
            B="${'$'}R$bannerPath"
            [ -f "${'$'}B" ] && /system/bin/chroot "${'$'}R" /bin/sh "$bannerPath" 2>/dev/null
            exec /system/bin/chroot ${sq(rootfs)} /bin/bash -i
        """.trimIndent() + "\n"
        bootstrapFile.writeText(text)
        chmodExecutable(bootstrapFile)
        return bootstrapFile
    }

    /** chmod 755。`File.setExecutable` 在 app 私有目录常常静默失败，直接走 shell。 */
    private fun chmodExecutable(file: File) {
        runCatching { file.setExecutable(true, false) }
        runCatching {
            ProcessBuilder("/system/bin/sh", "-c", "chmod 755 " + sq(file.absolutePath))
                .redirectErrorStream(true)
                .start()
                .waitFor(3, TimeUnit.SECONDS)
        }
    }

    private fun sq(text: String): String = "'" + text.replace("'", "'\\''") + "'"
}
