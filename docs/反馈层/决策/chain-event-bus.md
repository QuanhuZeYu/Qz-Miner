# 连锁事件总线框架决策

> 控制论定位：**反馈层·决策**。记录抛弃旧会话式框架、创建新事件总线框架的架构决策与阶段规划。
> 演进记录：2026-07-05 首次创建（P1 状态机收口实机失败后，用户决断框架级重写）。

## 决策背景

P1 状态机收口重构（`refactor/chain-phase-state-machine`，转移表 + 越界收口 + I10）实机验证**仍哑火**——CHAIN 连锁 + AREA 爆破模式按住键挖方块只挖一个、无预览、滚轮正常。用户决断：**抛弃旧框架，创建新框架**，采用控制论思路研究更优秀的框架模式做连锁。

P1 分支作废（代码不合并，转移表逻辑作为新状态机的参考输入）。

## 旧框架 5 个结构性缺陷（打补丁修不好的根因）

1. **状态多副本靠网络同步维持一致**：客户端 5+ 份 volatile 镜像（chainKeyPressed/serverChainKeyPressed/serverExecuting/serverExecutionStatus/previewActive），任何字段延迟/丢包就漂移（设计层竞态源）
2. **预览锁定强耦合服务端同步态**：`shouldLockCurrentPreview` 依赖 `serverExecutionStatus`，服务端卡死→客户端预览锁死（"无预览"根因）
3. **触发是"边沿"非"电平"**：破坏事件 + 跨包按键标志位双触发，时序竞态（"只挖一个"根因）
4. **执行态推进源分散在三层**：P1 的"单一入口"只守赋值合法性，挡不住"合法但时机错误"的转移
5. **会话是胖容器**：执行/规划/队列/掉落全挂 ChainSession，6 条生命周期路径漏一个就污染（"慢慢坏"根因）

## 用户拍板的 6 架构决策

| 决策点 | 选择 | 理由 |
|---|---|---|
| 事件总线线程模型 | **混合**（发布跨线程入队 + 主线程独占 drain） | 天然满足 I1/I4，dispatcher 收口内化进总线，worker 无切态能力（编译期保证） |
| 状态模型粒度 | **5 态**（IDLE/ARMED/PLANNING/RUNNING/FINISHING） | ARMED 根治触发竞态，FINISHING 给 I2/I9 显式收尾态 |
| 触发模型 | **ARMED + 破坏点火** | 保留"按键+挖方块"交互但用态表达，竞态窗口消失，玩家手感不变 |
| 客户端预览模型 | **双份——逻辑侧（服务端）为主唯一真相，预览侧（客户端）为辅允许偏差** | 服务端点火后才规划+执行（避免运算/网络压力）；预览本地算响应快，允许偏差，实际执行以服务端为准 |
| ChainSession 去留 | **弱化为 generation 计数器 + 独立队列** | 根治会话胖容器纠缠 |
| 迁移策略 | **直接替换，P1 作废** | 新旧并行成本 > 收益，事件总线与旧 dispatcher 语义冲突 |

## 新框架顶层设计（控制论五层映射）

一句话立意：**服务端主线程持有唯一权威状态机；所有状态变更收敛成一条有序事件流；worker 只入队"规划事件"，主线程独占消费并驱动转移；客户端不持有独立状态，只渲染服务端下发的快照**（预览侧为辅允许偏差）。

| 层 | 设计 | 守的不变量 |
|---|---|---|
| 设定值 | 状态机合法转移表 = I10 落地；worker 只发事件不切态 = I1 编译期保证 | I1/I10 |
| 传感 | 事件总线是唯一状态观测通道；每个事件带 tick + generation；诊断只读事件流 | I4 |
| 控制律 | 事件驱动状态机（主线程 drain 事件→转移→副作用）；执行仍轮询消费队列（吞吐控速） | I1/I9 |
| 纠偏 | 看门狗：N tick 无事件推进 → 注入 WatchdogTimeout → 协作式收敛回 IDLE | I2/I7 |
| 反馈 | 事件流本身即结构化日志；状态机转移全程可回放 | — |

## 自建事件总线核心设计

- **线程模型**：发布可跨线程入队（ConcurrentLinkedQueue），消费严格主线程 drain（ServerTickEvent.START）
- **不用 MC EventBus 的原因**：MC EventBus 同步分发在调用线程直接遍历监听器，Netty 线程 post 则监听器在 Netty 线程跑（违反 I4）。自建总线把"跨线程发布"和"主线程消费"在总线内部一次性解决，dispatcher 收口职责内化
- **订阅模型**：状态机是唯一写权威订阅者，其余（预览、日志、网络下发）是只读投影
- **事件类型**：输入（ChainKeyPressed/BlockBreakObserved/ModeSwitched）+ 规划（PlanStarted/Progress/Completed/Cancelled）+ 执行/生命周期（ExecutionAdvanced/Finished/LifecycleCleanup/WatchdogTimeout）
- **与 ChainSession 的关系**：会话弱化为 generation 计数器，陈旧 generation 的规划事件被状态机丢弃，不再有需要跨 6 条生命周期路径手动清理的胖对象

## 可复用资产（宪章已验证，不动）

- 整个 traverser 层（BudgetedChainTraverser，I3）
- ParallelTickExecutor + endStage 屏障（I2/I9）
- ChainPlayerDropBuffer 掉落兜底链（I5）
- 兼容层（矿石时运 mixin、GT 线缆、反射安全，I6/I8）

## 8 阶段迁移计划

| 阶段 | 内容 | 状态 |
|---|---|---|
| 1 | 事件总线骨架（Bus + Event 基类 + 11 事件族 + 订阅 + JVM 单测） | ✅ 完成（49b13f2，9 单测 passed，reviewer 有条件通过） |
| 2 | 状态机 + 5 态枚举 `ChainPhase` + 转移表 T1-T10 + 代际陈旧判定 + 24 转移单测 + I10 入宪章 | ✅ 完成（3dcf5b5→e69dd6b，24 单测 passed，reviewer 通过放行阶段3） |
| 3 | 触发链路迁移（按键/破坏事件 → 输入事件 → ARMED 点火）+ 客户端总线 | 待开展 |
| 4 | 规划接入（现有 traverser 挂到 PlanCompleted 事件发布） | 待开展 |
| 5 | 执行接入（队列消费订阅者，保留 maxBreakPerTick 控速） | 待开展 |
| 6 | 客户端纯投影（快照下发包 + 预览订阅，双份预览模型） | 待开展 |
| 7 | 看门狗 + 生命周期收口（纠偏层） | 待开展 |
| 8 | 删除旧框架类 + 实机验证（runClient21/runServer25） | 待开展 |

## 阶段2状态机转移表（I10 落地，oracle 清单回写）

> 权威源：`NORTH_STAR.md` §5 I10 + `docs/设定值层/硬约束总目录.md` 状态机组。代码落点 `chain.statemachine.ChainStateMachine`。
> 此表供后续阶段（触发链路/规划/执行/看门狗接入）溯源，避免又只靠代码 `// T1` 注释核对。

### 5×5 合法转移矩阵

行=源态，列=目标态，单元格=触发该转移的事件；`—`=非法丢弃；`self`=幂等或陈旧 no-op。

| 源＼目标 | IDLE | ARMED | PLANNING | RUNNING | FINISHING |
|---|---|---|---|---|---|
| **IDLE** | LifecycleCleanup(self) | ChainKeyPressed(pressed=true) | — | — | — |
| **ARMED** | ChainKeyPressed(pressed=false)／ModeSwitched／LifecycleCleanup | — | BlockBreakObserved／RightClickObserved | — | — |
| **PLANNING** | PlanCancelled／LifecycleCleanup／WatchdogTimeout | — | (陈旧事件 self) | PlanCompleted(gen匹配) | — |
| **RUNNING** | WatchdogTimeout／LifecycleCleanup | — | — | (陈旧事件 self) | ExecutionFinished |
| **FINISHING** | LifecycleCleanup／WatchdogTimeout | — | — | — | (陈旧事件 self) |

### T1-T10 合法转移清单

| # | 源→目标 | 触发事件 | generation | 阶段2 publish 派生事件 |
|---|---|---|---|---|
| T1 | IDLE→ARMED | ChainKeyPressed(pressed=true) | 不变 | 无（PlanStarted 阶段4发） |
| T2 | ARMED→IDLE | ChainKeyPressed(pressed=false) | 不变 | 无 |
| T3 | ARMED→IDLE | ModeSwitched | 不变 | 无 |
| T4 | ARMED→PLANNING | BlockBreakObserved 或 RightClickObserved | **++currentGeneration** 后转移 | 无 |
| T5 | PLANNING→RUNNING | PlanCompleted（gen 匹配） | 不变 | 无（ExecutionAdvanced 阶段5发） |
| T6 | PLANNING→IDLE | PlanCancelled | 不变 | 无 |
| T7 | RUNNING→FINISHING | ExecutionFinished | 不变 | 无（收尾逻辑阶段7发 LifecycleCleanup） |
| T8 | FINISHING→IDLE | LifecycleCleanup | 不变 | 无 |
| T9 | 任意非 IDLE→IDLE（兜底） | LifecycleCleanup（任意非 IDLE 态） | 不变 | 无（I7 退出/重生/切维度统一清理） |
| T10 | PLANNING/RUNNING/FINISHING→IDLE（兜底） | WatchdogTimeout（ARMED 不纳入，无异步活性） | 不变 | 无（I2 协作式收敛兜底） |

### generation 规则

- `currentGeneration` 为 `int`（对齐 `ChainEvent.generation`），状态机私有，唯一写权威。
- `++currentGeneration` 时机：**T4 ARMED→PLANNING 点火时**（不是 IDLE→ARMED）。理由：generation 标识一次真实连锁会话，ARMED 仅待命无异步规划事件、无跨代迟到风险；真正产生迟到风险的是规划线程启动后，进入 PLANNING 瞬间 ++ 让本次规划及其后续 Plan*/Execution* 都盖新 gen。按键重按下不 ++，避免无谓膨胀。
- 代际陈旧判定**只对派生事件**（PlanCompleted/PlanCancelled/ExecutionFinished/WatchdogTimeout/LifecycleCleanup）比对：`gen < current` → 丢弃+debug；`==` → 处理；`gen > current` → 丢弃+warn（不应出现，状态机自增外部盖不出更大值）。
- 输入事件（ChainKeyPressed/BlockBreakObserved/ModeSwitched）**豁免代际判定**：ChainKeyPressed 是新会话源头，发布时还不知道新 generation；真正需陈旧判定的是上一代规划线程迟到的派生事件。

### 越界收口

非法转移（矩阵 `—`）一律**丢弃事件 + `MyMod.LOG.debug`**（含源态/would-be 目标态/事件类型/gen/playerUUID），不抛异常、不改态。与阶段1 `ChainEventBus.drain()` 的 catch 隔离语义一致（`ChainEventBus.java:105-109`）。

### 状态机类设计要点

- 类 `ChainStateMachine` 放新包 `chain.statemachine`（非 `chain.eventbus`，因为 eventbus 严格 side-agnostic 便于阶段3客户端复用，状态机是服务端权威逻辑客户端不复用）。
- `currentPhase`/`currentGeneration` 全 private，唯一写点 `applyTransition`/`onBlockBreakObserved` 内部，无任何 public setter 或 transition 入口（守 I1 唯一写权威）。
- 构造器订阅 8 个驱动事件（精确类型 `bus.subscribe`）；`PlanStarted`/`PlanProgress`/`ExecutionAdvanced` 阶段2不订阅（喂狗逻辑阶段7加，规划/执行接入阶段4/5加）。
- handler 契约：只被主线程 `drain` 调用，单线程假定无需自锁（守 I4）。
- **阶段2不 publish 任何派生事件**——状态机是转移消费者，功能订阅者发派生事件。此分工贯穿阶段4/5/7。
- `MyMod.init` 已接线：`new ChainEventBus()` → `bindMainThread(Thread.currentThread())` → `new ChainStateMachine(bus)` → `new ChainEventBusDrainer(bus).bootstrap()`（阶段2末进入实机空跑 drain，此时无 publish 点，每 tick poll 空队列零副作用）。

## P2 项（reviewer 记录，不阻断，后续阶段补）

- `drain()` 加单次处理上限，防 tick 超时（无界 while 循环）
- `mainThread` 未绑定时软校验失效，建议未绑定时也 warn
- `ChainEventBus` 改构造器注入 Logger，降低 MyMod 类耦合
- 补 mainThread 软校验/publish(null)/subscribe 顺序边界测试
- 新总线继承旧 EventBus M2（无 unsubscribe，强引用泄漏），记录后续待办

## 工程量估算

约 13 个新类、~1750 行（阶段 1 已落 15 类 ~880 行）。复用的 traverser/掉落/兼容层不计。
