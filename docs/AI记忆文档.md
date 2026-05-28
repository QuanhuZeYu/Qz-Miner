# AI记忆文档

## 项目导航

- 仓库：`Qz-Miner`
- 当前主分支：`5.0重构`
- 模组入口：`src/main/java/club/heiqi/qz_miner/MyMod.java`
- 状态服务：`src/main/java/club/heiqi/qz_miner/chain/state/ChainStateService.java`
- 连锁执行器：`src/main/java/club/heiqi/qz_miner/chain/executor/ChainExecutor.java`
- 掉落收集与释放：`src/main/java/club/heiqi/qz_miner/chain/executor/ChainDropCollector.java`
- 并行 Tick 调度：`src/main/java/club/heiqi/qz_miner/parallel/ParallelTickExecutor.java`
- 模式装配入口：`src/main/java/club/heiqi/qz_miner/chain/mode/ChainModeBootstrap.java`
- 子模式装配入口：`src/main/java/club/heiqi/qz_miner/chain/mode/ChainSubModeBootstrap.java`
- 架构重构长期说明：`CHAIN_REFACTOR_PLAN.md`

## 稳定边界

- 当前 5.0 架构以 `ChainPlayerState` 作为服务端玩家连锁权威状态入口。
- `ChainSession` 只描述单次连锁请求与运行态，不再承担玩家资产级掉落缓存职责。
- 玩家级掉落缓冲位于 `src/main/java/club/heiqi/qz_miner/chain/state/ChainPlayerDropBuffer.java`，由 `ChainPlayerState` 持有。
- `ChainRuntimeState` 只负责单次会话的规划订阅、遍历队列、待破坏队列、执行节流和匹配计数。
- `ChainSession` 仍持有 `ChainRuntimeState`，但外部核心链路优先通过 `ChainSession` 委托方法访问运行态，减少直接暴露可变状态对象。
- 掉落释放与会话生命周期已解耦：会话结束不再依赖旧 `session` 保留到掉落释放完成。
- 玩家退出、重生、切维度、克隆、单人退主菜单等生命周期事件统一通过 `ChainStateService.cleanupPlayerState(...)` 处理。
- GT 线缆替换模式的锁定 MetaTileId 会在会话替换、会话清理和玩家生命周期清理时同步移除，避免跨会话残留。
- 当前掉落兜底策略：优先释放到当前玩家位置；拿不到当前玩家时，回退到已记录的重生点或世界出生点；再失败才告警丢弃。
- 当前并行线程仍允许异步读取世界；这只是现状，不表示线程模型已经彻底安全。
- 世界写入、真实方块破坏、掉落实体生成仍在主线程逻辑中完成。
- 客户端预览会在断线和客户端世界卸载时停止并行预览任务，并释放 `ChainPreviewMeshCache` 持有的 GPU 资源。清理入口位于 `src/main/java/club/heiqi/qz_miner/client/ClientConnectionListener.java`（`FMLNetworkEvent.ClientDisconnectionFromServerEvent` + `WorldEvent.Unload` 双钩子）。
- LootGames 扫雷兼容层已改为反射可选加载；构建时不再要求编译期引入 LootGames dev 依赖，运行时若反射调用失败会自动降级停用适配器。

## 开发流程约束

- 任何代码修改不得直接在主分支上进行。
- 每次任务必须先从主分支创建独立命名分支开发，例如 `fix/...`、`refactor/...`、`docs/...`。
- 任务完成后先在独立分支完成验证与提交，再合并回主分支。
- 若后续任务继续在同一主题上追加修改，也应优先继续使用对应主题分支，而不是直接污染主分支。

## 当前模式边界

- 主模式：`CHAIN / AREA / INTERACT / SPECIAL`
- 掉落相关问题主要影响 `CHAIN` 与 `AREA`，因为它们共用 `BlockHarvestActionExecutor` 和 `HarvestDropsEvent` 聚合链路。
- `SPECIAL` 当前是异构子模式容器，不应假设其完全共享 `CHAIN / AREA` 的执行闭环。

## 当前已知重点风险

- 并行规划线程仍会直接触发部分权威状态变更，后续若继续出现时序类问题，应优先检查 `planner -> ChainStateService` 的调用链。
- 网络包处理器当前仍直接调用状态服务，后续若做线程边界收口，应统一走主线程调度入口。
- 玩家级掉落缓冲虽然已经与会话解耦，但跨生命周期兜底释放仍依赖 Minecraft 1.7.10 的重生点与世界上下文 API，后续兼容改动需谨慎验证。
- 全项目系统性风险审查已完成（42 项），严重/高风险条目详见 `docs/开发者文档/reviews/REVIEW-20260528-代码框架设计风险排查.md`。其中 S1 异步世界读取与 S2 掉落聚合释放仍是当前接受中的设计取舍，S3 网络入包防御和 S4 客户端预览 GPU 生命周期已补齐。
