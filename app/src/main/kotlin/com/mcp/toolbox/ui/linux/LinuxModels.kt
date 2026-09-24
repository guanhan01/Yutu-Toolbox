package com.mcp.toolbox.ui.linux

import android.content.Context
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.util.Properties

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

/**
 * 一个 rootfs 下载源。
 *
 * [indexUrl] 是该发行版 rootfs 的目录页：Alpine 直接把压缩包放在该目录下，
 * Debian（LXC）下还有一层时间戳目录，解析器按发行版分别处理。
 *
 * 地址全部实测可用；界面只显示名称，不展示地址。
 */
data class LinuxSource(
    val id: String,
    val name: String,
    val distro: LinuxDistro,
    val indexUrl: String,
    val official: Boolean = false,
)

/** 内置下载源与源选择辅助。 */
object LinuxSources {

    /** 自动选择：并发测速后取最快可用的源。 */
    const val AUTO = "auto"

    private val ALL = listOf(
        // ---- Debian（LXC 镜像，arm64）----
        LinuxSource(
            id = "linuxcontainers",
            name = "官方源",
            distro = LinuxDistro.DEBIAN,
            indexUrl = "https://images.linuxcontainers.org/images/debian/trixie/arm64/default/",
            official = true,
        ),
        LinuxSource(
            id = "nju",
            name = "南京大学",
            distro = LinuxDistro.DEBIAN,
            indexUrl = "https://mirror.nju.edu.cn/lxc-images/images/debian/trixie/arm64/default/",
        ),
        LinuxSource(
            id = "tuna",
            name = "清华大学",
            distro = LinuxDistro.DEBIAN,
            indexUrl = "https://mirrors.tuna.tsinghua.edu.cn/lxc-images/images/debian/trixie/arm64/default/",
        ),
        LinuxSource(
            id = "lzu",
            name = "兰州大学",
            distro = LinuxDistro.DEBIAN,
            indexUrl = "https://mirrors.lzu.edu.cn/lxc-images/images/debian/trixie/arm64/default/",
        ),
        LinuxSource(
            id = "bfsu",
            name = "北外镜像",
            distro = LinuxDistro.DEBIAN,
            indexUrl = "https://mirrors.bfsu.edu.cn/lxc-images/images/debian/trixie/arm64/default/",
        ),
        // ---- Alpine（minirootfs，aarch64）----
        LinuxSource(
            id = "alpine-official",
            name = "官方源",
            distro = LinuxDistro.ALPINE,
            indexUrl = "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/aarch64/",
            official = true,
        ),
        LinuxSource(
            id = "tuna",
            name = "清华大学",
            distro = LinuxDistro.ALPINE,
            indexUrl = "https://mirrors.tuna.tsinghua.edu.cn/alpine/latest-stable/releases/aarch64/",
        ),
        LinuxSource(
            id = "ustc",
            name = "中科大",
            distro = LinuxDistro.ALPINE,
            indexUrl = "https://mirrors.ustc.edu.cn/alpine/latest-stable/releases/aarch64/",
        ),
        LinuxSource(
            id = "aliyun",
            name = "阿里云",
            distro = LinuxDistro.ALPINE,
            indexUrl = "https://mirrors.aliyun.com/alpine/latest-stable/releases/aarch64/",
        ),
        LinuxSource(
            id = "huawei",
            name = "华为云",
            distro = LinuxDistro.ALPINE,
            indexUrl = "https://mirrors.huaweicloud.com/alpine/latest-stable/releases/aarch64/",
        ),
        LinuxSource(
            id = "tencent",
            name = "腾讯云",
            distro = LinuxDistro.ALPINE,
            indexUrl = "https://mirrors.cloud.tencent.com/alpine/latest-stable/releases/aarch64/",
        ),
        LinuxSource(
            id = "nju",
            name = "南京大学",
            distro = LinuxDistro.ALPINE,
            indexUrl = "https://mirrors.nju.edu.cn/alpine/latest-stable/releases/aarch64/",
        ),
        LinuxSource(
            id = "sjtu",
            name = "上海交大",
            distro = LinuxDistro.ALPINE,
            indexUrl = "https://mirror.sjtu.edu.cn/alpine/latest-stable/releases/aarch64/",
        ),
        LinuxSource(
            id = "bfsu",
            name = "北外镜像",
            distro = LinuxDistro.ALPINE,
            indexUrl = "https://mirrors.bfsu.edu.cn/alpine/latest-stable/releases/aarch64/",
        ),
        LinuxSource(
            id = "lzu",
            name = "兰州大学",
            distro = LinuxDistro.ALPINE,
            indexUrl = "https://mirrors.lzu.edu.cn/alpine/latest-stable/releases/aarch64/",
        ),
    )

    /** 某个发行版可用的源（同一发行版内按建议优先级排列）。 */
    fun list(distro: LinuxDistro): List<LinuxSource> = ALL.filter { it.distro == distro }

    /**
     * 按 id 找源。
     *
     * id 在同一发行版内唯一，但不同发行版之间会重名（例如两边都有「清华」），
     * 因此必须带上发行版，否则会取到另一个发行版的地址。
     */
    fun byId(distro: LinuxDistro, id: String): LinuxSource? =
        ALL.firstOrNull { it.distro == distro && it.id == id }

    /** 界面选项：首项为自动，其余为该发行版各源（null 表示自动）。 */
    fun options(distro: LinuxDistro): List<LinuxSource?> = listOf(null) + list(distro)

    /** 把持久化的源 id 转成界面文字。 */
    fun nameOf(distro: LinuxDistro, id: String): String =
        if (id == AUTO) "自动选择" else byId(distro, id)?.name ?: "自动选择"

    /** 选项副标题。 */
    fun describe(source: LinuxSource?): String = when {
        source == null -> "并发测速，自动选择最快的可用源"
        source.official -> "上游官方源"
        else -> "镜像站"
    }
}

/** 可选的工具链组件。 */
enum class LinuxComponent(
    val title: String,
    val subtitle: String,
    /** 用于检测是否安装的可执行文件（在 rootfs 内）。 */
    val probe: String,
    /** 取版本号用的参数。 */
    val versionArgs: List<String> = listOf("--version"),
    /**
     * 是否依赖 Node.js。Codex / Claude 都是 npm 全局包，缺 Node 时装不上。
     */
    val requiresNode: Boolean = false,
) {
    PYTHON("Python / uv 环境", "uv 与最新正式版 Python", "/usr/local/bin/uv"),
    NODE("Node.js 环境", "Node.js 与 npm", "/usr/local/bin/node"),
    SSH("SSH 远程访问", "sshd、ssh-keygen 与 ssh-agent", "/usr/bin/ssh-keygen"),
    APK("APK 分析", "JADX、Apktool、smali 与 baksmali", "/opt/apktool/apktool"),
    GIT("Git 版本控制", "git 命令行工具", "/usr/bin/git"),
    CODEX("Codex CLI", "OpenAI 命令行助手（需先装 Node.js）", "/usr/local/bin/codex", requiresNode = true),
    CLAUDE("Claude Code", "Anthropic 命令行助手（需先装 Node.js）", "/usr/local/bin/claude", requiresNode = true),
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

/**
 * 一次安装的进度快照，用于中断后自动续装。
 *
 * 只记恢复所需的最小信息：用哪个源、解析出的压缩包地址与大小、停在哪一步。
 * 续装时直接复用这里记下的地址，而不是重跑目录解析——镜像更新后解析结果可能
 * 指向另一个文件，重解析等于让已下载的部分全部作废。
 */
data class InstallState(
    val sourceId: String,
    val url: String,
    val fileName: String,
    val total: Long,
    val stage: String,
) {
    fun save(context: Context, distro: LinuxDistro) {
        val props = Properties()
        props["sourceId"] = sourceId
        props["url"] = url
        props["fileName"] = fileName
        props["total"] = total.toString()
        props["stage"] = stage
        LinuxEnvStore.stateFile(context, distro).outputStream().use { props.store(it, null) }
    }

    companion object {
        const val STAGE_DOWNLOAD = "download"
        const val STAGE_EXTRACT = "extract"
        const val STAGE_REPAIR = "repair"

        fun load(context: Context, distro: LinuxDistro): InstallState? = runCatching {
            val file = LinuxEnvStore.stateFile(context, distro)
            if (!file.isFile) return null
            val props = Properties()
            file.inputStream().use { props.load(it) }
            val url = props.getProperty("url") ?: return null
            InstallState(
                sourceId = props.getProperty("sourceId") ?: LinuxSources.AUTO,
                url = url,
                fileName = props.getProperty("fileName") ?: url.substringAfterLast('/'),
                total = props.getProperty("total")?.toLongOrNull() ?: 0L,
                stage = props.getProperty("stage") ?: STAGE_DOWNLOAD,
            )
        }.getOrNull()

        fun clear(context: Context, distro: LinuxDistro) {
            runCatching { LinuxEnvStore.stateFile(context, distro).delete() }
        }
    }
}

/** 记住用户在环境页选过的发行版、运行方式与下载源（离开页面不重置）。 */
object LinuxPrefs {

    private const val PREFS = "linux-env-prefs"
    private const val KEY_DISTRO = "distro"
    private const val KEY_RUNTIME = "runtime"
    private const val KEY_MOUNTS = "custom-mounts"
    private const val KEY_SOURCE = "source-"

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

    /** 下载源：返回源 id，或 [LinuxSources.AUTO]。每个发行版各记一份。 */
    fun source(context: Context, distro: LinuxDistro): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SOURCE + distro.name, null) ?: LinuxSources.AUTO

    fun saveSource(context: Context, distro: LinuxDistro, sourceId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_SOURCE + distro.name, sourceId)
            .apply()
    }

    /** 自定义挂载：Android 绝对路径 -> rootfs 内的相对路径。 */
    fun customMounts(context: Context): List<Pair<String, String>> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MOUNTS, null) ?: return emptyList()
        return raw.lineSequence().mapNotNull { line ->
            val parts = line.split('|')
            if (parts.size != 2) return@mapNotNull null
            val src = parts[0].trim()
            val dst = parts[1].trim().trim('/')
            if (src.isEmpty() || dst.isEmpty()) null else src to dst
        }.toList()
    }

    fun saveCustomMounts(context: Context, mounts: List<Pair<String, String>>) {
        val raw = mounts.joinToString("\n") { "${it.first}|${it.second}" }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MOUNTS, raw)
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

    /**
     * 某个发行版的续装状态文件。
     *
     * 放在 filesDir 根下，不放进 linux-env：解压由 root 执行，root 会把
     * linux-env 及子目录建成 root 所有，写在里面会 EACCES。filesDir 始终
     * 归应用所有，任何情况下都能写。
     */
    fun stateFile(context: Context, distro: LinuxDistro): File =
        File(context.filesDir, ".linux-install-state.${distro.name.lowercase()}")

    /**
     * 确保应用自己需要写入的目录存在。
     *
     * 解压走 root，若父目录缺失会被 root 建成 root 所有，应用之后再想创建
     * 下载目录就会失败，所以先在应用侧建好。
     */
    fun prepare(context: Context, distro: LinuxDistro) {
        runCatching { root(context).mkdirs() }
        runCatching { downloads(context).mkdirs() }
        runCatching { File(root(context), distro.name.lowercase()).mkdirs() }
    }

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
     * 按「设备号」剪枝，只累计 rootfs 所在文件系统里的文件：
     * - proc / sys / dev / sdcard 在 commandLine 里被 bind mount 进来，不跳过就会
     *   去遍历整个宿主（几十万文件），既极慢又会抛异常被兜成 0，界面显示成「未安装」。
     * - mnt/android 下挂的是 Android 的系统分区（system、vendor、product、
     *   system_ext）。它们同样属于别的文件系统，只按目录名跳是漏的：实测这四项
     *   合计约 11 GB，会让「Linux 环境占用」虚高一个数量级。
     *
     * 用设备号而不是目录名，是因为自定义挂载点也可以指向任意宿主目录。
     */
    fun sizeOf(context: Context, distro: LinuxDistro): Long {
        val fs = rootfs(context, distro)
        if (!fs.isDirectory) return 0L
        val baseDev = runCatching { Os.lstat(fs.absolutePath).st_dev }.getOrNull() ?: return 0L
        return runCatching {
            fs.walkTopDown()
                .onEnter { dir ->
                    dir.name !in SKIP_WALK &&
                        runCatching { Os.lstat(dir.absolutePath).st_dev == baseDev }
                            .getOrDefault(false)
                }
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

    /**
     * 探测路径是否存在。
     *
     * 不能只用 [File.exists]：它会跟随软链接，而应用进程 stat rootfs 内的软链接会被
     * SELinux 拒绝（`avc: denied { read } ... tclass=lnk_file`），于是 npm 全局安装
     * 出来的 codex / claude 这类软链接一律被判成「未安装」，即使环境里跑得好好的。
     * 退回 lstat 只看链接自身，不读链接内容，因此不会被拒。
     */
    private fun probeExists(file: File): Boolean =
        file.exists() || runCatching { Os.lstat(file.absolutePath); true }.getOrDefault(false)

    /**
     * 探测是否可执行。
     *
     * 软链接走 [File.canExecute] 同样会因上面那条 SELinux 规则恒为 false。
     * 链接自身就位即可视为可用：真正的执行发生在 chroot 内、由 root 完成，
     * 那时链接会被正常解析（probeVersions 已证实这些命令能跑出版本号）。
     */
    private fun probeExecutable(file: File): Boolean {
        if (file.canExecute()) return true
        return runCatching { Os.lstat(file.absolutePath); true }.getOrDefault(false)
    }

    fun check(context: Context, distro: LinuxDistro): List<ComponentStatus> =
        LinuxComponent.entries.map { component ->
            val path = File(LinuxEnvStore.rootfs(context, distro), component.probe.removePrefix("/"))
            when {
                !probeExists(path) -> ComponentStatus(component, installed = false)
                !probeExecutable(path) -> ComponentStatus(
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
