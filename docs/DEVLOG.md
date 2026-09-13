# 开发日志（DEVLOG）

> 本文件保留项目的开发过程记录：阶段进度、构建环境细节、踩过的坑。
> 面向使用者的说明请看根目录的 [README.md](../README.md)。

> 开发者工具箱 + MCP 客户端 / 内置 MCP Server 的 Android 工程。
> 应用名：MCP Toolbox（工程代号 `mcp-toolbox`，包名 `com.mcp.toolbox`）。

## 1. 当前状态

| 阶段 | 内容 | 状态 |
|---|---|---|
| P0 | 脚手架 + Version Catalog + 设计系统（Token/组件/Gallery）+ 主题引擎 + 调色盘设置页 | 完成（真机验证） |
| P1 | 首页、设置、抽屉导航、宽屏自适应 | 完成（真机验证） |
| P2 | 文件 + 应用 | 完成（真机验证） |
| P3 | 网页 + 网络（HTTP/Ping/DNS/端口扫描/Whois/网络环境） | 完成（真机验证） |
| P4 | 数据库（SQLite 引擎、Schema/数据/查询三个视图、示例库） | 完成（真机验证） |
| P5 | 抓包 | 暂停（按需求先不做，页面为占位） |
| P6 | 反编译（真实引擎）+ 任务中心 | 完成（真机验证） |
| P7 | MCP 客户端 + Schema 表单 + 内置 Server + 产物目录 + 外置 SAF 目录 | 完成（真机验证，含 streamable HTTP 与 legacy SSE） |
| P8 | 打磨：动效、无障碍、性能、双语、README | 进行中 |

P8 已完成：

- 图标按钮触控目标统一到 48dp（`Modifier.miuixTouchTarget`，视觉尺寸不变、命中区放大），真机 dump 校验 0 违规
- 图标按钮补 `Role.Button` 语义（dumpsys 中节点 class 变为 `android.widget.Button`）
- 修复网页页顶部黑块：`AndroidView(WebView)` 的绘制溢出到自身边界之外，覆盖了顶栏/标签条/地址栏，
  给 `AndroidView` 加 `Modifier.clipToBounds()` 后正常
- 网页页标签项高度 40dp → 48dp，使标签内的关闭按钮触控达标
- 本文件按真实进度对齐

P8 待办：字号 1.3× 破版检查、Koin 收尾、动效统一走 `MotionTokens`、文案抽到 `strings.xml`（双语）。

## 2. 设备侧构建（本工程的实际构建方式）

本工程在 **Android 设备上的 Debian(PRoot) 环境** 中构建，Android SDK 位于设备本地。

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-arm64
export PATH=$JAVA_HOME/bin:$PATH
export GRADLE_USER_HOME=/storage/emulated/0/gradlehome     # 复用依赖缓存，可换任意目录
GRADLE=<gradle-9.6.0>/bin/gradle
$GRADLE --console=plain -p <项目根> :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`。

构建请**同步执行**（同一次调用内等它跑完）。用异步后台任务时进程会被 SIGKILL（exit 137），
单模块增量构建约 35–55s。

### 2.1 aapt2 的关键处理（重要）

Google 只为 x86_64 发布 `aapt2`，本机是 aarch64，直接使用 SDK 内的 `aapt2` 会 `Exec format error`。
方案：用 `qemu-user` 直通执行官方 x86_64 aapt2，并通过 Gradle 属性替换：

```properties
# gradle.properties
android.aapt2FromMavenOverride=<工具目录>/aapt2-bin/aapt2
```

`<工具目录>/aapt2-bin/aapt2` 是一个可执行桥接脚本（注意：文件名必须以 `aapt2` 结尾，
否则 AGP 会以 “does not point to an AAPT2 executable” 报错）：

```sh
#!/bin/sh
exec /usr/bin/qemu-x86_64 -L /usr/lib/x86_64-linux-gnu <工具目录>/aapt2-x86/aapt2 "$@"
```

依赖：`apt install qemu-user-static libc6:amd64 libstdc++6:amd64`（已装）。
在 x86_64 主机上构建时，删除 `android.aapt2FromMavenOverride` 一行即可回退为官方 aapt2。

### 2.2 设备内存注意事项

设备可用内存约 2–3 GB，`org.gradle.jvmargs=-Xmx1280m`、
`kotlin.compiler.execution.strategy=in-process`、`org.gradle.workers.max=1` 是按此调优的结果；
构建前建议清掉残留的 Gradle/Kotlin 守护进程，否则容易被系统 LMK 杀掉（exit 137）。

## 3. 权限清单（AndroidManifest）

| 权限 | 用途 | 阶段 |
|---|---|---|
| INTERNET / ACCESS_NETWORK_STATE | MCP HTTP/SSE 传输、网络诊断、WebView | P3/P7 |
| POST_NOTIFICATIONS / FOREGROUND_SERVICE(_DATA_SYNC) | 抓包前台服务 | P5 |
| PACKAGE_USAGE_STATS | 应用使用统计 | P2 |
| READ_EXTERNAL_STORAGE(≤32) / READ_MEDIA_IMAGES | 文件与媒体浏览 | P2 |
| QUERY_ALL_PACKAGES | 应用列表与包信息 | P2 |
| VPN_SERVICE（抓包） | HTTPS 解密走本地 VPN + CA 证书 | P5 |

## 4. 已知限制与降级

- **Miuix 组件库**：未直接依赖 `top.yukonga.miuix.kmp`（其 API 在本地不可校验），
  改为在 `core:designsystem` 内按 Miuix 视觉规范自绘一套组件，并保留同一套 Token API；
  后续如需切换官方 Miuix，只替换 `component/` 包实现即可。
- **DI**：仍是最小手写容器（`ToolboxApplication`）；Koin 已在 Version Catalog 声明（4.2.2）但尚未接入。
- **字体**：MiSans / HarmonyOS Sans 未随包分发，回退系统默认字体（Token 已预留 fontFamily）。
- **抽屉**：实现为“按钮/边缘滑动打开 + 左滑（累积位移判定）或点遮罩关闭”，未做逐帧跟手位移。
- **抓包（P5）**：按需求暂停，页面是明确标注阶段的占位页，不做假界面。
- **无障碍**：图标按钮触控目标已统一 48dp；`feature/web` 受父容器约束处（如 40dp 行高）已随本轮一并抬高。
- 未实现的模块统一落到占位页，明确标注所属阶段，不做假界面。

## 5. 工程结构

```
app/                    壳工程：MainActivity、AppShell（抽屉 + 底栏 + 宽屏 NavRail）、导航图
core/common/            格式化、Result、Dispatcher 抽象
core/model/             领域模型：MCP Server/Tool/Call、Artifacts 会话
core/designsystem/      DesignToken、主题引擎（HCT/莫奈）、组件库、ComponentGallery
feature/home/           首页（Banner + 搜索 + 九宫格 + 最近任务 + 真实 MCP 状态）
feature/settings/       设置与「主题与色彩」页（取色圆盘 + 参数滑杆）
feature/files/          文件浏览
feature/apps/           应用管理
feature/web/            WebView 多标签浏览器（真实前进后退、地址栏、阅读模式、下载）
feature/network/        HTTP 请求、Ping、DNS、端口扫描、Whois、网络环境
feature/database/       SQLite：Schema / 数据 / SQL 查询（只读）
feature/decompile/      反编译（真实引擎）+ 任务中心
feature/mcp/            MCP 客户端、内置 Server（SSE + streamable HTTP）、产物目录、Schema 表单
feature/capture/        抓包（P5，暂停）
```

## 6. 设计系统速查

- 圆角：卡片 = 全局尺度（默认 20dp）、Dialog/Sheet = ×1.4、输入框 = ×0.6、卡片内圆角 = 外圆角 − 8dp
- 间距：4dp 网格；页面水平 16dp；行高 56/64dp；分组间距 12dp
- 按压态：缩放 0.98 + 表面轻微变暗，**无水波纹**（`Modifier.miuixClickable`）
- 触控目标：所有可点击控件 ≥48dp（`Modifier.miuixTouchTarget`，视觉尺寸不变、只放大命中区）
- 动效：`spring(StiffnessMediumLow, 0.9)`；页面进入淡入上移 12dp；时长倍率 0.5×–1.5× 可调
- 配色：`material-color-utilities` 的 HCT + DynamicScheme；动态取色走 Android 12+ 系统壁纸
- 代码区：底色比内容区更暗（深色 #121212），语法高亮固定三色（关键字紫粉/字符串青绿/类型浅蓝）

## 关于页：自动检查更新 + 开源项目入口（v0.1.1）

- 新增 `app/src/main/kotlin/com/mcp/toolbox/ui/UpdateChecker.kt`：
  用 `HttpURLConnection` 请求 `GET /repos/guanhan01/Yutu-Toolbox/releases/latest`，
  比较 `tag_name` 与 `BuildConfig.VERSION_NAME`。版本比较按 `.`、`-`、`+` 分段取开头数字，
  缺失段记 0，故 `1.2` 与 `1.2.0` 相同，预发布版本（如 `1.0.0-beta1`）不判定为新。
  任何失败降级为 `UpdateState.Failed`，不阻塞页面其余内容。
- `AboutScreen` 在 `LaunchedEffect` 里进页自动检查一次，点该行可手动重查；
  有新版本时出现「前往下载」按钮，跳转对应 Release 页面。
- 新增「开源项目」卡片：项目主页与 Apache-2.0 许可，点击经 `Intent.ACTION_VIEW` 打开。
- 开启 `buildConfig = true`，版本号改为读 `BuildConfig.VERSION_NAME`
  （原先在 `strings.xml` 硬编码为 `0.1.0-p0`，与实际版本不符）。
