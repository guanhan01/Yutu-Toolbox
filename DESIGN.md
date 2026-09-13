# DESIGN.md · MCP Toolbox 设计系统

本文件是视觉与交互的唯一事实来源。实现与本文冲突时，先改实现，再更新本文。
所有数值均在代码里以 Token 形式存在（`core/designsystem/.../token`），禁止散落硬编码。

## 1. DesignToken 总表

### 1.1 圆角（`RadiusTokens`，基准 `base` 默认 20dp，可在 0–32dp 调节）

| Token | 派生公式 | 基准值 | 用途 |
|---|---|---|---|
| `sm` | base × 0.5 | 10dp | 小控件、徽标 |
| `md` | base | 20dp | 卡片 |
| `lg` | base × 1.4 | 28dp | Dialog / Sheet 顶部 |
| `field` | base × 0.6 | 12dp | 输入框、下拉 |
| `card` | base | 20dp | 卡片外圆角 |
| `dialog` | base × 1.4 | 28dp | 对话框 |
| `sheet` | base × 1.4 | 28dp | 底部 Sheet 顶角 |
| `inner` | base − 8dp | 12dp | 卡片内圆角（外圆角差 8dp） |
| 胶囊 | `RoundedCornerShape(percent = 50)` | — | 按钮 / Chip / Slider 轨道 / 标签 |

### 1.2 间距（`SpacingTokens`，4dp 网格）

| Token | 值 | 用途 |
|---|---|---|
| `xs / sm / md / lg / xl / xxl` | 4 / 8 / 12 / 16 / 24 / 32 dp | 通用间距阶梯 |
| `pageHorizontal` | 16dp | 页面水平 margin |
| `groupGap` | 12dp | 分组间距 |
| `rowMinHeight` | 56dp | 单行列表项 |
| `rowLargeHeight` | 64dp | 带副标题列表项 |
| `touchTarget` | 48dp | 最小触控目标 |

### 1.3 阴影（`ElevationTokens`）

| Token | 值 | 说明 |
|---|---|---|
| `level0` | 0dp | 平铺卡片 |
| `level1` | 1dp | 弱阴影（Miuix 风格） |
| `level2` | 2dp | Elevated 卡片 / Fab |
| `level3` | 3dp | 溢出菜单 / Toast |
| `darkOutline` | 1dp | 深色模式用 1dp 高光描边替代阴影 |

### 1.4 动效（`MotionTokens`，倍率 0.5×–1.5×）

| Token | 值 | 说明 |
|---|---|---|
| `fast` | 150ms × scale | 颜色/背景切换 |
| `medium` | 300ms × scale | 主题色插值 |
| `slow` | 450ms × scale | 大范围过渡 |
| `reveal` | 400ms × scale | 圆形揭示 |
| `gentle()` | `spring(StiffnessMediumLow, 0.9)` | 全站统一曲线 |
| `bouncy()` | `spring(StiffnessMedium, 0.7)` | thumb 弹跳 |
| `pressScale` | 0.98 | 按压缩放 |
| `pressDarken` | 0.08 | 按压变暗强度 |
| `enterOffsetDp` | 12dp | 页面进入上移距离 |

### 1.5 字体（`MiuixTypography`，缩放 0.85×–1.3×，行高 0.9×–1.4×）

| Token | 字号/行高 | 字重 | 用途 |
|---|---|---|---|
| `displaySmall` | 32 / 40 | SemiBold | 首页 Banner、折叠大标题 |
| `headlineSmall` | 24 / 32 | SemiBold | 二级大标题 |
| `titleLarge` | 20 / 28 | SemiBold | 顶栏标题 |
| `titleMedium` | 17 / 24 | Medium | 卡片/分组标题 |
| `titleSmall` | 15 / 20 | Medium | 小标题 |
| `bodyLarge` | 15 / 22 | Normal | 正文、列表项标题 |
| `bodyMedium` | 14 / 20 | Normal | 常规正文 |
| `bodySmall` | 12 / 16 | Normal | 辅助说明 |
| `labelLarge` | 14 / 20 | Medium | 按钮文字 |
| `labelMedium` | 12 / 16 | Medium | 标签、副标题 |
| `labelSmall` | 11 / 14 | Medium | 角标、计数 |
| `code` | 13 / 18 | Normal（Monospace） | 代码 / SQL 区 |

字体族：MiSans → HarmonyOS Sans → 系统默认（当前实现为系统默认，Token 已预留）。

## 2. 调色盘来源

| 来源 | 实现 | 说明 |
|---|---|---|
| 默认品牌 Seed | `#6750A4` | 与设计稿 HEX 一致 |
| 莫奈动态取色 | Android 12+ `dynamicLight/DarkColorScheme` | 读系统壁纸，映射进 `MiuixColors` |
| 自定义 Seed | HSV 取色圆盘 + HEX 输入框 + 7 枚候选色块 | 实时生效 |
| 预设调色盘包 | 8 套：Miuix 蓝 / 莫奈紫 / 抹茶 / 珊瑚 / 石墨 / 薄荷 / 日落 / 极夜 | 每套含 light+dark |
| 纯色 / AMOLED | `surface`、`background` 压到 `#000000`，容器整体下压 | 开关式 |

配色生成：`material-color-utilities`（HCT + DynamicScheme）。

- 饱和度：Seed 的 chroma × 倍率（0.5–1.5）
- 色彩活力：Vibrant / Tonal / Muted / Expressive / Neutral / Monochrome → 不同 DynamicScheme
- 对比度：Standard 0.0 / Medium 0.5 / High 1.0 / ExtraHigh 1.33（`contrastLevel`）
- 成功 / 警告：M3 方案缺失，用固定色相派生（成功 H=145、警告 H=75）

### 2.1 颜色角色（`MiuixColors`）

对齐 M3 角色命名并扩展语义色与代码色：
`primary / onPrimary / primaryContainer / primaryFixed(Dim) / secondary / tertiary / background /
surface / surfaceDim / surfaceBright / surfaceContainer(Lowest,Low,,High,Highest) / surfaceVariant /
inverse* / outline / outlineVariant / scrim / error / success / warning / codeBackground / codeKeyword /
codeString / codeType / codeComment`。

代码区规则：底色比内容区更暗（深色 #121212 vs 内容区容器 #1C1B1F 量级）；
语法高亮固定三色——关键字紫粉、字符串/数值青绿、类型/列名浅蓝，深浅模式各一套取值。

## 3. 组件规范（`core/designsystem/.../component`）

| 组件 | 关键规范 |
|---|---|
| `MiuixButton` | 5 种变体（Filled/Tonal/Outlined/Text/Elevated）× 3 种尺寸；加载态内嵌环形进度；禁用 0.4 透明度 |
| `MiuixIconButton` / `MiuixFab` / `MiuixExtendedFab` | 圆形/胶囊，按压缩放，`contentDescription` 必填 |
| `MiuixSegmentedButton` | 轨道 `surfaceContainerHighest`，选中段浮起为 `surface` 胶囊 |
| `MiuixSlider` / `MiuixRangeSlider` | 轨道全圆角；thumb 按下 20 → 24dp；可选数值气泡与离散刻度 |
| `MiuixSwitch` | 46×28 轨道 + 22dp thumb，弹簧位移 |
| `MiuixCheckbox` / `MiuixRadioButton` | 20dp 控件，选中主色，动画 150ms |
| `MiuixChip` / `MiuixTag` / `MiuixSuggestionChip` | 胶囊；`MiuixTag` 用于只读/状态（青绿描边） |
| `MiuixCard` | Filled / Elevated / Outlined 三形态；20dp 圆角 |
| `MiuixListItem` / `SuperArrow` / `SuperSwitch` | 56/64dp 行高，1dp 分隔线从文字起处缩进 54dp |
| `MiuixTextField` / `MiuixSearchField` | 12dp 圆角，支持前后缀、清除、错误态、计数 |
| `MiuixDialog` | 28dp 圆角，危险操作红色确认 |
| `MiuixBottomSheet` | 28dp 顶角 + 拖拽把手 + 遮罩 32% 黑 |
| `MiuixToastHost` | 顶部胶囊，2.2s 自动消失，四种语气色 |
| `MiuixTopAppBar` / `MiuixCollapsingTopAppBar` | 56dp；滚动后背景渐显；折叠后大字缩到 65% |
| `MiuixOverflowMenu` / `MiuixMenuItem` | 200dp 宽，危险项红字，复选态对勾 |
| `MiuixLinearProgress` / `MiuixCircularProgress` / `MiuixSkeleton` / `MiuixEmptyState` / `MiuixBadge` | 反馈与占位 |

## 4. 交互原则

1. **无水波纹**：所有可点击元素走 `Modifier.miuixClickable(pressState)` —— 缩放 0.98 + 轻微变暗。
2. **即时生效**：主题参数改完立即重绘，颜色走 `animateColorAsState`（300ms）插值，不生硬跳变。
3. **主题切换动效**：以触发控件为圆心做 circular reveal（P1 接入到抽屉/底栏切换）。
4. **无障碍**：图标按钮必须传 `contentDescription` + `Role.Button`；触控目标 ≥48dp，由 `Modifier.miuixTouchTarget()`
   统一放大命中区（视觉尺寸不变；父容器约束更小时服从父容器）；字号放大到 1.3× 不破版。
5. **危险操作**：卸载 / 冻结 / 删除 / HTTPS 解密 / 数据库写，一律红色 + 二次确认 + 可撤销。

## 5. 与设计稿的对应关系

| 图 | 页面 | 对应实现 |
|---|---|---|
| 图 3 | 主题与色彩 | `FeatureSettingsScreen` + `ColorWheelPicker`（已实现） |
| 图 5 | 组件总览 Gallery | `ComponentGalleryScreen`（已实现） |
| 图 2 | 主界面 + 汉堡抽屉 | `AppShell` + `DrawerContent`（已实现，二级菜单可折叠、含 badge） |
| 图 6 | 首页 | `HomeScreen`（已实现 Banner/搜索/九宫格/最近任务/状态条） |
| 图 7 | 文件 | `FilesScreen`（已实现） |
| 图 9 | 网络 | `NetworkHubScreen` + HTTP / Ping / DNS / 端口扫描 / Whois / 网络环境（已实现） |
| 图 10 | 抓包 | `CaptureScreen`（P5 暂停，占位页） |
| 图 4 | 数据库 | `DatabaseScreen`（Schema / 数据 / SQL 三视图，已实现） |
| 图 3（反编译） | 反编译 | `DecompileScreen` + 任务中心（真实引擎，已实现） |
| — | MCP | `McpScreen` + `ArtifactsScreen`（客户端 / 内置 Server / 产物目录，已实现） |
