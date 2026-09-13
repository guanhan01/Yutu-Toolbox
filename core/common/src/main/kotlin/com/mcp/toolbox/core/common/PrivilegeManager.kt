package com.mcp.toolbox.core.common

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * 可用的高权限后端。
 *
 * - ROOT：通过 `su -c` 执行，要求设备已 root 且用户在授权弹窗里同意
 * - SHIZUKU：通过 Shizuku 的 binder 执行，要求已安装 Shizuku 并授权
 *
 * 两者都不可用时应用仍然完整可用，只是高权限工具会明确报错而不是假装成功。
 */
enum class PrivilegeBackend { NONE, ROOT, SHIZUKU }

/** 权限探测结果，供首页状态卡与设置页展示。 */
data class PrivilegeStatus(
    val rootBinaryFound: Boolean = false,
    val rootGranted: Boolean = false,
    val shizukuInstalled: Boolean = false,
    val shizukuBinderAlive: Boolean = false,
    val shizukuGranted: Boolean = false,
    val active: PrivilegeBackend = PrivilegeBackend.NONE,
) {
    /** 是否至少有一个可用的高权限后端。 */
    val usable: Boolean get() = active != PrivilegeBackend.NONE

    /** 首页一行摘要。 */
    val summary: String get() = when {
        active == PrivilegeBackend.ROOT -> "root 已授权"
        active == PrivilegeBackend.SHIZUKU -> "Shizuku 已授权"
        rootBinaryFound && !rootGranted -> "检测到 root，尚未授权"
        shizukuInstalled && !shizukuGranted -> "检测到 Shizuku，尚未授权"
        else -> "无高权限后端"
    }

    /** 设置页两行明细。 */
    val detail: String get() = buildString {
        append(if (rootBinaryFound) "root 已发现 su" else "root 无 su")
        append(" · ")
        when {
            !shizukuInstalled -> append("Shizuku 未安装")
            !shizukuBinderAlive -> append("Shizuku 未运行")
            !shizukuGranted -> append("Shizuku 未授权")
            else -> append("Shizuku 已授权")
        }
    }
}

/** 一次 shell 执行结果。 */
data class ShellResult(
    val code: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
    val backend: PrivilegeBackend = PrivilegeBackend.NONE,
    val failure: String? = null,
) {
    val ok: Boolean get() = code == 0 && !timedOut && failure == null

    /** 给 MCP 调用方看的合并输出。 */
    val output: String get() = when {
        failure != null -> failure
        stdout.isNotBlank() && stderr.isNotBlank() -> stdout.trimEnd() + "\n" + stderr.trimEnd()
        stdout.isNotBlank() -> stdout.trimEnd()
        else -> stderr.trimEnd()
    }
}

/**
 * 高权限后端探测与执行。
 *
 * [status] 与 [probeRoot] 会真正拉起子进程（首次会触发系统 root 授权弹窗），
 * 必须在 IO 线程调用，不要放在 Compose 组合或主线程里。
 */
object PrivilegeManager {

    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    const val SHIZUKU_REQUEST_CODE = 4210

    private val ROOT_CANDIDATES = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/system/sbin/su",
        "/su/bin/su", "/debug_ramdisk/su", "/data/adb/ksu/bin/su",
    )

    /** 状态缓存：探测要起进程，太频繁会明显拖慢界面。 */
    @Volatile private var cached: PrivilegeStatus? = null
    @Volatile private var cachedAt = 0L
    private const val CACHE_TTL_MS = 5_000L

    fun rootBinary(): File? = ROOT_CANDIDATES.map(::File).firstOrNull { it.exists() }

    fun shizukuInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
    }.isSuccess

    /** Shizuku 服务进程是否在跑。未安装/未启动时会抛异常，统一吞成 false。 */
    fun shizukuAlive(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun shizukuGranted(): Boolean = runCatching {
        !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** 拉起 Shizuku 的授权弹窗（需要前台 Activity）。 */
    fun requestShizukuPermission() {
        runCatching { Shizuku.requestPermission(SHIZUKU_REQUEST_CODE) }
    }

    /** 执行 `su -c id`，用返回的 uid 判断 root 是否真的可用。 */
    fun probeRoot(timeoutMs: Long = 8_000L): Boolean {
        if (rootBinary() == null) return false
        val result = execRoot("id", timeoutMs)
        return result.ok && result.stdout.contains("uid=0")
    }

    fun status(context: Context, force: Boolean = false): PrivilegeStatus {
        val now = System.currentTimeMillis()
        cached?.let { if (!force && now - cachedAt < CACHE_TTL_MS) return it }

        val rootBinary = rootBinary() != null
        val rootGranted = rootBinary && probeRoot()
        val installed = shizukuInstalled(context)
        val alive = installed && shizukuAlive()
        val shizukuOk = alive && shizukuGranted()

        val active = when {
            rootGranted -> PrivilegeBackend.ROOT
            shizukuOk -> PrivilegeBackend.SHIZUKU
            else -> PrivilegeBackend.NONE
        }
        val fresh = PrivilegeStatus(rootBinary, rootGranted, installed, alive, shizukuOk, active)
        cached = fresh
        cachedAt = now
        return fresh
    }

    /** 清掉缓存，强制下次重新探测（用户刚授权或刚切换后端时调用）。 */
    fun invalidate() {
        cached = null
        cachedAt = 0L
    }

    /**
     * 用高权限后端执行 shell 命令。
     * 没有可用后端时返回带 [ShellResult.failure] 的结果而不是抛异常，调用方按失败处理即可。
     */
    fun exec(command: String, timeoutMs: Long = 30_000L, backend: PrivilegeBackend? = null): ShellResult {
        val chosen = backend ?: when {
            probeRoot() -> PrivilegeBackend.ROOT
            shizukuAlive() && shizukuGranted() -> PrivilegeBackend.SHIZUKU
            else -> PrivilegeBackend.NONE
        }
        return when (chosen) {
            PrivilegeBackend.ROOT -> execRoot(command, timeoutMs)
            PrivilegeBackend.SHIZUKU -> execShizuku(command, timeoutMs)
            PrivilegeBackend.NONE -> ShellResult(
                code = -1, stdout = "", stderr = "", backend = PrivilegeBackend.NONE,
                failure = "没有可用的高权限后端：未获得 root 授权，且 Shizuku 不可用",
            )
        }
    }

    private fun execRoot(command: String, timeoutMs: Long): ShellResult =
        runCatching { collect(ProcessBuilder("su", "-c", command).start(), timeoutMs, PrivilegeBackend.ROOT) }
            .getOrElse { ShellResult(-1, "", "", false, PrivilegeBackend.ROOT, "无法启动 su：${it.message}") }

    /**
     * 通过 Shizuku 执行命令。
     *
     * `Shizuku.newProcess` 在 aar 里是内部方法（对库外不可见），但 Shizuku 是第三方类、
     * 不受 Android hidden API 限制，所以用反射调用；拿不到就明确失败，不静默降级成 root。
     */
    private fun execShizuku(command: String, timeoutMs: Long): ShellResult = runCatching {
        val method = Shizuku::class.java.declaredMethods
            .firstOrNull { it.name == "newProcess" && it.parameterTypes.size == 3 }
            ?: error("当前 Shizuku 版本未暴露 newProcess")
        method.isAccessible = true
        val process = method.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
        collect(process, timeoutMs, PrivilegeBackend.SHIZUKU)
    }.getOrElse {
        ShellResult(-1, "", "", false, PrivilegeBackend.SHIZUKU, "Shizuku 执行失败：${it.message}")
    }

    private fun collect(process: Process, timeoutMs: Long, backend: PrivilegeBackend): ShellResult {
        val out = StringBuilder()
        val err = StringBuilder()
        // 必须并发读两个流：管道缓冲区写满会让子进程阻塞在写操作上，进而被误判成超时。
        val tOut = Thread { drain(process.inputStream, out) }.apply { isDaemon = true }
        val tErr = Thread { drain(process.errorStream, err) }.apply { isDaemon = true }
        tOut.start()
        tErr.start()

        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            tOut.join(500)
            tErr.join(500)
            return ShellResult(-1, out.toString(), err.toString(), true, backend, "执行超时（${timeoutMs}ms）")
        }
        tOut.join(1_000)
        tErr.join(1_000)
        return ShellResult(process.exitValue(), out.toString(), err.toString(), false, backend)
    }

    private fun drain(stream: InputStream, sink: StringBuilder) {
        runCatching {
            BufferedReader(InputStreamReader(stream)).use { reader ->
                val buf = CharArray(4096)
                while (true) {
                    val n = reader.read(buf)
                    if (n <= 0) break
                    sink.append(buf, 0, n)
                }
            }
        }
    }
}
