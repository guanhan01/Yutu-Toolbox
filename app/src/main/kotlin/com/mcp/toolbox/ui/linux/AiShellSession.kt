package com.mcp.toolbox.ui.linux

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 给 AI 用的持久 shell 会话。
 *
 * 为什么要它：`linux.exec` 每次都是 `sh -c` 跑完即退，没有状态。于是模型做不到
 * 「先进目录、再跑」这类最基本的事——每一步都从 `/root` 重新开始，`export` 立刻
 * 失效，`apt install` 拉起的后台进程下一句就看不见了。
 *
 * 这个类在 rootfs 里维持**一个长期存活的 `sh`**，所有命令都写进它的 stdin，
 * 因此 cwd、环境变量、后台任务都跨调用保留。
 *
 * ## 进程结构
 * ```
 * sh -c "su -c unshare -m sh <launch.sh>"     # 应用进程的直接子进程
 *  └─ unshare -m                              # 私有挂载空间
 *      └─ sh launch.sh                        # 做挂载、设环境，然后 exec
 *          └─ chroot <rootfs> /bin/sh         # 会话本体
 * ```
 *
 * `unshare -m` 不是可选项：挂载都做在私有命名空间里，会话一结束（或被杀）
 * 整套挂载随之消失。若做在宿主命名空间，命令结束也不会回收——LinuxRuntime 里
 * 记过同一个坑，残留挂载会让系统的存储统计一路穿透到整个外部存储。
 *
 * ## 命令协议（真机逐条验证过）
 *
 * 写进会话的是这样一个块：
 * ```
 * {
 *   <命令>
 * } </dev/null 2>&1; printf '\n__ETA_END_<id>__%d\n' $?
 * ```
 *
 * - **`{ }` 而不是子 shell**：`{ }` 在当前 shell 执行，`cd` / `export` 才能留下；
 *   换成 `( )` 就退化成一次性执行了。
 * - **`</dev/null` 必须加**：`cat`、`read`、`ssh` 这类会读 stdin 的命令会**吃掉
 *   后续写入的命令**，会话从此错位（实测：`cat` 之后所有命令都成为 cat 的输入，
 *   整条会话报废）。重定向到 /dev/null 后 `cat` 立刻 EOF 返回。
 * - **哨兵带唯一 id**：输出是异步的，只有独占的哨兵才能把「这次调用的输出」
 *   精确切出来，并把退出码带回来。
 * - **`2>&1`**：模型需要看到报错，否则失败时它只会拿到空输出。
 *
 * ## 已知限制：`exit`
 * `exit` 是 shell 内建，写进会话会终止整个会话。检测到后本次调用返回明确错误，
 * 并立刻重建会话，让后续调用仍可用，而不是静默失效到超时。
 */
class AiShellSession(private val context: Context, val distro: LinuxDistro) {

    companion object {
        private const val TAG = "AiShell"

        /** 单次调用默认等待上限；长任务由调用方传 timeoutMs 覆盖。 */
        const val DEFAULT_TIMEOUT_MS = 120_000L
        const val MAX_TIMEOUT_MS = 600_000L

        /** 会话建立（挂载 + chroot）实测 1~3 秒，留足余量。 */
        private const val READY_TIMEOUT_MS = 20_000L

        /** 会话输出缓冲上限。后台任务可能一直刷，必须封顶。 */
        private const val MAX_BUFFER_CHARS = 400_000

        /** 返回给模型的输出上限。 */
        private const val MAX_OUTPUT_CHARS = 60_000

        private val seq = AtomicLong()
    }

    data class Result(
        val command: String,
        val output: String,
        val exitCode: Int,
        val elapsedMs: Long,
        /** 本次调用结束后会话是否还活着（被 `exit` 终止过则已重建）。 */
        val sessionAlive: Boolean,
        val note: String? = null,
    )

    /** 一次等待的结果。 */
    sealed interface Outcome {
        /** 正常拿到哨兵。 */
        data class Done(val text: String, val exitCode: Int) : Outcome

        /** 会话结束（EOF）——通常是被 `exit` 杀了。 */
        data object Ended : Outcome

        /** 超时；会话仍活着，[text] 是到目前为止收到的输出。 */
        data class Timed(val text: String) : Outcome
    }

    /**
     * 一次会话。
     *
     * 输出由一个**常驻读线程**统一收进 [buffer]：不能每次调用各自去读同一条
     * `InputStream`——reader 会一次性读回超过哨兵的数据，属于后续（或后台任务）
     * 的字节会随 reader 一起被丢掉。
     */
    private inner class Live(val p: Process) {

        /** 命令写入端。 */
        val out: OutputStream = p.outputStream

        private val gate = Object()
        private var buffer = StringBuilder()

        /** 读完流（会话结束）后置位，等待方据此立刻返回而不是干等到超时。 */
        private var closed = false

        private val reader = Thread {
            val input: InputStream = p.inputStream
            val buf = ByteArray(8192)
            try {
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    append(String(buf, 0, n, Charsets.UTF_8))
                }
            } catch (t: Throwable) {
                Log.d(TAG, "reader ended: ${t.message}")
            } finally {
                synchronized(gate) {
                    closed = true
                    gate.notifyAll()
                }
            }
        }.apply { isDaemon = true }

        private fun append(chunk: String) {
            synchronized(gate) {
                buffer.append(chunk)
                // 封顶时丢最旧的：后面的人要看的是最新进展
                if (buffer.length > MAX_BUFFER_CHARS) {
                    buffer.delete(0, buffer.length - MAX_BUFFER_CHARS)
                }
                gate.notifyAll()
            }
        }

        /** 当前缓冲里的字符数（诊断用）。 */
        fun pending(): Int = synchronized(gate) { buffer.length }

        /** 等 [mark] 之后出现 `marker<exitCode>`。 */
        fun await(marker: String, from: Int, timeoutMs: Long): Outcome {
            val deadline = System.currentTimeMillis() + timeoutMs
            synchronized(gate) {
                while (true) {
                    val idx = buffer.indexOf(marker, from)
                    if (idx >= 0) {
                        val after = buffer.substring(idx + marker.length)
                        val nl = after.indexOf('\n')
                        if (nl >= 0) {
                            val code = after.take(nl).trim().toIntOrNull()
                            if (code != null) {
                                val text = buffer.substring(from, idx)
                                // 只丢掉「已返回的部分」，哨兵之后的内容留着：
                                // 那是后台任务在此期间的真实输出，下次调用应能看到
                                buffer.delete(0, idx + marker.length + nl + 1)
                                return Outcome.Done(text.trimEnd(), code)
                            }
                        }
                    }
                    if (closed) return Outcome.Ended
                    val left = deadline - System.currentTimeMillis()
                    if (left <= 0) {
                        val text = if (from < buffer.length) buffer.substring(from) else ""
                        return Outcome.Timed(text.trimEnd())
                    }
                    runCatching { gate.wait(minOf(left, 100L)) }
                }
            }
        }

        fun start() = reader.start()
    }

    @Volatile
    private var live: Live? = null

    /** 串行化：会话是单条 stdin 流，两个调用同时写会交错。 */
    private val lock = Any()

    private val launchFile: File get() = File(context.cacheDir, "ai-shell-${distro.name}.sh")
    private val pidPath: File get() = File(context.cacheDir, "ai-shell-${distro.name}.pid")

    val running: Boolean get() = live?.p?.isAlive == true

    /**
     * 执行一条命令，复用会话。
     *
     * [workdir] 非空时先切到该目录再执行：用 `cd X && cmd` 而不是 `cd X; cmd`，
     * 切目录失败就不会误跑在别的目录里。
     */
    suspend fun run(
        command: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        workdir: String? = null,
    ): Result = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val started = System.currentTimeMillis()
            val timeout = timeoutMs.coerceIn(1_000L, MAX_TIMEOUT_MS)

            guardCommand(command)?.let { reason ->
                return@synchronized Result(
                    command = command,
                    output = reason,
                    exitCode = -1,
                    elapsedMs = System.currentTimeMillis() - started,
                    sessionAlive = running,
                )
            }

            val boot = ensureStarted()
            if (boot != null) {
                return@synchronized Result(
                    command = command,
                    output = boot,
                    exitCode = -1,
                    elapsedMs = System.currentTimeMillis() - started,
                    sessionAlive = false,
                )
            }

            val session = live ?: return@synchronized Result(
                command = command,
                output = "错误：会话不可用。",
                exitCode = -1,
                elapsedMs = System.currentTimeMillis() - started,
                sessionAlive = false,
            )

            val id = "e" + seq.incrementAndGet()
            val marker = "__ETA_END_${id}__"
            // 起点恒为 0：缓冲里只保留**尚未消费**的字节（取到哨兵时会把已返回的
            // 部分删掉），所以从头读就是「模型还没见过的全部输出」。
            //
            // 不要用「本次调用开始时缓冲有多长」当起点：那样两次调用之间到达的
            // 后台任务输出（`apt install` 的进度、`nohup` 的日志）会落在两次
            // 起点之间，谁都不要它，就被永久丢掉了。
            val from = 0
            val payload = buildString {
                append("{\n")
                if (!workdir.isNullOrBlank()) {
                    append("cd ").append(singleQuote(workdir)).append(" && ")
                }
                append(command)
                append("\n} </dev/null 2>&1; printf '\\n$marker%d\\n' $?\n")
            }

            try {
                session.out.write(payload.toByteArray(Charsets.UTF_8))
                session.out.flush()
            } catch (t: Throwable) {
                Log.w(TAG, "write failed", t)
                killNow()
                return@synchronized Result(
                    command = command,
                    output = "错误：会话写入失败（${t.message}）。已结束该会话，下次调用会重建。",
                    exitCode = -1,
                    elapsedMs = System.currentTimeMillis() - started,
                    sessionAlive = false,
                )
            }

            when (val outcome = session.await(marker, from, timeout)) {
                is Outcome.Done -> Result(
                    command = command,
                    output = cutForModel(outcome.text),
                    exitCode = outcome.exitCode,
                    elapsedMs = System.currentTimeMillis() - started,
                    sessionAlive = true,
                )

                // 会话被 exit 杀掉：必须明确告知并立刻重建，否则后续每次调用都会
                // 「写入成功但永远收不到输出」，整体卡到超时
                Outcome.Ended -> {
                    killNow()
                    ensureStarted()
                    Result(
                        command = command,
                        output = "错误：会话已结束——命令里的 `exit` / `logout` 是 shell 内建，" +
                            "会连同 cwd 与环境变量一起终止整个持久会话。已自动重建一个新会话，" +
                            "可重试；要拿某个退出码请用 `(exit N)`，要结束子 shell 请用 `sh -c 'exit'`。",
                        exitCode = -1,
                        elapsedMs = System.currentTimeMillis() - started,
                        sessionAlive = false,
                        note = "会话已重建",
                    )
                }

                // 超时不杀会话：apt install 这类长任务还在跑，杀掉等于丢用户的进度
                is Outcome.Timed -> Result(
                    command = command,
                    output = "命令在 ${timeout / 1000} 秒内没有结束，**仍在后台继续运行**。" +
                        "会话与任务都保留着；这段输出也**没有**被丢弃，下次调用会连同新输出一起给你。" +
                        "可选：用更大的 timeoutMs 重试（注意命令会再执行一次，只对幂等命令这么做）、" +
                        "把任务改成后台执行、或稍后再来看输出。\n\n" +
                        "目前收到的输出：\n" + cutForModel(outcome.text),
                    exitCode = -1,
                    elapsedMs = System.currentTimeMillis() - started,
                    sessionAlive = true,
                    note = "已超时，会话保留",
                )
            }
        }
    }

    /**
     * 拦住会破坏会话的命令。
     *
     * 只拦「裸 exit」：单独一行、或作为最后一条独立语句。带引号、放子 shell 里的
     * `(exit 0)`、`sh -c 'exit'` 都是安全的，一刀切会把正常用法也拦掉。
     */
    private fun guardCommand(command: String): String? {
        val trimmed = command.trim()
        if (trimmed == "exit" || trimmed == "logout" ||
            Regex("""(^|[;&|]\s*|\n\s*)(exit|logout)\s*;?\s*$""").containsMatchIn(trimmed)
        ) {
            return "错误：`exit` / `logout` 是 shell 内建，会终止持久会话（cwd、环境变量、" +
                "后台任务全部随之丢失）。\n" +
                "· 想拿到某个退出码：用 `(exit N)`\n" +
                "· 想结束子 shell：用 `sh -c 'exit'`\n" +
                "· 真正要关掉会话请调用 linux.shell_kill 工具。"
        }
        return null
    }

    /**
     * 确保会话在跑；返回非 null 表示失败原因。
     */
    private fun ensureStarted(): String? {
        if (running) return null
        if (!LinuxEnvStore.isInstalled(context, distro)) {
            return "错误：Linux 环境尚未安装，请先在「Linux 工具」页下载安装 ${distro.displayName}。"
        }

        val rootfs = LinuxEnvStore.rootfs(context, distro)
        val launch = writeLauncher(rootfs)
        pidPath.delete()

        return runCatching {
            // 与 TerminalSession 同一套链路：su -c 里套 unshare -m，再由 chroot 起 sh。
            // 不需要 pty：命令都是整块写进去的文本。
            val bb = busyboxPath()
            val inner = "printf '%s' \"$$\" > ${singleQuote(pidPath.absolutePath)}; " +
                "exec ${singleQuote(bb)} unshare -m /system/bin/sh ${singleQuote(launch.absolutePath)}"
            val p = ProcessBuilder("/system/bin/sh", "-c", "su -c " + singleQuote(inner))
                .redirectErrorStream(false)
                .start()

            val l = Live(p)
            live = l
            l.start()

            // 启动阶段的 stderr（找不到 busybox、挂载失败）必须读掉：
            // 不读会填满管道把会话卡死，留尾巴用于报错文案
            drainStderr(p)

            val ready = waitForReady(p, l)
            if (ready != null) {
                killNow()
                return ready
            }
            null
        }.getOrElse { t ->
            Log.w(TAG, "start failed", t)
            killNow()
            "错误：无法启动 shell 会话（${t.message ?: t::class.simpleName}）。需要 Root 权限。"
        }
    }

    /**
     * 等会话可用：进程存活 + 能跑通一次哨兵往返。
     *
     * 只等文件出现是不够的——挂载做完不代表 shell 已经把 stdin 接上，
     * 直接发真命令偶尔会丢。跑一条 `:` 探针把链路验穿。
     */
    private fun waitForReady(p: Process, session: Live): String? {
        val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!p.isAlive) return "错误：会话进程提前退出（可能没有 Root 权限，或 rootfs 损坏）。"
            if (pidPath.isFile) break
            Thread.sleep(50)
        }

        val id = "r" + seq.incrementAndGet()
        val marker = "__ETA_END_${id}__"
        val from = 0
        return runCatching {
            session.out.write(
                ("{\n:\n} </dev/null 2>&1; printf '\\n$marker%d\\n' $?\n").toByteArray(Charsets.UTF_8),
            )
            session.out.flush()
            when (val o = session.await(marker, from, 8_000L)) {
                is Outcome.Done -> null
                Outcome.Ended -> "错误：会话启动后立刻结束（chroot 失败？）。"
                is Outcome.Timed -> "错误：会话在 ${READY_TIMEOUT_MS / 1000} 秒内未就绪。"
            }
        }.getOrElse { "错误：会话就绪探测失败（${it.message}）。" }
    }

    /** 把启动阶段的 stderr 读掉（否则管道写满会卡住 su）。 */
    private fun drainStderr(p: Process) {
        Thread {
            runCatching { p.errorStream.bufferedReader().forEachLine { } }
        }.apply { isDaemon = true }.start()
    }

    /** 结束会话。关 stdin 让 sh 正常退出；超时再按进程组兜底清理。 */
    suspend fun kill() = withContext(Dispatchers.IO) { killNow() }

    private fun killNow() {
        val session = live
        live = null
        runCatching { session?.out?.close() }
        val p = session?.p
        if (p != null) {
            // 正常路径：sh 读到 EOF 干净退出（实测无残留进程、无残留挂载）
            val ended = runCatching { p.waitFor(2, TimeUnit.SECONDS) }.getOrDefault(false)
            if (!ended) {
                runCatching {
                    val pgid = pidPath.readText().trim()
                    if (pgid.isNotEmpty()) {
                        ProcessBuilder("/system/bin/sh", "-c", "kill -9 -$pgid 2>/dev/null")
                            .start().waitFor(2, TimeUnit.SECONDS)
                    }
                }
                runCatching { p.destroyForcibly() }
            }
        }
        pidPath.delete()
    }

    /**
     * 组装 launch 脚本。
     *
     * 挂载与环境变量照着 [LinuxRuntime.commandLine] 的实测结论复刻，区别是它是
     * 「跑一次」的写法，这里要 exec 到 sh 并长期驻留。
     */
    private fun writeLauncher(rootfs: File): File {
        launchFile.writeText(buildLauncherScript(rootfs.absolutePath))
        runCatching {
            ProcessBuilder(
                "/system/bin/sh",
                "-c",
                "chmod 755 " + singleQuote(launchFile.absolutePath),
            ).start().waitFor(3, TimeUnit.SECONDS)
        }
        return launchFile
    }

    /** 首选 busybox；launch 脚本里还会依次回退到其它位置。 */
    private fun busyboxPath(): String {
        val candidates = listOf(
            "/data/adb/ksu/bin/busybox",
            "/data/adb/magisk/busybox",
            "/data/adb/ap/bin/busybox",
            "/system/xbin/busybox",
            "/system/bin/busybox",
        )
        // /data/adb 只有 root 能读，这里探不到也没关系——launch 脚本会再找一遍
        candidates.forEach { path -> if (File(path).exists()) return path }
        return candidates.first()
    }

    private fun singleQuote(text: String): String = "'" + text.replace("'", "'\\''") + "'"

    /** 模型只看尾部：长构建的真正结论在最后，头部噪音没有价值。 */
    private fun cutForModel(text: String): String {
        if (text.length <= MAX_OUTPUT_CHARS) return text
        val half = MAX_OUTPUT_CHARS / 2
        return "…（输出过长，中间省略 ${text.length - half * 2} 字符）…\n" +
            text.take(half) + "\n…\n" + text.takeLast(half)
    }
}

/**
 * 生成会话启动脚本。
 *
 * 抽成顶层纯函数是为了能被单测直接检查：这段脚本**只有真机跑起来才知道对不对**，
 * 而它里面全是 `$` 开头的 shell 变量，写进 Kotlin 字符串里极易被当成模板插值
 * （实测踩过：整段 `$R` / `$d` / `$BB` 全部变成 Kotlin 报错，或者悄悄插成空串）。
 * 现在用测试盯着它，改脚本时不会再静默写坏。
 *
 * 参数只用字符串，不依赖 Context / File，因此可以在 JVM 单测里跑。
 */
internal fun buildLauncherScript(rootfsPath: String): String = buildString {
    append("#!/system/bin/sh\n")
    append("R='").append(rootfsPath.replace("'", "'\\''")).append("'\n")

    // busybox 必须在 root 身份下找：/data/adb 是 root-only，
    // 应用进程 File.canExecute 恒为 false（LinuxRuntime 里记过同一件事）
    append("BB=\"\"\n")
    append("for c in /data/adb/ksu/bin/busybox /data/adb/magisk/busybox ")
    append("/data/adb/ap/bin/busybox /system/xbin/busybox /system/bin/busybox; do\n")
    append("  [ -x \"${'$'}c\" ] && { BB=\"${'$'}c\"; break; }\n")
    append("done\n")
    append("[ -n \"${'$'}BB\" ] || BB=${'$'}(command -v busybox 2>/dev/null)\n")

    // 先卸再挂：/proc/mounts 里记的是解析后的真实路径（/data/user/0/...），
    // 与 $R（/data/data/...）对不上，「已挂载就跳过」永远失效，会越叠越多
    append("for d in dev proc sys; do\n")
    append("  [ -d \"${'$'}R/${'$'}d\" ] || mkdir -p \"${'$'}R/${'$'}d\" 2>/dev/null\n")
    append("  umount \"${'$'}R/${'$'}d\" 2>/dev/null\n")
    append("  if [ \"${'$'}d\" = dev ] && [ -n \"${'$'}BB\" ]; then\n")
    // /dev 必须 rbind：单层 bind 带不进 /dev/pts 与 /dev/ptmx
    append("    ${'$'}BB mount --rbind /dev \"${'$'}R/dev\" 2>/dev/null\n")
    append("  else\n")
    append("    mount --bind \"/${'$'}d\" \"${'$'}R/${'$'}d\" 2>/dev/null\n")
    append("  fi\n")
    append("done\n")

    // 共享存储：模型读写用户文件的主要入口，两个常见路径都挂上
    append("for s in sdcard storage/emulated/0; do\n")
    append("  [ -d \"${'$'}R/${'$'}s\" ] || mkdir -p \"${'$'}R/${'$'}s\" 2>/dev/null\n")
    append("  umount \"${'$'}R/${'$'}s\" 2>/dev/null\n")
    append("  mount --bind /storage/emulated/0 \"${'$'}R/${'$'}s\" 2>/dev/null\n")
    append("done\n")

    // DNS：8.8.8.8 / 1.1.1.1 在境内基本不通，沿用系统当前 DNS
    append("rm -f \"${'$'}R/etc/resolv.conf\" 2>/dev/null\n")
    // net.dns1 在部分设备上为空（实测本机就是空的），再试 net.dns2；
    // 两个都拿不到才退回公共 DNS（223.5.5.5 境内可用）。
    append("D=${'$'}(getprop net.dns1 2>/dev/null)\n")
    append("[ -n \"${'$'}D\" ] || D=${'$'}(getprop net.dns2 2>/dev/null)\n")
    append("[ -n \"${'$'}D\" ] || D=223.5.5.5\n")
    append("printf 'nameserver %s\nnameserver 223.5.5.5\nnameserver 119.29.29.29\n' ")
    append("\"${'$'}D\" > \"${'$'}R/etc/resolv.conf\" 2>/dev/null\n")

    // chroot 不继承环境变量，必须显式导出。
    // 实测漏掉 PATH 时 sleep/cat/head 全部 not found，会话形同废掉。
    append("export HOME=/root\n")
    append("export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\n")
    // TMPDIR 必须显式设成 rootfs 内的 /tmp。
    //
    // 不设的话会**继承宿主的 TMPDIR**，而它指向应用自己的缓存目录，在 chroot 里
    // 并不存在。后果不是小毛病：apt 装包时 postinst 里的 mktemp 直接失败
    // （实测 openssh-server 就停在这里，包卡在半配置状态 iF，连 sshd_config 和
    // sshd 用户都没建出来），而且报错只在 dpkg 的输出里，很容易被当成「装好了」。
    // 凡是 postinst 用到 mktemp / debconf 的包都会中招，所以装包能力整体受影响。
    append("export TMPDIR=/tmp\n")
    // 没有终端可用，debconf 别去试 Dialog / Readline / Teletype（实测会各报一次错）
    append("export DEBIAN_FRONTEND=noninteractive\n")
    // TERM=dumb：模型不需要彩色，避免 ANSI 转义把输出读花
    append("export TERM=dumb LANG=C.UTF-8 LC_ALL=C.UTF-8\n")
    // cd 必须在 chroot **内部**再执行一次。
    //
    // chroot 不会改变工作目录，而宿主视角的 /root 在新根里并不存在，结果是
    // shell 起来时 pwd=/(LinuxRuntime 的 commandLine 里记过同一个坑)。
    // 在外层 cd 是无效的——这一点在真机测试里被逮到过：
    // 新会话的第一步命令看到的不是 /root。
    //
    // sh -c '...' 里最后 exec /bin/sh：进程被替换，cwd 保留，
    // 不会多留一层 shell。
    append("exec /system/bin/chroot \"${'$'}R\" /bin/sh -c ")
    append("'cd /root 2>/dev/null || cd /; exec /bin/sh'\n")
}

/** 单引号包裹，内部单引号转义。与类内的实现保持一致。 */
internal fun shellSingleQuote(text: String): String = "'" + text.replace("'", "'\\''") + "'"
