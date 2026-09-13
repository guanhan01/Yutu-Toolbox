# MCP Toolbox

> Android 上的开发者工具箱，同时是一个 MCP 客户端和内置 MCP Server。

一个装在手边的多功能工具集——文件、应用、网络、网页、数据库、反编译，
并把这套能力通过 [MCP（Model Context Protocol）](https://modelcontextprotocol.io) 暴露给 AI 助手，
让助手能直接读写手机上的文件、查询数据库、分析 APK。

包名 `com.mcp.toolbox` · 最低 Android 8.0（API 26）

## 功能

### 工具箱

| 模块 | 内容 |
|---|---|
| 文件 | 浏览、长按多选、内置文本编辑与图片/视频查看 |
| 应用 | 应用列表、包信息、冻结 / 解冻 / 强停（需 Shizuku 或 Root） |
| 网页 | 多标签 WebView、真实前进后退、地址栏、阅读模式、下载 |
| 网络 | HTTP 请求、Ping、DNS、端口扫描、Whois、网络环境 |
| 数据库 | SQLite：Schema / 数据 / SQL 查询三视图（只读） |
| 反编译 | 真实引擎（jadx / baksmali）+ 任务中心，双栏主界面 |
| 抓包 | 开发中（当前为明确标注的占位页） |

### MCP

- **MCP 客户端**：连接外部 MCP Server，Schema 驱动的参数表单，支持 streamable HTTP 与 legacy SSE
- **内置 MCP Server**：把应用自身能力暴露为 90+ 工具，供外部助手调用
- **产物目录**：工具调用产生的文件统一落到可配置目录，支持外置 SAF 存储

### 界面

- Miuix 风格自绘设计系统（`core:designsystem`），圆角 / 间距 / 阴影 / 动效全部 Token 化
- 主题引擎：HCT 动态取色、色相环调色盘，圆角与动效倍率可调
- 抽屉导航 + 底部栏，宽屏自动切换 NavRail
- 中英双语

## 构建

```bash
./gradlew :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

### 在 ARM64 Android 设备上构建

Google 只为 x86_64 发布 `aapt2`，aarch64 设备直接使用 SDK 内的 aapt2 会 `Exec format error`。
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

x86_64 主机上构建不需要这一步。

设备内存有限时建议加上：

```properties
org.gradle.jvmargs=-Xmx1280m -XX:MaxMetaspaceSize=512m
org.gradle.workers.max=1
org.gradle.parallel=false
```

## 权限说明

| 权限 | 用途 |
|---|---|
| INTERNET / ACCESS_NETWORK_STATE | MCP 传输、网络诊断、WebView |
| POST_NOTIFICATIONS / FOREGROUND_SERVICE(_DATA_SYNC) | 抓包前台服务 |
| PACKAGE_USAGE_STATS | 应用使用统计 |
| READ_EXTERNAL_STORAGE(≤32) / READ_MEDIA_IMAGES | 文件与媒体浏览 |
| QUERY_ALL_PACKAGES | 应用列表与包信息 |
| VPN_SERVICE | 抓包时本地 VPN + CA 证书解密 |

应用不收集、不上传任何数据，所有工具都在本地执行。

## 高权限能力（可选）

部分功能（冻结应用、读取私有目录、安装系统级 CA 证书）需要更高权限，支持三种后端：

- **Shizuku**：推荐，免 Root
- **Root**：直接执行
- **无特殊权限**：降级为沙箱内可访问的范围

## 工程结构

```
app/                     壳工程：MainActivity、AppShell（抽屉 + 底栏 + 宽屏 NavRail）、导航图
core/common/             格式化、Result、Dispatcher 抽象
core/model/              领域模型
core/designsystem/       DesignToken、主题引擎（HCT）、组件库
feature/home/            首页
feature/settings/        设置与「主题与色彩」
feature/files/           文件浏览
feature/apps/            应用管理
feature/web/             WebView 浏览器
feature/network/         网络工具
feature/database/        SQLite 查看器
feature/decompile/       反编译 + 任务中心
feature/mcp/             MCP 客户端 / 内置 Server / 产物目录
feature/capture/         抓包
```

设计规范见 [DESIGN.md](DESIGN.md)，开发过程记录见 [docs/DEVLOG.md](docs/DEVLOG.md)。

## 已知限制

- 未直接依赖 `top.yukonga.miuix.kmp`（其 API 无法在离线环境校验），改为按 Miuix 视觉规范自绘组件，
  保留同一套 Token API；将来如需切换官方 Miuix，只替换 `component/` 包实现即可
- 依赖注入仍是手写最小容器，Koin 已在 Version Catalog 声明但尚未接入
- MiSans / HarmonyOS Sans 未随包分发，回退系统默认字体
- 抓包模块开发中，占位页明确标注阶段，不做假界面
- `settings.gradle.kts` 配置了阿里云镜像（国内网络下 dl.google.com 会被重置），境外网络可自行移除

## 许可

[Apache License 2.0](LICENSE)
