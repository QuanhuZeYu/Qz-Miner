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

- Qz-Miner 5.3 要求 Qz-UILib `>=4.7.0,<5.0.0`；开发与测试使用 `libs/qz_uilib-4.7.0-git.7+c30aeb3cc5-dev.jar`（UILib c30aeb3c 方块图标光照配套修复版）本地 devjar，不再经 JitPack 解析；发布包不内嵌 UILib

### 基本操作

- 默认按键为 ``~`` 所在键位，可在 Minecraft 按键设置中修改
- 按住连锁键：启用当前模式，并显示当前目标预览
- 按住连锁键后滚轮：切换当前主模式下的子模式
- 按住连锁键和左 Shift 后滚轮：上下切换主模式
- 每个主模式都会记住自己上一次使用的子模式，切回该主模式时会自动恢复
- 连锁状态由 Qz-UILib `4.6.0` 紧凑 HUD 在屏幕左上角统一显示；打开 GUI 时由 UILib 自动隐藏

### 主模式说明

- `CHAIN`：以当前目标为起点做邻近连锁，适合常规挖掘、矿石和伐木
- `AREA`：按范围盒扫收集目标，适合平面清理、矿区切面和隧道开掘
- `INTERACT`（范围交互）：以宽泛右键为统一入口，在完整立方范围内盒扫目标，并按子模式连续执行正常右键语义
- `SPECIAL`：放特定模组兼容逻辑，目前包含 LootGames 扫雷预览与 GT 线缆替换

### 当前可用子模式

- `CHAIN_BASE`：默认连锁挖掘，仅连锁同类方块
- `CHAIN_ORE`：宽泛矿石匹配，支持原版与 GT / BW / GT++ / AE 常见矿石体系
- `CHAIN_LOGGING`：伐木模式，只匹配原木，按壳层方式向外扩张
- `AREA_SAME_BLOCK`：范围内仅处理同类方块
- `AREA_HARVESTABLE_ALL`：范围内处理所有当前可收获方块
- `AREA_ORE`：范围内按宽泛矿石匹配
- `AREA_TUNNEL`：按配置选择视线主轴或命中面朝方块内部，生成 `3 x 3 x radius` 的指向性隧道区域
- `AREA_SECTION_CLEAR`：按被挖方块所在的 `16 x 16 x 16` 区段生成固定清理区域
- `AREA_CUBOID_CLEAR`：用左右键选择两个端点，并清理服务端确认的 inclusive 立方体
- `INTERACT_BASE`：同类方块；严格匹配触发方块的 block、完整 metadata 与方块实体身份，并保留旧对象组扩展
- `INTERACT_LIQUID_SOURCE`：液体源；只处理与触发 source 同种且当前仍可排出的静态液体源
- `INTERACT_CROP`：全部作物；处理已可靠识别的成熟和未成熟作物，并保留旧对象组扩展
- `INTERACT_FERTILIZE_IMMATURE_CROP`：未成熟作物施肥；只处理当前可靠确认仍未成熟的作物
- `SPECIAL_LOOTGAMES_MINESWEEPER`：对准 LootGames 扫雷棋盘时，通过服务端读取雷位并在客户端标记
- `SPECIAL_GT_CABLE_REPLACE`：对准 GT 线缆时，连续替换同类连通线缆

### 使用建议

- 想快速挖一片同类方块时，用 `CHAIN_BASE` 或 `AREA_SAME_BLOCK`
- 想清矿脉时，用 `CHAIN_ORE` 或 `AREA_ORE`
- 想砍树时，用 `CHAIN_LOGGING`
- 想开矿道时，用 `AREA_TUNNEL`
- 想按 `16 x 16 x 16` 的固定区段整体清理时，用 `AREA_SECTION_CLEAR`
- 想精确清理任意长方体时，用 `AREA_CUBOID_CLEAR`；松开连锁键后左键选择 point1、右键选择 point2，按住连锁键再左键任意可破坏方块即可触发
- 想批量右键收作物时，用 `INTERACT_CROP`；想只给未成熟作物使用当前手持肥料时，用 `INTERACT_FERTILIZE_IMMATURE_CROP`
- 想让当前手持物逐个尝试右键同种静态液体源时，用 `INTERACT_LIQUID_SOURCE`；是否处理流体由物品自身决定
- 如果客户端卡顿明显，可关闭 `clientEnablePreviewRender`，或调低 `clientPreviewMaxRadius` 与 `clientPreviewMaxTargets`

### 范围交互执行边界

- 四个范围交互子模式的服务端规划与客户端预览都使用预算化 `BoxScanTraverser`，扫描以触发点为中心、边长 `2 x radius + 1` 的完整立方范围；目标无需相邻，不匹配坐标只会被跳过，不会阻断后续空间扫描
- 四个子模式都观察方块右键与空气右键。普通方块右键保留 Forge event 目标；空气右键从动作生效前的当前射线解析目标。液体模式对两种动作都使用包含液体的射线，客户端预览也使用同一射线数学，因此标准空桶对准原版 source 的空气右键可以成为入口
- 规划结果不是执行授权。服务端主线程在每个目标执行前都会重验当前世界身份，并继续检查方块存在、世界保护与玩家编辑权限
- 每个目标都重新读取当前手持物品。普通方块和作物使用带目标坐标的 Forge 方块右键；液体源经精确目标射线进入正常 Forge 空气右键与 Item 路径，不按桶、工业单元或未知物品类型预判能力，也不直接排液、改方块、搜索背包或构造容器
- 单目标无动作、被拒绝、返回 false 或抛出异常只结算该目标，后续计划目标继续尝试；本次不为同一动作可能出现的 BLOCK/AIR 双事件建立复杂去重事务
- 对象组仍只扩展 `INTERACT_BASE` 与 `INTERACT_CROP`，不会扩展液体源或未成熟作物施肥模式
- 本地自动化不能替代真实模组运行态：vanilla bucket / GT 或 IC2 单元 / 第三方 Item / GT CropCard / EFR / 保护插件、client 与 dedicated server 的连续四模式验证仍为 **INCOMPLETE**

### 并行执行说明

- 服务端连锁规划使用有界线程池调度，默认核心线程数为 `1`，最大线程数为 `20`
- 这样做的目标是降低 Hodgepodge 对异步世界读取的重复告警噪声
- 这不是线程安全修复，只是日志降噪方案，当前仍保留异步规划读取世界的实现方式

## 版本说明

### 5.2 网络兼容

- `5.2.x` 客户端与服务端只要版本字符串完整合法，就忽略 patch、prerelease 与 build qualifier
  互通；stable、prerelease、branch/dirty dev 均适用。
- `5.0.x`、`5.1.x`、`5.10.x` 与畸形版本不会被当成 5.2；5.2 family 内的 17 个 packet ID/Side、wire
  framing、协议、ordinal/code/mask 与 23-path 配置 schema 已冻结。不兼容变更必须升级新 minor。
- 远端模组表缺少 `qz_miner` 时 Forge checker 在 CLIENT/SERVER 两侧都会放行，但这只表示不由
  mod-list 检查拒绝；它不会为无 Qz-Miner 对端创建网络 channel，也不是无 Mod 运行安全保证。
- 当前真实 5.2 mixed-patch/missing client 与 dedicated server 运行态仍为 **INCOMPLETE**；本地测试或
  branch CI 不能替代实机证据。完整合同见
  `docs/反馈层/决策/network-version-compatibility.md`。

从 `4.0` 到当前 `5.2`，模组做过一次较大的重构。对使用者来说，比较重要的变化包括：

- 完成 `AREA` 模式、`INTERACT` 模式和作物交互模式迁移
- 为 `CHAIN` 与 `AREA` 接入宽泛矿石匹配子模式
- 为 `CHAIN` 接入伐木子模式，支持原木壳层遍历与可配置壳层层数
- 为 `AREA` 接入 `3 x 3 x 半径` 的指向性隧道子模式
- 为 `AREA` 接入按被挖方块所在 `16 x 16 x 16` 区段清理的固定子模式
- 优化客户端预览：连锁执行期间锁定当前预览目标，并限制每 tick 预览扫描配额
- 将客户端预览配置拆分到独立 `client` 分类，并支持单独关闭预览计算与渲染
- 为 HUD、模式名称、子模式名称、按键名称接入 `lang` 国际化
- 为 `SPECIAL` 接入首个子模式：`LootGames` 扫雷雷点预览
- 为 GT / BartWorks / GT++ 接入可选的矿石时运兼容，并修复对应 Mixin 启动问题
- 修复连锁执行结束时会话过早清理导致的“只破坏不掉落”问题
- 调整模式切换交互为“连锁键滚轮切子模式，连锁键 + 左 Shift + 滚轮切主模式”，并按主模式记忆最近子模式

如果你是从旧版本升级上来，最直观的变化就是：模式更多、预览更稳定、切换交互更统一，且对 GTNH 常见模组的兼容更完整。

## 当前已有功能

当前分支已经具备以下能力：

- `CHAIN` 模式完整闭环：输入、规划、执行、HUD、预览已接通
- `AREA` 模式完整闭环：盒扫搜索、执行、HUD、预览已接通
- `AREA_TUNNEL`：支持按玩家偏好选择视线主轴或命中面方向，生成 `3 x 3 x radius` 的指向性隧道区域
- `AREA_SECTION_CLEAR`：支持按被挖方块所在 `16 x 16 x 16` 区段生成固定清理区域
- `AREA_CUBOID_CLEAR`：支持服务端权威双点选区、固定成本 AABB 预览与冻结选区执行
- 服务端配置命令：权限等级 4 的 `/qzminer config list|get|set|reload` 可受限读写 `general.*` scalar 白名单并热发布
- `INTERACT` 模式完整闭环：四种范围交互统一盒扫，同类方块、液体源、全部作物与未成熟作物施肥均已接通执行期重验
- 统一子模式框架：主模式下可挂载多个子模式，并同步到客户端与服务端
- `CHAIN_ORE` 与 `AREA_ORE`：宽泛矿石匹配，面向 GT / BW / GT++ / 原版矿石体系
- `CHAIN_LOGGING`：伐木子模式，只匹配原木，使用壳层扩张搜索而不是默认 6 邻洪泛
- `SPECIAL_LOOTGAMES_MINESWEEPER`：对准 `LootGames` 扫雷棋盘时，通过服务端读取雷位并在客户端预览中标出扫描半径内的雷
- 复杂方块匹配：支持 `TileEntity` 参与同类判定，特别针对 GregTech 与 BartWorks 的复杂方块做了兼容
- 客户端预览：支持实时预览、分片计算、执行期间锁定目标、限制单 tick 扫描上限，并支持通过客户端配置完全关闭
- HUD 状态展示：通过 Qz-UILib 紧凑 HUD 显示执行阶段、模式/子模式、服务端限制与匹配数量、对象组同步、预览进度及 AREA 区域尺寸
- 文本国际化：HUD、模式名、子模式名、按键名已提供 `zh_CN / en_US`
- 生命周期清理：登录、重生、切维度、退出等场景会清理连锁状态
- 并行计算与主线程写世界分离：搜索放在并行 Tick 中，真实方块破坏仍由主线程执行
- 规划装配统一：服务端规划与客户端预览共用 `ChainPlanningRuntimeFactory`
- 状态职责拆分：`ChainRequest` 保存请求参数，`ChainRuntimeState` 保存一次任务的队列、心跳、掉落、订阅等运行态
- 掉落聚合修复：执行停止后会保留聚合掉落直到真正释放，避免连锁挖掘只破坏不掉落
- 模式切换交互：已移除独立主模式切换键，改为滚轮组合切换，并为每个主模式记忆最近子模式

当前重要配置项包括：

- `chainRadius`：连锁搜索半径
- `chainMaxBlocks`：最大连锁数量
- `maxBreakPerTick`：每 tick 最大实际破坏数量
- `chainLoggingShellLayers`：`CHAIN` 伐木子模式每次向外扩张的壳层数
- `clientEnablePreviewRender`：是否启用客户端预览计算与渲染
- `tunnelDirectionSource`：`AREA_TUNNEL` 的方向来源；`look_direction` 沿视线主轴，`hit_face` 沿命中面朝方块内部，默认 `look_direction`
- `clientPreviewMaxRadius`：客户端最大预览半径
- `clientPreviewMaxTargets`：客户端最大预览目标数
- `enableUnlimitedOreFortune`：是否解除 GT / BW / GT++ 普通矿的时运上限
- `enableFortuneForPlacedOre`：是否允许非自然生成的 GT / BW 矿石享受时运

相关文档：

- 当前使用说明以本 README 为准

## 文档导航

- 项目文档总入口（控制论五层）：`docs/README.md`
- 架构宪章（设定值层）：`NORTH_STAR.md`
- 协作规范：`AGENTS.md`
