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
| 3 | 触发链路迁移（按键/破坏事件 → 输入事件 → ARMED 点火）+ 客户端总线 | ✅ 完成（0fd61a6，29 单测 passed，reviewer 通过放行阶段4） |
| 4 | 规划接入（影子 traverser 挂到 PlanStarted→PlanCompleted 事件链） | 🔄 实施中（阶段4：A-shadow/B3/C3/D 落地，PlanStarted 进态广播 + ChainPlanningEventBridge 影子双 worker） |
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
- `currentPhase`/`currentGeneration` 全 private，phase 与 generation 均唯一写在 `applyTransition`，无任何 public setter 或 transition 入口（守 I1/I10 唯一写权威）。
- 构造器订阅 9 个驱动事件（精确类型 `bus.subscribe`，含 `ChainKeyPressed`/`BlockBreakObserved`/`RightClickObserved`/`ModeSwitched`/`PlanCompleted`/`PlanCancelled`/`ExecutionFinished`/`WatchdogTimeout`/`LifecycleCleanup`）；`PlanStarted`/`PlanProgress`/`ExecutionAdvanced` 阶段2不订阅（喂狗逻辑阶段7加，规划/执行接入阶段4/5加）。
- handler 契约：只被主线程 `drain` 调用，单线程假定无需自锁（守 I4）。
- **阶段2不 publish 任何派生事件**——状态机是转移消费者，功能订阅者发派生事件。此分工贯穿阶段4/5/7。
  （阶段4 起 T4 转移后 publish `PlanStarted` 作为进态广播，供 `ChainPlanningEventBridge` 拿 gen+上下文发起影子 traverser；其余派生事件仍由功能订阅者发。）
- `MyMod.init` 已接线：`new ChainEventBus()` → `bindMainThread(Thread.currentThread())` → `new ChainStateMachine(bus)` → `new ChainEventBusDrainer(bus).bootstrap()`（阶段2末进入实机空跑 drain，此时无 publish 点，每 tick poll 空队列零副作用）。

## P2 项（reviewer 记录，不阻断，后续阶段补）

### 阶段1 遗留 P2

- `drain()` 加单次处理上限，防 tick 超时（无界 while 循环）
- `mainThread` 未绑定时软校验失效，建议未绑定时也 warn
- `ChainEventBus` 改构造器注入 Logger，降低 MyMod 类耦合
- 补 mainThread 软校验/publish(null)/subscribe 顺序边界测试
- 新总线继承旧 EventBus M2（无 unsubscribe，强引用泄漏），记录后续待办

### 阶段3 reviewer P2

- **P2-A**：补 `BlockBreakObserved` 与 `RightClickObserved` 在 PLANNING/RUNNING/FINISHING 三个态的越界丢弃单测（3-6 用例），阶段2 越界覆盖集中在 IDLE，三态丢弃对称性需补强
- **P2-B**：阶段7 看门狗/生命周期收口为 `ChainStateMachine.slots` 增加 `slots.remove(uuid)` 防止玩家登出后槽永驻 HashMap；走 `LifecycleCleanup` 事件或 `ChainStateService.cleanupPlayerState` 钩子，不能直接给状态机加外部 `clear(uuid)` 入口绕唯一写权威（违 I7/I10）；建议发 `SlotReleased` 内部事件或暴露 package-private `releaseSlot(uuid)` 仅状态机自调用
- **P2-C**：影子并行期 `ChainStateService` 与 `ChainStateMachine` 双状态系统并存（`chainKeyPressed`/`executing` 字段语义 vs `phase`/`gen` 字段语义），阶段8 旧链路下线前在决策文档登记双状态漂移观察项，避免阶段4/5 接入 traverser 时误读其中之一作权威源
- **P2-D**：`ClientProxy.java:32` 锚定 `MyMod.clientChainEventBus` 在客户端运行的 `MyMod.init` 同时也把服务端 `chainEventBus` 锚到客户端主线程（`MyMod.java:108`），因 `ChainEventBusDrainer` 订阅 ServerTickEvent 在客户端不触发，软校验锚设置无害但语义不清，阶段6 客户端预览接入时一并整理

### 阶段4 reviewer P1/P2

- **P1-1（已收口）**：`NORTH_STAR.md` §8 偏离登记 scope 段已补具体回填行号 `AbstractFloodFillPlanningStrategy.java:143,157`（CHAIN/INTERACT）+ `BlockBoxScanPlanningStrategy.java:152,168`（AREA），便于阶段8 精确定位
- **P1-2（阶段5 执行接入启动前必收口）**：`ChainPlanningEventBridge.java:147` `MyMod.ensureParallelTickExecutor().registerPre(...)` 未 catch `RejectedExecutionException`；worker pool 20 槽（`ParallelTickExecutor.java:37`）满时状态机已进 PLANNING（gen 已自增）但影子 worker 未注册成功，不会 publish `PlanCompleted`/`PlanCancelled`，此代际卡 PLANNING 直到阶段7 看门狗兜底。现状缓解：`ChainEventBus.drain` 的 subscriber catch（`ChainEventBus.java:105-109`）吞异常并 warn，不会让 drain 崩；20 槽对常规服容量充足。阶段5 启动前在 bridge `:147` 补 try/catch，失败时主动 `bus.publish(buildPlanCancelled(playerUUID, planningGen, ..., "shadow-pool-exhausted"))` 让状态机干净回 IDLE
- **P2-1**：`ChainPlanningEventBridgeTest.java:18-19` 已注释标注 worker 真链路依赖 worldObj/player/session 运行时装配，留 runClient21/runServer25；可后续用 mock ChainEventBus + stub 验证 onPlanStarted 的 5 条短路路径，优先级低
- **P2-2**：worker publish PlanCompleted（`:222`）后 return COMPLETED（`:225`），ParallelTickExecutor 自注销与事件 drain 时序设计正确，worker 完成自注销无泄漏，无需改动，仅记录

## 工程量估算

约 13 个新类、~1750 行（阶段 1 已落 15 类 ~880 行）。复用的 traverser/掉落/兼容层不计。

## 阶段4 规划接入决策（2026-07-05，A-shadow/B3/C3/D 四分歧拍板）

### 四架构分歧与用户裁决

| 分歧点 | 选项 | 用户裁决 | 理由 |
|---|---|---|---|
| A. 新链路 worker 与旧 worker 关系 | A-shadow（影子双 worker）/ A-replace（直接替换） | **A-shadow** | 影子并行期新旧 worker 共存，新链路 worker（`ChainPlanningEventBridge`）只读世界+只 publish 不切态；旧 worker 保留驱动执行直到阶段 8 下线。代价是 CPU 翻倍（阶段 8 消失） |
| B. PlanStarted 由谁 publish | B1（bridge 发）/ B2（planner 发）/ B3（状态机 T4 后发） | **B3** | 状态机 publish PlanStarted 是"我已进 PLANNING"的进态广播，不是外部改态（守 I10）；bridge 拿 gen+上下文发起 traverser，避免 bridge 反向耦合状态机内部 generation |
| C. GT 线缆 planner 接入时机 | C1（阶段4 接）/ C2（删）/ C3（推迟） | **C3** | `GregTechCableReplacePlanner` 阶段4 不动，决策文档登记缺口，留阶段 5+ 单独处理（GT 线缆刷新跟随上游 API，I7/信条七） |
| D. 旧 worker I1 偏离处理 | D1（立即修）/ D（登记偏离保留） | **D** | 旧 worker 切态能力阶段 4-7 必须保留（旧链路驱动执行依赖），按 NORTH_STAR §8 修订纪律显式登记偏离，阶段 8 删旧链路时回填 |

### oracle 两个致命卡点结论（gen 传递链根基）

1. **gen 跨包不可见**：`ChainStateMachine.currentGeneration` 是 private 字段、`slots` 私有容器，bridge 跨包无法实时读。解法：gen 经 `PlanStarted` 事件注入 worker 闭包（`planningGen = event.getGeneration()`），worker publish 时回填同一值。状态机收到 `PlanCompleted` 用 genCheck 判定陈旧/匹配。
2. **worker 早于状态机进 PLANNING 的时序竞态**：若 bridge 自行启动 worker 后才通知状态机进 PLANNING，worker 完成时状态机可能仍在 ARMED（gen 未自增），PlanCompleted gen 比对失败。B3（状态机 T4 转移**后** publish PlanStarted）根治：状态机先 ++gen 进 PLANNING，再广播，bridge 拿到的 gen 必然是已自增的新值。

### 影子并行边界（阶段 4-7 共存，阶段 8 消失）

- 新链路 worker（`ChainPlanningEventBridge`）守 I1：只读世界 + 只 publish，绝不 setExecutionStatus/写 session/syncPlayerState
- 旧链路 worker（`AbstractFloodFillPlanningStrategy`/`BlockBoxScanPlanningStrategy`）保留切态能力，I1 偏离登记于 NORTH_STAR §8
- P2-C 双状态漂移：bridge 只从 `ChainPlayerState` 读配置性字段（mode/subMode/radius/maxBlocks），绝不读 `executionStatus`/`isChainKeyPressed` 做决策

### PlanStarted 字段扩展（B3 完整形态）

PlanStarted 从空骨架扩为承载规划启动上下文：`x/y/z/dimensionId/sideHit/hitX/hitY/hitZ`，字段集对齐 `RightClickObserved`。破坏路径（`BlockBreakObserved`）无命中偏移，hitX/Y/Z 填 0；右键路径填实际值供 INTERACT 模式 flood fill 方向判定。

### PlanCompleted 字段对齐说明

`PlanCompleted` 的业务字段实际命名为 `totalTargets`（`getTotalTargets()`），oracle 清单中称 `confirmedCount` 是语义指代——两者同义，bridge publish 时传 `searchContext.getConfirmedCount()` 作为 `totalTargets` 值。

## 阶段5 执行接入决策（2026-07-05，E1-c/E2-a/E3-a/E4-b 四分歧拍板）

### 阶段5 入口致命卡点（oracle 探明）

**shadowQueue 断链**：阶段4 `ChainPlanningEventBridge.onPlanStarted` 的 `shadowQueue` 是方法局部变量（`bridge:144`），worker 完成后随栈帧销毁；而 `PlanCompleted` 事件只携带 `int totalTargets`（`PlanCompleted.java:13`），**不带队列引用**。推论：阶段5 的执行订阅者在当前代码下根本拿不到新链路算出的目标集合。这是阶段5 第一件必须解决的结构问题（见分歧 E1）。

### 四架构分歧与用户裁决

| 分歧点 | 选项 | 用户裁决 | 理由 |
|---|---|---|---|
| E1. 新链路目标集合如何从 worker 传到执行订阅者 | E1-a（PlanCompleted 扩字段带队列）/ E1-c（独立 Registry） | **E1-c** | 保持 PlanCompleted 事件轻量（事件是信号不是数据管道）；队列可变大对象走事件流会破坏 ChainEvent 不可变契约（阶段1 已立 ChainEventImmutabilityTest）；Registry 按 UUID+gen 领取天然复用 gen 传递链做陈旧校验，阶段8 易删 |
| E2. 阶段5 新链路执行模式 | E2-a（dry-run 不破坏）/ E2-b（真实破坏+旧链路让路） | **E2-a** | A-shadow 影子并行要求阶段5-7 新链路不破坏；真实破坏必与旧 ChainExecutor 双破坏同一方块（违 I5 掉落完整性/I1 执行态主权）；dry-run 零冲突零掉落风险，掉落仍全由旧链路产生，新链路只在日志可见 transition |
| E3. ChainSession 规划字段阶段5 是否迁移 | E3-a（不迁移，阶段8 随旧链路删）/ E3-b（部分迁移）/ E3-c（全迁移） | **E3-a** | oracle 探明 plannerSubscription/matchedTargetCount/plannerRunning/plannerCompleted 全被旧链路依赖（ChainExecutor:85,117 读 plannerCompleted；旧 worker 写全部），迁移=砍旧链路=违背 A-shadow。与 NORTH_STAR §8 偏离登记（旧 worker 阶段8 回填）自洽 |
| E4. 是否打通 T8 完整闭环 | E4-a（只到 T7 不碰 T8）/ E4-b（打通完整闭环） | **E4-b** | 否则玩家槽卡 FINISHING，下次按键 T1(IDLE→ARMED) 因源态非 IDLE 被丢弃，导致二次连锁哑火（极易误判为触发链路 bug）；打通后阶段5 可验证状态机跑完一整圈 IDLE→ARMED→PLANNING→RUNNING→FINISHING→IDLE |

### 阶段5 实施要点（oracle A-G 清单浓缩）

#### A. 队列消费订阅者（新建 `chain.execution.ChainExecutionEventBridge`）
- 包选 `chain.execution`（与旧 `chain.executor.ChainExecutor` 包名区隔，阶段8 删旧时防误删）
- 订阅 `PlanCompleted`（首次装载执行上下文，与状态机 T5 独立消费同一事件）+ `ServerTickEvent.START`（FML tick 驱动每 tick 消费）
- 时序主脊：worker publish PlanCompleted → 主线程 drain：状态机 T5 进 RUNNING + 执行订阅者登记 ExecutionContext → 后续每 tick START 消费（maxBreakPerTick 控速，对齐 ChainExecutor:89）→ 队列空 publish ExecutionFinished → 状态机 T7 RUNNING→FINISHING
- **不订阅 ExecutionAdvanced 驱动自己**（避免"自己发自己听"循环）；ExecutionAdvanced 是消费订阅者每 tick **对外广播的诊断产物**
- MyMod 接线顺序：状态机(:112) → 执行订阅者（保持"状态先转移"直觉；执行订阅者只登记不立刻消费，顺序不影响正确性）

#### B. ChainSession 弱化（阶段5 不动旧字段）
- 旧字段（plannerSubscription/plannerRunning/plannerCompleted/matchedTargetCount/pendingBreakTargets/nextExecutorAllowedMillis）定义在 `ChainRuntimeState`，ChainSession 仅委托；全被旧链路依赖，阶段5 一个都不迁移
- 新链路自建独立结构 `chain.execution.ChainExecutionContext`（持有 `ConcurrentLinkedQueue<ChainTarget> targets` + `int generation` + `UUID playerUUID` + 独立节流字段），由执行订阅者按 UUID 维护 `Map<UUID, ChainExecutionContext>`
- **不塞进状态机 slots**：PlayerPhaseSlot 严格只持 phase/generation（守 I10 唯一写权威），塞队列会让状态机退化成胖容器（决策文档要根治的反模式）
- **决策文档措辞校准**：原"阶段5 开始迁移 ChainSession 规划字段"应理解为"阶段5 起新链路不再新增对旧字段依赖"（方向性），物理删除留阶段8 随旧链路一起

#### C. 执行完成 publish ExecutionFinished
- 由 `ChainExecutionEventBridge` publish（它持 ExecutionContext，唯一知道队列何时空+当前 gen）
- 触发时机：队列 `poll()` 返回 null 即空（对齐旧 ChainExecutor:94,117）；**不用 confirmedCount==brokenCount 判定**（dry-run 无 broken 计数，totalTargets 与实际入队数可能因匹配器二次过滤不等）
- **空规划边界**（卡点）：PlanCompleted 报 totalTargets=0 时，上下文队列初始即空，须在登记后立即 publish ExecutionFinished(reason="empty-plan")，不能卡 RUNNING
- gen 来源：经 `PlanCompleted.getGeneration()` 注入 ExecutionContext，publish ExecutionFinished 时回填同一 gen（gen 全程走事件流，绝不实时读状态机——阶段4 已验证的唯一根治解）
- ExecutionFinished 签名现状够用 `(playerUUID, generation, serverTick, timestampNanos, String reason)`，无需扩字段

#### D. 影子并行边界（阶段5-7 共存，阶段8 删除）
- **新链路只碰自己的 ChainExecutionContext.targets**，绝不 import `session.getPendingBreakTargets`、绝不调 `actionExecutor.execute`（代码评审逐行确认）
- 旧 worker 填 pendingBreakTargets 路径仍活着（AbstractFloodFill:122、BlockBoxScan:131），阶段5 不动
- 新链路 dry-run 不真实破坏 → 与旧 ChainExecutor 零冲突（旧独占实际掉落）
- 真实接管留阶段8

### E4-b 临时 LifecycleCleanup 桥（阶段7 回填）

阶段5 执行订阅者 publish ExecutionFinished 后，**同 tick 紧接 publish LifecycleCleanup(gen)** 走 T8 FINISHING→IDLE 回 IDLE，让状态机跑完完整闭环。

- **这是临时桥**：阶段7「看门狗+生命周期收口」会把这条临时 LifecycleCleanup 换成正规生命周期源（玩家退出/重生/切维度/I7 触发）或看门狗源
- 登记位置：本决策段（非 NORTH_STAR §8，因不破坏任何不变量——publish 不切态、状态机仍唯一写权威、LifecycleCleanup 是合法 T8 触发事件）
- 阶段7 实施时须搜索本段"临时 LifecycleCleanup 桥"定位回填点

### oracle 阶段5 致命卡点清单

1. **目标集合断链**（shadowQueue 局部变量）→ E1-c Registry 根治
2. **ExecutionFinished 的 gen 跨包不可见** → 走 PlanCompleted→ExecutionContext→ExecutionFinished 事件流（绝不像阶段4 那样实时读状态机 gen，否则被 genCheck 丢弃）
3. **双消费/双破坏** → 新链路只碰 ChainExecutionContext.targets，dry-run 不破坏
4. **FINISHING 卡死致二次连锁哑火** → E4-b 临时 LifecycleCleanup 桥规避
5. **空规划（totalTargets=0）卡 RUNNING** → 登记上下文时立即处理初始空队列
6. **gen 竞态——执行中玩家重按键触发新一代** → gen 传递链保护（迟到的旧 gen ExecutionFinished 被 genCheck 丢弃），需单测覆盖
7. **ServerTickEvent 双订阅者顺序**（旧 ChainExecutor + 新执行订阅者）→ 操作不同队列、dry-run 不冲突，仅日志交错，无功能风险

### 阶段5 测试覆盖（纯 JVM，不依赖 worldObj/player）

- `ChainExecutionContextTest`（新建）：登记后按 UUID+gen 领取正确队列、陈旧 gen 领取被拒、空队列初始即完成判定（卡点5）
- `ChainExecutionEventBridgeTest`（新建，仿 ChainPlanningEventBridgeTest）：`buildExecutionFinished(uuid,gen,tick,nanos,reason)` gen 原样回填（gen 传递链锚点）、reason 透传/null 不抛
- `ChainStateMachineTest` 扩展：T5→T7→T8 全链路、RUNNING 态收陈旧 gen ExecutionFinished 丢弃（卡点6）、gen 竞态回归（仿阶段4 genRaceStalePlanCompletedDropped）、空规划边界
- 留实机（runClient21/runServer25，阶段8）：真实 worker 产队列、真实破坏（dry-run 阶段5 本就不破坏）、maxBreakPerTick 控速手感、双链路共存无双挖

### 工程量估算

阶段5 约 **3 个新类**（`ChainExecutionEventBridge` 执行订阅者 + `ChainExecutionContext` 执行上下文 + `ChainExecutionContextRegistry` 跨线程注册表）+ bridge 微调（worker 完成时写 registry）+ MyMod 接线 + 2 个新单测类 + 扩 `ChainStateMachineTest` 若干用例，**约 350-450 行**（不含单测）。核心复杂度集中在 E1-c 的目标断链解法与 gen 传递链延续。

## 阶段6 客户端纯投影决策（2026-07-06，P0-1/P1-1/P1-2/P2-1/P2-D 五决策拍板）

### 阶段6 致命卡点（oracle 探明）

1. **G1 结构入口卡点：状态机转移无对外广播钩子**——`ChainStateMachine.applyTransition` 是唯一写点，改 slot 后只打日志不 publish（仅 T4 路径在 applyTransition 外单独 publish PlanStarted）。`ChainEventBus.drain` 按 `event.getClass()` 精确匹配，订阅基类兜不住。→ 根治：applyTransition 末尾 publish 新事件 `ChainPhaseChanged`（延续阶段4 B3 进态广播模式，守 I10）
2. **G2 影子期语义卡点：dry-run 使新 phase 瞬时闪回**——阶段5 新链路 dry-run（poll 只计数），E4-b 临时桥同 tick publish LifecycleCleanup 回 IDLE，新 phase 极短时间内跑完 PLANNING→RUNNING→FINISHING→IDLE。若投影直接驱动预览锁定/HUD 会瞬时闪烁。→ 根治（P0-1=A）：投影只可见不夺权，权威仍读旧 serverExecutionStatus，阶段8 才切换

### 五架构分歧与用户裁决

| 分歧 | 裁决 | 理由 |
|---|---|---|
| P0-1 投影是否夺权 | **A 不夺权（仅可见）** | G2 证明 dry-run 下新 phase 瞬时闪回，夺权致预览几乎不锁定、HUD 闪烁；与 A-shadow 影子边界自洽；阶段8 旧链路下线才切换权威 |
| P1-1 客户端收口路径 | **A 走 clientChainEventBus** | 兑现阶段3 空跑骨架既定用途（ClientChainEventBusDrainer 注释"预览订阅留阶段6 接入"），与决策文档"dispatcher 收口内化进总线"一致 |
| P1-2 投影容器粒度 | **A 单玩家** | 客户端进程只渲染本地玩家，收不到别人快照（服务端只 sendTo 本人），per-player Map 冗余 |
| P2-1 ChainPhaseChanged 字段 | **A 携带 from+to** | 诊断成本低，事件流即结构化日志；客户端投影用 to，from 供日志/回放 |
| P2-D 服务端 bus 锚点 | **B 挪到 serverStarting** | 服务端 bus drain 在服务器线程，锚点应对齐 drain 线程；init 在客户端主线程致单人模式软校验 warn 刷屏；serverStarting 每次开服触发，bindMainThread 幂等 |

### 阶段6 实施要点

#### G1 根治（ChainPhaseChanged 进态广播）
- 新建 `chain.eventbus.event.ChainPhaseChanged`（不可变，from/to 双 ordinal，守阶段1 ChainEventImmutabilityTest 契约）
- `ChainStateMachine.applyTransition` 末尾 publish（9 处 applyTransition 调用全覆盖；T4 路径 PlanStarted 与 ChainPhaseChanged 订阅集互不重叠——前者 ChainPlanningEventBridge，后者 ChainStateProjectionBridge，并行不冲突）

#### A1 快照下发主线（服务端→客户端）
- 新建 `network.PacketChainPhaseSnapshot`（Side.CLIENT，IMessage 模式对齐 PacketChainStateSync）
- 新建 `chain.state.projection.ChainStateProjectionBridge`（服务端订阅者，只 sendTo 守 I1）
- 唯一发送点钩在 `ChainPhaseChanged`（一条 phase 变化对应一次快照，语义单一）
- `NetworkMain` 注册新包；`MyMod.init` 接线 projectionBridge

#### A2 投影侧主线（客户端本地）
- 新建 `chain.client.projection.ClientPhaseProjection`（单玩家容器，volatile 字段 + gen 陈旧判定 + clear）
- 新建 `chain.client.projection.ClientPhaseProjectionSubscriber`（订阅 clientChainEventBus 上的 ChainPhaseChanged，复用类型作投影事件）
- `ClientProxy.handleClientChainPhaseSnapshot`：Netty 线程只 publish 到 clientChainEventBus（不直接改容器，守 I4）
- 链路：Netty 线程 → clientChainEventBus 队列 → ClientTickEvent.START drain（主线程）→ 订阅者 → update 容器

#### B P2-D 整理（bindMainThread 挪 serverStarting）
- `MyMod.init` 移除 bindMainThread（保留实例化）；`MyMod.serverStarting` 加 bindMainThread（null 检查 + 服务器线程执行）
- 客户端 bus（ClientProxy.init）不动；消除单人模式软校验 warn，语义清晰

#### C 旧入口关系裁决
- `handleClientChainStateSync`（8 字段）：**保留到阶段8**（HUD/预览锁定/预览范围阶段6-7 仍依赖旧链路真实态）
- `handleClientLootGamesMinesweeperPreview`：**长期保留**（扫雷雷坐标是预览侧 remote 数据源，非连锁转移，与新 phase 快照正交）
- 新投影另立容器 `ClientPhaseProjection`，**不污染旧 ChainClientState**（避免双状态漂移加剧，阶段8 旧字段随旧包删）

### G2 不夺权铁律（影子期特有，阶段6-7 守，阶段8 切换）
- 严禁改动：`ChainPreviewController.shouldLockCurrentPreview`（仍读旧 serverExecutionStatus）/ `HudOverlay` 权威字段 / `handleClientChainStateSync` 八字段逻辑
- 允许：投影容器可见（日志 debug + 可选诊断 HUD 行，不动主 HUD 权威字段）
- 阶段8 旧链路下线时，新 ChainPhase 投影接管权威才切换读取源

### 阶段6 测试覆盖（纯 JVM，3 新测类 12 用例）

- `ChainPhaseChangedTest`（4 用例）：构造字段透传 + ordinal 5×5 往返 + final 修饰符 + 边界 IDLE→IDLE gen=0
- `ClientPhaseProjectionTest`（5 用例）：初值 + 正常写入 + **gen 陈旧丢弃**（真测）+ 同代幂等覆盖 + clear 重置
- `ChainStateProjectionBridgeTest`（3 用例）：null-path 跳过分支（networkMain/playerManager JVM 环境为 null，sendTo 真链路留 runClient21）
- `ChainEventImmutabilityTest` 扩展：登记 ChainPhaseChanged 入反射测试集
- 留实机（runClient21，阶段8）：跨进程快照真实下发 + 单人模式无软校验 warn + 旧链路 HUD/预览不受影响 + I7 退出清理

### reviewer P2 项登记

- **P2-1（阶段6 已收口）**：ClientProxy 在 Netty 线程读 projection.getCurrentPhase() 取 from（I4 灰区 + from 死代码）→ 删 from 读取，publish 时 from 占位 IDLE
- **P2-2（不修，风险极低）**：bindMainThread 时序窗口（init bootstrap → serverStarting 之间无软校验，这段时间通常无 publish，玩家未进服）
- **P2-3（文档级）**：ChainPhaseChangedTest 测试描述"反射改 final 抛异常"实际测的是 final 修饰符（轻量有效，与 ChainEventImmutabilityTest 风格一致）
- **P2-4（留阶段8）**：缺"G2 不夺权"回归断言，留阶段8 切换权威源时重写测试一并处理

### 工程量估算

阶段6 约 **5 个新类**（ChainPhaseChanged 进态广播事件 + PacketChainPhaseSnapshot 快照下发包 + ChainStateProjectionBridge 服务端订阅者 + ClientPhaseProjection 客户端容器 + ClientPhaseProjectionSubscriber 客户端订阅者）+ 7 改动文件 + 3 新单测类 12 用例，**约 400-500 行**（不含单测）。核心复杂度集中在 G1 进态广播钩子与 I4 主线程收口链路设计。
