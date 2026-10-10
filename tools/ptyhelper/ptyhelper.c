/*
 * Yutu Agt —— 「Android 环境」终端窗口的 pty 载体。
 *
 * 为什么需要它
 * ------------
 * Android 上给 shell 分配伪终端只有一条路：forkpty(3)。系统自带的那套工具里
 * 没有能代劳的程序（toybox 没有 script applet），busybox 的 script 又在
 * root 独占目录里 —— 免 root 时应用既看不到也执行不到。
 *
 * 所以这里自带一个最小实现，随 APK 分发、落地到执行者有权读到的位置后运行。
 * 它只做一件事：forkpty -> 子进程 exec 目标 shell -> 父进程在 stdin 与
 * pty master 之间搬字节。等价于 busybox 的 `script -qfc`，但：
 *
 *   · 静态链接，不依赖 Android 上不存在的 glibc 或 rootfs 里的任何东西
 *   · 不需要 root（forkpty 是普通 libc 调用，untrusted_app 域即可使用）
 *   · 与调用者的通信**只走 stdin/stdout/stderr**，不落任何共享文件
 *
 * 用法
 * ----
 *   ptyhelper --rows 24 --cols 80 -- <cmd> [args...]
 *
 * pty 从端路径通过 stderr 上的一行 `EPTY=<path>` 上报。
 *
 * 为什么用 stderr 上报、而不是写一个双方约定的文件
 * ------------------------------------------------
 * 这个 helper 会被三种身份执行（root / shell(2000) / 应用自身），而三者能写的
 * 目录互不相同：应用写不了 /data/local/tmp（SELinux 拒），root 与 shell 又进不去
 * 应用私有目录。任何「写一个文件让对方读」的方案都会在某一种身份下静默失败。
 * stderr 是三者都现成可用的通道，且调用方本来就要在收终端画面之外单独接它，
 * 所以这里不引入任何新依赖。
 *
 * 退出码：3 = forkpty 失败；2 = 参数错误；其余透传子进程退出码。
 *
 * 编译（可复现，见 tools/ptyhelper/build.sh）
 * -----------------------------------------
 *   静态链接、aarch64、strip。产物约 65 KB。
 *
 * 这份源码属于本工程，作者即宿主工程作者；内不含任何第三方标识。
 */

#define _GNU_SOURCE
#include <pty.h>
#include <unistd.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <sys/select.h>
#include <sys/ioctl.h>
#include <sys/wait.h>

/* 上报 pty 从端的标记前缀。宿主按这一行解析，改名要同步改 PtyHelper.kt。 */
#define EPTY_PREFIX "EPTY="

static void note(const char *s) { (void)!write(2, s, strlen(s)); }

static int parse_int(const char *v, int def)
{
    char *end = NULL;
    long n = strtol(v, &end, 10);
    if (end == v || *end != '\0' || n <= 0 || n > 100000)
        return def;
    return (int)n;
}

int main(int argc, char **argv)
{
    int rows = 24, cols = 80;
    int i = 1;

    for (; i < argc; i++) {
        if (!strcmp(argv[i], "--rows") && i + 1 < argc) {
            rows = parse_int(argv[++i], 24);
        } else if (!strcmp(argv[i], "--cols") && i + 1 < argc) {
            cols = parse_int(argv[++i], 80);
        } else if (!strcmp(argv[i], "--")) {
            i++;
            break;
        } else {
            break;
        }
    }
    if (i >= argc) {
        note("ptyhelper: no command given\n");
        return 2;
    }

    /* 尺寸必须在 forkpty 那一刻给定：pty 建立后再设会漏掉最初几行输出。 */
    struct winsize ws;
    memset(&ws, 0, sizeof ws);
    ws.ws_row = (unsigned short)rows;
    ws.ws_col = (unsigned short)cols;

    int master = -1;
    pid_t pid = forkpty(&master, NULL, NULL, &ws);
    if (pid < 0) {
        char buf[192];
        int n = snprintf(buf, sizeof buf, "ptyhelper: forkpty failed: %s\n", strerror(errno));
        if (n > 0)
            (void)!write(2, buf, (size_t)n);
        return 3;
    }

    if (pid == 0) {
        /* 子进程：已是新会话的领头进程，fd 0/1/2 都指向 pty 从端。 */
        execvp(argv[i], &argv[i]);
        _exit(127);
    }

    /* 把从端路径交出去：/proc/<child>/fd/0 的链接目标就是 /dev/pts/N。
     * 宿主拿它做 `stty -F <pts> rows R cols C`，与 Linux 窗口那条 resize 同源。 */
    {
        char link[64], target[128];
        int n = snprintf(link, sizeof link, "/proc/%d/fd/0", (int)pid);
        if (n > 0) {
            ssize_t len = readlink(link, target, sizeof target - 1);
            if (len > 0) {
                target[len] = '\0';
                char line[192];
                int m = snprintf(line, sizeof line, EPTY_PREFIX "%s\n", target);
                if (m > 0)
                    (void)!write(2, line, (size_t)m);
            }
        }
    }

    signal(SIGPIPE, SIG_IGN);
    signal(SIGTERM, SIG_IGN);
    signal(SIGHUP, SIG_IGN);

    int stdin_open = 1;
    for (;;) {
        fd_set rf;
        FD_ZERO(&rf);
        if (stdin_open)
            FD_SET(0, &rf);
        FD_SET(master, &rf);
        int maxfd = master > 0 ? master : 0;

        int ready = select(maxfd + 1, &rf, NULL, NULL, NULL);
        if (ready < 0) {
            if (errno == EINTR)
                continue;
            break;
        }

        char buf[8192];

        /* pty -> 宿主 stdout。读到 0/负值说明 shell 已退出或 pty 已挂断，此时收工。 */
        if (FD_ISSET(master, &rf)) {
            ssize_t n = read(master, buf, sizeof buf);
            if (n <= 0)
                break;
            ssize_t off = 0;
            while (off < n) {
                ssize_t w = write(1, buf + off, (size_t)(n - off));
                if (w <= 0)
                    break;
                off += w;
            }
        }

        /* 宿主 stdin -> pty。
         *
         * stdin 读到 EOF 时**不能直接退出循环**：子进程可能还没结束，
         * 它的提示符、报错、退出前输出都还在 pty 里等着读。
         * 只把这一路关掉，继续把 pty 读干净，直到从端挂断。 */
        if (stdin_open && FD_ISSET(0, &rf)) {
            ssize_t n = read(0, buf, sizeof buf);
            if (n <= 0) {
                stdin_open = 0;
            } else {
                ssize_t off = 0;
                while (off < n) {
                    ssize_t w = write(master, buf + off, (size_t)(n - off));
                    if (w <= 0)
                        break;
                    off += w;
                }
            }
        }
    }

    int status = 0;
    waitpid(pid, &status, WNOHANG);
    if (WIFEXITED(status))
        return WEXITSTATUS(status);
    return 0;
}
