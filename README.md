<div align="center">
  <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://github.com/user-attachments/assets/7f5187f2-a567-424a-8a3a-dd49ab36b943#gh-dark-mode-only">
      <source media="(prefers-color-scheme: light)" srcset="https://github.com/user-attachments/assets/7f5187f2-a567-424a-8a3a-dd49ab36b943#gh-light-mode-only">
      <img alt="QzMinerLOGO" width="256" height="256" style="display:block;margin:auto">
  </picture>
</div>

# Qz-Miner

Qz-Miner 是一个面向 `Minecraft 1.7.10 + Forge + GTNH` 环境的连锁挖掘模组。

当前分支处于 `5.0` 重构阶段。重构目标不是简单把旧代码搬回来，而是把连锁搜索、执行、预览、网络同步和状态管理拆开，逐步恢复旧功能，同时为后续扩展打好结构基础。

## 操作方式

- 按住连锁键：启用连锁挖掘/交互，并显示当前目标预览
- 按住连锁键后滚轮：切换当前主模式下的子模式
- 按住连锁键和左 Shift 后滚轮：上下切换主模式
- 每个主模式都会记住自己上一次使用的子模式，切回该主模式时会自动恢复

## 4.0 - 5.0 大更新

从 `4.0` 到当前 `5.0`，项目经历了比较彻底的一次架构重建，重点变化包括：

- 重建连锁系统状态层，区分服务端权威状态与客户端显示状态
- 重建模式系统，统一 `CHAIN / AREA / INTERACT / SPECIAL` 的模式定义、子模式注册与切换同步
- 重建服务端规划与执行闭环，将搜索和真实破坏方块分离
- 重建客户端预览系统，支持按模式复用匹配器和遍历器，并以跨 Tick 分片方式计算
- 重建并行 Tick 执行框架，长耗时搜索不再一次性压到单帧或单 tick
- 重建基础网络同步链路，连锁键、模式、执行状态、半径和匹配数可以统一同步
- 完成 `AREA` 模式、`INTERACT` 模式和作物交互模式迁移
- 为 `CHAIN` 与 `AREA` 接入宽泛矿石匹配子模式
- 为 `CHAIN` 接入伐木子模式，支持原木壳层遍历与可配置壳层层数
- 为 `AREA` 接入 `3 x 3 x 半径` 的指向性隧道子模式
- 补齐 GT / BartWorks 复杂 `TileEntity` 的方块身份判定，提升矿石和机器类方块匹配准确度
- 优化客户端预览：连锁执行期间锁定当前预览目标，并限制每 tick 预览扫描配额
- 将客户端预览配置拆分到独立 `client` 分类，并支持单独关闭预览计算与渲染
- 为 HUD、模式名称、子模式名称、按键名称接入 `lang` 国际化
- 将服务端规划、客户端预览的运行时装配统一收敛到 planner 工厂层
- 将 `ChainSession` 拆分为请求参数与运行时状态两类职责，并把聚合掉落迁入运行时状态
- 为 `SPECIAL` 接入首个子模式：`LootGames` 扫雷雷点预览
- 为 GT / BartWorks / GT++ 接入可选的矿石时运兼容，并修复对应 Mixin 启动问题
- 修复连锁执行结束时会话过早清理导致的“只破坏不掉落”问题
- 调整模式切换交互为“连锁键滚轮切子模式，连锁键 + 左 Shift + 滚轮切主模式”，并按主模式记忆最近子模式

这些改动的核心目的，是把旧版高耦合的 `Manager / Founder / Operator` 逻辑拆成更清晰的策略层，让新功能和旧模式迁移都能在统一框架下进行。

## 当前已有功能

当前分支已经具备以下能力：

- `CHAIN` 模式完整闭环：输入、规划、执行、HUD、预览已接通
- `AREA` 模式完整闭环：盒扫搜索、执行、HUD、预览已接通
- `AREA_TUNNEL`：支持按玩家视线方向生成 `3 x 3 x radius` 的指向性隧道区域
- `INTERACT` 模式完整闭环：右键触发、默认同类交互、作物交互已接通
- 统一子模式框架：主模式下可挂载多个子模式，并同步到客户端与服务端
- `CHAIN_ORE` 与 `AREA_ORE`：宽泛矿石匹配，面向 GT / BW / GT++ / 原版矿石体系
- `CHAIN_LOGGING`：伐木子模式，只匹配原木，使用壳层扩张搜索而不是默认 6 邻洪泛
- `SPECIAL_LOOTGAMES_MINESWEEPER`：对准 `LootGames` 扫雷棋盘时，通过服务端读取雷位并在客户端预览中标出扫描半径内的雷
- 复杂方块匹配：支持 `TileEntity` 参与同类判定，特别针对 GregTech 与 BartWorks 的复杂方块做了兼容
- 客户端预览：支持实时预览、分片计算、执行期间锁定目标、限制单 tick 扫描上限，并支持通过客户端配置完全关闭
- HUD 状态展示：支持显示当前模式、子模式、服务端执行状态、匹配数量与 AREA 模式区域尺寸
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
- `clientPreviewMaxRadius`：客户端最大预览半径
- `clientPreviewMaxTargets`：客户端最大预览目标数
- `enableUnlimitedOreFortune`：是否解除 GT / BW / GT++ 普通矿的时运上限
- `enableFortuneForPlacedOre`：是否允许非自然生成的 GT / BW 矿石享受时运

核心文档：

- `CHAIN_REFACTOR_PLAN.md`：当前重构规划与里程碑
- `OLD_README.md`：旧版说明文档归档
- `backupSrc/`：旧架构源码备份，供迁移旧规则时参考

开发环境：

- Minecraft: `1.7.10`
- Forge: `10.13.4.1614`
- Mod ID: `qz_miner`
- 构建系统: `Gradle + GTNH Convention`
- 当前主线: `5.0重构`

常用命令：

```powershell
.\gradlew.bat build
.\gradlew.bat runClient
.\gradlew.bat runServer
```

## 未来展望

接下来仍会继续沿 `5.0` 重构路线推进，主要方向包括：

- 继续迁移旧版复杂模式，尤其是矿脉类、特殊模式与更多兼容规则
- 继续压缩默认实现中的模式特化判断，让策略层边界更清晰
- 让调度器进一步只依赖抽象接口，而不是默认的“挖方块”路径
- 继续完善 GTNH 生态兼容，补齐更多复杂方块、矿石和特殊交互场景
- 完善客户端预览表现和性能控制，降低大范围搜索时的卡顿感
- 在新架构稳定后，再考虑扩展隧道、探矿、更多交互式搜索等能力

当前阶段最重要的目标，仍然是先把旧能力稳定迁回新架构，再在此基础上扩展新功能，而不是重新回到旧版高耦合实现。
