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
| P12 | 图标体系重做（364 枚自建矢量图标集全量替换 370 处引用） | 完成（真机验证） |
| P13 | AI 回答区重构（Segment 时间线 + 流式管线 + 思考块 + 工具卡 + 异常矩阵） | 完成（真机验证） |

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

## 1.1 图标体系（P12，已完成）

**需求**（用户原话）：应用内所有图标「换更高级点，颜色丰富点，不要表情包」。

**现状**：全部 Material 图标已清零，改用自建单色矢量图标集。

| 项 | 值 |
|---|---|
| 图标库 | `图标库/ai-agent-icons.zip` → 364 枚，24dp 网格、2.0dp 主描边 / 1.6dp 细节描边 |
| 资源位置 | `core/designsystem/src/main/res/drawable/ic_*.xml`（1.4 MB） |
| 编译期索引 | `core/designsystem/.../core/design/icon/AgentIcons.kt`（`@DrawableRes val ic_xxx`） |
| 替换规模 | **370 处引用、42 个文件**，Material 符号 109 个 → 我方图标，映射 1:1 无遗漏 |
| 出口 | `MiuixIconRes(iconRes, ...)` 与 `MiuixIcon(iconRes, ...)` 重载 |

### 为什么走「`@DrawableRes` + 重载」而不是 `ImageVector`

1. **资源不占 Dex**：矢量 XML 由 AAPT2 编译并参与资源合并，不需要在 Dex 里再放一份路径数据；
   364 枚图标的 Kotlin `ImageVector` 字面量会让 Dex 明显变大。
2. **单色可 tint**：图标本身只表达「画什么」，颜色一律由调用点从主题取，深色/浅色/Monet 自动跟随。
3. **重载而非改签名**：`MiuixIconButton` / `MiuixListItem` / `MiuixTopAppBar` / `MiuixButton` /
   `MiuixMenuItem` / `MiuixEmptyState` 都加了 `iconRes` 重载，**图标按钮的按压效果只有一份实现**
   （统一收敛到 `MiuixIconButtonImpl(painter)`），不会出现「资源版和矢量版手感不一样」。

### 顺带修掉的问题

- **APK 体积 −9 MB（33.9 MB → 24.8 MB）**：`material-icons-extended` 在 12 个模块里被声明但
  已零引用，已全部移除。
- ⚠️ 移除后暴露一个**离线构建坑**：Gradle 缓存里只有 `material-icons-core 1.7.8`，
  而 `material3` 传递要求 **1.6.0**，之前是靠 extended 把版本顶上去的。删除 extended 后
  即使 `--offline` 也会去下载 1.6.0 而失败。现在改为在消费 `material3` 的模块里显式声明
  `libs.androidx.compose.material.icons.core`（版本对齐），并在 Version Catalog 里注明
  「这只是替 material3 钉住传递版本，不是在用它的图标」。

### 刻意没做的事

- **`miuix-icons` 已删除引用**：0.9.2 的 `miuix-icons` 只有约 160 个符号，且语义与 Material
  不对齐（`Delete` ↔ `trash-2` 这类映射无法机械批替换）。第一方图标集覆盖面更完整，
  两套图标混用只会让风格分裂。
- **品牌图标（11 个 `ic_brand_*`）保持原样**：它们是彩色品牌标识，不属于单色功能图标体系。

## 1.2 AI 回答区（P13，已完成）

按 UI/动效 Spec 重构。**顶栏与输入栏结构未动**，只做流式联动。

### 分层

```
feature/home/.../agent/
  AgentSegments.kt      Segment 数据模型（思考/旁白/工具/正文/系统卡）
  AgentEvents.kt        网络层事件（纯数据，零 Android 依赖）
  AgentTimeline.kt      reducer：事件流 → 有序 Segment（可单测）
  AgentStreamSink.kt    接口 + AgentStreamBus（跨模块转发流式事件）
  AgentChatState.kt     回答区状态宿主（隔离重组 + 时钟 + 自动收起）
  AgentText.kt          全部文案集中一处
  ToolVisuals.kt        工具 → 图标 / 中文名 / 动词文案 / L1 摘要
  DemoRunner.kt         演示入口开关（仅 debug）
  stream/TokenBuffer.kt      Token 节流（必选项）
  stream/MarkdownChunker.kt  块级增量解析
  stream/AutoScrollTracker.kt 自动滚动三态
  ui/                    ThinkingBlock / ToolCard / AnswerBlock / SystemCard / AgentMessageList / AgentAtoms
```

### 关键实现点

- **状态独立性**：展开态下沉到 Segment 自身字段（`isExpanded` / `isDetailExpanded`），
  LazyColumn `key = segment.id`，每段用局部动画。唯一允许的全局联动是「答案首 token
  到达后延时 600ms 自动收起」，且**跳过 `userToggled == true` 的段**。
- **流式管线**：`TokenBuffer` 节流 → `MarkdownChunker` 块级增量 → `closedBlocks`（永不重排）+ `tail`
  （每帧重解析）。未闭合围栏内整块按代码渲染，避免「先正文、闭合后跳成代码」的闪烁。
- **文字选中走系统**：正文包在 `SelectionContainer`，复制按钮只写系统剪贴板，**无自建浮层**。
- **异常矩阵全分支**：`SystemKind` 枚举 12 项（断网/中断/stall/停止/断点/401/429/5xx/拦截/截断/空回答/只有工具），
  每项都有图标 + 文案 + 操作，无 `else -> 什么都不显示`。

### 按实测偏离 Spec 的三处参数（都是为了让验收线真正达标）

| 项 | Spec | 实现 | 原因 |
|---|---|---|---|
| `folmeSpring` 参数顺序 | `spring(response, dampingRatio)` | **`folmeSpring(damping, response)`** | 真签名是这个顺序；Spec 写反了，照抄会静默变成「阻尼 0.35、响应 0.85s」的慢晃 |
| TokenBuffer 提交间隔 | 50ms | **80ms**，且 `forceChars` 20 → **64** | 条件是「token 数 ≥3 **且** 间隔 ≥50ms」。20 字符 × 每 token 4 字符 = 5 个 token = 75ms，会抢在 50ms 之前成为实际瓶颈，200 token 提交 50 次、**达不到 Spec 的 ≤40 次**。改后约 38 次 |
| 静默兜底 | 未提 | **新增 `maxSilenceMs = 250ms`** | 「与」逻辑在**稀疏 token**（每 300ms 一个字）下会饿死：时间早到了但永远攒不够 3 个 token，画面一动不动 |
| 工具图标 | Material Symbols 五档字重 | 自建图标集（单色 + tint） | 第一方图标覆盖更准；五档字重对单色线性图标无意义 |

### 演示脚本（仅 debug）

真实流式要服务商 API Key，本机没有。`AgentDemoScript` 用**真实事件类型**喂**真实状态机与组件**，
只替换网络来源，用于视觉与状态走查。入口是顶栏那枚 ★（`DemoRunner.enabled = BuildConfig.DEBUG`），
**必须由人点一下才播**——不能无条件自动播放，否则用户每次进对话页都会看到一个凭空出现的假回答。

### 单测（30 项，`feature/home/src/test`）

- `TokenBufferTest`（11）：首 token 立即提交、**每 token 都走首 token 分支导致节流失效的回归**、
  「与」语义、稀疏 token 不饿死、200 token <40 次提交、背压不丢字、reset
- `AgentTimelineTest`（13）：乱序（tool_result 先到）、缺失 tool_result 判超时、
  中断定格、自动收起跳过 userToggled、审批卡、**乐观占位卡让位的回归**
- `MarkdownChunkerTest`（6）：闭合缓存不重解析、半截围栏不误判、流式逐字与一次性解析结果一致

### 实测发现并修掉的三个真 bug

1. **每 token 都提交**：`pendingTokens == 1` 被当作「首个 token」判据，实际上它表示
   「距上次提交攒了 1 个」——节流完全失效（200 token 200 次提交）。
2. **乐观占位卡不让位**：点发送时插的空回答卡留在了最顶上，过程块被挤到它下面；
   而且它还是「最后一段流式正文」，最终回答会被写进这张位置错误的卡。
3. **正文凭空消失**：`onDelta` 只喂了 chunker、没写回 timeline，占位卡让位后
   **正文没有任何 Segment 承载**，界面上整段正文不见了。

### 第二轮修复（用户实测反馈的 8 个问题）

交付后的第一版在真机上被找出 8 个问题。其中 5 个是**同一个架构错误**的不同表现：
过程时间线只活在内存里，而消息只存「角色 + 纯文本」，两者是两份独立的真相。

| # | 现象 | 根因 | 修复 |
|---|---|---|---|
| 1 | 点停止后仍在继续跑 | `job.cancel()` 打不断阻塞的 `readLine()`，socket 还在推数据 | 记录在跑的连接，取消时 `disconnect()`；实测 mock 端抛 `BrokenPipeError`，上游真的停了 |
| 2 | 上滑后又被自动拽回底部 | 停止滚动时会做一次「距底近 → Anchored」的复位；且跟随滚动的 key 含 `segments`（思考计时每 500ms 换实例 → 每 500ms 滚一次） | 用户离开底部后**粘住 Drifted**；跟随的 key 只取「消息条数 + 尾部长度」，且 Drifted 时一个像素都不动 |
| 3 | 新问题带上之前几轮的思考与回答；新会话也带着上个会话内容 | `bindTurn` 只换 id、**不清时间线**，新事件的增量被追加到旧过程后面 | `bindTurn` 先 `resetTurn()` + `timeline.reset()` + 清空 `segments` |
| 4 | 重新生成变成「在旧回答下面继续」 | 重跑时把旧的 `segmentsJson` 原样带回去 | 原地重来要**清空**旧内容与旧时间线，同一条消息内重写 |
| 5 | 发消息会闪退 | `syncList()` 用 `SnapshotStateList.clear()+addAll()`，中间的**空列表**被 LazyColumn 观察到，拿旧 index 去 get → `IndexOutOfBoundsException` | `segments` 改成不可变列表 `mutableStateOf(emptyList())`，整体替换；顺带获得「按 equals 跳过未变段」的收益 |
| 6 | 点停止没有思考 / 工具 / 回答记录 | 停止时只把纯文本写回，过程从未落盘 | 每轮先落一条 `Running` 消息占位，过程按 500ms 节流 upsert 到**同一条**消息；收尾写终态 |
| 7 | 思考 / 工具进行中杀后台，记录消失 | 时间线只在内存 | 同 #6：时间线随消息落盘，冷启动从 `segmentsJson` 恢复 |
| 8 | 右上角多了个测试按钮 | 我为验收加的演示入口 | 删掉按钮，改为读启动 Intent 的 extra（`--es eta_demo full`），零界面痕迹 |

**另修 3 个在修复过程中新暴露的真 bug**（都是实测发现，不是读代码猜的）：

1. **同一段正文被渲染两遍**（一遍正常、一遍带 `**` 原文）
   `MarkdownChunker.current()` 把**内部可变列表**直接交出去，调用方持有的对象随后继续增长，
   于是「已闭合块」与「尾块」内容重叠。改为交快照 `toList()`；并把正文渲染收敛成
   **单一路径**（此前「解析后的块」与「未闭合围栏整块当代码」两条路径会同时出内容）。
2. **空气泡**：`Started` 会预建一张空 `AnswerSegment`，请求中途死掉时它留在消息里，
   界面上渲染成一个空白气泡。改为**惰性创建**正文段；无内容的轮次只显示恢复卡。
3. **冷启动后这轮仍在「流式」**：进程被杀留下的 `Running` 轮次被当成还在跑，
   光标一直闪、计时从 `00:00` 重来。`ChatStore.load()` 现在把 `Running` 一律定型为
   `Interrupted`，并把时间线里仍在 `Running` 的段一并冻结（工具段按超时兜底）。

### 第三轮修复（轮次边界相关的 3 个问题）

| # | 现象 | 根因 | 修复 |
|---|---|---|---|
| 1 | 工具调用记录出现在回答**下面** | `AiChatClient` 对**每一轮**都发 `onDelta`，而收尾时又对工具轮发一遍 `onNarration`——同一段文字被送进两个桶：正文区已开始生成「总回答」，工具卡却被追加在它后面 | 工具轮的正文在收到 `onNarration` 时**原地**改标成旁白段（同 id、同位置），工具卡自然排在它下面 |
| 2 | 已显示对勾的思考胶囊，计时一直涨（截图里的 `00:37`） | 只在整轮 `Finished` 时才把 `Running` 的思考标成 `Done`。一次回答常有五个来回的「思考 → 工具」，前面的思考在第 2 轮就该停表 | 新增 `sealThinking(round, includeSameRound)`：**轮次边界**即终点。同轮出现旁白/工具调用 ⇒ 本轮思考已产出完毕；出现下一轮思考或最终正文 ⇒ 更早的思考必然已结束。`tickElapsed` 只推进 `status == Running` 的段 |
| 3 | 中间回答与总回答同时生成 | 同 #1 | 同 #1：过渡正文不再落在正文区，因此「总回答」只在所有步骤走完后才开始 |

**另修一个我在这轮引入又回滚的回归**：为了让「过渡旁白」不与「最终回答」混在一起，
我先改成「整轮正文先缓冲、收尾再按性质派发」——但那会**牺牲逐字流式**
（最终轮要等全部收完才一次上屏）。已回滚，改用上面「原地改标」的方案：
逐字流式与正确顺序两者兼得。

**回归测试**（`AgentTimelineTest` 新增 4 条，合计 35 项）：

- `工具轮的过渡正文不算最终回答`：断言段序为
  `Thinking → Narration → ToolCall → Answer`，且正文段只有一段、内容不含旁白
- `已完成的思考不再继续计时`：封印后 `tickElapsed(+37s)` 返回 false，`elapsedMs` 不变
- `多轮思考各自独立停表`：第一轮封印、第二轮仍在跑；最终正文出现后第二轮也封印
- `Started 重复触发是幂等的`：不再预建占位段，也不清同轮已有内容

### 第四轮修复（过程组交互与排版）

| # | 现象 | 根因 | 修复 |
|---|---|---|---|
| 1 | 组头展开了就收不回去 | `onToggle = { state.toggleGroup(row.id, row.autoExpanded) }` —— 传的是**默认值**而不是当前值。全部结束时默认恒为 `false`，于是每次点击都算出 `!false = true`，永远展开 | 当前展开态只算一次、同时喂给 `expanded` 与 `onToggle` |
| 2 | 组内思考内容无法展开 | `ThinkingInline` 只画一行摘要，**没有任何点击处理** | 整行可点，展开完整正文（复用超长窗口逻辑）；`orb` 用加深底色反馈展开态 |
| 3 | 左侧竖线接不到卡片边框 | 竖线写死 `120dp`：组内两项时比内容还高（垂出去一截），五六项时又够不到最后一项 | 按行数推算总高（`ROW_APPROX_HEIGHT * n`），并向下收进半行高，使线的两端落在首/末项的竖直中线上 |
| 4 | 用户气泡整体偏左 | ① `Column` 用了 `weight(1f, fill = false)`，而 `weight` 会先按权重分配空间，与 `Arrangement.End`（靠右）意图冲突；② 右侧呼吸位照搬设计稿的 56dp，在手机宽度下把气泡推得过左 | 去掉 `weight`（固定 `widthIn(max)` + 父级 `End` 对齐即可）；呼吸位 56dp → 12dp；多行时不留 |

**验证方式说明**：uiautomator 在本机不稳定（记忆里已记过），且组头坐标会随展开态变化
（第一次验证时用固定坐标，结果点到了「思考行」而不是组头，把四块全展开了，差点误判）。
最终改用**截图哈希**验证往返：折叠态点击两次后与初始态 md5 完全一致（`07068df2…`），
证明「折叠 → 展开 → 折叠」闭环成立。教训：**布局变化后必须重新取坐标**。

### 第五轮修复（到底判据的输入栏内缩）

**用户口径**：「点回到底部应该是内容结尾在输入框上面，即复制全文那些按钮在输入框上面」。

| 项 | 根因 | 修复 |
|---|---|---|
| 内容结尾被输入栏盖住 | 判据写的是「末项底边 = **视口底边**」，但输入栏是 `Box` 里 `align(BottomCenter)` **浮在列表之上**的，所以贴着视口底边其实还被盖住（实测截图：`复制全文` 那一行压在输入栏下面） | 判据改成「末项底边 = 视口底边 − 输入栏高度」 |
| 流式期间跟随不生效 | 同一个式子在自动跟随里也算错（见下），且额外设了 `FOLLOW_MAX_PX = 240` 的上限——输入栏有 499px 高，所以最多仍能藏 240px | 去掉上限（「用户在看历史」已由状态机管：一上手就是 Drifted），跟随也扣输入栏 |

**「距底多少像素」这个式子我连续写错两版，值得记下来**：

```kotlin
// ① 少扣输入栏：贴着视口底边 ≠ 看得见
viewportEnd - itemOffset - itemSize
// ② clamp 抹掉底边信息：高于视口的项恒返回 0，永远判成「已到底」
itemSize - (viewportEnd - itemOffset).coerceIn(0, itemSize)
// ③ 正解：最朴素的减法
(target - (itemOffset + itemSize)).coerceAtLeast(0)   // target = viewportEnd - 输入栏
```

第 ② 版害处最大：最后一条长回答**高于视口**时它恒为 0，于是自动跟随里
`scrollBy(0)`（完全不跟）、「回到底部」里提前 `return`（只滚到长回答开头）。
两处症状看起来是两个 bug，其实是同一个错算。

**定位手段**：这次没有继续猜，而是临时打日志把布局数值打出来——
`bottomPadding=166.33dp px=499` 一眼确认内缩本来就预留了，问题在判据式子；
滚动收敛也由 `隐藏 761px → 补 → 隐藏 0px → 到底` 得到确证。日志用完已清理。

### 第六轮修复（工具重试）

**用户口径**：「点重试，状态栏多了一行字，但是是 bug 因为他太高了」
→ 由此牵出真正的问题：**重试在冷启动后根本用不了**，只能弹提示。

| 项 | 根因 | 修复 |
|---|---|---|
| 点重试没反应、只弹一行提示 | `AgentTimeline.restore()` 里调了 `reset()`，而 `reset()` 会清空 `toolIndex`；工具卡却靠这个索引定位（`upsertTool` / `retryTool` 都查它）。**收敛前重建索引这一步漏了** | `restore()` 末尾重建索引，并新增 `maxRound()`（供后续续写当轮次基址） |
| 重试被绑死在「是不是当前轮」 | 上一轮把「能不能重试」与「是不是当前轮」判成同一件事，于是切页/重启后（`state.messageId` 已解绑）一律不可用 | 历史轮次也支持：新增 `AgentSegmentCodec.patchToolSegment` 直接改写该消息的 `segmentsJson` |
| 工具重试期间发的消息会被静默丢弃 | `ChatRunner.runToolOnce` 复用了 `ChatRunner.job`，执行期间 `isBusy()` 为真 | 已修：工具重试改用独立 `toolJob`。**注意实际表现比「卡住」轻**——`ChatRunner.running` 只在 `start()` 里赋值，重试期间它为 null，所以界面仍显示「可发送」，只是 `start()` 在 `if (isBusy()) return` 处静默忽略，那条消息停在「正在生成」，需重发一次 |
| 重试按钮无条件出现 | 只要 `status` 是 `Failed` / `Timeout` 就给按钮 | 改为须同时满足：①该轮被异常打断（`Interrupted` / `Failed`，`Running`、`Done` 不给）②这张卡是**最后一个段**（后面还有正文/系统卡/别的工具卡说明模型已往下答，重试改变不了后文） |

**这次的教训（同类错误第二次犯）**：定位「重试没反应」时，我先基于**未经核实的假设**
（以为历史轮次拿不到工具参数）设计了一套复杂方案，写了一半才发现：看一眼
`AgentSegmentCodec` 就知道 `args` 一直是有落盘的。**先 grep 再设计**，成本一条命令。

**负向验证**：`restore` 的回归测试做过反向确认 —— 临时移除修复后它确实失败
（`restore 之后工具卡仍可被 (round,seq) 定位`），确认它抓的是这个 bug 而不是恒真。

**另一处顺手纠错**：`AgentSegmentCodec` 的字段名与 `patchToolSegment` 写入的键逐一对过
（`round` / `seq` / `status` / `startedAt` / `endedAt` / `result` / `awaiting` / `risk` / `toggled`），
写入端与解码端必须一致，否则改完读不回来 —— 这类 bug 在编译期完全看不出来。

**已解决**：`runToolOnce` 改用自己的 `toolJob`，与 `isBusy()` 解耦
（`isBusy` 的语义收窄为「有没有对话在跑」）。`stop()` 会一并取消它。

**触发条件（当时为什么难发现）**：需同时满足 ①在当前轮点重试 ②该工具执行耗时较长
③这几秒内想发新消息。历史轮次的重试**不经过** `ChatRunner`，不受影响。
表现也不是崩溃或转圈，而是「那条消息停在正在生成、重发一次就好」——所以不容易被注意到。

### 架构调整：一条消息 = 一条时间线

```
ChatMessage
  ├─ role / content（纯文本，用于「首条用户消息」推导标题、搜索等）
  ├─ segmentsJson（这一轮的完整过程时间线，JSON）
  ├─ turnState（Running / Done / Interrupted / Failed）
  ├─ imageUris / attachments（气泡里画缩略图与 chip）
  └─ modelContext（只发给模型、不上屏的附件上下文）
```

渲染上，「历史消息」与「当前时间线」不再是两组：
每轮结束就把过程写进**对应的那条消息**，列表里只有「按时间排列的消息」。
这一条改动同时消掉了顺序倒置（#3）与「第二个问题没有工具卡」——
后者的机制是引擎每次请求都从 `round = 0` 开始计数，而工具的定位索引是 `(round, seq)`，
不清空时间线时第二个问题的 `(0,0)` 会**命中第一个问题留下的那张卡**，只更新旧卡不新建。

## 1.3 终端改进（本轮）

### 已做

| 项 | 说明 |
|---|---|
| **右边距** | xterm 容器加 `padding-left: 10px / padding-right: 6px`。左侧是文字起点、给更多余量；右侧只避免横向滚动条。外层再 `clipToBounds()`，内容再宽也不撑出页面滚动 |
| **未配置也显示 banner** | 抽出 `TerminalBanner`，ASCII art 由 rootfs 脚本与应用侧**共用一份**。环境没装时由应用直接写进 xterm：「Yutu Agt 终端 / 环境未安装 / 环境未配置，暂时无法使用 / 请前往 AI 配置界面」。未就绪时不再尝试启动 shell（必然失败且报错无指导意义） |
| **加号先选环境** | 弹「本机环境 / Linux 环境」。本机 = 宿主 Android shell（不用 Root、不用 rootfs）；Linux = 需 Root 的 chroot 环境。两种窗口可同时开，Tab 标题区分（`本机 N` / `Linux N`） |
| **接入 AI** | 顶栏按钮 → 问一句想问什么 → 取终端当前输出（去 ANSI、尾部 6000 字符、注明截断）作为一条用户消息投进会话 → 跳回对话页。走 `ChatStore` 而非直接调模型，回答会正常落库、可继续追问 |

### 本机环境的一个硬限制（重要）

**本机模式（`Env.LOCAL`）当前不可用 —— 连字都输不进去。**

- 现象（实测）：`sh` 检测到 stdin 不是 tty → 走非交互模式 → **不打印提示符**，
  且**输入的文字送不进去**。整块区域等于死的。
- ⚠️ 本文档早先写的是「命令仍能执行，只是没提示符」（`ls` 无颜色、`top` 报 not a tty）。
  那是**未经验证的推断，已被实测否定**——不要据此以为本机模式「勉强能用」。
- 状态：**用户明确表示「先别修」**，方案 B 记在下方待办。**修好前不要把它描述成可用。**

原因是拿 pty 需要 `forkpty`，而 Android 上现成的载体是 busybox 的 `script`：

| 事实 | 实测结果 |
|---|---|
| `/system/bin/script` | **不存在**（系统 toybox 没有这个 applet） |
| busybox 的 script | 在 `/data/adb/ksu/bin/busybox`，**应用进程无权访问**（`inaccessible or not found`） |
| 替代工具 | 设备上无 `socat` / `expect` / `tmux` |
| rootfs 内的 `script` | 存在且应用可访问（私有目录），但**要求 Linux 环境已安装**，与「本机模式不用装环境」的前提冲突 |

所以本机模式主动在终端里写了一段说明，把「能用但没提示符」讲清楚，不让用户对着空白猜。

### 踩过的两个坑

1. **「验证无 root 能否工作」必须在应用身份下测。**
   我最早在 root shell 里跑 `script -qfc` 成功，就断言「无 root 也能拿 pty」——
   那是错的：`script` 来自 root 的 PATH（busybox），应用根本执行不到。
   后来用 `su <app-uid> -c` 复测才发现。
2. **改了代码没重新构建就测试，会得出「修复无效」的错误结论。**
   `TerminalBanner.kt` 的修复在 19:03，而当时装的 APK 是 18:53 构建的；
   我据此以为 logo 修复失败、还去改动 art，反而把它改坏了。
   **判据**：对比 APK 的 `lastUpdateTime` 与源文件 mtime，一致才测。

### banner 的转义规则（写清楚，别再踩）

`TerminalBanner.ART` 写在 Kotlin 字符串里，`"\\ \\ / /_"` 解析后是 `\ \ / /_`（屏幕上要看到的）。
之后**只有 rootfs 脚本那一路会再过一次 shell**，而它用 `echo '...'` 单引号包裹 ——
单引号内 shell 不解析反斜杠，所以输出与 ART 一致。

实测两种写法输出相同：

```sh
printf '%s\n' '\ \ / /_   _| |_ _   _'   # 正确
echo '\ \ / /_   _| |_ _   _'             # 正确
```

⚠️ 但**收尾的换行必须是真的换行**。早先写 `append("'\\n")`（Kotlin 的
`\n` 是「反斜杠 + n」两个字面字符），导致所有行挤成一行、`printf %s\n`
被原样打出来。**别再删 art 里的反斜杠「躲坑」**——删了 logo 的斜线笔画就没了。

### 待办清单（按「能不能做」分类）

判定依据是**依赖是否具备**，不是重要性——不具备依赖的项先写清缺什么，
避免后来者反复去试一条走不通的路。

#### A. 缺依赖 / 缺系统能力（暂时做不了）

| 项 | Spec | 卡在哪 |
|---|---|---|
| **朗读回答** | §5.7 操作条 | **没有 TTS 实现**。菜单项已存在并置灰。要做需接 `android.speech.tts.TextToSpeech`：初始化异步、语言包可能缺失、需随生命周期 shutdown。 |
| **侧边栏重构** | §9 全节 | **0.9.2 没有 `miuix-preference`**：`SwitchPreference` / `SliderPreference` / `OverlaySpinnerPreference` 全不存在。要做得先用 `miuix-ui` 官方组件自建等价物。属独立模块，不应混在回答区做。 |
| **玻璃层 / 实时模糊** | §2.2、§9.3 | **0.9.2 没有 blur API**（`miuix-blur` 整包不存在）。做要自研：`RenderEffect.createBlurEffect`（API 31+）+ `graphicsLayer`，低端机降级纯色。 |
| **内嵌浏览器「返回对话」胶囊** | §7.4 | 现用 `WebScreen`（有顶部进度条），但**没有底部「返回对话」浮层**。属 web 模块改动。 |
| **本机终端的 pty（待办 B，用户已选定）** | §10 终端 | 本机模式目前**完全不可用**（连输入都送不进去，不只是没提示符）。方案 B：**借用已装 Linux 环境 rootfs 里的 `script`**（`/data/data/<pkg>/files/linux-env/<distro>/rootfs/bin/script`，已实测应用身份可访问）。代价：本机模式将**要求 Linux 环境已安装**，与「不用装环境」的原始前提冲突——用户已知悉并选择此路线。 |

#### B. 依赖具备，尚未开工

| 项 | Spec | 要点 |
|---|---|---|
| **「引用回复」自定义选区 action** | §5.6 | 回答区**目前没有任何选区操作菜单**（不是「只有系统菜单」——早先这条描述有误）。Spec 允许在系统菜单之外追加自定义 action（把选中文字作为引用插入输入框）。`SelectionContainer` 本身不提供扩展点，需走 `ActionMode` 回调注入。 |
| **正文 `[1]` 角标 ↔ 来源列表双向联动** | §5.3、§7.4 | 点角标滚到并高亮来源卡（1.2s 消退）；点来源卡跳到正文首次引用处。需给 Markdown 行内解析加「引用角标」类型 + 锚点定位。 |
| **多答案分支切换** | §5.7 | 「1/3」页码 chip + `AnimatedContent` 横向淡入淡出。数据层要先支持「一轮多答案」。 |
| **工具步骤 >5 时折叠最早几个** | §7.3 | 目前是**整组**折叠（`ProcessGroupCard`），没有「>5 时把最早的折成『显示前面 N 个步骤』按钮」。原 `revealedSteps` 已随重构删除。 |
| **并行工具聚合胶囊「N 个任务进行中」** | §7.6 | ⚠️ **先确认要不要做**：`AiChatClient` 里工具是 `forEachIndexed` **串行**执行的（全文件无 `async` / `awaitAll`），所以「并行任务」这个状态**目前不存在** —— 任何时刻最多 1 张卡是 Running，做出来只会显示「1 个任务进行中」。要真做得先把后端改成并发执行。 |
| **审批卡真正被触发** | §7.5 | 数据层（`ToolNeedsApproval` / `awaitingApproval`）与 UI 都已就绪，但**后端从不发这个事件** —— 需要先定义「哪些工具、什么条件算危险操作」的策略。 |
| **异常矩阵：`Blocked` 与 `Truncated`**（严格说属 A 类，`Blocked` 缺判据） | §8.3 | **401 / 403 / 429 / 5xx / 超时 / 网络中断已完成**（`AiFault.kt` 的 `FaultMapper`：401、403 → `AuthRequired`；429 → `RateLimited` 并读 `Retry-After`；5xx → `ServerError`；超时 → `Stall`；IO 失败 → `NetworkLost`）。剩两类未做：`Blocked`（内容安全拦截）**各家标记不统一**——OpenAI 走 `finish_reason=content_filter`，也有服务商放在 `error.code`，无统一判据；`Truncated` 需解析流式 `finish_reason == "length"`，属响应体解析层。 |
| **TalkBack 播报节流** | §8.3 | 「状态变化播报节流到 1 次/2s」未做；目前只有 segment 级 `contentDescription`。 |
| **折叠屏 / 横屏锚定** | §8.3 | 旋转后按 `scrollAnchorId` 恢复位置未做。 |
| **历史会话分页** | §8.3 | 500+ 条时 `LazyColumn` 虚拟化够用，但 Room 分页未做（且见下条 Room 的取舍）。 |
| **Markdown 缺的结构** | §5.3 | **图片 `![]()`、脚注引用 `[1]`、LaTeX** 三种未支持；任务列表、表格、代码块、引用块已支持。 |
| **动效逐条走查** | §10、§13 | Spec 第 13 节验收清单的「逐条打勾 + 46 项状态走查」未做。 |
| **工具重试后的「续写」** | §8.3 | **用户已明确要求，尚未开工**。目标：被异常打断时保住「问题 + 已跑完的工具结果」，点重试后从那儿接着生成。**必须先解决的约束**：`ChatMessage.content` 只存最终正文，工具调用与结果从未进过消息历史，模型不知道自己拿到过什么，续写出来的内容会对不上。做法：给 `ChatMessage` 加「只发给模型、不上屏」的工具轨迹（建议加 `toolTrace: List<ToolTrace>` 并同步改 `ChatStore` 读写两侧，**不要**塞进 `content`），再让续写走一条**不重置时间线**的路径（`onStarted` 的 `resetTurn()` 与 `bindTurn` 的 `timeline.reset()` 都会清空既有内容），且新一轮 `round` 要用 `AgentTimeline.maxRound()` 当基址，否则与已有工具卡撞 `(round, seq)`。仍需用户确认：是「重跑工具后拿新结果续写」还是「不重跑、直接用已保存结果续写」。 |
| **工具重试的并发保护** | —（Spec 未涉及） | 重试期间不应允许再发对话请求（会与工具卡互相打架），已由 `toolJob` + `isBusy()` 双向挡住；`stop()` 一并取消。**仅剩一点未定**：同一时刻只支持一次工具重试，连点两下第二下会被拒（返回「正在生成中」提示）——是否要给多张卡排队重试，暂无需求。 |
| **重组次数实测** | §13 | `TokenBuffer` 单测覆盖了**提交次数**（200 token <40 次），但**Compose 真实重组计数**没有测。 |

#### C. 有意的偏离（不是遗漏，别再去「补」）

| 项 | Spec 要求 | 实际做法与理由 |
|---|---|---|
| **持久化** | §8.2 Room 增量 upsert | **用 `ChatStore` + JSON 文件**达到等价语义（500ms 节流 + `onStop` 强制落库 + 断点卡片）。理由：不为持久化引 KSP/Room 全链路，且现有 JSON 方案已验证。**不要**在没有充分理由时改成 Room。 |
| **`SavedStateHandle`** | §8.2 只存 conversationId + anchor + 草稿 | 未使用。当前时间线**随消息一起落盘**（`ChatMessage.segmentsJson`），恢复能力已覆盖它要解决的问题。 |
| **工具 schema 全量下发** | —（Spec 未涉及） | 内置工具 104 个（2026-10-05 精简自 117）的 JSON Schema 每轮固定占约 **16k prompt token**（实测 completion 只有 8–9 token 时 prompt 仍 16k）。对 1M 窗口只占 1.6%，暂时无碍；若要做**按需下发**（按会话场景筛相关工具）另开一项，不是 bug。 |
| **Squircle 圆角** | §2.1 `SmoothRoundedCornerShape` | 真 API 是 `Modifier.squircleSurface/Clip/Background/Border`（0.9.2 无形状类）。已封成 `core/designsystem/.../shape/MiuixShapes.kt` 的 `sq*` 别名。 |
| **`folmeSpring` 参数顺序** | 全文写 `spring(response, damping)` | 真签名是 **`folmeSpring(damping, response)`**。已在 `AgentMotion` 里按真签名封装。 |

### 仍未验证（需要有真实服务商才测得出来）

- **Anthropic 原生分支**：无密钥，未测。OpenAI 兼容端点与自建网关**已用真实响应验证**
  （用户 2026-10-05 确认）。据此把早先「解析层完全没跑通」的说法更正掉——那是
  本地网关不可达时的状态，已不再成立。
- **工具链路是否随真机验证过**：未向用户确认。若只测了纯文本回答，则工具链
  （`tool_call` / `tool_result` / 过程块）仍是未验证状态，别默认它通了。
- **`onStall` 触发**：本地 mock 覆盖到「POST 已发出、SSE 已开始流、取消时上游收到
  `BrokenPipeError`」，脚本留在 `/data/local/tmp/eta/mock_stop_server.py`。
- **工具结果不进消息历史**：`ChatMessage.content` 只存最终正文，工具调用与结果只在
  `segmentsJson` 里供显示。**影响**：模型在后续轮次里看不到自己调过什么工具、拿到了
  什么结果；「重试后续写」必须先把这一条补上（见 B 类待办）。
- **46 项状态走查**：Spec 第 11 节列了 46 项，代码里都有分支，但未逐项在真机确认。

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
