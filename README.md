# Qz-Miner

Qz-Miner 是一个面向 `Minecraft 1.7.10 + Forge + GTNH` 环境的连锁挖掘模组。

当前分支处于 `5.0` 重构阶段，重点不是恢复全部旧功能，而是先重建后续连锁系统所需的基础设施、状态层和执行边界。

## 当前状态

当前仓库已经完成的内容：

- 玩家生命周期管理基础设施已重建：`core/PlayerManager`
- 模组内部事件总线已接入：`event/*`
- 网络通信入口已恢复，并完成客户端按键状态上传：`network/NetworkMain`、`network/PacketKeyState`
- 连锁状态层骨架已建立：`chain/state/*`
- 连锁模式层骨架已建立：`chain/mode/*`
- 客户端按键监听和 HUD 提示已接回：`client/KeyListener`、`client/HudOverlay`
- 并行 Tick 分片执行框架已重建，并支持服务端/客户端 `pre/post` 四阶段窗口：`parallel/*`
- 单人退出、玩家断开等生命周期边界补钩子已接回：`mixins/*`

当前已经可以验证的行为：

- 客户端按住默认连锁键 `` ` `` / `~` 时，会向服务端同步“按下”状态
- 客户端松开该键时，会向服务端同步“松开”状态
- HUD 左下角会显示绿色“正在连锁”提示
- 服务端会为玩家维护独立的连锁按键状态
- 玩家登录、重生、切维度、断开、单人退出时，状态可以按现有骨架正确创建或清理

当前尚未恢复的内容：

- 真正的连锁挖掘执行
- 预览区域搜索与渲染结果
- 模式切换输入与模式业务映射
- 服务端 `planner -> executor` 最小闭环
- 客户端与服务端的完整权威状态同步
- 旧版复杂模式迁移（范围、作物、矿脉、交互等）

## 重构阶段进度

仓库中的重构基准文档为 `CHAIN_REFACTOR_PLAN.md`。

按该规划，当前进度可以概括为：

1. 第一阶段“状态与模式抽离”已完成主体骨架
2. 第二阶段“客户端预览最小闭环”尚未开始落地
3. 第三阶段“服务端执行最小闭环”尚未开始落地
4. 第四阶段“完整状态同步”仅完成了按键状态上传这一小部分
5. 第五阶段“旧模式迁移”尚未开始

当前代码实现与规划对应关系：

- `chain.state`：已建立 `ChainPlayerState`、`ChainClientState`、`ChainStateService`
- `chain.mode`：已建立 `ChainMode`、`ChainModeRegistry`
- `chain.client`：未建立正式模块，当前仅保留最小 HUD 提示
- `chain.planner`：未建立
- `chain.executor`：未建立
- `chain.sync`：未建立完整协议，仅有 `PacketKeyState`
- `chain.integration`：未建立独立模块，当前由 `MyMod` 和现有监听器临时接线

## 近期 Git 记录摘要

最近一段重构主线提交显示，项目目前的推进顺序与 `CHAIN_REFACTOR_PLAN.md` 基本一致：

- `83409cd`：重建并行 Tick 框架
- `496b023`：改为跨 Tick 分片推进长耗时任务
- `4435159`：增加服务端 `pre/post` 双阶段窗口
- `e4b65b0`：扩展客户端 Tick 阶段，允许客户端增量计算任务接入
- `8a02edf`：搭建 `chain` 状态层、模式层骨架，并接入按键状态同步

这意味着当前仓库的重点已经从“旧版功能维护”切换到“为新连锁系统铺底”。

## 目录说明

当前重构后的关键目录如下：

```text
src/main/java/club/heiqi/qz_miner/
├── chain/      # 新连锁系统骨架，当前已接入 state / mode
├── client/     # 客户端按键监听、HUD、配置界面
├── core/       # 玩家生命周期管理等基础模块
├── event/      # 模组内部事件系统
├── mixins/     # 生命周期边界补钩子
├── network/    # 网络入口与当前最小同步包
└── parallel/   # 并行 Tick 分片执行框架
```

此外，仓库保留了 `backupSrc/`，用于存放旧架构实现，便于后续按规划逐步迁移，而不是一次性回填。

## 开发环境

- Minecraft: `1.7.10`
- Forge: `10.13.4.1614`
- Mod ID: `qz_miner`
- Java 语法支持: `jabel`
- 构建系统: `Gradle + GTNH Convention`
- 当前分支: `5.0重构`

相关配置可见：

- `gradle.properties`
- `build.gradle.kts`

## 构建与运行

常用命令：

```powershell
.\gradlew.bat build
.\gradlew.bat runClient
.\gradlew.bat runServer
```

如果需要观察当前重构阶段的调试日志，可结合项目现有日志配置运行。

## 后续开发建议

如果继续沿当前重构路线推进，建议严格按 `CHAIN_REFACTOR_PLAN.md` 中的顺序继续：

1. 先实现 `chain.client` + `chain.planner` 的最小预览闭环
2. 再实现服务端 `ChainPlanner` + `ChainExecutor` 的最小执行闭环
3. 然后补完整状态同步
4. 最后再迁移旧版复杂模式

不要直接从 `backupSrc/` 一次性回填旧版 `Founder`、`Operator` 和 `Manager` 逻辑，否则会重新引入高耦合问题。
