# 开发日志（DEVLOG）

> 本文件保留项目的开发过程记录：阶段进度、构建环境细节、踩过的坑。
> 面向使用者的说明请看根目录的 [README.md](../README.md)。

> AI Agent 式开发者工具箱 + MCP 客户端（外部 Server 接入）的 Android 工程。
> 应用名：Yutu Agt（工程代号 `mcp-toolbox`，Kotlin 包名 / namespace 仍是 `com.mcp.toolbox*`，
> applicationId 为 `com.Yutu.Agent`，Beta 加 `.beta` 后缀）。

## 1. 当前状态

| 阶段 | 内容 | 状态 |
|---|---|---|
| P0 | 脚手架 + Version Catalog + 设计系统（Token/组件/Gallery）+ 主题引擎 + 调色盘设置页 | 完成（真机验证） |
| P1 | 首页、设置、抽屉导航、宽屏自适应 | 完成（真机验证） |
| P2 | 文件 + 应用 | 完成（真机验证） |
| P3 | 网页 + 网络（HTTP/Ping/DNS/端口扫描/Whois/网络环境） | 完成（真机验证） |
| P4 | 数据库（SQLite 引擎、Schema/数据/查询三个视图、示例库） | 完成（真机验证） |
| P5 | 抓包 | 已实现：本地 VPN + 真实 TLS 中间人解密、CA 管理、HAR / JSON 导出（`feature/capture` 约 6.5k 行，真机流程待复核） |
| P6 | 反编译（真实引擎）+ 任务中心 | 完成（真机验证） |
| P7 | MCP 客户端 + Schema 表单 + 产物目录 + 外置 SAF 目录（内置 Server 已移除） | 完成（真机验证，含 streamable HTTP 与 legacy SSE） |
| P8 | 打磨：动效、无障碍、性能、双语、README | 进行中 |
| P9 | AI Agent：对话主界面、服务商与自定义供应商、工具调用、记忆、压缩、计划模式 | 完成 |
| P10 | Linux 环境（Debian 13 / Alpine 按需安装 + 组件管理 + 终端） | 完成（运行需 Root） |
| P11 | Skill 工具箱（六种导入源 + 注入系统提示词 + 内置 `cognitive-engine`） | 完成 |

P8 已完成：

- 图标按钮触控目标统一到 48dp（`Modifier.miuixTouchTarget`，视觉尺寸不变、命中区放大），真机 dump 校验 0 违规
- 图标按钮补 `Role.Button` 语义（dumpsys 中节点 class 变为 `android.widget.Button`）
- 修复网页页顶部黑块：`AndroidView(WebView)` 的绘制溢出到自身边界之外，覆盖了顶栏/标签条/地址栏，
  给 `AndroidView` 加 `Modifier.clipToBounds()` 后正常
- 网页页标签项高度 40dp → 48dp，使标签内的关闭按钮触控达标
- 本文件按真实进度对齐

P8 待办：字号 1.3× 破版检查、Koin 收尾、动效统一走 `MotionTokens`、文案抽到 `strings.xml`（双语）。

### 待办 · 未验证项（截至 2026-10-02）

- **外部 MCP 工具接入 AI 的模型往返**：已用本地 mock 服务端（实现 Responses 协议）
  跑通「注入 tools → 模型发起 function_call → 执行工具 → 用 call_id 回填 output →
  第二轮收尾」，工具真实执行并回填成功。**仍未用真实服务商验证**（设备上无 API Key），
  下列分支也没构造用例：推理摘要事件（`response.reasoning_summary_text.delta`）、
  失败事件（`response.failed` / `incomplete`）、带图输入（`input_image`）。
- **无障碍免 root 通道**：需在设备上实测（见 v0.1.5 记录）。

### 待办 · 图标体系重做（用户已确认要做，尚未开工）

**需求**（用户原话）：应用内所有图标「换更高级点，颜色丰富点，不要表情包」；可上网找参考，但不直接套用。

**现状（2026-10-01 用命令核实）**：

- 全部来自 `androidx.compose.material:material-icons-extended:1.7.8` 的 `Icons.Outlined.*`
  单色细线矢量：**122 个不同符号、415 处引用、分布在 42 个文件**。
- 品牌图标 11 个（8 个 XML + 3 个 PNG）：`app/src/main/res/drawable/ic_brand_*`，
  由 `app/.../ui/ai/AiConfig.kt` 的 `AiProvider.iconRes` 引用。
- 统一出口只有一处：`core/designsystem/.../component/Icons.kt` 的
  `MiuixIcon(icon: ImageVector, contentDescription, modifier, tint, size)`。
  除品牌图标走 `@DrawableRes` 外，所有调用点传的都是 `ImageVector`。
- ⚠️ **`miuix-icons` 是死声明**：`gradle/libs.versions.toml:69` 声明了
  `top.yukonga.miuix.kmp:miuix-icons:0.9.2`，但**没有任何模块 `implementation` 它、
  也没有任何代码 import**。接手时不要以为可以直接用。

**待拍板（两条路工作量与结果差别很大，未选定）**：

- A. 换图标库（如 Lucide / Phosphor）：风格立刻改变。代价是逐处改 415 处引用、
  引入新依赖、维护图标名映射表。
- B. 保留现有矢量、给图标加彩色容器／渐变底：改动小、观感提升明显，
  但图形本身仍是 Material 线条风。

**选 A 的注意点**：`Icons.Outlined.X` 是 `ImageVector` 扩展属性，映射不能机械批量替换，
语义并不一一对应（`Delete` ↔ `trash-2`、`DriveFileRenameOutline` ↔ `pencil-line`），
必须逐处核对。

**验收**：编译通过（构建方式见 §2，`GRADLE_USER_HOME=/storage/emulated/0/gradlehome`）
+ 真机逐页截图核对；若动 `MiuixIcon` 签名，需同步全部调用点。

## 2. 设备侧构建（本工程的实际构建方式）

本工程在 **Android 设备上的 Debian(PRoot) 环境** 中构建，Android SDK 位于设备本地。

```bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-arm64   # 路径随发行版更新而变，用 ls /usr/lib/jvm/ 确认实际值
export PATH=$JAVA_HOME/bin:$PATH
export GRADLE_USER_HOME=/storage/emulated/0/gradlehome     # 复用依赖缓存，可换任意目录
GRADLE=<gradle-9.6.0>/bin/gradle
$GRADLE --console=plain -p <项目根> :app:assembleStableDebug :app:assembleBetaDebug
```

产物：`app/build/outputs/apk/stable/debug/app-stable-debug.apk`（正式版）
与 `app/build/outputs/apk/beta/debug/app-beta-debug.apk`（Beta）。

构建请**同步执行**（同一次调用内等它跑完）。用异步后台任务时进程会被 SIGKILL（exit 137），
单模块增量构建约 35–55s。

注意两点：

- **Gradle 发行版要放在 PRoot rootfs 内可访问的位置。** 若从 fuse 挂载点
  （如 `/storage/emulated/0/...`）直接执行，Gradle 9.6 会报
  `Could not create service of type FileSystem ... Mount point not found`
  ——PRoot 的 `/proc/self/mountinfo` 里没有 `/` 项。把发行版复制进容器内再跑。
- **改了资源也要 `clean`。** 增量构建可能不把 `res/values*/strings.xml` 并入
  `resources.arsc`，表现为「代码改了、界面没变」。判据（把 `<新字符串>` 换成实际文本）：

  ```sh
  # 期望输出 True；若为 False，说明资源未并入，需要 :app:clean 后重建
  python3 -c "import zipfile;print('<新字符串>'.encode('utf-8') in zipfile.ZipFile('<apk>').read('resources.arsc'))"
  ```

  同理，**删过类之后必须 `clean`**：旧 dex 残留会让运行时抛
  `ClassNotFoundException`（表现为一启动就闪退，而源码里已经搜不到那个类）。

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

清单按模块分散声明（`app` / `feature:capture` / `feature:decompile`），下表是合并后的全集。

| 权限 | 用途 | 声明处 |
|---|---|---|
| INTERNET / ACCESS_NETWORK_STATE | MCP HTTP/SSE 传输、网络诊断、WebView | app |
| POST_NOTIFICATIONS | 任务完成与抓包的通知 | app |
| FOREGROUND_SERVICE(+_DATA_SYNC / _SYSTEM_EXEMPTED) | 抓包与反编译任务的前台服务 | app、capture、decompile |
| PACKAGE_USAGE_STATS | 应用使用统计（需手动到系统设置开启） | app |
| QUERY_ALL_PACKAGES | 应用列表与包信息 | app |
| READ_EXTERNAL_STORAGE（`maxSdkVersion=32`） | Android 12 及以下的文件读取 | app |
| READ_MEDIA_IMAGES / READ_MEDIA_VIDEO / READ_MEDIA_AUDIO | Android 13+ 的媒体读取 | app |
| MANAGE_EXTERNAL_STORAGE | 文件页读取真实目录、产物目录直写公共存储（未授予时回退 SAF） | app |
| moe.shizuku.manager.permission.API_V23 | Shizuku 免 root 提权后端 | app |

> 抓包用的 `android.permission.BIND_VPN_SERVICE` **不是 uses-permission**，
> 而是 `CaptureVpnService` 上的 `android:permission` 属性（系统要求，写错位置会直接
> 装不上或起不来）。该 service 的 `foregroundServiceType` 是 `systemExempted`。
> `com.Yutu.Agent*.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` 由 AndroidX 自动生成，
> 不是业务权限。

## 4. 已知限制与降级

- **Miuix 组件库**：**已直接依赖**官方 `top.yukonga.miuix.kmp:miuix-ui`（0.9.2，
  `app` 与 `core:designsystem` 各自 `implementation`）。主题层用官方
  `OfficialMiuixTheme` + 自绘 `MiuixColors` 桥接，组件层优先复用官方实现
  （Switch / Slider / Dialog / BottomSheet / OverflowMenu 等），其余按 Miuix 视觉规范自绘。
  注：早前本文写过「未直接依赖、全部自绘」，那是错误的，已按依赖实况更正。
- **`miuix-icons` 是死声明**：`gradle/libs.versions.toml` 声明了
  `top.yukonga.miuix.kmp:miuix-icons`，但没有任何模块依赖、也没有代码引用。
  不要以为可以直接用它换图标。
- **DI**：仍是最小手写容器（`ToolboxApplication`）；Koin 已在 Version Catalog 声明（4.2.2）但尚未接入。
- **字体**：MiSans / HarmonyOS Sans 未随包分发，回退系统默认字体（Token 已预留 fontFamily）。
- **抽屉**：实现为“按钮/边缘滑动打开 + 左滑（累积位移判定）或点遮罩关闭”，未做逐帧跟手位移。
- **抓包（P5）**：**已实现**，不是占位页。`feature/capture` 约 6k 行：本地 VPN
  （`CaptureVpnService`）+ 真实 TLS 中间人解密（`MitmProxy` / `MitmCa`）、CA 管理与
  Magisk/KernelSU 模块导出、HAR / JSON 导出，`Routes.CAPTURE` 指向真实
  `CaptureScreen`（不在占位页集合里）。真机完整流程仍待复核。
- **无障碍**：图标按钮触控目标已统一 48dp；`feature/web` 受父容器约束处（如 40dp 行高）已随本轮一并抬高。
- 未实现的模块统一落到占位页，明确标注所属阶段，不做假界面。

## 5. 版本号与发布

| 渠道 | applicationId | 当前版本 | 更新检查 |
|---|---|---|---|
| 正式版 stable | `com.Yutu.Agent` | 0.1.5（versionCode 6） | 开启 |
| Beta | `com.Yutu.Agent.beta` | 0.1.5-beta（versionCode 6） | 关闭 |

两个 flavor 的 `applicationId` 不同，可同机共存。**升级正式版必须保持
`com.Yutu.Agent` 不变**，否则老用户无法覆盖安装（v0.1.5 之前曾从 `com.mcp.toolbox`
改为 `com.Yutu.Agent`，那次是破坏性变更）。

发版流程：

1. 改 `app/build.gradle.kts` 的 stable `versionCode` / `versionName`（**两个都要加**，
   versionCode 不能复用）。
2. 在 `CHANGELOG.md` 顶部加对应版本的段落（`## v<版本>`）。CI 会从这里抽取 Release 正文。
3. 提交并推送 `main`，然后打 annotated tag `v<版本>` 并推送——tag 会触发 CI 构建与发 Release。

Release 说明的两个坑（都踩过）：

- **正文**：`softprops/action-gh-release` 的 `generate_release_notes` 只会生成一行
  compare 链接。现在改为 `body_path: dist/release-body.md`，内容由
  `scripts/release_body.py` 从 CHANGELOG 抽取，保证仓库日志与 Release 页一致。
- **作者**：workflow 不传 token 时用的是默认 `GITHUB_TOKEN`，Release 的 author 会显示成
  `github-actions[bot]`。**GitHub 没有修改 author 的接口，只能删掉 Release 重建**
  （tag 不受影响）。现在传 `secrets.RELEASE_TOKEN`（回退 `github.token`）。
  重建时若要保住原 APK：先下载 asset、删 Release、建新 Release、再上传同一个文件。

## 6. 工程结构

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
feature/mcp/            MCP 客户端（外部 Server 接入）、产物目录、Schema 表单、Skill 管理
feature/capture/        抓包：VPN 引擎、TLS 中间人、CA 管理与模块导出、HAR/JSON 导出
```

工具集的两段式结构：`feature:mcp` 提供 103 个 `ToolDef`；`app` 模块通过
`BuiltInToolSet.registerExtra { ... }` 再注入 Linux（9 个）与 Agent 状态（3 个），
合计 115 个。这样拆分是为了避免 `feature:mcp` 反向依赖 `app` 的运行时。

AI 服务商分两组（`AiProvider`）：**通用兼容** 4 个（OpenAI / OpenAI Responses /
Gemini / Anthropic，按协议适配、地址自填）与**定制供应商** 11 家厂商预设。
自定义供应商（`CustomProvider`）独立于枚举存在，可添加任意多条、各自选协议。

## 7. 设计系统速查

- 圆角：卡片 = 全局尺度（默认 20dp）、Dialog/Sheet = ×1.4、输入框 = ×0.6、卡片内圆角 = 外圆角 − 8dp
- 间距：4dp 网格；页面水平 16dp；行高 56/64dp；分组间距 12dp
- 按压态：缩放 0.98 + 表面轻微变暗，**无水波纹**（`Modifier.miuixClickable`）
- 触控目标：所有可点击控件 ≥48dp（`Modifier.miuixTouchTarget`，视觉尺寸不变、只放大命中区）
- 动效：`spring(StiffnessMediumLow, 0.9)`；页面进入淡入上移 12dp；时长倍率 0.5×–1.5× 可调
- 配色：`material-color-utilities` 的 HCT + DynamicScheme；动态取色走 Android 12+ 系统壁纸
- 代码区：底色比内容区更暗（深色 #121212），语法高亮固定三色（关键字紫粉/字符串青绿/类型浅蓝）

## 8. 更新检查与关于页（v0.1.1 起）

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
- 该检查读的是 `releases/latest`，所以**发版若想被识别，必须发 Release 而不只是打 tag**
  （见 §5 的发布流程）。
