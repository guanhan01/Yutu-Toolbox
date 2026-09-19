package com.mcp.toolbox.ui.linux

import android.content.Context
import java.io.File

/** Linux 发行版。 */
enum class LinuxDistro(
    /** 界面上显示的完整名称，含版本号。 */
    val displayName: String,
    val title: String,
    val subtitle: String,
    val archiveName: String,
) {
    ALPINE("Alpine", "Alpine", "轻量的 musl 环境", "alpine-minirootfs"),
    DEBIAN("Debian 13", "Debian", "软件兼容性更好的 glibc 环境", "debian-rootfs"),
}

/** 运行方式。 */
enum class LinuxRunMode(val title: String, val subtitle: String) {
    PROOT("普通模式 · PRoot", "无需 Root，使用独立的私有 Linux 环境"),
    CHROOT("Root 模式 · chroot", "使用 Root 与独立挂载空间，保留已有环境"),
}

/** 可选的工具链组件。 */
enum class LinuxComponent(
    val title: String,
    val subtitle: String,
    /** 用于检测是否安装的可执行文件（在 rootfs 内）。 */
    val probe: String,
    /** 取版本号用的参数。 */
    val versionArgs: List<String> = listOf("--version"),
) {
    PYTHON("Python / uv 环境", "uv 与最新正式版 Python", "/usr/local/bin/uv"),
    NODE("Node.js 环境", "Node.js 与 npm", "/usr/local/bin/node"),
    SSH("SSH 远程访问", "sshd、ssh-keygen 与 ssh-agent", "/usr/bin/ssh-keygen"),
    APK("APK 分析", "JADX、Apktool、smali 与 baksmali", "/opt/apktool/apktool"),
}

/** 一个组件的检测结果。 */
data class ComponentStatus(
    val component: LinuxComponent,
    val installed: Boolean,
    val version: String = "",
    val error: String = "",
)

/** 整体环境状态。 */
data class LinuxEnvStatus(
    val distro: LinuxDistro = LinuxDistro.DEBIAN,
    val runtime: LinuxRunMode = LinuxRunMode.PROOT,
    val installed: Boolean = false,
    val rootfsBytes: Long = 0,
    val busy: Boolean = false,
    val message: String = "",
    val components: List<ComponentStatus> = emptyList(),
) {
    val readyCount: Int get() = components.count { it.installed }
}

/** 记住用户在环境页选过的发行版与运行方式（离开页面不重置）。 */
object LinuxPrefs {

    private const val PREFS = "linux-env-prefs"
    private const val KEY_DISTRO = "distro"
    private const val KEY_RUNTIME = "runtime"

    fun distro(context: Context): LinuxDistro {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_DISTRO, null)
        return LinuxDistro.entries.firstOrNull { it.name == raw } ?: LinuxDistro.DEBIAN
    }

    fun runtime(context: Context): LinuxRunMode {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_RUNTIME, null)
        return LinuxRunMode.entries.firstOrNull { it.name == raw } ?: LinuxRunMode.PROOT
    }

    fun save(context: Context, distro: LinuxDistro, runtime: LinuxRunMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_DISTRO, distro.name)
            .putString(KEY_RUNTIME, runtime.name)
            .apply()
    }
}

object LinuxEnvStore {

    private const val DIR = "linux-env"

    /** 环境根目录（应用私有）。 */
    fun root(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }

    /** 某个发行版的 rootfs 目录。 */
    fun rootfs(context: Context, distro: LinuxDistro): File =
        File(File(root(context), distro.name.lowercase()), "rootfs")

    /** PRoot 可执行文件。 */
    fun prootBinary(context: Context): File = File(root(context), "proot")

    /** 已下载的压缩包暂存目录。 */
    fun downloads(context: Context): File = File(root(context), "downloads").apply { mkdirs() }

    /** rootfs 是否已就绪（以 /bin 或 /usr 存在为准）。 */
    fun isInstalled(context: Context, distro: LinuxDistro): Boolean {
        val fs = rootfs(context, distro)
        return File(fs, "bin").isDirectory || File(fs, "usr").isDirectory
    }

    /** 遍历 rootfs 时要跳过的子目录。 */
    private val SKIP_WALK = setOf("proc", "sys", "dev", "sdcard")

    /**
     * 统计 rootfs 占用。
     *
     * 必须跳过 proc / sys / dev / sdcard：这些是 commandLine 里 bind mount 进来的
     * 宿主目录，不跳过就会去遍历整个宿主（几十万文件），既极慢又会抛异常被兜成 0，
     * 界面于是显示成「未安装」。
     */
    fun sizeOf(context: Context, distro: LinuxDistro): Long {
        val fs = rootfs(context, distro)
        if (!fs.isDirectory) return 0L
        return runCatching {
            fs.walkTopDown()
                .onEnter { it.name !in SKIP_WALK }
                .filter { it.isFile }
                .sumOf { it.length() }
        }.getOrDefault(0L)
    }

    /** 磁盘是否够装下解压后的 rootfs（压缩包通常膨胀 2.5～3 倍）。 */
    fun requiredBytes(archiveBytes: Long): Long = (archiveBytes * 3).coerceAtLeast(512L * 1024 * 1024)
}

/**
 * 环境检测。
 *
 * 不依赖真实启动 Linux——通过 rootfs 内的可执行文件是否存在来判断组件是否就绪，
 * 因此检测本身是瞬时且无副作用的。
 */
object LinuxChecker {

    fun check(context: Context, distro: LinuxDistro): List<ComponentStatus> =
        LinuxComponent.entries.map { component ->
            val path = File(LinuxEnvStore.rootfs(context, distro), component.probe.removePrefix("/"))
            when {
                !path.exists() -> ComponentStatus(component, installed = false)
                !path.canExecute() -> ComponentStatus(
                    component,
                    installed = true,
                    version = "缺少执行权限",
                )

                else -> ComponentStatus(component, installed = true, version = readVersion(path))
            }
        }

    /**
     * 直接读取脚本头部或伴随的版本文件；不做真实执行。
     *
     * 真实执行需要在 Linux 环境内跑起来（PRoot），成本高；这里优先读同目录的
     * `<名称>.version` 缓存文件，没有则留空由界面显示「已就绪」。
     */
    private fun readVersion(executable: File): String {
        val cached = File(executable.parentFile, executable.name + ".version")
        if (cached.isFile) {
            return runCatching { cached.readText().trim().lineSequence().first() }.getOrDefault("")
        }
        return ""
    }

    /** 人类可读的体积。 */
    fun humanSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.2f GB".format(bytes.toDouble() / (1L shl 30))
        bytes >= 1L shl 20 -> "%.0f MB".format(bytes.toDouble() / (1L shl 20))
        bytes >= 1L shl 10 -> "%.0f KB".format(bytes.toDouble() / (1L shl 10))
        bytes > 0 -> "$bytes B"
        else -> "未安装"
    }
}
