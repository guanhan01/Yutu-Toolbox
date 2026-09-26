# Yutu Agt

> 装在你手机里的 AI Agent：直连你自己的模型服务商，用 100+ 内置工具操作这台设备。

主界面就是一个 AI 对话窗口。你用自然语言说需求，模型通过 function calling 调用本机工具去执行——
读文件、装应用、抓包、反编译 APK、跑 Linux 命令、点屏幕、截屏，全部在这台手机上完成，不经任何中转服务。

```text
你：看看 /sdcard/Download 里那个 apk 是干嘛的
AI：[调用 file.tree] → [调用 apk.info] → [调用 apk.permissions] → 组织答案
```

包名 `com.Yutu.Agent`（Beta：`com.Yutu.Agent.beta`）· 最低 Android 8.0（API 26）

## 它为什么不是一个普通工具集

多数「手机开发工具箱」是一堆互不相干的页面，功能点进去、做完、退出来，人来回搬运信息。
Yutu Agt 把这些能力做成**模型可调用的工具**，于是它们可以被编排：

- 模型能连续调用多个工具，把前一步的输出喂给后一步（上面的例子就是三步链式调用）
- 工具结果落成可回看的步骤卡片，工具调用与思考过程按真实发生顺序混排
- 内置 MCP Server 还能把工具反过来暴露给**外部** AI 客户端（Claude Desktop、Cursor 等），
  让桌面上的助手直接操作你的手机——对外按白名单只放出 31 个只读 / 低风险工具（见下）

## AI Agent

| 能力 | 说明 |
|---|---|
| 服务商 | 11 家内置 + 自定义 OpenAI 兼容端点：OpenAI、Anthropic Claude、Google Gemini、DeepSeek、月之暗面 Kimi、智谱 GLM、通义千问、腾讯混元、硅基流动、xAI Grok、Mistral |
| 配置粒度 | 每家独立保存密钥、Base URL、模型列表与自定义请求头，互不干扰；API Key 用系统 Keystore 加密后存本机 |
| 模型管理 | 从服务商拉取可用模型列表、手工添加、设置上下文窗口、标注是否支持思考 |
| 流式与推理 | 流式输出；支持思考档位（Off → Max 八档，滑杆调节），推理内容折叠展示 |
| 多模态 | 图片作为缩略图附件送入；文件 / 文件夹 / 路径作为文本上下文 |
| 工具调用 | 103 个工具经 function calling 接入，调用过程实时显示为可展开的步骤卡片 |
| 上下文管理 | 用量比例显示、超阈值自动压缩（旧对话转摘要，原文保留可撤销） |
| 记忆 | 核心记忆（每轮注入）+ 子记忆（模型按需创建），token 用量受模型窗口约束 |
| 计划模式 | 先出总计划与子任务，再逐步执行，进度条常驻 |
| Skill | 导入 Markdown / ZIP / 文件夹 / URL / JSON / 剪贴板六种来源的自定义 Skill，以「名称 + 描述」注入系统提示词，正文按需读取 |
| 会话 | 多会话本地保存，支持重命名、删除、消息级复制 / 重说 / 编辑 |

## 内置 MCP 工具（103 个）

| 分组 | 主要工具 |
|---|---|
| 设备与系统 | `device.info` `system.battery/memory/storage/wifi/prop/sensors/top/notifications/power` `process.list` `logcat.read` `settings.get/put` |
| 文件 | `file.list/read/readRange/write/replace/search/grep/tree/hash/du/info` `file.copy/move/rename/delete/mkdir/chmod` `file.zip/unzip` |
| 应用 | `app.list/info/permissions/components/dataDir/export/install/uninstall/launch/enable/disable/clearData` `package.info/forceStop` |
| APK 与反编译 | `apk.info/manifest/permissions/components/entries/signature/resources/export/extract/smali/sources/java` `dex.list/class/search/strings/smali/java` `decode.smali` `build.rebuild/assemble/verify/sign` |
| 数据库 | `db.list/tables/schema/rows/query/dump/importCsv` `db.exec`（可写，需开启）`sql.query` |
| 网络与网页 | `http.request` `net.info` `web.fetch` |
| 抓包 | `capture.list/detail/records/stats/export/clear` |
| 界面操作 | `ui.tap/swipe/text/key/tree/current/screenshot` |
| 其它 | `shell.exec` `clipboard.get/set` `keystore.manage` `skill.list/read` `artifact.list` |

**外部 MCP 暴露面是收窄的。** 内置 Server 只对外放出 31 个只读或低风险工具（白名单硬上限），
`file.delete`、`app.uninstall`、`shell.exec`、`ui.tap` 这类改写与提权工具即使被猜到名字，
`tools/call` 也会直接拒绝。模型可用的工具集与外部客户端可用的工具集是两套。

## 工具箱

除 AI 之外，每个能力也都有独立的图形界面：

| 模块 | 内容 |
|---|---|
| 文件 | 目录浏览、多选、内置文本编辑与图片 / 视频查看 |
| 应用 | 应用列表、包信息、冻结 / 解冻 / 强停 |
| 网页 | 多标签 WebView、真实前进后退、地址栏、阅读模式、下载 |
| 网络 | HTTP 请求构造器、Ping / Traceroute、DNS 查询、端口扫描、Whois、网络环境 |
| 数据库 | SQLite 真实打开：Schema / 数据 / SQL 三视图，分页编辑、导入导出 |
| 抓包 | 本地 VPN + 真实 TLS 中间人解密，会话列表与请求详情，导出 HAR / JSON，单条可复制为 cURL |
| 反编译 | jadx / baksmali 真实引擎 + 任务中心，双栏主界面 |
| Linux | Debian 13 / Alpine 按需安装，检测页展示组件状态，含文件浏览与终端 |

### 抓包（HTTPS 解密）

真在设备上跑了一个 TLS 中间人：本机环回端口监听，按流现签 host 证书与客户端握手，
再用真实 SNI 连上游，双向解密 HTTP/1.1 并落成记录。

- **普通 App**：把导出的 PEM/DER 装进用户证书区即可（Android 7+ 应用默认不信任用户 CA）
- **系统信任区**：可生成 Magisk / KernelSU 模块把 CA 铺进系统信任区（Android 14+ 由 `service.sh`
  覆盖 Conscrypt APEX 证书目录），卸载模块或重启即还原
- **抓不到的会如实标注**：目标应用启用证书固定、或不信任用户 CA 时，记录标记为「未解密」而非静默丢弃

### Linux 环境

按需下载的私有 rootfs，提供 Debian 13（glibc）或 Alpine（musl）两选一。

可单独安装、单独探测版本的组件：

| 组件 | 内容 |
|---|---|
| Python / uv | uv 与最新正式版 Python |
| Node.js | Node.js 与 npm |
| SSH | sshd、ssh-keygen、ssh-agent |
| APK 分析 | JADX、Apktool、smali、baksmali（含 OpenJDK 前置依赖） |
| OpenJDK | 可单独安装，也是「APK 分析」的依赖 |
| Git / Codex CLI / Claude Code | 命令行工具与两个 AI CLI（后两者需 Node.js） |

下载支持多镜像并发测速选源、断点续传（严格校验 `Content-Range` 总长）、zip 中央目录完整性校验，
中断后可续装。rootfs 全程不随应用打包。

> 执行后端是系统 chroot，**安装与运行需要 Root**。免 Root 的 PRoot 路线实测在 Android 16 上
> 走不通（自身能启动，但一进入目标程序就 `execve` 失败），因此没有合入。

## 高权限能力（可选）

冻结应用、读写私有目录、安装系统级 CA 等需要更高权限，三种后端按可用性自动降级：

| 后端 | 说明 |
|---|---|
| **无障碍服务** | 免 Root。开启后 `ui.*` 与截屏走无障碍通道，支持点击、滑动、输入（含中文 / emoji）、读控件树、截屏 |
| **Shizuku** | 推荐。免 Root 拿到 shell 级权限 |
| **Root** | 直接执行 |

无障碍服务只申请 `canPerformGestures`、`canRetrieveWindowContent`、`canTakeScreenshot`，
不申请按键过滤与触摸探索；只在工具被调用时执行单条指令，不会自行操作手机。

## 权限说明

| 权限 | 用途 |
|---|---|
| `INTERNET` / `ACCESS_NETWORK_STATE` | 连接模型服务商、网络诊断、WebView |
| `MANAGE_EXTERNAL_STORAGE` | 文件页直接读取真实目录（授权后无需 SAF 引导） |
| `READ_MEDIA_IMAGES` / `VIDEO` / `AUDIO`、`READ_EXTERNAL_STORAGE`(≤32) | 媒体浏览 |
| `QUERY_ALL_PACKAGES` | 应用列表与包信息 |
| `PACKAGE_USAGE_STATS` | 应用使用统计 |
| `POST_NOTIFICATIONS` + `FOREGROUND_SERVICE`(+`_DATA_SYNC`/`_SYSTEM_EXEMPTED`) | 抓包 VPN 与反编译任务的前台服务 |
| `BIND_ACCESSIBILITY_SERVICE` | 免 Root 界面操作（由用户在系统设置里手动开启） |

**应用不收集、不上传任何数据。** 唯一的外发请求是你自己配置的模型服务商 API 与更新检查；
所有工具都在本机执行，会话、记忆、Skill、抓包记录都只存在本地。

## 构建

```bash
./gradlew :app:assembleStableDebug :app:assembleBetaDebug
```

产物：

- `app/build/outputs/apk/stable/debug/app-stable-debug.apk` — 正式版
- `app/build/outputs/apk/beta/debug/app-beta-debug.apk` — Beta

两个 flavor 使用不同 `applicationId`（`.beta` 后缀），可同机共存；Beta 不联网检查更新。

### 在 ARM64 Android 设备上构建

Google 只为 x86_64 发布 `aapt2`，aarch64 设备直接调用 SDK 内的 aapt2 会 `Exec format error`。
可以用 `qemu-user` 直通执行官方 x86_64 aapt2 绕过：

```sh
# 桥接脚本，文件名必须以 aapt2 结尾，否则 AGP 会报 "does not point to an AAPT2 executable"
#!/bin/sh
exec /usr/bin/qemu-x86_64 -L /usr/lib/x86_64-linux-gnu /path/to/aapt2-x86/aapt2 "$@"
```

依赖 `qemu-user-static`、`libc6:amd64`、`libstdc++6:amd64`，然后在**本地** `gradle.properties`
（不要提交）里加一行：

```properties
android.aapt2FromMavenOverride=/path/to/aapt2
```

x86_64 主机上构建不需要这一步。设备内存有限时建议加上：

```properties
org.gradle.jvmargs=-Xmx1280m -XX:MaxMetaspaceSize=512m
org.gradle.workers.max=1
org.gradle.parallel=false
```

## 工程结构

14 个 Gradle 模块，约 5.1 万行 Kotlin。`app` 是壳，`core:*` 是共享底座，`feature:*` 每个是一块可独立编译的能力。

```
app/                      壳工程：MainActivity、AppShell（抽屉 + 底栏 + 宽屏 NavRail）、导航图、AI 设置与对话宿主
core/common/              Result、Dispatcher、特权管理（Root / Shizuku / 无障碍）、无障碍服务
core/model/               领域模型
core/designsystem/        DesignToken、主题引擎（HCT 动态取色）、Miuix 风格组件库
feature/home/             AI 对话：消息流、工具步骤卡片、记忆、压缩、计划
feature/settings/         设置与「主题与色彩」
feature/files/            文件浏览
feature/apps/             应用管理
feature/web/              WebView 浏览器
feature/network/          网络工具
feature/database/         SQLite 查看器与编辑器
feature/decompile/        反编译引擎 + 任务中心
feature/capture/          抓包：VPN 引擎、TLS 中间人、CA 管理、导出
feature/mcp/              MCP 客户端 / 内置 Server / 103 个工具 / Skill 管理 / 产物目录
```

设计规范见 [DESIGN.md](DESIGN.md)，版本变更见 [CHANGELOG.md](CHANGELOG.md)，
开发过程记录见 [docs/DEVLOG.md](docs/DEVLOG.md)。

## 已知限制

- **Linux 环境需要 Root**：执行后端是系统 chroot，免 Root 的 PRoot 路线在 Android 16 上不可用
- **抓包受证书固定限制**：目标应用启用 certificate pinning 或拒绝用户 CA 时只能看到未解密的记录
- **界面操作依赖 ROM**：无障碍通道在部分定制 ROM 上 `takeScreenshot` 可能被限制，此时回落 Root / Shizuku
- **未依赖官方 Miuix**：`top.yukonga.miuix.kmp` 的 API 无法在离线环境校验，改为按其视觉规范自绘组件，
  保留同一套 Token API；将来切换官方库只需替换 `core:designsystem` 的 `component/` 包实现
- **依赖注入仍是手写最小容器**：Koin 已在 Version Catalog 声明但尚未接入
- MiSans / HarmonyOS Sans 未随包分发，回退系统默认字体
- `settings.gradle.kts` 配置了阿里云镜像（部分网络下 dl.google.com 会被重置），
  检测到 `CI=true` 时自动跳过，境外网络也可自行移除

## 合规声明

抓包（含 HTTPS 解密）与反编译能力**仅限用于你自有或已获得明确授权的应用**的学习与调试，
禁止用于破解、篡改他人应用。应用首次启动会展示该声明并要求确认。
所有数据仅保存在本机，默认不上传任何用户数据。

## 许可

[Apache License 2.0](LICENSE)
