package com.mcp.toolbox.ui.linux

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * SSH 远程访问的开关与启停。
 *
 * ## 为什么需要「运行」这一维
 *
 * `LinuxComponent.SSH` 只回答「sshd **装了没**」，而装上不等于可用 —— 服务端
 * 没跑起来时 22 端口根本不监听（用户实测：装了 openssh-server，检测页显示
 * 「已安装」，但 `ss -tlnp` 没有 22 端口）。所以这里独立管「跑没跑」。
 *
 * ## 进程模型（已在设备上实测确认）
 *
 * sshd 必须在 `unshare -m` **之外**启动，与 [LinuxRuntime.exec] 的做法相反：
 * exec 里的 `unshare -m` 会给每次执行一个私有挂载视图，**命令一结束命名空间就
 * 销毁、挂载随之消失**。sshd 是常驻进程，它需要的 `/dev`、`/proc`、`/sys`
 * 必须在它整个生命周期内都挂着，所以这里自己挂、不 unshare。
 *
 * 用 `setsid` 让 sshd 脱离调用它的 shell：否则父 shell 一退出，sshd 可能被
 * 一起带走。启动与停止都靠 `su -c`（需要 root，与 Linux 环境本身的要求一致）。
 *
 * 实测结果：`chroot <rootfs> /usr/sbin/sshd -D` 输出
 * `Server listening on 0.0.0.0 port 22`，`/proc/net/tcp` 里 0.0.0.0:22 为 LISTEN。
 *
 * ## 已知局限（如实告知用户，不粉饰）
 *
 * 这里**不常驻**：App 进程被系统回收、或设备重启后 sshd 会停，需要用户再打开。
 * 做常驻要引入前台服务与开机自启，属于另一件事，没有需求前不做。
 */
object SshRemote {

    private const val PREFS = "ssh-remote"
    private const val KEY_ENABLED = "enabled"

    /** 端口固定 22：sshd 默认读 /etc/ssh/sshd_config，这里不额外传 -p。 */
    private const val PORT = 22

    private val _enabled = MutableStateFlow(false)

    /** 用户是否开启了「SSH 远程访问」。 */
    val enabled: StateFlow<Boolean> = _enabled

    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        _enabled.value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)
        loaded = true
    }

    /**
     * 打开/关闭开关。
     *
     * 打开时**顺便拉起 sshd**（把「想要」落到「真的在跑」）；关掉时停止进程。
     * 若此刻 sshd 还没装，只记录意愿并返回 false，由调用方提示用户先安装 ——
     * 不假装成功。
     *
     * @return 是否已按意愿生效（true 表示 sshd 已在跑 / 已停止）。
     */
    suspend fun set(context: Context, on: Boolean, distro: LinuxDistro): Boolean {
        _enabled.value = on
        loaded = true
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, on).apply()
        return if (on) start(context, distro) else stop(context).also { true }
    }

    /** sshd 是否已在跑（按 22 端口是否处于监听判断，不依赖某个具体进程名）。 */
    suspend fun isRunning(context: Context, distro: LinuxDistro): Boolean = withContext(Dispatchers.IO) {
        val out = run(context, LISTEN_SCRIPT)
        out.contains("LISTEN") || out.contains("0016")
    }

    /** 拉起 sshd。返回是否确实起来了（以端口监听为准，不看命令退出码）。 */
    suspend fun start(context: Context, distro: LinuxDistro): Boolean = withContext(Dispatchers.IO) {
        if (!LinuxEnvStore.isInstalled(context, distro)) return@withContext false
        if (!sshdPresent(context, distro)) return@withContext false
        if (isRunning(context, distro)) return@withContext true
        run(context, startScript(context, distro))
        // 等它把端口绑上；sshd 启动本身很快，但 chroot + 挂载有几百毫秒开销
        repeat(6) {
            if (isRunning(context, distro)) return@withContext true
            Thread.sleep(250)
        }
        false
    }

    /** 停止 sshd。返回是否已确认停掉（端口不再监听）。 */
    suspend fun stop(context: Context): Boolean = withContext(Dispatchers.IO) {
        run(context, "for p in \$(pidof sshd); do kill \"\$p\" 2>/dev/null; done")
        repeat(6) {
            if (!isRunningRaw(context)) return@withContext true
            Thread.sleep(200)
        }
        false
    }

    /** sshd 可执行文件是否就位。能力判据与 [LinuxComponent.SSH.probe] 保持一致。 */
    private suspend fun sshdPresent(context: Context, distro: LinuxDistro): Boolean =
        LinuxChecker.isInstalled(context, distro, LinuxComponent.SSH)

    private fun rootfsPath(context: Context, distro: LinuxDistro): String =
        LinuxEnvStore.rootfs(context, distro).absolutePath

    /**
     * 启动脚本。挂载与 [LinuxRuntime.commandLine] 保持同样的目标，但**不 unshare**。
     *
     * 用 `-D` 前台模式 + `setsid` 脱离：`sshd` 不带 `-D` 会自己 daemonize，
     * 但那样父进程立刻返回、我们也拿不到它的退出信息；`-D` 配合 setsid 更可控。
     */
    private fun startScript(context: Context, distro: LinuxDistro): String {
        val r = rootfsPath(context, distro)
        return buildString {
            append("R=").append(shellQuote(r)).append("; ")
            append("for d in dev proc sys; do ")
            append("[ -d \"\$R/\$d\" ] || mkdir -p \"\$R/\$d\" 2>/dev/null; ")
            append("mount --bind /\$d \"\$R/\$d\" 2>/dev/null; ")
            append("done; ")
            // sshd 需要 /run/sshd 存在且权限正确，安装脚本建过，这里兜一次
            append("mkdir -p \"\$R/run/sshd\" 2>/dev/null; ")
            // setsid 脱离当前会话；重定向掉三个流，否则调用方的管道会一直挂着
            append("setsid chroot \"\$R\" /usr/sbin/sshd -D >/dev/null 2>&1 </dev/null & ")
        }
    }

    private const val LISTEN_SCRIPT =
        "awk '\$4==\"0A\" && \$2 ~ /:0016\$/ {print \"LISTEN\"}' /proc/net/tcp | head -n 1"

    private suspend fun isRunningRaw(context: Context): Boolean =
        run(context, LISTEN_SCRIPT).contains("LISTEN")

    /** 在 root shell 里跑一段脚本，返回合并输出。失败不抛，交给调用方按「没成功」处理。 */
    private fun run(context: Context, script: String): String = runCatching {
        val process = ProcessBuilder("/system/bin/sh", "-c", "su -c " + shellQuote(script))
            .redirectErrorStream(true)
            .start()
        val text = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        text
    }.getOrDefault("")

    private fun shellQuote(text: String): String =
        "'" + text.replace("'", "'\\''") + "'"
}
