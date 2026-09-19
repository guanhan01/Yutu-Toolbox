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

    /**
     * 环境是否可用。
     *
     * 只要 rootfs 就位即可：命令走系统自带的 chroot（需 Root），
     * 或在有自备 PRoot 时走 PRoot。是否需要 Root 由执行阶段判断，
     * 这里不做探测——`/data/adb` 属于 root，应用进程看不到。
     */
    fun isReady(context: Context, distro: LinuxDistro): Boolean =
        LinuxEnvStore.isInstalled(context, distro)

    /** 当前用的是哪种执行方式。 */
    fun modeOf(context: Context, distro: LinuxDistro): String = "chroot"

    /**
     * 在 Root shell 里探测 busybox。
     *
     * 不能在 Java 侧用 File.canExecute 判断：/data/adb 是 root:root 700，
     * 应用进程访问不到，探测结果恒为 false。
     */
    private fun probeBusybox(): String? {
        val script = buildString {
            append("for p in /data/adb/ksu/bin/busybox /data/adb/magisk/busybox ")
            append("/data/adb/apd/bin/busybox /data/adb/ap/bin/busybox ")
            append("/system/bin/busybox /system/xbin/busybox; do ")
            append("[ -x \"\$p\" ] && { echo \"\$p\"; exit 0; }; done; exit 1")
        }
        val process = ProcessBuilder("su", "-c", script)
            .redirectErrorStream(true)
            .start()
        val out = process.inputStream.bufferedReader().use { it.readText() }.trim()
        process.waitFor()
        return out.lineSequence().firstOrNull { it.startsWith("/") && it.endsWith("busybox") }
    }

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

        // 只走系统 chroot（需 Root）。
        //
        // 原本还有一条「自备 PRoot、免 Root」的路线，已废弃：Termux 源的 proot 是
        // 动态链接，依赖 libtalloc / libandroid-shmem / libtermux-exec，而镜像里
        // 没有这些库的独立包。直接执行会让 fork/exec 失败，最终只表现为一句
        // 「退出码 -1」，极难定位。mount 与 chroot 都是系统自带，不需要额外二进制。
        val script = buildString {
            append("R=").append(shellQuote(rootfs)).append("; ")
            append("for d in dev proc sys; do ")
            append("[ -d \"\$R/\$d\" ] || mkdir -p \"\$R/\$d\" 2>/dev/null; ")
            // 已挂载就跳过，避免重复挂载
            // 先卸再挂：/proc/mounts 里记的是解析后的真实路径（/data/user/0/...），
            // 与 $R（/data/data/...）对不上，原来的「已挂载就跳过」永远失效，
            // 每执行一次就叠一层挂载
            append("umount \"\$R/\$d\" 2>/dev/null; ")
            append("mount --bind /\$d \"\$R/\$d\" 2>/dev/null; ")
            append("done; ")
            append("[ -d \"\$R/sdcard\" ] || mkdir -p \"\$R/sdcard\" 2>/dev/null; ")
            append("umount \"\$R/sdcard\" 2>/dev/null; ")
            append("mount --bind /storage/emulated/0 \"\$R/sdcard\" 2>/dev/null; ")
            // 自定义挂载：把用户指定的 Android 目录绑进 rootfs
            LinuxPrefs.customMounts(context).forEach { (src, dst) ->
                val target = "\$R/" + dst.trim('/')
                append("mkdir -p \"").append(target).append("\" 2>/dev/null; ")
                append("umount \"").append(target).append("\" 2>/dev/null; ")
                append("mount --bind ").append(shellQuote(src))
                    .append(" \"").append(target).append("\" 2>/dev/null; ")
            }
            // rootfs 里常已存在（可能是空文件或 systemd 的软链），必须无条件覆盖；
            // 8.8.8.8 / 1.1.1.1 在境内基本不通，优先沿用系统当前 DNS
            append("rm -f \"\$R/etc/resolv.conf\" 2>/dev/null; ")
            append("D=\$(getprop net.dns1 2>/dev/null); ")
            append("[ -n \"\$D\" ] || D=223.5.5.5; ")
            append("printf 'nameserver %s\\nnameserver 223.5.5.5\\nnameserver 119.29.29.29\\n' \"\$D\" > \"\$R/etc/resolv.conf\" 2>/dev/null; ")
            append("cd \"\$R").append(workingDir).append("\" 2>/dev/null || cd \"\$R\" 2>/dev/null; ")
            append("HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin ")
            append("TERM=xterm-256color LANG=C.UTF-8 ")
            append("chroot \"\$R\" /bin/sh -c ").append(shellQuote(command))
        }
        return listOf("/system/bin/sh", "-c", "su -c " + shellQuote(script))
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
        timeoutMs: Long = TIMEOUT_MS,
        onLine: ((String) -> Unit)? = null,
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
            // 不要清空环境：清空后 PATH 一并消失，su / mount / chroot 这类
            // 依赖 PATH 的命令可能静默不执行，脚本却返回 0，极难排查
            val process = ProcessBuilder(args)
                .redirectErrorStream(false)
                .start()

            // 分开读，避免缓冲区写满导致互相阻塞
            val outText = StringBuilder()
            val errText = StringBuilder()
            val outThread = Thread {
                process.inputStream.bufferedReader().use { reader ->
                    reader.forEachLine { line ->
                        outText.append(line).append('\n')
                        onLine?.invoke(line)
                    }
                }
            }
            val errThread = Thread {
                process.errorStream.bufferedReader().use { reader ->
                    reader.forEachLine { line ->
                        errText.append(line).append('\n')
                        onLine?.invoke(line)
                    }
                }
            }
            outThread.start()
            errThread.start()

            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                outThread.join(1000)
                errThread.join(1000)
                return@runCatching Output(
                    command = command,
                    stdout = outText.toString(),
                    stderr = "命令超时（${timeoutMs / 1000} 秒）已被终止",
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
            LinuxComponent.GIT -> "git --version 2>/dev/null || true"
            LinuxComponent.CODEX -> "codex --version 2>/dev/null || true"
            LinuxComponent.CLAUDE -> "claude --version 2>/dev/null || true"
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
