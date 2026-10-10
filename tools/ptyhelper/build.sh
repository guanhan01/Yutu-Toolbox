#!/bin/sh
# 编译「Android 环境」终端用的 pty 载体（ptyhelper），并放进 app 的 assets。
#
# 为什么必须静态链接
# ------------------
# Android 上没有 glibc，也读不到本工程 rootfs 里的动态库（应用身份只有读权限，
# exec 会被拒）。动态链接的产物一放进 APK 就是「跑不起来」，且报错只有一句
# ENOENT，极难定位。静态产物不依赖任何外部 .so，任何设备上行为一致。
#
# 为什么用 musl 而不是 Debian 自带的 glibc 工具链
# ----------------------------------------------
# glibc 的静态产物仍会在运行时读取 /etc/nsswitch.conf、locale 等宿主路径，
# 在 Android 的 SELinux 域下不保证成功；musl 完全自包含，更省心。
#
# 依赖：本 Debian 容器内 musl-tools（apt-get install -y musl-tools binutils）
#
# 用法：sh tools/ptyhelper/build.sh
set -e

HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
OUT="$ROOT/app/src/main/assets/pty"

CC=${CC:-musl-gcc}
command -v "$CC" >/dev/null 2>&1 || {
    echo "找不到 $CC。先安装：apt-get install -y musl-tools binutils" >&2
    exit 1
}

mkdir -p "$OUT"
# aarch64 是本工程唯一支持的 ABI（见 README 的说明）。
"$CC" -static -O2 -s -o "$OUT/ptyhelper-arm64" "$HERE/ptyhelper.c"
chmod 644 "$OUT/ptyhelper-arm64"

echo "已生成 $OUT/ptyhelper-arm64"
ls -l "$OUT/ptyhelper-arm64"
