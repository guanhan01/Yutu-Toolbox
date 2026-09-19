package com.mcp.toolbox.ui.linux

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 在 PRoot 里执行命令。
 *
 * PRoot 用 `-r` 指定 rootfs，通过 `-b` 把宿主目录绑进去；`-0` 伪装成 root 身份，
 * 这样包管理器与多数工具不会因权限拒绝而报错。
 */
object LinuxRuntime {

    private const val TIMEOUT_MS = 120_000L

    /** 一次执行的结果。 */
    data class Output(
        val command: String,
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
        val elapsedMs: Long,
    ) {
        val ok: Boolean get() = exitCode == 0
        val combined: String
            get() = buildString {
                if (stdout.isNotBlank()) append(stdout.trim())
                if (stderr.isNotBlank()) {
                    if (isNotEmpty()) append("\n")
                    append(stderr.trim())
                }
            }.ifBlank { "(无输出)" }
    }

    /** 用 Root 模式时借系统自带的 chroot，不需要额外二进制。 */
    private fun busyboxPath(): String? = listOf(
        "/data/adb/ksu/bin/busybox",
        "/data/adb/magisk/busybox",
        "/data/adb/ap/bin/busybox",
        "/system/bin/busybox",
    ).firstOrNull { File(it).canExecute() }

    /** 环境是否可用：rootfs 就位即可（命令走系统 chroot）。 */
    fun isReady(context: Context, distro: LinuxDistro): Boolean {
        if (!LinuxEnvStore.isInstalled(context, distro)) return false
        // PRoot 兜底：rootfs + 自备 proot 也能跑
        return LinuxEnvStore.prootBinary(context).isFile || busyboxPath() != null
    }

    /** 当前用的是哪种执行方式。 */
    fun modeOf(context: Context, distro: LinuxDistro): String =
        if (LinuxEnvStore.prootBinary(context).isFile) "PRoot" else "chroot"

    /**
     * 组装执行命令行。
     *
     * 优先用系统 chroot（需要 Root）：把 rootfs 当根，绑定 /dev、/proc、/sys
     * 与共享存储，再进 /bin/sh。chroot 由系统自带，不依赖下载任何二进制。
     */
    private fun commandLine(
        context: Context,
        distro: LinuxDistro,
        workingDir: String,
        command: String,
    ): List<String> {
        val rootfs = LinuxEnvStore.rootfs(context, distro).absolutePath

        // 有 Root：su + chroot，绑定挂载在 chroot 之前用 mount --bind 完成
        val busybox = busyboxPath()
        if (busybox != null) {
            val script = buildString {
                append("R=").append(rootfs).append("; ")
                append("for d in dev proc sys; do ")
                append("[ -d \"\$R/\$d\" ] || mkdir -p \"\$R/\$d\"; ")
                // 已挂载就跳过，避免重复挂载
                append("mountpoint -q \"\$R/\$d\" || mount --bind /\$d \"\$R/\$d\" 2>/dev/null; ")
                append("done; ")
                append("[ -d \"\$R/sdcard\" ] || mkdir -p \"\$R/sdcard\"; ")
                append("mountpoint -q \"\$R/sdcard\" || mount --bind /storage/emulated/0 \"\$R/sdcard\" 2>/dev/null; ")
                append("[ -f \"\$R/etc/resolv.conf\" ] || { echo 'nameserver 8.8.8.8' > \"\$R/etc/resolv.conf\"; }; ")
                append("cd \"\$R").append(workingDir).append("\" 2>/dev/null || cd \"\$R\"; ")
                append("HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin ")
                append("TERM=xterm-256color LANG=C.UTF-8 ")
                append("chroot \"\$R\" /bin/sh -c ").append(shellQuote(command))
            }
            return listOf(busybox, "sh", "-c", "su -c " + shellQuote(script))
        }

        // 没有 Root：退回自备的 PRoot
        val proot = LinuxEnvStore.prootBinary(context)
        return listOf(
            proot.absolutePath,
            "--link2symlink", "-0",
            "-r", rootfs,
            "-w", workingDir,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", "/storage/emulated/0:/sdcard",
            "/usr/bin/env", "-i",
            "HOME=/root",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color",
            "/bin/sh", "-c", command,
        )
    }

    /** 单引号包裹，内部的单引号转义掉。 */
    private fun shellQuote(text: String): String =
        "'" + text.replace("'", "'\\''") + "'"

    /**
     * 执行一条命令。
     *
     * 非交互模式：用 `/bin/sh -c` 跑完就退出，输出整体返回。
     */
    suspend fun exec(
        context: Context,
        distro: LinuxDistro,
        command: String,
        workingDir: String = "/root",
    ): Output = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        if (!isReady(context, distro)) {
            return@withContext Output(
                command = command,
                stdout = "",
                stderr = "环境尚未就绪，请先在上一页下载并安装",
                exitCode = -1,
                elapsedMs = 0,
            )
        }

        val args = commandLine(context, distro, workingDir, command)

        runCatching {
            val process = ProcessBuilder(args)
                .redirectErrorStream(false)
                .apply { environment().clear() }
                .start()

            // 分开读，避免缓冲区写满导致互相阻塞
            val outText = StringBuilder()
            val errText = StringBuilder()
            val outThread = Thread { process.inputStream.bufferedReader().use { outText.append(it.readText()) } }
            val errThread = Thread { process.errorStream.bufferedReader().use { errText.append(it.readText()) } }
            outThread.start()
            errThread.start()

            val finished = process.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                outThread.join(1000)
                errThread.join(1000)
                return@runCatching Output(
                    command = command,
                    stdout = outText.toString(),
                    stderr = "命令超时（${TIMEOUT_MS / 1000} 秒）已被终止",
                    exitCode = -1,
                    elapsedMs = System.currentTimeMillis() - started,
                )
            }
            outThread.join(2000)
            errThread.join(2000)
            Output(
                command = command,
                stdout = outText.toString(),
                stderr = errText.toString(),
                exitCode = process.exitValue(),
                elapsedMs = System.currentTimeMillis() - started,
            )
        }.getOrElse { t ->
            Output(
                command = command,
                stdout = "",
                stderr = "${t::class.simpleName}: ${t.message}",
                exitCode = -1,
                elapsedMs = System.currentTimeMillis() - started,
            )
        }
    }

    /** 探测版本号并写入缓存文件，供检测页直接读取。 */
    suspend fun refreshVersion(
        context: Context,
        distro: LinuxDistro,
        component: LinuxComponent,
    ): String? {
        val probe = component.probe
        val args = when (component) {
            LinuxComponent.PYTHON -> "uv --version 2>/dev/null || true"
            LinuxComponent.NODE -> "node --version 2>/dev/null || true"
            LinuxComponent.SSH -> "ssh -V 2>&1 || ssh-keygen --help 2>/dev/null | head -1 || true"
            LinuxComponent.APK -> "apktool --version 2>/dev/null || true"
        }
        val result = exec(context, distro, "[ -x $probe ] && { $args; } || true")
        val line = result.combined.lineSequence().firstOrNull { it.isNotBlank() } ?: return null
        val version = line.trim()
        if (version.isBlank() || version == "(无输出)") return null
        runCatching {
            File(LinuxEnvStore.rootfs(context, distro), probe.removePrefix("/"))
                .parentFile
                ?.let { File(it, File(probe).name + ".version").writeText(version) }
        }
        return version
    }

    /** 环境自检：跑几条基础命令，确认 PRoot 真的工作。 */
    suspend fun selfCheck(context: Context, distro: LinuxDistro): List<Pair<String, String>> {
        val probes = listOf(
            "系统标识" to "cat /etc/os-release 2>/dev/null | head -2 || uname -a",
            "工作目录" to "pwd",
            "身份" to "id",
            "文件系统" to "df -h / | tail -1",
            "可用命令" to "ls /usr/bin /bin 2>/dev/null | wc -l",
        )
        return probes.map { (label, cmd) ->
            val out = exec(context, distro, cmd)
            label to out.combined
        }
    }
}
