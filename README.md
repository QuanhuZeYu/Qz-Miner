<div align="center">
  <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://github.com/user-attachments/assets/7f5187f2-a567-424a-8a3a-dd49ab36b943#gh-dark-mode-only">
      <source media="(prefers-color-scheme: light)" srcset="https://github.com/user-attachments/assets/7f5187f2-a567-424a-8a3a-dd49ab36b943#gh-light-mode-only">
      <img alt="QzMinerLOGO" width="256" height="256" style="display:block;margin:auto">
  </picture>
</div>

# Qz-Miner

Qz-Miner 是一个面向 `Minecraft 1.7.10 + Forge + GTNH` 环境的连锁挖掘模组。

当前版本已经提供 `CHAIN / AREA / INTERACT / SPECIAL` 四类主模式，并支持客户端预览、HUD 状态显示、模式切换记忆和多种 GTNH 生态兼容逻辑。

## 快速上手

### 安装依赖

- Qz-Miner 5.3.2 起要求 Qz-UILib `>=4.10.0,<5.0.0`（`@Mod` 依赖声明为 `required-after:qz_uilib@[4.10.0,5.0.0)`）；发布包不内嵌 UILib，运行时由 modpack 提供。
- 开发与构建细节见 [docs/README.md](docs/README.md)；对外兼容边界见 [docs/开发者文档/README.md](docs/开发者文档/README.md)。

### 基本操作

- 默认按键为 ``~`` 所在键位，可在 Minecraft 按键设置中修改。
- 按住连锁键：启用当前模式，并显示当前目标预览；按住连锁键后滚轮：切换当前主模式下的子模式；按住连锁键与游戏设置中的潜行键后滚轮：上下切换主模式。
- 该组合键独占滚轮，不改变快捷栏选中槽；未按连锁键或打开界面时保留原版滚轮行为。
- 每个主模式都会记住自己上一次使用的子模式，切回该主模式时会自动恢复。
- 连锁状态由 Qz-UILib 的 HUD 虚拟窗口在屏幕左上角以液态玻璃卡片统一显示，内容随状态变化刷新、空状态整窗隐藏；关闭态不挂常驻工具栏，打开 GUI 时自动隐藏。
- 打开聊天输入框后，工具栏的「编辑 HUD」按钮进入 UILib 布局编辑子模式：拖动连锁状态 HUD 预览调整屏幕位置，缩放 `- / 1:1 / +` 也在该编辑态统一提供；取消/Esc 放弃本次修改；提交后布局由 UILib 持久化到配置目录下的纯文本文件。
- 登录、重生、切维度、退出等场景会清理连锁状态。

### 主模式与子模式

- `CHAIN`：以当前目标为起点做邻近连锁，适合常规挖掘、矿石和伐木
  - `CHAIN_BASE`：默认连锁挖掘，仅连锁同类方块；同类判定支持 `TileEntity` 参与，对 GregTech / BartWorks 复杂方块兼容
  - `CHAIN_ORE`：宽泛矿石匹配，支持原版与 GT / BW / GT++ / AE 常见矿石体系
  - `CHAIN_LOGGING`：伐木模式，只匹配原木，按壳层方式向外扩张
- `AREA`：按范围盒扫收集目标，适合平面清理、矿区切面和隧道开掘
  - `AREA_SAME_BLOCK`：范围内仅处理同类方块
  - `AREA_HARVESTABLE_ALL`：范围内处理所有当前可收获方块
  - `AREA_ORE`：范围内按宽泛矿石匹配
  - `AREA_TUNNEL`：按配置选择视线主轴或命中面朝方块内部，生成 `3 x 3 x radius` 的指向性隧道区域
  - `AREA_SECTION_CLEAR`：按被挖方块所在的 `16 x 16 x 16` 区段生成固定清理区域
  - `AREA_CUBOID_CLEAR`：松开连锁键后左键选择 point1、右键选择 point2，按住连锁键再左键任意可破坏方块即可触发；清理服务端确认的 inclusive 立方体
- `INTERACT`（范围交互）：以宽泛右键为统一入口，在完整立方范围内盒扫目标，并按子模式连续执行正常右键语义
  - `INTERACT_BASE`：同类方块；严格匹配触发方块的 block、完整 metadata 与方块实体身份，并保留旧对象组扩展
  - `INTERACT_LIQUID_SOURCE`：液体源；只处理与触发 source 同种且当前仍可排出的静态液体源；是否处理流体由物品自身决定
  - `INTERACT_CROP`：全部作物；处理已可靠识别的成熟和未成熟作物，并保留旧对象组扩展
  - `INTERACT_FERTILIZE_IMMATURE_CROP`：未成熟作物施肥；只处理当前可靠确认仍未成熟的作物
- `SPECIAL`：特定模组兼容逻辑
  - `SPECIAL_LOOTGAMES_MINESWEEPER`：对准 LootGames 扫雷棋盘时，通过服务端读取雷位并在客户端标记
  - `SPECIAL_GT_CABLE_REPLACE`：对准 GT 线缆时，连续替换同类连通线缆

## 关键配置与限制

- `chainRadius`：连锁搜索半径；`chainMaxBlocks`：最大连锁数量；`chainLoggingShellLayers`：`CHAIN` 伐木子模式每次向外扩张的壳层数。
- `tickBudgetMs`：planning、客户端 preview 与非 GT 普通执行共享的每 Tick soft deadline，默认 `15`，合法范围 `1..40`；不承诺硬实时中断。GT 线缆替换在预校验通过后仍单 tick 原子执行。
- `clientEnablePreviewRender`：是否启用客户端预览计算与渲染；`clientPreviewMaxRadius` / `clientPreviewMaxTargets`：客户端最大预览半径与预览目标数。客户端卡顿明显时可关闭预览，或调低这两项。
- `tunnelDirectionSource`：`AREA_TUNNEL` 的方向来源；`look_direction` 沿视线主轴，`hit_face` 沿命中面朝方块内部，默认 `look_direction`。
- `enableUnlimitedOreFortune`：是否解除 GT / BW / GT++ 普通矿的时运上限；`enableFortuneForPlacedOre`：是否允许非自然生成的 GT / BW 矿石享受时运。
- 服务端配置命令：权限等级 4 的 `/qzminer config list|get|set|reload` 可受限读写 `general.*` scalar 白名单并热发布。
- 对象组（`client.objectGroups`）是现有模式的筛选扩展，不是独立滚轮模式，其完整语义见 [docs/使用文档/README.md](docs/使用文档/README.md)。
- 范围扫描与执行边界：`INTERACT` 四个子模式与 `AREA` 范围子模式使用以触发点为中心、边长 `2 x radius + 1` 的完整立方盒扫；规划结果不是执行授权，服务端主线程在每个目标执行前重新校验世界、目标身份与编辑权限，单目标失败只结算该目标。
- 本地自动化不能替代真实模组运行态；尚未验证的组合集中列在 [使用文档](docs/使用文档/README.md) 的「验证边界」。

## 版本兼容边界

### 5.3 联机

- `5.3.x` 客户端与服务端只要版本字符串完整合法，就忽略 patch、prerelease 与 build qualifier 互通；stable、prerelease、branch/dirty dev 均适用。
- `5.0.x`、`5.1.x`、`5.2.x`、`5.10.x` 与畸形版本不会被当成 5.3；5.3 family 内的 packet ID/Side、wire framing、协议与配置 schema 已冻结，不兼容变更必须升级新 minor。
- 远端模组表缺少 `qz_miner` 时 Forge checker 在 CLIENT/SERVER 两侧都会放行，但这只表示不由 mod-list 检查拒绝；它不会为无 Qz-Miner 对端创建网络 channel，也不是无 Mod 运行安全保证。
- 当前真实 5.3 mixed-patch / missing client 与 dedicated server 运行态仍为 **INCOMPLETE**；本地测试或 branch CI 不能替代实机证据。

完整合同（版本 family 判定、已冻结面、GTNH 基线）见 [docs/使用文档/README.md](docs/使用文档/README.md) 的「5.3 联机版本边界」。

### 从旧版本升级

从 `4.0` 到当前 `5.3`，模组做过一次较大的重构。对使用者来说，主要变化是：模式更多（补齐 `AREA`、`INTERACT` 与作物交互，并接入宽泛矿石、伐木、隧道等子模式），客户端预览更稳定（执行期间锁定预览目标，配置拆到独立 `client` 分类并可单独关闭），模式切换交互统一为滚轮组合并按主模式记忆最近子模式，HUD、模式名、子模式名与按键名接入 `lang` 国际化，GT / BW / GT++ 可选的矿石时运兼容更完整。

## 文档导航

- [文档分区与职责分工](docs/README.md)
- [使用文档](docs/使用文档/README.md)：配置项语义与 5.3 联机版本边界
- [公共接口与兼容边界](docs/开发者文档/公共接口与兼容边界.md)：对外开放边界与变更处理
- [踩坑记录](docs/反馈层/errors/)
- [协作规范](AGENTS.md)
