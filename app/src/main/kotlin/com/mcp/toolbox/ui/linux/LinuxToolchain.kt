package com.mcp.toolbox.ui.linux

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * 在 Linux 环境里装可选用工具。
 *
 * 全部走国内源：apt 换成清华镜像，独立二进制走 GitHub 国内加速。
 * 脚本在 chroot 内以 root 执行，输出回传界面。
 */
/** 主页点「安装」时暂存目标组件，跳转到检测页后自动开始安装。 */
object LinuxPendingInstall {
    var component: LinuxComponent? = null
}

object LinuxToolchain {

    /** apt 国内源（Debian 13 = trixie）。 */
    private val DEBIAN_SOURCES = listOf(
        "deb https://mirrors.tuna.tsinghua.edu.cn/debian trixie main contrib non-free non-free-firmware",
        "deb https://mirrors.tuna.tsinghua.edu.cn/debian trixie-updates main contrib non-free non-free-firmware",
        "deb https://mirrors.tuna.tsinghua.edu.cn/debian-security trixie-security main contrib non-free non-free-firmware",
    ).joinToString("\n")

    /**
     * 每个组件一套自包含脚本。
     *
     * 开头统一换源；`set -e` 保证任一步失败即中止，避免留下半装状态。
     */
    private fun scriptOf(component: LinuxComponent): String {
        val head = """
            set -e
            export DEBIAN_FRONTEND=noninteractive
            export TMPDIR=/tmp
            cat > /etc/apt/sources.list <<'SRC'
            $DEBIAN_SOURCES
            SRC
            apt-get update -qq
        """.trimIndent()

        val body = when (component) {
            LinuxComponent.PYTHON -> """
                apt-get install -y -qq --no-install-recommends python3 python3-pip python3-venv curl ca-certificates
                curl -fL --retry 3 https://ghfast.top/https://github.com/astral-sh/uv/releases/latest/download/uv-aarch64-unknown-linux-gnu.tar.gz -o /tmp/uv.tgz
                mkdir -p /tmp/uvx && tar -xzf /tmp/uv.tgz -C /tmp/uvx
                find /tmp/uvx -name uv -type f -exec cp -f {} /usr/local/bin/uv \;
                chmod 755 /usr/local/bin/uv
                rm -rf /tmp/uv.tgz /tmp/uvx
                echo "python3: ${'$'}(python3 --version 2>&1)"
                echo "uv: ${'$'}(/usr/local/bin/uv --version 2>&1)"
            """.trimIndent()

            LinuxComponent.NODE -> """
                apt-get install -y -qq --no-install-recommends curl ca-certificates xz-utils
                curl -fL --retry 3 https://cdn.npmmirror.com/binaries/node/v22.20.0/node-v22.20.0-linux-arm64.tar.xz -o /tmp/node.tar.xz
                tar -xJf /tmp/node.tar.xz -C /usr/local --strip-components=1
                rm -f /tmp/node.tar.xz
                echo "node: ${'$'}(node --version 2>&1)"
                echo "npm: ${'$'}(npm --version 2>&1)"
            """.trimIndent()

            LinuxComponent.SSH -> """
                apt-get install -y -qq --no-install-recommends openssh-server openssh-client
                mkdir -p /run/sshd /root/.ssh
                chmod 700 /root/.ssh
                echo "ssh: ${'$'}(ssh -V 2>&1)"
            """.trimIndent()

            LinuxComponent.APK -> """
                apt-get install -y -qq --no-install-recommends curl ca-certificates unzip openjdk-21-jre-headless
                curl -fL --retry 3 https://ghfast.top/https://github.com/skylot/jadx/releases/download/v1.5.1/jadx-1.5.1.zip -o /tmp/jadx.zip
                rm -rf /opt/jadx && mkdir -p /opt/jadx && unzip -q -o /tmp/jadx.zip -d /opt/jadx
                JADX_BIN="${'$'}(find /opt/jadx -type f -path '*/bin/jadx' | head -n 1)"
                [ -n "${'$'}JADX_BIN" ] || { echo "jadx 解压异常：未找到 bin/jadx"; exit 1; }
                chmod 755 "${'$'}JADX_BIN"
                ln -sf "${'$'}JADX_BIN" /usr/local/bin/jadx
                mkdir -p /opt/apktool
                curl -fL --retry 3 https://ghfast.top/https://github.com/iBotPeaches/Apktool/releases/download/v2.11.1/apktool_2.11.1.jar -o /opt/apktool/apktool.jar
                printf '#!/bin/sh\nexec java -jar /opt/apktool/apktool.jar "${'$'}@"\n' > /opt/apktool/apktool
                chmod 755 /opt/apktool/apktool
                ln -sf /opt/apktool/apktool /usr/local/bin/apktool
                rm -f /tmp/jadx.zip
                echo "jadx: ok"
                echo "apktool: ok"
            """.trimIndent()
        }
        return head + "\n" + body
    }

    /** 安装一个组件；[onLine] 收到脚本输出行。 */
    suspend fun install(
        context: Context,
        distro: LinuxDistro,
        component: LinuxComponent,
        onLine: (String) -> Unit,
    ): Result<Unit> {
        if (!LinuxRuntime.isReady(context, distro)) {
            return Result.failure(IllegalStateException("请先安装 Linux 环境"))
        }
        onLine("→ 开始安装 ${component.title}")
        // 读取线程在后台，回传时切到主线程刷 UI
        val main = Handler(Looper.getMainLooper())
        val result = LinuxRuntime.exec(
            context = context,
            distro = distro,
            command = scriptOf(component),
            timeoutMs = 30 * 60 * 1000L,
            onLine = { raw -> if (raw.isNotBlank()) main.post { onLine(raw.trim()) } },
        )
        return if (result.ok) {
            LinuxRuntime.refreshVersion(context, distro, component)
            onLine("→ ${component.title} 完成")
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("安装失败（退出码 ${result.exitCode}）"))
        }
    }
}
