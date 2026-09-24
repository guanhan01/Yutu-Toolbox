package com.mcp.toolbox.ui.linux

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 主页点「安装」时暂存目标组件，跳转到检测页后自动开始安装。 */
object LinuxPendingInstall {
    var component: LinuxComponent? = null
}

/**
 * 在 Linux 环境里装可选用工具。
 *
 * 大文件（uv / Node / jadx / apktool）先在 Java 侧下载到 rootfs 内的暂存目录，
 * 再由 chroot 脚本离线解包——这样能复用 [LinuxInstaller] 里已验证过的断点续传，
 * 中断后重按「安装」是接着下，而不是从头再来；SDK 包仍走 chroot 内的包管理器。
 *
 * 所有镜像都是「多源候选 + 自动回退」：逐个探测，用第一个连得通的。
 * 包管理器按发行版区分：Debian 用 apt，Alpine 用 apk，包名也各自适配。
 */
object LinuxToolchain {

    /**
     * rootfs 内放大文件归档的位置；Java 侧写入，chroot 侧读取。
     *
     * 必须是 /tmp（rootfs 里默认 1777）：/root 由 root 创建且权限 700，应用进程
     * 写不进去，会 EACCES。
     */
    private const val STAGE_DIR = "tmp/.mcp-dl"

    /** Debian 镜像：主源与安全源分属不同路径，成对记录。地址均实测可用。 */
    private val DEBIAN_MIRRORS = listOf(
        "https://mirrors.tuna.tsinghua.edu.cn/debian" to "https://mirrors.tuna.tsinghua.edu.cn/debian-security",
        "https://mirrors.ustc.edu.cn/debian" to "https://mirrors.ustc.edu.cn/debian-security",
        "https://mirrors.aliyun.com/debian" to "https://mirrors.aliyun.com/debian-security",
        "https://mirrors.huaweicloud.com/debian" to "https://mirrors.huaweicloud.com/debian-security",
        "https://mirrors.cloud.tencent.com/debian" to "https://mirrors.cloud.tencent.com/debian-security",
        "https://mirrors.nju.edu.cn/debian" to "https://mirrors.nju.edu.cn/debian-security",
        "https://mirrors.bfsu.edu.cn/debian" to "https://mirrors.bfsu.edu.cn/debian-security",
        "https://mirrors.lzu.edu.cn/debian" to "https://mirrors.lzu.edu.cn/debian-security",
        "https://deb.debian.org/debian" to "https://deb.debian.org/debian-security",
    )

    /** Alpine 镜像（apk）。 */
    private val ALPINE_MIRRORS = listOf(
        "https://mirrors.tuna.tsinghua.edu.cn/alpine",
        "https://mirrors.ustc.edu.cn/alpine",
        "https://mirrors.aliyun.com/alpine",
        "https://mirrors.huaweicloud.com/alpine",
        "https://mirrors.cloud.tencent.com/alpine",
        "https://mirrors.nju.edu.cn/alpine",
        "https://mirror.sjtu.edu.cn/alpine",
        "https://mirrors.bfsu.edu.cn/alpine",
        "https://mirrors.lzu.edu.cn/alpine",
        "https://dl-cdn.alpinelinux.org/alpine",
    )

    /** GitHub 加速前缀；末位空串是直连 GitHub，作为最后兜底。 */
    private val GITHUB_PROXIES = listOf(
        "https://gh-proxy.com/",
        "https://ghproxy.net/",
        "https://gh.ddlc.top/",
        "",
    )

    /** PyPI 镜像（index-url 就是这一串）。 */
    private val PYPI_MIRRORS = listOf(
        "https://pypi.tuna.tsinghua.edu.cn/simple",
        "https://mirrors.aliyun.com/pypi/simple",
        "https://mirrors.cloud.tencent.com/pypi/simple",
        "https://mirrors.ustc.edu.cn/pypi/simple",
        "https://mirrors.huaweicloud.com/repository/pypi/simple",
        "https://pypi.org/simple",
    )

    /** npm registry 镜像。 */
    private val NPM_MIRRORS = listOf(
        "https://registry.npmmirror.com",
        "https://mirrors.cloud.tencent.com/npm/",
        "https://mirrors.huaweicloud.com/repository/npm/",
        "https://registry.npmjs.org",
    )

    private const val NODE_VERSION = "v22.20.0"

    /** Node 二进制镜像。 */
    private val NODE_URLS = listOf(
        "https://cdn.npmmirror.com/binaries/node/$NODE_VERSION/node-$NODE_VERSION-linux-arm64.tar.xz",
        "https://mirrors.huaweicloud.com/nodejs/$NODE_VERSION/node-$NODE_VERSION-linux-arm64.tar.xz",
        "https://mirrors.cloud.tencent.com/nodejs-release/$NODE_VERSION/node-$NODE_VERSION-linux-arm64.tar.xz",
        "https://mirrors.aliyun.com/nodejs-release/$NODE_VERSION/node-$NODE_VERSION-linux-arm64.tar.xz",
        "https://nodejs.org/dist/$NODE_VERSION/node-$NODE_VERSION-linux-arm64.tar.xz",
    )

    /** 把 GitHub 资源展开成「各加速器 + 直连」的候选列表。 */
    private fun github(path: String): List<String> =
        GITHUB_PROXIES.map { prefix -> prefix + "https://github.com/" + path }

    private fun uvUrls(musl: Boolean): List<String> {
        val suffix = if (musl) "musl" else "gnu"
        return github("astral-sh/uv/releases/latest/download/uv-aarch64-unknown-linux-$suffix.tar.gz")
    }

    private val JADX_URLS = github("skylot/jadx/releases/download/v1.5.1/jadx-1.5.1.zip")
    private val APKTOOL_URLS =
        github("iBotPeaches/Apktool/releases/download/v2.11.1/apktool_2.11.1.jar")

    private fun List<String>.shellList(): String = joinToString(" ")

    /** 一个需要预先下载的归档。 */
    private data class Archive(val name: String, val candidates: List<String>)

    /** 该组件需要预下载的大文件；SSH / Git / npm 包不需要。 */
    private fun archivesOf(distro: LinuxDistro, component: LinuxComponent): List<Archive> {
        val musl = distro == LinuxDistro.ALPINE
        return when (component) {
            LinuxComponent.PYTHON -> listOf(Archive("uv.tar.gz", uvUrls(musl)))
            LinuxComponent.NODE -> listOf(Archive("node.tar.xz", NODE_URLS))
            LinuxComponent.APK -> listOf(
                Archive("jadx.zip", JADX_URLS),
                Archive("apktool.jar", APKTOOL_URLS),
            )

            else -> emptyList()
        }
    }

    /**
     * 组装一个组件的安装脚本。
     *
     * 模板里用 `§` 代表 shell 的 `$`，最后统一替换：否则模板里每个 `$` 都要写成
     * `${'$'}`，既难读又容易漏写；Kotlin 侧的插值则照常用 `$`。
     */
    private fun scriptOf(distro: LinuxDistro, component: LinuxComponent): String =
        (preamble(distro) + "\n" + bodyOf(distro, component)).replace("§", "$")

    /**
     * 公共头部：修证书、按发行版挑一个连得通的软件源并定义 `$PKG`。
     *
     * `$PKG` 必须在这里就定义好，组件脚本第一行直接 `§PKG ...` 用；若写成单独
     * 一行 `§PKG` 会空跑一条无参命令。
     */
    private fun preamble(distro: LinuxDistro): String {
        val head = """
            set -e
            export DEBIAN_FRONTEND=noninteractive
            export TMPDIR=/tmp
            echo "[1/4] 已进入 Linux 环境"
            mkdir -p /tmp
            # chroot 里没有服务管理器，装包时禁止自动起服务
            printf '#!/bin/sh\nexit 101\n' > /usr/sbin/policy-rc.d
            chmod +x /usr/sbin/policy-rc.d
            # Debian 13 的 LXC rootfs 里 ca-certificates 没在构建阶段跑完：
            # /etc/ssl/certs 缺部分根证书 PEM 与 hash 软链，CApath 校验会整链
            # certificate verify failed，https 中文镜像全部不可用。重跑生成器即可。
            update-ca-certificates --fresh >/dev/null 2>&1 || true
        """.trimIndent()

        val source = when (distro) {
            LinuxDistro.DEBIAN -> """
                echo "[2/4] 选择软件源"
                DEB_MAIN=""; DEB_SEC=""
                for m in ${DEBIAN_MIRRORS.map { it.first }.shellList()}; do
                  if curl -fsSL --max-time 6 -o /dev/null "§m/dists/trixie/Release" 2>/dev/null; then
                    sec=§(printf '%s' "§m" | sed 's|/debian§|/debian-security|')
                    if curl -fsSL --max-time 6 -o /dev/null "§sec/dists/trixie-security/Release" 2>/dev/null; then
                      DEB_MAIN="§m"; DEB_SEC="§sec"; break
                    fi
                  fi
                done
                if [ -z "§DEB_MAIN" ]; then
                  DEB_MAIN=${DEBIAN_MIRRORS.first().first}
                  DEB_SEC=${DEBIAN_MIRRORS.first().second}
                fi
                echo "  使用 §DEB_MAIN"
                cat > /etc/apt/sources.list <<SRC
                deb §DEB_MAIN trixie main contrib non-free non-free-firmware
                deb §DEB_MAIN trixie-updates main contrib non-free non-free-firmware
                deb §DEB_SEC trixie-security main contrib non-free non-free-firmware
                SRC
                echo "[3/4] 更新软件索引"
                apt-get update -qq
                PKG="apt-get install -y -qq --no-install-recommends"
            """.trimIndent()

            LinuxDistro.ALPINE -> """
                echo "[2/4] 选择软件源"
                VER=§(cut -d. -f1,2 /etc/alpine-release 2>/dev/null)
                if [ -z "§VER" ]; then VER=latest-stable; fi
                ALP=""
                for m in ${ALPINE_MIRRORS.shellList()}; do
                  if wget -q -T 6 -O /dev/null "§m/v§VER/main/aarch64/APKINDEX.tar.gz" 2>/dev/null; then
                    ALP="§m"; break
                  fi
                done
                if [ -z "§ALP" ]; then ALP=${ALPINE_MIRRORS.first()}; fi
                echo "  使用 §ALP"
                printf '%s\n' "§ALP/v§VER/main" "§ALP/v§VER/community" > /etc/apk/repositories
                echo "[3/4] 更新软件索引"
                apk update
                PKG="apk add --no-cache"
            """.trimIndent()
        }
        return head + "\n" + source + "\n"
    }

    private fun bodyOf(distro: LinuxDistro, component: LinuxComponent): String {
        val alpine = distro == LinuxDistro.ALPINE
        return when (component) {
            LinuxComponent.PYTHON -> """
                §PKG python3 ${if (alpine) "py3-pip py3-virtualenv" else "python3-pip python3-venv"} curl ca-certificates
                echo "[4/4] 安装 uv"
                rm -rf /tmp/uvx && mkdir -p /tmp/uvx && tar -xzf /tmp/.mcp-dl/uv.tar.gz -C /tmp/uvx
                find /tmp/uvx -name uv -type f -exec cp -f {} /usr/local/bin/uv \;
                chmod 755 /usr/local/bin/uv
                rm -rf /tmp/uvx
                # pip 与 uv 的索引也指向国内镜像，否则装包时会卡在境外
                UV_INDEX=""
                for r in ${PYPI_MIRRORS.shellList()}; do
                  if curl -fsSL --max-time 6 -o /dev/null "§r/uv/" 2>/dev/null; then UV_INDEX="§r"; break; fi
                done
                if [ -n "§UV_INDEX" ]; then
                  mkdir -p /root/.config/pip /root/.config/uv
                  printf '[global]\nindex-url = %s\ntimeout = 30\n' "§UV_INDEX" > /root/.config/pip/pip.conf
                  printf '[[index]]\nurl = "%s"\ndefault = true\n' "§UV_INDEX" > /root/.config/uv/uv.toml
                fi
                echo "python3: §(python3 --version 2>&1)"
                echo "uv: §(/usr/local/bin/uv --version 2>&1)"
            """.trimIndent()

            LinuxComponent.NODE -> """
                §PKG curl ca-certificates ${if (alpine) "xz libstdc++" else "xz-utils"}
                echo "[4/4] 安装 Node.js"
                tar -xJf /tmp/.mcp-dl/node.tar.xz -C /usr/local --strip-components=1
                echo "node: §(node --version 2>&1)"
                echo "npm: §(npm --version 2>&1)"
            """.trimIndent()

            LinuxComponent.SSH -> """
                §PKG ${if (alpine) "openssh" else "openssh-server openssh-client"}
                mkdir -p /run/sshd /root/.ssh
                chmod 700 /root/.ssh
                echo "ssh: §(ssh -V 2>&1)"
            """.trimIndent()

            LinuxComponent.APK -> """
                §PKG curl ca-certificates unzip ${if (alpine) "openjdk21-jre-headless" else "openjdk-21-jre-headless"}
                echo "[4/4] 安装 jadx 与 apktool"
                rm -rf /opt/jadx && mkdir -p /opt/jadx && unzip -q -o /tmp/.mcp-dl/jadx.zip -d /opt/jadx
                JADX_BIN="§(find /opt/jadx -type f -path '*/bin/jadx' | head -n 1)"
                [ -n "§JADX_BIN" ] || { echo "jadx 解压异常：未找到 bin/jadx"; exit 1; }
                chmod 755 "§JADX_BIN"
                ln -sf "§JADX_BIN" /usr/local/bin/jadx
                mkdir -p /opt/apktool
                cp -f /tmp/.mcp-dl/apktool.jar /opt/apktool/apktool.jar
                printf '#!/bin/sh\nexec java -jar /opt/apktool/apktool.jar "$@"\n' > /opt/apktool/apktool
                chmod 755 /opt/apktool/apktool
                ln -sf /opt/apktool/apktool /usr/local/bin/apktool
                echo "jadx: ok"
                echo "apktool: ok"
            """.trimIndent()

            LinuxComponent.GIT -> """
                §PKG git
                echo "git: §(git --version 2>&1)"
            """.trimIndent()

            // 这两个是 npm 全局包，必须先有 Node.js 环境
            LinuxComponent.CODEX -> npmBody(
                packageName = "@openai/codex",
                cleanupDir = "/usr/local/lib/node_modules/@openai",
                binary = "codex",
            )

            LinuxComponent.CLAUDE -> npmBody(
                packageName = "@anthropic-ai/claude-code",
                cleanupDir = "/usr/local/lib/node_modules/@anthropic-ai",
                binary = "claude",
            )
        }
    }

    /** npm 全局包：先挑一个连得通的 registry，再安装。 */
    private fun npmBody(packageName: String, cleanupDir: String, binary: String): String = """
        [ -x /usr/local/bin/npm ] || { echo "请先安装 Node.js 环境"; exit 1; }
        NPM_REG=""
        for r in ${NPM_MIRRORS.shellList()}; do
          if curl -fsSL --max-time 6 -o /dev/null "§r/$packageName" 2>/dev/null; then NPM_REG="§r"; break; fi
        done
        if [ -n "§NPM_REG" ]; then npm config set registry "§NPM_REG"; fi
        # 中断过的安装会残留半装目录，npm 重装时报 ENOTEMPTY 直接失败
        rm -rf $cleanupDir
        npm install -g $packageName
        echo "$binary: §(/usr/local/bin/$binary --version 2>&1 | head -n 1)"
    """.trimIndent()

    /**
     * 已经落盘但尚未解包的归档大小，用于界面提示「可继续下载」。
     *
     * 只统计 `.part`：完整文件在下一次安装时会被跳过，不需要提醒续传。
     */
    fun pendingBytes(context: Context, distro: LinuxDistro, component: LinuxComponent): Long {
        val stage = File(LinuxEnvStore.rootfs(context, distro), STAGE_DIR)
        return archivesOf(distro, component).sumOf { archive ->
            LinuxInstaller.partialBytes(File(stage, archive.name), archive.candidates)
        }
    }

    /**
     * 归档是否可用。
     *
     * 删掉某个归档及其所有源的 `.part` 断点。
     *
     * 断点按地址区分（换源不会串内容），代价是换源后会留下旧源的残留；
     * 归档已到手或已装好时，这些残留都不再需要。
     */
    private fun clearArchive(stage: File, name: String) {
        runCatching { File(stage, name).delete() }
        clearParts(stage, name)
    }

    /** 只清 `.part`，保留已下完的归档。 */
    private fun clearParts(stage: File, name: String) {
        val prefix = "$name."
        runCatching {
            stage.listFiles()?.forEach { f ->
                if (f.name.startsWith(prefix) && f.name.endsWith(".part")) f.delete()
            }
        }
    }

    /**
     * zip 只验证中央目录能否读出，不做全量解压：目的是把「下载坏了」与
     * 「解包出错」分开——前者重下即可，后者才是脚本或环境的问题。
     * 非 zip（tar.gz / tar.xz / jar）不做结构校验，交给 tar 自己报错。
     */
    private fun archiveValid(archive: Archive, file: File): Boolean {
        if (!archive.name.endsWith(".zip")) return true
        return runCatching { java.util.zip.ZipFile(file).use { it.size() > 0 } }.getOrDefault(false)
    }

    /**
     * Node.js 是否已就绪。
     *
     * Codex / Claude 走 npm 全局安装，没有 Node 时脚本注定失败；这里只看
     * 可执行文件是否存在，不做真实执行——真跑一次 chroot 代价太大。
     */
    private fun nodeReady(context: Context, distro: LinuxDistro): Boolean {
        val rootfs = LinuxEnvStore.rootfs(context, distro)
        // 查 node 本体与 npm 包本体，不查 /usr/local/bin/npm：
        // 那是个软链接，应用进程 stat 它会被 SELinux 拒绝（lnk_file read），
        // 会让装了 Node 的用户仍然被拦在「请先安装 Node.js 环境」。
        return File(rootfs, "usr/local/bin/node").exists() &&
            File(rootfs, "usr/local/lib/node_modules/npm/bin/npm-cli.js").exists()
    }

    /** 安装一个组件；[onLine] 收到脚本输出行。 */
    suspend fun install(
        context: Context,
        distro: LinuxDistro,
        component: LinuxComponent,
        onProgress: (LinuxInstaller.Progress) -> Unit = {},
        onLine: (String) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        // 回调此前直接改界面状态；切到 IO 后统一由 Handler 送回主线程，
        // 否则 SnapshotStateList 会被跨线程写，日志与进度会丢更新。
        val main = Handler(Looper.getMainLooper())
        installBlocking(
            context = context,
            distro = distro,
            component = component,
            onProgress = { p -> main.post { onProgress(p) } },
            onLine = { line -> main.post { onLine(line) } },
        )
    }

    /**
     * [install] 的实现体。
     *
     * 内含 Java 侧 HTTP 下载与 chroot 执行，必须在 IO 线程上跑。调用方
     * （Compose 的 rememberCoroutineScope）默认在 Main 上，直接跑会抛
     * NetworkOnMainThreadException——该异常不带 message，界面只会显示
     * 一句「下载失败：null」，从现象看不出真正原因。
     */
    private suspend fun installBlocking(
        context: Context,
        distro: LinuxDistro,
        component: LinuxComponent,
        onProgress: (LinuxInstaller.Progress) -> Unit = {},
        onLine: (String) -> Unit,
    ): Result<Unit> {
        if (!LinuxRuntime.isReady(context, distro)) {
            return Result.failure(IllegalStateException("请先安装 Linux 环境"))
        }

        // Codex / Claude 是 npm 全局包，缺 Node 时装不出任何东西。脚本里的
        // 判空在 apt-get update 之后，会先白等一轮索引更新才报错，这里提前拦下。
        if (component.requiresNode && !nodeReady(context, distro)) {
            onLine("✗ 请先安装 Node.js 环境，再安装 ${component.title}")
            return Result.failure(IllegalStateException("请先安装 Node.js 环境"))
        }
        onLine("→ 开始安装 ${component.title}")

        // 先把需要的大文件下好（复用断点续传），再进 chroot 离线解包。
        val stage = File(LinuxEnvStore.rootfs(context, distro), STAGE_DIR)
        val archives = archivesOf(distro, component)
        try {
            stage.mkdirs()
            for (archive in archives) {
                val target = File(stage, archive.name)
                // 已下载的归档在复用前同样要验：旧版本会放过超长的坏包，留着它
                // 就等于反复拿同一个坏文件去解压，只会得到一个退出码。
                if (target.isFile && target.length() > 0) {
                    if (archiveValid(archive, target)) {
                        onLine("→ ${archive.name} 已在本地，跳过下载")
                        continue
                    }
                    onLine("→ ${archive.name} 本地副本不完整，重新下载")
                    target.delete()
                }
                onLine("→ 下载 ${archive.name}（中断可从断点继续）")
                LinuxInstaller.downloadAny(
                    candidates = archive.candidates,
                    target = target,
                    onProgress = onProgress,
                )
                if (!archiveValid(archive, target)) {
                    target.delete()
                    onLine("✗ ${archive.name} 校验失败（压缩包不完整），已丢弃，请重试")
                    return Result.failure(IllegalStateException("${archive.name} 校验失败"))
                }
                // 完整文件已到手，其它源留下的同包断点就是垃圾
                clearParts(stage, archive.name)
                onLine("→ ${archive.name} 下载完成（${LinuxChecker.humanSize(target.length())}）")
            }
        } catch (t: Throwable) {
            onLine("✗ 下载失败：${t.message ?: t::class.simpleName ?: "未知错误"}")
            onLine("  已下载的部分已保留，重试会从断点继续")
            return Result.failure(t)
        }

        // 读取线程在后台；回调的线程切换由 [install] 统一处理
        val result = LinuxRuntime.exec(
            context = context,
            distro = distro,
            command = scriptOf(distro, component),
            timeoutMs = 30 * 60 * 1000L,
            onLine = { raw -> if (raw.isNotBlank()) onLine(raw.trim()) },
        )
        if (!result.ok) {
            // 兜底分支（超时 / 启动异常）不会有流式输出，这里补上真实原因，
            // 否则界面上只剩一个退出码，无法排查
            result.stderr.lineSequence().forEach { if (it.isNotBlank()) onLine(it.trim()) }
            // SSH / Git 这类没有归档的组件不该提示「已保留」，否则等于误导
            if (archives.isNotEmpty()) onLine("→ 归档文件已保留，重按「安装」会跳过已下载部分")
        }
        // 无论成败都报耗时：脚本"秒回"就说明它根本没跑起来
        onLine("→ 退出码 ${result.exitCode}，耗时 ${result.elapsedMs / 1000} 秒，输出 ${result.stdout.length} 字")
        return if (result.ok) {
            // 装完清掉归档，连 `.part` 一起清。换过源的组件会留下旧源的断点，
            // 只删归档文件名会漏掉它们，白占几十上百 MB 且再也不会被用到。
            archives.forEach { runCatching { clearArchive(stage, it.name) } }
            LinuxRuntime.refreshVersion(context, distro, component)
            onLine("→ ${component.title} 完成")
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("安装失败（退出码 ${result.exitCode}）"))
        }
    }
}
